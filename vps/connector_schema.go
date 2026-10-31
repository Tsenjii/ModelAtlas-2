package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

// v2 is still a fixed, declarative GET grammar. Checks are literal equalities,
// not expressions, instructions, code, or a server-provided parser.
func safeConnectorSchema(c ConnectorConfig) bool {
	if c.Origin != "" && !publicURL(c.Origin) {
		return false
	}
	if !connectorSafePointer(c.Root) {
		return false
	}
	for _, path := range c.Fields {
		if !connectorSafePointer(path) {
			return false
		}
	}
	if !oneOf(c.Parser, "", "declarative-get-v1", "declarative-get-v2") || c.Freshness != 0 && (c.Freshness < 60 || c.Freshness > 604800) || len(c.FieldUnits) > 12 {
		return false
	}
	if c.Parser == "declarative-get-v2" && c.Freshness == 0 {
		return false
	}
	for _, checks := range []map[string]string{c.Checks, c.RootChecks} {
		if len(checks) > 4 {
			return false
		}
		for path, want := range checks {
			if !connectorSafePointer(path) || want == "" || safeText(want, 80) != want || rawSecretPattern.MatchString(want) {
				return false
			}
		}
	}
	for field, unit := range c.FieldUnits {
		if c.Fields[field] == "" || connectorFieldUnit(c.Kind, field, c.Unit) != unit || unit == "" {
			return false
		}
	}
	if c.Parser == "declarative-get-v2" {
		for field := range c.Fields {
			if !oneOf(field, "model_id", "group", "name", "notice") && (connectorFieldUnit(c.Kind, field, c.Unit) == "" || c.FieldUnits[field] != connectorFieldUnit(c.Kind, field, c.Unit)) {
				return false
			}
		}
	}
	return true
}
func connectorSafePointer(path string) bool {
	if !validPointer(path) {
		return false
	}
	for _, part := range strings.Split(strings.ToLower(path), "/") {
		part = strings.ReplaceAll(strings.ReplaceAll(part, "~1", "/"), "~0", "~")
		if oneOf(part, "key", "api_key", "secret", "access_token", "token", "credentials", "authorization", "pat", "cookie") {
			return false
		}
	}
	return true
}
func connectorFieldUnit(kind, field, unit string) string {
	if kind == "PRICING" {
		if field == "fixed" {
			return "USD/request"
		}
		return unit
	}
	units := map[string]string{"rpd": "REQUESTS/day", "tpd": "TOKENS/day", "rpm": "REQUESTS/minute", "tpm": "TOKENS/minute", "tps": "TOKENS/second", "ttft_ms": "MS", "latency_ms": "MS", "success_rate": "RATIO", "sample_count": "REQUESTS", "requests": "REQUESTS", "tokens": "TOKENS", "input_tokens": "TOKENS", "output_tokens": "TOKENS", "reset_at": "UNIX_MS"}
	if u := units[field]; u != "" {
		return u
	}
	if oneOf(kind, "BALANCE", "USAGE", "QUOTA") && oneOf(field, "used", "remaining", "balance") && oneOf(unit, "USD", "CREDITS", "TOKENS", "REQUESTS") {
		return unit
	}
	return ""
}
func connectorChecks(value any, checks map[string]string) bool {
	for path, want := range checks {
		if str(pointer(value, path)) != want {
			return false
		}
	}
	return true
}
func connectorMemberCurrent(ctx context.Context, q queryRows, v Selection) bool {
	var count int
	err := q.QueryRowContext(ctx, `SELECT COUNT(*) FROM my_models m LEFT JOIN membership_state x ON x.scope_key=? WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=? AND m.model_id=? AND m.group_id=? AND m.endpoint=? AND COALESCE(x.state,'ACTIVE')='ACTIVE'`, scopeKey(v), v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint).Scan(&count)
	return err == nil && count == 1
}

type connectorFixture struct {
	ConfigHash  string            `json:"config_hash"`
	ContextHash string            `json:"context_hash"`
	Selection   Selection         `json:"selection"`
	Rows        []any             `json:"rows"`
	Expected    map[string]any    `json:"expected"`
	RootValues  map[string]string `json:"root_values"`
}

func (s *Server) validateConnectorFixture(ctx context.Context, id string, c ConnectorConfig, v Selection, hash string) error {
	return validateConnectorFixtureQuery(ctx, s.Store.DB, id, c, v, hash)
}
func validateConnectorFixtureQuery(ctx context.Context, q queryRows, id string, c ConnectorConfig, v Selection, hash string) error {
	var raw, proofRaw string
	err := q.QueryRowContext(ctx, "SELECT fixture_json,proof_json FROM connector_generated_versions WHERE version_id=?", id).Scan(&raw, &proofRaw)
	if err == sql.ErrNoRows {
		return nil
	} // Existing/manual drafts retain their original real-test workflow.
	if err != nil {
		return errors.Join(errors.New("fixture unavailable"), err)
	}
	var proof connectorDiscoveryProof
	if json.Unmarshal([]byte(proofRaw), &proof) != nil {
		return errMappingReview
	}
	if proof.MappingSampleID != "" {
		// Fresh samples admit new mappings. Existing versions must instead replay
		// immutable evidence and obtain a fresh real HTTP receipt; source history
		// does not expire an already approved reader.
		sample, report, e := readMappingSampleCurrent(ctx, q, proof.MappingSampleID, false)
		if e != nil {
			return e
		}
		if sample.DiscoveryID != proof.MappingParentID || connectorHash(sample.Root) != proof.MappingSampleHash || sample.SourceURL != proof.SourceURL || sample.SourceHash != proof.SourceHash || connectorHash(report.Selection) != connectorHash(v) || sample.BindingContext != hash {
			return errMappingReview
		}
		bind, e := connectorSampleBinding(report)
		if e != nil || bind.Role != c.Role || bind.PATID != c.PATID {
			return errMappingReview
		}
	}
	var f connectorFixture
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	if decoder.Decode(&f) != nil || f.ConfigHash != connectorHash(c) || f.ContextHash != hash || connectorHash(f.Selection) != connectorHash(v) {
		return errors.New("generated fixture scope changed; regenerate draft")
	}
	rootOK := len(f.RootValues) == len(c.RootChecks)
	for path, want := range c.RootChecks {
		if f.RootValues[path] != want {
			rootOK = false
		}
	}
	if !rootOK {
		return errors.New("generated fixture root checks changed")
	}
	facts, err := extractConnector(c, v, f.Rows)
	if err != nil || connectorHash(facts) != connectorHash(f.Expected) {
		return errors.New("generated fixture did not reproduce; regenerate draft")
	}
	return nil
}
func (s *Server) connectorExecutionBound(ctx context.Context, id string, c ConnectorConfig) error {
	var raw string
	var approved ConnectorConfig
	if !safeConnector(c) || s.Store.DB.QueryRowContext(ctx, "SELECT config_json FROM connector_execution_bindings WHERE version_id=?", id).Scan(&raw) != nil || json.Unmarshal([]byte(raw), &approved) != nil || connectorHash(approved) != connectorHash(c) {
		return errors.New("active connector definition changed; save and review a new version")
	}
	return nil
}

// Retain only explicitly mapped fields/checks, never the server's arbitrary JSON.
func connectorFixtureRow(row any, c ConnectorConfig) map[string]any {
	out := map[string]any{}
	paths := []string{}
	for _, path := range c.Fields {
		paths = append(paths, path)
	}
	for path := range c.Checks {
		paths = append(paths, path)
	}
	for _, path := range paths {
		value := pointer(row, path)
		if value == nil || path == "" {
			continue
		}
		target := out
		parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
		for i, key := range parts {
			key = strings.ReplaceAll(strings.ReplaceAll(key, "~1", "/"), "~0", "~")
			if i == len(parts)-1 {
				target[key] = value
				break
			}
			if target[key] == nil {
				target[key] = map[string]any{}
			}
			next, ok := target[key].(map[string]any)
			if !ok {
				break
			}
			target = next
		}
	}
	return out
}

func connectorHash(value any) string {
	raw, err := json.Marshal(value)
	if err != nil {
		return ""
	}
	var canonical any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if decoder.Decode(&canonical) != nil {
		return ""
	}
	raw, err = json.Marshal(canonical)
	if err != nil {
		return ""
	}
	return contentHash(raw)
}
func connectorStripRawSecrets(value any, depth int) any {
	if depth > 64 {
		return nil
	}
	switch v := value.(type) {
	case string:
		if rawSecretPattern.MatchString(v) {
			return nil
		}
		return v
	case map[string]any:
		out := map[string]any{}
		for k, item := range v {
			if !rawSecretPattern.MatchString(k) {
				out[k] = connectorStripRawSecrets(item, depth+1)
			}
		}
		return out
	case []any:
		out := make([]any, len(v))
		for i, item := range v {
			out[i] = connectorStripRawSecrets(item, depth+1)
		}
		return out
	default:
		return value
	}
}
