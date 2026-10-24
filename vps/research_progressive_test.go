package main

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"testing"
)

var progressiveTestSlots = make(chan struct{}, 2)

// Only these new tests run together, with distinct stores, servers, clocks and
// TLS fixtures. Keep every scenario and the original full-suite time limit.
func parallelProgressiveTest(t *testing.T) {
	t.Helper()
	t.Parallel()
	progressiveTestSlots <- struct{}{}
	t.Cleanup(func() { <-progressiveTestSlots })
}

// A successful request in one provider project must not become a successful
// observation of another project after the formal configuration is changed.
func TestResearchProgressiveDoesNotReuseChangedRequestConfiguration(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	s.runPending(context.Background())
	f.Calls.Store(0)
	inner := s.Client.Transport
	var mu sync.Mutex
	var projects []string
	s.Client.Transport = originsRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		projects = append(projects, r.Header.Get("OpenAI-Project"))
		mu.Unlock()
		return inner.RoundTrip(r)
	})
	result, code := s.execute(context.Background(), inferRequest{SessionID: f.SessionID, Prompt: "A real user observation", MaxTokens: 16}, "PLAYGROUND", func(string, any) error { return nil })
	if code != 0 || result.Outcome != "SUCCESS" || f.Calls.Load() != 1 {
		t.Fatal("initial real request did not succeed", result.Outcome, code)
	}
	p, err := s.provider(context.Background(), f.Scope.ProviderID)
	if err != nil {
		t.Fatal(err)
	}
	cfg := s.config(context.Background(), p)
	cfg.Headers = map[string]string{"openai-project": "second-reviewed-project"}
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": p.ID, "config": cfg}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "CANCEL"}), 200)
	f.RunID = researchRunID(t, s, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 10, ProbeBudget: 5})
	if err = s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", f.RunID).Scan(&f.TaskID); err != nil {
		t.Fatal(err)
	}
	observation, err := s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &f.Scope})
	if err != nil {
		t.Fatal(err)
	}
	if observation["reused_local_traffic"] != false || f.Calls.Load() != 2 {
		t.Fatalf("changed request configuration borrowed the old successful observation: reused=%v HTTP=%d", observation["reused_local_traffic"], f.Calls.Load())
	}
	mu.Lock()
	defer mu.Unlock()
	if len(projects) != 2 || projects[0] != "" || projects[1] != "second-reviewed-project" {
		t.Fatal("new project did not reach the actual TLS provider", projects)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM telemetry")
}

func TestResearchProgressivePlannedConnectionChangeStopsBeforeHTTP(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	s.runPending(context.Background())
	f.Calls.Store(0)
	p, err := s.provider(context.Background(), f.Scope.ProviderID)
	if err != nil {
		t.Fatal(err)
	}
	cfg := s.config(context.Background(), p)
	cfg.Headers = map[string]string{"openai-project": "changed-after-planning"}
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": p.ID, "config": cfg}), 200)
	if _, err = s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &f.Scope}); !errors.Is(err, errResearchProbeScopeChanged) {
		t.Fatal("a prior local plan silently approved the changed request", err)
	}
	if f.Calls.Load() != 0 {
		t.Fatal("changed plan sent private traffic")
	}
	f.assertRunUsage(t, 0, 0)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchProgressiveForeignMetadataIsNotLocalTraffic(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	s.runPending(context.Background())
	f.Calls.Store(0)
	_, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES('foreign-observation','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',?)", now())
	if err != nil {
		t.Fatal(err)
	}
	observation, err := s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &f.Scope})
	if err != nil || observation["reused_local_traffic"] != false || f.Calls.Load() != 1 {
		t.Fatal("foreign metadata became a local successful request", observation, err, f.Calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE id='foreign-observation'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchProgressiveObservationReadFailureDoesNotSpend(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	s.runPending(context.Background())
	f.Calls.Store(0)
	if _, err := s.Store.DB.Exec("DROP TABLE authenticity_local_samples"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &f.Scope}); err == nil {
		t.Fatal("missing local observation authority was silently replaced with a paid probe")
	}
	if f.Calls.Load() != 0 {
		t.Fatal("observation read failure sent upstream traffic")
	}
	f.assertRunUsage(t, 0, 0)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}
