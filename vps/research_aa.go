package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

// Only the public progress journal is product history. Partial pages and the
// hash of the local encrypted credential revision never cross workspace sync
// or portable backups, and cannot grant execution intent on another device.
const aaResearchSchema = `CREATE TABLE IF NOT EXISTS research_aa_checkpoints(
 task_id TEXT PRIMARY KEY REFERENCES research_tasks(id),state_json TEXT NOT NULL CHECK(json_valid(state_json)));`

type aaHTTPFailure struct{ researchHTTPFailure }

func (e *aaHTTPFailure) Error() string { return fmt.Sprintf("AA HTTP %d", e.Status) }

var errAABusy = errors.New("AA sync busy")
var errAAPageLimit = errors.New("AA page attempt limit reached")
var errAAWaiting = errors.New("AA page waiting")
var errAACredentialChanged = errors.New("AA credential changed; start a new run")
var errAAExpired = errors.New("AA partial ranking expired; start a new run")

type aaPageEvent struct {
	Page       int   `json:"page"`
	Attempt    int   `json:"attempt"`
	HTTPStatus int   `json:"http_status"`
	ObservedAt int64 `json:"observed_at"`
	RetryAt    int64 `json:"retry_at,omitempty"`
}

type aaResearchCheckpoint struct {
	Snapshot        AASnapshot    `json:"snapshot"`
	CredentialStamp string        `json:"credential_stamp"`
	NextPage        int           `json:"next_page"`
	TotalPages      int           `json:"total_pages"`
	Attempts        int           `json:"attempts"`
	Hashes          []string      `json:"hashes"`
	SeenIDs         []string      `json:"seen_ids"`
	History         []aaPageEvent `json:"history"`
	NextAttemptAt   int64         `json:"next_attempt_at,omitempty"`
	StoppedReason   string        `json:"stopped_reason,omitempty"`
	Complete        bool          `json:"complete"`
}

func (c *aaResearchCheckpoint) progress() map[string]any {
	return map[string]any{"snapshot_id": c.Snapshot.ID, "completed_pages": len(c.Hashes), "total_pages": c.TotalPages, "next_page": c.NextPage, "model_count": len(c.Snapshot.Models), "index_version": c.Snapshot.IndexVersion, "started_at": c.Snapshot.ObservedAt, "valid_until": c.Snapshot.ValidUntil, "next_attempt_at": c.NextAttemptAt, "stopped_reason": c.StoppedReason, "complete": c.Complete, "history": c.History}
}

// Validate the entire page before mutating the checkpoint. Neither a changed
// version/page count nor duplicate identities can publish a mixed leaderboard.
func (c *aaResearchCheckpoint) acceptPage(root map[string]json.RawMessage, key string) error {
	var version json.Number
	if json.Unmarshal(root["intelligence_index_version"], &version) != nil || decimal(version.String()) == nil {
		return errors.New("AA index version missing")
	}
	if c.Snapshot.IndexVersion != "" && c.Snapshot.IndexVersion != version.String() {
		return errors.New("AA versions changed between pages")
	}
	var pagination struct {
		Page       int   `json:"page"`
		TotalPages int   `json:"total_pages"`
		HasMore    *bool `json:"has_more"`
	}
	if json.Unmarshal(root["pagination"], &pagination) != nil || pagination.Page != c.NextPage || pagination.HasMore == nil || pagination.TotalPages < c.NextPage || pagination.TotalPages > 10 {
		return errors.New("AA incomplete pagination")
	}
	if *pagination.HasMore != (pagination.Page < pagination.TotalPages) || c.TotalPages != 0 && c.TotalPages != pagination.TotalPages {
		return errors.New("AA pagination conflict")
	}
	var data []struct {
		ID          string              `json:"id"`
		Slug        string              `json:"slug"`
		Name        string              `json:"name"`
		Evaluations map[string]*float64 `json:"evaluations"`
	}
	if json.Unmarshal(root["data"], &data) != nil || data == nil || len(data) > 200 {
		return errors.New("AA invalid model page")
	}
	seen := map[string]bool{}
	for _, id := range c.SeenIDs {
		seen[id] = true
	}
	models, ids := []AAModel{}, []string{}
	for _, v := range data {
		if !validID(v.ID) || !validID(v.Slug) || v.Name == "" || safeText(v.Name, 200, key) != v.Name || seen[v.ID] {
			return errors.New("AA model schema conflict")
		}
		seen[v.ID] = true
		ids = append(ids, v.ID)
		score := v.Evaluations["artificial_analysis_intelligence_index"]
		if score == nil {
			continue
		}
		if *score < 0 || *score > 100 || math.IsNaN(*score) || math.IsInf(*score, 0) {
			return errors.New("AA model schema conflict")
		}
		models = append(models, AAModel{ID: v.ID, Slug: v.Slug, Name: v.Name, Score: *score})
	}
	pageRaw, _ := json.Marshal(root)
	c.Snapshot.IndexVersion = version.String()
	c.Snapshot.Models = append(c.Snapshot.Models, models...)
	c.SeenIDs = append(c.SeenIDs, ids...)
	c.Hashes = append(c.Hashes, contentHash(pageRaw))
	c.TotalPages = pagination.TotalPages
	c.NextPage++
	c.Attempts = 0
	c.NextAttemptAt = 0
	return nil
}

func (s *Server) aaCredential(ctx context.Context) (key, stamp string, err error) {
	var encrypted string
	if err = s.Store.DB.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='AA'").Scan(&encrypted); err != nil {
		return "", "", errors.New("AA key not configured")
	}
	key, err = s.decrypt(encrypted)
	return key, contentHash([]byte(encrypted)), err
}

func loadAACheckpoint(ctx context.Context, db interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, task string) (*aaResearchCheckpoint, error) {
	var raw string
	if err := db.QueryRowContext(ctx, "SELECT state_json FROM research_aa_checkpoints WHERE task_id=?", task).Scan(&raw); err != nil {
		return nil, err
	}
	var c aaResearchCheckpoint
	if json.Unmarshal([]byte(raw), &c) != nil || !validID(c.Snapshot.ID) || c.NextPage < 1 || c.NextPage > 11 || c.TotalPages < 0 || c.TotalPages > 10 || c.NextPage != len(c.Hashes)+1 || c.TotalPages > 0 && c.NextPage > c.TotalPages+1 || len(c.Snapshot.Models) > 2000 || len(c.SeenIDs) > 2000 || len(c.History) > 30 || c.Attempts < 0 || c.Attempts > researchRetryLimit {
		return nil, errors.New("AA local checkpoint invalid")
	}
	return &c, nil
}

func saveAACheckpointTx(ctx context.Context, tx *sql.Tx, task string, c *aaResearchCheckpoint) error {
	raw, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO research_aa_checkpoints VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET state_json=excluded.state_json", task, string(raw)); err != nil {
		return err
	}
	progress, _ := json.Marshal(c.progress())
	_, err = tx.ExecContext(ctx, "UPDATE research_tasks SET result_json=json_set(result_json,'$.aa_progress',json(?)) WHERE id=?", string(progress), task)
	return err
}

func (s *Server) saveAACheckpoint(task string, c *aaResearchCheckpoint) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = saveAACheckpointTx(ctx, tx, task, c); err != nil {
		return err
	}
	return tx.Commit()
}

type aaPageRequestKey struct{}
type aaPageRequest struct {
	Task       string
	Checkpoint *aaResearchCheckpoint
}

// Reserve before the GET, including the page's crash/replay attempt. If any
// local admission check fails, both quota meters and the checkpoint roll back.
func (s *Server) chargeAARequest(ctx context.Context) error {
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	page, _ := ctx.Value(aaPageRequestKey{}).(*aaPageRequest)
	if page != nil {
		c := page.Checkpoint
		if c.Attempts >= researchRetryLimit {
			return errAAPageLimit
		}
		var encrypted string
		if tx.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='AA'").Scan(&encrypted) != nil || contentHash([]byte(encrypted)) != c.CredentialStamp {
			return errAACredentialChanged
		}
	}
	if err = s.chargeResearchNetworkTx(ctx, tx, false, ""); err != nil {
		return err
	}
	if err = s.chargeIntegrationWindowTx(ctx, tx, "AA", 100); err != nil {
		return err
	}
	if page != nil {
		page.Checkpoint.Attempts++
		if err = saveAACheckpointTx(ctx, tx, page.Task, page.Checkpoint); err != nil {
			page.Checkpoint.Attempts--
			return err
		}
	}
	err = tx.Commit()
	if err != nil && page != nil {
		page.Checkpoint.Attempts--
	}
	return err
}

func (s *Server) executeAAResearchTask(ctx context.Context, task ResearchTask) (out map[string]any, taskErr error) {
	if !s.beginFlight("AA_SYNC") {
		return nil, errAABusy
	}
	defer s.endFlight("AA_SYNC")
	var c *aaResearchCheckpoint
	// Preserve the existing incomplete-update archive, including local quota
	// refusal and cancellation. Each execution attempt has its own failed ID;
	// the candidate checkpoint ID remains available for a later VALID commit.
	// Waiting without a new admission is not another failed update attempt.
	defer func() {
		if taskErr == nil || c == nil || c.Complete || errors.Is(taskErr, errAAWaiting) {
			return
		}
		save, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		version := c.Snapshot.IndexVersion
		if version == "" {
			version = "UNKNOWN"
		}
		_, _ = s.Store.DB.ExecContext(save, "INSERT INTO aa_snapshots VALUES(?,?,'FAILED',?,?,0,'')", newID(), version, c.Snapshot.Source, s.scheduleTime().UnixMilli())
	}()
	c, err := loadAACheckpoint(ctx, s.Store.DB, task.ID)
	if err == sql.ErrNoRows {
		var old struct {
			Progress struct {
				SnapshotID string `json:"snapshot_id"`
			} `json:"aa_progress"`
		}
		json.Unmarshal(task.Result, &old)
		if old.Progress.SnapshotID != "" {
			return nil, errors.New("AA local checkpoint unavailable; start a new run")
		}
		_, stamp, keyErr := s.aaCredential(ctx)
		if keyErr != nil {
			return nil, keyErr
		}
		at := s.scheduleTime().UnixMilli()
		c = &aaResearchCheckpoint{Snapshot: AASnapshot{ID: newID(), Source: aaOrigin + aaPath, ObservedAt: at, ValidUntil: at + int64(24*time.Hour/time.Millisecond), Models: []AAModel{}}, CredentialStamp: stamp, NextPage: 1, History: []aaPageEvent{}}
		if err = s.saveAACheckpoint(task.ID, c); err != nil {
			return nil, errors.New("AA checkpoint storage unavailable")
		}
	} else if err != nil {
		return nil, errors.New("AA checkpoint storage unavailable")
	}
	result := func() map[string]any {
		return map[string]any{"snapshot_id": c.Snapshot.ID, "models": len(c.Snapshot.Models), "index_version": c.Snapshot.IndexVersion, "aa_progress": c.progress()}
	}
	if c.Complete {
		return result(), nil
	}
	stop := func(reason string, failure error) (map[string]any, error) {
		c.StoppedReason, c.NextAttemptAt = reason, 0
		if s.saveAACheckpoint(task.ID, c) != nil {
			return nil, errors.New("AA checkpoint storage unavailable")
		}
		return result(), failure
	}
	if c.StoppedReason != "" {
		return result(), errors.New("AA checkpoint stopped; start a new run")
	}
	if c.NextAttemptAt > s.scheduleTime().UnixMilli() {
		return result(), errAAWaiting
	}
	for c.TotalPages == 0 || c.NextPage <= c.TotalPages {
		if ctx.Err() != nil {
			return result(), ctx.Err()
		}
		if s.scheduleTime().UnixMilli() >= c.Snapshot.ValidUntil {
			return stop("CHECKPOINT_EXPIRED", errAAExpired)
		}
		key, stamp, e := s.aaCredential(ctx)
		if ctx.Err() != nil {
			return result(), ctx.Err()
		}
		if e != nil || stamp != c.CredentialStamp {
			return stop("CREDENTIAL_CHANGED", errAACredentialChanged)
		}
		pageCtx := context.WithValue(context.WithValue(ctx, integrationHeaderKey{}, "AA"), aaPageRequestKey{}, &aaPageRequest{task.ID, c})
		root, _, e := s.getJSON(pageCtx, Provider{BaseURL: aaOrigin, AuthMode: "API_KEY"}, ProviderConfig{AuthMode: "API_KEY", AuthHeader: "x-api-key"}, aaOrigin+aaPath+fmt.Sprintf("?page=%d", c.NextPage), key, 1<<20)
		if e != nil {
			var failure *aaHTTPFailure
			if errors.As(e, &failure) {
				at := s.scheduleTime().UnixMilli()
				event := aaPageEvent{Page: c.NextPage, Attempt: c.Attempts, HTTPStatus: failure.Status, ObservedAt: at}
				if c.Attempts >= researchRetryLimit {
					c.StoppedReason = "ATTEMPT_LIMIT"
				} else if failure.WaitTooLong {
					c.StoppedReason = "REMOTE_WAIT_TOO_LONG"
				} else {
					jitter := int64([]byte(contentHash([]byte(c.Snapshot.ID)))[0] % 6)
					event.RetryAt = max(at+(int64(30*(1<<(c.Attempts-1)))+jitter)*1000, failure.NotBefore)
					c.NextAttemptAt = event.RetryAt
				}
				c.History = append(c.History, event)
				if c.StoppedReason != "" {
					return stop(c.StoppedReason, e)
				}
				if s.saveAACheckpoint(task.ID, c) != nil {
					return nil, errors.New("AA checkpoint storage unavailable")
				}
			} else if errors.Is(e, errAAPageLimit) {
				return stop("ATTEMPT_LIMIT", e)
			} else if errors.Is(e, errAACredentialChanged) {
				return stop("CREDENTIAL_CHANGED", e)
			} else if !researchTransportStopped(ctx, e) && !strings.Contains(e.Error(), "budget") {
				return stop("REQUEST_FAILED", e)
			}
			return result(), e
		}
		if e = c.acceptPage(root, key); e != nil {
			return stop("INCONSISTENT_PAGES", e)
		}
		if s.saveAACheckpoint(task.ID, c) != nil {
			return nil, errors.New("AA checkpoint storage unavailable")
		}
	}
	if len(c.Snapshot.Models) < 4 {
		return stop("INCONSISTENT_PAGES", errors.New("AA leaderboard has fewer than four scored models"))
	}
	if err = s.commitAARanking(ctx, &c.Snapshot, c.Hashes, task.ID, c); err != nil {
		if errors.Is(err, errAACredentialChanged) {
			return stop("CREDENTIAL_CHANGED", err)
		}
		if errors.Is(err, errAAExpired) {
			return stop("CHECKPOINT_EXPIRED", err)
		}
		return result(), err
	}
	s.reconcileAllCandidates(ctx)
	return result(), nil
}

func (s *Server) aaResearchResult(ctx context.Context, task string, result map[string]any, status string, taskErr error) (map[string]any, string) {
	if result == nil {
		result = map[string]any{}
	}
	if c, err := loadAACheckpoint(ctx, s.Store.DB, task); err == nil {
		result["aa_progress"] = c.progress()
		if status == "FAILED" && c.NextAttemptAt > 0 && c.StoppedReason == "" {
			status = "QUEUED"
		}
	}
	if errors.Is(taskErr, errAABusy) {
		result["aa_progress"] = map[string]any{"next_attempt_at": s.scheduleTime().UnixMilli() + 1000, "busy": true}
		_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_tasks SET attempts=MAX(0,attempts-1) WHERE id=?", task)
		if status == "FAILED" {
			status = "QUEUED"
		}
	}
	return result, status
}

func (s *Server) commitAARanking(ctx context.Context, snapshot *AASnapshot, hashes []string, task string, checkpoint *aaResearchCheckpoint) error {
	if len(snapshot.Models) < 4 {
		return errors.New("AA leaderboard has fewer than four scored models")
	}
	sort.Slice(snapshot.Models, func(i, j int) bool {
		if snapshot.Models[i].Score == snapshot.Models[j].Score {
			return snapshot.Models[i].ID < snapshot.Models[j].ID
		}
		return snapshot.Models[i].Score > snapshot.Models[j].Score
	})
	for i := range snapshot.Models {
		snapshot.Models[i].Rank = i + 1
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if checkpoint != nil {
		var state, encrypted string
		if tx.QueryRowContext(ctx, "SELECT r.status FROM research_tasks t JOIN research_runs r ON r.id=t.run_id WHERE t.id=?", task).Scan(&state) != nil || !oneOf(state, "RUNNING", "QUEUED") {
			return errors.New("research run paused")
		}
		if tx.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='AA'").Scan(&encrypted) != nil || contentHash([]byte(encrypted)) != checkpoint.CredentialStamp {
			return errAACredentialChanged
		}
		if s.scheduleTime().UnixMilli() >= snapshot.ValidUntil {
			return errAAExpired
		}
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO aa_snapshots VALUES(?,?,'VALID',?,?,?,?)", snapshot.ID, snapshot.IndexVersion, snapshot.Source, snapshot.ObservedAt, snapshot.ValidUntil, contentHash([]byte(strings.Join(hashes, "")))); err != nil {
		return err
	}
	for _, v := range snapshot.Models {
		if _, err = tx.ExecContext(ctx, "INSERT INTO aa_models VALUES(?,?,?,?,?,?)", snapshot.ID, v.ID, v.Slug, v.Name, v.Rank, v.Score); err != nil {
			return err
		}
	}
	if checkpoint != nil {
		checkpoint.Complete = true
		if err = saveAACheckpointTx(ctx, tx, task, checkpoint); err != nil {
			checkpoint.Complete = false
			return err
		}
	}
	err = tx.Commit()
	if err != nil && checkpoint != nil {
		checkpoint.Complete = false
	}
	return err
}
