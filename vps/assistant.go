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
	if v.Action != "ADD_MY_MODEL" && v.Action != "REMOVE_MY_MODEL" {
		http.Error(w, "tool not allowlisted", 422)
		return
	}
	if v.Selection.Endpoint != "CHAT_COMPLETIONS" || !validID(v.Selection.ProviderID) || !validID(v.Selection.AccountID) || !validID(v.Selection.CredentialID) || !validID(v.Selection.ModelID) || len(v.Selection.Group) > 120 {
		http.Error(w, "invalid scope", 422)
		return
	}
	if !s.validateSelection(r.Context(), v.Selection) && v.Action == "ADD_MY_MODEL" {
		// Adding a model requires a real scoped credential, not preexisting membership.
		var n int
		err := s.Store.DB.QueryRowContext(r.Context(), "SELECT 1 FROM models m JOIN credentials c ON c.provider_id=m.provider_id WHERE m.provider_id=? AND m.id=? AND c.account_id=? AND c.id=? AND c.enabled=1 AND c.role='INFERENCE'", v.Selection.ProviderID, v.Selection.ModelID, v.Selection.AccountID, v.Selection.CredentialID).Scan(&n)
		if err != nil {
			http.Error(w, "model or credential not found", 422)
			return
		}
	}
	exists := s.validateSelection(r.Context(), v.Selection)
	if v.Action == "ADD_MY_MODEL" && exists || v.Action == "REMOVE_MY_MODEL" && !exists {
		http.Error(w, "action not applicable", 409)
		return
	}
	v.ID = newID()
	v.State = "PENDING"
	raw, _ := json.Marshal(v.Selection)
	before, _ := json.Marshal(map[string]bool{"existed": exists})
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO action_ledger(id,action,subject,before_json,after_json,state,created_at) VALUES(?,?,?,?,?,'PENDING',?)", v.ID, v.Action, v.Selection.ProviderID, string(before), string(raw), now())
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) applyMembership(tx *sql.Tx, v Selection, add bool) error {
	if add {
		_, err := tx.Exec("INSERT INTO my_models(provider_id,account_id,credential_id,model_id,group_id,endpoint,created_at) VALUES(?,?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, now())
		return err
	}
	result, err := tx.Exec("DELETE FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n != 1 {
		return sql.ErrNoRows
	}
	return nil
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
	var action, raw, state string
	if tx.QueryRowContext(r.Context(), "SELECT action,after_json,state FROM action_ledger WHERE id=?", v.ID).Scan(&action, &raw, &state) != nil {
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
	add := action == "ADD_MY_MODEL"
	if undo {
		add = !add
	}
	if add {
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
	result, err := tx.Exec("UPDATE action_ledger SET state=? WHERE id=? AND state=?", next, v.ID, target)
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
