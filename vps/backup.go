package main

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
)

type BackupDocument struct {
	BackupExtras
	CustomProtocols []struct {
		ProviderID string `json:"provider_id"`
		EndpointID string `json:"endpoint_id"`
		Raw        string `json:"config_json"`
	} `json:"custom_protocols,omitempty"`
	Endpoints        []EndpointProfile `json:"endpoints,omitempty"`
	EndpointBindings []struct {
		ProviderID string `json:"provider_id"`
		ID         string `json:"endpoint_id"`
		Operation  string `json:"operation"`
		URL        string `json:"url"`
		CreatedAt  int64  `json:"created_at"`
	} `json:"endpoint_bindings,omitempty"`
	Format          string     `json:"format"`
	Version         int        `json:"version"`
	SecretsIncluded bool       `json:"secrets_included"`
	Providers       []Provider `json:"providers"`
	Accounts        []Account  `json:"accounts"`
	Credentials     []struct {
		ID         string `json:"id"`
		ProviderID string `json:"provider_id"`
		AccountID  string `json:"account_id"`
		Label      string `json:"label"`
		Role       string `json:"role"`
		Enabled    int    `json:"enabled"`
		GroupID    string `json:"group_id"`
		BaseURL    string `json:"base_url,omitempty"`
		AuthMode   string `json:"auth_mode,omitempty"`
	} `json:"credentials"`
	Models []struct {
		ProviderID  string `json:"provider_id"`
		ID          string `json:"id"`
		DisplayName string `json:"display_name"`
		Provenance  string `json:"provenance"`
	} `json:"models"`
	MyModels []struct {
		ProviderID   string `json:"provider_id"`
		AccountID    string `json:"account_id"`
		CredentialID string `json:"credential_id"`
		ModelID      string `json:"model_id"`
		GroupID      string `json:"group_id"`
		Endpoint     string `json:"endpoint"`
	} `json:"my_models"`
	Configs []struct {
		ProviderID string `json:"provider_id"`
		Raw        string `json:"config_json"`
	} `json:"provider_config"`
	Metadata []struct {
		ProviderID  string `json:"provider_id"`
		ModelID     string `json:"model_id"`
		CanonicalID string `json:"canonical_id"`
		Raw         string `json:"metadata_json"`
	} `json:"model_metadata"`
}

func (s *Server) restoreBackup(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Confirm bool           `json:"confirm"`
		Backup  BackupDocument `json:"backup"`
	}
	if !decodeLimit(w, r, &v, 16<<20) {
		return
	}
	b := v.Backup
	if !v.Confirm || b.Format != "modelatlas-safe-export" || !oneOfVersion(b.Version, 1, 2) || b.SecretsIncluded || len(b.Providers) > 100 || len(b.Credentials) > 1000 || len(b.Models) > 10000 {
		http.Error(w, "explicit supported safe backup required", 400)
		return
	}
	for _, p := range b.Providers {
		if !validID(p.ID) || len(p.Name) > 120 || p.Name == "" || !validateHTTPS(p.BaseURL) || len(p.Description) > 1000 || !oneOf(p.AuthMode, "NONE", "BEARER") {
			http.Error(w, "invalid provider", 400)
			return
		}
	}
	for _, c := range b.Configs {
		var cfg ProviderConfig
		if json.Unmarshal([]byte(c.Raw), &cfg) != nil || !safeConfig(cfg) {
			http.Error(w, "unsafe provider configuration", 400)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	fail := func() { http.Error(w, "backup conflicts or contains invalid scopes; no changes saved", 409) }
	for _, p := range b.Providers {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO providers(id,name,base_url,description,auth_mode,created_at) VALUES(?,?,?,?,?,?)", p.ID, p.Name, p.BaseURL, p.Description, p.AuthMode, now()); err != nil {
			fail()
			return
		}
	}
	for _, a := range b.Accounts {
		if !validID(a.ID) || !validID(a.ProviderID) || len(a.Label) > 120 {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO accounts VALUES(?,?,?)", a.ID, a.ProviderID, a.Label); err != nil {
			fail()
			return
		}
	}
	for _, c := range b.Credentials {
		if !validID(c.ID) || !validID(c.ProviderID) || !validID(c.AccountID) || len(c.Label) > 120 || !oneOf(c.Role, "INFERENCE", "ACCOUNT_PAT") {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credentials(id,provider_id,account_id,label,encrypted_key,role,enabled,created_at) VALUES(?,?,?,?,'',?,0,?)", c.ID, c.ProviderID, c.AccountID, c.Label, c.Role, now()); err != nil {
			fail()
			return
		}
		base := c.BaseURL
		if base == "" {
			for _, p := range b.Providers {
				if p.ID == c.ProviderID {
					base = p.BaseURL
					break
				}
			}
		}
		if insertCredentialOrigin(r.Context(), tx, c.ProviderID, c.AccountID, c.ID, base, c.AuthMode) != nil {
			fail()
			return
		}

	}
	for _, c := range b.Credentials {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_context VALUES(?,?,?,?,'',0)", c.ProviderID, c.AccountID, c.ID, c.GroupID); err != nil {
			fail()
			return
		}
	}

	for _, m := range b.Models {
		if !validID(m.ProviderID) || !validID(m.ID) || len(m.DisplayName) > 200 {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO models VALUES(?,?,?,'UNKNOWN','UNKNOWN','MANUAL_IMPORT',?)", m.ProviderID, m.ID, m.DisplayName, now()); err != nil {
			fail()
			return
		}
	}
	for _, c := range b.Configs {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO provider_config VALUES(?,?)", c.ProviderID, c.Raw); err != nil {
			fail()
			return
		}
	}
	for _, m := range b.Metadata {
		var meta ModelMetadata
		if json.Unmarshal([]byte(m.Raw), &meta) != nil || meta.ProviderID != m.ProviderID || meta.ModelID != m.ModelID || !validID(meta.CanonicalID) {
			fail()
			return
		}
		meta.Source = "MANUAL_IMPORT"
		raw, _ := json.Marshal(meta)
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO model_metadata VALUES(?,?,?,?)", m.ProviderID, m.ModelID, meta.CanonicalID, string(raw)); err != nil {
			fail()
			return
		}
	}
	for _, profile := range b.Endpoints {
		var p Provider
		for _, candidate := range b.Providers {
			if candidate.ID == profile.ProviderID {
				p = candidate
				break
			}
		}
		base, _ := url.Parse(p.BaseURL)
		_, e := url.Parse(profile.URL)
		if e != nil || base == nil || !validateHTTPS(profile.URL) || !backupOriginAllowed(b, profile.ProviderID, profile.URL, base.Host) || !validID(profile.ID) || endpointNames[profile.Operation] == "" {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO endpoint_profiles VALUES(?,?,?,?)", profile.ProviderID, profile.ID, profile.Operation, profile.URL); err != nil {
			fail()
			return
		}
	}
	for _, definition := range b.CustomProtocols {
		var cfg CustomProtocol
		var op string
		if json.Unmarshal([]byte(definition.Raw), &cfg) != nil || tx.QueryRowContext(r.Context(), "SELECT operation FROM endpoint_profiles WHERE provider_id=? AND id=?", definition.ProviderID, definition.EndpointID).Scan(&op) != nil || op != "CUSTOM" || saveCustom(r.Context(), tx, definition.ProviderID, definition.EndpointID, &cfg) != nil {
			fail()
			return
		}
	}
	for _, profile := range b.Endpoints {
		if profile.Operation == "CUSTOM" {
			var n int
			_ = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM custom_protocols WHERE provider_id=? AND endpoint_id=?", profile.ProviderID, profile.ID).Scan(&n)
			if n != 1 {
				fail()
				return
			}
		}
	}
	for _, binding := range b.EndpointBindings {
		var p Provider
		for _, candidate := range b.Providers {
			if candidate.ID == binding.ProviderID {
				p = candidate
				break
			}
		}
		base, _ := url.Parse(p.BaseURL)
		_, e := url.Parse(binding.URL)
		if e != nil || base == nil || !validateHTTPS(binding.URL) || !backupOriginAllowed(b, binding.ProviderID, binding.URL, base.Host) || !validID(binding.ID) || endpointNames[binding.Operation] == "" {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO endpoint_bindings VALUES(?,?,?,?,?)", binding.ProviderID, binding.ID, binding.Operation, binding.URL, binding.CreatedAt); err != nil {
			fail()
			return
		}
	}
	for _, m := range b.MyModels {
		selection := Selection{ProviderID: m.ProviderID, AccountID: m.AccountID, CredentialID: m.CredentialID, ModelID: m.ModelID, Group: m.GroupID, Endpoint: m.Endpoint}
		if !validScope(selection) {
			fail()
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO my_models VALUES(?,?,?,?,?,?,?)", m.ProviderID, m.AccountID, m.CredentialID, m.ModelID, m.GroupID, m.Endpoint, now()); err != nil {
			fail()
			return
		}
		if bumpMembership(tx, selection) != nil {
			fail()
			return
		}
	}
	if b.Version >= 2 {
		if restoreExtras(r.Context(), tx, b.BackupExtras) != nil {
			fail()
			return
		}
	}
	if tx.Commit() != nil {
		fail()
		return
	}
	jsonOK(w, 201, map[string]any{"providers": len(b.Providers), "credentials_disabled": true, "secret_reentry_required": true})
}

func oneOfVersion(v int, versions ...int) bool {
	for _, n := range versions {
		if v == n {
			return true
		}
	}
	return false
}

func backupOriginAllowed(b BackupDocument, provider, target, primaryHost string) bool {
	u, e := url.Parse(target)
	if e != nil {
		return false
	}
	if strings.EqualFold(primaryHost, u.Host) {
		return true
	}
	for _, credential := range b.Credentials {
		if credential.ProviderID == provider && sameOrigin(credential.BaseURL, target) {
			return true
		}
	}
	return false
}
