package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func (s *Server) secretChangeAfter(ctx context.Context, v ControlChange, before map[string]any) (map[string]any, error) {
	var refID string
	if json.Unmarshal(v.Patch["safe_secret_ref"], &refID) != nil {
		return nil, errors.New("safe secret reference required")
	}
	ref, _, e := s.assistantSecret(ctx, refID)
	if e != nil || v.Kind == "CREDENTIAL_ROTATE" && ref.BaseURL != str(before["origin"]) {
		return nil, errors.New("reference must match the credential's origin")
	}
	if v.Kind == "CREDENTIAL_ROTATE" && ref.Role != str(before["role"]) {
		return nil, errors.New("credential role cannot change")
	}
	if v.Kind == "CREDENTIAL_ADD" && before["exists"] != false {
		return nil, errors.New("credential already exists")
	}
	var cipher, fields string
	if s.Store.DB.QueryRowContext(ctx, "SELECT encrypted_key,fields_json FROM assistant_secret_refs WHERE id=?", refID).Scan(&cipher, &fields) != nil {
		return nil, errors.New("safe reference unavailable")
	}
	var p Provider
	p, e = s.provider(ctx, v.ProviderID)
	if v.Kind == "CREDENTIAL_ROTATE" {
		p, e = boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
	}
	if e != nil || v.Kind == "CREDENTIAL_ROTATE" && s.config(ctx, p).AuthMode == "NONE" && ref.Role == "INFERENCE" {
		return nil, errors.New("anonymous provider cannot receive a secret")
	}
	after := map[string]any{}
	for k, x := range before {
		after[k] = x
	}
	if v.Kind == "CREDENTIAL_ADD" {
		after["origin"] = ref.BaseURL
		mode := s.config(ctx, p).AuthMode
		if mode == "NONE" {
			mode = "BEARER"
		}
		if cipher == "" && fields != "[]" {
			mode = "CUSTOM_HEADER"
		}
		after["auth_mode"] = mode
		after["label"] = ref.Label
		after["role"] = ref.Role
		after["group"] = ""
		after["note"] = ""
		after["priority"] = 0
		after["enabled"] = true
		after["exists"] = true
	}
	for k, raw := range v.Patch {
		if k == "safe_secret_ref" {
			continue
		}
		if v.Kind != "CREDENTIAL_ADD" || !oneOf(k, "label", "group", "note") {
			return nil, errors.New("secret change field not allowlisted")
		}
		var text string
		if json.Unmarshal(raw, &text) != nil || safeText(text, 512) != text || oneOf(k, "label", "group") && len(text) > 120 {
			return nil, errors.New("invalid credential metadata")
		}
		after[k] = text
	}
	after["safe_secret_ref"] = refID
	after["_cipher"] = cipher
	after["_fields"] = fields
	after["_key_version_hash"] = contentHash([]byte(cipher))
	return after, nil
}

func (s *Server) writeSecretChange(ctx context.Context, tx *sql.Tx, v ControlChange, data map[string]any) error {
	if v.Kind == "CREDENTIAL_ADD" {
		if data["exists"] == false {
			_, e := tx.ExecContext(ctx, "UPDATE credentials SET enabled=0 WHERE provider_id=? AND account_id=? AND id=?", v.ProviderID, v.AccountID, v.CredentialID)
			return e
		}
		if _, e := tx.ExecContext(ctx, "INSERT INTO credentials(id,provider_id,account_id,label,role,encrypted_key,enabled,created_at) VALUES(?,?,?,?,?,?,?,?)", v.CredentialID, v.ProviderID, v.AccountID, str(data["label"]), str(data["role"]), str(data["_cipher"]), true, now()); e != nil {
			return e
		}
		if e := insertCredentialOrigin(ctx, tx, v.ProviderID, v.AccountID, v.CredentialID, str(data["origin"]), str(data["auth_mode"])); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, "INSERT INTO credential_context VALUES(?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, str(data["group"]), str(data["note"]), 0); e != nil {
			return e
		}
	} else {
		var old string
		if tx.QueryRowContext(ctx, "SELECT encrypted_key FROM credentials WHERE provider_id=? AND account_id=? AND id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&old) != nil {
			return errors.New("credential missing")
		}
		var version int
		tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&version)
		if version == 0 {
			if _, e := tx.ExecContext(ctx, "INSERT INTO credential_secret_versions VALUES(?,?,?,1,?,?)", v.ProviderID, v.AccountID, v.CredentialID, old, now()); e != nil {
				return e
			}
			version = 1
		}
		if _, e := tx.ExecContext(ctx, "INSERT INTO credential_secret_versions VALUES(?,?,?,?,?,?)", v.ProviderID, v.AccountID, v.CredentialID, version+1, str(data["_cipher"]), now()); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, "UPDATE credentials SET encrypted_key=? WHERE provider_id=? AND account_id=? AND id=?", str(data["_cipher"]), v.ProviderID, v.AccountID, v.CredentialID); e != nil {
			return e
		}
	}
	if _, e := tx.ExecContext(ctx, "INSERT INTO credential_auth_fields VALUES(?,?,?,?) ON CONFLICT(provider_id,account_id,credential_id) DO UPDATE SET fields_json=excluded.fields_json", v.ProviderID, v.AccountID, v.CredentialID, str(data["_fields"])); e != nil {
		return e
	}
	_, e := tx.ExecContext(ctx, "DELETE FROM capability_confirmations WHERE json_extract(scope_key,'$.provider_id')=? AND json_extract(scope_key,'$.account_id')=? AND json_extract(scope_key,'$.credential_id')=?", v.ProviderID, v.AccountID, v.CredentialID)
	return e
}
