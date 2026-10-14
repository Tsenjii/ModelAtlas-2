package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
)

type SessionSettings struct {
	System    string `json:"system"`
	MaxTokens int    `json:"max_tokens"`
	Streaming bool   `json:"streaming"`
}

func (s *Server) sessionSettings(ctx context.Context, id string) (SessionSettings, error) {
	out := SessionSettings{MaxTokens: 512, Streaming: true}
	var raw string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT settings_json FROM session_settings WHERE session_id=?", id).Scan(&raw)
	if e == sql.ErrNoRows {
		return out, nil
	}
	if e != nil {
		return out, e
	}
	e = json.Unmarshal([]byte(raw), &out)
	return out, e
}
func (s *Server) manualSession(ctx context.Context, id string) bool {
	var n int
	return validID(id) && s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM sessions s WHERE s.id=? AND NOT EXISTS(SELECT 1 FROM assistant_threads a WHERE a.session_id=s.id)", id).Scan(&n) == nil && n == 1
}
func (s *Server) getSessionSettings(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("session_id")
	if !s.manualSession(r.Context(), id) {
		http.Error(w, "manual conversation missing", 404)
		return
	}
	v, e := s.sessionSettings(r.Context(), id)
	if e != nil {
		http.Error(w, "settings unavailable", 503)
		return
	}
	tools, e := s.sessionTools(r.Context(), id, nil)
	if e != nil {
		http.Error(w, "tools unavailable", 503)
		return
	}
	jsonOK(w, 200, struct {
		SessionSettings
		Tools []ToolDefinition `json:"tools"`
	}{v, tools})
}
func (s *Server) setSessionSettings(w http.ResponseWriter, r *http.Request) {
	var v struct {
		SessionID string `json:"session_id"`
		SessionSettings
	}
	if !decodeLimit(w, r, &v, 20000) {
		return
	}
	if !s.manualSession(r.Context(), v.SessionID) {
		http.Error(w, "manual conversation missing", 404)
		return
	}
	if v.MaxTokens < 1 || v.MaxTokens > 32768 || len(v.System) > 16000 || rawSecretPattern.MatchString(v.System) {
		http.Error(w, "invalid conversation settings", 400)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, secret := range secrets {
		if jsonContainsSecret(v.SessionSettings, secret) {
			http.Error(w, "secret found in system instruction", 422)
			return
		}
	}
	s.flightMu.Lock()
	defer s.flightMu.Unlock()
	if s.flights[v.SessionID] {
		http.Error(w, "conversation is generating", 409)
		return
	}
	raw, _ := json.Marshal(v.SessionSettings)
	if _, e = s.Store.DB.ExecContext(r.Context(), "INSERT INTO session_settings VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET settings_json=excluded.settings_json", v.SessionID, string(raw)); e != nil {
		http.Error(w, "settings unavailable", 503)
		return
	}
	jsonOK(w, 200, v.SessionSettings)
}
