package main

import (
	"context"
	"encoding/json"
	"net/http"
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
	out := map[string]any{"notifications": false, "performance_window": "7d", "timezone": "Asia/Taipei", "retention": "PERMANENT"}
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
	for k, raw := range v {
		switch k {
		case "notifications":
			var n bool
			if json.Unmarshal(raw, &n) != nil {
				http.Error(w, "invalid notification preference", 400)
				return
			}
		case "performance_window":
			var n string
			if json.Unmarshal(raw, &n) != nil || !oneOf(n, "1h", "24h", "7d", "30d", "all") {
				http.Error(w, "invalid window", 400)
				return
			}
		case "timezone":
			var n string
			if json.Unmarshal(raw, &n) != nil {
				http.Error(w, "invalid timezone", 400)
				return
			}
			if _, err := time.LoadLocation(n); err != nil {
				http.Error(w, "invalid timezone", 400)
				return
			}
		default:
			http.Error(w, "unsupported setting", 400)
			return
		}
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
	queries := map[string]string{"endpoints": "SELECT provider_id,id,operation,url FROM endpoint_profiles", "endpoint_bindings": "SELECT provider_id,endpoint_id,operation,url,created_at FROM endpoint_bindings", "providers": "SELECT id,name,base_url,description,auth_mode FROM providers", "accounts": "SELECT id,provider_id,label FROM accounts", "credentials": "SELECT c.id,c.provider_id,c.account_id,c.label,c.role,c.enabled,COALESCE(x.group_id,'') AS group_id FROM credentials c LEFT JOIN credential_context x ON x.provider_id=c.provider_id AND x.account_id=c.account_id AND x.credential_id=c.id", "models": "SELECT provider_id,id,display_name,provenance FROM models", "my_models": "SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM my_models", "provider_config": "SELECT provider_id,config_json FROM provider_config", "model_metadata": "SELECT provider_id,model_id,canonical_id,metadata_json FROM model_metadata"}
	out := map[string]any{"format": "modelatlas-safe-export", "version": 1, "secrets_included": false}
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
				obj[key] = values[i]
			}
			list = append(list, obj)
		}
		rows.Close()
		out[table] = list
	}
	w.Header().Set("Content-Disposition", `attachment; filename="modelatlas-config.json"`)
	jsonOK(w, 200, out)
}
