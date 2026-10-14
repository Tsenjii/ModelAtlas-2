package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

type ProviderConfig struct {
	Adapter         string            `json:"adapter"`
	AuthMode        string            `json:"auth_mode"`
	AuthHeader      string            `json:"auth_header,omitempty"`
	AuthQuery       string            `json:"auth_query,omitempty"`
	AuthPrefix      string            `json:"auth_prefix,omitempty"`
	Headers         map[string]string `json:"headers,omitempty"`
	Endpoints       map[string]string `json:"endpoints,omitempty"`
	Website         string            `json:"website,omitempty"`
	RequiresCheckin bool              `json:"requires_daily_checkin"`
	CheckinURL      string            `json:"checkin_url,omitempty"`
	Notes           string            `json:"notes,omitempty"`
	ProjectID       string            `json:"project_id,omitempty"`
}

var endpointNames = map[string]string{"CHAT_COMPLETIONS": "/chat/completions", "RESPONSES": "/responses", "ANTHROPIC_MESSAGES": "/messages", "GEMINI_NATIVE": "/models/{model}:streamGenerateContent", "MODELS": "/models", "IMAGES_GENERATION": "/images/generations", "IMAGES_EDIT": "/images/edits", "AUDIO": "/audio/transcriptions", "EMBEDDINGS": "/embeddings", "RERANK": "/rerank"}
var fieldName = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func safePath(path string) bool {
	u, err := url.Parse(path)
	return err == nil && strings.HasPrefix(path, "/") && !strings.HasPrefix(path, "//") && u.Host == "" && u.Scheme == "" && u.RawQuery == "" && u.Fragment == "" && !strings.ContainsAny(path, "\\\r\n\x00") && !strings.Contains(u.Path, "..") && len(path) <= 256
}
func safeConfig(c ProviderConfig) bool {
	if c.Adapter == "" {
		c.Adapter = "GENERIC"
	}
	if _, ok := adapterRegistry[c.Adapter]; !ok {
		return false
	}
	if !oneOf(c.AuthMode, "", "BEARER", "NONE", "API_KEY", "CUSTOM_HEADER") || len(c.Notes) > 4096 || len(c.ProjectID) > 128 {
		return false
	}
	if c.Website != "" && !validateHTTPS(c.Website) || c.CheckinURL != "" && !validateHTTPS(c.CheckinURL) {
		return false
	}
	if c.RequiresCheckin && c.CheckinURL == "" {
		return false
	}
	if len(c.Endpoints) > 12 || len(c.Headers) > 8 || len(c.AuthPrefix) > 32 || strings.ContainsAny(c.AuthPrefix, "\r\n\x00") {
		return false
	}
	for name, path := range c.Endpoints {
		if _, ok := endpointNames[name]; !ok || !safePath(path) {
			return false
		}
	}
	if c.AuthHeader != "" && !fieldName.MatchString(c.AuthHeader) || c.AuthQuery != "" && !fieldName.MatchString(c.AuthQuery) || c.AuthHeader != "" && c.AuthQuery != "" {
		return false
	}
	for _, name := range []string{c.AuthHeader, c.AuthQuery} {
		if oneOf(strings.ToLower(name), "host", "cookie", "content-length", "connection", "proxy-authorization", "origin", "referer") {
			return false
		}
	}
	for name, value := range c.Headers {
		if !oneOf(strings.ToLower(name), "openai-organization", "openai-project", "anthropic-version", "x-project-id") || len(value) > 256 || strings.ContainsAny(value, "\r\n\x00") {
			return false
		}
	}
	return true
}
func oneOf(s string, values ...string) bool {
	for _, v := range values {
		if s == v {
			return true
		}
	}
	return false
}
func (s *Server) config(ctx context.Context, p Provider) ProviderConfig {
	c := ProviderConfig{Adapter: "GENERIC", AuthMode: p.AuthMode}
	var raw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT config_json FROM provider_config WHERE provider_id=?", p.ID).Scan(&raw) == nil {
		_ = json.Unmarshal([]byte(raw), &c)
	}
	if c.Adapter == "" {
		c.Adapter = "GENERIC"
	}
	if c.AuthMode == "" {
		c.AuthMode = p.AuthMode
	}
	var recipeRaw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT v.recipe_json FROM recipe_versions v JOIN recipe_active a ON a.provider_id=v.provider_id AND a.version=v.version WHERE v.provider_id=?", p.ID).Scan(&recipeRaw) == nil {
		var recipe map[string]any
		if json.Unmarshal([]byte(recipeRaw), &recipe) == nil {
			if c.Endpoints == nil {
				c.Endpoints = map[string]string{}
			}
			for k, kind := range map[string]string{"catalog_path": "MODELS", "chat_path": "CHAT_COMPLETIONS"} {
				if path, ok := recipe[k].(string); ok && safePath(path) {
					c.Endpoints[kind] = path
				}
			}
		}
	}

	return c
}
func (s *Server) getConfig(w http.ResponseWriter, r *http.Request) {
	p, err := s.provider(r.Context(), r.URL.Query().Get("provider_id"))
	if err != nil {
		http.Error(w, "provider missing", 404)
		return
	}
	jsonOK(w, 200, s.config(r.Context(), p))
}
func (s *Server) setConfig(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string         `json:"provider_id"`
		Config     ProviderConfig `json:"config"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	p, err := s.provider(r.Context(), v.ProviderID)
	if err != nil {
		http.Error(w, "provider missing", 404)
		return
	}
	if v.Config.AuthMode == "" {
		v.Config.AuthMode = p.AuthMode
	}
	if v.Config.Adapter == "" {
		v.Config.Adapter = "GENERIC"
	}
	if !safeConfig(v.Config) {
		http.Error(w, "invalid provider configuration", 400)
		return
	}
	// An auth-mode change cannot silently reinterpret existing secrets.
	previous := s.config(r.Context(), p)
	if previous.AuthMode != v.Config.AuthMode {
		http.Error(w, "auth mode is immutable; create a reviewed connection", 409)
		return
	}
	raw, _ := json.Marshal(v.Config)
	if _, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO provider_config VALUES(?,?) ON CONFLICT(provider_id) DO UPDATE SET config_json=excluded.config_json", p.ID, string(raw)); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, v.Config)
}
func (s *Server) provider(ctx context.Context, id string) (Provider, error) {
	var p Provider
	err := s.Store.DB.QueryRowContext(ctx, "SELECT id,name,base_url,description,auth_mode FROM providers WHERE id=?", id).Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode)
	return p, err
}
func endpointURL(p Provider, c ProviderConfig, kind, model string) (string, error) {
	if !validateHTTPS(p.BaseURL) {
		return "", errors.New("invalid base URL")
	}
	path := c.Endpoints[kind]
	custom := path != ""
	if path == "" {
		path = endpointNames[kind]
	}
	if path == "" || !safePath(path) {
		return "", errors.New("unsupported endpoint")
	}
	path = strings.ReplaceAll(path, "{model}", url.PathEscape(strings.TrimPrefix(model, "models/")))
	if custom {
		return originPath(p, path), nil
	}
	base := strings.TrimRight(p.BaseURL, "/")
	if strings.HasSuffix(base, path) {
		return base, nil
	}
	return base + path, nil
}
func (s *Server) authorizeUpstream(req *http.Request, p Provider, key string, c ProviderConfig) error {
	if !validateHTTPS(p.BaseURL) || req.URL.Scheme != "https" {
		return errors.New("HTTPS required")
	}
	origin, _ := url.Parse(p.BaseURL)
	if !strings.EqualFold(req.URL.Host, origin.Host) {
		return errors.New("credential origin mismatch")
	}
	for k, v := range c.Headers {
		req.Header.Set(k, v)
	}
	if c.AuthMode == "NONE" {
		if key != "" {
			return errors.New("anonymous secret")
		}
		return nil
	}
	if key == "" && len(p.AuthFields) == 0 || strings.ContainsAny(key, "\r\n\x00") {
		return errors.New("invalid credential")
	}
	if key == "" {
		return applyAuthFields(req, p.AuthFields)
	}
	if c.AuthQuery != "" {
		q := req.URL.Query()
		q.Set(c.AuthQuery, key)
		req.URL.RawQuery = q.Encode()
		return applyAuthFields(req, p.AuthFields)
	}
	header, prefix := c.AuthHeader, c.AuthPrefix
	if header == "" {
		header = "Authorization"
		prefix = "Bearer "
		if c.AuthMode == "API_KEY" {
			header = "x-api-key"
			prefix = ""
		}
		if c.Adapter == "GEMINI_FREE" {
			header = "x-goog-api-key"
			prefix = ""
		}
	}
	req.Header.Set(header, prefix+key)
	if c.Adapter == "ANTHROPIC" {
		req.Header.Set("anthropic-version", "2023-06-01")
	}
	return applyAuthFields(req, p.AuthFields)
}
func applyAuthFields(req *http.Request, fields []AuthField) error {
	for _, f := range fields {
		if f.Query {
			q := req.URL.Query()
			if q.Has(f.Name) {
				return errors.New("duplicate auth query")
			}
			q.Set(f.Name, f.Secret)
			req.URL.RawQuery = q.Encode()
		} else {
			if req.Header.Get(f.Name) != "" {
				return errors.New("duplicate auth header")
			}
			req.Header.Set(f.Name, f.Secret)
		}
	}
	return nil
}
func scopeKey(v Selection) string { raw, _ := json.Marshal(v); return string(raw) }
