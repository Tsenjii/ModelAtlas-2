package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type ConnectorConfig struct {
	Kind        string            `json:"kind"`
	Path        string            `json:"path"`
	Role        string            `json:"credential_role"`
	PATID       string            `json:"pat_credential_id,omitempty"`
	Scope       string            `json:"scope"`
	Root        string            `json:"root_pointer"`
	Fields      map[string]string `json:"fields"`
	Unit        string            `json:"unit"`
	FixedAbsent bool              `json:"fixed_charge_absent"`
}
type ConnectorVersion struct {
	ID          string          `json:"id"`
	ProviderID  string          `json:"provider_id"`
	Name        string          `json:"name"`
	Version     int             `json:"version"`
	Config      ConnectorConfig `json:"config"`
	EvidenceIDs []string        `json:"evidence_ids"`
	Reason      string          `json:"reason"`
	Status      string          `json:"status"`
	Test        map[string]any  `json:"test"`
	TestedAt    *int64          `json:"tested_at"`
	Active      bool            `json:"active"`
	ActiveID    string          `json:"active_id"`
}

var connectorFields = map[string]string{"CATALOG": "model_id name group", "PRICING": "model_id group input output cache fixed", "QUOTA": "model_id group rpd tpd rpm tpm remaining used reset_at", "BALANCE": "model_id group balance used remaining", "USAGE": "model_id group requests tokens input_tokens output_tokens used", "PERFORMANCE": "model_id group tps ttft_ms latency_ms success_rate sample_count", "NOTICE": "model_id group notice"}

func safeConnector(c ConnectorConfig) bool {
	allowed, ok := connectorFields[c.Kind]
	if !ok || !safePath(c.Path) || !validPointer(c.Root) || !oneOf(c.Role, "NONE", "INFERENCE", "ACCOUNT_PAT") || !oneOf(c.Scope, "MODEL_KEY_GROUP", "MODEL_PROVIDER", "ACCOUNT", "CATALOG") || len(c.Fields) < 1 || len(c.Fields) > 12 {
		return false
	}
	if c.Role == "ACCOUNT_PAT" && !validID(c.PATID) || c.Role != "ACCOUNT_PAT" && c.PATID != "" {
		return false
	}
	if !oneOf(c.Unit, "USD/1M tokens", "USD/1K tokens", "USD/token", "USD", "CREDITS", "TOKENS", "REQUESTS", "MS", "TPS", "NONE") {
		return false
	}
	if c.Kind == "PRICING" && !oneOf(c.Unit, "USD/1M tokens", "USD/1K tokens", "USD/token") {
		return false
	}
	if c.Kind == "CATALOG" && c.Scope != "CATALOG" || c.Kind != "CATALOG" && c.Scope == "CATALOG" {
		return false
	}
	if oneOf(c.Scope, "MODEL_KEY_GROUP", "MODEL_PROVIDER", "CATALOG") && c.Fields["model_id"] == "" {
		return false
	}
	if c.Scope == "MODEL_KEY_GROUP" && c.Fields["group"] == "" {
		return false
	}
	for name, path := range c.Fields {
		if !oneOf(name, strings.Fields(allowed)...) || !validPointer(path) {
			return false
		}
		for _, part := range strings.Split(strings.ToLower(path), "/") {
			if oneOf(part, "key", "api_key", "secret", "access_token", "token", "credentials", "authorization", "pat", "cookie") {
				return false
			}
		}
	}
	return true
}
func (s *Server) connectorAuth(ctx context.Context, c ConnectorConfig, v Selection) (Provider, ProviderConfig, string, error) {
	p, key, e := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if e != nil {
		return p, ProviderConfig{}, "", e
	}
	cfg := s.config(ctx, p)
	if c.Role == "NONE" {
		cfg.AuthMode = "NONE"
		cfg.AuthHeader = ""
		cfg.AuthQuery = ""
		p.AuthFields = nil
		key = ""
	}
	if c.Role == "ACCOUNT_PAT" {
		var encrypted string
		if s.Store.DB.QueryRowContext(ctx, "SELECT encrypted_key FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='ACCOUNT_PAT' AND enabled=1", v.ProviderID, v.AccountID, c.PATID).Scan(&encrypted) != nil {
			return p, cfg, "", errors.New("explicit PAT unavailable for selected account")
		}
		key, e = s.decrypt(encrypted)
		if e != nil {
			return p, cfg, "", errors.New("vault unavailable")
		}
		p.AuthFields = nil
		cfg.AuthMode = "BEARER"
		cfg.AuthHeader = ""
		cfg.AuthQuery = ""
	}
	return p, cfg, key, nil
}
func (s *Server) connectorRows(ctx context.Context, c ConnectorConfig, v Selection) ([]any, error) {
	p, cfg, key, e := s.connectorAuth(ctx, c, v)
	if e != nil {
		return nil, e
	}
	path := strings.ReplaceAll(c.Path, "{model}", url.PathEscape(v.ModelID))
	root, _, e := s.getStructured(ctx, p, cfg, originPath(p, path), key, 2<<20)
	if e != nil {
		return nil, e
	}
	value := root
	value = pointer(value, c.Root)
	rows, ok := value.([]any)
	if !ok {
		rows = []any{value}
	}
	if value == nil || len(rows) > 1000 {
		return nil, errors.New("connector root shape or item budget")
	}
	return rows, nil
}
func extractConnector(c ConnectorConfig, v Selection, rows []any) (map[string]any, error) {
	for _, row := range rows {
		if oneOf(c.Scope, "MODEL_KEY_GROUP", "MODEL_PROVIDER", "CATALOG") && str(pointer(row, c.Fields["model_id"])) != v.ModelID {
			continue
		}
		if c.Scope == "MODEL_KEY_GROUP" {
			group := pointer(row, c.Fields["group"])
			if group == nil || str(group) != v.Group {
				continue
			}
		}
		facts := map[string]any{"scope_kind": c.Scope, "unit": c.Unit, "parser_version": "declarative-get-v1"}
		known := 0
		if c.Kind == "CATALOG" {
			facts["availability"] = "CATALOG_LISTED"
			return facts, nil
		}
		for name, path := range c.Fields {
			if oneOf(name, "model_id", "group") {
				continue
			}
			value := pointer(row, path)
			facts[name] = nil
			if name == "notice" {
				text := str(value)
				if text != "" && safeText(text, 4000) == text && !rawSecretPattern.MatchString(text) {
					facts[name] = text
					known++
				}
				continue
			}
			if n := decimal(value); n != nil && n.Sign() >= 0 {
				facts[name] = *exactString(n)
				known++
			}
		}
		if known == 0 {
			return facts, errors.New("mapped facts remain unknown")
		}
		if c.Kind == "PRICING" {
			legs := PriceLegs{Unit: "USD/1K tokens", FixedAbsent: c.FixedAbsent}
			for name, target := range map[string]**string{"input": &legs.Input, "output": &legs.Output, "cache": &legs.Cache, "fixed": &legs.Fixed} {
				if value := facts[name]; value != nil {
					raw, _ := json.Marshal(value)
					factor := int64(1)
					if name != "fixed" && c.Unit == "USD/token" {
						factor = 1000
					}
					scaled := scalePrice(raw, factor)
					if scaled != nil && name != "fixed" && c.Unit == "USD/1M tokens" {
						n := decimal(*scaled)
						scaled = exactString(n.Quo(n, decimal("1000")))
					}
					*target = scaled
				}
			}
			facts["price"] = legs
			facts["nominal_price_state"] = priceState(legs)
		}
		return facts, nil
	}
	return nil, errors.New("connector model/group scope did not match")
}
func connectorContext(ctx context.Context, q queryRows, c ConnectorConfig, v Selection) (string, error) {
	var cipher, fields, base, config, group string
	var enabled int
	if q.QueryRowContext(ctx, "SELECT c.encrypted_key,c.enabled,p.base_url,COALESCE(x.config_json,'{}'),COALESCE(g.group_id,'') FROM credentials c JOIN providers p ON p.id=c.provider_id LEFT JOIN provider_config x ON x.provider_id=p.id LEFT JOIN credential_context g ON g.provider_id=c.provider_id AND g.account_id=c.account_id AND g.credential_id=c.id WHERE c.provider_id=? AND c.account_id=? AND c.id=? AND c.role='INFERENCE'", v.ProviderID, v.AccountID, v.CredentialID).Scan(&cipher, &enabled, &base, &config, &group) != nil || enabled != 1 || group != v.Group {
		return "", errors.New("selected credential/group unavailable")
	}
	q.QueryRowContext(ctx, "SELECT fields_json FROM credential_auth_fields WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&fields)
	var pat string
	if c.Role == "ACCOUNT_PAT" && q.QueryRowContext(ctx, "SELECT encrypted_key FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND role='ACCOUNT_PAT' AND enabled=1", v.ProviderID, v.AccountID, c.PATID).Scan(&pat) != nil {
		return "", errors.New("explicit PAT unavailable")
	}
	var version int
	q.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&version)
	return snapshotHash(map[string]any{"credential_version": version, "selection": v, "credential_hash": contentHash([]byte(cipher)), "pat_hash": contentHash([]byte(pat)), "fields_hash": contentHash([]byte(fields)), "base": base, "config": config}), nil
}
func (s *Server) proposeConnector(w http.ResponseWriter, r *http.Request) {
	var v ConnectorVersion
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ProviderID) || !validID(v.Name) || safeText(v.Reason, 1000) != v.Reason || v.Reason == "" || !safeConnector(v.Config) || len(v.EvidenceIDs) > 20 {
		http.Error(w, "safe declarative connector required", 422)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, key := range secrets {
		if jsonContainsSecret(v, key) {
			http.Error(w, "raw secrets are not connector data", 422)
			return
		}
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if e = tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM connector_versions WHERE provider_id=? AND name=?", v.ProviderID, v.Name).Scan(&v.Version); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	v.ID = newID()
	v.Status = "DRAFT"
	raw, _ := json.Marshal(v.Config)
	proof, _ := json.Marshal(v.EvidenceIDs)
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO connector_versions(id,provider_id,name,version,config_json,evidence_json,reason,created_at) VALUES(?,?,?,?,?,?,?,?)", v.ID, v.ProviderID, v.Name, v.Version, string(raw), string(proof), v.Reason, now()); e != nil || tx.Commit() != nil {
		http.Error(w, "provider missing or persistence failed", 422)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) listConnectors(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT v.id,v.provider_id,v.name,v.version,v.config_json,v.evidence_json,v.reason,v.status,v.test_json,v.tested_at,COALESCE(a.version_id,'') FROM connector_versions v LEFT JOIN connector_active a ON a.provider_id=v.provider_id AND a.name=v.name WHERE (?='' OR v.provider_id=?) ORDER BY v.rowid DESC LIMIT 200", r.URL.Query().Get("provider_id"), r.URL.Query().Get("provider_id"))
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []ConnectorVersion{}
	for rows.Next() {
		var v ConnectorVersion
		var cfg, proof, test string
		if rows.Scan(&v.ID, &v.ProviderID, &v.Name, &v.Version, &cfg, &proof, &v.Reason, &v.Status, &test, &v.TestedAt, &v.ActiveID) == nil {
			json.Unmarshal([]byte(cfg), &v.Config)
			json.Unmarshal([]byte(proof), &v.EvidenceIDs)
			json.Unmarshal([]byte(test), &v.Test)
			v.Active = v.ID == v.ActiveID
			out = append(out, v)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) testConnector(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID        string    `json:"id"`
		Selection Selection `json:"selection"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	var provider, raw string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT provider_id,config_json FROM connector_versions WHERE id=?", v.ID).Scan(&provider, &raw) != nil {
		http.Error(w, "connector missing", 404)
		return
	}
	var cfg ConnectorConfig
	json.Unmarshal([]byte(raw), &cfg)
	if provider != v.Selection.ProviderID || !s.validateSelection(r.Context(), v.Selection) || !safeConnector(cfg) {
		http.Error(w, "explicit selected model scope required", 422)
		return
	}
	flight := "connector:" + v.ID
	if !s.beginFlight(flight) {
		http.Error(w, "connector busy", 409)
		return
	}
	defer s.endFlight(flight)
	secrets, vaultErr := s.knownSecrets(r.Context())
	if vaultErr != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	ctx, stop := context.WithTimeout(r.Context(), 20*time.Second)
	defer stop()
	hash, e := connectorContext(ctx, s.Store.DB, cfg, v.Selection)
	var facts map[string]any
	if e == nil {
		var rows []any
		rows, e = s.connectorRows(ctx, cfg, v.Selection)
		if e == nil {
			facts, e = extractConnector(cfg, v.Selection, rows)
		}
	}
	status := "VALIDATED"
	reason := ""
	if e != nil {
		status = "FAILED"
		reason = excerpt(e.Error(), 250)
	}
	result := safeAssistantData(map[string]any{"selection": v.Selection, "context_hash": hash, "facts": facts, "reason": reason, "http_method": "GET", "inference_requests": 0}, secrets)
	data, _ := json.Marshal(result)
	save, finish := context.WithTimeout(context.Background(), 5*time.Second)
	defer finish()
	tx, err := s.Store.DB.BeginTx(save, nil)
	if err != nil {
		http.Error(w, "test persistence failed", 500)
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(save, "UPDATE connector_versions SET status=?,test_json=?,tested_at=? WHERE id=?", status, string(data), now(), v.ID); err != nil {
		http.Error(w, "test persistence failed", 500)
		return
	}
	if _, err = tx.ExecContext(save, "INSERT INTO connector_test_history VALUES(?,?,?,?,?)", newID(), v.ID, status, string(data), now()); err != nil || tx.Commit() != nil {
		http.Error(w, "test history persistence failed", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "status": status, "test": result})
}
func (s *Server) activateConnector(w http.ResponseWriter, r *http.Request) {
	var v versionActivation
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm {
		http.Error(w, "explicit confirmation required", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var provider, name, status, raw, test string
	var at int64
	if tx.QueryRowContext(r.Context(), "SELECT provider_id,name,status,config_json,test_json,COALESCE(tested_at,0) FROM connector_versions WHERE id=?", v.ID).Scan(&provider, &name, &status, &raw, &test, &at) != nil {
		http.Error(w, "connector missing", 404)
		return
	}
	var cfg ConnectorConfig
	var t struct {
		Selection Selection `json:"selection"`
		Hash      string    `json:"context_hash"`
	}
	json.Unmarshal([]byte(raw), &cfg)
	json.Unmarshal([]byte(test), &t)
	hash, e := connectorContext(r.Context(), tx, cfg, t.Selection)
	if status != "VALIDATED" || now()-at > int64(24*time.Hour/time.Millisecond) || e != nil || hash != t.Hash {
		http.Error(w, "test this version with current scope before activation", 409)
		return
	}
	if cfg.Kind == "CATALOG" {
		var count int
		tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM connector_active a JOIN connector_versions x ON x.id=a.version_id WHERE a.provider_id=? AND a.name!=? AND json_extract(x.config_json,'$.kind')='CATALOG'", provider, name).Scan(&count)
		if count > 0 {
			http.Error(w, "one explicit primary catalog connector per provider; undo the existing activation first", 409)
			return
		}
	}
	id, e := applyVersion(r.Context(), tx, "CONNECTOR", provider, name, v.ID, v.ExpectedID)
	if e != nil {
		http.Error(w, e.Error(), 409)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "active": true, "action_id": id})
}
func (s *Server) enrichConnectors(ctx context.Context, v Selection) map[string]string {
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT x.id,x.config_json FROM connector_versions x JOIN connector_active a ON a.version_id=x.id WHERE a.provider_id=? ORDER BY x.name LIMIT 16", v.ProviderID)
	if e != nil {
		return map[string]string{"connectors": "UNAVAILABLE"}
	}
	configs := map[string]ConnectorConfig{}
	for rows.Next() {
		var id, raw string
		rows.Scan(&id, &raw)
		var c ConnectorConfig
		json.Unmarshal([]byte(raw), &c)
		configs[id] = c
	}
	rows.Close()
	out := map[string]string{}
	for id, c := range configs {
		if c.Kind == "CATALOG" {
			continue
		}
		rows, e := s.connectorRows(ctx, c, v)
		var facts map[string]any
		if e == nil {
			facts, e = extractConnector(c, v, rows)
		}
		status := "SUCCESS"
		if e != nil {
			status = "FAILED"
			facts = map[string]any{"reason": excerpt(e.Error(), 250), "scope_kind": c.Scope}
		}
		scope := v
		if c.Scope == "ACCOUNT" {
			scope.CredentialID = ""
		} else if c.Scope == "MODEL_PROVIDER" {
			scope.CredentialID = ""
			scope.AccountID = ""
			scope.Group = ""
		}
		_ = s.observe(ctx, scope, c.Kind, "DECLARATIVE_PROVIDER_META:"+id, status, facts)
		out["connector:"+id] = status
	}
	return out
}

func (s *Server) connectorCatalog(ctx context.Context, v syncRequest) ([]CatalogEntry, bool, error) {
	var id, raw string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT x.id,x.config_json FROM connector_versions x JOIN connector_active a ON a.version_id=x.id WHERE a.provider_id=? AND json_extract(x.config_json,'$.kind')='CATALOG' ORDER BY x.name LIMIT 1", v.ProviderID).Scan(&id, &raw)
	if e != nil {
		return nil, false, nil
	}
	var cfg ConnectorConfig
	json.Unmarshal([]byte(raw), &cfg)
	selection := Selection{ProviderID: v.ProviderID, AccountID: v.AccountID, CredentialID: v.CredentialID}
	rows, e := s.connectorRows(ctx, cfg, selection)
	if e != nil {
		return nil, true, e
	}
	out := []CatalogEntry{}
	for _, row := range rows {
		model := str(pointer(row, cfg.Fields["model_id"]))
		if !validID(model) {
			continue
		}
		name := model
		if path := cfg.Fields["name"]; path != "" {
			if title := safeText(str(pointer(row, path)), 200); title != "" {
				name = title
			}
		}
		meta := ModelMetadata{ProviderID: v.ProviderID, ModelID: model, CanonicalID: canonicalID(model), Category: "OTHER", Source: "DECLARATIVE_PROVIDER_META:" + id, Capabilities: []string{}, Modalities: []string{}, Endpoints: []string{}, Aliases: []string{}}
		out = append(out, CatalogEntry{ID: model, Name: name, Meta: meta, Facts: map[string]any{"availability": "CATALOG_LISTED", "scope_kind": "CATALOG", "source_override": "DECLARATIVE_PROVIDER_META:" + id}})
	}
	if len(out) == 0 {
		return nil, true, errors.New("mapped catalog contains no valid model IDs")
	}
	return out, true, nil
}
