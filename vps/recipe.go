package main

import (
	"encoding/json"
	"net/http"
	"strings"
)

type Recipe struct {
	SiteDraft          *siteRecipeDocument   `json:"site_draft,omitempty"`
	Test               *siteRecipeEvaluation `json:"test,omitempty"`
	Status             string                `json:"status,omitempty"`
	ActiveID           string                `json:"active_id,omitempty"`
	ActivationActionID string                `json:"activation_action_id,omitempty"`
	ID                 string                `json:"id"`
	ProviderID         string                `json:"provider_id"`
	Version            int                   `json:"version"`
	Settings           map[string]any        `json:"settings"`
	Reason             string                `json:"reason"`
	Active             bool                  `json:"active"`
}

func safeRecipe(settings map[string]any) bool {
	if len(settings) > 12 {
		return false
	}
	for k, v := range settings {
		switch k {
		case "generated_recipe_id":
			if !validID(str(v)) {
				return false
			}
		case "catalog_path", "chat_path":
			if path, ok := v.(string); !ok || !safePath(path) {
				return false
			}
		case "checkin_url":
			if value, ok := v.(string); !ok || !validateHTTPS(value) {
				return false
			}
		case "notes":
			if s, ok := v.(string); !ok || len(s) > 1024 || strings.ContainsAny(s, "\r\n") {
				return false
			}
		case "sync_enabled", "supports_tools", "supports_vision":
			if _, ok := v.(bool); !ok {
				return false
			}
		default:
			return false
		}
	}
	return true
}
func (s *Server) listRecipes(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT v.id,v.provider_id,v.version,v.recipe_json,v.reason,COALESCE(a.version,-1) FROM recipe_versions v LEFT JOIN recipe_active a ON a.provider_id=v.provider_id ORDER BY v.provider_id,v.version DESC LIMIT 100")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Recipe{}
	for rows.Next() {
		var x Recipe
		var raw string
		var current int
		if rows.Scan(&x.ID, &x.ProviderID, &x.Version, &raw, &x.Reason, &current) == nil {
			if json.Unmarshal([]byte(raw), &x.Settings) == nil {
				x.Active = x.Version == current
				out = append(out, x)
			}
		}
	}
	if err = rows.Err(); err != nil {
		http.Error(w, "recipe history unavailable", 503)
		return
	}
	rows.Close()
	if err = s.enrichSiteRecipes(r.Context(), out); err != nil {
		http.Error(w, "recipe evidence unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) addRecipe(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string         `json:"provider_id"`
		Settings   map[string]any `json:"settings"`
		Reason     string         `json:"reason"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if str(v.Settings["generated_recipe_id"]) != "" {
		http.Error(w, "generated recipes require local source evidence", 422)
		return
	}
	if !validID(v.ProviderID) || len(v.Reason) > 512 || !safeRecipe(v.Settings) {
		http.Error(w, "unsafe recipe fields", 400)
		return
	}
	if v.Reason == "" {
		http.Error(w, "reason required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var n int
	if tx.QueryRowContext(r.Context(), "SELECT 1 FROM providers WHERE id=?", v.ProviderID).Scan(&n) != nil {
		http.Error(w, "provider missing", 404)
		return
	}
	var version int
	if tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM recipe_versions WHERE provider_id=?", v.ProviderID).Scan(&version) != nil {
		http.Error(w, "database error", 500)
		return
	}
	raw, _ := json.Marshal(v.Settings)
	id := newID()
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO recipe_versions(id,provider_id,version,recipe_json,reason,created_at) VALUES(?,?,?,?,?,?)", id, v.ProviderID, version, string(raw), v.Reason, now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if err = bindLegacyRecipe(r.Context(), tx, id, string(raw)); err != nil {
		http.Error(w, "recipe local approval unavailable", 503)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO recipe_active(provider_id,version) VALUES(?,?) ON CONFLICT(provider_id) DO UPDATE SET version=excluded.version", v.ProviderID, version); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if err = tx.Commit(); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, Recipe{ID: id, ProviderID: v.ProviderID, Version: version, Settings: v.Settings, Reason: v.Reason, Active: true})
}
func (s *Server) rollbackRecipe(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string `json:"provider_id"`
		Version    int    `json:"version"`
		Confirm    bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !validID(v.ProviderID) || v.Version < 1 {
		http.Error(w, "explicit confirmation and target version required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var id, raw string
	if tx.QueryRowContext(r.Context(), "SELECT id,recipe_json FROM recipe_versions WHERE provider_id=? AND version=?", v.ProviderID, v.Version).Scan(&id, &raw) != nil {
		http.Error(w, "version missing", 404)
		return
	}
	if err = bindLegacyRecipe(r.Context(), tx, id, raw); err != nil {
		http.Error(w, "recipe needs its own local review", 409)
		return
	}
	var before int
	if tx.QueryRowContext(r.Context(), "SELECT version FROM recipe_active WHERE provider_id=?", v.ProviderID).Scan(&before) != nil {
		http.Error(w, "active version missing", 409)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "UPDATE recipe_active SET version=? WHERE provider_id=?", v.Version, v.ProviderID); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO action_ledger(id,action,subject,before_json,after_json,state,created_at) VALUES(?,'ROLLBACK_RECIPE',?,?,?,'CONFIRMED',?)", newID(), v.ProviderID, jsonNumber(before), jsonNumber(v.Version), now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"provider_id": v.ProviderID, "from_version": before, "active_version": v.Version})
}
func jsonNumber(v int) string { b, _ := json.Marshal(v); return string(b) }
