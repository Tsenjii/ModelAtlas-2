package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func (s *Server) createTestSession(ctx context.Context, v Selection) (string, error) {
	id := newID()
	_, err := s.Store.DB.ExecContext(ctx, "INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?)", id, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, "測試 · "+v.ModelID, now())
	return id, err
}
func (s *Server) testModelHTTP(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.validateSelection(r.Context(), v) {
		http.Error(w, "selected model missing", 422)
		return
	}
	id, err := s.createTestSession(r.Context(), v)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	result, _ := s.execute(r.Context(), inferRequest{SessionID: id, Prompt: "Reply OK.", MaxTokens: 16}, "MANUAL_TEST", func(string, any) error { return nil })
	jsonOK(w, 200, result)
}
func (s *Server) compare(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Selections []Selection `json:"selections"`
		Prompt     string      `json:"prompt"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if len(v.Selections) < 2 || len(v.Selections) > 4 || len(v.Prompt) > 1000 {
		http.Error(w, "compare requires 2..4 explicit selections", 400)
		return
	}
	if v.Prompt == "" {
		v.Prompt = "Reply OK."
	}
	canonical := ""
	seen := map[string]bool{}
	for _, selection := range v.Selections {
		if !s.validateSelection(r.Context(), selection) {
			http.Error(w, "selection unavailable", 422)
			return
		}
		id := s.metadata(r.Context(), selection.ProviderID, selection.ModelID).CanonicalID
		if canonical == "" {
			canonical = id
		}
		if canonical != id || seen[scopeKey(selection)] {
			http.Error(w, "compare the same canonical model with distinct offerings", 422)
			return
		}
		seen[scopeKey(selection)] = true
	}
	out := []InferenceResult{}
	for _, selection := range v.Selections {
		if r.Context().Err() != nil {
			return
		}
		id, err := s.createTestSession(r.Context(), selection)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		result, _ := s.execute(r.Context(), inferRequest{SessionID: id, Prompt: v.Prompt, MaxTokens: 32}, "COMPARE_LIVE", func(string, any) error { return nil })
		out = append(out, result)
	}
	jsonOK(w, 200, out)
}
func (s *Server) listCheckins(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,date,completed_at FROM checkins ORDER BY completed_at DESC LIMIT 200")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var p, d string
		var at int64
		if rows.Scan(&p, &d, &at) == nil {
			out = append(out, map[string]any{"provider_id": p, "date": d, "completed_at": at})
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) completeCheckin(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string `json:"provider_id"`
		Timezone   string `json:"timezone"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	p, err := s.provider(r.Context(), v.ProviderID)
	if err != nil || !s.config(r.Context(), p).RequiresCheckin {
		http.Error(w, "check-in not configured", 422)
		return
	}
	if v.Timezone == "" {
		v.Timezone = "Asia/Taipei"
	}
	zone, err := time.LoadLocation(v.Timezone)
	if err != nil {
		http.Error(w, "invalid timezone", 400)
		return
	}
	date := time.Now().In(zone).Format("2006-01-02")
	_, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO checkins VALUES(?,?,?) ON CONFLICT(provider_id,date) DO UPDATE SET completed_at=excluded.completed_at", p.ID, date, now())
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"provider_id": p.ID, "date": date, "source": "MANUAL_COMPLETION"})
}
func (s *Server) getSettings(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT key,value_json FROM settings")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := map[string]any{"notifications": false, "performance_window": "7d", "timezone": "Asia/Taipei", "retention": "PERMANENT", "background": BackgroundSettings{IntervalMinutes: 60, MaxScopes: 2}, "notification_rules": NotificationRules{QuotaRemainingBelow: 100}, "ranking_weights": map[string]float64{"strength": 1, "speed": 1, "quota": 1, "reliability": 1}}
	for rows.Next() {
		var key, raw string
		if rows.Scan(&key, &raw) == nil {
			var v any
			if json.Unmarshal([]byte(raw), &v) == nil {
				out[key] = v
			}
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) saveSettings(w http.ResponseWriter, r *http.Request) {
	var v map[string]json.RawMessage
	if !decodeJSON(w, r, &v) {
		return
	}
	if err := validateSettings(v); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	for key, raw := range v {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json", key, string(raw)); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]bool{"saved": true})
}
func (s *Server) exportBackup(w http.ResponseWriter, r *http.Request) {
	// Portable configuration export intentionally omits keys, PATs and chat content.
	queries := map[string]string{"endpoints": "SELECT provider_id,id,operation,url FROM endpoint_profiles", "endpoint_bindings": "SELECT provider_id,endpoint_id,operation,url,created_at FROM endpoint_bindings", "providers": "SELECT id,name,base_url,description,auth_mode FROM providers", "accounts": "SELECT id,provider_id,label FROM accounts", "credentials": "SELECT c.id,c.provider_id,c.account_id,c.label,c.role,c.enabled,COALESCE(x.group_id,'') AS group_id,COALESCE(o.base_url,p.base_url) AS base_url,COALESCE(o.auth_mode,'') AS auth_mode FROM credentials c JOIN providers p ON p.id=c.provider_id LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id LEFT JOIN credential_context x ON x.provider_id=c.provider_id AND x.account_id=c.account_id AND x.credential_id=c.id", "models": "SELECT provider_id,id,display_name,provenance FROM models", "my_models": "SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM my_models", "provider_config": "SELECT provider_id,config_json FROM provider_config", "model_metadata": "SELECT provider_id,model_id,canonical_id,metadata_json FROM model_metadata"}
	extraQueries := map[string]string{"aliases": "SELECT provider_id,raw_id,canonical_id,source FROM aliases", "notes": "SELECT subject_type,subject_id,notes,tags_json FROM entity_notes", "settings": "SELECT key,value_json FROM settings WHERE key IN ('notifications','background','notification_rules','ranking_weights','performance_window','timezone')", "membership_state": "SELECT scope_key,state,favorite,watchlist FROM membership_state", "recipes": "SELECT id,provider_id,version,recipe_json,reason,created_at FROM recipe_versions", "research_sources": "SELECT id,name,kind,url,language,enabled,config_json,created_at FROM research_sources", "knowledge": "SELECT id,kind,subject,version,data_json,evidence_json,reason,created_at FROM knowledge_versions", "connectors": "SELECT id,provider_id,name,version,config_json,evidence_json,reason,created_at FROM connector_versions"}
	for key, query := range extraQueries {
		queries[key] = query
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	out := map[string]any{"format": "modelatlas-safe-export", "version": 2, "secrets_included": false}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	for table, query := range queries {
		rows, err := tx.QueryContext(r.Context(), query)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		cols, _ := rows.Columns()
		list := []map[string]any{}
		for rows.Next() {
			values := make([]any, len(cols))
			ptrs := make([]any, len(cols))
			for i := range values {
				ptrs[i] = &values[i]
			}
			if rows.Scan(ptrs...) != nil {
				continue
			}
			obj := map[string]any{}
			for i, key := range cols {
				if oneOf(key, "favorite", "watchlist", "enabled") && oneOf(table, "membership_state", "research_sources") {
					obj[key] = fmt.Sprint(values[i]) == "1"
				} else {
					obj[key] = values[i]
				}
			}
			list = append(list, obj)
		}
		rows.Close()
		out[table] = list
	}
	for _, secret := range secrets {
		if jsonContainsSecret(out, secret) {
			http.Error(w, "secret found in configuration metadata; use encrypted database backup", 422)
			return
		}
	}
	w.Header().Set("Content-Disposition", `attachment; filename="modelatlas-config.json"`)
	jsonOK(w, 200, out)
}

func validateSettings(v map[string]json.RawMessage) error {
	for k, raw := range v {
		switch k {
		case "background":
			var b BackgroundSettings
			if json.Unmarshal(raw, &b) != nil || b.IntervalMinutes < 15 || b.IntervalMinutes > 10080 || b.MaxScopes < 1 || b.MaxScopes > 10 {
				return errors.New("background interval 15..10080 minutes and 1..10 scopes required")
			}
		case "notification_rules":
			var rules NotificationRules
			d := json.NewDecoder(strings.NewReader(string(raw)))
			d.DisallowUnknownFields()
			if d.Decode(&rules) != nil || rules.QuotaRemainingBelow < 0 || rules.QuotaRemainingBelow > 1000000000 {
				return errors.New("invalid notification rules")
			}
		case "notifications":
			var n bool
			if json.Unmarshal(raw, &n) != nil {
				return errors.New("invalid notification preference")
			}
		case "ranking_weights":
			var weights map[string]float64
			if json.Unmarshal(raw, &weights) != nil || len(weights) != 4 {
				return errors.New("four bounded ranking weights required")
			}
			for key, value := range weights {
				max := 5.0
				if key == "reliability" {
					max = 3
				}
				if !oneOf(key, "strength", "speed", "quota", "reliability") || value < 0 || value > max {
					return errors.New("invalid ranking weight")
				}
			}
			if weights["strength"]+weights["speed"]+weights["quota"] == 0 {
				return errors.New("at least one ranking component required")
			}
		case "performance_window":
			var n string
			if json.Unmarshal(raw, &n) != nil || !oneOf(n, "1h", "24h", "7d", "30d", "all") {
				return errors.New("invalid window")
			}
		case "timezone":
			var n string
			if json.Unmarshal(raw, &n) != nil {
				return errors.New("invalid timezone")
			}
			if _, err := time.LoadLocation(n); err != nil {
				return errors.New("invalid timezone")
			}
		default:
			return errors.New("unsupported setting")
		}
	}

	return nil
}
