package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
)

func (s *Server) getResearchResourceBudget(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("history") == "1" {
		s.resourceBudgetHistory(w, r)
		return
	}
	var after researchInferenceScope
	if cursor := r.URL.Query().Get("cursor"); cursor != "" {
		if len(cursor) > 3072 {
			http.Error(w, "invalid resource budget cursor", 400)
			return
		}
		raw, err := base64.RawURLEncoding.DecodeString(cursor)
		if err != nil || json.Unmarshal(raw, &after) != nil || !after.valid() {
			http.Error(w, "invalid resource budget cursor", 400)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	defer tx.Rollback()
	policy, err := readResearchResourcePolicy(r.Context(), tx)
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	instant := s.scheduleTime()
	day := instant.Format("2006-01-02")
	usage, scopes, err := readResourceUsage(r.Context(), tx, day)
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	var older int
	if err = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM research_inference_admissions a LEFT JOIN research_resource_admissions r ON r.request_id=a.request_id WHERE a.day=? AND r.request_id IS NULL", day).Scan(&older); err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	rows, err := tx.QueryContext(r.Context(), `WITH scopes AS (
SELECT provider_id,account_id,id AS credential_id FROM credentials WHERE role='INFERENCE'
UNION SELECT provider_id,account_id,credential_id FROM research_resource_limits
UNION SELECT a.provider_id,a.account_id,a.credential_id FROM research_inference_admissions a JOIN research_resource_admissions r ON r.request_id=a.request_id WHERE a.day=?)
SELECT s.provider_id,s.account_id,s.credential_id,COALESCE(p.name,s.provider_id),COALESCE(a.label,s.account_id),COALESCE(c.label,s.credential_id),COALESCE(c.enabled,0),l.limits_json FROM scopes s LEFT JOIN providers p ON p.id=s.provider_id LEFT JOIN accounts a ON a.id=s.account_id AND a.provider_id=s.provider_id LEFT JOIN credentials c ON c.id=s.credential_id AND c.provider_id=s.provider_id AND c.account_id=s.account_id AND c.role='INFERENCE' LEFT JOIN research_resource_limits l ON l.provider_id=s.provider_id AND l.account_id=s.account_id AND l.credential_id=s.credential_id WHERE (s.provider_id,s.account_id,s.credential_id)>(?,?,?) ORDER BY s.provider_id,s.account_id,s.credential_id LIMIT 101`, day, after.ProviderID, after.AccountID, after.CredentialID)
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	type credential struct {
		researchInferenceScope
		ProviderName string                 `json:"provider_name"`
		AccountLabel string                 `json:"account_label"`
		Label        string                 `json:"label"`
		Available    bool                   `json:"available"`
		Override     bool                   `json:"override"`
		Limits       researchResourceLimits `json:"limits"`
		Usage        map[string]any         `json:"usage"`
	}
	list := []credential{}
	for rows.Next() {
		var v credential
		var raw sql.NullString
		if err = rows.Scan(&v.ProviderID, &v.AccountID, &v.CredentialID, &v.ProviderName, &v.AccountLabel, &v.Label, &v.Available, &raw); err != nil {
			break
		}
		v.Limits = researchResourceLimits{policy.CredentialTokens, policy.CredentialUSD}
		v.Override = raw.Valid
		if raw.Valid {
			v.Limits, err = decodeResourceLimits(raw.String)
			if err != nil {
				break
			}
		}
		u := scopes[v.researchInferenceScope]
		if u == nil {
			u = newResourceUsage()
		}
		v.Usage = u.view()
		list = append(list, v)
	}
	rowErr := rows.Err()
	rows.Close()
	if err != nil || rowErr != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	next := ""
	if len(list) > 100 {
		list = list[:100]
		raw, _ := json.Marshal(list[99].researchInferenceScope)
		next = base64.RawURLEncoding.EncodeToString(raw)
	}
	if err = tx.Commit(); err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": policy.Revision, "started_at": policy.StartedAt, "policy": policy.researchResourcePolicy, "day": day, "reset_at": nextBudgetDay(instant), "usage": usage.view(), "older_unbudgeted_requests": older, "credentials": list, "next_cursor": next, "lifecycle": "THIS_ENGINE", "budget_basis": "LOCAL_RESERVATIONS_AND_REPORTED_USAGE"})
}

func (s *Server) setResearchResourceBudget(w http.ResponseWriter, r *http.Request) {
	var raw map[string]json.RawMessage
	if !decodeJSON(w, r, &raw) {
		return
	}
	encoded, _ := json.Marshal(raw)
	var v struct {
		Action   string `json:"action"`
		Revision int64  `json:"revision"`
		researchInferenceScope
		researchResourcePolicy
		researchResourceLimits
	}
	if json.Unmarshal(encoded, &v) != nil || v.Revision < 1 || !oneOf(v.Action, "POLICY", "CREDENTIAL", "RESET") {
		http.Error(w, "invalid resource budget", 400)
		return
	}
	allowed := map[string]bool{"action": true, "revision": true}
	keys := []string{"provider_id", "account_id", "credential_id"}
	if v.Action == "POLICY" {
		keys = []string{"daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd"}
	} else if v.Action == "CREDENTIAL" {
		keys = append(keys, "tokens", "usd")
	}
	for _, key := range keys {
		allowed[key] = true
	}
	for key := range raw {
		if !allowed[key] {
			http.Error(w, "unexpected resource budget field", 400)
			return
		}
	}
	if v.Action == "POLICY" {
		for _, name := range []string{"daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd"} {
			if len(raw[name]) == 0 {
				http.Error(w, "explicit resource limits required; null disables a limit", 400)
				return
			}
		}
		if !v.researchResourcePolicy.valid() || v.ProviderID != "" || v.AccountID != "" || v.CredentialID != "" {
			http.Error(w, "invalid global resource budget", 400)
			return
		}
	} else if !v.researchInferenceScope.valid() || v.Action == "CREDENTIAL" && (len(raw["tokens"]) == 0 || len(raw["usd"]) == 0 || !v.researchResourceLimits.valid()) {
		http.Error(w, "exact credential and explicit resource limits required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	defer tx.Rollback()
	policy, err := readResearchResourcePolicy(r.Context(), tx)
	if err != nil {
		http.Error(w, "resource budget unavailable", 503)
		return
	}
	if policy.Revision != v.Revision {
		http.Error(w, "resource budget changed; reload before saving", 409)
		return
	}
	var value any
	if v.Action == "POLICY" {
		value = v.researchResourcePolicy
	} else if v.Action == "CREDENTIAL" {
		var count int
		if err = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='INFERENCE'", v.ProviderID, v.AccountID, v.CredentialID).Scan(&count); err != nil {
			http.Error(w, "resource budget unavailable", 503)
			return
		}
		if count != 1 {
			http.Error(w, "inference credential unavailable", 422)
			return
		}
		value = v.researchResourceLimits
	} else {
		value = map[string]any{"use_default": true}
	}
	data, _ := json.Marshal(value)
	if v.Action == "POLICY" {
		_, err = tx.ExecContext(r.Context(), "UPDATE research_resource_policy SET policy_json=? WHERE id=1", string(data))
	} else if v.Action == "CREDENTIAL" {
		_, err = tx.ExecContext(r.Context(), "INSERT INTO research_resource_limits VALUES(?,?,?,?) ON CONFLICT(provider_id,account_id,credential_id) DO UPDATE SET limits_json=excluded.limits_json", v.ProviderID, v.AccountID, v.CredentialID, string(data))
	} else {
		var result sql.Result
		result, err = tx.ExecContext(r.Context(), "DELETE FROM research_resource_limits WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID)
		if err == nil {
			var count int64
			count, err = result.RowsAffected()
			if err == nil && count != 1 {
				http.Error(w, "credential resource override unavailable", 422)
				return
			}
		}
	}
	if err == nil {
		var result sql.Result
		result, err = tx.ExecContext(r.Context(), "UPDATE research_resource_policy SET revision=revision+1 WHERE id=1 AND revision=?", v.Revision)
		if err == nil {
			var count int64
			count, err = result.RowsAffected()
			if err == nil && count != 1 {
				http.Error(w, "resource budget changed", 409)
				return
			}
		}
	}
	if err == nil {
		_, err = tx.ExecContext(r.Context(), "INSERT INTO research_resource_history VALUES(?,?,?,?,?,?,?)", v.Revision+1, v.Action, v.ProviderID, v.AccountID, v.CredentialID, string(data), s.scheduleTime().UnixMilli())
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		http.Error(w, "resource budget not saved", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": v.Revision + 1})
}

func (s *Server) resourceBudgetHistory(w http.ResponseWriter, r *http.Request) {
	before := int64(1<<63 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n < 1 {
			http.Error(w, "invalid resource history cursor", 400)
			return
		}
		before = n
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT revision,action,provider_id,account_id,credential_id,value_json,changed_at FROM research_resource_history WHERE revision<? ORDER BY revision DESC LIMIT 51", before)
	if err != nil {
		http.Error(w, "resource history unavailable", 503)
		return
	}
	defer rows.Close()
	type event struct {
		Revision int64  `json:"revision"`
		Action   string `json:"action"`
		researchInferenceScope
		Value     json.RawMessage `json:"value"`
		ChangedAt int64           `json:"changed_at"`
	}
	list := []event{}
	for rows.Next() {
		var v event
		var raw string
		if err = rows.Scan(&v.Revision, &v.Action, &v.ProviderID, &v.AccountID, &v.CredentialID, &raw, &v.ChangedAt); err != nil {
			break
		}
		v.Value = json.RawMessage(raw)
		if !json.Valid(v.Value) {
			err = sql.ErrNoRows
			break
		}
		list = append(list, v)
	}
	if err != nil || rows.Err() != nil {
		http.Error(w, "resource history unavailable", 503)
		return
	}
	next := int64(0)
	if len(list) > 50 {
		list = list[:50]
		next = list[49].Revision
	}
	jsonOK(w, 200, map[string]any{"history": list, "next_before": next})
}
