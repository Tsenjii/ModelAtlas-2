package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const extractionPolicy = "exact-public-read-batch-v1"
const extractionSchema = `
CREATE TABLE IF NOT EXISTS extraction_versions(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,request_hash TEXT NOT NULL,definition_json TEXT NOT NULL CHECK(json_valid(definition_json)),created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS extraction_comparisons(id TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES extraction_versions(id),status TEXT NOT NULL,report_json TEXT NOT NULL CHECK(json_valid(report_json)),created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS extraction_active(scope_key TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES extraction_versions(id));
CREATE TABLE IF NOT EXISTS extraction_bindings(version_id TEXT PRIMARY KEY REFERENCES extraction_versions(id),definition_hash TEXT NOT NULL,comparison_id TEXT NOT NULL REFERENCES extraction_comparisons(id),comparison_hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS extraction_observations(id TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES extraction_versions(id),status TEXT NOT NULL,report_json TEXT NOT NULL CHECK(json_valid(report_json)),created_at INTEGER NOT NULL);`

var errExtractionReview = errors.New("local extraction scope, rules or review changed")

func extractionProblem(w http.ResponseWriter, e error, message string) {
	if siteRecipeStorageFailure(e) {
		http.Error(w, "extraction local storage unavailable; read saved state before retrying", 503)
		return
	}
	http.Error(w, message, 409)
}

type extractionMeterKey struct{}
type extractionRule struct {
	ID     string          `json:"id"`
	Name   string          `json:"name"`
	Config ConnectorConfig `json:"config"`
}
type extractionDefinition struct {
	Policy       string           `json:"policy"`
	Selection    Selection        `json:"selection"`
	ContextHash  string           `json:"context_hash"`
	Rules        []extractionRule `json:"rules"`
	Expected     string           `json:"expected_active_id"`
	BaselineHTTP int              `json:"baseline_public_gets"`
	ProposedHTTP int              `json:"proposed_public_gets"`
}
type extractionVersion struct {
	ID           string                `json:"id"`
	Definition   extractionDefinition  `json:"definition"`
	At           int64                 `json:"at"`
	Active       bool                  `json:"active"`
	Action       string                `json:"activation_action_id,omitempty"`
	Comparison   *extractionComparison `json:"comparison,omitempty"`
	Retest       bool                  `json:"retest_required"`
	Observations []map[string]any      `json:"observations"`
}
type extractionRound struct {
	BaselineHTTP int    `json:"baseline_http"`
	ProposedHTTP int    `json:"proposed_http"`
	BaselineMS   int64  `json:"baseline_ms"`
	ProposedMS   int64  `json:"proposed_ms"`
	Equal        bool   `json:"facts_equal"`
	FactsHash    string `json:"facts_sha256"`
}
type extractionComparison struct {
	ID             string            `json:"id"`
	VersionID      string            `json:"version_id"`
	Status         string            `json:"status"`
	DefinitionHash string            `json:"definition_hash"`
	HTTP           *int              `json:"http_requests"`
	CompletedHTTP  int               `json:"completed_round_http_requests"`
	FailureHead    string            `json:"prior_failure_id"`
	Rounds         []extractionRound `json:"rounds"`
	Inference      int               `json:"inference_requests"`
	Cost           *string           `json:"total_monetary_cost"`
	Reason         string            `json:"reason"`
	At             int64             `json:"at"`
}

func extractionScope(v Selection) string { return connectorHash(v) }

func extractionFailureHead(ctx context.Context, q queryRows, id string) (string, error) {
	var head string
	e := q.QueryRowContext(ctx, "SELECT id FROM extraction_observations WHERE version_id=? AND status='FAILED' ORDER BY rowid DESC LIMIT 1", id).Scan(&head)
	if e == sql.ErrNoRows {
		return "", nil
	}
	return head, e
}
func extractionGroup(c ConnectorConfig, v Selection) string {
	return c.Origin + "|" + strings.ReplaceAll(c.Path, "{model}", url.PathEscape(v.ModelID))
}
func extractionEligible(c ConnectorConfig) bool {
	return c.Kind != "CATALOG" && c.Role == "NONE" && c.PATID == "" && c.Parser == "declarative-get-v2" && c.Origin != "" && safeConnector(c)
}

func extractionCurrent(ctx context.Context, q queryRows, d extractionDefinition) error {
	if d.Policy != extractionPolicy || len(d.Rules) < 2 || len(d.Rules) > 6 || !connectorMemberCurrent(ctx, q, d.Selection) {
		return errExtractionReview
	}
	seen := map[string]bool{}
	groups := map[string]bool{}
	provider, e := boundProvider(ctx, q, d.Selection.ProviderID, d.Selection.AccountID, d.Selection.CredentialID)
	if e != nil {
		return e
	}
	for _, ref := range d.Rules {
		if seen[ref.ID] || !extractionEligible(ref.Config) || ref.Config.Origin != provider.BaseURL {
			return errExtractionReview
		}
		seen[ref.ID] = true
		var raw, approved, active string
		if e := q.QueryRowContext(ctx, `SELECT v.config_json,b.config_json,a.version_id FROM connector_versions v JOIN connector_execution_bindings b ON b.version_id=v.id JOIN connector_active a ON a.provider_id=v.provider_id AND a.name=v.name WHERE v.id=? AND v.provider_id=? AND v.name=?`, ref.ID, d.Selection.ProviderID, ref.Name).Scan(&raw, &approved, &active); e != nil {
			return e
		}
		var live, saved ConnectorConfig
		if active != ref.ID || json.Unmarshal([]byte(raw), &live) != nil || json.Unmarshal([]byte(approved), &saved) != nil || connectorHash(live) != connectorHash(ref.Config) || connectorHash(saved) != connectorHash(ref.Config) {
			return errExtractionReview
		}
		hash, e := connectorContext(ctx, q, ref.Config, d.Selection)
		if e != nil {
			return e
		}
		if hash != d.ContextHash {
			return errExtractionReview
		}
		if e = validateSiteRecipeConnectorUse(ctx, q, ref.ID, d.Selection); e != nil {
			return e
		}
		groups[extractionGroup(ref.Config, d.Selection)] = true
	}
	if d.BaselineHTTP != len(d.Rules) || d.ProposedHTTP != len(groups) || d.ProposedHTTP >= d.BaselineHTTP {
		return errExtractionReview
	}
	return nil
}
func readExtraction(ctx context.Context, q queryRows, id string) (extractionVersion, error) {
	var v extractionVersion
	var raw, scope string
	e := q.QueryRowContext(ctx, "SELECT id,scope_key,definition_json,created_at FROM extraction_versions WHERE id=?", id).Scan(&v.ID, &scope, &raw, &v.At)
	if e != nil {
		return v, e
	}
	if json.Unmarshal([]byte(raw), &v.Definition) != nil || scope != extractionScope(v.Definition.Selection) || v.Definition.Policy != extractionPolicy {
		return v, errExtractionReview
	}
	v.Observations = []map[string]any{}
	return v, nil
}
func (s *Server) proposeExtraction(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID        string    `json:"id"`
		Selection Selection `json:"selection"`
		IDs       []string  `json:"connector_ids"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validID(in.ID) || len(in.IDs) < 2 || len(in.IDs) > 6 {
		http.Error(w, "select two to six exact public rules", 422)
		return
	}
	sort.Strings(in.IDs)
	requestHash := connectorHash(in)
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "extraction storage unavailable", 503)
		return
	}
	defer tx.Rollback()
	var prior string
	e = tx.QueryRowContext(r.Context(), "SELECT request_hash FROM extraction_versions WHERE id=?", in.ID).Scan(&prior)
	if e == nil {
		if prior != requestHash {
			http.Error(w, "proposal ID already belongs to another intent", 409)
			return
		}
		v, e := readExtraction(r.Context(), tx, in.ID)
		if e != nil {
			http.Error(w, "saved proposal unavailable", 503)
			return
		}
		jsonOK(w, 200, v)
		return
	}
	if e != sql.ErrNoRows {
		http.Error(w, "extraction storage unavailable", 503)
		return
	}
	d := extractionDefinition{Policy: extractionPolicy, Selection: in.Selection, Expected: activeVersion(r.Context(), tx, "EXTRACTION", extractionScope(in.Selection), "")}
	for _, id := range in.IDs {
		var ref extractionRule
		var raw string
		if e = tx.QueryRowContext(r.Context(), "SELECT id,name,config_json FROM connector_versions WHERE id=? AND provider_id=?", id, in.Selection.ProviderID).Scan(&ref.ID, &ref.Name, &raw); e != nil || json.Unmarshal([]byte(raw), &ref.Config) != nil {
			http.Error(w, "selected public rule unavailable", 409)
			return
		}
		d.Rules = append(d.Rules, ref)
	}
	groups := map[string]bool{}
	for _, ref := range d.Rules {
		groups[extractionGroup(ref.Config, in.Selection)] = true
	}
	d.BaselineHTTP = len(d.Rules)
	d.ProposedHTTP = len(groups)
	d.ContextHash, e = connectorContext(r.Context(), tx, ConnectorConfig{Role: "NONE"}, in.Selection)
	if e == nil {
		e = extractionCurrent(r.Context(), tx, d)
	}
	if e != nil {
		extractionProblem(w, e, "current locally approved public rules with a repeated exact GET required")
		return
	}
	raw, _ := json.Marshal(d)
	at := now()
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO extraction_versions VALUES(?,?,?,?,?)", in.ID, extractionScope(in.Selection), requestHash, string(raw), at); e != nil || tx.Commit() != nil {
		http.Error(w, "proposal persistence failed; read saved proposals", 503)
		return
	}
	jsonOK(w, 201, extractionVersion{ID: in.ID, Definition: d, At: at, Observations: []map[string]any{}})
}

func (s *Server) listExtraction(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id FROM extraction_versions ORDER BY rowid DESC LIMIT 100")
	if e != nil {
		http.Error(w, "extraction history unavailable", 503)
		return
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if e = rows.Scan(&id); e != nil {
			break
		}
		ids = append(ids, id)
	}
	if e == nil {
		e = rows.Err()
	}
	rows.Close()
	if e != nil {
		http.Error(w, "extraction history unavailable", 503)
		return
	}
	out := []extractionVersion{}
	for _, id := range ids {
		v, e := readExtraction(r.Context(), s.Store.DB, id)
		if e != nil {
			http.Error(w, "extraction history unavailable", 503)
			return
		}
		v.Active = activeVersion(r.Context(), s.Store.DB, "EXTRACTION", extractionScope(v.Definition.Selection), "") == id
		if e = s.Store.DB.QueryRowContext(r.Context(), `SELECT d.action_id FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id WHERE d.family='EXTRACTION' AND d.after_id=? AND a.state='CONFIRMED' ORDER BY a.rowid DESC LIMIT 1`, id).Scan(&v.Action); e != nil && e != sql.ErrNoRows {
			http.Error(w, "extraction action history unavailable", 503)
			return
		}
		var raw string
		if e = s.Store.DB.QueryRowContext(r.Context(), "SELECT report_json FROM extraction_comparisons WHERE version_id=? ORDER BY rowid DESC LIMIT 1", id).Scan(&raw); e == nil {
			var c extractionComparison
			if json.Unmarshal([]byte(raw), &c) != nil {
				http.Error(w, "comparison history unavailable", 503)
				return
			}
			v.Comparison = &c
			if c.Status == "READY_FOR_TRIAL" {
				head, err := extractionFailureHead(r.Context(), s.Store.DB, id)
				if err != nil {
					http.Error(w, "extraction monitoring unavailable", 503)
					return
				}
				v.Retest = head != c.FailureHead
			}
		} else if e != sql.ErrNoRows {
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		history, e := s.Store.DB.QueryContext(r.Context(), "SELECT report_json FROM extraction_observations WHERE version_id=? ORDER BY rowid DESC LIMIT 30", id)
		if e != nil {
			http.Error(w, "extraction monitoring unavailable", 503)
			return
		}
		for history.Next() {
			var text string
			if e = history.Scan(&text); e != nil {
				break
			}
			var m map[string]any
			if e = json.Unmarshal([]byte(text), &m); e != nil {
				break
			}
			v.Observations = append(v.Observations, m)
		}
		if e == nil {
			e = history.Err()
		}
		history.Close()
		if e != nil {
			http.Error(w, "extraction monitoring unavailable", 503)
			return
		}
		out = append(out, v)
	}
	jsonOK(w, 200, out)
}

type extractionRead struct {
	root any
	err  error
}

func (s *Server) runExtraction(ctx context.Context, d extractionDefinition, shared bool) (map[string]any, error) {
	facts := map[string]any{}
	cache := map[string]extractionRead{}
	for _, ref := range d.Rules {
		if e := extractionCurrent(ctx, s.Store.DB, d); e != nil {
			return nil, e
		}
		key := extractionGroup(ref.Config, d.Selection)
		entry, found := cache[key]
		if !shared || !found {
			entry.root, entry.err = s.connectorRoot(ctx, ref.Config, d.Selection)
			if shared {
				cache[key] = entry
			}
		}
		if entry.err != nil {
			return nil, entry.err
		}
		rows, e := connectorRowsFromRoot(ref.Config, entry.root)
		if e != nil {
			return nil, e
		}
		f, e := extractConnector(ref.Config, d.Selection, rows)
		if e != nil {
			return nil, e
		}
		facts[ref.ID] = f
	}
	if e := extractionCurrent(ctx, s.Store.DB, d); e != nil {
		return nil, e
	}
	return facts, nil
}
func (s *Server) compareExtraction(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID      string `json:"id"`
		Version string `json:"version_id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validID(in.ID) || !in.Confirm {
		http.Error(w, "confirm three bounded public comparison rounds", 422)
		return
	}
	var saved, owner string
	e := s.Store.DB.QueryRowContext(r.Context(), "SELECT version_id,report_json FROM extraction_comparisons WHERE id=?", in.ID).Scan(&owner, &saved)
	if e == nil {
		if owner != in.Version {
			http.Error(w, "comparison intent changed", 409)
			return
		}
		var out extractionComparison
		if json.Unmarshal([]byte(saved), &out) != nil {
			http.Error(w, "comparison unavailable", 503)
			return
		}
		jsonOK(w, 200, out)
		return
	}
	if e != sql.ErrNoRows {
		http.Error(w, "comparison storage unavailable", 503)
		return
	}
	v, e := readExtraction(r.Context(), s.Store.DB, in.Version)
	if e == nil && (now() < v.At || now()-v.At > int64(24*time.Hour/time.Millisecond)) {
		e = errExtractionReview
	}
	if e == nil {
		e = extractionCurrent(r.Context(), s.Store.DB, v.Definition)
	}
	if e != nil {
		extractionProblem(w, e, "current local extraction proposal required")
		return
	}
	if !s.beginFlight("extraction:" + v.ID) {
		http.Error(w, "comparison already running", 409)
		return
	}
	defer s.endFlight("extraction:" + v.ID)
	out := extractionComparison{ID: in.ID, VersionID: v.ID, Status: "RUNNING", DefinitionHash: connectorHash(v.Definition), Rounds: []extractionRound{}, At: now()}
	out.FailureHead, e = extractionFailureHead(r.Context(), s.Store.DB, v.ID)
	if e != nil {
		http.Error(w, "extraction monitoring unavailable", 503)
		return
	}
	raw, _ := json.Marshal(out)
	if _, e = s.Store.DB.ExecContext(r.Context(), "INSERT INTO extraction_comparisons VALUES(?,?,?,?,?)", in.ID, v.ID, out.Status, string(raw), out.At); e != nil {
		http.Error(w, "comparison intent could not be saved", 503)
		return
	}
	ctx, stop := context.WithTimeout(r.Context(), 60*time.Second)
	defer stop()
	out.Status = "READY_FOR_TRIAL"
	totalHTTP := 0
	for i := 0; i < 3; i++ {
		before, after := 0, 0
		start := time.Now()
		a, err := s.runExtraction(context.WithValue(ctx, extractionMeterKey{}, &before), v.Definition, false)
		baselineMS := time.Since(start).Milliseconds()
		var b map[string]any
		proposedMS := int64(0)
		if err == nil {
			start = time.Now()
			b, err = s.runExtraction(context.WithValue(ctx, extractionMeterKey{}, &after), v.Definition, true)
			proposedMS = time.Since(start).Milliseconds()
		}
		totalHTTP += before + after
		equal := err == nil && connectorHash(a) == connectorHash(b)
		round := extractionRound{BaselineHTTP: before, ProposedHTTP: after, BaselineMS: baselineMS, ProposedMS: proposedMS, Equal: equal}
		if equal {
			round.FactsHash = connectorHash(a)
		}
		out.Rounds = append(out.Rounds, round)
		out.CompletedHTTP = totalHTTP
		checkpoint := out
		checkpoint.Status = "RUNNING"
		checkpoint.HTTP = nil
		checkpointRaw, _ := json.Marshal(checkpoint)
		save, finish := context.WithTimeout(context.Background(), 5*time.Second)
		_, persistErr := s.Store.DB.ExecContext(save, "UPDATE extraction_comparisons SET report_json=? WHERE id=? AND status='RUNNING'", string(checkpointRaw), out.ID)
		finish()
		if persistErr != nil {
			http.Error(w, "comparison checkpoint unavailable; read saved state before a new intent", 503)
			return
		}
		if err != nil {
			out.Status = "FAILED"
			out.Reason = "Public comparison did not complete; retained requests are not retried."
			break
		}
		if !equal || before != v.Definition.BaselineHTTP || after != v.Definition.ProposedHTTP || after >= before {
			out.Status = "NO_IMPROVEMENT"
			out.Reason = "Actual facts differed or exact HTTP reduction was not demonstrated."
			break
		}
	}
	if ctx.Err() != nil {
		out.Status = "CANCELLED"
		out.Reason = "Comparison stopped; admitted requests and completed rounds remain recorded."
	}
	out.HTTP = &totalHTTP
	save, finish := context.WithTimeout(context.Background(), 5*time.Second)
	defer finish()
	if extractionCurrent(save, s.Store.DB, v.Definition) != nil {
		out.Status = "SCOPE_CHANGED"
		out.Reason = "Scope or rule changed during comparison; no execution authority created."
	}
	if head, err := extractionFailureHead(save, s.Store.DB, v.ID); err != nil || head != out.FailureHead {
		out.Status = "SCOPE_CHANGED"
		out.Reason = "Local trial monitoring changed during comparison; measure again before activation."
	}
	raw, _ = json.Marshal(out)
	if _, e = s.Store.DB.ExecContext(save, "UPDATE extraction_comparisons SET status=?,report_json=? WHERE id=?", out.Status, string(raw), out.ID); e != nil {
		http.Error(w, "comparison result persistence uncertain; read saved state", 503)
		return
	}
	jsonOK(w, 200, out)
}

type extractionReview struct {
	ID          string               `json:"id"`
	Expected    string               `json:"expected_active_id"`
	Fingerprint string               `json:"fingerprint"`
	Definition  extractionDefinition `json:"definition"`
	Comparison  extractionComparison `json:"comparison"`
	Note        string               `json:"note"`
}

func validExtractionComparison(c extractionComparison, d extractionDefinition, id string) bool {
	maximum := 3 * (d.BaselineHTTP + d.ProposedHTTP)
	if c.VersionID != id || !validID(c.ID) || c.DefinitionHash != connectorHash(d) || c.Status != "READY_FOR_TRIAL" || len(c.Rounds) != 3 || c.HTTP == nil || *c.HTTP != maximum || c.CompletedHTTP != maximum || c.Inference != 0 || c.Cost != nil || c.At > now() {
		return false
	}
	for _, round := range c.Rounds {
		if !round.Equal || round.BaselineHTTP != d.BaselineHTTP || round.ProposedHTTP != d.ProposedHTTP || len(round.FactsHash) != 64 || round.BaselineMS < 0 || round.ProposedMS < 0 {
			return false
		}
	}
	return true
}

func extractionPlan(ctx context.Context, q queryRows, id string) (extractionReview, error) {
	out := extractionReview{ID: id, Note: "僅試用這組公開metadata的同一批次合併讀取，不跨批次快取、不變更欄位／單位／認證或模型推論；每次保留原scope檢查，連續三次非取消讀取失敗回復原逐項模式。HTTP以實際紀錄為準，完整貨幣成本Unknown。"}
	v, e := readExtraction(ctx, q, id)
	if e != nil {
		return out, e
	}
	if now() < v.At || now()-v.At > int64(24*time.Hour/time.Millisecond) {
		return out, errExtractionReview
	}
	if e = extractionCurrent(ctx, q, v.Definition); e != nil {
		return out, e
	}
	var raw, status string
	if e = q.QueryRowContext(ctx, "SELECT status,report_json FROM extraction_comparisons WHERE version_id=? ORDER BY rowid DESC LIMIT 1", id).Scan(&status, &raw); e != nil {
		return out, e
	}
	if json.Unmarshal([]byte(raw), &out.Comparison) != nil || status != out.Comparison.Status || !validExtractionComparison(out.Comparison, v.Definition, id) || out.Comparison.At < v.At {
		return out, errExtractionReview
	}
	head, e := extractionFailureHead(ctx, q, id)
	if e != nil {
		return out, e
	}
	if head != out.Comparison.FailureHead {
		return out, errExtractionReview
	}
	for _, round := range out.Comparison.Rounds {
		if !round.Equal || round.BaselineHTTP != v.Definition.BaselineHTTP || round.ProposedHTTP != v.Definition.ProposedHTTP || len(round.FactsHash) != 64 {
			return out, errExtractionReview
		}
	}
	out.Definition = v.Definition
	out.Expected = activeVersion(ctx, q, "EXTRACTION", extractionScope(v.Definition.Selection), "")
	if out.Expected != v.Definition.Expected || out.Expected == id {
		return out, errExtractionReview
	}
	out.Fingerprint = connectorHash(map[string]any{"definition": v.Definition, "comparison": out.Comparison, "expected": out.Expected})
	return out, nil
}
func (s *Server) previewExtraction(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "extraction review unavailable", 503)
		return
	}
	defer tx.Rollback()
	out, e := extractionPlan(r.Context(), tx, in.ID)
	if e != nil {
		extractionProblem(w, e, "current exact comparison and local review required")
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "extraction review unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) activateExtraction(w http.ResponseWriter, r *http.Request) {
	var in struct {
		versionActivation
		Fingerprint string `json:"review_fingerprint"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !in.Confirm || in.Fingerprint == "" {
		http.Error(w, "measured extraction review required", 409)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "extraction activation unavailable", 503)
		return
	}
	defer tx.Rollback()
	plan, e := extractionPlan(r.Context(), tx, in.ID)
	if e != nil {
		extractionProblem(w, e, "current extraction review required")
		return
	}
	if plan.Fingerprint != in.Fingerprint || plan.Expected != in.ExpectedID {
		http.Error(w, "extraction scope or review changed; preview again", 409)
		return
	}
	action, e := applyVersion(r.Context(), tx, "EXTRACTION", extractionScope(plan.Definition.Selection), "", in.ID, in.ExpectedID)
	if e != nil {
		http.Error(w, "extraction active version changed", 409)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO extraction_bindings VALUES(?,?,?,?) ON CONFLICT(version_id) DO UPDATE SET definition_hash=excluded.definition_hash,comparison_id=excluded.comparison_id,comparison_hash=excluded.comparison_hash", in.ID, connectorHash(plan.Definition), plan.Comparison.ID, connectorHash(plan.Comparison)); e != nil || tx.Commit() != nil {
		http.Error(w, "extraction activation persistence uncertain; read saved state", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"id": in.ID, "active": true, "action_id": action})
}
