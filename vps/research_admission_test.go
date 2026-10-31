package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type researchAdmissionFixture struct {
	Server                   *Server
	Scope                    Selection
	RunID, TaskID, SessionID string
	Calls                    atomic.Int32
	Mode                     atomic.Int32
	Started                  chan struct{}
	Release                  chan struct{}
}

func newResearchAdmissionFixture(t *testing.T, budget, probes int) *researchAdmissionFixture {
	t.Helper()
	f := &researchAdmissionFixture{Server: testServer(t), Started: make(chan struct{}, 20), Release: make(chan struct{})}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.Calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong credential for admitted request")
		}
		if r.Method == "GET" && r.URL.Path == "/v1/models" {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
			return
		}
		if f.Mode.Load() == 1 {
			http.Error(w, "upstream unavailable", 503)
			return
		}
		if f.Mode.Load() == 3 {
			f.Started <- struct{}{}
			select {
			case <-f.Release:
			case <-r.Context().Done():
				return
			}
		}
		w.Header().Set("Content-Type", "text/event-stream")
		if f.Mode.Load() == 2 {
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"))
			w.(http.Flusher).Flush()
			f.Started <- struct{}{}
			select {
			case <-r.Context().Done():
			case <-time.After(5 * time.Second):
				t.Error("admitted request was not cancelled")
			}
			return
		}
		w.Write([]byte("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"Observed answer\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	t.Cleanup(up.Close)
	f.Server.Client = up.Client()
	f.Scope = seed(t, f.Server, up.URL+"/v1")
	f.SessionID = sessionForTest(t, f.Server, f.Scope)
	f.RunID = researchRunID(t, f.Server, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: budget, ProbeBudget: probes})
	if err := f.Server.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", f.RunID).Scan(&f.TaskID); err != nil {
		t.Fatal(err)
	}
	return f
}

func (f *researchAdmissionFixture) context() context.Context {
	return context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, f.RunID), researchTaskBudgetKey{}, f.TaskID)
}

func (f *researchAdmissionFixture) assertRunUsage(t *testing.T, httpUsed, probesUsed int) {
	t.Helper()
	var httpCount, probeCount int
	if err := f.Server.Store.DB.QueryRow("SELECT used,probes_used FROM research_run_options WHERE run_id=?", f.RunID).Scan(&httpCount, &probeCount); err != nil {
		t.Fatal(err)
	}
	if httpCount != httpUsed || probeCount != probesUsed {
		t.Fatalf("admission meters: HTTP=%d inference=%d, want %d/%d", httpCount, probeCount, httpUsed, probesUsed)
	}
}
func (f *researchAdmissionFixture) assertUsage(t *testing.T, httpUsed, probesUsed int) {
	t.Helper()
	f.assertRunUsage(t, httpUsed, probesUsed)
	assertSQLInt(t, f.Server, httpUsed, "SELECT COALESCE(SUM(http_requests),0) FROM research_task_usage WHERE task_id=?", f.TaskID)
	assertSQLInt(t, f.Server, probesUsed, "SELECT COALESCE(SUM(inference_requests),0) FROM research_task_usage WHERE task_id=?", f.TaskID)
}

func TestResearchConversationSecretRejectionDoesNotSpendBudget(t *testing.T) {
	f := newResearchAdmissionFixture(t, 1, 1)
	result, code := f.Server.execute(f.context(), inferRequest{SessionID: f.SessionID, Prompt: "Do not send top-secret-example-key", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
	if code != 422 || result.Outcome != "SECRET_IN_REQUEST_CONTENT" || f.Calls.Load() != 0 {
		t.Fatalf("local secret guard: code=%d outcome=%s HTTP=%d", code, result.Outcome, f.Calls.Load())
	}
	f.assertUsage(t, 0, 0)
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM research_task_usage")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM messages")
}

func TestResearchConversationAdmissionRollsBackEveryLocalWriteAndPreservesHistory(t *testing.T) {
	for _, table := range []string{"session_options", "messages", "telemetry", "request_context", "message_requests", "research_task_usage"} {
		t.Run(table, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 2, 2)
			s := f.Server
			first, code := s.execute(f.context(), inferRequest{SessionID: f.SessionID, Prompt: "Retain this successful conversation", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code != 0 || first.Outcome != "SUCCESS" {
				t.Fatal("fixture did not retain a successful request")
			}
			requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", struct {
				Selection
				Capabilities []string `json:"capabilities"`
				Confirm      bool     `json:"confirm"`
			}{f.Scope, []string{"TOOL_CALLING", "VISION"}, true}), 200)
			if err := s.saveSessionTools(context.Background(), f.SessionID, toolsForTest()); err != nil {
				t.Fatal(err)
			}
			var oldTools string
			if err := s.Store.DB.QueryRow("SELECT tools_json FROM session_options WHERE session_id=?", f.SessionID).Scan(&oldTools); err != nil {
				t.Fatal(err)
			}
			if _, err := s.Store.DB.Exec("CREATE TRIGGER admission_failure BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'injected admission failure'); END"); err != nil {
				t.Fatal(err)
			}
			request := inferRequest{SessionID: f.SessionID, Prompt: "Do not partially save this request", MaxTokens: 16, Tools: authenticityTools(), Attachments: []Attachment{{Kind: "image", Filename: "local.png", MIME: "image/png", Data: testPNG(t)}}}
			failed, code := s.execute(f.context(), request, "CRAWLER_VERIFY", func(string, any) error { return nil })
			wantCode, wantOutcome := 500, "TELEMETRY_SAVE"
			if table == "session_options" || table == "messages" {
				wantOutcome = "HISTORY_SAVE"
			}
			if table == "research_task_usage" {
				wantCode, wantOutcome = 429, "RESEARCH_BUDGET_EXHAUSTED"
			}
			if code != wantCode || failed.Outcome != wantOutcome || f.Calls.Load() != 1 || failed.Details["sample_id"] != nil || failed.Details["receipt"] != nil {
				t.Fatalf("partial admission: code=%d outcome=%s HTTP=%d", code, failed.Outcome, f.Calls.Load())
			}
			f.assertUsage(t, 1, 1)
			for _, name := range []string{"telemetry", "request_context", "authenticity_local_samples"} {
				assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM "+name)
			}
			assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM message_requests")
			var actual string
			if err := s.Store.DB.QueryRow("SELECT tools_json FROM session_options WHERE session_id=?", f.SessionID).Scan(&actual); err != nil {
				t.Fatal(err)
			}
			if actual != oldTools || s.mediaBytes != 0 || len(s.media) != 0 {
				t.Fatal("failed request changed tools or retained uncommitted media")
			}
			if _, err := s.Store.DB.Exec("DROP TRIGGER admission_failure"); err != nil {
				t.Fatal(err)
			}
			f.Server = reopenScheduleStore(t, s)
			s = f.Server
			f.assertUsage(t, 1, 1)
			requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
			success, code := s.execute(f.context(), request, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code != 0 || success.Outcome != "SUCCESS" || f.Calls.Load() != 2 {
				t.Fatalf("remaining budget did not survive local failure/restart: %d %s", code, success.Outcome)
			}
			f.assertUsage(t, 2, 2)
			assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM message_requests")
			var currentTools []ToolDefinition
			if err := s.Store.DB.QueryRow("SELECT tools_json FROM session_options WHERE session_id=?", f.SessionID).Scan(&actual); err != nil {
				t.Fatal(err)
			}
			if json.Unmarshal([]byte(actual), &currentTools) != nil || len(currentTools) != 1 || currentTools[0].Function.Name != "echo" {
				t.Fatal("committed tools missing")
			}
			retainedMedia := s.mediaBytes
			request.Tools = toolsForTest()
			denied, code := s.execute(f.context(), request, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 2 || s.mediaBytes != retainedMedia || retainedMedia == 0 || len(s.media) != 1 {
				t.Fatalf("exhausted admission changed history/media: %d %s", code, denied.Outcome)
			}
			f.assertUsage(t, 2, 2)
			assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM telemetry")
			if err := s.Store.DB.QueryRow("SELECT tools_json FROM session_options WHERE session_id=?", f.SessionID).Scan(&actual); err != nil {
				t.Fatal(err)
			}
			if json.Unmarshal([]byte(actual), &currentTools) != nil || len(currentTools) != 1 || currentTools[0].Function.Name != "echo" {
				t.Fatal("denied request changed the previous committed tools")
			}
		})
	}
}

func TestResearchConversationConcurrentAdmissionCannotOverspendEitherMeter(t *testing.T) {
	f := newResearchAdmissionFixture(t, 5, 3)
	f.Mode.Store(3)
	sessions := make([]string, 20)
	for i := range sessions {
		r := req(t, f.Server, "POST", "/api/v1/sessions", Session{Selection: f.Scope, Title: fmt.Sprintf("Concurrent session %d", i)})
		requireCode(t, r, 201)
		var session Session
		if json.Unmarshal(r.Body.Bytes(), &session) != nil || session.ID == "" {
			t.Fatal("distinct conversation unavailable")
		}
		sessions[i] = session.ID
	}
	var wg sync.WaitGroup
	var admitted, denied atomic.Int32
	budgetDone := make(chan struct{}, 20)
	release := sync.OnceFunc(func() { close(f.Release) })
	defer func() { release(); wg.Wait() }()
	// Keep all twenty attempts, with the engine's existing four-flight limit.
	// Client-side queuing lets every attempt reach admission without bypassing
	// or mistaking the separate REQUEST_IN_PROGRESS guard for a quota result.
	slots := make(chan struct{}, f.Server.flightLimit())
	for i, session := range sessions {
		wg.Add(1)
		go func(i int, session string) {
			defer wg.Done()
			slots <- struct{}{}
			defer func() { <-slots }()
			result, code := f.Server.execute(f.context(), inferRequest{SessionID: session, Prompt: fmt.Sprintf("Concurrent observation %d", i), MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code == 0 && result.Outcome == "SUCCESS" {
				admitted.Add(1)
			} else if code == 429 && result.Outcome == "RESEARCH_BUDGET_EXHAUSTED" {
				denied.Add(1)
				budgetDone <- struct{}{}
				if result.Details["sample_id"] != nil {
					t.Error("denied request exposed a nonexistent sample")
				}
			} else {
				t.Errorf("unexpected admission: %d %s", code, result.Outcome)
			}
		}(i, session)
	}
	for i := 0; i < 17; i++ {
		select {
		case <-budgetDone:
		case <-time.After(5 * time.Second):
			t.Fatal("remaining attempts did not reach the budget guard while three requests were pending")
		}
	}
	for i := 0; i < 3; i++ {
		select {
		case <-f.Started:
		case <-time.After(5 * time.Second):
			t.Fatal("three admitted TLS requests were not in flight")
		}
	}
	f.assertUsage(t, 3, 3)
	assertSQLInt(t, f.Server, 3, "SELECT COUNT(*) FROM telemetry WHERE outcome='RUNNING'")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM authenticity_local_samples")
	release()
	wg.Wait()
	if admitted.Load() != 3 || denied.Load() != 17 || f.Calls.Load() != 3 {
		t.Fatalf("concurrent admission: accepted=%d denied=%d HTTP=%d", admitted.Load(), denied.Load(), f.Calls.Load())
	}
	f.assertUsage(t, 3, 3)
	for _, name := range []string{"telemetry", "request_context"} {
		assertSQLInt(t, f.Server, 3, "SELECT COUNT(*) FROM "+name)
	}
	for _, name := range []string{"messages", "message_requests"} {
		assertSQLInt(t, f.Server, 6, "SELECT COUNT(*) FROM "+name)
	}
	f.Server = reopenScheduleStore(t, f.Server)
	f.assertUsage(t, 3, 3)
}

func TestResearchConversationSentFailureOrCancellationRetainsAdmission(t *testing.T) {
	for _, mode := range []int32{1, 2} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 2, 1)
			f.Mode.Store(mode)
			ctx, cancel := context.WithCancel(f.context())
			defer cancel()
			type response struct {
				result InferenceResult
				code   int
			}
			done := make(chan response, 1)
			go func() {
				r, code := f.Server.execute(ctx, inferRequest{SessionID: f.SessionID, Prompt: "Admit exactly once", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
				done <- response{r, code}
			}()
			if mode == 2 {
				select {
				case <-f.Started:
					cancel()
				case <-time.After(3 * time.Second):
					t.Fatal("real TLS request never started")
				}
			}
			var out response
			select {
			case out = <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("admitted request did not finish")
			}
			if mode == 1 && (out.result.Status != 503 || out.result.Outcome == "SUCCESS") {
				t.Fatalf("upstream failure lost: %d %s", out.result.Status, out.result.Outcome)
			}
			if mode == 2 && out.result.Outcome != "CANCELLED" {
				t.Fatalf("cancellation lost: %s", out.result.Outcome)
			}
			if f.Calls.Load() != 1 || out.result.Details["sample_id"] == nil {
				t.Fatal("sent attempt was dropped or retried")
			}
			f.assertUsage(t, 1, 1)
			assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM telemetry")
			assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM request_context")
			f.Server = reopenScheduleStore(t, f.Server)
			f.assertUsage(t, 1, 1)
			requireCode(t, req(t, f.Server, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
			id, err := f.Server.createTestSession(context.Background(), f.Scope)
			if err != nil {
				t.Fatal(err)
			}
			denied, code := f.Server.execute(f.context(), inferRequest{SessionID: id, Prompt: "Do not refund a sent attempt", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 1 {
				t.Fatal("sent failure/cancellation refunded an admitted attempt")
			}
			f.assertUsage(t, 1, 1)
		})
	}
}

func TestResearchConversationWorkerRecoversWithoutExtraBudgetAndUsesNewEvidence(t *testing.T) {
	f := newResearchAdmissionFixture(t, 2, 1)
	s := f.Server
	if _, err := s.Store.DB.Exec("CREATE TRIGGER admission_failure BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'injected history failure'); END"); err != nil {
		t.Fatal(err)
	}
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 1 {
		t.Fatalf("only catalog should reach the provider: %d", f.Calls.Load())
	}
	f.assertRunUsage(t, 1, 0)
	assertSQLInt(t, s, 0, "SELECT COALESCE(SUM(http_requests),0) FROM research_task_usage WHERE task_id=?", f.TaskID)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM messages")
	var oldReport authenticityReport
	var raw string
	if err := s.Store.DB.QueryRow("SELECT report_json FROM authenticity_reports ORDER BY rowid DESC LIMIT 1").Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal([]byte(raw), &oldReport) != nil || oldReport.Target.Usable != 0 {
		t.Fatal("initial local failure report missing")
	}
	if _, err := s.Store.DB.Exec("DROP TRIGGER admission_failure"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME", "retry_failed": true, "additional_budget": 0, "additional_probes": 0}), 200)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatalf("recovery should send one explicit inference: %d", f.Calls.Load())
	}
	f.assertRunUsage(t, 2, 1)
	assertSQLInt(t, s, 1, "SELECT http_requests FROM research_task_usage WHERE task_id=?", f.TaskID)
	assertSQLInt(t, s, 1, "SELECT inference_requests FROM research_task_usage WHERE task_id=?", f.TaskID)
	var status, result string
	if err := s.Store.DB.QueryRow("SELECT status,result_json FROM research_tasks WHERE id=?", f.TaskID).Scan(&status, &result); err != nil {
		t.Fatal(err)
	}
	var taskResult map[string]any
	if json.Unmarshal([]byte(result), &taskResult) != nil || status != "SUCCESS" {
		t.Fatalf("worker did not recover: %s", status)
	}
	reportID := str(taskResult["authenticity_report_id"])
	if reportID == "" {
		t.Fatal("recovery omitted its saved report")
	}
	var current authenticityReport
	if err := s.Store.DB.QueryRow("SELECT report_json FROM authenticity_reports WHERE id=?", reportID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal([]byte(raw), &current) != nil || current.Target.Usable != 1 || current.ID == oldReport.ID {
		t.Fatalf("successful retry borrowed the earlier empty report: usable=%d same_id=%t", current.Target.Usable, current.ID == oldReport.ID)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM authenticity_reports")
}
