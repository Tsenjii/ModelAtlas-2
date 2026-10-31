package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestResearchCostPublicComparisonDoesNotTurnNoInferenceIntoFree(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	pair := queryPairForTest(t, s, f.Draft, 4)
	if pair.CostComparison == nil || pair.CostComparison.Status != "INCOMPLETE" || pair.CostComparison.USD != nil {
		t.Fatal("queued experiments claimed measured savings", pair)
	}
	s.runResearchPending(context.Background())
	w := req(t, s, "GET", "/api/v1/research/query-comparisons?version_id="+f.Draft.ID+"&id="+pair.ID, nil)
	requireCode(t, w, 200)
	var response struct {
		Comparisons []struct {
			Arms []struct {
				Cost map[string]any `json:"cost_evidence"`
			} `json:"arms"`
		} `json:"comparisons"`
	}
	if e := json.Unmarshal(w.Body.Bytes(), &response); e != nil {
		t.Fatal(e)
	}
	if len(response.Comparisons) != 1 || len(response.Comparisons[0].Arms) != 2 {
		t.Fatal("missing exact pair", w.Body.String())
	}
	for _, arm := range response.Comparisons[0].Arms {
		c := arm.Cost
		if c == nil || c["reported_inference_tokens"] != "0" || c["reported_inference_usd"] != "0" || c["total_usd"] != nil || c["unpriced_public_http_requests"] != float64(2) {
			t.Fatal("public HTTP cost coverage is missing or no inference was called free", w.Body.String())
		}
	}
	for i := 0; i < 2; i++ {
		queryPairForTest(t, s, f.Draft, 4)
		s.runResearchPending(context.Background())
	}
	e := qualityEvaluation(t, s, f.Draft)
	if e.Status != "READY_FOR_TRIAL" || e.Cost == nil || e.Cost.Basis != "PAIRED_FULL_RUNS" || e.Cost.Baseline.HTTP != 6 || e.Cost.Proposed.HTTP != 6 || e.Baseline.Cost.HTTP != 3 || e.Cost.USD != nil || e.Cost.Status != "UNKNOWN" || e.Cost.Tokens == nil || *e.Cost.Tokens != "0" {
		t.Fatalf("comparison borrowed the search-only denominator or invented savings: %+v", e)
	}
	s = reopenScheduleStore(t, s)
	saved := qualityEvaluation(t, s, f.Draft)
	if saved.DatasetHash != e.DatasetHash || connectorHash(saved.Cost) != connectorHash(e.Cost) {
		t.Fatal("cost comparison did not survive restart")
	}
	if f.Searches.Load() != 7 || f.Reads.Load() != 7 {
		t.Fatal("cost read sent traffic", f.Searches.Load(), f.Reads.Load())
	}
}

func TestResearchCostTLSReceiptsStayExactScopedAndSeparateFromAllocations(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	scope := seed(t, s, "https://openrouter.ai/v1")
	_ = sessionForTest(t, s, scope)
	var calls atomic.Int32
	var zero atomic.Bool
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong exact credential")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		if zero.Load() {
			fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"Observed\"}}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0,\"cost\":0}}\n\ndata: [DONE]\n\n")
			return
		}
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"Observed\"}}],\"usage\":{\"prompt_tokens\":9007199254740992,\"completion_tokens\":1,\"total_tokens\":9007199254740993,\"cost\":1e-4096}}\n\ndata: [DONE]\n\n")
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 5, ProbeBudget: 2})
	var task string
	if e := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task); e != nil {
		t.Fatal(e)
	}
	ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task)
	if r, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("actual inference failed", code, r.Outcome)
	}
	c, e := readResearchCost(ctx, s.Store.DB, run, task, 1, 1)
	if e != nil || c.Tokens == nil || *c.Tokens != "9007199254740993" || c.TotalUSD == nil || *c.TotalUSD != "0."+strings.Repeat("0", 4095)+"1" || c.AccountedUSD != c.KnownUSD || c.UnknownUSD != 0 {
		t.Fatal("precision/coverage lost", c, e)
	}
	public, e := readResearchCost(ctx, s.Store.DB, run, "another-task", 1, 0)
	if e != nil || public.Tokens == nil || *public.Tokens != "0" || public.TotalUSD != nil || public.KnownTokens != "0" {
		t.Fatal("task borrowed another task's receipt", public, e)
	}
	aggregate := addResearchCost(c, c, 1, 1)
	if *aggregate.Tokens != "18014398509481986" || aggregate.KnownUSD != "0."+strings.Repeat("0", 4095)+"2" {
		t.Fatal("aggregate rounded exact amounts", aggregate)
	}
	missing, e := readResearchCost(ctx, s.Store.DB, "other-run", "", 1, 1)
	if e != nil || missing.Tokens != nil || missing.InferenceUSD != nil || missing.Missing != 1 || missing.TotalUSD != nil {
		t.Fatal("missing receipt became free", missing, e)
	}
	zero.Store(true)
	otherRun := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 5, ProbeBudget: 2})
	var otherTask string
	if e := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", otherRun).Scan(&otherTask); e != nil {
		t.Fatal(e)
	}
	otherCtx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, otherRun), researchTaskBudgetKey{}, otherTask)
	if r, code := inferBudgetAttempt(t, s, otherCtx, scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("actual zero response failed", code, r.Outcome)
	}
	z, e := readResearchCost(otherCtx, s.Store.DB, otherRun, otherTask, 1, 1)
	if e != nil || z.TotalUSD == nil || *z.TotalUSD != "0" || z.Tokens == nil || *z.Tokens != "0" {
		t.Fatal("measured zero became unknown", z, e)
	}
	comparison := compareResearchCost("PAIRED_FULL_RUNS", c, z)
	if comparison.Status != "LOWER" || comparison.USD == nil || *comparison.USD != "-0."+strings.Repeat("0", 4095)+"1" || *comparison.Tokens != "-9007199254740993" {
		t.Fatal("known delta rounded", comparison)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "CANCEL"}), 200)
	w := req(t, s, "GET", "/api/v1/research/reflections?run_id="+run, nil)
	requireCode(t, w, 200)
	var reports []struct {
		Report struct {
			Cost *researchCostEvidence `json:"cost_evidence"`
		} `json:"report"`
	}
	if json.Unmarshal(w.Body.Bytes(), &reports) != nil || len(reports) != 1 || reports[0].Report.Cost == nil || *reports[0].Report.Cost.Tokens != "9007199254740993" {
		t.Fatal("cancelled actual cost disappeared", w.Body.String())
	}
	if calls.Load() != 2 || strings.Contains(w.Body.String(), "top-secret-example-key") {
		t.Fatal("cost inspection sent traffic or leaked secret")
	}
}

func TestResearchCostReplayKeepsLegacyUnknownAndImmutableTaskCoverage(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, _, calls := qualityFixture(t)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "useful")
	e := qualityEvaluation(t, s, v)
	if e.Cost == nil || e.Cost.Basis != "RECORDED_ATOMIC_TASK_REPLAY" || e.Cost.Baseline.HTTP != 3 || e.Cost.Proposed.HTTP != 3 || e.Cost.Baseline.PublicHTTP != 3 || *e.Cost.Baseline.Tokens != "0" || e.Cost.Status != "UNKNOWN" {
		t.Fatalf("missing replay cost coverage: %+v", e.Cost)
	}
	if _, err := s.Store.DB.Exec("UPDATE knowledge_evaluations SET report_json=json_remove(report_json,'$.cost_comparison') WHERE id=?", e.ID); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, Confirm: true, EvaluationID: e.ID}), 409)
	if _, err := s.Store.DB.Exec("UPDATE research_quality_observations SET observation_json=json_remove(observation_json,'$.cost_evidence')"); err != nil {
		t.Fatal(err)
	}
	legacy := qualityEvaluation(t, s, v)
	if legacy.Status != e.Status || legacy.Cost.Baseline.Tokens != nil || legacy.Cost.Baseline.TotalUSD != nil || legacy.Cost.Baseline.Unmeasured != 3 || legacy.DatasetHash == e.DatasetHash {
		t.Fatal("legacy records invented zero or lost original quality meaning", legacy.Cost)
	}
	if calls.Load() != 6 {
		t.Fatal("cost evaluation issued HTTP", calls.Load())
	}
}

func TestResearchCostUnknownFailureCannotBecomeMeasuredSavings(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 5, 2)
	f.Mode.Store(1)
	if r, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 502 || r.Outcome != "UPSTREAM_5XX" {
		t.Fatal("actual HTTP failure not retained", code, r.Outcome)
	}
	c, e := readResearchCost(f.context(), f.Server.Store.DB, f.RunID, f.TaskID, 1, 1)
	if e != nil || c.Tokens != nil || c.TotalUSD != nil || c.AccountedTokens != "256" || c.AccountedUSD != "0.01" || c.KnownTokens != "0" || c.UnknownTokens != 1 || c.UnknownUSD != 1 {
		t.Fatal("reservation became reported cost", c, e)
	}
	old := addResearchCost(nil, nil, 1, 0)
	comparison := compareResearchCost("RECORDED_ATOMIC_TASK_REPLAY", c, old)
	if comparison.Status != "UNKNOWN" || comparison.USD != nil || comparison.Tokens != nil {
		t.Fatal("unknown was promoted to a savings claim", comparison)
	}
	if f.Calls.Load() != 1 {
		t.Fatal("inspection retried inference")
	}
}
