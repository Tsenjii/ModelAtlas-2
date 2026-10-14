package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

type AssistantAction struct {
	ID        string    `json:"id"`
	Action    string    `json:"action"`
	Selection Selection `json:"selection"`
	State     string    `json:"state"`
}

func (s *Server) assistantPreview(w http.ResponseWriter, r *http.Request) {
	var v AssistantAction
	if !decodeJSON(w, r, &v) {
		return
	}
	if !oneOf(v.Action, "ADD_MY_MODEL", "REMOVE_MY_MODEL") || !validScope(v.Selection) {
		http.Error(w, "invalid allowlisted action or scope", 422)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var role string
	var enabled int
	if tx.QueryRowContext(r.Context(), "SELECT role,enabled FROM credentials WHERE provider_id=? AND account_id=? AND id=?", v.Selection.ProviderID, v.Selection.AccountID, v.Selection.CredentialID).Scan(&role, &enabled) != nil || role != "INFERENCE" || v.Action == "ADD_MY_MODEL" && enabled != 1 {
		http.Error(w, "inference credential required", 422)
		return
	}
	var count int
	if tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.Selection.ProviderID, v.Selection.AccountID, v.Selection.CredentialID, v.Selection.ModelID, v.Selection.Group, v.Selection.Endpoint).Scan(&count) != nil {
		http.Error(w, "database error", 500)
		return
	}
	exists := count > 0
	if v.Action == "ADD_MY_MODEL" && exists || v.Action == "REMOVE_MY_MODEL" && !exists {
		http.Error(w, "action not applicable", 409)
		return
	}
	var present int
	if tx.QueryRowContext(r.Context(), "SELECT 1 FROM models WHERE provider_id=? AND id=?", v.Selection.ProviderID, v.Selection.ModelID).Scan(&present) != nil {
		http.Error(w, "model missing", 422)
		return
	}
	revision, err := membershipRevision(tx, v.Selection)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	v.ID = newID()
	v.State = "PENDING"
	raw, _ := json.Marshal(v.Selection)
	before, _ := json.Marshal(map[string]any{"existed": exists, "revision": revision})
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO action_ledger(id,action,subject,before_json,after_json,state,created_at) VALUES(?,?,?,?,?,'PENDING',?)", v.ID, v.Action, v.Selection.ProviderID, string(before), string(raw), now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) applyMembership(tx *sql.Tx, v Selection, add bool) error {
	if add {
		_, err := tx.Exec("INSERT INTO my_models(provider_id,account_id,credential_id,model_id,group_id,endpoint,created_at) VALUES(?,?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, now())
		if err != nil {
			return err
		}
		return bumpMembership(tx, v)
	}
	result, err := tx.Exec("DELETE FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n != 1 {
		return sql.ErrNoRows
	}
	return bumpMembership(tx, v)
}
func (s *Server) assistantTransition(w http.ResponseWriter, r *http.Request, undo bool) {
	var v struct {
		ID      string `json:"id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !validID(v.ID) {
		http.Error(w, "explicit confirmation required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var action, raw, state, beforeRaw string
	if tx.QueryRowContext(r.Context(), "SELECT action,after_json,state,before_json FROM action_ledger WHERE id=?", v.ID).Scan(&action, &raw, &state, &beforeRaw) != nil {
		http.Error(w, "action missing", 404)
		return
	}
	target := "PENDING"
	next := "CONFIRMED"
	if undo {
		target = "CONFIRMED"
		next = "UNDONE"
	}
	if state != target {
		http.Error(w, "action no longer available", 409)
		return
	}
	if action != "ADD_MY_MODEL" && action != "REMOVE_MY_MODEL" {
		http.Error(w, "action not supported", 422)
		return
	}
	var choice Selection
	if json.Unmarshal([]byte(raw), &choice) != nil {
		http.Error(w, "invalid action", 500)
		return
	}
	var expected struct {
		Revision *int64 `json:"revision"`
	}
	if json.Unmarshal([]byte(beforeRaw), &expected) != nil || expected.Revision == nil {
		http.Error(w, "legacy preview must be recreated", 409)
		return
	}
	revision, err := membershipRevision(tx, choice)
	if err != nil || revision != *expected.Revision {
		http.Error(w, "membership changed since preview or action; refuse stale undo", 409)
		return
	}
	add := action == "ADD_MY_MODEL"
	if undo {
		add = !add
	}
	if add {
		var p Provider
		if tx.QueryRowContext(r.Context(), "SELECT id,name,base_url,description,auth_mode FROM providers WHERE id=?", choice.ProviderID).Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode) != nil {
			http.Error(w, "provider missing", 409)
			return
		}
		cfg := ProviderConfig{AuthMode: p.AuthMode}
		var raw string
		if tx.QueryRowContext(r.Context(), "SELECT config_json FROM provider_config WHERE provider_id=?", p.ID).Scan(&raw) == nil {
			_ = json.Unmarshal([]byte(raw), &cfg)
		}
		if bindEndpoint(tx, p, cfg, choice.Endpoint) != nil {
			http.Error(w, "endpoint unavailable", 409)
			return
		}
		var allowed int
		if tx.QueryRowContext(r.Context(), "SELECT 1 FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='INFERENCE' AND enabled=1", choice.ProviderID, choice.AccountID, choice.CredentialID).Scan(&allowed) != nil {
			http.Error(w, "credential role or enabled state changed", 409)
			return
		}
	}
	if err = s.applyMembership(tx, choice, add); err != nil {
		http.Error(w, "conflicting membership state", 409)
		return
	}
	revision, err = membershipRevision(tx, choice)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	before, _ := json.Marshal(map[string]any{"revision": revision})
	result, err := tx.Exec("UPDATE action_ledger SET state=?,before_json=? WHERE id=? AND state=?", next, string(before), v.ID, target)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	changed, _ := result.RowsAffected()
	if changed != 1 {
		http.Error(w, "action race", 409)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	if add {
		s.scheduleEnrichment(r.Context(), choice)
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "state": next, "history_preserved": true})
}
func (s *Server) assistantConfirm(w http.ResponseWriter, r *http.Request) {
	s.assistantTransition(w, r, false)
}
func (s *Server) assistantUndo(w http.ResponseWriter, r *http.Request) {
	s.assistantTransition(w, r, true)
}
