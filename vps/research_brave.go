package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"
)

// Execution authority, encrypted-credential revision hashes and URL deduplication
// are local. Only the redacted progress/history crosses workspace sync.
const braveResearchSchema = `CREATE TABLE IF NOT EXISTS research_brave_checkpoints(
 task_id TEXT PRIMARY KEY REFERENCES research_tasks(id),state_json TEXT NOT NULL CHECK(json_valid(state_json)));
CREATE TABLE IF NOT EXISTS research_brave_daily(credential_stamp TEXT NOT NULL,day TEXT NOT NULL,used INTEGER NOT NULL,
 PRIMARY KEY(credential_stamp,day));`

var errBraveWaiting = errors.New("Brave page waiting")
var errBraveCredentialChanged = errors.New("Brave credential changed; start a new run")
var errBravePageLimit = errors.New("Brave page attempt limit reached")

func validBraveCountry(country string) bool {
	// A small explicit UI set; all are supported by the upstream web-search API.
	return oneOf(country, "", "TW", "ALL", "US", "JP", "KR", "DE", "FR", "GB", "CA", "AU", "BR", "IN")
}
func braveSearchOptions(in taskInput) (pages int, country string) {
	pages, country = in.SearchPages, in.SearchCountry
	if pages == 0 {
		pages = 1
	}
	if country == "" {
		country = "TW"
	}
	return
}

type bravePageEvent struct {
	Offset     int    `json:"offset"`
	Attempt    int    `json:"attempt"`
	HTTPStatus int    `json:"http_status"`
	Kind       string `json:"kind"`
	ObservedAt int64  `json:"observed_at"`
	RetryAt    int64  `json:"retry_at,omitempty"`
}
type braveResearchCheckpoint struct {
	ID              string           `json:"id"`
	ScopeHash       string           `json:"scope_hash"`
	CredentialStamp string           `json:"credential_stamp"`
	StartedAt       int64            `json:"started_at"`
	ValidUntil      int64            `json:"valid_until"`
	PageLimit       int              `json:"page_limit"`
	Country         string           `json:"country"`
	NextOffset      int              `json:"next_offset"`
	Attempts        int              `json:"attempts"`
	Hashes          []string         `json:"hashes"`
	SeenURLs        []string         `json:"seen_urls"`
	CandidateIDs    []string         `json:"candidate_ids"`
	DuplicateHits   int              `json:"duplicate_hits"`
	SkippedHits     int              `json:"skipped_hits"`
	History         []bravePageEvent `json:"history"`
	NextAttemptAt   int64            `json:"next_attempt_at,omitempty"`
	StoppedReason   string           `json:"stopped_reason,omitempty"`
	EndReason       string           `json:"end_reason,omitempty"`
	Complete        bool             `json:"complete"`
}

func (c *braveResearchCheckpoint) progress() map[string]any {
	return map[string]any{"checkpoint_id": c.ID, "completed_pages": len(c.Hashes), "page_limit": c.PageLimit, "next_offset": c.NextOffset, "candidate_count": len(c.CandidateIDs), "duplicate_hits": c.DuplicateHits, "skipped_hits": c.SkippedHits, "country": c.Country, "started_at": c.StartedAt, "valid_until": c.ValidUntil, "next_attempt_at": c.NextAttemptAt, "stopped_reason": c.StoppedReason, "end_reason": c.EndReason, "complete": c.Complete, "history": c.History}
}

func (s *Server) braveUsage(ctx context.Context, q scheduleQuerier) (map[string]any, error) {
	day := s.scheduleTime().Format("2006-01-02")
	var shared int
	if err := q.QueryRowContext(ctx, "SELECT COALESCE((SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH' AND date=?),0)", day).Scan(&shared); err != nil {
		return nil, err
	}
	var credential *int
	var encrypted string
	err := q.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='BRAVE_SEARCH'").Scan(&encrypted)
	if err == nil {
		var n int
		if err = q.QueryRowContext(ctx, "SELECT COALESCE((SELECT used FROM research_brave_daily WHERE credential_stamp=? AND day=?),0)", contentHash([]byte(encrypted)), day).Scan(&n); err != nil {
			return nil, err
		}
		credential = &n
	} else if err != sql.ErrNoRows {
		return nil, err
	}
	return map[string]any{"day": day, "shared_used": shared, "local_daily_limit": 1000, "credential_revision_used": credential, "reset_at": nextBudgetDay(s.scheduleTime()), "source": "LOCAL_ADMISSION", "provider_remaining": nil, "tokens": nil, "currency_cost": nil}, nil
}
func loadBraveCheckpoint(ctx context.Context, q scheduleQuerier, task string) (*braveResearchCheckpoint, error) {
	var raw string
	if err := q.QueryRowContext(ctx, "SELECT state_json FROM research_brave_checkpoints WHERE task_id=?", task).Scan(&raw); err != nil {
		return nil, err
	}
	var c braveResearchCheckpoint
	if json.Unmarshal([]byte(raw), &c) != nil || !validID(c.ID) || len(c.ScopeHash) != 64 || len(c.CredentialStamp) != 64 || c.PageLimit < 1 || c.PageLimit > 10 || c.NextOffset != len(c.Hashes) || c.NextOffset > c.PageLimit || c.Attempts < 0 || c.Attempts > researchRetryLimit || len(c.SeenURLs) > 100 || len(c.CandidateIDs) > 100 || len(c.History) > 30 || !validBraveCountry(c.Country) {
		return nil, errors.New("Brave local checkpoint invalid")
	}
	return &c, nil
}
func saveBraveCheckpointTx(ctx context.Context, tx *sql.Tx, task string, c *braveResearchCheckpoint) error {
	raw, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO research_brave_checkpoints VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET state_json=excluded.state_json", task, string(raw)); err != nil {
		return err
	}
	p, _ := json.Marshal(c.progress())
	_, err = tx.ExecContext(ctx, "UPDATE research_tasks SET result_json=json_set(result_json,'$.brave_progress',json(?)) WHERE id=?", string(p), task)
	return err
}
func (s *Server) saveBraveCheckpoint(task string, c *braveResearchCheckpoint) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = saveBraveCheckpointTx(ctx, tx, task, c); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Server) braveCredential(ctx context.Context) (key, stamp string, err error) {
	var encrypted string
	if err = s.Store.DB.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='BRAVE_SEARCH'").Scan(&encrypted); err != nil {
		return "", "", errors.New("Brave search key is not configured")
	}
	key, err = s.decrypt(encrypted)
	return key, contentHash([]byte(encrypted)), err
}
func checkBraveCredential(ctx context.Context, tx *sql.Tx, c *braveResearchCheckpoint) error {
	var encrypted string
	if tx.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='BRAVE_SEARCH'").Scan(&encrypted) != nil || contentHash([]byte(encrypted)) != c.CredentialStamp {
		return errBraveCredentialChanged
	}
	return nil
}

// Reserve the attempt and every applicable meter in one transaction, before
// sending HTTP. A local refusal rolls back all counters. A lost response still
// consumes one attempt, and a crash preserves a bounded retry delay.
func (s *Server) chargeBraveRequest(ctx context.Context, task string, c *braveResearchCheckpoint) error {
	if c.Attempts >= researchRetryLimit {
		return errBravePageLimit
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = checkBraveCredential(ctx, tx, c); err != nil {
		return err
	}
	if err = s.chargeResearchNetworkTx(ctx, tx, false, braveOrigin); err != nil {
		return err
	}
	day := s.scheduleTime().Format("2006-01-02")
	result, err := tx.ExecContext(ctx, "INSERT INTO request_budgets VALUES('BRAVE_SEARCH',?,1) ON CONFLICT(name,date) DO UPDATE SET used=used+1 WHERE used<1000", day)
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return errors.New("Brave daily request budget exhausted")
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO research_brave_daily VALUES(?,?,1) ON CONFLICT(credential_stamp,day) DO UPDATE SET used=used+1", c.CredentialStamp, day); err != nil {
		return err
	}
	before := *c
	c.Attempts++
	jitter := int64([]byte(contentHash([]byte(c.ID)))[0] % 6)
	c.NextAttemptAt = s.scheduleTime().UnixMilli() + (int64(30*(1<<(c.Attempts-1)))+jitter)*1000
	if err = saveBraveCheckpointTx(ctx, tx, task, c); err == nil {
		err = tx.Commit()
	}
	if err != nil {
		*c = before
	}
	return err
}

type braveSearchItem struct {
	URL         string `json:"url"`
	Title       string `json:"title"`
	Description string `json:"description"`
}
type braveSearchPage struct {
	Query struct {
		More *bool `json:"more_results_available"`
	} `json:"query"`
	Web struct {
		Results []braveSearchItem `json:"results"`
	} `json:"web"`
}

// Atomic page receipt: no new HTTP is needed after a crash following commit,
// and no duplicate candidate/evidence can be created by replaying that page.
func (s *Server) commitBravePage(ctx context.Context, task ResearchTask, in taskInput, c *braveResearchCheckpoint, page braveSearchPage, hash, key string) error {
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = checkBraveCredential(ctx, tx, c); err != nil {
		return err
	}
	var runState string
	if err = tx.QueryRowContext(ctx, "SELECT status FROM research_runs WHERE id=?", task.RunID).Scan(&runState); err != nil {
		return err
	}
	if !oneOf(runState, "QUEUED", "RUNNING") {
		return errors.New("research run paused")
	}
	if s.scheduleTime().UnixMilli() >= c.ValidUntil {
		return errors.New("Brave checkpoint expired; start a new run")
	}
	// Recheck schedule intent/approved definitions without charging it again.
	var definition string
	var enabled, intent int
	err = tx.QueryRowContext(ctx, "SELECT s.definition_json,s.enabled,p.intent FROM research_schedule_runs p JOIN research_schedules s ON s.id=p.schedule_id WHERE p.run_id=?", task.RunID).Scan(&definition, &enabled, &intent)
	if err == nil {
		var d scheduleDefinition
		if enabled != 1 || intent != 1 {
			return errors.New("scheduled research paused")
		}
		if json.Unmarshal([]byte(definition), &d) != nil || !scheduleSourcesApproved(ctx, tx, d) {
			return errScheduledDefinitionChanged
		}
	} else if err != sql.ErrNoRows {
		return err
	}
	// Mutate a copy; a failed page commit never advances in-memory progress.
	next := *c
	next.SeenURLs = append([]string(nil), c.SeenURLs...)
	next.CandidateIDs = append([]string(nil), c.CandidateIDs...)
	next.Hashes = append([]string(nil), c.Hashes...)
	seen := map[string]bool{}
	for _, u := range c.SeenURLs {
		seen[u] = true
	}
	for _, item := range page.Web.Results {
		description := excerpt(item.Title+" "+item.Description, 3800)
		if !publicURL(item.URL) || safeText(item.URL, 4096, key) != item.URL || safeText(item.Title, 3800, key) != item.Title || safeText(description, 3800, key) != description {
			next.SkippedHits++
			continue
		}
		uHash := contentHash([]byte(item.URL))
		if seen[uHash] {
			next.DuplicateHits++
			continue
		}
		seen[uHash] = true
		next.SeenURLs = append(next.SeenURLs, uHash)
		provider := providerFromURL(item.URL)
		var candidate string
		err = tx.QueryRowContext(ctx, "SELECT id FROM research_candidates WHERE provider_id=? AND model_id='UNKNOWN' ORDER BY rowid LIMIT 1", provider).Scan(&candidate)
		if err == sql.ErrNoRows {
			candidate = newID()
			details, _ := json.Marshal(CandidateDetails{Website: item.URL, CreatedRun: task.RunID, Confidence: 0})
			if _, err = tx.ExecContext(ctx, "INSERT INTO research_candidates VALUES(?,?,?,NULL,NULL,'UNKNOWN','UNKNOWN','UNKNOWN','DISCOVERED',?,?)", candidate, provider, "UNKNOWN", now(), now()); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, "INSERT INTO research_candidate_details VALUES(?,?)", candidate, string(details)); err != nil {
				return err
			}
		} else if err != nil {
			return err
		}
		details := EvidenceDetails{RunID: task.RunID, SourceID: "BRAVE_SEARCH", Language: in.Language, Normalized: map[string]any{"query": in.Query, "title": item.Title, "country": c.Country, "offset": c.NextOffset}, Confidence: 0.2, ContentHash: hash, ParserVersion: "brave-web-v2"}
		if _, err = recordResearchEvidenceTx(ctx, tx, candidate, "SEARCH_DISCOVERY", item.URL, description, false, details); err != nil {
			return err
		}
		payload, _ := json.Marshal(taskInput{CandidateID: candidate, URL: item.URL, SourceID: "BRAVE_SEARCH", Language: in.Language})
		if _, err = tx.ExecContext(ctx, "INSERT INTO research_tasks(id,run_id,kind,dedupe,payload_json,status,created_at) VALUES(?,?,'INSPECT',?,?,'QUEUED',?) ON CONFLICT(run_id,dedupe) DO NOTHING", newID(), task.RunID, "inspect:"+provider, string(payload), now()); err != nil {
			return err
		}
		found := false
		for _, id := range next.CandidateIDs {
			if id == candidate {
				found = true
				break
			}
		}
		if !found {
			next.CandidateIDs = append(next.CandidateIDs, candidate)
		}
	}
	next.Hashes = append(next.Hashes, hash)
	next.NextOffset++
	next.Attempts = 0
	next.NextAttemptAt = 0
	if page.Query.More != nil && !*page.Query.More {
		next.Complete = true
		next.EndReason = "NO_MORE_RESULTS"
	} else if next.NextOffset >= next.PageLimit {
		next.Complete = true
		next.EndReason = "PAGE_LIMIT"
	} else if page.Query.More == nil {
		next.StoppedReason = "PAGINATION_UNAVAILABLE"
	}
	if err = saveBraveCheckpointTx(ctx, tx, task.ID, &next); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	*c = next
	return nil
}

func (s *Server) executeBraveResearchTask(ctx context.Context, task ResearchTask, in taskInput) (map[string]any, error) {
	pages, country := braveSearchOptions(in)
	if pages < 1 || pages > 10 || !validBraveCountry(country) || !researchLanguage(in.Language) || in.Query == "" || safeText(in.Query, 300) != in.Query {
		return nil, errors.New("invalid Brave search plan")
	}
	c, err := loadBraveCheckpoint(ctx, s.Store.DB, task.ID)
	if err == sql.ErrNoRows {
		var old struct {
			Progress *json.RawMessage `json:"brave_progress"`
		}
		json.Unmarshal(task.Result, &old)
		if old.Progress != nil {
			return nil, errors.New("Brave local checkpoint unavailable; start a new run")
		}
		_, stamp, e := s.braveCredential(ctx)
		if e != nil {
			return nil, e
		}
		at := s.scheduleTime().UnixMilli()
		c = &braveResearchCheckpoint{ID: newID(), ScopeHash: researchInputScope("SEARCH", in), CredentialStamp: stamp, StartedAt: at, ValidUntil: at + int64(24*time.Hour/time.Millisecond), PageLimit: pages, Country: country, Hashes: []string{}, SeenURLs: []string{}, CandidateIDs: []string{}, History: []bravePageEvent{}}
		if err = s.saveBraveCheckpoint(task.ID, c); err != nil {
			return nil, errors.New("Brave checkpoint storage unavailable")
		}
	} else if err != nil {
		return nil, errors.New("Brave checkpoint storage unavailable")
	}
	result := func() map[string]any {
		return map[string]any{"candidate_ids": c.CandidateIDs, "language": in.Language, "query": in.Query, "brave_progress": c.progress()}
	}
	stop := func(reason string, e error) (map[string]any, error) {
		c.StoppedReason = reason
		c.NextAttemptAt = 0
		if s.saveBraveCheckpoint(task.ID, c) != nil {
			return nil, errors.New("Brave checkpoint storage unavailable")
		}
		return result(), e
	}
	if c.ScopeHash != researchInputScope("SEARCH", in) || c.PageLimit != pages || c.Country != country {
		return stop("PLAN_CHANGED", errors.New("Brave plan changed; start a new run"))
	}
	if c.Complete {
		return result(), nil
	}
	if c.StoppedReason != "" {
		return result(), errors.New("Brave checkpoint stopped; start a new run")
	}
	if c.NextAttemptAt > s.scheduleTime().UnixMilli() {
		return result(), errBraveWaiting
	}
	for !c.Complete {
		if ctx.Err() != nil {
			return result(), ctx.Err()
		}
		if s.scheduleTime().UnixMilli() >= c.ValidUntil {
			return stop("CHECKPOINT_EXPIRED", errors.New("Brave checkpoint expired; start a new run"))
		}
		key, stamp, e := s.braveCredential(ctx)
		if e != nil || stamp != c.CredentialStamp {
			return stop("CREDENTIAL_CHANGED", errBraveCredentialChanged)
		}
		languages := map[string]string{"en": "en", "zh": "zh-hant", "ru": "ru", "ja": "ja", "ko": "ko", "es": "es", "pt": "pt-br", "id": "id"}
		payload, _ := json.Marshal(map[string]any{"q": in.Query, "country": country, "search_lang": languages[in.Language], "count": 10, "offset": c.NextOffset})
		request, e := http.NewRequestWithContext(ctx, "POST", braveOrigin+"/res/v1/web/search", bytes.NewReader(payload))
		if e != nil {
			return stop("INVALID_REQUEST", errors.New("invalid search request"))
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Accept", "application/json")
		request.Header.Set("X-Subscription-Token", key)
		if e = s.chargeBraveRequest(ctx, task.ID, c); e != nil {
			if errors.Is(e, errBravePageLimit) {
				return stop("ATTEMPT_LIMIT", e)
			}
			if errors.Is(e, errBraveCredentialChanged) {
				return stop("CREDENTIAL_CHANGED", e)
			}
			return result(), e
		}
		client := *s.Client
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
		admittedAt := s.scheduleTime().UnixMilli()
		resp, e := client.Do(request)
		status := 0
		var failure *researchHTTPFailure
		kind := "TRANSPORT"
		if e == nil {
			status = resp.StatusCode
			kind = "HTTP"
		}
		if e != nil || status != 200 {
			if resp != nil {
				if e == nil {
					errors.As(publicHTTPFailure(status, resp.Header, s.scheduleTime()), &failure)
				}
				resp.Body.Close()
			}
			if ctx.Err() != nil {
				return result(), ctx.Err()
			}
			if e == nil && failure == nil {
				c.History = append(c.History, bravePageEvent{Offset: c.NextOffset, Attempt: c.Attempts, HTTPStatus: status, Kind: kind, ObservedAt: s.scheduleTime().UnixMilli()})
				return stop("HTTP_REJECTED", fmt.Errorf("search HTTP %d: %s", status, classifyHTTP(status)))
			}
			if failure != nil {
				if s.recordResearchOriginFailure(braveOrigin, failure) != nil {
					return stop("COOLDOWN_STORAGE", errors.New("Brave cooldown storage unavailable"))
				}
			}
			event := bravePageEvent{Offset: c.NextOffset, Attempt: c.Attempts, HTTPStatus: status, Kind: kind, ObservedAt: s.scheduleTime().UnixMilli()}
			if failure != nil {
				c.NextAttemptAt = max(c.NextAttemptAt, failure.NotBefore)
			}
			if c.Attempts >= researchRetryLimit {
				c.StoppedReason = "ATTEMPT_LIMIT"
			} else if failure != nil && failure.WaitTooLong {
				c.StoppedReason = "REMOTE_WAIT_TOO_LONG"
			} else {
				event.RetryAt = c.NextAttemptAt
			}
			c.History = append(c.History, event)
			if c.StoppedReason != "" {
				return stop(c.StoppedReason, errors.New("Brave page recovery stopped"))
			}
			if s.saveBraveCheckpoint(task.ID, c) != nil {
				return nil, errors.New("Brave checkpoint storage unavailable")
			}
			return result(), errBraveWaiting
		}
		raw, readErr := readBounded(resp.Body, 1<<20)
		resp.Body.Close()
		if readErr != nil {
			if ctx.Err() != nil {
				return result(), ctx.Err()
			}
			if readErr.Error() == "response limit" {
				return stop("INVALID_RESPONSE", errors.New("Brave response too large"))
			}
			event := bravePageEvent{Offset: c.NextOffset, Attempt: c.Attempts, HTTPStatus: 200, Kind: "TRANSPORT", ObservedAt: s.scheduleTime().UnixMilli()}
			if c.Attempts >= researchRetryLimit {
				c.History = append(c.History, event)
				return stop("ATTEMPT_LIMIT", errors.New("Brave response interrupted"))
			}
			event.RetryAt = c.NextAttemptAt
			c.History = append(c.History, event)
			if s.saveBraveCheckpoint(task.ID, c) != nil {
				return nil, errors.New("Brave checkpoint storage unavailable")
			}
			return result(), errBraveWaiting
		}
		var page braveSearchPage
		if json.Unmarshal(raw, &page) != nil || page.Web.Results == nil || len(page.Web.Results) > 10 {
			return stop("INVALID_SCHEMA", errors.New("search schema invalid"))
		}
		if e = s.commitBravePage(ctx, task, in, c, page, contentHash(raw), key); e != nil {
			if errors.Is(e, errBraveCredentialChanged) {
				return stop("CREDENTIAL_CHANGED", e)
			}
			return result(), e
		}
		s.researchOriginSucceeded(braveOrigin, admittedAt)
		if c.StoppedReason != "" {
			return result(), errors.New("Brave pagination hint unavailable; partial evidence retained")
		}
	}
	return result(), nil
}
func (s *Server) braveResearchResult(ctx context.Context, task string, result map[string]any, status string, taskErr error) (map[string]any, string) {
	if result == nil {
		result = map[string]any{}
	}
	if c, err := loadBraveCheckpoint(ctx, s.Store.DB, task); err == nil {
		result["brave_progress"] = c.progress()
		result["candidate_ids"] = c.CandidateIDs
		if status == "FAILED" && c.NextAttemptAt > 0 && c.StoppedReason == "" {
			status = "QUEUED"
		}
	}
	return result, status
}
