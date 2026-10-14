package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"unicode"
	"unicode/utf8"
)

type SafeSecretRef struct {
	ID      string `json:"id"`
	BaseURL string `json:"base_url"`
	Label   string `json:"label"`
	Role    string `json:"role"`
}

func (s *Server) saveAssistantSecret(w http.ResponseWriter, r *http.Request) {
	var v struct {
		BaseURL    string      `json:"base_url"`
		Label      string      `json:"label"`
		Role       string      `json:"role"`
		Secret     string      `json:"secret"`
		AuthFields []AuthField `json:"auth_fields"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validateHTTPS(v.BaseURL) || !oneOf(v.Role, "INFERENCE", "ACCOUNT_PAT") || safeText(v.Label, 120) != v.Label || len(v.Secret) > 8192 || strings.ContainsAny(v.Secret, "\x00\r\n") || v.Secret == "" && len(v.AuthFields) == 0 || v.Role == "ACCOUNT_PAT" && len(v.AuthFields) > 0 {
		http.Error(w, "valid origin-bound secret required", 422)
		return
	}
	encrypted, e := s.encrypt(v.Secret)
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	fields, e := s.encryptFields(v.AuthFields)
	if e != nil {
		http.Error(w, "invalid custom auth", 422)
		return
	}
	ref := SafeSecretRef{newID(), v.BaseURL, v.Label, v.Role}
	if _, e = s.Store.DB.ExecContext(r.Context(), "INSERT INTO assistant_secret_refs VALUES(?,?,?,?,?,?,?)", ref.ID, ref.BaseURL, ref.Label, ref.Role, encrypted, fields, now()); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, ref)
}
func (s *Server) listAssistantSecrets(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,base_url,label,role FROM assistant_secret_refs ORDER BY rowid DESC LIMIT 100")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []SafeSecretRef{}
	for rows.Next() {
		var ref SafeSecretRef
		if rows.Scan(&ref.ID, &ref.BaseURL, &ref.Label, &ref.Role) == nil {
			out = append(out, ref)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) assistantSecret(ctx context.Context, id string) (SafeSecretRef, Credential, error) {
	var ref SafeSecretRef
	var encrypted, fields string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT id,base_url,label,role,encrypted_key,fields_json FROM assistant_secret_refs WHERE id=?", id).Scan(&ref.ID, &ref.BaseURL, &ref.Label, &ref.Role, &encrypted, &fields)
	if e != nil {
		return ref, Credential{}, errors.New("safe secret reference missing")
	}
	key, e := s.decrypt(encrypted)
	if e != nil {
		return ref, Credential{}, errors.New("vault unavailable")
	}
	auth, e := s.decryptFields(fields)
	return ref, Credential{ID: newID(), Label: ref.Label, Role: ref.Role, Secret: key, AuthFields: auth}, e
}
func (s *Server) knownSecrets(ctx context.Context) ([]string, error) {
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT encrypted_key FROM credentials UNION SELECT encrypted_key FROM credential_secret_versions UNION SELECT encrypted_key FROM integration_credentials UNION SELECT encrypted_key FROM assistant_secret_refs")
	if e != nil {
		return nil, e
	}
	encrypted := []string{}
	for rows.Next() {
		var v string
		if rows.Scan(&v) == nil && v != "" {
			encrypted = append(encrypted, v)
		}
	}
	rows.Close()
	out := []string{s.Token}
	if len(s.Key) > 0 {
		out = append(out, base64.StdEncoding.EncodeToString(s.Key))
	}
	for _, v := range encrypted {
		secret, e := s.decrypt(v)
		if e != nil {
			return nil, errors.New("vault unavailable")
		}
		if secret != "" {
			out = append(out, secret)
		}
	}
	rows, e = s.Store.DB.QueryContext(ctx, "SELECT fields_json FROM credential_auth_fields UNION SELECT fields_json FROM assistant_secret_refs")
	if e != nil {
		return nil, e
	}
	raw := []string{}
	for rows.Next() {
		var v string
		if rows.Scan(&v) == nil {
			raw = append(raw, v)
		}
	}
	rows.Close()
	for _, v := range raw {
		fields, e := s.decryptFields(v)
		if e != nil {
			return nil, e
		}
		for _, f := range fields {
			out = append(out, f.Secret)
		}
	}
	return out, nil
}

func safeAssistantData(value any, secrets []string) any {
	var scrub func(any, int) any
	scrub = func(v any, depth int) any {
		if depth > 32 {
			return nil
		}
		switch x := v.(type) {
		case json.RawMessage:
			var decoded any
			if json.Unmarshal(x, &decoded) != nil {
				return nil
			}
			return scrub(decoded, depth+1)
		case map[string]any:
			out := map[string]any{}
			for key, value := range x {
				if oneOf(strings.ToLower(key), "authorization", "cookie", "password", "secret", "api_key", "apikey", "encrypted_key", "ciphertext", "headers", "auth_fields", "fields_json", "draft_json") {
					continue
				}
				out[key] = scrub(value, depth+1)
			}
			return out
		case []any:
			out := []any{}
			for _, v := range x[:min(len(x), 100)] {
				out = append(out, scrub(v, depth+1))
			}
			return out
		case string:
			x = strings.Map(func(r rune) rune {
				if unicode.Is(unicode.Cf, r) || unicode.IsControl(r) && r != '\n' && r != '\r' && r != '\t' {
					return -1
				}
				return r
			}, strings.ToValidUTF8(x, ""))
			flat := strings.NewReplacer("\n", "", "\r", "", "\t", "").Replace(x)
			if safeText(flat, len(flat), secrets...) != flat {
				return "[REDACTED]"
			}
			if len(x) > 16000 {
				x = x[:16000]
				for !utf8.ValidString(x) {
					x = x[:len(x)-1]
				}
			}
			return x
		default:
			raw, err := json.Marshal(v)
			if err != nil {
				return nil
			}
			var decoded any
			if json.Unmarshal(raw, &decoded) != nil {
				return nil
			}
			switch decoded.(type) {
			case map[string]any, []any:
				return scrub(decoded, depth+1)
			}
			return decoded
		}
	}
	return scrub(value, 0)
}
func compactAssistantData(value any, secrets []string) string {
	clean := safeAssistantData(value, secrets)
	raw, _ := json.Marshal(clean)
	if len(raw) > 24000 {
		return `{"status":"TOO_LARGE","message":"Narrow the query or select one model/provider."}`
	}
	return string(raw)
}
