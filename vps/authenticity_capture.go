package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"strings"
)

const authenticitySuiteVersion = "traffic-fingerprint-v2"
const authenticityCaptureVersion = "local-request-v2"

// These receipts and activation authority are local. Metadata sync/import cannot
// turn a foreign telemetry row into an attested request or activate a reference.
const authenticitySchema = `
CREATE TABLE IF NOT EXISTS authenticity_local_samples(sample_id TEXT PRIMARY KEY REFERENCES telemetry(id),material_stamp TEXT NOT NULL,telemetry_hash TEXT NOT NULL,features_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS authenticity_reference_versions(id TEXT PRIMARY KEY,target_scope TEXT NOT NULL,version INTEGER NOT NULL,definition_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(target_scope,version));
CREATE TABLE IF NOT EXISTS authenticity_reference_active(target_scope TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES authenticity_reference_versions(id));
CREATE TABLE IF NOT EXISTS authenticity_reports(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,target_scope TEXT NOT NULL,report_json TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS authenticity_report_scope ON authenticity_reports(target_scope,created_at);
`

type authenticityCapture struct {
	Version         string `json:"version"`
	SampleID        string `json:"sample_id"`
	ConditionHash   string `json:"condition_hash"`
	ConfigHash      string `json:"config_hash"`
	EndpointHash    string `json:"endpoint_hash"`
	PayloadBytes    int64  `json:"payload_bytes"`
	MaxOutputTokens int    `json:"max_output_tokens"`
	ToolsOffered    int    `json:"tools_offered"`
	ValidToolCalls  int    `json:"valid_tool_calls"`
	TextBytes       int    `json:"text_bytes"`
	ReasoningBytes  int    `json:"reasoning_bytes"`
}

func credentialMaterialStamp(cipher, fields string) string {
	return contentHash([]byte(cipher + "\x00" + fields))
}

// Hash the payload and effective protocol conditions without retaining prompt,
// reply, tool arguments or headers. Gemini carries stream mode in its URL; an
// Anthropic adapter may override the configured version during authorization.
// Only the model identifier is omitted for same-canonical comparison. The
// segmented encoder keeps the existing full-context memory boundary.
func authenticityCondition(payload map[string]any, operation string, stream bool, apiVersion string, size int64) string {
	if operation == "CUSTOM" {
		return ""
	} // Arbitrary mappings lack comparable semantics.
	normalized := make(map[string]any, len(payload))
	for k, v := range payload {
		if k != "model" {
			normalized[k] = v
		}
	}
	conditions := map[string]any{"operation": operation, "stream": stream, "payload": normalized}
	if operation == "ANTHROPIC_MESSAGES" {
		conditions["anthropic_version"] = apiVersion
	}
	body, err := encodeRequestJSON(conditions, size+4096)
	if err != nil {
		return ""
	}
	h := sha256.New()
	r := body.reader()
	defer r.Close()
	if _, err = io.Copy(h, r); err != nil {
		return ""
	}
	return hex.EncodeToString(h.Sum(nil))
}

type authenticityTraffic struct {
	ID                string    `json:"id"`
	Selection         Selection `json:"selection"`
	Operation         string    `json:"operation"`
	Source            string    `json:"source"`
	Outcome           string    `json:"outcome"`
	CredentialVersion int       `json:"credential_version"`
	At                int64     `json:"observed_at"`
	Input             *int64    `json:"input_tokens"`
	Output            *int64    `json:"output_tokens"`
	Details           string    `json:"-"`
}

// Include the original JSON bytes: rewritten/imported rows conservatively lose
// local attestation instead of borrowing it from a matching public sample ID.
func (v authenticityTraffic) hash() string {
	return connectorHash(struct {
		Traffic authenticityTraffic
		Details string
	}{v, v.Details})
}

const authenticityTrafficColumns = `t.id,t.provider_id,t.account_id,t.credential_id,t.model_id,t.group_id,t.endpoint,t.operation,t.source,t.outcome,t.credential_version,t.created_at,t.input_tokens,t.output_tokens,t.details_json`

func scanAuthenticityTraffic(row interface{ Scan(...any) error }, v *authenticityTraffic, extra ...any) error {
	args := []any{&v.ID, &v.Selection.ProviderID, &v.Selection.AccountID, &v.Selection.CredentialID, &v.Selection.ModelID, &v.Selection.Group, &v.Selection.Endpoint, &v.Operation, &v.Source, &v.Outcome, &v.CredentialVersion, &v.At, &v.Input, &v.Output, &v.Details}
	return row.Scan(append(args, extra...)...)
}
func (s *Server) captureAuthenticity(ctx context.Context, id, stamp string, f authenticityCapture, expected authenticityTraffic) error {
	var t authenticityTraffic
	if err := scanAuthenticityTraffic(s.Store.DB.QueryRowContext(ctx, "SELECT "+authenticityTrafficColumns+" FROM telemetry t WHERE t.id=?", id), &t); err != nil {
		return err
	}
	if t.hash() != expected.hash() {
		return errors.New("request record changed before local attestation")
	}
	f.Version = authenticityCaptureVersion
	f.SampleID = id
	raw, err := json.Marshal(f)
	if err != nil {
		return err
	}
	_, err = s.Store.DB.ExecContext(ctx, "INSERT INTO authenticity_local_samples VALUES(?,?,?,?)", id, stamp, t.hash(), string(raw))
	return err
}

type authenticityScope struct {
	Selection             Selection `json:"selection"`
	Canonical             string    `json:"canonical_id"`
	Aliases               []string  `json:"expected_response_names"`
	Origin                string    `json:"origin"`
	Operation             string    `json:"operation"`
	CredentialVersion     int       `json:"credential_version"`
	ConfigHash            string    `json:"config_hash"`
	MetadataHash          string    `json:"metadata_hash"`
	MetadataSource        string    `json:"metadata_source"`
	DeclaredCapabilities  []string  `json:"declared_capabilities"`
	DeclaredContextTokens *int64    `json:"declared_context_tokens"`
	EndpointHash          string    `json:"endpoint_hash"`
	ContextHash           string    `json:"context_hash"`
	MaterialStamp         string    `json:"-"`
}

func (s *Server) authenticityScope(ctx context.Context, q queryRows, v Selection) (authenticityScope, error) {
	out := authenticityScope{Selection: v, Aliases: []string{}}
	if !validScope(v) || !connectorMemberCurrent(ctx, q, v) {
		return out, errors.New("active My Models scope required")
	}
	var p Provider
	var cipher, fields, group string
	var enabled int
	err := q.QueryRowContext(ctx, `SELECT p.id,p.auth_mode,COALESCE(o.base_url,p.base_url),COALESCE(o.auth_mode,''),c.encrypted_key,COALESCE(f.fields_json,''),COALESCE(g.group_id,''),c.enabled,COALESCE((SELECT MAX(version) FROM credential_secret_versions WHERE provider_id=c.provider_id AND account_id=c.account_id AND credential_id=c.id),0) FROM credentials c JOIN providers p ON p.id=c.provider_id LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id LEFT JOIN credential_auth_fields f ON f.provider_id=c.provider_id AND f.account_id=c.account_id AND f.credential_id=c.id LEFT JOIN credential_context g ON g.provider_id=c.provider_id AND g.account_id=c.account_id AND g.credential_id=c.id WHERE c.provider_id=? AND c.account_id=? AND c.id=? AND c.role='INFERENCE'`, v.ProviderID, v.AccountID, v.CredentialID).Scan(&p.ID, &p.AuthMode, &p.BaseURL, &p.CredentialMode, &cipher, &fields, &group, &enabled, &out.CredentialVersion)
	if err != nil || enabled != 1 || group != v.Group || !validateHTTPS(p.BaseURL) {
		return out, errors.New("selected credential, origin or group unavailable")
	}
	out.Origin = p.BaseURL
	out.MaterialStamp = credentialMaterialStamp(cipher, fields)
	out.Operation = v.Endpoint
	if _, ok := endpointNames[v.Endpoint]; !ok {
		if q.QueryRowContext(ctx, "SELECT operation FROM endpoint_profiles WHERE provider_id=? AND id=?", v.ProviderID, v.Endpoint).Scan(&out.Operation) != nil {
			return out, errors.New("endpoint unavailable")
		}
	}
	if !conversationOperation(out.Operation) {
		return out, errors.New("suite supports conversation operations only")
	}
	cfg := providerConfiguration(ctx, q, p)
	out.ConfigHash = connectorHash(cfg)
	endpoint, err := requestEndpointFrom(ctx, q, p, cfg, v.Endpoint, v.ModelID)
	if err != nil {
		return out, errors.New("endpoint unavailable")
	}
	// Stream state is separately part of condition hash; Gemini URL mode is
	// canonicalized to the streaming variant solely for local binding.
	out.EndpointHash = authenticityEndpointHash(endpoint, out.Operation)
	meta := ModelMetadata{CanonicalID: canonicalID(v.ModelID), Source: "NORMALIZATION"}
	var raw, ruleID, ruleRaw string
	if err = q.QueryRowContext(ctx, "SELECT metadata_json FROM model_metadata WHERE provider_id=? AND model_id=?", v.ProviderID, v.ModelID).Scan(&raw); err == nil {
		if json.Unmarshal([]byte(raw), &meta) != nil {
			return out, errors.New("metadata unavailable")
		}
	}
	if meta.Source != "MANUAL" && q.QueryRowContext(ctx, "SELECT v.id,v.data_json FROM knowledge_versions v JOIN knowledge_active a ON a.version_id=v.id WHERE a.kind='MODEL_ALIAS_RULE' AND a.subject=?", v.ProviderID+"/"+v.ModelID).Scan(&ruleID, &ruleRaw) == nil {
		var rule map[string]any
		if json.Unmarshal([]byte(ruleRaw), &rule) == nil && validID(str(rule["canonical_id"])) {
			meta.CanonicalID = str(rule["canonical_id"])
		}
	}
	if !validID(meta.CanonicalID) {
		return out, errors.New("canonical mapping unavailable")
	}
	out.Canonical = meta.CanonicalID
	out.MetadataSource = meta.Source
	out.DeclaredCapabilities = meta.Capabilities
	out.DeclaredContextTokens = meta.ContextTokens
	for _, alias := range append([]string{v.ModelID, meta.CanonicalID}, meta.Aliases...) {
		if alias != "" && safeAuthenticityModel(alias, nil) == alias && !oneOf(alias, out.Aliases...) {
			out.Aliases = append(out.Aliases, alias)
		}
	}
	out.MetadataHash = connectorHash(struct {
		Metadata ModelMetadata
		RuleID   string
	}{meta, ruleID})
	out.ContextHash = connectorHash(struct {
		Scope authenticityScope
		Stamp string
	}{out, out.MaterialStamp})
	return out, nil
}
func authenticityEndpointHash(endpoint, operation string) string {
	if operation == "GEMINI_NATIVE" {
		endpoint = geminiEndpoint(strings.Replace(endpoint, ":generateContent", ":streamGenerateContent", 1), true)
	}
	return contentHash([]byte(endpoint))
}
func safeAuthenticityModel(v string, secrets []string) string {
	if rawSecretPattern.MatchString(v) || strings.TrimSpace(v) != v || safeText(v, 128, secrets...) != v {
		return ""
	}
	return v
}
