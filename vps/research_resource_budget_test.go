package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type resourceUsageView struct {
	Requests       int     `json:"requests"`
	Pending        int     `json:"pending_requests"`
	UnknownTokens  int     `json:"unknown_token_requests"`
	UnknownUSD     int     `json:"unknown_usd_requests"`
	Overruns       int     `json:"overrun_requests"`
	Tokens         string  `json:"accounted_tokens"`
	USD            string  `json:"accounted_usd"`
	ReportedTokens *string `json:"reported_tokens"`
	ReportedUSD    *string `json:"reported_usd"`
}

type resourceBudgetView struct {
	Revision    int64                  `json:"revision"`
	Policy      researchResourcePolicy `json:"policy"`
	Usage       resourceUsageView      `json:"usage"`
	Day         string                 `json:"day"`
	Older       int                    `json:"older_unbudgeted_requests"`
	Next        string                 `json:"next_cursor"`
	Credentials []struct {
		researchInferenceScope
		Limits   researchResourceLimits `json:"limits"`
		Override bool                   `json:"override"`
		Usage    resourceUsageView      `json:"usage"`
	} `json:"credentials"`
}

func resourceBudgetForTest(t *testing.T, s *Server) resourceBudgetView {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/resource-budget", nil)
	requireCode(t, w, 200)
	var v resourceBudgetView
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil || v.Revision < 1 || strings.Contains(w.Body.String(), "top-secret-example-key") {
		t.Fatal("invalid or unsafe resource budget", err)
	}
	return v
}

func setResourcePolicyForTest(t *testing.T, s *Server, changes map[string]any) {
	t.Helper()
	v := resourceBudgetForTest(t, s)
	raw, _ := json.Marshal(v.Policy)
	var value map[string]any
	json.Unmarshal(raw, &value)
	for name, change := range changes {
		value[name] = change
	}
	value["action"] = "POLICY"
	value["revision"] = v.Revision
	requireCode(t, req(t, s, "POST", "/api/v1/research/resource-budget", value), 200)
}

func setResourceCredentialForTest(t *testing.T, s *Server, scope Selection, tokens, usd any, reset bool) {
	t.Helper()
	v := resourceBudgetForTest(t, s)
	action := "CREDENTIAL"
	body := map[string]any{"revision": v.Revision, "provider_id": scope.ProviderID, "account_id": scope.AccountID, "credential_id": scope.CredentialID}
	if reset {
		action = "RESET"
	} else {
		body["tokens"] = tokens
		body["usd"] = usd
	}
	body["action"] = action
	requireCode(t, req(t, s, "POST", "/api/v1/research/resource-budget", body), 200)
}

func TestResearchResourceBudgetFormalSettingsAndUnknownReservation(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	w := req(t, f.Server, "GET", "/api/v1/research/resource-budget", nil)
	requireCode(t, w, 200)
	var state struct {
		Revision int64 `json:"revision"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &state); err != nil || state.Revision < 1 {
		t.Fatal("resource budget unavailable", err)
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/resource-budget", map[string]any{"action": "POLICY", "revision": state.Revision, "daily_tokens": 32, "daily_usd": "0.02", "credential_tokens": nil, "credential_usd": nil, "reserve_tokens": 32, "reserve_usd": "0.01"}), 200)
	tooLarge, code := f.Server.execute(f.context(), inferRequest{SessionID: f.SessionID, Prompt: "Explicit oversized output", MaxTokens: 33}, "CRAWLER_VERIFY", func(string, any) error { return nil })
	envelope, ok := tooLarge.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || envelope.Metric != "OUTPUT_ENVELOPE" || f.Calls.Load() != 0 {
		t.Fatal("output envelope bypassed token reservation", code, tooLarge.Outcome)
	}
	f.assertUsage(t, 0, 0)
	for i := 0; i < 2; i++ {
		result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
		if i == 0 && (code != 0 || result.Outcome != "SUCCESS") {
			t.Fatal("first resource authorization failed", code, result.Outcome)
		}
		if i == 1 {
			raw, _ := json.Marshal(result.Details)
			if code != 429 || result.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || !strings.Contains(string(raw), `"metric":"TOKENS"`) {
				t.Fatalf("unknown usage bypassed allocated token budget: %d %s %s", code, result.Outcome, raw)
			}
		}
	}
	if f.Calls.Load() != 1 {
		t.Fatal("resource budget sent unauthorized HTTP", f.Calls.Load())
	}
	f.assertUsage(t, 1, 1)
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_resource_admissions")
	view := req(t, f.Server, "GET", "/api/v1/research/resource-budget", nil)
	requireCode(t, view, 200)
	if !strings.Contains(view.Body.String(), `"accounted_tokens":"32"`) || !strings.Contains(view.Body.String(), `"reported_tokens":null`) {
		t.Fatal("unknown token use became zero", view.Body.String())
	}
	result, code := inferBudgetAttempt(t, f.Server, context.Background(), f.Scope, "PLAYGROUND")
	if code != 0 || result.Outcome != "SUCCESS" || f.Calls.Load() != 2 {
		t.Fatal("research resource policy blocked ordinary chat", code, result.Outcome)
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_resource_admissions")
}

func TestResearchResourceBudgetInvalidCostAndPartialResponseKeepReservation(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, tc := range []struct{ name, events, outcome, tokens, usd string }{
		{"invalid-cost-sticky", `data: {"choices":[{"delta":{"content":"Observed"}}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3,"cost":-1}}

data: {"choices":[],"usage":{"cost":0}}

data: [DONE]

`, "SUCCESS", "3", "0.01"},
		{"truncated-larger-report", `data: {"choices":[{"delta":{"content":"Observed"}}],"usage":{"prompt_tokens":30,"completion_tokens":10,"total_tokens":40,"cost":0.2}}

`, "PARSER", "40", "0.2"},
		{"truncated-smaller-report", `data: {"choices":[{"delta":{"content":"Observed"}}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3,"cost":0}}

`, "PARSER", "32", "0.01"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := testServer(t)
			scope := seed(t, s, "https://openrouter.ai/v1")
			_ = sessionForTest(t, s, scope)
			var calls atomic.Int32
			up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
					t.Error("wrong partial response credential")
				}
				w.Header().Set("Content-Type", "text/event-stream")
				fmt.Fprint(w, tc.events)
			}))
			defer up.Close()
			s.Client = mappedFixture(up)
			run := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 5, ProbeBudget: 2})
			var task string
			s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task)
			ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task)
			setResourcePolicyForTest(t, s, map[string]any{"reserve_tokens": 32})
			r, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
			if code != 0 || r.Outcome != tc.outcome || calls.Load() != 1 {
				t.Fatal("partial caller", code, r.Outcome, calls.Load())
			}
			v := resourceBudgetForTest(t, s)
			if v.Usage.Tokens != tc.tokens || v.Usage.USD != tc.usd || v.Usage.ReportedUSD != nil {
				t.Fatal("invalid/partial cost released reservation", v.Usage)
			}
			if tc.outcome != "SUCCESS" && v.Usage.ReportedTokens != nil {
				t.Fatal("incomplete tokens claimed complete", v.Usage)
			}
		})
	}
}

func TestResearchResourceBudgetNativeMeasurementsStayExactAndComplete(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, tc := range []struct{ name, op, body, tokens string }{
		{"chat-complete", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"completion_tokens_details":{"reasoning_tokens":2},"cost":0}}`, "7"},
		{"chat-partial", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"cost":0}}`, ""},
		{"chat-inconsistent", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":2}}`, ""},
		{"chat-negative", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"completion_tokens":-1,"total_tokens":7}}`, ""},
		{"chat-inconsistent-reasoning", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7,"completion_tokens_details":{"reasoning_tokens":10}}}`, ""},
		{"chat-inconsistent-cache", "CHAT_COMPLETIONS", `{"choices":[{"message":{"content":"Observed"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7,"prompt_tokens_details":{"cached_tokens":10}}}`, ""},
		{"responses-exact-int64", "RESPONSES", `{"status":"completed","model":"m","output":[{"type":"message","content":[{"type":"output_text","text":"Observed"}]}],"usage":{"input_tokens":9007199254740992,"output_tokens":1,"total_tokens":9007199254740993}}`, "9007199254740993"},
		{"gemini-complete", "GEMINI_NATIVE", `{"modelVersion":"m","candidates":[{"content":{"parts":[{"text":"Observed"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":3,"thoughtsTokenCount":5,"toolUsePromptTokenCount":10,"totalTokenCount":22}}`, "22"},
		{"gemini-inconsistent-tool-prompt", "GEMINI_NATIVE", `{"modelVersion":"m","candidates":[{"content":{"parts":[{"text":"Observed"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":3,"thoughtsTokenCount":5,"toolUsePromptTokenCount":10,"totalTokenCount":12}}`, ""},
		{"gemini-partial", "GEMINI_NATIVE", `{"modelVersion":"m","candidates":[{"content":{"parts":[{"text":"Observed"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":3}}`, ""},
		{"anthropic-cache-complete", "ANTHROPIC_MESSAGES", `{"model":"m","content":[{"type":"text","text":"Observed"}],"stop_reason":"end_turn","usage":{"input_tokens":4,"output_tokens":3,"cache_read_input_tokens":10,"cache_creation_input_tokens":5}}`, "22"},
		{"anthropic-cache-missing", "ANTHROPIC_MESSAGES", `{"model":"m","content":[{"type":"text","text":"Observed"}],"stop_reason":"end_turn","usage":{"input_tokens":4,"output_tokens":3}}`, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
					t.Error("wrong native budget credential")
				}
				w.Header().Set("Content-Type", "application/json")
				fmt.Fprint(w, tc.body)
			}))
			defer up.Close()
			s := testServer(t)
			s.Client = up.Client()
			scope := seed(t, s, up.URL+"/v1")
			scope.Endpoint = tc.op
			session := sessionForTest(t, s, scope)
			run := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 5, ProbeBudget: 2})
			var task string
			s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task)
			ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task)
			setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 128, "reserve_tokens": 32})
			stream := false
			result, code := s.execute(ctx, inferRequest{SessionID: session, Prompt: "One native observation", MaxTokens: 16, Streaming: &stream}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code != 0 || result.Outcome != "SUCCESS" || calls.Load() != 1 {
				t.Fatal("native resource caller failed", code, result.Outcome, calls.Load())
			}
			v := resourceBudgetForTest(t, s)
			if tc.tokens == "" {
				if v.Usage.ReportedTokens != nil || v.Usage.Tokens != "32" || v.Usage.UnknownTokens != 1 {
					t.Fatal("partial/invalid native usage released reservation", v.Usage)
				}
			} else if v.Usage.ReportedTokens == nil || *v.Usage.ReportedTokens != tc.tokens || v.Usage.Tokens != tc.tokens {
				t.Fatal("native token total lost precision/components", tc.tokens, v.Usage)
			}
			if v.Usage.ReportedUSD != nil || v.Usage.USD != "0.01" || v.Usage.UnknownUSD != 1 {
				t.Fatal("unknown currency became free", v.Usage)
			}
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
		})
	}
}

func TestResearchResourceBudgetRejectsMalformedPoliciesAndStaleWrites(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 5, 3)
	v := resourceBudgetForTest(t, f.Server)
	base := map[string]any{"action": "POLICY", "revision": v.Revision, "daily_tokens": nil, "daily_usd": nil, "credential_tokens": nil, "credential_usd": nil, "reserve_tokens": 32, "reserve_usd": "0.01"}
	for _, tc := range []struct {
		name   string
		change map[string]any
		omit   string
	}{
		{"missing-explicit-cap", nil, "daily_tokens"},
		{"unknown-field", map[string]any{"provider_remaining": 0}, ""},
		{"negative", map[string]any{"daily_tokens": -1}, ""},
		{"fractional-token", map[string]any{"daily_tokens": 0.5}, ""},
		{"zero-reservation", map[string]any{"reserve_tokens": 0}, ""},
		{"zero-money-reservation", map[string]any{"reserve_usd": "0"}, ""},
		{"nonfinite-money", map[string]any{"daily_usd": "NaN"}, ""},
		{"numeric-money", map[string]any{"daily_usd": 0.1}, ""},
		{"negative-money", map[string]any{"daily_usd": "-0.1"}, ""},
		{"too-large", map[string]any{"daily_tokens": 1000000000001}, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := map[string]any{}
			for k, x := range base {
				body[k] = x
			}
			for k, x := range tc.change {
				body[k] = x
			}
			delete(body, tc.omit)
			requireCode(t, req(t, f.Server, "POST", "/api/v1/research/resource-budget", body), 400)
			if resourceBudgetForTest(t, f.Server).Revision != v.Revision {
				t.Fatal("invalid policy mutated revision")
			}
		})
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/resource-budget", base), 200)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/resource-budget", base), 409)
	assertSQLInt(t, f.Server, 2, "SELECT COUNT(*) FROM research_resource_history")
	if f.Calls.Load() != 0 {
		t.Fatal("policy write sent private traffic")
	}
	if _, err := f.Server.Store.DB.Exec(`UPDATE research_resource_policy SET policy_json='{"reserve_tokens":32,"reserve_usd":"0.01"}'`); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, f.Server, "GET", "/api/v1/research/resource-budget", nil), 503)
	r, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
	b, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || b.Metric != "UNAVAILABLE" || f.Calls.Load() != 0 {
		t.Fatal("malformed stored policy allowed HTTP", code, r.Outcome)
	}
	f.assertUsage(t, 0, 0)
}

func TestResearchResourceBudgetUsesSameUTCAdmissionDayAtMidnight(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 5, 3)
	before := time.Date(2026, 10, 23, 23, 59, 59, 999000000, time.UTC)
	after := before.Add(time.Millisecond)
	f.Server.scheduleClock = func() time.Time { return before }
	setResourcePolicyForTest(t, f.Server, map[string]any{"daily_tokens": 32, "reserve_tokens": 32})
	if result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("initial midnight observation", code, result.Outcome)
	}
	var calls atomic.Int32
	f.Server.scheduleClock = func() time.Time {
		if calls.Add(1) == 1 {
			return before
		}
		return after
	}
	result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
	b, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || b.Day != "2026-10-23" || f.Calls.Load() != 1 {
		t.Fatal("midnight split bypassed original admission day", code, result.Outcome, b, f.Calls.Load())
	}
	f.assertUsage(t, 1, 1)
	f.Server.scheduleClock = func() time.Time { return after }
	if result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("next UTC day did not reopen budget", code, result.Outcome)
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_resource_admissions r JOIN research_inference_admissions a ON a.request_id=r.request_id WHERE day='2026-10-23'")
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_resource_admissions r JOIN research_inference_admissions a ON a.request_id=r.request_id WHERE day='2026-10-24'")
}

func TestResearchResourceBudgetExactReportedSettlementZeroOverrunAndRotation(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	scope := seed(t, s, "https://openrouter.ai/v1")
	_ = sessionForTest(t, s, scope)
	var mode, calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if !oneOf(r.Header.Get("Authorization"), "Bearer top-secret-example-key", "Bearer synthetic-resource-rotation") {
			t.Error("wrong resource fixture credential")
		}
		usage := `"prompt_tokens":9,"completion_tokens":3,"total_tokens":12,"cost":0.1`
		switch mode.Load() {
		case 1:
			usage = `"prompt_tokens":9,"completion_tokens":3,"total_tokens":12,"cost":0.2`
		case 2:
			usage = `"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"cost":0`
		case 3:
			usage = `"prompt_tokens":30,"completion_tokens":2,"total_tokens":32,"cost":0.8`
		case 4:
			usage = `"prompt_tokens":9007199254740992,"completion_tokens":1,"total_tokens":9007199254740993,"cost":1e-4096`
		case 5:
			usage = `"prompt_tokens":9223372036854775806,"completion_tokens":1,"total_tokens":9223372036854775807,"cost":0`
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "data: {\"choices\":[{\"delta\":{\"content\":\"Observed\"}}],\"usage\":{%s}}\n\ndata: [DONE]\n\n", usage)
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 20, ProbeBudget: 10})
	var task string
	s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task)
	ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task)
	setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 128, "daily_usd": "0.3", "reserve_tokens": 32, "reserve_usd": "0.1"})
	for i := 0; i < 2; i++ {
		mode.Store(int32(i))
		result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
		if code != 0 || result.Outcome != "SUCCESS" {
			t.Fatal("exact spend request failed", code, result.Outcome)
		}
	}
	v := resourceBudgetForTest(t, s)
	if v.Usage.Tokens != "24" || v.Usage.USD != "0.3" || v.Usage.ReportedUSD == nil || *v.Usage.ReportedUSD != "0.3" || v.Usage.UnknownTokens != 0 || v.Usage.UnknownUSD != 0 {
		t.Fatalf("USD float drift or fabricated usage: %#v", v.Usage)
	}
	denied, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
	limit, ok := denied.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || limit.Metric != "USD" || calls.Load() != 2 {
		t.Fatal("exact USD cap did not stop before HTTP", code, denied.Outcome, limit)
	}
	setResourcePolicyForTest(t, s, map[string]any{"daily_usd": "1"})
	mode.Store(2)
	if result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("reported zero failed")
	}
	if v := resourceBudgetForTest(t, s); v.Usage.Tokens != "24" || v.Usage.USD != "0.3" || v.Usage.UnknownUSD != 0 {
		t.Fatal("known zero treated as unknown", v.Usage)
	}
	mode.Store(3)
	if result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("overrun response/history was discarded")
	}
	v = resourceBudgetForTest(t, s)
	if v.Usage.USD != "1.1" || v.Usage.Tokens != "56" || v.Usage.Overruns != 2 {
		t.Fatal("overrun was clamped to reservation", v.Usage)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "synthetic-resource-rotation", "confirm": true}), 200)
	denied, code = inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
	if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || calls.Load() != 4 {
		t.Fatal("rotation refunded USD")
	}
	setResourcePolicyForTest(t, s, map[string]any{"daily_usd": "2"})
	setResourceCredentialForTest(t, s, scope, nil, "0.15", false)
	denied, code = inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
	limit, ok = denied.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || limit.Scope != "CREDENTIAL" || limit.Metric != "USD" {
		t.Fatal("credential spend scope bypassed", limit)
	}
	setResourceCredentialForTest(t, s, scope, nil, nil, true)
	setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": nil, "daily_usd": nil})
	mode.Store(4)
	if result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("large exact provider count failed", code, result.Outcome)
	}
	v = resourceBudgetForTest(t, s)
	if v.Usage.Tokens != "9007199254741049" {
		t.Fatal("unsafe Number/int precision", v.Usage.Tokens)
	}
	actual := resourceStoredAmount(*v.Usage.ReportedUSD)
	if actual == nil || actual.Cmp(big.NewRat(11, 10)) <= 0 {
		t.Fatal("tiny positive reported USD rounded to zero")
	}
	assertSQLInt(t, s, 5, "SELECT COUNT(*) FROM research_resource_admissions")
	assertSQLInt(t, s, 5, "SELECT COUNT(*) FROM research_resource_settlements")
	mode.Store(5)
	for i := 0; i < 2; i++ {
		if result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
			t.Fatal("int64 edge report failed", code, result.Outcome)
		}
	}
	if v := resourceBudgetForTest(t, s); v.Usage.Tokens != "18455751272964292663" {
		t.Fatal("cumulative tokens overflowed int64", v.Usage.Tokens)
	}
	assertSQLInt(t, s, 7, "SELECT COUNT(*) FROM research_resource_admissions")
	assertSQLInt(t, s, 7, "SELECT COUNT(*) FROM research_resource_settlements")
}

func TestResearchResourceBudgetAtomicFaultsAndUnavailableMeasurements(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, table := range []string{"research_resource_admissions", "messages", "telemetry"} {
		t.Run(table, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 5, 3)
			_, err := f.Server.Store.DB.Exec("CREATE TRIGGER resource_fault BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'synthetic resource admission fault'); END")
			if err != nil {
				t.Fatal(err)
			}
			result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code == 0 || result.Outcome == "SUCCESS" || f.Calls.Load() != 0 {
				t.Fatal("failed local admission sent HTTP")
			}
			f.assertUsage(t, 0, 0)
			for _, name := range []string{"research_inference_admissions", "research_resource_admissions", "research_resource_settlements", "messages", "telemetry", "request_context"} {
				assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM "+name)
			}
		})
	}
	t.Run("settlement-retains-reservation", func(t *testing.T) {
		f := newResearchAdmissionFixture(t, 5, 3)
		setResourcePolicyForTest(t, f.Server, map[string]any{"daily_tokens": 32, "reserve_tokens": 32})
		_, err := f.Server.Store.DB.Exec("CREATE TRIGGER resource_settlement_fault BEFORE INSERT ON research_resource_settlements BEGIN SELECT RAISE(ABORT,'synthetic settlement fault'); END")
		if err != nil {
			t.Fatal(err)
		}
		result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
		if code != 0 || result.Outcome != "SUCCESS" || result.Details["resource_budget_settlement"] != "UNAVAILABLE_RESERVATION_RETAINED" {
			t.Fatal("settlement fault lost answer/history", result.Outcome)
		}
		v := resourceBudgetForTest(t, f.Server)
		if v.Usage.Pending != 1 || v.Usage.Tokens != "32" || v.Usage.ReportedTokens != nil {
			t.Fatal("failed settlement released a reservation", v.Usage)
		}
		denied, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
		if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 1 {
			t.Fatal("settlement DB fault overspent")
		}
		assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM telemetry WHERE outcome='SUCCESS'")
		assertSQLInt(t, f.Server, 2, "SELECT COUNT(*) FROM messages")
	})
	t.Run("policy-and-journal", func(t *testing.T) {
		f := newResearchAdmissionFixture(t, 5, 3)
		s := f.Server
		before := resourceBudgetForTest(t, s)
		_, err := s.Store.DB.Exec("CREATE TRIGGER resource_policy_fault BEFORE INSERT ON research_resource_history BEGIN SELECT RAISE(ABORT,'synthetic policy fault'); END")
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := json.Marshal(before.Policy)
		var body map[string]any
		json.Unmarshal(raw, &body)
		body["action"] = "POLICY"
		body["revision"] = before.Revision
		body["daily_tokens"] = 0
		requireCode(t, req(t, s, "POST", "/api/v1/research/resource-budget", body), 503)
		if after := resourceBudgetForTest(t, s); after.Revision != before.Revision || after.Policy.DailyTokens != nil {
			t.Fatal("failed journal committed authorization")
		}
		_, err = s.Store.DB.Exec("DELETE FROM research_resource_policy")
		if err != nil {
			t.Fatal(err)
		}
		result, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
		limit, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError)
		if code != 429 || !ok || limit.Metric != "UNAVAILABLE" || f.Calls.Load() != 0 {
			t.Fatal("missing policy fell back to unlimited", code, result.Outcome)
		}
		f.assertUsage(t, 0, 0)
		requireCode(t, req(t, s, "GET", "/api/v1/research/resource-budget", nil), 503)
	})
}

func TestResearchResourceBudgetConcurrentReservationsWhileTLSIsPending(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, bound := range []string{"ENGINE", "CREDENTIAL"} {
		t.Run(bound, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 20, 10)
			s := f.Server
			f.Mode.Store(3)
			changes := map[string]any{"reserve_tokens": 32, "reserve_usd": "0.1"}
			if bound == "ENGINE" {
				changes["daily_tokens"] = 96
			} else {
				changes["credential_usd"] = "0.3"
			}
			setResourcePolicyForTest(t, s, changes)
			contexts := []context.Context{f.context()}
			for i := 0; i < 3; i++ {
				run := researchRunID(t, s, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 20, ProbeBudget: 10})
				var task string
				if err := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task); err != nil {
					t.Fatal(err)
				}
				contexts = append(contexts, context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task))
			}
			ids := make([]string, 20)
			for i := range ids {
				var err error
				ids[i], err = s.createTestSession(context.Background(), f.Scope)
				if err != nil {
					t.Fatal(err)
				}
			}
			var wg sync.WaitGroup
			var success, denied atomic.Int32
			waiting := make(chan struct{}, 20)
			slots := make(chan struct{}, s.flightLimit())
			release := sync.OnceFunc(func() { close(f.Release) })
			defer func() { release(); wg.Wait() }()
			for i, id := range ids {
				wg.Add(1)
				go func(i int, id string) {
					defer wg.Done()
					slots <- struct{}{}
					defer func() { <-slots }()
					result, code := s.execute(contexts[i%len(contexts)], inferRequest{SessionID: id, Prompt: "Concurrent resource authorization", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
					if code == 0 && result.Outcome == "SUCCESS" {
						success.Add(1)
					} else if code == 429 && result.Outcome == "RESEARCH_BUDGET_EXHAUSTED" {
						limit, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError)
						if !ok || limit.Scope != bound {
							t.Errorf("wrong resource scope: %#v", limit)
						}
						denied.Add(1)
						waiting <- struct{}{}
					} else {
						t.Errorf("unexpected concurrent result %d %s", code, result.Outcome)
					}
				}(i, id)
			}
			for i := 0; i < 17; i++ {
				select {
				case <-waiting:
				case <-time.After(5 * time.Second):
					t.Fatal("resource denials did not finish while three TLS requests were held")
				}
			}
			for i := 0; i < 3; i++ {
				select {
				case <-f.Started:
				case <-time.After(5 * time.Second):
					t.Fatal("authorized TLS request absent")
				}
			}
			view := resourceBudgetForTest(t, s)
			if view.Usage.Pending != 3 || view.Usage.Tokens != "96" || view.Usage.USD != "0.3" {
				t.Fatal("in-flight resource accounting", view.Usage)
			}
			for _, query := range []string{"SELECT COUNT(*) FROM research_resource_admissions", "SELECT COUNT(*) FROM research_inference_admissions", "SELECT SUM(used) FROM research_run_options", "SELECT SUM(probes_used) FROM research_run_options", "SELECT COUNT(*) FROM telemetry WHERE outcome='RUNNING'"} {
				assertSQLInt(t, s, 3, query)
			}
			release()
			wg.Wait()
			if success.Load() != 3 || denied.Load() != 17 || f.Calls.Load() != 3 {
				t.Fatal("concurrent resource overspend", success.Load(), denied.Load(), f.Calls.Load())
			}
			assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_resource_settlements")
		})
	}
}

func TestResearchResourceBudgetFailureCancelUTCDayAndExplicitWorkerResume(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, mode := range []int32{1, 2} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 10, 5)
			f.Mode.Store(mode)
			s := f.Server
			instant := time.Date(2026, 10, 23, 23, 59, 59, 0, time.UTC)
			s.scheduleClock = func() time.Time { return instant }
			setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 32, "reserve_tokens": 32})
			ctx, cancel := context.WithCancel(f.context())
			defer cancel()
			done := make(chan InferenceResult, 1)
			go func() {
				result, _ := s.execute(ctx, inferRequest{SessionID: f.SessionID, Prompt: "Keep unknown sent usage", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
				done <- result
			}()
			if mode == 2 {
				select {
				case <-f.Started:
					cancel()
				case <-time.After(3 * time.Second):
					t.Fatal("cancel fixture not sent")
				}
			}
			select {
			case result := <-done:
				if mode == 1 && result.Status != 503 || mode == 2 && result.Outcome != "CANCELLED" {
					t.Fatal("lost original failure", result.Outcome)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("failure did not finish")
			}
			f.Server = reopenScheduleStore(t, s)
			s = f.Server
			s.scheduleClock = func() time.Time { return instant }
			requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
			result, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code != 429 || result.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 1 {
				t.Fatal("restart refunded unknown sent usage")
			}
			v := resourceBudgetForTest(t, s)
			if v.Usage.Tokens != "32" || v.Usage.ReportedTokens != nil || v.Usage.UnknownTokens != 1 {
				t.Fatal("failed request treated as free", v.Usage)
			}
			instant = instant.Add(time.Second)
			if v := resourceBudgetForTest(t, s); v.Usage.Requests != 0 || v.Day != "2026-10-24" {
				t.Fatal("resource day did not reset", v)
			}
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
		})
	}
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 0, "reserve_tokens": 32})
	s.runResearchPending(context.Background())
	check := func() {
		t.Helper()
		assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE id=? AND status='BUDGET_EXHAUSTED' AND attempts=0 AND json_extract(result_json,'$.resource_budget.metric')='TOKENS'", f.TaskID)
		if f.Calls.Load() != 1 {
			t.Fatal("worker wait sent inference or repeated catalog")
		}
	}
	check()
	for i := 0; i < 3; i++ {
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
		s.runResearchPending(context.Background())
		check()
	}
	setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 32})
	s.runResearchPending(context.Background())
	check()
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatal("explicit resource resume did not send one probe")
	}
	f.assertRunUsage(t, 2, 1)
	setResourcePolicyForTest(t, s, map[string]any{"daily_tokens": 0, "daily_usd": "0"})
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 5, ProbeBudget: 1})
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 3 {
		t.Fatal("zero budget re-probed existing traffic")
	}
	assertSQLInt(t, s, 0, "SELECT probes_used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
}

func TestResearchResourceBudgetLocalAuthorityMigrationAndPagedHistory(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	hub := f.Server
	setResourcePolicyForTest(t, hub, map[string]any{"daily_tokens": 32, "reserve_tokens": 32})
	setResourceCredentialForTest(t, hub, f.Scope, 32, "0.01", false)
	if result, code := inferBudgetAttempt(t, hub, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("resource fixture failed")
	}
	peer := testServer(t)
	setResourcePolicyForTest(t, peer, map[string]any{"daily_tokens": 777})
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	for _, name := range []string{"research_resource_policy", "research_resource_limits", "research_resource_admissions", "research_resource_settlements", "research_resource_history"} {
		if workspaceAllowed(name) {
			t.Fatal("resource authorization entered sync", name)
		}
	}
	if v := resourceBudgetForTest(t, peer); v.Policy.DailyTokens == nil || *v.Policy.DailyTokens != 777 || v.Usage.Requests != 0 {
		t.Fatal("HTTPS sync imported resource authority", v)
	}
	backup := req(t, hub, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	if strings.Contains(backup.Body.String(), "research_resource_") {
		t.Fatal("portable exported local budget")
	}
	var document BackupDocument
	json.Unmarshal(backup.Body.Bytes(), &document)
	restored := testServer(t)
	setResourcePolicyForTest(t, restored, map[string]any{"daily_tokens": 888})
	requireCode(t, req(t, restored, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	if v := resourceBudgetForTest(t, restored); v.Policy.DailyTokens == nil || *v.Policy.DailyTokens != 888 || v.Usage.Requests != 0 {
		t.Fatal("portable restore replaced local resource policy")
	}
	for _, name := range []string{"research_resource_settlements", "research_resource_admissions", "research_resource_limits", "research_resource_history", "research_resource_policy"} {
		if _, err := hub.Store.DB.Exec("DROP TABLE " + name); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := hub.Store.DB.Exec("PRAGMA user_version=27"); err != nil {
		t.Fatal(err)
	}
	hub = reopenScheduleStore(t, hub)
	assertSQLInt(t, hub, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	view := resourceBudgetForTest(t, hub)
	if view.Older != 1 || view.Usage.Requests != 0 || view.Policy.DailyTokens != nil {
		t.Fatal("migration invented old allocations", view)
	}
	files, err := filepath.Glob(filepath.Join(filepath.Dir(hub.Store.path), "upgrade-backups", fmt.Sprintf("schema-27-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("resource migration lacks verified original snapshot", err)
	}
	for i := 0; i < 101; i++ {
		id := fmt.Sprintf("resource-page-%03d", i)
		requireCode(t, req(t, hub, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "a", ID: id, Label: id, Secret: "synthetic-resource-page-key"}), 201)
	}
	view = resourceBudgetForTest(t, hub)
	if len(view.Credentials) != 100 || view.Next == "" {
		t.Fatal("unbounded resource credential page")
	}
	w := req(t, hub, "GET", "/api/v1/research/resource-budget?cursor="+view.Next, nil)
	requireCode(t, w, 200)
	var second resourceBudgetView
	json.Unmarshal(w.Body.Bytes(), &second)
	if len(second.Credentials) != 2 || second.Next != "" {
		t.Fatal("resource page lost credentials")
	}
	for i := 0; i < 54; i++ {
		setResourcePolicyForTest(t, hub, map[string]any{"daily_tokens": i})
	}
	w = req(t, hub, "GET", "/api/v1/research/resource-budget?history=1", nil)
	requireCode(t, w, 200)
	var history struct {
		History []json.RawMessage `json:"history"`
		Next    int64             `json:"next_before"`
	}
	json.Unmarshal(w.Body.Bytes(), &history)
	if len(history.History) != 50 || history.Next == 0 {
		t.Fatal("resource history not paged")
	}
	w = req(t, hub, "GET", fmt.Sprintf("/api/v1/research/resource-budget?history=1&before=%d", history.Next), nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &history)
	if len(history.History) != 5 || history.Next != 0 {
		t.Fatal("resource history dropped original versions")
	}
}
