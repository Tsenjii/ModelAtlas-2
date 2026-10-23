package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type inferenceBudgetView struct {
	researchInferencePolicy
	Day               string                        `json:"day"`
	TodayUsed         int                           `json:"today_used"`
	ResetAt           int64                         `json:"reset_at"`
	Credentials       []researchInferenceCredential `json:"credentials"`
	NextCursor        string                        `json:"next_cursor"`
	TokenUsage        *int                          `json:"token_usage"`
	CurrencyCost      *string                       `json:"currency_cost"`
	ProviderRemaining *int                          `json:"provider_remaining"`
}

func inferenceBudgetForTest(t *testing.T, s *Server) inferenceBudgetView {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/inference-budget", nil)
	requireCode(t, w, 200)
	var v inferenceBudgetView
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil || v.Revision < 1 {
		t.Fatal("invalid local inference policy", err)
	}
	if v.CurrencyCost != nil || v.TokenUsage != nil || v.ProviderRemaining != nil || strings.Contains(w.Body.String(), "top-secret-example-key") {
		t.Fatal("unknown usage became zero or raw credential leaked")
	}
	return v
}

func setInferencePolicyForTest(t *testing.T, s *Server, all, credential int) {
	t.Helper()
	v := inferenceBudgetForTest(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", map[string]any{"action": "POLICY", "revision": v.Revision, "daily_limit": all, "default_credential_limit": credential}), 200)
}

func setInferenceCredentialForTest(t *testing.T, s *Server, scope Selection, limit int, reset bool) {
	t.Helper()
	action := "CREDENTIAL"
	if reset {
		action = "RESET"
	}
	v := inferenceBudgetForTest(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", map[string]any{"action": action, "revision": v.Revision, "provider_id": scope.ProviderID, "account_id": scope.AccountID, "credential_id": scope.CredentialID, "daily_limit": limit}), 200)
}

func inferBudgetAttempt(t *testing.T, s *Server, ctx context.Context, scope Selection, source string) (InferenceResult, int) {
	t.Helper()
	id, err := s.createTestSession(context.Background(), scope)
	if err != nil {
		t.Fatal(err)
	}
	return s.execute(ctx, inferRequest{SessionID: id, Prompt: "One explicit observation", MaxTokens: 16}, source, func(string, any) error { return nil })
}

func TestResearchInferenceDailyBudgetFormalSettingsEntry(t *testing.T) {
	f := newResearchAdmissionFixture(t, 5, 3)
	w := req(t, f.Server, "GET", "/api/v1/research/inference-budget", nil)
	requireCode(t, w, 200)
	var policy struct {
		Revision int `json:"revision"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &policy); err != nil || policy.Revision < 1 {
		t.Fatal("daily inference policy is unavailable")
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/inference-budget", map[string]any{"action": "POLICY", "revision": policy.Revision, "daily_limit": 1, "default_credential_limit": 1}), 200)
	for i := 0; i < 2; i++ {
		if i > 0 {
			f.RunID = researchRunID(t, f.Server, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 5, ProbeBudget: 3})
			if err := f.Server.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", f.RunID).Scan(&f.TaskID); err != nil {
				t.Fatal(err)
			}
			id, err := f.Server.createTestSession(context.Background(), f.Scope)
			if err != nil {
				t.Fatal(err)
			}
			f.SessionID = id
		}
		result, code := f.Server.execute(f.context(), inferRequest{SessionID: f.SessionID, Prompt: "One locally authorized observation", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
		if i == 0 && (code != 0 || result.Outcome != "SUCCESS") {
			t.Fatalf("first authorized request: %d %s", code, result.Outcome)
		}
		if i == 1 && (code != 429 || result.Outcome != "RESEARCH_BUDGET_EXHAUSTED") {
			t.Fatalf("new run bypassed the daily cap: %d %s", code, result.Outcome)
		}
	}
	if f.Calls.Load() != 1 {
		t.Fatalf("daily cap allowed %d real HTTP requests", f.Calls.Load())
	}
	f.assertUsage(t, 0, 0)
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM telemetry")
}

func TestResearchInferenceDailyBudgetStableScopeRotationAndUTCDay(t *testing.T) {
	f := newResearchAdmissionFixture(t, 20, 10)
	s := f.Server
	instant := time.Date(2026, 10, 23, 23, 59, 59, 0, time.UTC)
	s.scheduleClock = func() time.Time { return instant }
	setInferencePolicyForTest(t, s, 10, 1)
	first, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 0 || first.Outcome != "SUCCESS" {
		t.Fatal("first observation failed", code, first.Outcome)
	}
	// A different material version must still use this stable credential's count.
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "synthetic-budget-rotated-key", "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "other", DisplayName: "Other"}), 201)
	changed := f.Scope
	changed.ModelID, changed.Endpoint = "other", "RESPONSES"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", changed), 201)
	for _, scope := range []Selection{f.Scope, changed} {
		result, code := inferBudgetAttempt(t, s, f.context(), scope, "CRAWLER_VERIFY")
		limit, ok := result.Details["research_inference_budget"].(*researchInferenceBudgetError)
		if code != 429 || !ok || limit.Scope != "CREDENTIAL" || limit.Used != 1 || limit.Limit != 1 || limit.ResetAt != instant.Add(time.Second).UnixMilli() {
			t.Fatalf("scope change escaped stable daily cap: %d %s %#v", code, result.Outcome, limit)
		}
	}
	if _, err := s.Store.DB.Exec("UPDATE credential_context SET group_id='budget-group' WHERE provider_id='p' AND account_id='a' AND credential_id='c'"); err != nil {
		t.Fatal(err)
	}
	grouped := changed
	grouped.Group = "budget-group"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", grouped), 201)
	deniedGroup, groupCode := inferBudgetAttempt(t, s, f.context(), grouped, "CRAWLER_VERIFY")
	if limit, ok := deniedGroup.Details["research_inference_budget"].(*researchInferenceBudgetError); groupCode != 429 || !ok || limit.Scope != "CREDENTIAL" {
		t.Fatal("new group bypassed shared credential allowance", groupCode, deniedGroup.Outcome)
	}
	if _, err := s.Store.DB.Exec("UPDATE credential_context SET group_id='' WHERE provider_id='p' AND account_id='a' AND credential_id='c'"); err != nil {
		t.Fatal(err)
	}
	// Restore the synthetic fixture's expected wire material, without refunding.
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "top-secret-example-key", "confirm": true}), 200)
	for _, account := range []string{"a", "b"} {
		id := "another-key"
		if account == "b" {
			id = "c" // same credential ID in a different account remains separate.
			requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ProviderID: "p", ID: account, Label: "Other account"}), 201)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: account, ID: id, Label: id, Secret: "top-secret-example-key"}), 201)
		scope := f.Scope
		scope.AccountID, scope.CredentialID = account, id
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
		result, code := inferBudgetAttempt(t, s, f.context(), scope, "CRAWLER_VERIFY")
		if code != 0 || result.Outcome != "SUCCESS" {
			t.Fatal("unrelated credential borrowed another scope's cap", code, result.Outcome)
		}
	}
	view := inferenceBudgetForTest(t, s)
	if view.TodayUsed != 3 || len(view.Credentials) != 3 || view.Day != "2026-10-23" {
		t.Fatalf("scope accounting: %#v", view)
	}
	setInferenceCredentialForTest(t, s, f.Scope, 0, false)
	setInferenceCredentialForTest(t, s, f.Scope, 0, true)
	if inferenceBudgetForTest(t, s).TodayUsed != 3 {
		t.Fatal("editing/resetting override erased counts")
	}
	f.Server = reopenScheduleStore(t, s)
	s = f.Server
	if inferenceBudgetForTest(t, s).TodayUsed != 3 {
		t.Fatal("restart erased daily reservations")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
	denied, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 3 {
		t.Fatal("restart refunded a previous key version")
	}
	instant = instant.Add(time.Second)
	if v := inferenceBudgetForTest(t, s); v.TodayUsed != 0 || v.Day != "2026-10-24" {
		t.Fatal("not an exact UTC reset", v)
	}
	result, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 0 || result.Outcome != "SUCCESS" || f.Calls.Load() != 4 {
		t.Fatal("new UTC day did not preserve remaining per-run authorization", code, result.Outcome)
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions WHERE day='2026-10-23'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions WHERE day='2026-10-24' AND credential_version=3")
	assertSQLInt(t, s, 5, "SELECT COUNT(*) FROM my_models")
}

func TestResearchInferenceDailyBudgetConcurrentReservationsAcrossRuns(t *testing.T) {
	for _, bound := range []string{"ENGINE", "CREDENTIAL"} {
		t.Run(bound, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 20, 10)
			f.Mode.Store(3)
			if bound == "ENGINE" {
				setInferencePolicyForTest(t, f.Server, 3, 20)
			} else {
				setInferencePolicyForTest(t, f.Server, 100, 3)
			}
			contexts := []context.Context{f.context()}
			for i := 0; i < 3; i++ {
				run := researchRunID(t, f.Server, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 20, ProbeBudget: 10})
				var task string
				if err := f.Server.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task); err != nil {
					t.Fatal(err)
				}
				contexts = append(contexts, context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task))
			}
			ids := make([]string, 20)
			for i := range ids {
				var err error
				ids[i], err = f.Server.createTestSession(context.Background(), f.Scope)
				if err != nil {
					t.Fatal(err)
				}
			}
			var wg sync.WaitGroup
			var success, denied atomic.Int32
			waiting := make(chan struct{}, 20)
			slots := make(chan struct{}, f.Server.flightLimit())
			release := sync.OnceFunc(func() { close(f.Release) })
			defer func() { release(); wg.Wait() }()
			for i, id := range ids {
				wg.Add(1)
				go func(i int, id string) {
					defer wg.Done()
					slots <- struct{}{}
					defer func() { <-slots }()
					result, code := f.Server.execute(contexts[i%len(contexts)], inferRequest{SessionID: id, Prompt: "Concurrent daily authorization", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
					if code == 0 && result.Outcome == "SUCCESS" {
						success.Add(1)
					} else if code == 429 && result.Outcome == "RESEARCH_BUDGET_EXHAUSTED" {
						limit, ok := result.Details["research_inference_budget"].(*researchInferenceBudgetError)
						if !ok || limit.Scope != bound || limit.Used != 3 {
							t.Errorf("wrong daily admission reason: %#v", limit)
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
					t.Fatal("daily refusals did not arrive while the three admitted TLS requests were pending")
				}
			}
			for i := 0; i < 3; i++ {
				select {
				case <-f.Started:
				case <-time.After(5 * time.Second):
					t.Fatal("admitted TLS request missing")
				}
			}
			assertSQLInt(t, f.Server, 3, "SELECT COUNT(*) FROM research_inference_admissions")
			assertSQLInt(t, f.Server, 3, "SELECT SUM(used) FROM research_run_options")
			assertSQLInt(t, f.Server, 3, "SELECT SUM(probes_used) FROM research_run_options")
			assertSQLInt(t, f.Server, 3, "SELECT COUNT(*) FROM telemetry WHERE outcome='RUNNING'")
			release()
			wg.Wait()
			if success.Load() != 3 || denied.Load() != 17 || f.Calls.Load() != 3 {
				t.Fatalf("daily concurrency overspent: admitted=%d denied=%d wire=%d", success.Load(), denied.Load(), f.Calls.Load())
			}
			assertSQLInt(t, f.Server, 6, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, f.Server, 3, "SELECT SUM(inference_requests) FROM research_task_usage")
		})
	}
}

func TestResearchInferenceDailyBudgetAtomicFaultsAndRevisionProtection(t *testing.T) {
	for _, table := range []string{"research_inference_admissions", "messages", "telemetry"} {
		t.Run(table, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 3, 2)
			if _, err := f.Server.Store.DB.Exec("CREATE TRIGGER daily_admission_fault BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'injected daily admission failure'); END"); err != nil {
				t.Fatal(err)
			}
			result, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code == 0 || result.Outcome == "SUCCESS" || f.Calls.Load() != 0 {
				t.Fatal("local failure sent HTTP")
			}
			f.assertUsage(t, 0, 0)
			for _, table := range []string{"research_inference_admissions", "messages", "telemetry", "request_context"} {
				assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM "+table)
			}
			if _, err := f.Server.Store.DB.Exec("DROP TRIGGER daily_admission_fault"); err != nil {
				t.Fatal(err)
			}
			result, code = inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code != 0 || result.Outcome != "SUCCESS" || inferenceBudgetForTest(t, f.Server).TodayUsed != 1 {
				t.Fatal("local failure consumed daily authorization")
			}
		})
	}
	f := newResearchAdmissionFixture(t, 3, 2)
	s := f.Server
	initial := inferenceBudgetForTest(t, s)
	setInferencePolicyForTest(t, s, 1, 0)
	requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", map[string]any{"action": "POLICY", "revision": initial.Revision, "daily_limit": 2000, "default_credential_limit": 2000}), 409)
	if _, err := s.Store.DB.Exec("CREATE TRIGGER daily_policy_fault BEFORE INSERT ON research_inference_policy_history BEGIN SELECT RAISE(ABORT,'injected policy journal failure'); END"); err != nil {
		t.Fatal(err)
	}
	v := inferenceBudgetForTest(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", map[string]any{"action": "CREDENTIAL", "revision": v.Revision, "provider_id": "p", "account_id": "a", "credential_id": "c", "daily_limit": 1}), 500)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_limits")
	if inferenceBudgetForTest(t, s).Revision != v.Revision {
		t.Fatal("failed policy journal partially committed")
	}
	if _, err := s.Store.DB.Exec("DROP TRIGGER daily_policy_fault"); err != nil {
		t.Fatal(err)
	}
	for _, body := range []map[string]any{
		{"action": "POLICY", "revision": v.Revision, "daily_limit": 1},
		{"action": "POLICY", "revision": v.Revision, "daily_limit": -1, "default_credential_limit": 1},
		{"action": "POLICY", "revision": v.Revision, "daily_limit": 1, "default_credential_limit": 2001},
		{"action": "POLICY", "revision": v.Revision, "daily_limit": 1, "default_credential_limit": 1, "credential_id": "not-a-scope"},
		{"action": "CREDENTIAL", "revision": v.Revision, "daily_limit": 1, "provider_id": "p", "account_id": "a"},
	} {
		requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", body), 400)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/inference-budget", map[string]any{"action": "RESET", "revision": v.Revision, "provider_id": "p", "account_id": "a", "credential_id": "unknown"}), 422)
	setInferenceCredentialForTest(t, s, f.Scope, 1, false)
	result, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("explicit override failed", code, result.Outcome)
	}
	setInferencePolicyForTest(t, s, 0, 0)
	result, code = inferBudgetAttempt(t, s, context.Background(), f.Scope, "PLAYGROUND")
	if code != 0 || result.Outcome != "SUCCESS" || inferenceBudgetForTest(t, s).TodayUsed != 1 {
		t.Fatal("daily research limit incorrectly blocked/counts normal conversation")
	}
	// Storage failure is never interpreted as an unused/free budget.
	if _, err := s.Store.DB.Exec("DELETE FROM research_inference_policy"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/inference-budget", nil), 500)
	result, code = inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 429 || result.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 2 {
		t.Fatal("missing policy became permission")
	}
	f.assertUsage(t, 1, 1)
}

func TestResearchInferenceDailyBudgetWorkerWaitExplicitResumeAndTrafficReuse(t *testing.T) {
	f := newResearchAdmissionFixture(t, 8, 2)
	s := f.Server
	setInferencePolicyForTest(t, s, 0, 0)
	s.runResearchPending(context.Background())
	checkWait := func() {
		t.Helper()
		var status, raw string
		var attempts int
		if err := s.Store.DB.QueryRow("SELECT status,attempts,result_json FROM research_tasks WHERE id=?", f.TaskID).Scan(&status, &attempts, &raw); err != nil {
			t.Fatal(err)
		}
		var result struct {
			Limit *researchInferenceBudgetError `json:"inference_budget"`
		}
		if json.Unmarshal([]byte(raw), &result) != nil || status != "BUDGET_EXHAUSTED" || attempts != 0 || result.Limit == nil || result.Limit.Scope != "ENGINE" {
			t.Fatalf("worker lost non-wire budget state %s attempts%d %s", status, attempts, raw)
		}
		if f.Calls.Load() != 1 {
			t.Fatal("daily cap sent inference or repeated catalog")
		}
	}
	checkWait()
	f.assertRunUsage(t, 1, 0)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	checkWait()
	setInferencePolicyForTest(t, s, 1, 1)
	s.runResearchPending(context.Background())
	checkWait() // a policy write is not resume consent.
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatal("explicit resume did not send exactly one inference")
	}
	f.assertRunUsage(t, 2, 1)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT attempts FROM research_tasks WHERE id=?", f.TaskID)
	setInferencePolicyForTest(t, s, 0, 0)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 5, ProbeBudget: 1})
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 3 {
		t.Fatal("existing traffic was probed again despite zero daily authorization")
	}
	assertSQLInt(t, s, 0, "SELECT probes_used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE' AND status='SUCCESS' AND json_extract(result_json,'$.reused_local_traffic')=1", run)
}

func TestResearchInferenceDailyBudgetSentFailureRetainedAcrossRestart(t *testing.T) {
	for _, mode := range []int32{1, 2} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 5, 3)
			f.Mode.Store(mode)
			setInferencePolicyForTest(t, f.Server, 1, 1)
			ctx, cancel := context.WithCancel(f.context())
			defer cancel()
			done := make(chan InferenceResult, 1)
			go func() {
				result, _ := f.Server.execute(ctx, inferRequest{SessionID: f.SessionID, Prompt: "Keep the admitted attempt", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
				done <- result
			}()
			if mode == 2 {
				select {
				case <-f.Started:
					cancel()
				case <-time.After(3 * time.Second):
					t.Fatal("TLS request did not start")
				}
			}
			var result InferenceResult
			select {
			case result = <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("request did not finish")
			}
			if mode == 1 && result.Status != 503 || mode == 2 && result.Outcome != "CANCELLED" {
				t.Fatal("wire failure/cancellation lost", result.Outcome)
			}
			f.Server = reopenScheduleStore(t, f.Server)
			requireCode(t, req(t, f.Server, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
			denied, code := inferBudgetAttempt(t, f.Server, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code != 429 || denied.Outcome != "RESEARCH_BUDGET_EXHAUSTED" || f.Calls.Load() != 1 || inferenceBudgetForTest(t, f.Server).TodayUsed != 1 {
				t.Fatal("failed or cancelled request refunded daily reservation")
			}
			f.assertUsage(t, 1, 1)
		})
	}
}

func TestResearchInferenceDailyBudgetLocalOnlySyncBackupAndMigration(t *testing.T) {
	f := newResearchAdmissionFixture(t, 5, 3)
	hub := f.Server
	setInferencePolicyForTest(t, hub, 1, 1)
	setInferenceCredentialForTest(t, hub, f.Scope, 1, false)
	result, code := inferBudgetAttempt(t, hub, f.context(), f.Scope, "CRAWLER_VERIFY")
	if code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("fixture failed")
	}
	peer := testServer(t)
	setInferencePolicyForTest(t, peer, 17, 8)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	for _, table := range []string{"research_inference_limits", "research_inference_admissions"} {
		assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM "+table)
	}
	if v := inferenceBudgetForTest(t, peer); v.DailyLimit != 17 || v.DefaultCredentialLimit != 8 || v.TodayUsed != 0 {
		t.Fatal("sync imported budget authority", v)
	}
	for _, kind := range []string{"research_inference_policy", "research_inference_limits", "research_inference_admissions", "research_inference_policy_history"} {
		if workspaceAllowed(kind) {
			t.Fatal("local budget authority entered sync allowlist", kind)
		}
	}
	backup := req(t, hub, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	if strings.Contains(backup.Body.String(), "research_inference_") {
		t.Fatal("portable backup exported local authority")
	}
	var document BackupDocument
	if json.Unmarshal(backup.Body.Bytes(), &document) != nil {
		t.Fatal("invalid backup")
	}
	restored := testServer(t)
	setInferencePolicyForTest(t, restored, 9, 7)
	requireCode(t, req(t, restored, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	if v := inferenceBudgetForTest(t, restored); v.DailyLimit != 9 || v.DefaultCredentialLimit != 7 || v.TodayUsed != 0 {
		t.Fatal("portable restore replaced local authorization", v)
	}
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	legacy := testServer(t)
	seedAALastGood(t, legacy)
	if _, err := legacy.Store.DB.Exec("DROP TABLE research_inference_policy;DROP TABLE research_inference_limits;DROP TABLE research_inference_admissions;DROP TABLE research_inference_policy_history;PRAGMA user_version=26"); err != nil {
		t.Fatal(err)
	}
	legacy = reopenScheduleStore(t, legacy)
	assertSQLInt(t, legacy, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE id='aa-last-good'")
	v := inferenceBudgetForTest(t, legacy)
	if v.DailyLimit != 100 || v.DefaultCredentialLimit != 20 || v.TodayUsed != 0 || v.Revision != 1 {
		t.Fatal("invalid migration defaults", v)
	}
	files, err := filepath.Glob(filepath.Join(filepath.Dir(legacy.Store.path), "upgrade-backups", fmt.Sprintf("schema-26-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("migration lacks a verified pre-upgrade snapshot", files, err)
	}
}

func TestResearchInferenceDailyBudgetPaginationAndImmutablePolicyHistory(t *testing.T) {
	f := newResearchAdmissionFixture(t, 5, 3)
	s := f.Server
	for i := 0; i < 101; i++ {
		id := fmt.Sprintf("page-key-%03d", i)
		requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "a", ID: id, Label: id, Secret: "synthetic-pagination-key"}), 201)
	}
	first := inferenceBudgetForTest(t, s)
	if len(first.Credentials) != 100 || first.NextCursor == "" {
		t.Fatal("credential list did not bound its page")
	}
	w := req(t, s, "GET", "/api/v1/research/inference-budget?cursor="+first.NextCursor, nil)
	requireCode(t, w, 200)
	var second inferenceBudgetView
	if json.Unmarshal(w.Body.Bytes(), &second) != nil || len(second.Credentials) != 2 || second.NextCursor != "" {
		t.Fatal("credential pagination lost the tail")
	}
	seen := map[researchInferenceScope]bool{}
	for _, v := range append(first.Credentials, second.Credentials...) {
		if seen[v.researchInferenceScope] {
			t.Fatal("cursor repeated a credential")
		}
		seen[v.researchInferenceScope] = true
	}
	for _, query := range []string{"?cursor=bad!", "?history=1&before=-1", "?history=1&before=abc"} {
		requireCode(t, req(t, s, "GET", "/api/v1/research/inference-budget"+query, nil), 400)
	}
	for i := 0; i < 54; i++ {
		setInferencePolicyForTest(t, s, 100+i, 20)
	}
	var page struct {
		History []struct {
			Revision int64 `json:"revision"`
		} `json:"history"`
		NextBefore int64 `json:"next_before"`
	}
	w = req(t, s, "GET", "/api/v1/research/inference-budget?history=1", nil)
	requireCode(t, w, 200)
	if json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.History) != 50 || page.History[0].Revision != 55 || page.NextBefore != 6 {
		t.Fatal("bounded policy history missing", w.Body.String())
	}
	w = req(t, s, "GET", fmt.Sprintf("/api/v1/research/inference-budget?history=1&before=%d", page.NextBefore), nil)
	requireCode(t, w, 200)
	if json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.History) != 5 || page.History[4].Revision != 1 || page.NextBefore != 0 {
		t.Fatal("original policy history disappeared", w.Body.String())
	}
	assertSQLInt(t, s, 55, "SELECT COUNT(*) FROM research_inference_policy_history")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
}
