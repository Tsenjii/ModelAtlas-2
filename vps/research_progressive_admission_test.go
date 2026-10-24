package main

import (
	"context"
	"fmt"
	"strings"
	"testing"
)

func TestResearchProgressiveAdmissionRechecksScopeInsideTransaction(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	s.runPending(context.Background())
	f.Calls.Store(0)
	bound, err := s.researchTaskProbeContext(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &f.Scope})
	if err != nil {
		t.Fatal(err)
	}
	p, err := s.provider(context.Background(), "p")
	if err != nil {
		t.Fatal(err)
	}
	cfg := s.config(context.Background(), p)
	cfg.Headers = map[string]string{"OpenAI-Project": "changed-between-task-and-admission"}
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": cfg}), 200)
	result, code := s.execute(bound, inferRequest{SessionID: f.SessionID, Prompt: "A reviewed but now stale connection", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
	if code != 409 || result.Outcome != "RESEARCH_PLAN_CHANGED" || f.Calls.Load() != 0 {
		t.Fatal("scope changed between initial check and atomic admission", code, result.Outcome, f.Calls.Load())
	}
	f.assertRunUsage(t, 0, 0)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchProgressiveDeeperAdmissionResourceAndStorageFailuresAreAtomic(t *testing.T) {
	parallelProgressiveTest(t)
	for _, metric := range []string{"OUTPUT_ENVELOPE", "USD", "DB"} {
		t.Run(metric, func(t *testing.T) {
			f := newResearchProbeFixture(t, true)
			s := f.Server
			v := f.options()
			v.DeeperSelections = nil
			researchRunID(t, s, v)
			s.runResearchPending(context.Background())
			if f.Calls.Load() != 2 {
				t.Fatal("real minimal observation missing")
			}
			switch metric {
			case "OUTPUT_ENVELOPE":
				setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 10000, "reserve_tokens": 64})
			case "USD":
				setResourcePolicyForTest(t, s, map[string]any{"daily_usd": "0"})
			case "DB":
				if _, err := s.Store.DB.Exec("CREATE TRIGGER progressive_admission_fault BEFORE INSERT ON research_resource_admissions BEGIN SELECT RAISE(ABORT,'injected progressive admission failure'); END"); err != nil {
					t.Fatal(err)
				}
			}
			run := researchRunID(t, s, f.options())
			s.runResearchPending(context.Background())
			if f.Calls.Load() != 3 {
				t.Fatal("refused deeper work sent inference", metric, f.Calls.Load())
			}
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry")
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_probe_observations")
			assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", run)
			assertSQLInt(t, s, 0, "SELECT probes_used FROM research_run_options WHERE run_id=?", run)
			for i := 0; i < 3; i++ {
				s.workerTick(context.Background())
			}
			if f.Calls.Load() != 3 {
				t.Fatal("admission refusal spun or retried")
			}
		})
	}
}

func TestResearchProgressiveOversizeCompletePlanIsRejectedBeforePartialEnqueue(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, false)
	s := f.Server
	v := ResearchOptions{Budget: 200, ProbeBudget: 10, MinimalInference: true, ObservationMinutes: 15}
	for i := 0; i < 32; i++ {
		scope := f.Scope
		if i > 0 {
			scope.ModelID = fmt.Sprintf("model-%02d", i)
			requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: scope.ModelID, DisplayName: scope.ModelID}), 201)
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
		}
		v.Selections = append(v.Selections, scope)
		if i < 8 {
			v.DeeperSelections = append(v.DeeperSelections, scope)
		}
		id := fmt.Sprintf("source-%02d", i)
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: id, Name: id, Kind: "JSON", URL: "https://provider.example/" + id, Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data"}}), 200)
		v.SourceIDs = append(v.SourceIDs, id)
		candidate, err := s.ensureCandidate(context.Background(), "public-provider", scope.ModelID, "imported", "https://provider.example/"+scope.ModelID)
		if err != nil {
			t.Fatal(err)
		}
		v.CandidateIDs = append(v.CandidateIDs, candidate.ID)
	}
	w := req(t, s, "POST", "/api/v1/research/plan", v)
	requireCode(t, w, 422)
	if !strings.Contains(w.Body.String(), "128 tasks") {
		t.Fatal("oversize plan was silently truncated", w.Body.String())
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/start", v), 400)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_tasks")
	assertSQLInt(t, s, 32, "SELECT COUNT(*) FROM my_models")
	if f.Calls.Load() != 0 {
		t.Fatal("invalid plan sent traffic")
	}
}
