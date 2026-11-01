package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"time"
)

// Schedules, activation intent and daily reservations are local to this engine.
// They are not workspace-synchronized or restored by portable settings import.
const researchScheduleSchema = `
CREATE TABLE research_schedules(id TEXT PRIMARY KEY,name TEXT NOT NULL,revision INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 0,interval_minutes INTEGER NOT NULL,daily_http_limit INTEGER NOT NULL,definition_json TEXT NOT NULL,next_at INTEGER NOT NULL,last_run_id TEXT NOT NULL DEFAULT '',last_started_at INTEGER,last_reason TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
CREATE INDEX research_schedules_due ON research_schedules(enabled,next_at);
CREATE TABLE research_schedule_runs(run_id TEXT PRIMARY KEY REFERENCES research_runs(id),schedule_id TEXT NOT NULL REFERENCES research_schedules(id),scheduled_for INTEGER NOT NULL,revision INTEGER NOT NULL,intent INTEGER NOT NULL DEFAULT 1,resume_at INTEGER NOT NULL DEFAULT 0,continued_from TEXT NOT NULL DEFAULT '',UNIQUE(schedule_id,scheduled_for));
CREATE INDEX research_schedule_runs_owner ON research_schedule_runs(schedule_id,run_id);
CREATE TABLE research_schedule_daily(day TEXT NOT NULL,scope TEXT NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(day,scope));
`

var errScheduledDailyBudget = errors.New("research daily HTTP budget exhausted")
var errScheduledDefinitionChanged = errors.New("scheduled research sources changed; local review required")

type scheduleDefinition struct {
	Private            *schedulePrivatePolicy `json:"private,omitempty"`
	Options            ResearchOptions        `json:"options"`
	Languages          []string               `json:"languages"`
	SourcesFingerprint string                 `json:"sources_fingerprint"`
}
type researchSchedule struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	Revision        int64  `json:"revision"`
	Enabled         bool   `json:"enabled"`
	IntervalMinutes int    `json:"interval_minutes"`
	DailyHTTPLimit  int    `json:"daily_http_limit"`
	scheduleDefinition
	NextAt         int64           `json:"next_at"`
	LastRunID      string          `json:"last_run_id"`
	LastStartedAt  *int64          `json:"last_started_at"`
	LastReason     string          `json:"last_reason"`
	RunStatus      string          `json:"run_status"`
	TodayUsed      int             `json:"today_used"`
	RunUsed        int             `json:"run_used"`
	RunBudget      int             `json:"run_budget"`
	RunProbes      int             `json:"run_probes"`
	RunProbeBudget int             `json:"run_probe_budget"`
	PrivateUsage   map[string]any  `json:"private_usage,omitempty"`
	Summary        json.RawMessage `json:"summary"`
	ResumeAt       int64           `json:"resume_at"`
	AutoResume     bool            `json:"auto_resume"`
	State          string          `json:"state"`
}

func (s *Server) scheduleTime() time.Time {
	if s.scheduleClock != nil {
		return s.scheduleClock().UTC()
	}
	return time.Now().UTC()
}
func nextBudgetDay(t time.Time) int64 {
	return t.UTC().Truncate(24 * time.Hour).Add(24 * time.Hour).UnixMilli()
}

type scheduleQuerier interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func scheduleDailyLimit(ctx context.Context, q scheduleQuerier) int {
	var raw string
	limit := 100
	if q.QueryRowContext(ctx, "SELECT value_json FROM settings WHERE key='research_schedule_http_limit'").Scan(&raw) == nil {
		var n int
		if json.Unmarshal([]byte(raw), &n) == nil && n >= 1 && n <= 2000 {
			limit = n
		}
	}
	return limit
}
func scheduleUsage(ctx context.Context, q scheduleQuerier, day, scope string) int {
	var used int
	_ = q.QueryRowContext(ctx, "SELECT used FROM research_schedule_daily WHERE day=? AND scope=?", day, scope).Scan(&used)
	return used
}

// Bind automatic work to the public source definitions approved on this host.
// The hash is recomputed under the same transaction as each HTTP reservation.
func scheduleSourcesFingerprint(ctx context.Context, q scheduleQuerier, ids []string) (string, error) {
	ids = append([]string(nil), ids...)
	sort.Strings(ids)
	sources := []ResearchSource{}
	for _, id := range ids {
		var src ResearchSource
		var raw string
		if err := q.QueryRowContext(ctx, "SELECT id,name,kind,url,language,enabled,config_json FROM research_sources WHERE id=?", id).Scan(&src.ID, &src.Name, &src.Kind, &src.URL, &src.Language, &src.Enabled, &raw); err != nil {
			return "", err
		}
		if json.Unmarshal([]byte(raw), &src.Config) != nil || !src.Enabled || !validSource(src) {
			return "", errScheduledDefinitionChanged
		}
		sources = append(sources, src)
	}
	raw, _ := json.Marshal(sources)
	return contentHash(raw), nil
}
func scheduleSourcesApproved(ctx context.Context, q scheduleQuerier, d scheduleDefinition) bool {
	fingerprint, err := scheduleSourcesFingerprint(ctx, q, d.Options.SourceIDs)
	return err == nil && d.SourcesFingerprint != "" && fingerprint == d.SourcesFingerprint
}

func (s *Server) scheduledNetworkCharge(ctx context.Context, tx *sql.Tx, run string, probe bool) error {
	var id string
	var enabled, intent, daily int
	var definition string
	err := tx.QueryRowContext(ctx, "SELECT s.id,s.enabled,r.intent,s.daily_http_limit,s.definition_json FROM research_schedule_runs r JOIN research_schedules s ON s.id=r.schedule_id WHERE r.run_id=?", run).Scan(&id, &enabled, &intent, &daily, &definition)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	if enabled != 1 || intent != 1 {
		return errors.New("scheduled research paused")
	}
	var d scheduleDefinition
	if json.Unmarshal([]byte(definition), &d) != nil {
		return errScheduledDefinitionChanged
	}
	if probe && (d.Private == nil || !d.Options.MinimalInference) {
		return errors.New("scheduled model inference is disabled")
	}
	if !s.scheduleApproved(ctx, tx, id, d) {
		return errScheduledDefinitionChanged
	}
	if d.Private != nil {
		if task, _ := ctx.Value(researchTaskBudgetKey{}).(string); task != "" {
			var payload string
			if tx.QueryRowContext(ctx, "SELECT payload_json FROM research_tasks WHERE id=? AND run_id=?", task, run).Scan(&payload) != nil {
				return errScheduledDefinitionChanged
			}
			var input taskInput
			if json.Unmarshal([]byte(payload), &input) != nil || input.Selection != nil && !privateScheduleScopeAllowed(d, *input.Selection) || probe && input.Selection == nil {
				return errScheduledDefinitionChanged
			}
		} else if probe {
			return errScheduledDefinitionChanged
		}
	}
	day := s.researchAdmissionTime(ctx).Format("2006-01-02")
	if scheduleUsage(ctx, tx, day, "global") >= scheduleDailyLimit(ctx, tx) || scheduleUsage(ctx, tx, day, "schedule:"+id) >= daily {
		return errScheduledDailyBudget
	}
	for _, scope := range []string{"global", "schedule:" + id} {
		if _, err = tx.ExecContext(ctx, "INSERT INTO research_schedule_daily VALUES(?,?,1) ON CONFLICT(day,scope) DO UPDATE SET used=used+1", day, scope); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) scheduleOptions(ctx context.Context, d scheduleDefinition) (ResearchOptions, error) {
	v := d.Options
	if len(d.Languages) > 8 || d.Private == nil && (v.MinimalInference || v.ProbeBudget != 0 || len(v.Selections) > 0 || len(v.DeeperSelections) > 0) {
		return v, errors.New("automatic schedules accept public discovery only; use a manual run for model inference")
	}
	if d.Private != nil && (!d.Private.valid() || len(v.Selections) == 0 || len(v.Selections) > 32 || v.MinimalInference && (v.ProbeBudget < 1 || v.ProbeBudget > 10) || !v.MinimalInference && v.ProbeBudget != 0) {
		return v, errors.New("explicit private selections and bounded inference policy required")
	}
	seen := map[string]bool{}
	for _, lang := range d.Languages {
		if !researchLanguage(lang) || seen[lang] {
			return v, errors.New("invalid schedule languages")
		}
		seen[lang] = true
	}
	if v.Search && len(d.Languages) > 0 {
		v.Queries = map[string]string{}
		for _, lang := range d.Languages {
			query := searchQueries[lang]
			if learned := s.activeKnowledge(ctx, "RESEARCH_STRATEGY", lang); str(learned["query"]) != "" {
				query = str(learned["query"])
			}
			v.Queries[lang] = query
		}
	}
	return v, nil
}

func (s *Server) readSchedules(ctx context.Context) ([]researchSchedule, error) {
	instant := s.scheduleTime()
	day := instant.Format("2006-01-02")
	globalUsed, globalLimit := scheduleUsage(ctx, s.Store.DB, day, "global"), scheduleDailyLimit(ctx, s.Store.DB)
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT s.id,s.name,s.revision,s.enabled,s.interval_minutes,s.daily_http_limit,s.definition_json,s.next_at,s.last_run_id,s.last_started_at,s.last_reason,COALESCE(r.status,''),COALESCE(o.used,0),COALESCE(o.budget,0),COALESCE(o.summary_json,'{}'),COALESCE(p.resume_at,0),COALESCE(p.intent,0),COALESCE(d.used,0),COALESCE(o.probes_used,0),COALESCE(o.probe_budget,0)
 FROM research_schedules s LEFT JOIN research_runs r ON r.id=s.last_run_id LEFT JOIN research_run_options o ON o.run_id=r.id LEFT JOIN research_schedule_runs p ON p.run_id=r.id LEFT JOIN research_schedule_daily d ON d.scope='schedule:'||s.id AND d.day=? ORDER BY s.next_at,s.created_at,s.id LIMIT 32`, day)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []researchSchedule{}
	for rows.Next() {
		var v researchSchedule
		var raw, summary string
		if err = rows.Scan(&v.ID, &v.Name, &v.Revision, &v.Enabled, &v.IntervalMinutes, &v.DailyHTTPLimit, &raw, &v.NextAt, &v.LastRunID, &v.LastStartedAt, &v.LastReason, &v.RunStatus, &v.RunUsed, &v.RunBudget, &summary, &v.ResumeAt, &v.AutoResume, &v.TodayUsed, &v.RunProbes, &v.RunProbeBudget); err != nil {
			return nil, err
		}
		if err = json.Unmarshal([]byte(raw), &v.scheduleDefinition); err != nil {
			return nil, err
		}
		v.Summary = json.RawMessage(summary)
		v.State = "WAITING"
		switch {
		case !v.Enabled:
			v.State = "OFF"
			if v.LastReason != "" {
				v.State = "NEEDS_REVIEW"
			}
		case v.RunStatus == "PAUSED" && !v.AutoResume:
			v.State = "PAUSED"
		case v.TodayUsed >= v.DailyHTTPLimit || globalUsed >= globalLimit || v.ResumeAt > instant.UnixMilli():
			v.State = "DAILY_LIMIT"
			if v.TodayUsed < v.DailyHTTPLimit && globalUsed < globalLimit {
				v.State = "INTEGRATION_LIMIT"
			}
		case oneOf(v.RunStatus, "QUEUED", "RUNNING"):
			v.State = v.RunStatus
		case v.RunStatus == "BUDGET_EXHAUSTED" && scheduleRoundFull(v):
			v.State = "ROUND_LIMIT"
		}
		list = append(list, v)
	}
	rowErr := rows.Err()
	rows.Close()
	if rowErr != nil {
		return nil, rowErr
	}
	for i := range list {
		v := &list[i]
		if v.Private == nil {
			continue
		}
		tx, e := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
		if e != nil {
			return nil, e
		}
		u, e := schedulePrivateUsage(ctx, tx, v.ID, day, "")
		tx.Rollback()
		if e != nil {
			return nil, e
		}
		v.PrivateUsage = u.view()
		if !v.Enabled && v.Private.ApprovalID == "" {
			v.State = "NEEDS_REVIEW"
		}
		if v.Enabled && v.RunStatus == "BUDGET_EXHAUSTED" && v.ResumeAt > instant.UnixMilli() {
			v.State = "INFERENCE_WAIT"
		}
		if v.Enabled && v.RunStatus == "BUDGET_EXHAUSTED" && !v.AutoResume && !scheduleRoundFull(*v) {
			v.State = "INFERENCE_LIMIT"
		}
	}
	return list, nil
}
func (s *Server) listResearchSchedules(w http.ResponseWriter, r *http.Request) {
	list, err := s.readSchedules(r.Context())
	if err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	policy, err := readResearchExecutionPolicy(r.Context(), s.Store.DB)
	if err != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	t := s.scheduleTime()
	jsonOK(w, 200, map[string]any{"schedules": list, "daily_http_limit": scheduleDailyLimit(r.Context(), s.Store.DB), "today_used": scheduleUsage(r.Context(), s.Store.DB, t.Format("2006-01-02"), "global"), "reset_at": nextBudgetDay(t), "max_active": policy.MaxParallelRuns, "inference_enabled": false, "private_inference_opt_in_available": true, "lifecycle": "THIS_ENGINE"})
}
func (s *Server) saveResearchSchedule(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID              string                 `json:"id"`
		Name            string                 `json:"name"`
		Revision        int64                  `json:"revision"`
		IntervalMinutes int                    `json:"interval_minutes"`
		DailyHTTPLimit  int                    `json:"daily_http_limit"`
		Options         ResearchOptions        `json:"options"`
		Languages       []string               `json:"languages"`
		Private         *schedulePrivatePolicy `json:"private"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || v.Name == "" || safeText(v.Name, 120) != v.Name || v.Revision < 0 || v.IntervalMinutes < 15 || v.IntervalMinutes > 10080 || v.DailyHTTPLimit < 1 || v.DailyHTTPLimit > 2000 {
		http.Error(w, "bounded schedule definition required", 400)
		return
	}
	d := scheduleDefinition{Options: v.Options, Languages: v.Languages, Private: v.Private}
	if d.Private != nil {
		d.Private.ApprovalID = ""
		d.Private.ScopesFingerprint = ""
	}
	known, secretErr := s.knownSecrets(r.Context())
	if secretErr != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, secret := range known {
		if jsonContainsSecret(v, secret) {
			http.Error(w, "use safe references", 422)
			return
		}
	}
	options, err := s.scheduleOptions(r.Context(), d)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	options, _, err = s.planResearch(r.Context(), options)
	if err != nil {
		http.Error(w, "selected research sources or options unavailable", 422)
		return
	}
	// Keep language templates dynamic; the actual run freezes its planned options.
	d.Options.Budget = options.Budget
	d.Options.ProbeBudget = options.ProbeBudget
	d.SourcesFingerprint, err = scheduleSourcesFingerprint(r.Context(), s.Store.DB, options.SourceIDs)
	if err != nil {
		http.Error(w, "selected research sources unavailable", 422)
		return
	}
	if d.Private != nil {
		d.Private.ScopesFingerprint, err = s.privateScheduleFingerprint(r.Context(), s.Store.DB, d)
		if err != nil {
			http.Error(w, "private model scopes unavailable", 422)
			return
		}
	}
	raw, _ := json.Marshal(d)
	at := s.scheduleTime().UnixMilli()
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	defer tx.Rollback()
	var revision int64
	var enabled int
	err = tx.QueryRowContext(r.Context(), "SELECT revision,enabled FROM research_schedules WHERE id=?", v.ID).Scan(&revision, &enabled)
	if err == sql.ErrNoRows {
		var count int
		tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM research_schedules").Scan(&count)
		if v.Revision != 0 || count >= 32 {
			http.Error(w, "schedule revision or count limit", 409)
			return
		}
		_, err = tx.ExecContext(r.Context(), "INSERT INTO research_schedules(id,name,revision,interval_minutes,daily_http_limit,definition_json,next_at,created_at,updated_at) VALUES(?,?,1,?,?,?,?,?,?)", v.ID, v.Name, v.IntervalMinutes, v.DailyHTTPLimit, string(raw), at+int64(v.IntervalMinutes)*60000, at, at)
	} else if err == nil {
		if revision != v.Revision || enabled == 1 {
			http.Error(w, "disable the schedule before editing; use its latest revision", 409)
			return
		}
		if _, err = stopScheduleRuns(r.Context(), tx, v.ID, true, at); err != nil {
			http.Error(w, "schedule storage unavailable", 500)
			return
		}
		_, err = tx.ExecContext(r.Context(), "UPDATE research_schedules SET last_run_id='',name=?,revision=revision+1,interval_minutes=?,daily_http_limit=?,definition_json=?,next_at=?,last_reason='',updated_at=? WHERE id=? AND revision=?", v.Name, v.IntervalMinutes, v.DailyHTTPLimit, string(raw), at+int64(v.IntervalMinutes)*60000, at, v.ID, v.Revision)
	}
	if err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "revision": v.Revision + 1, "enabled": false})
}

func (s *Server) setResearchScheduleLimit(w http.ResponseWriter, r *http.Request) {
	var v struct {
		DailyHTTPLimit int `json:"daily_http_limit"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.DailyHTTPLimit < 1 || v.DailyHTTPLimit > 2000 {
		http.Error(w, "daily HTTP limit must be 1..2000", 400)
		return
	}
	raw, _ := json.Marshal(v.DailyHTTPLimit)
	if _, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO settings VALUES('research_schedule_http_limit',?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json", string(raw)); err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	jsonOK(w, 200, v)
}

// Preserve every round and task when a schedule stops. Replacing its definition
// cancels only unfinished work, so an old source snapshot is never replayed as
// part of a newly approved definition.
func stopScheduleRuns(ctx context.Context, tx *sql.Tx, id string, cancel bool, at int64) ([]string, error) {
	rows, err := tx.QueryContext(ctx, "SELECT r.id FROM research_runs r JOIN research_schedule_runs p ON p.run_id=r.id WHERE p.schedule_id=? AND r.status IN ('QUEUED','RUNNING','PAUSED','BUDGET_EXHAUSTED')", id)
	if err != nil {
		return nil, err
	}
	ids := []string{}
	for rows.Next() {
		var run string
		if err = rows.Scan(&run); err != nil {
			break
		}
		ids = append(ids, run)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		return nil, err
	}
	state := "PAUSED"
	if cancel {
		state = "CANCELLED"
	}
	for _, run := range ids {
		if _, err = tx.ExecContext(ctx, "UPDATE research_runs SET status=?,finished_at=CASE WHEN ?='CANCELLED' THEN ? ELSE finished_at END WHERE id=?", state, state, at, run); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE research_schedule_runs SET intent=0,resume_at=0 WHERE run_id=?", run); err != nil {
			return nil, err
		}
		if cancel {
			if _, err = tx.ExecContext(ctx, "UPDATE research_tasks SET status='CANCELLED' WHERE run_id=? AND status IN ('QUEUED','INTERRUPTED','BUDGET_EXHAUSTED')", run); err != nil {
				return nil, err
			}
		}
	}
	return ids, nil
}
func (s *Server) cancelScheduledFlights(ids []string) {
	for _, id := range ids {
		s.flightMu.Lock()
		cancel := s.cancels["research:"+id]
		s.flightMu.Unlock()
		if cancel != nil {
			cancel()
		}
	}
}
func (s *Server) controlResearchSchedule(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID                         string `json:"id"`
		Revision                   int64  `json:"revision"`
		Action                     string `json:"action"`
		ConfirmPrivate             bool   `json:"confirm_private"`
		ExpectedPrivateFingerprint string `json:"expected_private_fingerprint"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || !oneOf(v.Action, "ENABLE", "DISABLE", "RUN_NOW") {
		http.Error(w, "invalid schedule action", 400)
		return
	}
	// Plan validation needs no network and precedes the write transaction. Its
	// source fingerprint is checked again inside that transaction.
	var d scheduleDefinition
	var raw string
	if v.Action != "DISABLE" {
		if s.Store.DB.QueryRowContext(r.Context(), "SELECT definition_json FROM research_schedules WHERE id=?", v.ID).Scan(&raw) != nil || json.Unmarshal([]byte(raw), &d) != nil {
			http.Error(w, "schedule missing", 404)
			return
		}
		options, err := s.scheduleOptions(r.Context(), d)
		if err == nil {
			_, _, err = s.planResearch(r.Context(), options)
		}
		if err != nil {
			http.Error(w, "selected research sources or options unavailable", 422)
			return
		}
		if v.Action == "ENABLE" && d.Private != nil && !v.ConfirmPrivate {
			http.Error(w, "explicit private schedule confirmation required", 409)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	defer tx.Rollback()
	var revision int64
	var enabled int
	var stored string
	if tx.QueryRowContext(r.Context(), "SELECT revision,enabled,definition_json FROM research_schedules WHERE id=?", v.ID).Scan(&revision, &enabled, &stored) != nil {
		http.Error(w, "schedule missing", 404)
		return
	}
	if revision != v.Revision {
		http.Error(w, "schedule changed; refresh first", 409)
		return
	}
	if v.Action == "RUN_NOW" && enabled != 1 {
		http.Error(w, "enable the schedule first", 409)
		return
	}
	if v.Action != "DISABLE" && raw != stored {
		http.Error(w, "schedule changed; refresh first", 409)
		return
	}
	at := s.scheduleTime().UnixMilli()
	ids := []string{}
	if v.Action == "DISABLE" {
		enabled = 0
		ids, err = stopScheduleRuns(r.Context(), tx, v.ID, false, at)
	} else {
		fingerprint, e := scheduleSourcesFingerprint(r.Context(), tx, d.Options.SourceIDs)
		if e != nil {
			http.Error(w, "selected research sources unavailable", 422)
			return
		}
		if v.Action == "RUN_NOW" && fingerprint != d.SourcesFingerprint {
			http.Error(w, "scheduled research sources changed; disable and review first", 409)
			return
		}
		if fingerprint != d.SourcesFingerprint {
			ids, err = stopScheduleRuns(r.Context(), tx, v.ID, true, at)
			if err == nil {
				_, err = tx.ExecContext(r.Context(), "UPDATE research_schedules SET last_run_id='' WHERE id=?", v.ID)
			}
		}
		d.SourcesFingerprint = fingerprint
		if d.Private != nil && v.Action == "ENABLE" {
			current, e := s.privateScheduleFingerprint(r.Context(), tx, d)
			if e != nil {
				http.Error(w, "private model scopes unavailable", 422)
				return
			}
			if current != v.ExpectedPrivateFingerprint {
				http.Error(w, "private scopes changed; review current connections again", 409)
				return
			}
			if current != d.Private.ScopesFingerprint {
				var stopped []string
				stopped, err = stopScheduleRuns(r.Context(), tx, v.ID, true, at)
				ids = append(ids, stopped...)
				if err == nil {
					_, err = tx.ExecContext(r.Context(), "UPDATE research_schedules SET last_run_id='' WHERE id=?", v.ID)
				}
			}
			if err == nil {
				err = s.approvePrivateSchedule(r.Context(), tx, v.ID, revision+1, &d)
			}
		}
		if d.Private != nil && v.Action == "RUN_NOW" && !s.scheduleApproved(r.Context(), tx, v.ID, d) {
			http.Error(w, "private scope changed; disable and review first", 409)
			return
		}
		encoded, _ := json.Marshal(d)
		stored = string(encoded)
		enabled = 1
		if err == nil {
			_, err = tx.ExecContext(r.Context(), "UPDATE research_schedule_runs SET intent=1 WHERE run_id=(SELECT last_run_id FROM research_schedules WHERE id=?)", v.ID)
		}
		if err == nil {
			_, err = tx.ExecContext(r.Context(), "UPDATE research_runs SET status='BUDGET_EXHAUSTED' WHERE id=(SELECT last_run_id FROM research_schedules WHERE id=?) AND status='PAUSED' AND id IN (SELECT run_id FROM research_run_options WHERE used>=budget)", v.ID)
		}
	}
	if err != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	_, err = tx.ExecContext(r.Context(), "UPDATE research_schedules SET enabled=?,revision=revision+1,definition_json=?,next_at=MAX(?,COALESCE((SELECT MAX(scheduled_for)+1 FROM research_schedule_runs WHERE schedule_id=?),?)),last_reason='',updated_at=? WHERE id=? AND revision=?", enabled, stored, at, v.ID, at, at, v.ID, revision)
	if err != nil || tx.Commit() != nil {
		http.Error(w, "schedule storage unavailable", 500)
		return
	}
	s.cancelScheduledFlights(ids)
	s.wakeWorker()
	jsonOK(w, 200, map[string]any{"id": v.ID, "revision": revision + 1, "enabled": enabled == 1})
}

// A synchronized edit to a locally owned round must never become executable.
func suspendScheduledRun(ctx context.Context, tx *sql.Tx, run, reason string, at int64) error {
	var owner string
	err := tx.QueryRowContext(ctx, "SELECT schedule_id FROM research_schedule_runs WHERE run_id=?", run).Scan(&owner)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	if _, err = stopScheduleRuns(ctx, tx, owner, true, at); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "UPDATE research_schedules SET enabled=0,revision=revision+1,last_run_id='',last_reason=?,updated_at=? WHERE id=?", reason, at, owner)
	return err
}
func (s *Server) suspendSchedule(ctx context.Context, id, reason string) {
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	ids, err := stopScheduleRuns(ctx, tx, id, false, s.scheduleTime().UnixMilli())
	if err != nil {
		return
	}
	if _, err = tx.ExecContext(ctx, "UPDATE research_schedules SET enabled=0,revision=revision+1,last_reason=?,updated_at=? WHERE id=? AND enabled=1", reason, s.scheduleTime().UnixMilli(), id); err != nil {
		return
	}
	if tx.Commit() == nil {
		s.cancelScheduledFlights(ids)
	}
}

// A manual pause is different from a process restart. Only durable intent=1
// may recover automatically, and a disabled owner can never resume through a
// different API entry point. Every admission rechecks the local concurrency cap.
func (s *Server) scheduledRunControl(ctx context.Context, tx *sql.Tx, run, action string, additionalProbes int) error {
	var owner string
	var enabled int
	err := tx.QueryRowContext(ctx, "SELECT p.schedule_id,s.enabled FROM research_schedule_runs p JOIN research_schedules s ON s.id=p.schedule_id WHERE p.run_id=?", run).Scan(&owner, &enabled)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	if additionalProbes != 0 {
		return errors.New("automatic schedules do not permit model inference")
	}
	intent := 0
	if action == "RESUME" {
		if enabled != 1 {
			return errors.New("enable the owning research schedule before resuming")
		}
		var last, raw string
		if err = tx.QueryRowContext(ctx, "SELECT last_run_id,definition_json FROM research_schedules WHERE id=?", owner).Scan(&last, &raw); err != nil {
			return err
		}
		var d scheduleDefinition
		if last != run || json.Unmarshal([]byte(raw), &d) != nil || !s.scheduleApproved(ctx, tx, owner, d) {
			return errors.New("only the current approved schedule round may resume")
		}
		if d.Private != nil {
			var httpCap, probeCap int
			if tx.QueryRowContext(ctx, "SELECT budget,probe_budget FROM research_run_options WHERE run_id=?", run).Scan(&httpCap, &probeCap) != nil || httpCap > d.Options.Budget || probeCap > d.Options.ProbeBudget {
				return errors.New("private round budgets cannot exceed the approved schedule definition")
			}
		}
		slot, err := researchScheduleHasSlot(ctx, tx, run)
		if err != nil {
			return err
		}
		if !slot {
			return errors.New("another scheduled research run is active")
		}
		intent = 1
	}
	_, err = tx.ExecContext(ctx, "UPDATE research_schedule_runs SET intent=?,resume_at=0 WHERE run_id=?", intent, run)
	return err
}

func (s *Server) researchScheduleTick(ctx context.Context) {
	if !s.scheduleMu.TryLock() {
		return
	}
	defer s.scheduleMu.Unlock()
	if s.maintenancePending() {
		return
	}
	list, err := s.readSchedules(ctx)
	if err != nil {
		return
	}
	// Recover approved local checkpoints before admitting a new periodic round.
	resumeCandidate := func(v researchSchedule) bool {
		return v.Enabled && v.AutoResume && (v.RunStatus == "PAUSED" || v.RunStatus == "BUDGET_EXHAUSTED" && !scheduleRoundFull(v))
	}
	sort.SliceStable(list, func(i, j int) bool { return resumeCandidate(list[i]) && !resumeCandidate(list[j]) })
	at := s.scheduleTime().UnixMilli()
	day := s.scheduleTime().Format("2006-01-02")
	for _, v := range list {
		if ctx.Err() != nil {
			return
		}
		if !v.Enabled {
			continue
		}
		if !s.scheduleApproved(ctx, s.Store.DB, v.ID, v.scheduleDefinition) {
			s.suspendSchedule(ctx, v.ID, "SOURCES_CHANGED")
			continue
		}
		// All counters are rechecked atomically immediately before each HTTP.
		if scheduleUsage(ctx, s.Store.DB, day, "global") >= scheduleDailyLimit(ctx, s.Store.DB) || v.TodayUsed >= v.DailyHTTPLimit {
			continue
		}
		if v.LastRunID != "" && (oneOf(v.RunStatus, "QUEUED", "RUNNING", "PAUSED") || (v.RunStatus == "BUDGET_EXHAUSTED" && !scheduleRoundFull(v))) {
			s.recoverScheduledRun(ctx, v, at)
			continue
		}
		if v.NextAt > at {
			continue
		}
		options, e := s.scheduleOptions(ctx, v.scheduleDefinition)
		var tasks []researchPlannedTask
		if e == nil {
			options, tasks, e = s.planResearch(ctx, options)
		}
		if e != nil {
			s.suspendSchedule(ctx, v.ID, "SOURCES_UNAVAILABLE")
			continue
		}
		tx, e := s.Store.DB.BeginTx(ctx, nil)
		if e != nil {
			return
		}
		if !s.scheduleApproved(ctx, tx, v.ID, v.scheduleDefinition) {
			tx.Rollback()
			s.suspendSchedule(ctx, v.ID, "SOURCES_CHANGED")
			continue
		}
		slot, e := researchScheduleHasSlot(ctx, tx, "")
		if e != nil || !slot {
			tx.Rollback()
			continue
		}
		continuedFrom := ""
		if v.RunStatus == "BUDGET_EXHAUSTED" && scheduleRoundFull(v) {
			remaining, busy, carryErr := carryScheduledTasks(ctx, tx, v.LastRunID)
			if carryErr != nil || busy {
				tx.Rollback()
				if carryErr != nil {
					s.suspendSchedule(ctx, v.ID, "SOURCES_UNAVAILABLE")
				}
				continue
			}
			if len(remaining) > 0 {
				// A schema30 private round already has an exact local approval.
				// Bind its carried tasks to the current matching planned scope;
				// do not grant deeper work or replay an interrupted inference.
				current := map[string]taskInput{}
				for _, planned := range tasks {
					if planned.input.Selection != nil {
						current[scopeKey(*planned.input.Selection)] = planned.input
					}
				}
				for i := range remaining {
					if selected := remaining[i].input.Selection; selected != nil && remaining[i].input.ProbeStamp == "" {
						binding := current[scopeKey(*selected)]
						remaining[i].input.ProbeStamp = binding.ProbeStamp
						remaining[i].input.ObservationMinutes = binding.ObservationMinutes
					}
				}
				tasks = remaining
				continuedFrom = v.LastRunID
			}
		}
		result, e := tx.ExecContext(ctx, "UPDATE research_schedules SET next_at=?,last_started_at=?,last_reason='',updated_at=? WHERE id=? AND revision=? AND enabled=1 AND next_at=?", at+int64(v.IntervalMinutes)*60000, at, at, v.ID, v.Revision, v.NextAt)
		if e != nil {
			tx.Rollback()
			continue
		}
		affected, _ := result.RowsAffected()
		if affected != 1 {
			tx.Rollback()
			continue
		}
		id := newID()
		if e = insertResearchRun(ctx, tx, id, options, tasks); e == nil {
			_, e = tx.ExecContext(ctx, "INSERT INTO research_schedule_runs(run_id,schedule_id,scheduled_for,revision,continued_from) VALUES(?,?,?,?,?)", id, v.ID, v.NextAt, v.Revision, continuedFrom)
		}
		if e == nil {
			_, e = tx.ExecContext(ctx, "UPDATE research_schedules SET last_run_id=? WHERE id=?", id, v.ID)
		}
		if e != nil {
			tx.Rollback()
			continue
		}
		if tx.Commit() != nil {
			continue
		}
		// A larger limit admits other approved schedules in this same tick. The
		// default still returns after one, before planning another due schedule.
		if slot, err := researchScheduleHasSlot(ctx, s.Store.DB, ""); err != nil || !slot {
			return
		}
	}
}

// A small per-round cap must not starve later sources or discovered pages.
// Transfer only unexecuted public tasks into the next bounded round, retaining
// the old task results and explicit lineage. No missed time slots are backfilled.
func carryScheduledTasks(ctx context.Context, tx *sql.Tx, run string) ([]researchPlannedTask, bool, error) {
	var definition string
	if err := tx.QueryRowContext(ctx, "SELECT s.definition_json FROM research_schedule_runs r JOIN research_schedules s ON s.id=r.schedule_id WHERE r.run_id=?", run).Scan(&definition); err != nil {
		return nil, false, err
	}
	var approved scheduleDefinition
	if json.Unmarshal([]byte(definition), &approved) != nil {
		return nil, false, errScheduledDefinitionChanged
	}

	bindings, err := researchBindings(ctx, tx, run)
	if err != nil {
		return nil, false, err
	}
	var running int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='RUNNING'", run).Scan(&running); err != nil {
		return nil, false, err
	}
	if running > 0 {
		return nil, true, nil
	}
	rows, err := tx.QueryContext(ctx, "SELECT t.kind,t.dedupe,t.payload_json,t.result_json,COALESCE(a.state_json,''),COALESCE(b.state_json,'') FROM research_tasks t LEFT JOIN research_aa_checkpoints a ON a.task_id=t.id LEFT JOIN research_brave_checkpoints b ON b.task_id=t.id WHERE t.run_id=? AND t.status IN ('QUEUED','INTERRUPTED','BUDGET_EXHAUSTED') ORDER BY t.rowid LIMIT 128", run)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	tasks := []researchPlannedTask{}
	for rows.Next() {
		var task researchPlannedTask
		var raw, result, aaState, braveState string
		if err = rows.Scan(&task.kind, &task.dedupe, &raw, &result, &aaState, &braveState); err != nil {
			return nil, false, err
		}
		if json.Unmarshal([]byte(raw), &task.input) != nil || !oneOf(task.kind, "SOURCE", "SEARCH", "INSPECT", "INSPECT_LINK", "REVALIDATE_CANDIDATE", "AA", "LMSPEED_SEEDS", "CATALOG", "MINIMAL_INFERENCE", "SEMANTIC_PROBE", "TOOL_PROBE") {
			return nil, false, errors.New("invalid scheduled public task")
		}
		if task.input.Selection != nil && (approved.Private == nil || !privateScheduleScopeAllowed(approved, *task.input.Selection) || researchInferenceTask(task.kind) && !approved.Options.MinimalInference || researchDeeperTask(task.kind) && !researchDeeperSelection(approved.Options, *task.input.Selection)) || task.input.Selection == nil && (task.kind == "CATALOG" || researchInferenceTask(task.kind)) {
			return nil, false, errScheduledDefinitionChanged
		}
		if task.input.Strategy != nil {
			key := knowledgeMetricKey(task.input.Strategy.Kind, task.input.Strategy.Subject)
			if binding, ok := bindings[key]; ok && binding.ScopeHash == researchInputScope(task.kind, task.input) {
				task.binding = &binding
				task.input.Strategy = &binding
			} else {
				task.input.Strategy = nil
			}
		}
		// Continuation grants a new HTTP budget, not a new retry allowance or
		// permission to ignore a remote Retry-After deadline.
		task.retry = readResearchRetry([]byte(result))
		task.originWait = readResearchOriginWait([]byte(result))
		if task.kind == "AA" && aaState != "" {
			var checkpoint aaResearchCheckpoint
			if json.Unmarshal([]byte(aaState), &checkpoint) != nil {
				return nil, false, errors.New("invalid AA continuation checkpoint")
			}
			task.aaCheckpoint = &checkpoint
		}
		if task.kind == "SEARCH" && braveState != "" {
			var checkpoint braveResearchCheckpoint
			if json.Unmarshal([]byte(braveState), &checkpoint) != nil {
				return nil, false, errors.New("invalid Brave continuation checkpoint")
			}
			task.braveCheckpoint = &checkpoint
		}
		tasks = append(tasks, task)
	}
	return tasks, false, rows.Err()
}

func (s *Server) recoverScheduledRun(ctx context.Context, v researchSchedule, at int64) {
	if !oneOf(v.RunStatus, "PAUSED", "BUDGET_EXHAUSTED") {
		return
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	var intent int
	var resumeAt int64
	var enabled int
	var status string
	var budget, used int
	if tx.QueryRowContext(ctx, "SELECT p.intent,p.resume_at,s.enabled,r.status,o.budget,o.used FROM research_schedule_runs p JOIN research_schedules s ON s.id=p.schedule_id JOIN research_runs r ON r.id=p.run_id JOIN research_run_options o ON o.run_id=r.id WHERE p.run_id=?", v.LastRunID).Scan(&intent, &resumeAt, &enabled, &status, &budget, &used) != nil {
		return
	}
	if intent != 1 || enabled != 1 || resumeAt > at || used >= budget || !oneOf(status, "PAUSED", "BUDGET_EXHAUSTED") {
		return
	}
	if slot, err := researchScheduleHasSlot(ctx, tx, ""); err != nil || !slot {
		return
	}
	if v.Private != nil {
		if _, err = tx.ExecContext(ctx, "UPDATE research_tasks SET status='FAILED',result_json=json_set(result_json,'$.reason','interrupted private inference is never automatically replayed') WHERE run_id=? AND kind IN ('MINIMAL_INFERENCE','SEMANTIC_PROBE','TOOL_PROBE') AND status='INTERRUPTED'", v.LastRunID); err != nil {
			return
		}
	}
	// Automatic replay of an interrupted network attempt remains bounded.
	if _, err = tx.ExecContext(ctx, "UPDATE research_tasks SET status='FAILED',result_json=json_set(result_json,'$.reason','restart retry limit reached') WHERE run_id=? AND status='INTERRUPTED' AND attempts>=3 AND (kind!='AA' OR NOT EXISTS(SELECT 1 FROM research_aa_checkpoints a WHERE a.task_id=research_tasks.id)) AND (kind!='SEARCH' OR NOT EXISTS(SELECT 1 FROM research_brave_checkpoints b WHERE b.task_id=research_tasks.id))", v.LastRunID); err != nil {
		return
	}
	if _, err = tx.ExecContext(ctx, "UPDATE research_tasks SET status='QUEUED' WHERE run_id=? AND status IN ('INTERRUPTED','BUDGET_EXHAUSTED')", v.LastRunID); err != nil {
		return
	}
	var pending int
	if tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='QUEUED'", v.LastRunID).Scan(&pending) != nil {
		return
	}
	next := "QUEUED"
	if pending == 0 {
		next = "COMPLETED"
	}
	if _, err = tx.ExecContext(ctx, "UPDATE research_runs SET status=?,finished_at=CASE WHEN ?='COMPLETED' THEN ? ELSE NULL END WHERE id=?", next, next, at, v.LastRunID); err != nil {
		return
	}
	if _, err = tx.ExecContext(ctx, "UPDATE research_schedule_runs SET resume_at=0 WHERE run_id=?", v.LastRunID); err != nil {
		return
	}
	if tx.Commit() == nil {
		s.updateResearchSummary(ctx, v.LastRunID)
	}
}

// Shared Brave/AA admission limits do not become a 30-second retry loop.
// They retain their existing UTC/fixed-window semantics independently of the
// new schedule-only HTTP meter.
func (s *Server) scheduleBudgetWait(ctx context.Context, run, task string, err error) {
	if err == nil {
		return
	}
	if s.privateScheduleBudgetWait(ctx, run, task, err) {
		return
	}
	wait := int64(0)
	switch {
	case errors.Is(err, errScheduledDailyBudget), err.Error() == "daily request budget exhausted":
		wait = nextBudgetDay(s.scheduleTime())
	case err.Error() == "integration fixed-window budget exhausted":
		if s.Store.DB.QueryRowContext(ctx, "SELECT reset_at FROM integration_windows WHERE name='AA'").Scan(&wait) != nil {
			return
		}
	default:
		return
	}
	result, e := s.Store.DB.ExecContext(ctx, "UPDATE research_schedule_runs SET resume_at=MAX(resume_at,?) WHERE run_id=?", wait, run)
	if e != nil {
		return
	}
	affected, _ := result.RowsAffected()
	if affected == 1 {
		_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_tasks SET attempts=MAX(0,attempts-1) WHERE id=?", task)
	}
}
