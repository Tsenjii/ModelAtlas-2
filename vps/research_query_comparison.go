package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
)

var errQueryComparisonChanged = errors.New("query comparison approval changed; review a new comparison")

type queryComparisonRequest struct {
	ID          string `json:"id"`
	VersionID   string `json:"version_id"`
	Budget      int    `json:"budget_per_arm"`
	Fingerprint string `json:"review_fingerprint"`
}
type queryComparisonReview struct {
	Fingerprint        string  `json:"fingerprint"`
	VersionID          string  `json:"version_id"`
	BaselineID         string  `json:"baseline_id"`
	BaselineQuery      string  `json:"baseline_query"`
	ProposedQuery      string  `json:"proposed_query"`
	Language           string  `json:"language"`
	Country            string  `json:"country"`
	Pages              int     `json:"pages_per_arm"`
	Budget             int     `json:"budget_per_arm"`
	MaximumHTTP        int     `json:"maximum_http_requests"`
	MaximumSearchPages int     `json:"maximum_search_pages"`
	ModelInference     int     `json:"model_inference_requests"`
	Cost               *string `json:"total_monetary_cost"`
	Note               string  `json:"note"`
}
type queryComparison struct {
	ID              string               `json:"id"`
	RequestHash     string               `json:"-"`
	VersionID       string               `json:"version_id"`
	VersionHash     string               `json:"-"`
	BaselineID      string               `json:"baseline_id"`
	BaselineQuery   string               `json:"baseline_query"`
	ProposedQuery   string               `json:"proposed_query"`
	Language        string               `json:"language"`
	Country         string               `json:"country"`
	Pages           int                  `json:"pages_per_arm"`
	Budget          int                  `json:"budget_per_arm"`
	CredentialStamp string               `json:"-"`
	At              int64                `json:"at"`
	Changed         bool                 `json:"approval_changed"`
	Arms            []queryComparisonArm `json:"arms"`
	Cost            *string              `json:"total_monetary_cost"`
}
type queryComparisonArm struct {
	Arm       string           `json:"arm"`
	RunID     string           `json:"run_id"`
	Status    string           `json:"status"`
	HTTP      int              `json:"http_requests"`
	Inference int              `json:"model_inference_requests"`
	Budget    int              `json:"budget"`
	Quality   *researchQuality `json:"quality,omitempty"`
	Cost      *string          `json:"total_monetary_cost"`
}

const comparisonColumns = `id,request_hash,version_id,version_hash,baseline_id,baseline_query,proposed_query,language,country,pages,budget,credential_stamp,created_at`

func scanQueryComparison(row interface{ Scan(...any) error }) (c queryComparison, e error) {
	e = row.Scan(&c.ID, &c.RequestHash, &c.VersionID, &c.VersionHash, &c.BaselineID, &c.BaselineQuery, &c.ProposedQuery, &c.Language, &c.Country, &c.Pages, &c.Budget, &c.CredentialStamp, &c.At)
	if e == nil && (!validID(c.ID) || !validID(c.VersionID) || len(c.VersionHash) != 64 || !researchLanguage(c.Language) || !validBraveCountry(c.Country) || c.Pages < 1 || c.Pages > 10 || c.Budget < 1 || c.Budget > 20 || len(c.CredentialStamp) != 64 || safeText(c.BaselineQuery, 300) != c.BaselineQuery || c.BaselineQuery == "" || safeText(c.ProposedQuery, 300) != c.ProposedQuery || c.ProposedQuery == "" || c.BaselineQuery == c.ProposedQuery) {
		e = errors.New("invalid comparison receipt")
	}
	return
}
func currentQueryCredential(ctx context.Context, q researchQueryer) (string, error) {
	var encrypted string
	e := q.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name='BRAVE_SEARCH'").Scan(&encrypted)
	if e != nil {
		if e == sql.ErrNoRows {
			return "", queryInput("Brave key unavailable")
		}
		return "", e
	}
	if encrypted == "" {
		return "", queryInput("Brave key unavailable")
	}
	return contentHash([]byte(encrypted)), nil
}
func comparisonIntent(v queryComparisonRequest) string {
	return snapshotHash(map[string]any{"version_id": v.VersionID, "budget_per_arm": v.Budget})
}
func comparisonPlanHash(c queryComparison) string {
	return snapshotHash(map[string]any{"policy": pairedQueryPolicy, "version_id": c.VersionID, "version_hash": c.VersionHash, "baseline_id": c.BaselineID, "baseline_query": c.BaselineQuery, "proposed_query": c.ProposedQuery, "language": c.Language, "country": c.Country, "pages": c.Pages, "credential_revision": c.CredentialStamp})
}
func comparisonFingerprint(c queryComparison) string {
	return snapshotHash(map[string]any{"plan": comparisonPlanHash(c), "budget_per_arm": c.Budget})
}

func planQueryComparison(ctx context.Context, q researchQueryer, request queryComparisonRequest) (queryComparisonReview, queryComparison, error) {
	var review queryComparisonReview
	var c queryComparison
	if !validID(request.VersionID) || request.Budget < 1 || request.Budget > 20 {
		return review, c, queryInput("bounded query comparison required")
	}
	v, e := readKnowledgeVersion(ctx, q, request.VersionID)
	if e != nil {
		return review, c, e
	}
	proof, e := readQueryGeneration(ctx, q, v)
	if e != nil {
		return review, c, e
	}
	if proof == nil || v.Status == "IMPORTED" {
		return review, c, queryInput("local generated query required")
	}
	in, e := queryContext(v)
	if e != nil {
		return review, c, e
	}
	baseID, baseQuery, e := resolveQueryBaseline(ctx, q, in)
	if e != nil {
		return review, c, e
	}
	if baseQuery == in.Query {
		return review, c, queryInput("proposed query equals current baseline")
	}
	stamp, e := currentQueryCredential(ctx, q)
	if e != nil {
		return review, c, e
	}
	c = queryComparison{VersionID: v.ID, VersionHash: snapshotHash(v.Data), BaselineID: baseID, BaselineQuery: baseQuery, ProposedQuery: in.Query, Language: in.Language, Country: in.SearchCountry, Pages: in.SearchPages, Budget: request.Budget, CredentialStamp: stamp}
	review = queryComparisonReview{Fingerprint: comparisonFingerprint(c), VersionID: c.VersionID, BaselineID: baseID, BaselineQuery: baseQuery, ProposedQuery: in.Query, Language: in.Language, Country: in.SearchCountry, Pages: in.SearchPages, Budget: c.Budget, MaximumHTTP: 2 * c.Budget, MaximumSearchPages: 2 * c.Pages, Note: "明確確認才建立兩個公開研究輪次。各臂含搜尋／公開頁面檢查，共用既有每日／來源上限；不呼叫模型。搜尋與公開來源計費Unknown，不以零模型推論推定免費。完成至少3組相同範圍對照才評估；失敗／取消／不完整不作成功樣本。"}
	return review, c, nil
}
func (s *Server) previewQueryComparison(w http.ResponseWriter, r *http.Request) {
	var input queryComparisonRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "comparison preview unavailable", 503)
		return
	}
	defer tx.Rollback()
	review, _, e := planQueryComparison(r.Context(), tx, input)
	if e != nil {
		code := 503
		if errors.Is(e, errQueryInput) || errors.Is(e, sql.ErrNoRows) {
			code = 422
		}
		http.Error(w, "local query and configured search key required", code)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "comparison preview unavailable", 503)
		return
	}
	jsonOK(w, 200, review)
}

func comparisonOptions(c queryComparison, arm string) (ResearchOptions, []researchPlannedTask) {
	query := c.BaselineQuery
	if arm == "PROPOSED" {
		query = c.ProposedQuery
	}
	options := ResearchOptions{Search: true, Queries: map[string]string{c.Language: query}, SearchCountry: c.Country, SearchPages: c.Pages, Budget: c.Budget}
	task := researchPlannedTask{kind: "SEARCH", dedupe: "search:" + c.Language, input: taskInput{Language: c.Language, Query: query, SearchCountry: c.Country, SearchPages: c.Pages}}
	bindResearchKnowledge(&task, map[string]KnowledgeVersion{}, false)
	return options, []researchPlannedTask{task}
}
func (s *Server) startQueryComparison(w http.ResponseWriter, r *http.Request) {
	var input queryComparisonRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	if !validID(input.ID) || !validID(input.VersionID) || input.Budget < 1 || input.Budget > 20 {
		http.Error(w, "bounded comparison and request id required", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "comparison unavailable", 503)
		return
	}
	defer tx.Rollback()
	old, e := scanQueryComparison(tx.QueryRowContext(r.Context(), "SELECT "+comparisonColumns+" FROM research_query_comparisons WHERE id=?", input.ID))
	if e == nil {
		if old.RequestHash != comparisonIntent(input) {
			http.Error(w, "comparison id belongs to another intent", 409)
			return
		}
		if e = fillQueryComparison(r.Context(), tx, &old); e != nil {
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		if e = tx.Commit(); e != nil {
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		jsonOK(w, 200, old)
		return
	}
	if e != sql.ErrNoRows {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	review, c, e := planQueryComparison(r.Context(), tx, input)
	if e != nil {
		code := 503
		if errors.Is(e, errQueryInput) || errors.Is(e, sql.ErrNoRows) {
			code = 422
		}
		http.Error(w, "comparison plan unavailable", code)
		return
	}
	if input.Fingerprint == "" || input.Fingerprint != review.Fingerprint {
		http.Error(w, "query comparison changed; review again", 409)
		return
	}
	c.ID = input.ID
	c.RequestHash = comparisonIntent(input)
	c.At = now()
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO research_query_comparisons VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", c.ID, c.RequestHash, c.VersionID, c.VersionHash, c.BaselineID, c.BaselineQuery, c.ProposedQuery, c.Language, c.Country, c.Pages, c.Budget, c.CredentialStamp, c.At); e != nil {
		http.Error(w, "comparison persistence unavailable", 503)
		return
	}
	for _, arm := range []string{"BASELINE", "PROPOSED"} {
		options, tasks := comparisonOptions(c, arm)
		run := newID()
		if e = insertResearchRun(r.Context(), tx, run, options, tasks); e != nil {
			http.Error(w, "comparison persistence unavailable", 503)
			return
		}
		if _, e = tx.ExecContext(r.Context(), "INSERT INTO research_query_comparison_runs VALUES(?,?,?,?)", c.ID, run, arm, connectorHash(options)); e != nil {
			http.Error(w, "comparison persistence unavailable", 503)
			return
		}
	}
	if e = fillQueryComparison(r.Context(), tx, &c); e != nil {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "comparison persistence unavailable", 503)
		return
	}
	s.wakeWorker()
	jsonOK(w, 201, c)
}

func validateQueryComparison(ctx context.Context, q researchQueryer, c queryComparison) error {
	v, e := readKnowledgeVersion(ctx, q, c.VersionID)
	if e != nil {
		return e
	}
	proof, e := readQueryGeneration(ctx, q, v)
	if e != nil {
		return e
	}
	if proof == nil || v.Status == "IMPORTED" || snapshotHash(v.Data) != c.VersionHash {
		return errQueryComparisonChanged
	}
	in, e := queryContext(v)
	if e != nil || in.Language != c.Language || in.Query != c.ProposedQuery || in.SearchCountry != c.Country || in.SearchPages != c.Pages {
		return errQueryComparisonChanged
	}
	baseID, query, e := resolveQueryBaseline(ctx, q, in)
	if e != nil {
		return e
	}
	stamp, e := currentQueryCredential(ctx, q)
	if e != nil {
		return e
	}
	if baseID != c.BaselineID || query != c.BaselineQuery || stamp != c.CredentialStamp {
		return errQueryComparisonChanged
	}
	return nil
}
func queryComparisonMembership(ctx context.Context, q researchQueryer, run string) (*queryComparison, string, string, error) {
	var id, arm, hash string
	e := q.QueryRowContext(ctx, "SELECT comparison_id,arm,options_hash FROM research_query_comparison_runs WHERE run_id=?", run).Scan(&id, &arm, &hash)
	if e == sql.ErrNoRows {
		return nil, "", "", nil
	}
	if e != nil {
		return nil, "", "", e
	}
	c, e := scanQueryComparison(q.QueryRowContext(ctx, "SELECT "+comparisonColumns+" FROM research_query_comparisons WHERE id=?", id))
	return &c, arm, hash, e
}

// Recheck the exact approval in the same transaction as HTTP admission. Neither
// a changed query, key, plan nor a model task may borrow the public comparison.
func validateQueryComparisonAdmission(ctx context.Context, tx *sql.Tx, probe bool) error {
	run, _ := ctx.Value(researchBudgetKey{}).(string)
	if run == "" {
		return nil
	}
	c, arm, hash, e := queryComparisonMembership(ctx, tx, run)
	if e != nil || c == nil {
		return e
	}
	if probe {
		return errQueryComparisonChanged
	}
	if e = validateQueryComparison(ctx, tx, *c); e != nil {
		return e
	}
	expected, _ := comparisonOptions(*c, arm)
	var raw string
	var budget, probes int
	if e = tx.QueryRowContext(ctx, "SELECT config_json,budget,probe_budget FROM research_run_options WHERE run_id=?", run).Scan(&raw, &budget, &probes); e != nil {
		return e
	}
	var options ResearchOptions
	if json.Unmarshal([]byte(raw), &options) != nil || hash != connectorHash(expected) || connectorHash(options) != hash || budget != c.Budget || probes != 0 {
		return errQueryComparisonChanged
	}
	task, _ := ctx.Value(researchTaskBudgetKey{}).(string)
	var kind, payload string
	if e = tx.QueryRowContext(ctx, "SELECT kind,payload_json FROM research_tasks WHERE id=? AND run_id=?", task, run).Scan(&kind, &payload); e != nil {
		return e
	}
	var in taskInput
	if json.Unmarshal([]byte(payload), &in) != nil || in.Selection != nil || !oneOf(kind, "SEARCH", "INSPECT", "INSPECT_LINK") {
		return errQueryComparisonChanged
	}
	if kind == "SEARCH" {
		pages, country := braveSearchOptions(in)
		if in.Language != c.Language || in.Query != expected.Queries[c.Language] || pages != c.Pages || country != c.Country {
			return errQueryComparisonChanged
		}
	}
	return nil
}

func queryComparisonControl(ctx context.Context, tx *sql.Tx, run, action string, additionalHTTP, additionalProbes int, retryFailed bool) error {
	c, _, _, e := queryComparisonMembership(ctx, tx, run)
	if e != nil || c == nil {
		return e
	}
	if action == "RESUME" {
		if additionalHTTP != 0 || additionalProbes != 0 || retryFailed {
			return errors.New("comparison has a fixed reviewed budget; create a new pair")
		}
		return validateQueryComparison(ctx, tx, *c)
	}
	return nil
}

func fillQueryComparison(ctx context.Context, q researchQueryer, c *queryComparison) error {
	c.Arms = []queryComparisonArm{}
	rows, e := q.QueryContext(ctx, `SELECT m.arm,r.id,r.status,o.used,o.probes_used,o.budget FROM research_query_comparison_runs m JOIN research_runs r ON r.id=m.run_id JOIN research_run_options o ON o.run_id=r.id WHERE m.comparison_id=? ORDER BY m.arm`, c.ID)
	if e != nil {
		return e
	}
	for rows.Next() {
		var a queryComparisonArm
		if e = rows.Scan(&a.Arm, &a.RunID, &a.Status, &a.HTTP, &a.Inference, &a.Budget); e != nil {
			rows.Close()
			return e
		}
		c.Arms = append(c.Arms, a)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	if len(c.Arms) != 2 {
		return errors.New("incomplete comparison receipt")
	}
	if e = validateQueryComparison(ctx, q, *c); e != nil {
		if errors.Is(e, errQueryComparisonChanged) {
			c.Changed = true
		} else {
			return e
		}
	}
	for i := range c.Arms {
		var raw string
		e = q.QueryRowContext(ctx, "SELECT observation_json FROM research_quality_observations WHERE run_id=? AND metric_key=? ORDER BY rowid DESC LIMIT 1", c.Arms[i].RunID, knowledgeMetricKey("RESEARCH_STRATEGY", c.Language)).Scan(&raw)
		if e == sql.ErrNoRows {
			continue
		}
		if e != nil {
			return e
		}
		var quality researchQuality
		if json.Unmarshal([]byte(raw), &quality) != nil {
			return errors.New("invalid comparison quality")
		}
		c.Arms[i].Quality = &quality
	}
	return nil
}

func (s *Server) listQueryComparisons(w http.ResponseWriter, r *http.Request) {
	version, id := r.URL.Query().Get("version_id"), r.URL.Query().Get("id")
	if !validID(version) || id != "" && !validID(id) {
		http.Error(w, "query version required", 422)
		return
	}
	before := int64(0)
	if v := r.URL.Query().Get("before"); v != "" {
		var e error
		before, e = strconv.ParseInt(v, 10, 64)
		if e != nil || before < 1 {
			http.Error(w, "invalid cursor", 422)
			return
		}
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	defer tx.Rollback()
	rows, e := tx.QueryContext(r.Context(), "SELECT rowid,"+comparisonColumns+" FROM research_query_comparisons WHERE version_id=? AND (?='' OR id=?) AND (?=0 OR rowid<?) ORDER BY rowid DESC LIMIT 51", version, id, id, before, before)
	if e != nil {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	// Fetch ids first so no cursor remains open while the single SQLite connection
	// loads related runs. Every row is then decoded and validated, never skipped.
	ids := []string{}
	cursors := []int64{}
	for rows.Next() {
		var rowID int64
		var c queryComparison
		e = rows.Scan(&rowID, &c.ID, &c.RequestHash, &c.VersionID, &c.VersionHash, &c.BaselineID, &c.BaselineQuery, &c.ProposedQuery, &c.Language, &c.Country, &c.Pages, &c.Budget, &c.CredentialStamp, &c.At)
		if e != nil {
			rows.Close()
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		ids = append(ids, c.ID)
		cursors = append(cursors, rowID)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	next := ""
	if len(ids) > 50 {
		next = strconv.FormatInt(cursors[49], 10)
		ids = ids[:50]
	}
	out := []queryComparison{}
	for _, id := range ids {
		c, err := scanQueryComparison(tx.QueryRowContext(r.Context(), "SELECT "+comparisonColumns+" FROM research_query_comparisons WHERE id=?", id))
		if err != nil {
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		if err = fillQueryComparison(r.Context(), tx, &c); err != nil {
			http.Error(w, "comparison history unavailable", 503)
			return
		}
		out = append(out, c)
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "comparison history unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"comparisons": out, "next_before": next})
}
