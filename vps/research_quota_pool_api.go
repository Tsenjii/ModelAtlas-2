package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
)

func (s *Server) getResearchQuotaPools(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("history") == "1" {
		s.researchQuotaPoolHistory(w, r)
		return
	}
	after := r.URL.Query().Get("after")
	if after != "" && !validID(after) {
		http.Error(w, "invalid pool cursor", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "research pools unavailable", 503)
		return
	}
	defer tx.Rollback()
	revision, started, pools, err := readResearchQuotaPools(r.Context(), tx)
	if err != nil {
		http.Error(w, "research pools unavailable", 503)
		return
	}
	instant := s.scheduleTime()
	day := instant.Format("2006-01-02")
	list := []map[string]any{}
	next := ""
	for _, pool := range pools {
		if pool.ID <= after {
			continue
		}
		if len(list) == 20 {
			next = list[19]["definition"].(researchQuotaPool).ID
			break
		}
		u, requests, older, e := quotaPoolUsage(r.Context(), tx, pool, day, "")
		if e != nil {
			http.Error(w, "research pool usage unavailable", 503)
			return
		}
		list = append(list, map[string]any{"definition": pool, "usage": u.view(), "today_requests": requests, "older_unbudgeted_requests": older})
	}
	if err = tx.Commit(); err != nil {
		http.Error(w, "research pools unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": revision, "started_at": started, "day": day, "reset_at": nextBudgetDay(instant), "pools": list, "next_after": next, "lifecycle": "THIS_ENGINE", "basis": "LOCAL_RESEARCH_AUTHORIZATION"})
}

func (s *Server) setResearchQuotaPool(w http.ResponseWriter, r *http.Request) {
	var fields map[string]json.RawMessage
	if !decodeJSON(w, r, &fields) {
		return
	}
	raw, _ := json.Marshal(fields)
	var v struct {
		Revision int64 `json:"revision"`
		researchQuotaPool
	}
	if !resourceFields(string(raw), "revision", "id", "label", "kind", "provider_id", "account_id", "project", "enabled", "requests", "tokens", "usd", "members") || json.Unmarshal(raw, &v) != nil || v.Revision < 1 || !v.researchQuotaPool.valid() || string(fields["enabled"]) != "true" && string(fields["enabled"]) != "false" {
		http.Error(w, "exact pool definition and explicit nullable limits required", 400)
		return
	}
	var members []json.RawMessage
	if json.Unmarshal(fields["members"], &members) != nil {
		http.Error(w, "exact pool members required", 400)
		return
	}
	for _, member := range members {
		if !resourceFields(string(member), "provider_id", "account_id", "credential_id") {
			http.Error(w, "exact pool members required", 400)
			return
		}
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, secret := range secrets {
		if jsonContainsSecret(v.researchQuotaPool, secret) {
			http.Error(w, "raw secrets are not pool metadata", 422)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "research pools unavailable", 503)
		return
	}
	defer tx.Rollback()
	revision, _, pools, err := readResearchQuotaPools(r.Context(), tx)
	if err != nil {
		http.Error(w, "research pools unavailable", 503)
		return
	}
	if revision != v.Revision {
		http.Error(w, "research pools changed; reload before saving", 409)
		return
	}
	var previous *researchQuotaPool
	for i := range pools {
		if pools[i].ID == v.ID {
			previous = &pools[i]
			break
		}
	}
	if previous == nil && len(pools) >= 100 {
		http.Error(w, "research pool limit reached", 422)
		return
	}
	if previous != nil && (previous.Kind != v.Kind || previous.ProviderID != v.ProviderID || previous.AccountID != v.AccountID || previous.Project != v.Project) {
		http.Error(w, "pool identity is immutable; disable it and create another pool", 422)
		return
	}
	if previous == nil && oneOf(v.Kind, "PROVIDER", "ACCOUNT", "PROJECT") {
		var count int
		query := "SELECT COUNT(*) FROM providers WHERE id=?"
		args := []any{v.ProviderID}
		if v.Kind == "ACCOUNT" {
			query = "SELECT COUNT(*) FROM accounts WHERE provider_id=? AND id=?"
			args = append(args, v.AccountID)
		}
		if err = tx.QueryRowContext(r.Context(), query, args...).Scan(&count); err != nil {
			http.Error(w, "pool scope unavailable", 503)
			return
		}
		if count != 1 {
			http.Error(w, "pool scope unavailable", 422)
			return
		}
	}
	for _, member := range v.Members {
		retained := false
		if previous != nil {
			for _, old := range previous.Members {
				if old == member {
					retained = true
				}
			}
		}
		if retained {
			continue
		}
		var count int
		if err = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='INFERENCE'", member.ProviderID, member.AccountID, member.CredentialID).Scan(&count); err != nil {
			http.Error(w, "pool member unavailable", 503)
			return
		}
		if count != 1 {
			http.Error(w, "exact inference credential required for every new member", 422)
			return
		}
	}
	day := s.scheduleTime().Format("2006-01-02")
	if previous != nil {
		if err = snapshotQuotaPoolUse(r.Context(), tx, *previous, day, v.Revision); err != nil {
			http.Error(w, "research pool usage not saved", 503)
			return
		}
	}
	definition, _ := json.Marshal(v.researchQuotaPool)
	_, err = tx.ExecContext(r.Context(), "INSERT INTO research_quota_pools VALUES(?,?) ON CONFLICT(id) DO UPDATE SET definition_json=excluded.definition_json", v.ID, string(definition))
	if err == nil {
		err = snapshotQuotaPoolUse(r.Context(), tx, v.researchQuotaPool, day, v.Revision+1)
	}
	if err == nil {
		var result sql.Result
		result, err = tx.ExecContext(r.Context(), "UPDATE research_quota_pool_policy SET revision=revision+1 WHERE id=1 AND revision=?", v.Revision)
		if err == nil {
			var count int64
			count, err = result.RowsAffected()
			if err == nil && count != 1 {
				http.Error(w, "research pools changed", 409)
				return
			}
		}
	}
	if err == nil {
		_, err = tx.ExecContext(r.Context(), "INSERT INTO research_quota_pool_history VALUES(?,?,?,?)", v.Revision+1, v.ID, string(definition), s.scheduleTime().UnixMilli())
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		http.Error(w, "research pool not saved", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": v.Revision + 1, "id": v.ID})
}

func (s *Server) researchQuotaPoolHistory(w http.ResponseWriter, r *http.Request) {
	before := int64(1<<63 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		n, e := strconv.ParseInt(raw, 10, 64)
		if e != nil || n < 1 {
			http.Error(w, "invalid pool history cursor", 400)
			return
		}
		before = n
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT revision,pool_id,definition_json,changed_at FROM research_quota_pool_history WHERE revision<? ORDER BY revision DESC LIMIT 51", before)
	if err != nil {
		http.Error(w, "pool history unavailable", 503)
		return
	}
	defer rows.Close()
	list := []map[string]any{}
	for rows.Next() {
		var revision, at int64
		var id, raw string
		if err = rows.Scan(&revision, &id, &raw, &at); err != nil {
			break
		}
		var definition researchQuotaPool
		if json.Unmarshal([]byte(raw), &definition) != nil || !definition.valid() || definition.ID != id {
			http.Error(w, "pool history unavailable", 503)
			return
		}
		list = append(list, map[string]any{"revision": revision, "pool_id": id, "definition": definition, "changed_at": at})
	}
	if err != nil || rows.Err() != nil {
		http.Error(w, "pool history unavailable", 503)
		return
	}
	next := int64(0)
	if len(list) > 50 {
		list = list[:50]
		next = list[49]["revision"].(int64)
	}
	jsonOK(w, 200, map[string]any{"history": list, "next_before": next})
}
