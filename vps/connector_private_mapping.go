package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
	"time"
)

// Only the complete response's hash and size enter this receipt, never its body
// or authentication fields. Existing authenticated reads retain their client.
type connectorSourceReceiptKey struct{}
type connectorSourceReceipt struct {
	Hash, ContentType string
	Bytes int
}

func privateMappingScope(role string) string {
	if role == "ACCOUNT_PAT" {
		return "ACCOUNT"
	}
	return "MODEL_KEY_GROUP"
}

func privateMappingGroupFields(root any, path, modelPath string, v Selection) []mappingField {
	if !validPointer(path) || !validPointer(modelPath) || modelPath == "" {
		return nil
	}
	value := pointer(root, path)
	rows, ok := value.([]any)
	if !ok {
		rows = []any{value}
	}
	if value == nil || len(rows) > 1000 {
		return nil
	}
	fields := map[string]mappingField{}
	for _, row := range rows {
		if str(pointer(row, modelPath)) != v.ModelID {
			continue
		}
		for _, field := range mappingFields(row, true) {
			if field.Value == v.Group && oneOf(field.Type, "TEXT", "NUMBER") {
				fields[field.Path] = field
			}
		}
	}
	keys := []string{}
	for key := range fields {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	out := []mappingField{}
	for _, key := range keys {
		if len(out) == 128 {
			break
		}
		out = append(out, fields[key])
	}
	return out
}

func mappingReviewRows(root any, path, modelPath, groupPath string, report connectorDiscoveryReport) ([]any, any, error) {
	if report.CredentialRole == "" {
		return mappingRows(root, path, modelPath, report.Selection.ModelID)
	}
	if !validPointer(path) {
		return nil, nil, errMappingReview
	}
	value := pointer(root, path)
	if privateMappingScope(report.CredentialRole) == "ACCOUNT" {
		if path != "" || modelPath != "" || groupPath != "" {
			return nil, nil, errMappingReview
		}
		if _, ok := value.(map[string]any); !ok {
			return nil, nil, errMappingReview
		}
		return []any{value}, value, nil
	}
	if !validPointer(modelPath) || modelPath == "" {
		return nil, nil, errMappingReview
	}
	rows, ok := value.([]any)
	if !ok {
		rows = []any{value}
	}
	if value == nil || len(rows) > 1000 {
		return nil, nil, errMappingReview
	}
	var matched any
	for _, row := range rows {
		if str(pointer(row, modelPath)) != report.Selection.ModelID {
			continue
		}
		if groupPath != "" {
			if !validPointer(groupPath) || pointer(row, groupPath) == nil || str(pointer(row, groupPath)) != report.Selection.Group {
				continue
			}
		}
		if matched != nil {
			return nil, nil, errMappingReview
		}
		matched = row
	}
	if matched == nil {
		return nil, nil, errMappingReview
	}
	return rows, matched, nil
}

func connectorPrivatePath(path, role string) bool {
	return path != "/" && (connectorDiscoveryPath(path) || role == "ACCOUNT_PAT" && path == "/api/user/self")
}

func connectorSampleBinding(report connectorDiscoveryReport) (ConnectorConfig, error) {
	if report.CredentialRole == "" && report.PATID == "" && !report.PrivateConfirmed {
		return ConnectorConfig{Role: "NONE"}, nil
	}
	if !report.PrivateConfirmed || !oneOf(report.CredentialRole, "INFERENCE", "ACCOUNT_PAT") ||
		report.CredentialRole == "INFERENCE" && report.PATID != "" ||
		report.CredentialRole == "ACCOUNT_PAT" && !validID(report.PATID) {
		return ConnectorConfig{}, errMappingReview
	}
	return ConnectorConfig{Role: report.CredentialRole, PATID: report.PATID, Origin: report.BaseURL}, nil
}

func connectorSampleContext(ctx context.Context, q queryRows, report connectorDiscoveryReport) (string, error) {
	bind, e := connectorSampleBinding(report)
	if e != nil {
		return "", e
	}
	h, e := connectorContext(ctx, q, bind, report.Selection)
	if e != nil {
		return "", e
	}
	if bind.Role == "NONE" {
		return h, nil
	}
	// Preserve all existing public/manual receipt hashes. New private samples
	// additionally bind their acquisition role and explicit PAT reference.
	return connectorHash(map[string]any{"context": h, "role": bind.Role, "pat_id": bind.PATID, "policy": "private-field-mapping-v1"}), nil
}

func preparePrivateMappingSample(root any, report connectorDiscoveryReport, source connectorDiscoverySource, hash string) *connectorMappingSample {
	root = stripMappingSecrets(root, "", 0)
	raw, e := json.Marshal(root)
	if e != nil || len(raw) > 256<<10 {
		return nil
	}
	if report.CredentialRole == "ACCOUNT_PAT" {
		if _, ok := root.(map[string]any); !ok {
			return nil
		}
	} else if len(mappingContainers(root, "")) == 0 {
		return nil
	}
	return &connectorMappingSample{ID: newID(), DiscoveryID: report.ID, ContextHash: hash, SourceURL: source.URL, SourceHash: source.Hash, Path: report.SourcePath, Root: root, At: report.ObservedAt}
}

func (s *Server) generatePrivateConnectorSample(parent context.Context, v connectorDiscoveryRequest, limit int) (connectorDiscoveryReport, bool, error) {
	var empty connectorDiscoveryReport
	if !validID(v.ID) || len(v.ID) > 120 || !validScope(v.Selection) || limit < 1 || limit > 6 || !v.ConfirmPrivate ||
		!oneOf(v.CredentialRole, "INFERENCE", "ACCOUNT_PAT") || len(v.ReferenceURLs) != 0 || !connectorPrivatePath(v.SourcePath, v.CredentialRole) ||
		v.CredentialRole == "INFERENCE" && v.PATID != "" || v.CredentialRole == "ACCOUNT_PAT" && !validID(v.PATID) {
		return empty, false, errors.New("explicit private read confirmation, exact selected credential/PAT and one read-only JSON path required")
	}
	secrets, e := s.knownSecrets(parent)
	if e != nil {
		return empty, false, errors.New("vault unavailable")
	}
	if containsKnownSecret(v, secrets) {
		return empty, false, errors.New("raw secrets are not source definitions")
	}
	for _, secret := range secrets {
		if jsonContainsSecret(v, secret) {
			return empty, false, errors.New("raw secrets are not source definitions")
		}
	}
	requestHash := connectorHash(v)
	var prior, raw string
	e = s.Store.DB.QueryRowContext(parent, "SELECT request_hash,report_json FROM connector_discoveries WHERE id=?", v.ID).Scan(&prior, &raw)
	if e == nil {
		if prior != requestHash || json.Unmarshal([]byte(raw), &empty) != nil {
			return empty, false, errors.New("request ID already belongs to another source read")
		}
		return empty, false, nil
	}
	if e != sql.ErrNoRows {
		return empty, false, errors.New("source history unavailable")
	}
	if !s.validateSelection(parent, v.Selection) {
		return empty, false, errors.New("select an active My Models scope before private source access")
	}
	p, e := boundProvider(parent, s.Store.DB, v.Selection.ProviderID, v.Selection.AccountID, v.Selection.CredentialID)
	if e != nil || !publicURL(p.BaseURL) || containsKnownSecret(p.BaseURL, secrets) {
		return empty, false, errors.New("selected HTTPS provider origin required")
	}
	report := connectorDiscoveryReport{
		ID: v.ID, Selection: v.Selection, SourcePath: v.SourcePath, BaseURL: p.BaseURL,
		Status: "RUNNING", Family: "UNKNOWN", Version: "UNKNOWN", ObservedAt: now(), RequestLimit: 1,
		CredentialRole: v.CredentialRole, PATID: v.PATID, PrivateConfirmed: true,
		Sources: []connectorDiscoverySource{}, DraftIDs: []string{}, Conflicts: []string{},
		Unknown: []string{"reviewed field meanings, scope and units", "recurring eligibility", "effective charges", "model identity"},
		Limitations: []string{
			"One explicitly confirmed authenticated GET to the selected origin/path only; no link following, code, login or inference.",
			"Private metadata is not public/provider-wide evidence. Scope and units require explicit field review before testing or activation.",
			"No automatic proposal, activation, membership changes or credential fallback.",
		},
	}
	hash, e := connectorSampleContext(parent, s.Store.DB, report)
	if e != nil {
		return empty, false, errors.New("selected private source context unavailable")
	}
	bind, _ := connectorSampleBinding(report)
	authProvider, cfg, key, e := s.connectorAuth(parent, bind, v.Selection)
	if e != nil || cfg.AuthMode == "NONE" || key == "" && len(authProvider.AuthFields) == 0 {
		return empty, false, errors.New("selected private credential unavailable; anonymous mode remains separate")
	}
	current, e := connectorSampleContext(parent, s.Store.DB, report)
	if e != nil || current != hash {
		return empty, false, errors.New("private source context changed before read")
	}
	initial, _ := json.Marshal(report)
	if _, e = s.Store.DB.ExecContext(parent, "INSERT INTO connector_discoveries VALUES(?,?,?,?,?)", v.ID, requestHash, v.Selection.ProviderID, string(initial), report.ObservedAt); e != nil {
		return empty, false, errors.New("source reservation failed; read history before retrying")
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	meter := 0
	receipt := new(connectorSourceReceipt)
	ctx = context.WithValue(ctx, connectorDiscoveryMeterKey{}, &meter)
	ctx = context.WithValue(ctx, connectorSourceReceiptKey{}, receipt)
	root, status, fetchErr := s.getStructured(ctx, authProvider, cfg, originPath(authProvider, v.SourcePath), key, 1<<20)
	source := connectorDiscoverySource{URL: originPath(p, v.SourcePath), HTTPStatus: status, DraftIDs: []string{}, Hash: receipt.Hash}
	report.Attempts, report.HTTPRequests, report.FinishedAt, report.Status = 1, &meter, now(), "NO_SUPPORTED_MAPPING"
	var sample *connectorMappingSample
	if fetchErr != nil {
		source.Status = "UNAVAILABLE"
		source.Reason = "Authenticated JSON read failed; no source claims accepted."
		if status == 401 || status == 403 {
			source.Status = "AUTH_REQUIRED"
			source.Reason = "Selected authority was refused; no other Key or PAT was tried."
		}
	} else {
		root = connectorStripRawSecrets(cleanSecretEcho(root, secrets, 0), 0)
		source.Status = "INSPECTED"
		source.Reason = "Private JSON retained for explicit scoped field review; no automatic mapping or public family claims."
		if !containsKnownSecret(root, secrets) {
			sample = preparePrivateMappingSample(root, report, source, hash)
		}
		if sample != nil {
			source.MappingSampleID = sample.ID
		} else {
			source.Status = "SCHEMA_UNSUPPORTED"
			source.Reason = "A complete bounded model-row or account-object sample is required; no truncated fixture saved."
		}
	}
	if ctx.Err() != nil {
		report.Status, sample, source.MappingSampleID = "INTERRUPTED", nil, ""
	}
	// Do not advertise a sample that failed its commit, even to internal callers.
	fail := func(err error) (connectorDiscoveryReport, bool, error) {
		for i := range report.Sources {
			report.Sources[i].MappingSampleID = ""
		}
		return report, true, err
	}
	save, finish := context.WithTimeout(context.Background(), 5*time.Second)
	defer finish()
	tx, e := s.Store.DB.BeginTx(save, nil)
	if e != nil {
		return fail(errors.New("source result unavailable; read the saved request before retrying"))
	}
	defer tx.Rollback()
	current, e = connectorSampleContext(save, tx, report)
	if e != nil || current != hash || !connectorMemberCurrent(save, tx, v.Selection) {
		report.Status, sample, source.MappingSampleID = "SCOPE_CHANGED", nil, ""
	}
	report.Sources = []connectorDiscoverySource{source}
	if sample != nil {
		if e = saveMappingSamples(save, tx, []connectorMappingSample{*sample}, report); e != nil {
			return fail(e)
		}
	}
	final, _ := json.Marshal(report)
	if _, e = tx.ExecContext(save, "UPDATE connector_discoveries SET report_json=? WHERE id=? AND request_hash=?", string(final), v.ID, requestHash); e != nil {
		return fail(e)
	}
	if e = tx.Commit(); e != nil {
		return fail(e)
	}
	return report, true, nil
}

// Private generated readers retain the authority that acquired and reviewed the
// sample. A provider-wide active slot never grants another Key/account access.
func validatePrivateMappingUse(ctx context.Context, q queryRows, id string, c ConnectorConfig, v Selection) error {
	if c.Parser != "declarative-get-v2" || c.Role == "NONE" {
		return nil
	}
	hash, e := connectorContext(ctx, q, c, v)
	if e != nil {
		return e
	}
	return validateConnectorFixtureQuery(ctx, q, id, c, v, hash)
}
