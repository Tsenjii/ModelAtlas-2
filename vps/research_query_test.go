package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

type queryResearchFixture struct {
	Server                 *Server
	Searches, Reads, Novel atomic.Int32
	Empty, BaseNovel       atomic.Bool
	ProposalEmpty          atomic.Bool
	Draft                  KnowledgeVersion
	Reflection             string
}

func newQueryResearchFixture(t *testing.T) *queryResearchFixture {
	t.Helper()
	f := &queryResearchFixture{}
	s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" {
			f.Reads.Add(1)
			if r.Header.Get("X-Subscription-Token") != "" || r.Header.Get("Authorization") != "" {
				t.Error("secret forwarded to public site")
			}
			w.Write([]byte("<p>Public model API documentation; quota unknown.</p>"))
			return
		}
		f.Searches.Add(1)
		var body map[string]any
		if r.Method != "POST" || json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("invalid actual Brave request")
		}
		if f.Empty.Load() || f.ProposalEmpty.Load() && strings.Contains(str(body["q"]), "RPD") {
			braveFixturePage(w, false)
			return
		}
		site := "https://common-query.example/docs"
		if strings.Contains(str(body["q"]), "RPD") || f.BaseNovel.Load() {
			site = fmt.Sprintf("https://new-query-%d.example/docs", f.Novel.Add(1))
		}
		braveFixturePage(w, false, site)
	})
	f.Server = s
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": searchQueries["en"]}, Budget: 4})
	s.runResearchPending(context.Background())
	if e := s.Store.DB.QueryRow("SELECT id FROM research_reflections WHERE run_id=? ORDER BY rowid DESC LIMIT 1", run).Scan(&f.Reflection); e != nil {
		t.Fatal(e)
	}
	all := queryVersionsForTest(t, s)
	for _, v := range all {
		if v.QueryGeneration != nil && v.Subject == "en" {
			f.Draft = v
			break
		}
	}
	if f.Draft.ID == "" {
		t.Fatal("completed public research did not generate a concrete query draft")
	}
	return f
}
func queryVersionsForTest(t *testing.T, s *Server) []KnowledgeVersion {
	t.Helper()
	r := req(t, s, "GET", "/api/v1/research/knowledge?kind=RESEARCH_STRATEGY", nil)
	requireCode(t, r, 200)
	var versions []KnowledgeVersion
	if e := json.Unmarshal(r.Body.Bytes(), &versions); e != nil {
		t.Fatal(e)
	}
	return versions
}
func queryPreviewForTest(t *testing.T, s *Server, v KnowledgeVersion, budget int) queryComparisonReview {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/research/query-comparisons/plan", queryComparisonRequest{VersionID: v.ID, Budget: budget})
	requireCode(t, r, 200)
	var review queryComparisonReview
	if e := json.Unmarshal(r.Body.Bytes(), &review); e != nil {
		t.Fatal(e)
	}
	return review
}
func queryPairForTest(t *testing.T, s *Server, v KnowledgeVersion, budget int) queryComparison {
	t.Helper()
	review := queryPreviewForTest(t, s, v, budget)
	r := req(t, s, "POST", "/api/v1/research/query-comparisons", queryComparisonRequest{ID: newID(), VersionID: v.ID, Budget: budget, Fingerprint: review.Fingerprint})
	requireCode(t, r, 201)
	var pair queryComparison
	if e := json.Unmarshal(r.Body.Bytes(), &pair); e != nil {
		t.Fatal(e)
	}
	return pair
}

func TestReflectionQueryGenerationLocalEvidenceIdempotenceAndRevision(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	v := f.Draft
	if f.Searches.Load() != 1 || f.Reads.Load() != 1 || v.QueryGeneration.Query == searchQueries["en"] || v.QueryGeneration.HTTP != 0 || v.QueryGeneration.Inference != 0 || v.QueryGeneration.Cost != nil || len(v.QueryGeneration.Sources) != 1 || v.QueryGeneration.Sources[0].Hash == "" {
		t.Fatalf("not an honest local draft: %+v", v.QueryGeneration)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, Confirm: true}), 409)
	run := v.RunID
	if e := s.reflectResearch(context.Background(), run); e != nil {
		t.Fatal(e)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_query_generations")
	input := map[string]string{"id": "manual-query", "reflection_id": f.Reflection, "language": "en"}
	a := req(t, s, "POST", "/api/v1/research/query-generation", input)
	requireCode(t, a, 201)
	b := req(t, s, "POST", "/api/v1/research/query-generation", input)
	requireCode(t, b, 201)
	var first, second KnowledgeVersion
	json.Unmarshal(a.Body.Bytes(), &first)
	json.Unmarshal(b.Body.Bytes(), &second)
	if first.ID != second.ID {
		t.Fatal("generation retry duplicated a version")
	}
	input["language"] = "ja"
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-generation", input), 422)
	edit := KnowledgeVersion{Kind: v.Kind, Subject: v.Subject, Data: v.Data, EvidenceIDs: v.EvidenceIDs, Reason: "Refine the public query before a new explicit comparison."}
	edit.Data["query"] = "recurring free model API RPD TPD documented daily limits"
	r := req(t, s, "POST", "/api/v1/research/knowledge", edit)
	requireCode(t, r, 201)
	var revision KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &revision)
	if revision.QueryGeneration == nil || revision.QueryGeneration.ParentID != v.ID || revision.QueryGeneration.ID == v.QueryGeneration.ID {
		t.Fatal("revision lost its local provenance")
	}
	edit.Data["search_country"] = "JP"
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge", edit), 422)
	edit.Data["search_country"] = "TW"
	edit.Data["query_generation_id"] = "foreign-proof"
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge", edit), 422)
	if f.Searches.Load() != 1 || f.Reads.Load() != 1 {
		t.Fatal("draft/review/revision sent network traffic")
	}
}

func TestQueryComparisonExplicitAtomicReviewRetryAndKeyRace(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	review := queryPreviewForTest(t, s, f.Draft, 4)
	if review.MaximumHTTP != 8 || review.ModelInference != 0 || review.Cost != nil || review.BaselineQuery != searchQueries["en"] {
		t.Fatal("incorrect public cost review", review)
	}
	input := queryComparisonRequest{ID: newID(), VersionID: f.Draft.ID, Budget: 4}
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons", input), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "rotated-query-fixture-key"}), 200)
	input.Fingerprint = review.Fingerprint
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons", input), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_query_comparisons")
	input.Fingerprint = queryPreviewForTest(t, s, f.Draft, 4).Fingerprint
	if _, e := s.Store.DB.Exec(`CREATE TRIGGER query_second_arm_failure BEFORE INSERT ON research_query_comparison_runs WHEN NEW.arm='PROPOSED' BEGIN SELECT RAISE(ABORT,'fixture second arm fault'); END;`); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons", input), 503)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_query_comparisons")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs")
	s.Store.DB.Exec("DROP TRIGGER query_second_arm_failure")
	a := req(t, s, "POST", "/api/v1/research/query-comparisons", input)
	requireCode(t, a, 201)
	b := req(t, s, "POST", "/api/v1/research/query-comparisons", input)
	requireCode(t, b, 200)
	var pair, replayed queryComparison
	json.Unmarshal(a.Body.Bytes(), &pair)
	json.Unmarshal(b.Body.Bytes(), &replayed)
	if len(pair.Arms) != 2 || pair.Arms[0].RunID != replayed.Arms[0].RunID || pair.Arms[1].RunID != replayed.Arms[1].RunID {
		t.Fatal("confirmation retry created new runs")
	}
	input.Budget = 5
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons", input), 409)
	if f.Searches.Load() != 1 || f.Reads.Load() != 1 {
		t.Fatal("preview/creation performed unreviewed network requests")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "second-rotated-query-fixture-key"}), 200)
	s.runResearchPending(context.Background())
	if f.Searches.Load() != 1 || f.Reads.Load() != 1 {
		t.Fatal("queued comparison borrowed changed key authority")
	}
	for _, a := range pair.Arms {
		assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", a.RunID)
	}
}

func TestQueryComparisonMeasuredTrialNextRunMonitorAndPersistentUndo(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	v := f.Draft
	first := qualityEvaluation(t, s, v)
	if first.Status != "INSUFFICIENT" || first.Policy != pairedQueryPolicy {
		t.Fatal("unmeasured query became eligible", first)
	}
	for i := 0; i < 3; i++ {
		queryPairForTest(t, s, v, 4)
		s.runResearchPending(context.Background())
	}
	e := qualityEvaluation(t, s, v)
	if e.Status != "READY_FOR_TRIAL" || e.ComparableRuns != 3 || e.Paired == nil || e.Baseline.Utility != 0 || e.Proposed.Utility != 1.5 || e.Paired.BaselineHTTP != 6 || e.Paired.ProposedHTTP != 6 || e.Target.HTTP != 3 || e.Paired.Cost != nil {
		t.Fatalf("actual paired measurements incorrect: %+v", e)
	}
	if f.Searches.Load() != 7 || f.Reads.Load() != 7 {
		t.Fatal("comparison did not execute exact real requests", f.Searches.Load(), f.Reads.Load())
	}
	r := req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true})
	requireCode(t, r, 200)
	var action map[string]string
	json.Unmarshal(r.Body.Bytes(), &action)
	f.Empty.Store(true)
	for i := 0; i < 3; i++ {
		run := researchRunID(t, s, ResearchOptions{Search: true, Budget: 8})
		s.runResearchPending(context.Background())
		s.runResearchPending(context.Background())
		var raw string
		s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? AND dedupe='search:en'", run).Scan(&raw)
		var in taskInput
		json.Unmarshal([]byte(raw), &in)
		if in.Query != str(v.Data["query"]) || in.Strategy == nil || in.Strategy.VersionID != v.ID {
			t.Fatal("actual next research did not use confirmed query", raw)
		}
	}
	s = reopenScheduleStore(t, s)
	found := false
	for _, active := range queryVersionsForTest(t, s) {
		if active.ID == v.ID {
			found = true
			if active.Monitor == nil || active.Monitor.Status != "DEGRADED" || active.Monitor.Observed.Runs != 3 || active.Monitor.Baseline.Utility != 1.5 || active.ActivationActionID == "" {
				raw, _ := json.Marshal(active)
				t.Fatalf("query trial monitor not durable: %s", raw)
			}
			requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": active.ActivationActionID, "confirm": true}), 200)
		}
	}
	if !found {
		t.Fatal("active query missing")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
}

func TestQueryComparisonFixedBudgetCancelPauseAndRestart(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	pair := queryPairForTest(t, s, f.Draft, 1)
	for _, a := range pair.Arms {
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": a.RunID, "action": "PAUSE"}), 200)
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": a.RunID, "action": "RESUME", "additional_budget": 1}), 409)
	}
	s = reopenScheduleStore(t, s)
	for _, a := range pair.Arms {
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": a.RunID, "action": "RESUME"}), 200)
	}
	s.runResearchPending(context.Background())
	if f.Searches.Load() != 3 || f.Reads.Load() != 1 {
		t.Fatal("fixed budget or resume replayed work", f.Searches.Load(), f.Reads.Load())
	}
	for _, a := range pair.Arms {
		assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", a.RunID)
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": a.RunID, "action": "RESUME", "additional_budget": 1}), 409)
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": a.RunID, "action": "CANCEL"}), 200)
	}
	if e := qualityEvaluation(t, s, f.Draft); e.Status != "INSUFFICIENT" || e.ComparableRuns != 0 {
		t.Fatal("partial/cancelled pair counted as success")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_query_comparison_runs")
}

func TestQueryComparisonTamperedScopeOptionsAndProbeCannotBorrowApproval(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, change := range []string{"country", "options", "probe"} {
		t.Run(change, func(t *testing.T) {
			f := newQueryResearchFixture(t)
			s := f.Server
			pair := queryPairForTest(t, s, f.Draft, 4)
			a := pair.Arms[0]
			var task string
			s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=?", a.RunID).Scan(&task)
			if change == "country" {
				s.Store.DB.Exec(`UPDATE research_tasks SET payload_json=json_set(payload_json,'$.search_country','JP') WHERE id=?`, task)
			}
			if change == "options" {
				s.Store.DB.Exec(`UPDATE research_run_options SET config_json=json_set(config_json,'$.budget',20) WHERE run_id=?`, a.RunID)
			}
			ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, a.RunID), researchTaskBudgetKey{}, task)
			if e := s.chargeResearchNetwork(ctx, change == "probe", braveOrigin); e == nil {
				t.Fatal("changed task borrowed a public comparison")
			}
			assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", a.RunID)
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_task_usage WHERE task_id=?", task)
			if f.Searches.Load() != 1 || f.Reads.Load() != 1 {
				t.Fatal("local refusal sent traffic")
			}
		})
	}
}

func TestQueryComparisonNoGainRegressionStaleAndForeignHistory(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	v := f.Draft
	f.Empty.Store(true)
	for i := 0; i < 3; i++ {
		queryPairForTest(t, s, v, 4)
		s.runResearchPending(context.Background())
	}
	if e := qualityEvaluation(t, s, v); e.Status != "NO_OBSERVED_GAIN" || e.ComparableRuns != 3 {
		t.Fatal("equal empty results became improvement", e.Status)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: qualityEvaluation(t, s, v).ID, Confirm: true}), 409)
	// A copied/synchronized version and its JSON metadata have no local generation
	// receipt, so neither paired execution nor manual activation can borrow it.
	s.Store.DB.Exec("UPDATE knowledge_versions SET status='IMPORTED' WHERE id=?", v.ID)
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons/plan", queryComparisonRequest{VersionID: v.ID, Budget: 4}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, Confirm: true}), 422)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
}

func TestQueryComparisonRealRegressionAndStaleDatasetCannotActivate(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	v := f.Draft
	for i := 0; i < 3; i++ {
		queryPairForTest(t, s, v, 4)
		s.runResearchPending(context.Background())
	}
	evaluated := qualityEvaluation(t, s, v)
	if evaluated.Status != "READY_FOR_TRIAL" {
		t.Fatal(evaluated.Status)
	}
	queryPairForTest(t, s, v, 4)
	s.runResearchPending(context.Background())
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: evaluated.ID, Confirm: true}), 409)
	// Start a distinct immutable revision so the next three pairs are the whole
	// new dataset. Baseline finds real new hosts; proposal returns no results.
	data := map[string]any{}
	for k, x := range v.Data {
		data[k] = x
	}
	data["query"] = "model API documented RPD TPD recurring limits"
	r := req(t, s, "POST", "/api/v1/research/knowledge", KnowledgeVersion{Kind: v.Kind, Subject: v.Subject, Data: data, EvidenceIDs: v.EvidenceIDs, Reason: "Compare an explicit alternative on actual new responses."})
	requireCode(t, r, 201)
	var revised KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &revised)
	f.BaseNovel.Store(true)
	f.ProposalEmpty.Store(true)
	for i := 0; i < 3; i++ {
		queryPairForTest(t, s, revised, 4)
		s.runResearchPending(context.Background())
	}
	e := qualityEvaluation(t, s, revised)
	if e.Status != "REGRESSION" || e.Baseline.Utility != 1.5 || e.Proposed.Utility != 0 || e.Paired.BaselineHTTP != 6 || e.Paired.ProposedHTTP != 3 {
		t.Fatalf("actual quality regression escaped: %+v", e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: revised.ID, EvaluationID: e.ID, Confirm: true}), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
}

func TestQueryGenerationAndComparisonStorageFaultsStayAtomicAndLocal(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	if _, e := s.Store.DB.Exec(`CREATE TRIGGER query_generation_fault BEFORE INSERT ON research_query_generations BEGIN SELECT RAISE(ABORT,'fixture generation save fault'); END;`); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-generation", map[string]string{"id": "faulted-generation", "reflection_id": f.Reflection, "language": "en"}), 503)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_query_generations")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM knowledge_versions WHERE kind='RESEARCH_STRATEGY'")
	s.Store.DB.Exec("DROP TRIGGER query_generation_fault")
	pair := queryPairForTest(t, s, f.Draft, 4)
	if _, e := s.Store.DB.Exec("ALTER TABLE research_query_generations RENAME TO query_generation_unavailable"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/query-comparisons/plan", queryComparisonRequest{VersionID: f.Draft.ID, Budget: 4}), 503)
	requireCode(t, req(t, s, "GET", "/api/v1/research/query-comparisons?version_id="+f.Draft.ID, nil), 503)
	s.runResearchPending(context.Background())
	if f.Searches.Load() != 1 || f.Reads.Load() != 1 {
		t.Fatal("storage failure triggered replacement paid work")
	}
	for _, a := range pair.Arms {
		assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", a.RunID)
	}
	s.Store.DB.Exec("ALTER TABLE query_generation_unavailable RENAME TO research_query_generations")
	r := req(t, s, "GET", "/api/v1/research/query-comparisons?version_id="+f.Draft.ID, nil)
	requireCode(t, r, 200)
	s.Store.DB.Exec("UPDATE research_query_comparisons SET country='invalid' WHERE id=?", pair.ID)
	requireCode(t, req(t, s, "GET", "/api/v1/research/query-comparisons?version_id="+f.Draft.ID, nil), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/evaluate", map[string]string{"id": f.Draft.ID}), 503)
}

func TestQueryComparisonLocalAuthoritySyncPortableAndSchema31Snapshot(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	queryPairForTest(t, s, f.Draft, 4)
	s.runResearchPending(context.Background())
	peer := testServer(t)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	workspacePairForTest(t, s, peer, host, false)
	workspaceRunForTest(t, peer)
	tables := []string{"research_query_comparison_runs", "research_query_comparisons", "research_query_generations"}
	for _, table := range tables {
		if workspaceAllowed(table) {
			t.Fatal("local query authority entered workspace sync", table)
		}
		assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM "+table)
	}
	var imported []KnowledgeVersion
	r := req(t, peer, "GET", "/api/v1/research/knowledge?kind=RESEARCH_STRATEGY", nil)
	requireCode(t, r, 200)
	json.Unmarshal(r.Body.Bytes(), &imported)
	found := false
	for _, v := range imported {
		if v.ID == f.Draft.ID {
			found = true
			if !v.QueryGenerationUnavailable || v.QueryGeneration != nil {
				t.Fatal("synced query borrowed local generation proof")
			}
			requireCode(t, req(t, peer, "POST", "/api/v1/research/query-comparisons/plan", queryComparisonRequest{VersionID: v.ID, Budget: 4}), 422)
		}
	}
	if !found {
		t.Fatal("sync lost query history")
	}
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	var document BackupDocument
	json.Unmarshal(backup.Body.Bytes(), &document)
	portable := testServer(t)
	requireCode(t, req(t, portable, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	for _, table := range tables {
		assertSQLInt(t, portable, 0, "SELECT COUNT(*) FROM "+table)
		if _, e := s.Store.DB.Exec("DROP TABLE " + table); e != nil {
			t.Fatal(e)
		}
	}
	s.Store.DB.Exec("PRAGMA user_version=31")
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	for _, table := range tables {
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+table)
	}
	snapshots, e := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-31-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(snapshots) != 1 || verifySnapshot(snapshots[0]) != nil {
		t.Fatal("schema31 upgrade lost consistent snapshot", e)
	}
	before := f.Searches.Load()
	s.workerTick(context.Background())
	peer.workerTick(context.Background())
	if f.Searches.Load() != before {
		t.Fatal("history/import/upgrade replayed query work")
	}
}
