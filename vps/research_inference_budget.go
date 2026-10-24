package main

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
)

// Local authorization, never a provider quota or an exact billing meter. These
// tables deliberately stay outside workspace sync and portable settings import.
const researchInferenceBudgetSchema = `
CREATE TABLE IF NOT EXISTS research_inference_policy(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,daily_limit INTEGER NOT NULL CHECK(daily_limit BETWEEN 0 AND 2000),default_credential_limit INTEGER NOT NULL CHECK(default_credential_limit BETWEEN 0 AND 2000),started_at INTEGER NOT NULL);
INSERT INTO research_inference_policy VALUES(1,1,100,20,CAST(strftime('%s','now') AS INTEGER)*1000) ON CONFLICT(id) DO NOTHING;
CREATE TABLE IF NOT EXISTS research_inference_limits(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,daily_limit INTEGER NOT NULL CHECK(daily_limit BETWEEN 0 AND 2000),PRIMARY KEY(provider_id,account_id,credential_id));
CREATE TABLE IF NOT EXISTS research_inference_admissions(request_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,task_id TEXT NOT NULL,day TEXT NOT NULL,admitted_at INTEGER NOT NULL,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,credential_version INTEGER NOT NULL,selection_json TEXT NOT NULL,policy_revision INTEGER NOT NULL,daily_limit INTEGER NOT NULL,credential_limit INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_inference_admissions_day ON research_inference_admissions(day,provider_id,account_id,credential_id);
CREATE TABLE IF NOT EXISTS research_inference_policy_history(revision INTEGER PRIMARY KEY,action TEXT NOT NULL,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,daily_limit INTEGER,default_credential_limit INTEGER,changed_at INTEGER NOT NULL);
INSERT INTO research_inference_policy_history SELECT revision,'POLICY','','','',daily_limit,default_credential_limit,started_at FROM research_inference_policy WHERE revision=1 ON CONFLICT(revision) DO NOTHING;
`

type researchInferenceScope struct {
	ProviderID   string `json:"provider_id"`
	AccountID    string `json:"account_id"`
	CredentialID string `json:"credential_id"`
}

func (v researchInferenceScope) valid() bool {
	return validID(v.ProviderID) && validID(v.AccountID) && validID(v.CredentialID)
}

type researchInferencePolicy struct {
	Revision               int64 `json:"revision"`
	DailyLimit             int   `json:"daily_limit"`
	DefaultCredentialLimit int   `json:"default_credential_limit"`
	StartedAt              int64 `json:"started_at"`
}

func readResearchInferencePolicy(ctx context.Context, q scheduleQuerier) (v researchInferencePolicy, err error) {
	err = q.QueryRowContext(ctx, "SELECT revision,daily_limit,default_credential_limit,started_at FROM research_inference_policy WHERE id=1").Scan(&v.Revision, &v.DailyLimit, &v.DefaultCredentialLimit, &v.StartedAt)
	if err == nil && (v.Revision < 1 || v.DailyLimit < 0 || v.DailyLimit > 2000 || v.DefaultCredentialLimit < 0 || v.DefaultCredentialLimit > 2000) {
		err = errors.New("invalid local inference budget policy")
	}
	return
}

type researchInferenceBudgetError struct {
	Scope   string `json:"scope"`
	Limit   int    `json:"limit"`
	Used    int    `json:"used"`
	Day     string `json:"day"`
	ResetAt int64  `json:"reset_at"`
	researchInferenceScope
}

func (e *researchInferenceBudgetError) Error() string {
	if e.Scope == "CREDENTIAL" {
		return "research credential daily inference budget exhausted"
	}
	return "research engine daily inference budget exhausted"
}

// Called inside conversation admission: a rejected policy, run budget or local
// write rolls back every meter. Rotation/model/group/endpoint changes do not
// erase the stable provider/account/credential's daily reservation count.
func (s *Server) reserveResearchInference(ctx context.Context, tx *sql.Tx, record requestRecord) error {
	if record.Source != "CRAWLER_VERIFY" {
		return nil
	}
	policy, err := readResearchInferencePolicy(ctx, tx)
	if err != nil {
		return err
	}
	v := record.Selection
	limit := policy.DefaultCredentialLimit
	if err = tx.QueryRowContext(ctx, "SELECT daily_limit FROM research_inference_limits WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&limit); err != nil && err != sql.ErrNoRows {
		return err
	}
	instant := s.researchAdmissionTime(ctx)
	day := instant.Format("2006-01-02")
	var all, credential int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(provider_id=? AND account_id=? AND credential_id=?),0) FROM research_inference_admissions WHERE day=?", v.ProviderID, v.AccountID, v.CredentialID, day).Scan(&all, &credential); err != nil {
		return err
	}
	issue := &researchInferenceBudgetError{Day: day, ResetAt: nextBudgetDay(instant), researchInferenceScope: researchInferenceScope{v.ProviderID, v.AccountID, v.CredentialID}}
	if all >= policy.DailyLimit {
		issue.Scope, issue.Limit, issue.Used = "ENGINE", policy.DailyLimit, all
		return issue
	}
	if credential >= limit {
		issue.Scope, issue.Limit, issue.Used = "CREDENTIAL", limit, credential
		return issue
	}
	run, _ := ctx.Value(researchBudgetKey{}).(string)
	task, _ := ctx.Value(researchTaskBudgetKey{}).(string)
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO research_inference_admissions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", record.ID, run, task, day, instant.UnixMilli(), v.ProviderID, v.AccountID, v.CredentialID, record.CredentialVersion, string(raw), policy.Revision, policy.DailyLimit, limit)
	return err
}

type researchInferenceCredential struct {
	researchInferenceScope
	ProviderName string `json:"provider_name"`
	AccountLabel string `json:"account_label"`
	Label        string `json:"label"`
	Available    bool   `json:"available"`
	Used         int    `json:"used"`
	DailyLimit   int    `json:"daily_limit"`
	Override     bool   `json:"override"`
}

func (s *Server) getResearchInferenceBudget(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("history") == "1" {
		s.researchInferenceBudgetHistory(w, r)
		return
	}
	var after researchInferenceScope
	if cursor := r.URL.Query().Get("cursor"); cursor != "" {
		if len(cursor) > 3072 {
			http.Error(w, "invalid credential cursor", 400)
			return
		}
		raw, err := base64.RawURLEncoding.DecodeString(cursor)
		if err != nil || json.Unmarshal(raw, &after) != nil || !after.valid() {
			http.Error(w, "invalid credential cursor", 400)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	defer tx.Rollback()
	ctx := r.Context()
	policy, err := readResearchInferencePolicy(ctx, tx)
	if err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	instant := s.scheduleTime()
	day := instant.Format("2006-01-02")
	var used int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_inference_admissions WHERE day=?", day).Scan(&used); err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	rows, err := tx.QueryContext(ctx, `WITH scopes AS (
SELECT provider_id,account_id,id AS credential_id FROM credentials WHERE role='INFERENCE'
UNION SELECT provider_id,account_id,credential_id FROM research_inference_limits
UNION SELECT provider_id,account_id,credential_id FROM research_inference_admissions WHERE day=?)
SELECT s.provider_id,s.account_id,s.credential_id,COALESCE(p.name,s.provider_id),COALESCE(a.label,s.account_id),COALESCE(c.label,s.credential_id),COALESCE(c.enabled,0),COALESCE(l.daily_limit,?),l.daily_limit IS NOT NULL,(SELECT COUNT(*) FROM research_inference_admissions i WHERE i.day=? AND i.provider_id=s.provider_id AND i.account_id=s.account_id AND i.credential_id=s.credential_id)
FROM scopes s LEFT JOIN providers p ON p.id=s.provider_id LEFT JOIN accounts a ON a.provider_id=s.provider_id AND a.id=s.account_id LEFT JOIN credentials c ON c.provider_id=s.provider_id AND c.account_id=s.account_id AND c.id=s.credential_id AND c.role='INFERENCE' LEFT JOIN research_inference_limits l ON l.provider_id=s.provider_id AND l.account_id=s.account_id AND l.credential_id=s.credential_id
WHERE (s.provider_id,s.account_id,s.credential_id)>(?,?,?) ORDER BY s.provider_id,s.account_id,s.credential_id LIMIT 101`, day, policy.DefaultCredentialLimit, day, after.ProviderID, after.AccountID, after.CredentialID)
	if err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	list := []researchInferenceCredential{}
	for rows.Next() {
		var v researchInferenceCredential
		if err = rows.Scan(&v.ProviderID, &v.AccountID, &v.CredentialID, &v.ProviderName, &v.AccountLabel, &v.Label, &v.Available, &v.DailyLimit, &v.Override, &v.Used); err != nil {
			break
		}
		list = append(list, v)
	}
	rowErr := rows.Err()
	rows.Close()
	if err != nil || rowErr != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	next := ""
	if len(list) > 100 {
		list = list[:100]
		raw, _ := json.Marshal(list[99].researchInferenceScope)
		next = base64.RawURLEncoding.EncodeToString(raw)
	}
	if err = tx.Commit(); err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": policy.Revision, "daily_limit": policy.DailyLimit, "default_credential_limit": policy.DefaultCredentialLimit, "started_at": policy.StartedAt, "day": day, "today_used": used, "reset_at": nextBudgetDay(instant), "credentials": list, "next_cursor": next, "lifecycle": "THIS_ENGINE", "token_usage": nil, "currency_cost": nil, "provider_remaining": nil})
}

func (s *Server) setResearchInferenceBudget(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Action                 string `json:"action"`
		Revision               int64  `json:"revision"`
		DailyLimit             *int   `json:"daily_limit"`
		DefaultCredentialLimit *int   `json:"default_credential_limit"`
		researchInferenceScope
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	bounded := func(n *int) bool { return n != nil && *n >= 0 && *n <= 2000 }
	if v.Revision < 1 || !oneOf(v.Action, "POLICY", "CREDENTIAL", "RESET") || v.Action == "POLICY" && (!bounded(v.DailyLimit) || !bounded(v.DefaultCredentialLimit)) || v.Action != "POLICY" && !v.researchInferenceScope.valid() || v.Action == "CREDENTIAL" && !bounded(v.DailyLimit) {
		http.Error(w, "bounded inference budget and exact scope required", 400)
		return
	}
	if v.Action == "POLICY" && (v.ProviderID != "" || v.AccountID != "" || v.CredentialID != "") {
		http.Error(w, "global policy cannot contain a credential scope", 400)
		return
	}
	if v.Action != "POLICY" {
		v.DefaultCredentialLimit = nil
	}
	if v.Action == "RESET" {
		v.DailyLimit = nil
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	defer tx.Rollback()
	ctx := r.Context()
	policy, err := readResearchInferencePolicy(ctx, tx)
	if err != nil {
		http.Error(w, "inference budget unavailable", 500)
		return
	}
	if policy.Revision != v.Revision {
		http.Error(w, "inference budget changed; reload before saving", 409)
		return
	}
	if v.Action == "CREDENTIAL" {
		var count int
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='INFERENCE'", v.ProviderID, v.AccountID, v.CredentialID).Scan(&count); err != nil {
			http.Error(w, "inference budget unavailable", 500)
			return
		}
		if count != 1 {
			http.Error(w, "inference credential unavailable", 422)
			return
		}
		_, err = tx.ExecContext(ctx, "INSERT INTO research_inference_limits VALUES(?,?,?,?) ON CONFLICT(provider_id,account_id,credential_id) DO UPDATE SET daily_limit=excluded.daily_limit", v.ProviderID, v.AccountID, v.CredentialID, *v.DailyLimit)
	} else if v.Action == "RESET" {
		var deleted sql.Result
		deleted, err = tx.ExecContext(ctx, "DELETE FROM research_inference_limits WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID)
		if err == nil {
			if n, rowsErr := deleted.RowsAffected(); rowsErr != nil {
				err = rowsErr
			} else if n != 1 {
				http.Error(w, "credential override unavailable", 422)
				return
			}
		}
	} else {
		_, err = tx.ExecContext(ctx, "UPDATE research_inference_policy SET daily_limit=?,default_credential_limit=? WHERE id=1", *v.DailyLimit, *v.DefaultCredentialLimit)
	}
	if err == nil {
		_, err = tx.ExecContext(ctx, "UPDATE research_inference_policy SET revision=revision+1 WHERE id=1 AND revision=?", v.Revision)
	}
	if err == nil {
		_, err = tx.ExecContext(ctx, "INSERT INTO research_inference_policy_history VALUES(?,?,?,?,?,?,?,?)", v.Revision+1, v.Action, v.ProviderID, v.AccountID, v.CredentialID, v.DailyLimit, v.DefaultCredentialLimit, s.scheduleTime().UnixMilli())
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		http.Error(w, "inference budget not saved", 500)
		return
	}
	// Saving limits never resets a reservation and never resumes a stopped run.
	jsonOK(w, 200, map[string]any{"revision": v.Revision + 1})
}

func (s *Server) researchInferenceBudgetHistory(w http.ResponseWriter, r *http.Request) {
	before := int64(1<<63 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n < 1 {
			http.Error(w, "invalid history cursor", 400)
			return
		}
		before = n
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT revision,action,provider_id,account_id,credential_id,daily_limit,default_credential_limit,changed_at FROM research_inference_policy_history WHERE revision<? ORDER BY revision DESC LIMIT 51", before)
	if err != nil {
		http.Error(w, "inference budget history unavailable", 500)
		return
	}
	defer rows.Close()
	type event struct {
		Revision int64  `json:"revision"`
		Action   string `json:"action"`
		researchInferenceScope
		DailyLimit             *int  `json:"daily_limit"`
		DefaultCredentialLimit *int  `json:"default_credential_limit"`
		ChangedAt              int64 `json:"changed_at"`
	}
	list := []event{}
	for rows.Next() {
		var v event
		if err = rows.Scan(&v.Revision, &v.Action, &v.ProviderID, &v.AccountID, &v.CredentialID, &v.DailyLimit, &v.DefaultCredentialLimit, &v.ChangedAt); err != nil {
			break
		}
		list = append(list, v)
	}
	if err != nil || rows.Err() != nil {
		http.Error(w, "inference budget history unavailable", 500)
		return
	}
	next := int64(0)
	if len(list) > 50 {
		list = list[:50]
		next = list[49].Revision
	}
	jsonOK(w, 200, map[string]any{"history": list, "next_before": next})
}
