package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const connectorDiscoverySchema = `
 CREATE TABLE IF NOT EXISTS connector_discoveries(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,provider_id TEXT NOT NULL REFERENCES providers(id),report_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS connector_generated_versions(version_id TEXT PRIMARY KEY REFERENCES connector_versions(id),discovery_id TEXT NOT NULL REFERENCES connector_discoveries(id),proof_json TEXT NOT NULL,fixture_json TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS connector_test_receipts(version_id TEXT PRIMARY KEY REFERENCES connector_versions(id),context_hash TEXT NOT NULL,config_hash TEXT NOT NULL,tested_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS connector_execution_bindings(version_id TEXT PRIMARY KEY REFERENCES connector_versions(id),config_json TEXT NOT NULL);
 INSERT OR IGNORE INTO connector_execution_bindings SELECT a.version_id,v.config_json FROM connector_active a JOIN connector_versions v ON v.id=a.version_id;
`

type connectorDiscoveryRequest struct {
	ID            string    `json:"id"`
	ReferenceURLs []string  `json:"reference_urls,omitempty"`
	Selection     Selection `json:"selection"`
	SourcePath    string    `json:"source_path"`
	CredentialRole string `json:"credential_role,omitempty"`
	PATID string `json:"pat_credential_id,omitempty"`
	ConfirmPrivate bool `json:"confirm_private,omitempty"`
}
type connectorDiscoverySource struct {
	MappingSampleID string   `json:"mapping_sample_id,omitempty"`
	URL             string   `json:"url"`
	Status          string   `json:"status"`
	HTTPStatus      int      `json:"http_status,omitempty"`
	Hash            string   `json:"content_hash,omitempty"`
	DraftIDs        []string `json:"draft_ids"`
	Reason          string   `json:"reason,omitempty"`
}
type connectorDiscoveryReport struct {
	FamilyEvidence []siteFamilyEvidence       `json:"family_evidence,omitempty"`
	ID             string                     `json:"id"`
	Selection      Selection                  `json:"selection"`
	SourcePath     string                     `json:"source_path"`
	BaseURL        string                     `json:"base_url"`
	Status         string                     `json:"status"`
	Family         string                     `json:"family_hint"`
	Version        string                     `json:"version_hint"`
	ObservedAt     int64                      `json:"observed_at"`
	FinishedAt     int64                      `json:"finished_at,omitempty"`
	HTTPRequests   *int                       `json:"http_requests"`
	Attempts       int                        `json:"fetch_attempts"`
	RequestLimit   int                        `json:"request_limit"`
	Sources        []connectorDiscoverySource `json:"sources"`
	DraftIDs       []string                   `json:"draft_ids"`
	Unknown        []string                   `json:"unknown_fields"`
	Conflicts      []string                   `json:"conflicting_kinds"`
	Limitations    []string                   `json:"limitations"`
	CredentialRole string `json:"credential_role,omitempty"`
	PATID string `json:"pat_credential_id,omitempty"`
	PrivateConfirmed bool `json:"private_confirmed,omitempty"`
}
type connectorDiscoveryProof struct {
	MappingSampleID   string         `json:"mapping_sample_id,omitempty"`
	MappingSampleHash string         `json:"mapping_sample_sha256,omitempty"`
	MappingParentID   string         `json:"mapping_parent_id,omitempty"`
	ReportID          string         `json:"report_id"`
	SourceURL         string         `json:"source_url"`
	SourceHash        string         `json:"content_hash"`
	ObservedAt        int64          `json:"observed_at"`
	Selection         Selection      `json:"selection"`
	Family            string         `json:"family_hint"`
	Version           string         `json:"version_hint"`
	FixtureStatus     string         `json:"fixture_status"`
	Expected          map[string]any `json:"fixture_expected"`
	Limitations       []string       `json:"limitations"`
}
type connectorGeneratedDraft struct {
	Config  ConnectorConfig
	Proof   connectorDiscoveryProof
	Fixture connectorFixture
	Source  int
}
type connectorDiscoveryMeterKey struct{}

// Discovery does not probe actions or authentication endpoints, follow redirects,
// fetch an arbitrary origin, or carry any selected credential into public reads.
func connectorDiscoveryPath(path string) bool {
	if !safePath(path) || strings.ContainsAny(path, "%{}") || rawSecretPattern.MatchString(path) {
		return false
	}
	lower := strings.ToLower(path)
	for _, word := range []string{"delete", "logout", "login", "signin", "checkin", "check-in", "redeem", "transfer", "billing/charge", "/key", "token", "secret", "credential", "authorization", "cookie", "/admin", "/user"} {
		if strings.Contains(lower, word) {
			return false
		}
	}
	if path == "/" {
		return true
	}
	for _, word := range []string{"model", "pricing", "price", "quota", "usage", "balance", "status", "notice", "announcement", "performance", "metric", "metadata", "openapi", "docs"} {
		if strings.Contains(lower, word) {
			return true
		}
	}
	return false
}
func (s *Server) discoverConnectors(w http.ResponseWriter, r *http.Request) {
	var v connectorDiscoveryRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	report, fresh, err := s.generateConnectorDrafts(r.Context(), v, 6)
	if err != nil {
		http.Error(w, err.Error(), 409)
		return
	}
	code := 200
	if fresh {
		code = 201
	}
	jsonOK(w, code, report)
}
func (s *Server) listConnectorDiscoveries(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT report_json FROM connector_discoveries WHERE (?='' OR provider_id=?) AND (?='' OR id=?) ORDER BY rowid DESC LIMIT 30", r.URL.Query().Get("provider_id"), r.URL.Query().Get("provider_id"), r.URL.Query().Get("id"), r.URL.Query().Get("id"))
	if err != nil {
		http.Error(w, "discovery history unavailable", 503)
		return
	}
	defer rows.Close()
	out := []connectorDiscoveryReport{}
	for rows.Next() {
		var raw string
		var report connectorDiscoveryReport
		if rows.Scan(&raw) != nil || json.Unmarshal([]byte(raw), &report) != nil {
			http.Error(w, "discovery history unavailable", 503)
			return
		}
		out = append(out, report)
	}
	if rows.Err() != nil {
		http.Error(w, "discovery history unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) generateConnectorDrafts(parent context.Context, v connectorDiscoveryRequest, limit int) (connectorDiscoveryReport, bool, error) {
	if v.CredentialRole != "" && v.CredentialRole != "NONE" || v.PATID != "" || v.ConfirmPrivate {
		return s.generatePrivateConnectorSample(parent, v, limit)
	}
	v.CredentialRole = ""
	var empty connectorDiscoveryReport
	if v.SourcePath == "" {
		v.SourcePath = "/"
	}
	if !validID(v.ID) || len(v.ID) > 120 || !connectorDiscoveryPath(v.SourcePath) || !validScope(v.Selection) || limit < 1 || limit > 6 {
		return empty, false, errors.New("explicit request ID, model scope and read-only metadata path required")
	}
	if len(v.ReferenceURLs) > 2 {
		return empty, false, errors.New("at most two explicit public source references")
	}
	refs := map[string]bool{}
	for _, ref := range v.ReferenceURLs {
		if !siteReferenceURL(ref) || refs[ref] {
			return empty, false, errors.New("bounded raw GitHub source references required")
		}
		refs[ref] = true
	}
	secrets, err := s.knownSecrets(parent)
	if err != nil {
		return empty, false, errors.New("vault unavailable")
	}
	if containsKnownSecret(v, secrets) {
		return empty, false, errors.New("raw secrets are not discovery data")
	}
	for _, secret := range secrets {
		if jsonContainsSecret(v, secret) {
			return empty, false, errors.New("raw secrets are not discovery data")
		}
	}
	requestHash := connectorHash(v)
	var priorHash, raw string
	err = s.Store.DB.QueryRowContext(parent, "SELECT request_hash,report_json FROM connector_discoveries WHERE id=?", v.ID).Scan(&priorHash, &raw)
	if err == nil {
		if priorHash != requestHash || json.Unmarshal([]byte(raw), &empty) != nil {
			return empty, false, errors.New("discovery ID already belongs to another request")
		}
		return empty, false, nil
	}
	if err != nil && err != sql.ErrNoRows {
		return empty, false, errors.New("discovery history unavailable")
	}
	if !s.validateSelection(parent, v.Selection) {
		return empty, false, errors.New("select an active My Models scope before discovery")
	}
	p, err := boundProvider(parent, s.Store.DB, v.Selection.ProviderID, v.Selection.AccountID, v.Selection.CredentialID)
	if err != nil || !publicURL(p.BaseURL) || containsKnownSecret(p.BaseURL, secrets) {
		return empty, false, errors.New("public HTTPS provider origin required")
	}
	bind := ConnectorConfig{Role: "NONE"}
	hash, err := connectorContext(parent, s.Store.DB, bind, v.Selection)
	if err != nil {
		return empty, false, errors.New("selected scope unavailable")
	}
	flight := "connector-discovery:" + v.Selection.ProviderID
	if !s.beginFlight(flight) {
		return empty, false, errors.New("provider discovery busy; read saved history before retrying")
	}
	defer s.endFlight(flight)
	report := connectorDiscoveryReport{ID: v.ID, Selection: v.Selection, SourcePath: v.SourcePath, BaseURL: p.BaseURL, Status: "RUNNING", Family: "UNKNOWN", Version: "UNKNOWN", ObservedAt: now(), RequestLimit: limit, Sources: []connectorDiscoverySource{}, DraftIDs: []string{}, Unknown: []string{"Taiwan registration/access", "recurring reset and eligibility", "effective charges", "private account/PAT endpoints", "model identity"}, Conflicts: []string{}, Limitations: []string{"Public provider claims only; no credentials or inference are sent.", "Observed same-origin read-only metadata paths and up to two explicitly selected passive raw GitHub references; maximum 6 total requests and 30 seconds.", "Missing/ambiguous units or scope remain unknown. Fixture replay is not independent source verification.", "No automatic activation, membership changes, eligibility, or identity verification."}}
	initial, _ := json.Marshal(report)
	if _, err = s.Store.DB.ExecContext(parent, "INSERT INTO connector_discoveries VALUES(?,?,?,?,?)", v.ID, requestHash, v.Selection.ProviderID, string(initial), report.ObservedAt); err != nil {
		return empty, false, errors.New("discovery reservation failed; read history before retrying")
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	meter := 0
	ctx = context.WithValue(ctx, connectorDiscoveryMeterKey{}, &meter)
	queue := append([]string{v.SourcePath}, v.ReferenceURLs...)
	seen := map[string]bool{v.SourcePath: true}
	for _, ref := range v.ReferenceURLs {
		seen[ref] = true
	}
	drafts := []connectorGeneratedDraft{}
	mappingSamples := []connectorMappingSample{}
	for len(queue) > 0 && report.Attempts < limit && ctx.Err() == nil {
		path := queue[0]
		queue = queue[1:]
		target := originPath(p, path)
		reference := siteReferenceURL(path)
		if reference {
			target = path
		}
		report.Attempts++
		body, ctype, status, fetchErr := s.publicFetch(ctx, target)
		source := connectorDiscoverySource{URL: target, HTTPStatus: status, DraftIDs: []string{}}
		if fetchErr != nil {
			source.Status = "UNAVAILABLE"
			source.Reason = "Bounded public read failed; no source claims accepted."
			if status == 401 || status == 403 {
				source.Status = "AUTH_REQUIRED"
				source.Reason = "Authentication required; public discovery does not try keys or PATs."
			}
			var wait *researchOriginWait
			if errors.As(fetchErr, &wait) {
				source.Status = "WAITING_ORIGIN"
				source.Reason = "Saved origin cooldown prevents this read; no HTTP admission."
			}
			report.Sources = append(report.Sources, source)
			continue
		}
		source.Hash = contentHash(body)
		source.Status = "INSPECTED"
		var root any
		decoder := json.NewDecoder(bytes.NewReader(body))
		decoder.UseNumber()
		jsonType := strings.Contains(strings.ToLower(ctype), "json")
		isJSON := jsonType && decoder.Decode(&root) == nil && decoder.Decode(new(any)) == io.EOF
		if jsonType && !isJSON {
			source.Status = "SCHEMA_UNSUPPORTED"
			source.Reason = "JSON shape is invalid or incomplete; no mapping accepted."
			report.Sources = append(report.Sources, source)
			continue
		}
		observations := siteFamilyObservations(target, source.Hash, root, body)
		for _, observation := range observations {
			if !containsKnownSecret(observation, secrets) {
				report.FamilyEvidence = append(report.FamilyEvidence, observation)
			}
		}
		if isJSON {
			root = connectorStripRawSecrets(cleanSecretEcho(root, secrets, 0), 0)
			if !reference {
				if sample := prepareMappingSample(root, v.ID, target, source.Hash, path, hash, report.ObservedAt); sample != nil && !containsKnownSecret(sample.Root, secrets) {
					source.MappingSampleID = sample.ID
					mappingSamples = append(mappingSamples, *sample)
				}
			}
			family, version := connectorFamilyHint(root)
			generated, reasons := inferConnectorMappings(root, path, v.Selection)
			if reference {
				generated = nil
				reasons = []string{"Passive source reference only; provider endpoints require their own observed response and real test."}
			}
			source.Reason = strings.Join(reasons, "; ")
			for _, draft := range generated {
				if len(drafts) >= 12 {
					source.Reason = "Draft limit reached; remaining shapes require manual review."
					break
				}
				draft.Source = len(report.Sources)
				draft.Config.Origin = p.BaseURL
				draft.Fixture.ConfigHash = connectorHash(draft.Config)
				draft.Fixture.ContextHash = hash
				draft.Proof = connectorDiscoveryProof{ReportID: v.ID, SourceURL: target, SourceHash: source.Hash, ObservedAt: report.ObservedAt, Selection: v.Selection, Family: family, Version: version, FixtureStatus: "PASSED", Expected: draft.Fixture.Expected, Limitations: report.Limitations}
				if !containsKnownSecret(draft, secrets) {
					drafts = append(drafts, draft)
				}
			}
			if paths, ok := pointer(root, "/paths").(map[string]any); ok {
				names := []string{}
				for path, methods := range paths {
					if pointer(methods, "/get") != nil {
						names = append(names, path)
					}
				}
				sort.Strings(names)
				for _, path := range names {
					connectorQueuePath(&queue, seen, path, secrets)
				}
			}
		} else {
			_, links, paths := inspectHTML(body, target)
			if reference {
				paths = append(paths, siteLiteralMetadataPaths(body)...)
			}
			for _, path := range paths {
				connectorQueuePath(&queue, seen, path, secrets)
			}
			base, _ := url.Parse(target)
			for _, link := range links {
				if reference {
					continue
				}
				parsed, e := url.Parse(link)
				if e == nil && strings.EqualFold(parsed.Host, base.Host) && parsed.Scheme == base.Scheme && parsed.RawQuery == "" {
					connectorQueuePath(&queue, seen, parsed.Path, secrets)
				}
			}
			source.Reason = "Page links and literal endpoint hints only; page instructions are never executed."
		}
		report.Sources = append(report.Sources, source)
	}
	report.Family, report.Version = summarizeSiteFamily(report.FamilyEvidence)
	report.HTTPRequests = &meter
	report.FinishedAt = now()
	report.Status = "NO_SUPPORTED_MAPPING"
	if len(drafts) > 0 {
		report.Status = "DRAFTS_READY"
	}
	if ctx.Err() != nil {
		report.Status = "INTERRUPTED"
		drafts = nil
	}
	// A late response after origin/key/group/config changes cannot create drafts.
	save, finish := context.WithTimeout(context.Background(), 5*time.Second)
	defer finish()
	tx, err := s.Store.DB.BeginTx(save, nil)
	if err != nil {
		return report, true, errors.New("discovery save unavailable; inspect saved request ID")
	}
	defer tx.Rollback()
	current, err := connectorContext(save, tx, bind, v.Selection)
	if err != nil || current != hash || !connectorMemberCurrent(save, tx, v.Selection) {
		report.Status = "SCOPE_CHANGED"
		drafts = nil
	}
	claims := map[string]string{}
	conflicting := map[string]bool{}
	for _, draft := range drafts {
		kind := draft.Config.Kind
		signature := connectorHash(draft.Fixture.Expected)
		if prior, ok := claims[kind]; ok && prior != signature {
			conflicting[kind] = true
		}
		claims[kind] = signature
	}
	for kind := range conflicting {
		report.Conflicts = append(report.Conflicts, kind)
	}
	sort.Strings(report.Conflicts)
	for _, draft := range drafts {
		id := newID()
		name := "auto-" + strings.ToLower(draft.Config.Kind) + "-" + contentHash([]byte(draft.Config.Path + "|" + draft.Config.Root))[:12]
		var version int
		if err = tx.QueryRowContext(save, "SELECT COALESCE(MAX(version),0)+1 FROM connector_versions WHERE provider_id=? AND name=?", v.Selection.ProviderID, name).Scan(&version); err != nil {
			return report, true, errors.New("draft persistence failed")
		}
		config, _ := json.Marshal(draft.Config)
		proof, _ := json.Marshal(draft.Proof)
		fixture, _ := json.Marshal(draft.Fixture)
		evidence, _ := json.Marshal([]string{v.ID + ":" + draft.Proof.SourceHash})
		reason := "Public structured source mapped by bounded schema discovery; review nominal claims and unknowns before activation."
		if conflicting[draft.Config.Kind] {
			reason = "Conflicting public sources detected; compare source evidence before choosing a version."
		}
		if _, err = tx.ExecContext(save, "INSERT INTO connector_versions(id,provider_id,name,version,config_json,evidence_json,reason,created_at) VALUES(?,?,?,?,?,?,?,?)", id, v.Selection.ProviderID, name, version, string(config), string(evidence), reason, report.ObservedAt); err != nil {
			return report, true, errors.New("draft persistence failed")
		}
		if _, err = tx.ExecContext(save, "INSERT INTO connector_generated_versions VALUES(?,?,?,?)", id, v.ID, string(proof), string(fixture)); err != nil {
			return report, true, errors.New("fixture persistence failed")
		}
		report.DraftIDs = append(report.DraftIDs, id)
		report.Sources[draft.Source].DraftIDs = append(report.Sources[draft.Source].DraftIDs, id)
	}
	if !oneOf(report.Status, "DRAFTS_READY", "NO_SUPPORTED_MAPPING") {
		for i := range report.Sources {
			report.Sources[i].MappingSampleID = ""
		}
	}
	final, _ := json.Marshal(report)
	if err = saveMappingSamples(save, tx, mappingSamples, report); err != nil {
		return report, true, errors.New("source sample persistence failed; inspect saved request ID")
	}
	if _, err = tx.ExecContext(save, "UPDATE connector_discoveries SET report_json=? WHERE id=?", string(final), v.ID); err != nil || tx.Commit() != nil {
		return report, true, errors.New("discovery commit failed; inspect saved request ID")
	}
	return report, true, nil
}
func containsKnownSecret(value any, secrets []string) bool {
	for _, secret := range secrets {
		if jsonContainsSecret(value, secret) {
			return true
		}
	}
	raw, _ := json.Marshal(value)
	return rawSecretPattern.Match(raw)
}
func connectorQueuePath(queue *[]string, seen map[string]bool, path string, secrets []string) {
	if !connectorDiscoveryPath(path) || seen[path] || len(seen) >= 32 || containsKnownSecret(path, secrets) {
		return
	}
	seen[path] = true
	*queue = append(*queue, path)
}
func connectorFamilyHint(root any) (string, string) {
	version := "UNKNOWN"
	raw := str(pointer(root, "/data/version"))
	if raw != "" && safeText(raw, 64) == raw && !rawSecretPattern.MatchString(raw) {
		version = raw
	}
	if pointer(root, "/data/quota_per_unit") != nil && pointer(root, "/data/checkin_enabled") != nil && version != "UNKNOWN" {
		return "NEW_API", version
	}
	if str(pointer(root, "/data/0/object")) == "model" {
		return "OPENAI_COMPATIBLE", "UNKNOWN"
	}
	return "UNKNOWN", version
}

// Deliberately conservative shapes: literal model IDs, literal scope, explicit
// pricing units, and fixed well-named metric units. Other fields stay unknown.
func inferConnectorMappings(root any, path string, v Selection) ([]connectorGeneratedDraft, []string) {
	out := []connectorGeneratedDraft{}
	reasons := []string{}
	containers := []struct {
		path string
		rows []any
	}{}
	var walk func(any, string, int)
	nodes := 0
	walk = func(value any, p string, depth int) {
		nodes++
		if nodes > 64 || depth > 3 || !connectorSafePointer(p) {
			return
		}
		if rows, ok := value.([]any); ok {
			if len(rows) > 0 && len(rows) <= 1000 {
				containers = append(containers, struct {
					path string
					rows []any
				}{p, rows})
			}
			return
		}
		if obj, ok := value.(map[string]any); ok {
			keys := []string{}
			for k := range obj {
				if fieldName.MatchString(k) {
					keys = append(keys, k)
				}
			}
			sort.Strings(keys)
			for _, key := range keys {
				walk(obj[key], p+"/"+key, depth+1)
			}
		}
	}
	walk(root, "", 0)
	if len(containers) == 0 {
		if _, ok := root.(map[string]any); ok {
			containers = append(containers, struct {
				path string
				rows []any
			}{"", []any{root}})
		}
	}
	for _, container := range containers {
		var selected any
		modelPath := ""
		for _, row := range container.rows {
			for _, field := range []string{"/model_id", "/model", "/id"} {
				if field == "/id" && str(pointer(row, "/object")) != "model" && !strings.Contains(strings.ToLower(container.path), "models") {
					continue
				}
				if str(pointer(row, field)) == v.ModelID {
					selected = row
					modelPath = field
					break
				}
			}
			if selected != nil {
				break
			}
		}
		if selected == nil {
			continue
		}
		for _, kind := range []string{"CATALOG", "PRICING", "QUOTA", "PERFORMANCE", "USAGE", "BALANCE", "NOTICE"} {
			cfg := ConnectorConfig{Kind: kind, Path: path, Role: "NONE", Scope: "MODEL_PROVIDER", Root: container.path, Fields: map[string]string{"model_id": modelPath}, Unit: "NONE", Parser: "declarative-get-v2", Freshness: 86400, FieldUnits: map[string]string{}, Checks: map[string]string{}, RootChecks: map[string]string{}}
			if kind == "CATALOG" {
				if modelPath != "/id" && !strings.Contains(strings.ToLower(path+container.path), "model") {
					continue
				}
				cfg.Scope = "CATALOG"
			} else {
				scope := str(pointer(selected, "/scope"))
				scopePath := "/scope"
				if scope == "" {
					scope = str(pointer(root, "/scope"))
					scopePath = ""
				}
				if !oneOf(scope, "MODEL_PROVIDER", "MODEL_KEY_GROUP") {
					reasons = appendUnique(reasons, "Explicit model scope missing; no inferred account/key claims.")
					continue
				}
				cfg.Scope = scope
				if scopePath != "" {
					cfg.Checks[scopePath] = scope
				} else {
					cfg.RootChecks["/scope"] = scope
				}
				if scope == "MODEL_KEY_GROUP" {
					if pointer(selected, "/group") == nil || str(pointer(selected, "/group")) != v.Group {
						continue
					}
					cfg.Fields["group"] = "/group"
				}
			}
			prefix := ""
			if pointer(selected, "/"+strings.ToLower(kind)) != nil {
				prefix = "/" + strings.ToLower(kind)
			}
			if kind == "PRICING" {
				unit := str(pointer(selected, prefix+"/unit"))
				if !oneOf(unit, "USD/1M tokens", "USD/1K tokens", "USD/token") {
					reasons = appendUnique(reasons, "Pricing unit missing or unsupported; no currency/unit guessed.")
					continue
				}
				cfg.Unit = unit
				cfg.Checks[prefix+"/unit"] = unit
				if currency := str(pointer(selected, prefix+"/currency")); currency != "" {
					if currency != "USD" {
						reasons = appendUnique(reasons, "Currency conflicts with pricing unit; no price draft accepted.")
						continue
					}
					cfg.Checks[prefix+"/currency"] = "USD"
				}
			}
			if kind == "BALANCE" || kind == "USAGE" {
				unit := str(pointer(selected, prefix+"/unit"))
				if oneOf(unit, "USD", "CREDITS", "TOKENS", "REQUESTS") {
					cfg.Unit = unit
					cfg.Checks[prefix+"/unit"] = unit
				}
			}
			for _, field := range strings.Fields(connectorFields[kind]) {
				if oneOf(field, "model_id", "group") {
					continue
				}
				fieldPath := prefix + "/" + field
				if field == "reset_at" {
					fieldPath = prefix + "/reset_at_ms"
				}
				if field == "success_rate" {
					if str(pointer(selected, prefix+"/success_rate_unit")) != "RATIO" {
						continue
					}
					cfg.Checks[prefix+"/success_rate_unit"] = "RATIO"
				}
				if kind == "CATALOG" && field == "name" {
					fieldPath = "/name"
				}
				value := pointer(selected, fieldPath)
				if kind == "PRICING" && field == "fixed" && value != nil {
					if str(pointer(selected, prefix+"/fixed_unit")) != "USD/request" {
						continue
					}
					cfg.Checks[prefix+"/fixed_unit"] = "USD/request"
				}
				if field == "notice" {
					if prefix == "/notice" {
						fieldPath = "/notice"
						value = pointer(selected, fieldPath)
					}
					if str(value) == "" || safeText(str(value), 4000) != str(value) || rawSecretPattern.MatchString(str(value)) {
						continue
					}
				} else if field == "name" {
					if str(value) == "" || safeText(str(value), 200) != str(value) {
						continue
					}
				} else {
					if decimal(value) == nil || decimal(value).Sign() < 0 || connectorFieldUnit(kind, field, cfg.Unit) == "" {
						continue
					}
					cfg.FieldUnits[field] = connectorFieldUnit(kind, field, cfg.Unit)
				}
				cfg.Fields[field] = fieldPath
			}
			if kind != "CATALOG" && len(cfg.Fields) == 1+map[bool]int{true: 1}[cfg.Scope == "MODEL_KEY_GROUP"] {
				continue
			}
			if !safeConnector(cfg) {
				continue
			}
			facts, err := extractConnector(cfg, v, container.rows)
			if err != nil {
				continue
			}
			row := connectorFixtureRow(selected, cfg)
			fixture := connectorFixture{ConfigHash: connectorHash(cfg), Selection: v, Rows: []any{row}, Expected: facts, RootValues: cfg.RootChecks}
			if replay, e := extractConnector(cfg, v, fixture.Rows); e != nil || connectorHash(replay) != connectorHash(facts) {
				continue
			}
			out = append(out, connectorGeneratedDraft{Config: cfg, Fixture: fixture})
		}
	}
	if len(out) == 0 && len(reasons) == 0 {
		reasons = append(reasons, "No supported exact model shape; retained as unverified source, manual mapping required.")
	}
	return out, reasons
}
func appendUnique(values []string, value string) []string {
	for _, v := range values {
		if v == value {
			return values
		}
	}
	return append(values, value)
}
