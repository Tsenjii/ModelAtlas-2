package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"strconv"
	"time"
)

const researchPrivateScheduleSchema = `
CREATE TABLE IF NOT EXISTS research_schedule_private_approvals(id TEXT PRIMARY KEY,schedule_id TEXT NOT NULL,revision INTEGER NOT NULL,definition_hash TEXT NOT NULL,scopes_fingerprint TEXT NOT NULL,definition_json TEXT NOT NULL,approved_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_schedule_private_history ON research_schedule_private_approvals(schedule_id,revision);
CREATE TABLE IF NOT EXISTS research_schedule_inference_admissions(request_id TEXT PRIMARY KEY REFERENCES research_resource_admissions(request_id),schedule_id TEXT NOT NULL,day TEXT NOT NULL,approval_id TEXT NOT NULL REFERENCES research_schedule_private_approvals(id));
CREATE INDEX IF NOT EXISTS research_schedule_inference_day ON research_schedule_inference_admissions(schedule_id,day);
`

type privateScheduleCredentialKey struct{}

type schedulePrivatePolicy struct {
	DailyInferences   int     `json:"daily_inferences"`
	DailyTokens       *int64  `json:"daily_tokens"`
	DailyUSD          *string `json:"daily_usd"`
	ResumeAfterReset  bool    `json:"resume_after_reset"`
	ScopesFingerprint string  `json:"scopes_fingerprint,omitempty"`
	ApprovalID        string  `json:"approval_id,omitempty"`
}

func (p schedulePrivatePolicy) valid() bool {
	return p.DailyInferences >= 0 && p.DailyInferences <= 2000 && (researchResourceLimits{p.DailyTokens, p.DailyUSD}).valid()
}

func privateScheduleScopeAllowed(d scheduleDefinition, scope Selection) bool {
	for _, allowed := range d.Options.Selections {
		if scope == allowed {
			return true
		}
	}
	return false
}

// Bind credentials, encrypted material revision, effective origin/protocol and
// exact scope. Catalog metadata freshness does not revoke an unchanged grant.
func (s *Server) privateScheduleFingerprint(ctx context.Context, q queryRows, d scheduleDefinition) (string, error) {
	if d.Private == nil || !d.Private.valid() || len(d.Options.Selections) == 0 || len(d.Options.Selections) > 32 {
		return "", errors.New("explicit private schedule scope required")
	}
	var bindings []any
	seen := map[string]bool{}
	for _, v := range d.Options.Selections {
		key := scopeKey(v)
		if seen[key] {
			return "", errors.New("duplicate private schedule scope")
		}
		seen[key] = true
		selected, err := s.authenticityScope(ctx, q, v)
		if err != nil {
			return "", err
		}
		bindings = append(bindings, struct {
			Selection                                     Selection
			Origin, Operation, Config, Endpoint, Material string
			Version                                       int
		}{v, selected.Origin, selected.Operation, selected.ConfigHash, selected.EndpointHash, selected.MaterialStamp, selected.CredentialVersion})
	}
	return connectorHash(bindings), nil
}

func privateScheduleDefinitionHash(d scheduleDefinition) string {
	copy := *d.Private
	copy.ApprovalID = ""
	d.Private = &copy
	return connectorHash(d)
}

func (s *Server) scheduleApproved(ctx context.Context, q queryRows, id string, d scheduleDefinition) bool {
	if !scheduleSourcesApproved(ctx, q, d) {
		return false
	}
	if d.Private == nil {
		return !d.Options.MinimalInference && d.Options.ProbeBudget == 0 && len(d.Options.Selections) == 0
	}
	p := d.Private
	if !validID(p.ApprovalID) {
		return false
	}
	fingerprint, err := s.privateScheduleFingerprint(ctx, q, d)
	if err != nil || fingerprint != p.ScopesFingerprint {
		return false
	}
	var owner, hash, scopes string
	err = q.QueryRowContext(ctx, "SELECT schedule_id,definition_hash,scopes_fingerprint FROM research_schedule_private_approvals WHERE id=?", p.ApprovalID).Scan(&owner, &hash, &scopes)
	return err == nil && owner == id && scopes == fingerprint && hash == privateScheduleDefinitionHash(d)
}

func (s *Server) approvePrivateSchedule(ctx context.Context, tx *sql.Tx, id string, revision int64, d *scheduleDefinition) error {
	fingerprint, err := s.privateScheduleFingerprint(ctx, tx, *d)
	if err != nil {
		return err
	}
	d.Private.ScopesFingerprint = fingerprint
	d.Private.ApprovalID = newID()
	raw, _ := json.Marshal(d)
	_, err = tx.ExecContext(ctx, "INSERT INTO research_schedule_private_approvals VALUES(?,?,?,?,?,?,?)", d.Private.ApprovalID, id, revision, privateScheduleDefinitionHash(*d), fingerprint, string(raw), s.scheduleTime().UnixMilli())
	return err
}

func schedulePrivateUsage(ctx context.Context, tx *sql.Tx, id, day, exclude string) (*researchResourceUsage, error) {
	u, _, err := readResourceUsageWhere(ctx, tx, day, " AND a.request_id<>? AND EXISTS(SELECT 1 FROM research_schedule_inference_admissions x WHERE x.request_id=a.request_id AND x.schedule_id=?)", []any{exclude, id})
	return u, err
}

func (s *Server) reserveScheduledInference(ctx context.Context, tx *sql.Tx, record requestRecord) (err error) {
	if record.Source != "CRAWLER_VERIFY" {
		return nil
	}
	run, _ := ctx.Value(researchBudgetKey{}).(string)
	if run == "" {
		return nil
	}
	var id, name, raw string
	err = tx.QueryRowContext(ctx, "SELECT s.id,s.name,s.definition_json FROM research_schedule_runs r JOIN research_schedules s ON s.id=r.schedule_id WHERE r.run_id=?", run).Scan(&id, &name, &raw)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	issue := &researchResourceBudgetError{Scope: "SCHEDULE", Metric: "UNAVAILABLE", ScheduleID: id, ScheduleName: name, researchInferenceScope: researchInferenceScope{record.Selection.ProviderID, record.Selection.AccountID, record.Selection.CredentialID}}
	defer func() {
		if err != nil {
			var limit *researchResourceBudgetError
			if !errors.As(err, &limit) {
				err = issue
			}
		}
	}()
	var d scheduleDefinition
	if json.Unmarshal([]byte(raw), &d) != nil || d.Private == nil || !d.Options.MinimalInference || !privateScheduleScopeAllowed(d, record.Selection) || !s.scheduleApproved(ctx, tx, id, d) {
		return issue
	}
	var payload string
	task, _ := ctx.Value(researchTaskBudgetKey{}).(string)
	if tx.QueryRowContext(ctx, "SELECT payload_json FROM research_tasks WHERE id=? AND run_id=? AND kind='MINIMAL_INFERENCE'", task, run).Scan(&payload) != nil {
		return issue
	}
	var input taskInput
	if json.Unmarshal([]byte(payload), &input) != nil || input.Selection == nil || *input.Selection != record.Selection {
		return issue
	}
	var day string
	var at int64
	if err = tx.QueryRowContext(ctx, "SELECT day,admitted_at FROM research_inference_admissions WHERE request_id=?", record.ID).Scan(&day, &at); err != nil {
		return err
	}
	instant := time.UnixMilli(at).UTC()
	issue.Day, issue.ResetAt = day, nextBudgetDay(instant)
	if day != instant.Format("2006-01-02") {
		return issue
	}
	u, err := schedulePrivateUsage(ctx, tx, id, day, record.ID)
	if err != nil {
		return err
	}
	p := d.Private
	if u.Requests >= p.DailyInferences {
		issue.Metric, issue.Used, issue.Limit, issue.Reservation = "REQUESTS", big.NewInt(int64(u.Requests)).String(), big.NewInt(int64(p.DailyInferences)).String(), "1"
		return issue
	}
	policy, err := readResearchResourcePolicy(ctx, tx)
	if err != nil {
		return err
	}
	if p.DailyTokens != nil && int64(record.MaxOutputTokens) > policy.ReserveTokens {
		issue.Metric, issue.Used, issue.Limit = "OUTPUT_ENVELOPE", big.NewInt(int64(record.MaxOutputTokens)).String(), big.NewInt(policy.ReserveTokens).String()
		return issue
	}
	if p.DailyTokens != nil && new(big.Int).Add(u.Tokens, big.NewInt(policy.ReserveTokens)).Cmp(big.NewInt(*p.DailyTokens)) > 0 {
		issue.Metric, issue.Used, issue.Limit, issue.Reservation = "TOKENS", u.Tokens.String(), big.NewInt(*p.DailyTokens).String(), big.NewInt(policy.ReserveTokens).String()
		return issue
	}
	if p.DailyUSD != nil && new(big.Rat).Add(u.USD, decimal(policy.ReserveUSD)).Cmp(decimal(*p.DailyUSD)) > 0 {
		issue.Metric, issue.Used, issue.Limit, issue.Reservation = "USD", resourceDecimal(u.USD), *p.DailyUSD, policy.ReserveUSD
		return issue
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO research_schedule_inference_admissions VALUES(?,?,?,?)", record.ID, id, day, p.ApprovalID)
	return err
}

// A rejected private authorization never spins at the worker's 30-second tick.
// Automatic UTC recovery is a separate explicit policy; unavailable evidence
// and manual pause always require a user action.
func (s *Server) privateScheduleBudgetWait(ctx context.Context, run, task string, cause error) bool {
	var id, raw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT s.id,s.definition_json FROM research_schedule_runs r JOIN research_schedules s ON s.id=r.schedule_id WHERE r.run_id=?", run).Scan(&id, &raw) != nil {
		return false
	}
	var d scheduleDefinition
	if json.Unmarshal([]byte(raw), &d) != nil || d.Private == nil {
		return false
	}
	if cause.Error() == "research inference budget exhausted" {
		_, err := s.Store.DB.ExecContext(ctx, "UPDATE research_tasks SET attempts=MAX(0,attempts-1) WHERE id=? AND run_id=?", task, run)
		return err == nil
	}
	var resource *researchResourceBudgetError
	var daily *researchInferenceBudgetError
	if !errors.As(cause, &resource) && !errors.As(cause, &daily) {
		return false
	}
	intent, wait := 0, int64(0)
	if d.Private.ResumeAfterReset && (resource == nil || resource.Metric != "UNAVAILABLE" && resource.Metric != "OUTPUT_ENVELOPE") {
		intent = 1
		wait = nextBudgetDay(s.scheduleTime())
		if resource != nil && resource.ResetAt > wait {
			wait = resource.ResetAt
		}
		if daily != nil && daily.ResetAt > wait {
			wait = daily.ResetAt
		}
	}
	_, err := s.Store.DB.ExecContext(ctx, "UPDATE research_schedule_runs SET intent=?,resume_at=? WHERE run_id=?", intent, wait, run)
	return err == nil
}

func scheduleRoundFull(v researchSchedule) bool {
	return v.RunUsed >= v.RunBudget || v.Private != nil && v.RunProbeBudget > 0 && v.RunProbes >= v.RunProbeBudget
}

func (s *Server) reviewPrivateResearchSchedule(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if !validID(id) {
		http.Error(w, "exact schedule required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "schedule review unavailable", 503)
		return
	}
	defer tx.Rollback()
	var raw, name string
	var revision int64
	if tx.QueryRowContext(r.Context(), "SELECT definition_json,name,revision FROM research_schedules WHERE id=?", id).Scan(&raw, &name, &revision) != nil {
		http.Error(w, "schedule missing", 404)
		return
	}
	var d scheduleDefinition
	if json.Unmarshal([]byte(raw), &d) != nil || d.Private == nil {
		http.Error(w, "private schedule required", 422)
		return
	}
	fingerprint, err := s.privateScheduleFingerprint(r.Context(), tx, d)
	if err != nil {
		http.Error(w, "private scopes unavailable; correct the selected connection first", 422)
		return
	}
	list := []map[string]any{}
	for _, v := range d.Options.Selections {
		selected, e := s.authenticityScope(r.Context(), tx, v)
		if e != nil {
			http.Error(w, "private scope unavailable", 422)
			return
		}
		list = append(list, map[string]any{"selection": v, "origin": selected.Origin, "operation": selected.Operation, "credential_version": selected.CredentialVersion})
	}
	if tx.Commit() != nil {
		http.Error(w, "schedule review unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"id": id, "name": name, "revision": revision, "fingerprint": fingerprint, "scopes": list, "private": d.Private, "options": d.Options})
}

func (s *Server) privateResearchScheduleHistory(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if !validID(id) {
		http.Error(w, "exact schedule required", 400)
		return
	}
	before := int64(1<<63 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		n, e := strconv.ParseInt(raw, 10, 64)
		if e != nil || n < 1 {
			http.Error(w, "invalid approval cursor", 400)
			return
		}
		before = n
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,revision,definition_json,approved_at FROM research_schedule_private_approvals WHERE schedule_id=? AND revision<? ORDER BY revision DESC LIMIT 51", id, before)
	if err != nil {
		http.Error(w, "private approval history unavailable", 503)
		return
	}
	defer rows.Close()
	list := []map[string]any{}
	for rows.Next() {
		var approval, raw string
		var revision, at int64
		if err = rows.Scan(&approval, &revision, &raw, &at); err != nil {
			break
		}
		var d scheduleDefinition
		if json.Unmarshal([]byte(raw), &d) != nil || d.Private == nil || !d.Private.valid() {
			http.Error(w, "private approval history unavailable", 503)
			return
		}
		list = append(list, map[string]any{"approval_id": approval, "revision": revision, "approved_at": at, "definition": d})
	}
	if err != nil || rows.Err() != nil {
		http.Error(w, "private approval history unavailable", 503)
		return
	}
	next := int64(0)
	if len(list) > 50 {
		list = list[:50]
		next = list[49]["revision"].(int64)
	}
	jsonOK(w, 200, map[string]any{"history": list, "next_before": next})
}
