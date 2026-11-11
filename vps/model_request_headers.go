package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strings"
	"unicode"
)

const modelRequestHeaderSchema = `
CREATE TABLE IF NOT EXISTS model_request_headers (
	provider_id TEXT NOT NULL, account_id TEXT NOT NULL, credential_id TEXT NOT NULL,
	model_id TEXT NOT NULL, group_id TEXT NOT NULL, endpoint TEXT NOT NULL,
	base_url TEXT NOT NULL, revision TEXT NOT NULL, encrypted_headers TEXT NOT NULL,
	PRIMARY KEY(provider_id,account_id,credential_id,model_id,group_id,endpoint),
	FOREIGN KEY(provider_id,account_id,credential_id,model_id,group_id,endpoint)
		REFERENCES my_models(provider_id,account_id,credential_id,model_id,group_id,endpoint) ON DELETE CASCADE
);`

const modelHeaderScope = "provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?"
const modelHeaderLimit, modelHeaderValueLimit = 8, 2048

type modelRequestHeader struct {
	Name    string `json:"name"`
	Value   string `json:"value"`
	Private bool   `json:"private"`
}

type modelHeaderEdit struct {
	Name    string  `json:"name"`
	Value   *string `json:"value,omitempty"`
	Private bool    `json:"private"`
}

type modelHeaderEditor struct {
	Selection
	Revision string            `json:"revision"`
	Headers  []modelHeaderEdit `json:"headers"`
}

type modelHeaderPayload struct {
	Selection
	BaseURL string               `json:"base_url"`
	Headers []modelRequestHeader `json:"headers"`
}

func modelHeaderArgs(scope Selection) []any {
	return []any{scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint}
}

func modelHeaderNameKey(name string) string {
	return strings.ReplaceAll(strings.ToLower(name), "_", "-")
}

func reservedModelHeader(key string) bool {
	switch key {
	case "authorization", "www-authenticate", "cookie", "cookie2", "set-cookie",
		"host", "api-key", "x-api-key", "x-goog-api-key",
		"content-type", "content-length", "content-encoding", "content-range",
		"accept", "accept-encoding", "connection", "keep-alive",
		"transfer-encoding", "te", "trailer", "upgrade", "expect",
		"forwarded", "x-real-ip", "x-original-url", "x-rewrite-url":
		return true
	}
	return strings.HasPrefix(key, "proxy-") || strings.HasPrefix(key, "x-forwarded-")
}

func modelHeaderValueValid(value string) bool {
	return value != "" && len(value) <= modelHeaderValueLimit && strings.IndexFunc(value, unicode.IsControl) == -1
}

func modelHeaderAuthNames(cfg ProviderConfig, fields []AuthField) map[string]bool {
	names := map[string]bool{}
	if cfg.AuthHeader != "" {
		names[modelHeaderNameKey(cfg.AuthHeader)] = true
	}
	for _, field := range fields {
		if !field.Query {
			names[modelHeaderNameKey(field.Name)] = true
		}
	}
	return names
}

func (s *Server) readModelHeaders(ctx context.Context, db queryRows, scope Selection, baseURL string) ([]modelRequestHeader, string, error) {
	var encrypted, revision string
	err := db.QueryRowContext(ctx, "SELECT encrypted_headers,revision FROM model_request_headers WHERE "+modelHeaderScope+" AND base_url=?",
		append(modelHeaderArgs(scope), baseURL)...).Scan(&encrypted, &revision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", nil
	}
	if err != nil || encrypted == "" {
		return nil, revision, err
	}
	raw, err := s.decrypt(encrypted)
	var payload modelHeaderPayload
	if err != nil || json.Unmarshal([]byte(raw), &payload) != nil || payload.Selection != scope || payload.BaseURL != baseURL {
		return nil, "", errors.New("model request headers unavailable")
	}
	return payload.Headers, revision, nil
}

// This runs only on an inference request after the selected credential has
// authorized the destination. Discovery and account calls keep their headers.
func (s *Server) applyModelHeaders(req *http.Request, p Provider, cfg ProviderConfig, scope Selection) ([]string, string, error) {
	headers, revision, err := s.readModelHeaders(req.Context(), s.Store.DB, scope, p.BaseURL)
	if err != nil || len(headers) == 0 {
		return nil, "", err
	}
	protected := modelHeaderAuthNames(cfg, p.AuthFields)
	var secrets []string
	for _, header := range headers {
		key := modelHeaderNameKey(header.Name)
		if reservedModelHeader(key) || protected[key] {
			return nil, "", errors.New("model request header conflicts with credential or protocol")
		}
		req.Header.Set(header.Name, header.Value)
		if header.Private {
			secrets = append(secrets, header.Value)
		}
	}
	return secrets, revision, nil
}

func modelHeaderRevision(scope Selection, baseURL, revision string) string {
	return contentHash([]byte(scopeKey(scope) + "\x00" + baseURL + "\x00" + revision))
}

func modelHeaderView(scope Selection, baseURL, revision string, headers []modelRequestHeader) modelHeaderEditor {
	values := make([]modelHeaderEdit, 0, len(headers))
	for _, header := range headers {
		value := modelHeaderEdit{Name: header.Name, Private: header.Private}
		if !header.Private {
			value.Value = &header.Value
		}
		values = append(values, value)
	}
	return modelHeaderEditor{scope, modelHeaderRevision(scope, baseURL, revision), values}
}

func modelHeaderProvider(ctx context.Context, db queryRows, scope Selection) (Provider, error) {
	var member int
	if err := db.QueryRowContext(ctx, "SELECT 1 FROM my_models WHERE "+modelHeaderScope, modelHeaderArgs(scope)...).Scan(&member); err != nil {
		return Provider{}, err
	}
	return boundProvider(ctx, db, scope.ProviderID, scope.AccountID, scope.CredentialID)
}

func (s *Server) gatewayGetModelHeaders(w http.ResponseWriter, r *http.Request) {
	scope := selectionQuery(r)
	if !validScope(scope) || !r.URL.Query().Has("group") {
		http.Error(w, "full selected scope required", 400)
		return
	}
	ctx := r.Context()
	p, err := modelHeaderProvider(ctx, s.Store.DB, scope)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	headers, revision, err := s.readModelHeaders(ctx, s.Store.DB, scope, p.BaseURL)
	if err != nil {
		http.Error(w, "model request headers unavailable", 503)
		return
	}
	jsonOK(w, 200, modelHeaderView(scope, p.BaseURL, revision, headers))
}

func (s *Server) gatewaySaveModelHeaders(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Selection
		Revision string            `json:"revision"`
		Headers  []modelHeaderEdit `json:"headers"`
	}
	if !decodeLimit(w, r, &in, 48<<10) {
		return
	}
	if !validScope(in.Selection) || in.Headers == nil || len(in.Headers) > modelHeaderLimit {
		http.Error(w, "invalid model request headers", 400)
		return
	}
	ctx := r.Context()
	known, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	defer tx.Rollback()
	p, err := modelHeaderProvider(ctx, tx, in.Selection)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	current, revision, err := s.readModelHeaders(ctx, tx, in.Selection, p.BaseURL)
	if err != nil {
		http.Error(w, "model request headers unavailable", 503)
		return
	}
	if in.Revision != modelHeaderRevision(in.Selection, p.BaseURL, revision) {
		http.Error(w, "model request headers changed; reload before saving", 409)
		return
	}
	fields, err := modelHeaderCredentialFields(ctx, tx, in.Selection)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	protected := modelHeaderAuthNames(providerConfiguration(ctx, tx, p), fields)
	previous := make(map[string]modelRequestHeader, len(current))
	for _, header := range current {
		previous[modelHeaderNameKey(header.Name)] = header
	}
	next := make([]modelRequestHeader, 0, len(in.Headers))
	seen := map[string]bool{}
	for _, edit := range in.Headers {
		name := http.CanonicalHeaderKey(strings.Trim(edit.Name, " "))
		key := modelHeaderNameKey(name)
		if !fieldName.MatchString(name) || reservedModelHeader(key) || protected[key] || seen[key] {
			http.Error(w, "invalid, duplicate or reserved model request header", 400)
			return
		}
		seen[key] = true
		value := previous[key].Value
		if edit.Value != nil {
			value = strings.Trim(*edit.Value, " ")
		} else if previous[key].Private && !edit.Private {
			http.Error(w, "enter a new value before making a header public", 400)
			return
		}
		if !modelHeaderValueValid(value) {
			http.Error(w, "invalid model request header value", 400)
			return
		}
		if gatewayHasSecret(name, known) || !edit.Private && gatewayHasSecret(value, known) {
			http.Error(w, "private content in a visible header field", 422)
			return
		}
		next = append(next, modelRequestHeader{Name: name, Value: value, Private: edit.Private})
	}
	if slices.Equal(current, next) {
		jsonOK(w, 200, modelHeaderView(in.Selection, p.BaseURL, revision, current))
		return
	}
	encrypted := ""
	if len(next) > 0 {
		raw, _ := json.Marshal(modelHeaderPayload{in.Selection, p.BaseURL, next})
		encrypted, err = s.encrypt(string(raw))
		if err != nil {
			http.Error(w, "vault unavailable", 503)
			return
		}
	}
	revision = newID()
	_, err = tx.ExecContext(ctx, `INSERT INTO model_request_headers VALUES(?,?,?,?,?,?,?,?,?)
		ON CONFLICT(provider_id,account_id,credential_id,model_id,group_id,endpoint) DO UPDATE SET
		base_url=excluded.base_url,revision=excluded.revision,encrypted_headers=excluded.encrypted_headers`,
		append(modelHeaderArgs(in.Selection), p.BaseURL, revision, encrypted)...)
	if err != nil || tx.Commit() != nil {
		http.Error(w, "model request headers unavailable", 503)
		return
	}
	jsonOK(w, 200, modelHeaderView(in.Selection, p.BaseURL, revision, next))
}

func modelHeaderCredentialFields(ctx context.Context, db queryRows, scope Selection) ([]AuthField, error) {
	var raw string
	err := db.QueryRowContext(ctx, "SELECT fields_json FROM credential_auth_fields WHERE provider_id=? AND account_id=? AND credential_id=?",
		scope.ProviderID, scope.AccountID, scope.CredentialID).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) || err == nil && raw == "" {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var stored []encryptedField
	if err = json.Unmarshal([]byte(raw), &stored); err != nil {
		return nil, err
	}
	fields := make([]AuthField, 0, len(stored))
	for _, field := range stored {
		fields = append(fields, AuthField{Name: field.Name, Query: field.Query})
	}
	return fields, nil
}
