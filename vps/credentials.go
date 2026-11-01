package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

type AuthField struct {
	Name   string `json:"name"`
	Query  bool   `json:"query"`
	Secret string `json:"secret,omitempty"`
}
type encryptedField struct {
	Name       string `json:"name"`
	Query      bool   `json:"query"`
	Ciphertext string `json:"ciphertext"`
}

func validAuthFields(fields []AuthField) bool {
	if len(fields) > 8 {
		return false
	}
	seen := map[string]bool{}
	for _, f := range fields {
		key := strings.ToLower(f.Name) + jsonNumber(boolInt(f.Query))
		if !fieldName.MatchString(f.Name) || seen[key] || oneOf(strings.ToLower(f.Name), "authorization", "cookie", "host", "content-length", "origin", "referer", "proxy-authorization", "connection") || f.Secret == "" || len(f.Secret) > 8192 || strings.ContainsAny(f.Secret, "\r\n\x00") {
			return false
		}
		seen[key] = true
	}
	return true
}
func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
func (s *Server) encryptFields(fields []AuthField) (string, error) {
	if !validAuthFields(fields) {
		return "", errors.New("invalid auth fields")
	}
	out := []encryptedField{}
	for _, f := range fields {
		enc, err := s.encrypt(f.Secret)
		if err != nil {
			return "", err
		}
		out = append(out, encryptedField{Name: f.Name, Query: f.Query, Ciphertext: enc})
	}
	raw, _ := json.Marshal(out)
	return string(raw), nil
}
func (s *Server) resolveFields(ctx context.Context, p, a, c string) ([]AuthField, error) {
	var raw string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT fields_json FROM credential_auth_fields WHERE provider_id=? AND account_id=? AND credential_id=?", p, a, c).Scan(&raw)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return s.decryptFields(raw)
}
func (s *Server) decryptFields(raw string) ([]AuthField, error) {
	if raw == "" {
		return nil, nil
	}
	var fields []encryptedField
	if json.Unmarshal([]byte(raw), &fields) != nil {
		return nil, errors.New("invalid encrypted fields")
	}
	out := []AuthField{}
	for _, f := range fields {
		secret, err := s.decrypt(f.Ciphertext)
		if err != nil {
			return nil, err
		}
		out = append(out, AuthField{Name: f.Name, Query: f.Query, Secret: secret})
	}
	return out, nil
}
func (s *Server) rotateCredential(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID   string `json:"provider_id"`
		AccountID    string `json:"account_id"`
		CredentialID string `json:"credential_id"`
		Secret       string `json:"secret"`
		Confirm      bool   `json:"confirm"`
		Version      int    `json:"version,omitempty"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !validID(v.ProviderID) || !validID(v.AccountID) || !validID(v.CredentialID) || len(v.Secret) > 8192 || strings.ContainsAny(v.Secret, "\r\n\x00") {
		http.Error(w, "explicit valid credential change required", 400)
		return
	}
	p, providerErr := boundProvider(r.Context(), s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
	var role string
	if providerErr != nil || s.Store.DB.QueryRowContext(r.Context(), "SELECT role FROM credentials WHERE provider_id=? AND account_id=? AND id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&role) != nil {
		http.Error(w, "credential missing", 404)
		return
	}
	if s.config(r.Context(), p).AuthMode == "NONE" && role == "INFERENCE" {
		http.Error(w, "anonymous connection cannot receive a secret", 422)
		return
	}
	enc := ""
	var err error
	if v.Version == 0 {
		if v.Secret == "" {
			http.Error(w, "new secret required", 400)
			return
		}
		enc, err = s.encrypt(v.Secret)
		if err != nil {
			http.Error(w, "vault unavailable", 503)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var previous string
	if tx.QueryRowContext(r.Context(), "SELECT encrypted_key FROM credentials WHERE provider_id=? AND account_id=? AND id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&previous) != nil {
		http.Error(w, "credential missing", 404)
		return
	}
	var version int
	tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&version)
	if version == 0 {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_secret_versions VALUES(?,?,?,1,?,?)", v.ProviderID, v.AccountID, v.CredentialID, previous, now()); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		version = 1
	}
	if v.Version > 0 {
		if tx.QueryRowContext(r.Context(), "SELECT encrypted_key FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=? AND version=?", v.ProviderID, v.AccountID, v.CredentialID, v.Version).Scan(&enc) != nil {
			http.Error(w, "version missing", 404)
			return
		}
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_secret_versions VALUES(?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, version+1, enc, now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "UPDATE credentials SET encrypted_key=? WHERE provider_id=? AND account_id=? AND id=?", enc, v.ProviderID, v.AccountID, v.CredentialID); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "DELETE FROM capability_confirmations WHERE json_extract(scope_key,'$.provider_id')=? AND json_extract(scope_key,'$.account_id')=? AND json_extract(scope_key,'$.credential_id')=?", v.ProviderID, v.AccountID, v.CredentialID); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"version": version + 1, "history_preserved": true})
}
