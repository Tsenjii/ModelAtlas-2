package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

type researchProbeFixture struct {
	Server        *Server
	Scope         Selection
	Calls         atomic.Int32
	BadAnswer     atomic.Bool
	InputTokens   atomic.Int64
	BlockSemantic atomic.Bool
	Started       chan struct{}
	Release       chan struct{}
}

func newResearchProbeFixture(t *testing.T, tools bool) *researchProbeFixture {
	t.Helper()
	f := &researchProbeFixture{Server: testServer(t), Started: make(chan struct{}, 1), Release: make(chan struct{})}
	f.InputTokens.Store(4)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.Calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("probe did not use the exact reviewed credential")
		}
		w.Header().Set("Content-Type", "application/json")
		if r.Method == "GET" && r.URL.Path == "/v1/models" {
			fmt.Fprint(w, `{"data":[{"id":"m"}]}`)
			return
		}
		var body struct {
			Messages []struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"messages"`
			Tools     []ToolDefinition `json:"tools"`
			MaxTokens int              `json:"max_tokens"`
			Stream    bool             `json:"stream"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || r.URL.Path != "/v1/chat/completions" || len(body.Messages) == 0 {
			t.Error("invalid actual probe request")
			http.Error(w, "invalid", 400)
			return
		}
		prompt := body.Messages[len(body.Messages)-1].Content
		message := map[string]any{"role": "assistant", "content": "Observed availability"}
		if prompt == semanticProbePrompt {
			if f.BlockSemantic.Load() {
				select {
				case f.Started <- struct{}{}:
				default:
				}
				select {
				case <-f.Release:
				case <-r.Context().Done():
					return
				}
			}
			answer := `{"owner":"Mira","total":19,"marker":"CEDAR-27"}`
			if f.BadAnswer.Load() {
				answer = `{"owner":"Wrong","total":0,"marker":"wrong"}`
			}
			message["content"] = answer
			if body.MaxTokens != 128 {
				t.Error("semantic output envelope changed")
			}
		}
		if prompt == toolProbePrompt {
			if len(body.Tools) != 1 || body.Tools[0].Function.Name != "atlas_probe_echo" || body.MaxTokens != 128 {
				t.Error("wrong bounded tool request")
			}
			message["content"] = ""
			message["tool_calls"] = []any{map[string]any{"id": "probe-call", "type": "function", "function": map[string]any{"name": "atlas_probe_echo", "arguments": `{"marker":"CEDAR-27","total":19}`}}}
		}
		choice := map[string]any{"message": message, "finish_reason": "stop"}
		if body.Stream {
			choice = map[string]any{"delta": message, "finish_reason": "stop"}
		}
		response := map[string]any{"model": "m", "choices": []any{choice}, "usage": map[string]any{"prompt_tokens": f.InputTokens.Load(), "completion_tokens": 2}}
		if body.Stream {
			w.Header().Set("Content-Type", "text/event-stream")
			raw, _ := json.Marshal(response)
			fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
		} else {
			json.NewEncoder(w).Encode(response)
		}
	}))
	t.Cleanup(up.Close)
	s := f.Server
	s.Client = up.Client()
	f.Scope = seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", f.Scope), 201)
	if tools {
		requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "group": "", "endpoint": "CHAT_COMPLETIONS", "capabilities": []string{"TOOL_CALLING"}, "confirm": true}), 200)
	}
	s.runPending(context.Background())
	f.Calls.Store(0)
	return f
}

func (f *researchProbeFixture) options() ResearchOptions {
	return ResearchOptions{Selections: []Selection{f.Scope}, DeeperSelections: []Selection{f.Scope}, ObservationMinutes: 15, MinimalInference: true, Budget: 10, ProbeBudget: 5}
}
func probeReportsForTest(t *testing.T, s *Server, run string) []researchProbeReport {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/probes?run_id="+run, nil)
	requireCode(t, w, 200)
	var out struct {
		Reports []researchProbeReport `json:"reports"`
	}
	if json.Unmarshal(w.Body.Bytes(), &out) != nil {
		t.Fatal("invalid probe report page")
	}
	return out.Reports
}

func TestResearchProgressiveRealProbesAndZeroHTTPReuse(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	run := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 4 {
		t.Log(req(t, s, "GET", "/api/v1/research/tasks?run_id="+run, nil).Body.String())
		t.Logf("local observations: %+v", authenticityAnalyze(t, s, f.Scope, "probe-diagnostic").Target)
		t.Fatal("expected actual catalog, minimal, semantic and tool requests", f.Calls.Load())
	}
	reports := probeReportsForTest(t, s, run)
	if len(reports) != 2 {
		t.Fatal("missing immutable probe reports", reports)
	}
	for _, r := range reports {
		if r.Suite != progressiveProbeSuite || r.Grade == nil || r.Grade.Correct != 3 || r.Grade.Total != 3 || r.Identity != "UNPROVEN" || r.AdditionalHTTP != 1 || r.ReportedUSD != nil || r.InputTokens == nil || *r.InputTokens != "4" || r.OutputTokens == nil || *r.OutputTokens != "2" || r.ConditionHash == "" {
			t.Fatalf("bad real observation: %#v", r)
		}
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_probe_observations")
	assertSQLInt(t, s, 3, "SELECT json_extract(summary_json,'$.research_resources.requests') FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 3, "SELECT json_extract(summary_json,'$.research_resources.unknown_usd_requests') FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_reflections WHERE run_id=? AND json_extract(report_json,'$.probe_value.new_deeper_requests')=2 AND json_extract(report_json,'$.probe_value.repeated_probes')=0 AND json_array_length(json_extract(report_json,'$.progressive_probe_findings'))=2 AND json_extract(report_json,'$.inference_resource_usage.reported_usd') IS NULL", run)
	run2 := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 5 {
		t.Fatal("fresh attested traffic or benchmark was repeated", f.Calls.Load())
	}
	for _, r := range probeReportsForTest(t, s, run2) {
		if r.State != "REUSED_LOCAL_BENCHMARK" || r.AdditionalHTTP != 0 || r.AdditionalProbes != 0 || r.OriginalReportID == "" || r.Grade == nil || r.Grade.Correct != 3 {
			t.Fatal("reuse lost proof or charged again", r)
		}
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM research_probe_observations")
}

func TestResearchProgressiveWrongAnswerIsEvidenceWithoutAutomaticRetry(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchProbeFixture(t, false)
	f.BadAnswer.Store(true)
	s := f.Server
	run := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	reports := probeReportsForTest(t, s, run)
	if len(reports) != 2 || f.Calls.Load() != 3 {
		t.Fatal("unexpected capability request", reports, f.Calls.Load())
	}
	for _, r := range reports {
		if r.Identity != "UNPROVEN" {
			t.Fatal("small challenge proved identity")
		}
		if r.Stage == "SEMANTIC_PROBE" && (r.Grade == nil || r.Grade.Correct != 0 || r.Grade.Total != 3) {
			t.Fatal("wrong answers were accepted", r)
		}
		if r.Stage == "TOOL_PROBE" && (r.State != "TOOL_CAPABILITY_NOT_ENABLED" || r.Grade != nil || r.AdditionalHTTP != 0) {
			t.Fatal("unknown tools were guessed or sent", r)
		}
	}
	for i := 0; i < 3; i++ {
		s.runResearchPending(context.Background())
	}
	if f.Calls.Load() != 3 {
		t.Fatal("wrong answers triggered automatic paid retries")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchProgressiveDailyStopAndExplicitRecovery(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	setInferencePolicyForTest(t, s, 2, 2)
	run := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 3 || len(probeReportsForTest(t, s, run)) != 1 {
		t.Fatal("daily limit leaked a tool probe", f.Calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=? AND kind='TOOL_PROBE'", run)
	setInferencePolicyForTest(t, s, 3, 3)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 3 {
		t.Fatal("policy write automatically resumed a paused probe")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 4 || len(probeReportsForTest(t, s, run)) != 2 {
		t.Fatal("explicit recovery did not continue only the unfinished tool probe", f.Calls.Load())
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind='TOOL_PROBE' AND status='SUCCESS' AND attempts=1", run)
}

func TestResearchProgressivePrivateRoundCapCarriesUnadmittedProbe(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchProbeFixture(t, false)
	s := f.Server
	clock := time.Now().UTC()
	s.scheduleClock = func() time.Time { return clock }
	options := f.options()
	options.ProbeBudget = 1
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "progressive", "name": "Bounded private behavior", "interval_minutes": 15, "daily_http_limit": 20, "options": options, "private": schedulePrivatePolicy{DailyInferences: 5}}), 200)
	enablePrivateSchedule(t, s, "progressive")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	first := scheduleForTest(t, s, "progressive")
	if first.RunStatus != "BUDGET_EXHAUSTED" || f.Calls.Load() != 2 || first.RunProbes != 1 {
		t.Fatal("per-round cap was not enforced", first, f.Calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=? AND kind='SEMANTIC_PROBE'", first.LastRunID)
	clock = clock.Add(16 * time.Minute)
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	second := scheduleForTest(t, s, "progressive")
	if second.LastRunID == first.LastRunID || second.RunStatus != "COMPLETED" || f.Calls.Load() != 3 {
		t.Fatal("unfinished depth work did not carry without replaying catalog/minimal", second, f.Calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_probe_observations")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs WHERE run_id=? AND continued_from=?", second.LastRunID, first.LastRunID)
}
