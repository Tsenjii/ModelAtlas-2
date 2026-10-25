package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

const connectorMappingSchema = `
CREATE TABLE IF NOT EXISTS connector_mapping_samples(id TEXT PRIMARY KEY,discovery_id TEXT NOT NULL REFERENCES connector_discoveries(id),report_hash TEXT NOT NULL,context_hash TEXT NOT NULL,source_url TEXT NOT NULL,source_hash TEXT NOT NULL,path TEXT NOT NULL,root_json TEXT NOT NULL CHECK(json_valid(root_json)),root_hash TEXT NOT NULL,observed_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS connector_mapping_requests(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,version_id TEXT REFERENCES connector_versions(id));`

var errMappingReview = errors.New("saved source, exact model scope and current field review required")

type connectorMappingSample struct {
	ID, DiscoveryID, ReportHash, ContextHash, SourceURL, SourceHash, Path string
	Root                                                                  any
	At                                                                    int64
}
type mappingField struct {
	Path  string `json:"path"`
	Type  string `json:"type"`
	Value string `json:"value"`
}
type mappingContainer struct {
	Root       string   `json:"root_pointer"`
	ModelPaths []string `json:"model_paths"`
}
type mappingView struct {
	ID         string             `json:"id"`
	Selection  Selection          `json:"selection"`
	SourceURL  string             `json:"source_url"`
	SourceHash string             `json:"source_sha256"`
	SampleHash string             `json:"sample_sha256"`
	At         int64              `json:"observed_at"`
	Containers []mappingContainer `json:"containers"`
	RootFields []mappingField     `json:"source_fields"`
	Fields     []mappingField     `json:"row_fields"`
}
type mappingLocation struct {
	Path   string `json:"path"`
	Source bool   `json:"source_root"`
}
type connectorMappingRequest struct {
	ID        string                     `json:"id"`
	SampleID  string                     `json:"sample_id"`
	Kind      string                     `json:"kind"`
	Root      string                     `json:"root_pointer"`
	ModelPath string                     `json:"model_path"`
	Scope     mappingLocation            `json:"scope_field"`
	Fields    map[string]string          `json:"fields"`
	Units     map[string]mappingLocation `json:"units"`
}

func mappingProblem(w http.ResponseWriter, e error) {
	if siteRecipeStorageFailure(e) {
		http.Error(w, "saved mapping evidence unavailable; read state before retrying", 503)
	} else {
		http.Error(w, "source sample or reviewed mapping changed; read the current source and fields", 409)
	}
}

// Samples are local source data, never prompts, code, auth definitions or sync
// execution grants. An oversized response remains in the discovery history but
// is not silently truncated into a different mapping fixture.
func prepareMappingSample(root any, discovery, sourceURL, sourceHash, path, hash string, at int64) *connectorMappingSample {
	if !connectorDiscoveryPath(path) || siteReferenceURL(path) {
		return nil
	}
	root = stripMappingSecrets(root, "", 0)
	raw, e := json.Marshal(root)
	if e != nil || len(raw) > 256<<10 || len(mappingContainers(root, "")) == 0 {
		return nil
	}
	return &connectorMappingSample{ID: newID(), DiscoveryID: discovery, ContextHash: hash, SourceURL: sourceURL, SourceHash: sourceHash, Path: path, Root: root, At: at}
}
func stripMappingSecrets(v any, path string, depth int) any {
	if depth > 32 || !connectorSafePointer(path) {
		return nil
	}
	switch x := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for key, value := range x {
			next := path + "/" + strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1")
			if connectorSafePointer(next) && !mappingSecretField(key) && !rawSecretPattern.MatchString(key) {
				out[key] = stripMappingSecrets(value, next, depth+1)
			}
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, value := range x {
			out[i] = stripMappingSecrets(value, path, depth+1)
		}
		return out
	case string:
		if rawSecretPattern.MatchString(x) {
			return nil
		}
	}
	return v
}
func mappingSecretField(key string) bool {
	normal := strings.Map(func(r rune) rune {
		if r >= 'A' && r <= 'Z' {
			return r + ('a' - 'A')
		}
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, key)
	if oneOf(normal, "key", "token", "pat", "auth") {
		return true
	}
	for _, word := range []string{"apikey", "clientkey", "privatekey", "secret", "accesstoken", "refreshtoken", "bearertoken", "authorization", "authentication", "password", "passwd", "cookie", "credential"} {
		if strings.Contains(normal, word) {
			return true
		}
	}
	return false
}
func saveMappingSamples(ctx context.Context, tx *sql.Tx, samples []connectorMappingSample, report connectorDiscoveryReport) error {
	if !oneOf(report.Status, "DRAFTS_READY", "NO_SUPPORTED_MAPPING") {
		return nil
	}
	for _, v := range samples {
		raw, e := json.Marshal(v.Root)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, "INSERT INTO connector_mapping_samples VALUES(?,?,?,?,?,?,?,?,?,?)", v.ID, v.DiscoveryID, connectorHash(report), v.ContextHash, v.SourceURL, v.SourceHash, v.Path, string(raw), connectorHash(v.Root), v.At)
		if e != nil {
			return e
		}
	}
	return nil
}
func readMappingSample(ctx context.Context, q queryRows, id string) (connectorMappingSample, connectorDiscoveryReport, error) {
	return readMappingSampleCurrent(ctx, q, id, true)
}
func readMappingSampleCurrent(ctx context.Context, q queryRows, id string, fresh bool) (connectorMappingSample, connectorDiscoveryReport, error) {
	var v connectorMappingSample
	var report connectorDiscoveryReport
	var raw, hash, parent string
	e := q.QueryRowContext(ctx, "SELECT id,discovery_id,report_hash,context_hash,source_url,source_hash,path,root_json,root_hash,observed_at FROM connector_mapping_samples WHERE id=?", id).Scan(&v.ID, &v.DiscoveryID, &v.ReportHash, &v.ContextHash, &v.SourceURL, &v.SourceHash, &v.Path, &raw, &hash, &v.At)
	if e != nil {
		return v, report, e
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	if decoder.Decode(&v.Root) != nil || hash != connectorHash(v.Root) || now() < v.At || fresh && now()-v.At > int64(24*time.Hour/time.Millisecond) {
		return v, report, errMappingReview
	}
	e = q.QueryRowContext(ctx, "SELECT report_json FROM connector_discoveries WHERE id=?", v.DiscoveryID).Scan(&parent)
	if e != nil {
		return v, report, e
	}
	if json.Unmarshal([]byte(parent), &report) != nil || connectorHash(report) != v.ReportHash || !oneOf(report.Status, "DRAFTS_READY", "NO_SUPPORTED_MAPPING") || report.ObservedAt != v.At || !connectorMemberCurrent(ctx, q, report.Selection) {
		return v, report, errMappingReview
	}
	matched := false
	for _, source := range report.Sources {
		if source.MappingSampleID == v.ID && source.URL == v.SourceURL && source.Hash == v.SourceHash && source.Status == "INSPECTED" {
			matched = true
		}
	}
	h, e := connectorContext(ctx, q, ConnectorConfig{Role: "NONE"}, report.Selection)
	if e != nil {
		return v, report, e
	}
	p, e := boundProvider(ctx, q, report.Selection.ProviderID, report.Selection.AccountID, report.Selection.CredentialID)
	if e != nil {
		return v, report, e
	}
	if !matched || h != v.ContextHash || !connectorDiscoveryPath(v.Path) || originPath(p, v.Path) != v.SourceURL {
		return v, report, errMappingReview
	}
	return v, report, nil
}

// Fixed JSON pointers only. Array containers keep all rows, so a duplicate model
// cannot be hidden by taking the first element as an allegedly complete list.
func mappingContainers(root any, model string) []mappingContainer {
	out := []mappingContainer{}
	nodes := 0
	var walk func(any, string, int)
	walk = func(value any, path string, depth int) {
		nodes++
		if nodes > 128 || depth > 7 || !connectorSafePointer(path) {
			return
		}
		if rows, ok := value.([]any); ok {
			if len(rows) == 0 || len(rows) > 1000 {
				return
			}
			set := map[string]bool{}
			for _, row := range rows {
				for _, f := range mappingFields(row, true) {
					if model == "" || f.Value == model {
						set[f.Path] = true
					}
				}
			}
			paths := []string{}
			for p := range set {
				paths = append(paths, p)
			}
			sort.Strings(paths)
			if len(paths) > 0 {
				out = append(out, mappingContainer{path, paths})
			}
			return
		}
		if obj, ok := value.(map[string]any); ok {
			keys := []string{}
			for key := range obj {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				walk(obj[key], path+"/"+strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1"), depth+1)
			}
		}
	}
	walk(root, "", 0)
	if len(out) == 0 {
		if _, ok := root.(map[string]any); ok {
			paths := []string{}
			for _, f := range mappingFields(root, true) {
				if model == "" || f.Value == model {
					paths = append(paths, f.Path)
				}
			}
			if len(paths) > 0 {
				out = append(out, mappingContainer{"", paths})
			}
		}
	}
	return out
}
func mappingFields(value any, skipArrays bool) []mappingField {
	out := []mappingField{}
	nodes := 0
	var walk func(any, string, int)
	walk = func(v any, path string, depth int) {
		nodes++
		if nodes > 2048 || len(out) >= 128 || depth > 8 || !connectorSafePointer(path) {
			return
		}
		switch x := v.(type) {
		case map[string]any:
			keys := []string{}
			for key := range x {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				walk(x[key], path+"/"+strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1"), depth+1)
			}
		case []any:
			if !skipArrays && len(x) <= 32 {
				for i, item := range x {
					walk(item, fmt.Sprintf("%s/%d", path, i), depth+1)
				}
			}
		case string:
			if path != "" && len(x) <= 200 && safeText(x, 200) == x && !rawSecretPattern.MatchString(x) {
				out = append(out, mappingField{path, "TEXT", x})
			}
		case json.Number:
			out = append(out, mappingField{path, "NUMBER", x.String()})
		case bool:
			out = append(out, mappingField{path, "BOOLEAN", fmt.Sprint(x)})
		}
	}
	walk(value, "", 0)
	return out
}
func mappingRows(root any, path, modelPath, model string) ([]any, any, error) {
	allowed := false
	for _, c := range mappingContainers(root, model) {
		if c.Root == path {
			for _, p := range c.ModelPaths {
				if p == modelPath {
					allowed = true
				}
			}
		}
	}
	if !allowed {
		return nil, nil, errMappingReview
	}
	value := pointer(root, path)
	rows, ok := value.([]any)
	if !ok {
		rows = []any{value}
	}
	var selected any
	count := 0
	for _, row := range rows {
		if str(pointer(row, modelPath)) == model {
			selected = row
			count++
		}
	}
	if count != 1 {
		return nil, nil, errMappingReview
	}
	return rows, selected, nil
}
func (s *Server) getMappingSample(w http.ResponseWriter, r *http.Request) {
	v, report, e := readMappingSample(r.Context(), s.Store.DB, r.URL.Query().Get("id"))
	if e != nil {
		mappingProblem(w, e)
		return
	}
	out := mappingView{ID: v.ID, Selection: report.Selection, SourceURL: v.SourceURL, SourceHash: v.SourceHash, SampleHash: connectorHash(v.Root), At: v.At, Containers: mappingContainers(v.Root, report.Selection.ModelID), RootFields: mappingFields(v.Root, true), Fields: []mappingField{}}
	if r.URL.Query().Has("root_pointer") {
		_, row, e := mappingRows(v.Root, r.URL.Query().Get("root_pointer"), r.URL.Query().Get("model_path"), report.Selection.ModelID)
		if e != nil {
			mappingProblem(w, e)
			return
		}
		out.Fields = mappingFields(row, true)
	}
	jsonOK(w, 200, out)
}
func mappingConfig(v connectorMappingRequest, sample connectorMappingSample, report connectorDiscoveryReport) (ConnectorConfig, connectorFixture, error) {
	c := ConnectorConfig{Kind: v.Kind, Path: sample.Path, Role: "NONE", Scope: "MODEL_PROVIDER", Root: v.Root, Fields: map[string]string{"model_id": v.ModelPath}, Unit: "NONE", Parser: "declarative-get-v2", Freshness: 86400, FieldUnits: map[string]string{}, Checks: map[string]string{}, RootChecks: map[string]string{}, Origin: report.BaseURL}
	var fixture connectorFixture
	if !oneOf(v.Kind, "PRICING", "QUOTA", "PERFORMANCE", "USAGE", "BALANCE", "NOTICE") || len(v.Fields) < 1 || len(v.Fields) > 4 || len(v.Units) > len(v.Fields) {
		return c, fixture, errMappingReview
	}
	for field := range v.Units {
		if _, ok := v.Fields[field]; !ok {
			return c, fixture, errMappingReview
		}
	}
	rows, row, e := mappingRows(sample.Root, v.Root, v.ModelPath, report.Selection.ModelID)
	if e != nil {
		return c, fixture, e
	}
	rowFields, rootFields := map[string]string{}, map[string]string{}
	for _, f := range mappingFields(row, true) {
		rowFields[f.Path] = f.Value
	}
	for _, f := range mappingFields(sample.Root, true) {
		rootFields[f.Path] = f.Value
	}
	check := func(loc mappingLocation, want string) bool {
		fields, checks := rowFields, c.Checks
		if loc.Source {
			fields, checks = rootFields, c.RootChecks
		}
		value, ok := fields[loc.Path]
		if !ok || value != want || want == "" {
			return false
		}
		checks[loc.Path] = want
		return true
	}
	if !check(v.Scope, "MODEL_PROVIDER") {
		return c, fixture, errMappingReview
	}
	for name, path := range v.Fields {
		if oneOf(name, "model_id", "group", "name") || !oneOf(name, strings.Fields(connectorFields[v.Kind])...) {
			return c, fixture, errMappingReview
		}
		if _, ok := rowFields[path]; !ok {
			return c, fixture, errMappingReview
		}
		c.Fields[name] = path
		if name == "notice" {
			if _, ok := v.Units[name]; ok {
				return c, fixture, errMappingReview
			}
			continue
		}
		loc, ok := v.Units[name]
		if !ok {
			return c, fixture, errMappingReview
		}
		unit := rowFields[loc.Path]
		if loc.Source {
			unit = rootFields[loc.Path]
		}
		if v.Kind == "PRICING" && name != "fixed" {
			if c.Unit != "NONE" && c.Unit != unit {
				return c, fixture, errMappingReview
			}
			c.Unit = unit
		}
		if oneOf(v.Kind, "BALANCE", "USAGE", "QUOTA") && oneOf(name, "used", "remaining", "balance") {
			if c.Unit != "NONE" && c.Unit != unit {
				return c, fixture, errMappingReview
			}
			c.Unit = unit
		}
		expected := connectorFieldUnit(v.Kind, name, c.Unit)
		if expected == "" || !check(loc, expected) {
			return c, fixture, errMappingReview
		}
		c.FieldUnits[name] = expected
	}
	if !safeConnector(c) || !connectorChecks(sample.Root, c.RootChecks) {
		return c, fixture, errMappingReview
	}
	facts, e := extractConnector(c, report.Selection, rows)
	if e != nil {
		return c, fixture, e
	}
	fixture = connectorFixture{ConfigHash: connectorHash(c), ContextHash: sample.ContextHash, Selection: report.Selection, Rows: []any{connectorFixtureRow(row, c)}, Expected: facts, RootValues: c.RootChecks}
	replay, e := extractConnector(c, report.Selection, fixture.Rows)
	if e != nil || connectorHash(replay) != connectorHash(facts) {
		return c, fixture, errMappingReview
	}
	return c, fixture, nil
}
func (s *Server) getMappedConnectorResult(w http.ResponseWriter, r *http.Request) {
	var id string
	e := s.Store.DB.QueryRowContext(r.Context(), "SELECT version_id FROM connector_mapping_requests WHERE id=?", r.URL.Query().Get("id")).Scan(&id)
	if e == sql.ErrNoRows {
		http.Error(w, "mapping request not found; read state before retrying", 404)
		return
	}
	if e != nil {
		mappingProblem(w, e)
		return
	}
	jsonOK(w, 200, map[string]string{"id": id})
}
func (s *Server) proposeMappedConnector(w http.ResponseWriter, r *http.Request) {
	var v connectorMappingRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || len(v.ID) > 120 || !validID(v.SampleID) {
		http.Error(w, "stable mapping ID and saved source required", 422)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		mappingProblem(w, e)
		return
	}
	if containsKnownSecret(v, secrets) {
		http.Error(w, "raw secrets are not mapping data", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		mappingProblem(w, e)
		return
	}
	defer tx.Rollback()
	requestHash := connectorHash(v)
	var prior, versionID string
	e = tx.QueryRowContext(r.Context(), "SELECT request_hash,version_id FROM connector_mapping_requests WHERE id=?", v.ID).Scan(&prior, &versionID)
	if e == nil {
		if prior != requestHash {
			mappingProblem(w, errMappingReview)
			return
		}
		jsonOK(w, 200, map[string]string{"id": versionID})
		return
	}
	if e != sql.ErrNoRows {
		mappingProblem(w, e)
		return
	}
	// Reserve this exact ID before inspecting evidence. Concurrent originals and
	// retries cannot race a rejected review into two different committed drafts.
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO connector_mapping_requests VALUES(?,?,NULL)", v.ID, requestHash); e != nil {
		mappingProblem(w, e)
		return
	}
	sample, report, e := readMappingSample(r.Context(), tx, v.SampleID)
	if e != nil {
		mappingProblem(w, e)
		return
	}
	cfg, fixture, e := mappingConfig(v, sample, report)
	if e != nil {
		mappingProblem(w, e)
		return
	}
	versionID = newID()
	derivedID := newID()
	name := "mapped-" + strings.ToLower(v.Kind) + "-" + contentHash([]byte(cfg.Path + "|" + cfg.Root))[:12]
	var version int
	if e = tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM connector_versions WHERE provider_id=? AND name=?", report.Selection.ProviderID, name).Scan(&version); e != nil {
		mappingProblem(w, e)
		return
	}
	proof := connectorDiscoveryProof{ReportID: derivedID, SourceURL: sample.SourceURL, SourceHash: sample.SourceHash, ObservedAt: sample.At, Selection: report.Selection, Family: report.Family, Version: report.Version, FixtureStatus: "PASSED", Expected: fixture.Expected, Limitations: append(append([]string{}, report.Limitations...), "User-reviewed field semantics over a saved public sample; no independent verification or new HTTP during generation."), MappingSampleID: sample.ID, MappingSampleHash: connectorHash(sample.Root), MappingParentID: sample.DiscoveryID}
	zero := 0
	derived := connectorDiscoveryReport{ID: derivedID, Selection: report.Selection, SourcePath: sample.Path, BaseURL: report.BaseURL, Status: "DRAFTS_READY", Family: report.Family, Version: report.Version, ObservedAt: sample.At, FinishedAt: now(), HTTPRequests: &zero, RequestLimit: 0, Sources: []connectorDiscoverySource{{URL: sample.SourceURL, Status: "INSPECTED", Hash: sample.SourceHash, DraftIDs: []string{versionID}}}, DraftIDs: []string{versionID}, Unknown: report.Unknown, Conflicts: []string{}, Limitations: proof.Limitations, FamilyEvidence: report.FamilyEvidence}
	configRaw, _ := json.Marshal(cfg)
	proofRaw, _ := json.Marshal(proof)
	fixtureRaw, _ := json.Marshal(fixture)
	reportRaw, _ := json.Marshal(derived)
	evidenceRaw, _ := json.Marshal([]string{sample.DiscoveryID + ":" + sample.SourceHash})
	_, e = tx.ExecContext(r.Context(), "INSERT INTO connector_discoveries VALUES(?,?,?,?,?)", derivedID, requestHash, report.Selection.ProviderID, string(reportRaw), sample.At)
	if e == nil {
		_, e = tx.ExecContext(r.Context(), "INSERT INTO connector_versions(id,provider_id,name,version,config_json,evidence_json,reason,created_at) VALUES(?,?,?,?,?,?,?,?)", versionID, report.Selection.ProviderID, name, version, string(configRaw), string(evidenceRaw), "Explicit field mapping from saved public evidence; fixture passed, real HTTP test and confirmation still required.", now())
	}
	if e == nil {
		_, e = tx.ExecContext(r.Context(), "INSERT INTO connector_generated_versions VALUES(?,?,?,?)", versionID, derivedID, string(proofRaw), string(fixtureRaw))
	}
	if e == nil {
		_, e = tx.ExecContext(r.Context(), "UPDATE connector_mapping_requests SET version_id=? WHERE id=? AND request_hash=?", versionID, v.ID, requestHash)
	}
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		mappingProblem(w, e)
		return
	}
	jsonOK(w, 201, map[string]string{"id": versionID})
}
