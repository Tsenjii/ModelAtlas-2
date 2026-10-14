package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
)

type Provider struct {
	AuthFields  []AuthField     `json:"-"`
	ID          string          `json:"id"`
	Name        string          `json:"name"`
	BaseURL     string          `json:"base_url"`
	Description string          `json:"description"`
	AuthMode    string          `json:"auth_mode"`
	Config      *ProviderConfig `json:"config,omitempty"`
}
type Account struct {
	ID         string `json:"id"`
	ProviderID string `json:"provider_id"`
	Label      string `json:"label"`
}
type Credential struct {
	AuthFields []AuthField `json:"auth_fields,omitempty"`
	ID         string      `json:"id"`
	ProviderID string      `json:"provider_id"`
	AccountID  string      `json:"account_id"`
	Label      string      `json:"label"`
	Secret     string      `json:"secret,omitempty"`
	Enabled    bool        `json:"enabled"`
	Role       string      `json:"role"`
	Group      string      `json:"group"`
	Note       string      `json:"note,omitempty"`
	Priority   int         `json:"priority"`
}
type Model struct {
	ProviderID  string         `json:"provider_id"`
	ID          string         `json:"id"`
	DisplayName string         `json:"display_name"`
	PriceState  string         `json:"price_state"`
	QuotaState  string         `json:"quota_state"`
	Provenance  string         `json:"provenance"`
	Metadata    *ModelMetadata `json:"metadata,omitempty"`
}
type Selection struct {
	ProviderID   string `json:"provider_id"`
	AccountID    string `json:"account_id"`
	CredentialID string `json:"credential_id"`
	ModelID      string `json:"model_id"`
	Group        string `json:"group"`
	Endpoint     string `json:"endpoint"`
}

func (s *Server) listProviders(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,name,base_url,description,auth_mode FROM providers ORDER BY name,id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	a := []Provider{}
	for rows.Next() {
		var p Provider
		if rows.Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode) == nil {
			a = append(a, p)
		}
	}
	rows.Close()
	for i := range a {
		c := s.config(r.Context(), a[i])
		a[i].AuthMode = c.AuthMode
		a[i].Config = &c
	}
	jsonOK(w, 200, a)
}
func (s *Server) addProvider(w http.ResponseWriter, r *http.Request) {
	var p Provider
	if !decodeJSON(w, r, &p) {
		return
	}
	if !validID(p.ID) || len(p.Name) < 1 || len(p.Name) > 120 || !validateHTTPS(p.BaseURL) || len(p.Description) > 1000 {
		http.Error(w, "invalid provider fields", 400)
		return
	}
	if p.AuthMode == "" {
		p.AuthMode = "BEARER"
	}
	c := ProviderConfig{Adapter: "GENERIC", AuthMode: p.AuthMode}
	if p.Config != nil {
		c = *p.Config
		c.AuthMode = p.AuthMode
		if c.Adapter == "" {
			c.Adapter = "GENERIC"
		}
	}
	if !safeConfig(c) {
		http.Error(w, "unsupported provider configuration", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	mode := "BEARER"
	if c.AuthMode == "NONE" {
		mode = "NONE"
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO providers(id,name,base_url,description,auth_mode,created_at) VALUES(?,?,?,?,?,?)", p.ID, p.Name, p.BaseURL, p.Description, mode, now()); err != nil {
		http.Error(w, "provider conflict", 409)
		return
	}
	raw, _ := json.Marshal(c)
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO provider_config VALUES(?,?)", p.ID, string(raw)); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	p.Config = &c
	jsonOK(w, 201, p)
}
func (s *Server) listAccounts(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,provider_id,label FROM accounts ORDER BY provider_id,id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	a := []Account{}
	for rows.Next() {
		var v Account
		if rows.Scan(&v.ID, &v.ProviderID, &v.Label) == nil {
			a = append(a, v)
		}
	}
	jsonOK(w, 200, a)
}
func (s *Server) addAccount(w http.ResponseWriter, r *http.Request) {
	var a Account
	if !decodeJSON(w, r, &a) {
		return
	}
	if !validID(a.ID) || !validID(a.ProviderID) || len(a.Label) > 120 {
		http.Error(w, "invalid account", 400)
		return
	}
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO accounts(id,provider_id,label) VALUES(?,?,?)", a.ID, a.ProviderID, a.Label)
	if err != nil {
		http.Error(w, "missing provider or duplicate account", 409)
		return
	}
	jsonOK(w, 201, a)
}
func (s *Server) listCredentials(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT c.id,c.provider_id,c.account_id,c.label,c.enabled,c.role,COALESCE(x.group_id,''),COALESCE(x.note,''),COALESCE(x.priority,0) FROM credentials c LEFT JOIN credential_context x ON x.provider_id=c.provider_id AND x.account_id=c.account_id AND x.credential_id=c.id ORDER BY c.provider_id,c.account_id,c.id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	a := []Credential{}
	for rows.Next() {
		var v Credential
		var enabled int
		if rows.Scan(&v.ID, &v.ProviderID, &v.AccountID, &v.Label, &enabled, &v.Role, &v.Group, &v.Note, &v.Priority) == nil {
			v.Enabled = enabled == 1
			a = append(a, v)
		}
	}
	jsonOK(w, 200, a)
}
func (s *Server) addCredential(w http.ResponseWriter, r *http.Request) {
	var v Credential
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.Role == "" {
		v.Role = "INFERENCE"
	}
	if !oneOf(v.Role, "INFERENCE", "ACCOUNT_PAT") || !validID(v.ID) || !validID(v.ProviderID) || !validID(v.AccountID) || len(v.Label) > 120 || len(v.Note) > 512 || len(v.Secret) > 8192 || strings.ContainsAny(v.Secret, "\r\n\x00") || safeText(v.Group, 120) != v.Group {
		http.Error(w, "invalid credential", 400)
		return
	}
	p, err := s.provider(r.Context(), v.ProviderID)
	if err != nil {
		http.Error(w, "provider missing", 422)
		return
	}
	c := s.config(r.Context(), p)
	enc := ""
	if c.AuthMode != "NONE" || v.Role == "ACCOUNT_PAT" {
		if v.Secret == "" && !(c.AuthMode == "CUSTOM_HEADER" && len(v.AuthFields) > 0 && v.Role == "INFERENCE") {
			http.Error(w, "credential secret required", 400)
			return
		}
		if v.Secret != "" {
			enc, err = s.encrypt(v.Secret)
		}
		if err != nil {
			http.Error(w, "master encryption key required", 503)
			return
		}
	} else if v.Secret != "" || len(v.AuthFields) > 0 {
		http.Error(w, "anonymous provider cannot receive secret", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO credentials(id,provider_id,account_id,label,encrypted_key,role,enabled,created_at) VALUES(?,?,?,?,?,?,1,?)", v.ID, v.ProviderID, v.AccountID, v.Label, enc, v.Role, now()); err != nil {
		http.Error(w, "duplicate credential or missing account", 409)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_context VALUES(?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.ID, v.Group, v.Note, v.Priority); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	fields, err := s.encryptFields(v.AuthFields)
	if v.Role == "ACCOUNT_PAT" && len(v.AuthFields) > 0 {
		http.Error(w, "account PAT cannot carry custom inference auth", 400)
		return
	}
	if err != nil {
		http.Error(w, "invalid custom auth fields", 400)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_auth_fields VALUES(?,?,?,?)", v.ProviderID, v.AccountID, v.ID, fields); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	v.Secret = ""
	v.AuthFields = nil
	v.Enabled = true
	jsonOK(w, 201, v)
}
func (s *Server) toggleCredentials(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string   `json:"provider_id"`
		AccountID  string   `json:"account_id"`
		IDs        []string `json:"ids"`
		Enabled    bool     `json:"enabled"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ProviderID) || !validID(v.AccountID) || len(v.IDs) < 1 || len(v.IDs) > 100 {
		http.Error(w, "invalid credential selection", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	for _, id := range v.IDs {
		res, err := tx.ExecContext(r.Context(), "UPDATE credentials SET enabled=? WHERE provider_id=? AND account_id=? AND id=?", v.Enabled, v.ProviderID, v.AccountID, id)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		n, _ := res.RowsAffected()
		if n != 1 {
			http.Error(w, "credential missing", 404)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, v)
}
func (s *Server) listModels(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,id,display_name,price_state,quota_state,provenance FROM models ORDER BY provider_id,id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	a := []Model{}
	for rows.Next() {
		var m Model
		if rows.Scan(&m.ProviderID, &m.ID, &m.DisplayName, &m.PriceState, &m.QuotaState, &m.Provenance) == nil {
			a = append(a, m)
		}
	}
	rows.Close()
	out := []Model{}
	q := strings.ToLower(r.URL.Query().Get("q"))
	for _, m := range a {
		if p := r.URL.Query().Get("provider_id"); p != "" && m.ProviderID != p {
			continue
		}
		meta := s.metadata(r.Context(), m.ProviderID, m.ID)
		m.Metadata = &meta
		haystack := strings.ToLower(m.ID + " " + m.DisplayName + " " + meta.CanonicalID + " " + strings.Join(meta.Aliases, " "))
		if q != "" && !strings.Contains(haystack, q) && !strings.Contains(canonicalID(haystack), canonicalID(q)) {
			continue
		}
		if c := r.URL.Query().Get("capability"); c != "" && !oneOf(c, meta.Capabilities...) {
			continue
		}
		if c := r.URL.Query().Get("category"); c != "" && c != meta.Category {
			continue
		}
		out = append(out, m)
	}
	jsonOK(w, 200, out)
}
func (s *Server) addModel(w http.ResponseWriter, r *http.Request) {
	var m Model
	if !decodeJSON(w, r, &m) {
		return
	}
	if !validID(m.ProviderID) || !validID(m.ID) || len(m.DisplayName) < 1 || len(m.DisplayName) > 200 {
		http.Error(w, "invalid model", 400)
		return
	}
	if m.PriceState != "" && m.PriceState != "UNKNOWN" || m.QuotaState != "" && m.QuotaState != "UNKNOWN" {
		http.Error(w, "manual facts need a scoped annotation", 422)
		return
	}
	m.PriceState = "UNKNOWN"
	m.QuotaState = "UNKNOWN"
	m.Provenance = "MANUAL"
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO models(provider_id,id,display_name,price_state,quota_state,provenance,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(provider_id,id) DO UPDATE SET display_name=excluded.display_name,updated_at=excluded.updated_at WHERE models.provenance='MANUAL'", m.ProviderID, m.ID, m.DisplayName, m.PriceState, m.QuotaState, m.Provenance, now())
	if err != nil {
		http.Error(w, "provider missing", 409)
		return
	}
	jsonOK(w, 201, m)
}
func (s *Server) listMyModels(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM my_models ORDER BY created_at DESC")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	a := []Selection{}
	for rows.Next() {
		var v Selection
		if rows.Scan(&v.ProviderID, &v.AccountID, &v.CredentialID, &v.ModelID, &v.Group, &v.Endpoint) == nil {
			a = append(a, v)
		}
	}
	rows.Close()
	out := []map[string]any{}
	for _, v := range a {
		state := "ACTIVE"
		favorite, watch := false, false
		_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT state,favorite,watchlist FROM membership_state WHERE scope_key=?", scopeKey(v)).Scan(&state, &favorite, &watch)
		if r.URL.Query().Get("all") != "true" && state != "ACTIVE" {
			continue
		}
		if p := r.URL.Query().Get("provider_id"); p != "" && p != v.ProviderID {
			continue
		}
		b, _ := json.Marshal(v)
		obj := map[string]any{}
		_ = json.Unmarshal(b, &obj)
		obj["state"] = state
		obj["favorite"] = favorite
		obj["watchlist"] = watch
		obj["metadata"] = s.metadata(r.Context(), v.ProviderID, v.ModelID)
		obj["enabled"] = s.validateSelection(r.Context(), v)
		out = append(out, obj)
	}
	jsonOK(w, 200, out)
}
func (s *Server) validateSelection(ctx context.Context, v Selection) bool {
	if !validScope(v) {
		return false
	}
	var n int
	err := s.Store.DB.QueryRowContext(ctx, "SELECT 1 FROM my_models m JOIN credentials c ON c.provider_id=m.provider_id AND c.account_id=m.account_id AND c.id=m.credential_id AND c.enabled=1 AND c.role='INFERENCE' WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=? AND m.model_id=? AND m.group_id=? AND m.endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint).Scan(&n)
	if err != nil {
		return false
	}
	var state string
	err = s.Store.DB.QueryRowContext(ctx, "SELECT state FROM membership_state WHERE scope_key=?", scopeKey(v)).Scan(&state)
	return err == sql.ErrNoRows || err == nil && state == "ACTIVE"
}
func (s *Server) addMyModel(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.Endpoint == "" {
		v.Endpoint = "CHAT_COMPLETIONS"
	}
	if !validScope(v) {
		http.Error(w, "invalid selection", 400)
		return
	}
	var role string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT role FROM credentials WHERE provider_id=? AND account_id=? AND id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&role) != nil || role != "INFERENCE" {
		http.Error(w, "inference credential required", 422)
		return
	}
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO my_models(provider_id,account_id,credential_id,model_id,group_id,endpoint,created_at) VALUES(?,?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, now())
	if err != nil {
		http.Error(w, "scope missing or membership exists", 409)
		return
	}
	s.scheduleEnrichment(r.Context(), v)
	jsonOK(w, 201, v)
}
func (s *Server) removeMyModel(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	result, err := s.Store.DB.ExecContext(r.Context(), "DELETE FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		http.Error(w, "missing membership", 404)
		return
	}
	jsonOK(w, 200, map[string]any{"removed": true, "history_preserved": true})
}

type syncRequest struct {
	ProviderID   string `json:"provider_id"`
	AccountID    string `json:"account_id"`
	CredentialID string `json:"credential_id"`
}
type upstreamModels struct {
	Data []struct {
		ID string `json:"id"`
	} `json:"data"`
}

func (s *Server) syncModels(w http.ResponseWriter, r *http.Request) {
	var v syncRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	count, err := s.discover(r.Context(), v)
	if err != nil {
		http.Error(w, "provider sync failed", 502)
		return
	}
	jsonOK(w, 200, map[string]any{"models_seen": count, "my_models_unchanged": true})
}
func (s *Server) endpointAuth(ctx context.Context, provider, account, credential string) (Provider, string, error) {
	var p Provider
	var enc string
	var enabled int
	err := s.Store.DB.QueryRowContext(ctx, "SELECT p.id,p.name,p.base_url,p.description,p.auth_mode,c.encrypted_key,c.enabled FROM providers p JOIN accounts a ON a.provider_id=p.id JOIN credentials c ON c.provider_id=a.provider_id AND c.account_id=a.id WHERE p.id=? AND a.id=? AND c.id=? AND c.role='INFERENCE'", provider, account, credential).Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode, &enc, &enabled)
	if err != nil || enabled != 1 || !validateHTTPS(p.BaseURL) {
		return Provider{}, "", sql.ErrNoRows
	}
	cfg := s.config(ctx, p)
	p.AuthMode = cfg.AuthMode
	if p.AuthMode == "NONE" {
		return p, "", nil
	}
	p.AuthFields, err = s.resolveFields(ctx, provider, account, credential)
	if err != nil {
		return Provider{}, "", err
	}
	if enc == "" && cfg.AuthMode == "CUSTOM_HEADER" && len(p.AuthFields) > 0 {
		return p, "", nil
	}
	key, err := s.decrypt(enc)
	return p, key, err
}
