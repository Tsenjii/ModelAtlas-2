package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func privateScheduleFixture(t *testing.T, policy schedulePrivatePolicy, extraKey bool) *researchAdmissionFixture {
	t.Helper()
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "CANCEL"}), 200)
	selections := []Selection{f.Scope}
	if extraKey {
		selections = append(selections, addPoolKey(t, s, f.Scope, "a", "second"))
	}
	s.runPending(context.Background())
	f.Calls.Store(0)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "private", "name": "Private model research", "interval_minutes": 15, "daily_http_limit": 20, "options": ResearchOptions{Selections: selections, MinimalInference: true, Budget: 10, ProbeBudget: 2}, "private": policy}), 200)
	return f
}

func enablePrivateSchedule(t *testing.T, s *Server, id string) {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/schedules/review?id="+id, nil)
	requireCode(t, w, 200)
	var review struct {
		Revision    int64  `json:"revision"`
		Fingerprint string `json:"fingerprint"`
	}
	if json.Unmarshal(w.Body.Bytes(), &review) != nil || len(review.Fingerprint) != 64 {
		t.Fatal("invalid private scope review")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": id, "revision": review.Revision, "action": "ENABLE", "confirm_private": true, "expected_private_fingerprint": review.Fingerprint}), 200)
}

func TestResearchPrivateScheduleFormalEntry(t *testing.T) {
	f := newResearchAdmissionFixture(t, 10, 5)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/schedules", map[string]any{
		"id": "private-schedule", "name": "Explicit model research", "interval_minutes": 15, "daily_http_limit": 10,
		"options": ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 5, ProbeBudget: 1},
		"private": map[string]any{"daily_inferences": 1, "daily_tokens": nil, "daily_usd": nil, "resume_after_reset": false},
	}), 200)
}

func TestResearchPrivateScheduleExplicitApprovalAndDailyStop(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 1}, true)
	s := f.Server
	s.workerTick(context.Background())
	if f.Calls.Load() != 0 {
		t.Fatal("saved private schedule ran without approval")
	}
	v := scheduleForTest(t, s, "private")
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": v.ID, "revision": v.Revision, "action": "ENABLE"}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": v.ID, "revision": v.Revision, "action": "ENABLE", "confirm_private": true}), 409)
	enablePrivateSchedule(t, s, v.ID)
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	v = scheduleForTest(t, s, v.ID)
	if f.Calls.Load() != 3 || v.RunStatus != "BUDGET_EXHAUSTED" || v.TodayUsed != 3 || v.RunProbes != 1 || v.AutoResume || v.State != "INFERENCE_LIMIT" {
		t.Fatalf("private daily policy: HTTP=%d %#v", f.Calls.Load(), v)
	}
	if v.PrivateUsage["requests"].(float64) != 1 {
		t.Fatal("private policy lost request use", v.PrivateUsage)
	}
	assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=? AND status='BUDGET_EXHAUSTED'", v.LastRunID)
	for i := 0; i < 4; i++ {
		s.workerTick(context.Background())
	}
	if f.Calls.Load() != 3 {
		t.Fatal("private budget spun at worker tick")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_private_approvals")
}

func TestResearchPrivateScheduleReviewRejectsRotationRaceAndPreservesScope(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 5}, false)
	s := f.Server
	w := req(t, s, "GET", "/api/v1/research/schedules/review?id=private", nil)
	requireCode(t, w, 200)
	var old map[string]any
	json.Unmarshal(w.Body.Bytes(), &old)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "top-secret-example-key", "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": "private", "revision": old["revision"], "action": "ENABLE", "confirm_private": true, "expected_private_fingerprint": old["fingerprint"]}), 409)
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "top-secret-example-key", "confirm": true}), 200)
	s.researchScheduleTick(context.Background())
	v := scheduleForTest(t, s, "private")
	if v.Enabled || v.State != "NEEDS_REVIEW" || f.Calls.Load() != 0 {
		t.Fatal("changed key still ran", v)
	}
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatal("fresh explicit review did not run once", f.Calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_private_approvals")
}

func TestResearchPrivateScheduleUTCRecoveryIsSeparateOptIn(t *testing.T) {
	for _, automatic := range []bool{false, true} {
		t.Run(fmt.Sprint(automatic), func(t *testing.T) {
			f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 1, ResumeAfterReset: automatic}, true)
			s := f.Server
			clock := time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC)
			s.scheduleClock = func() time.Time { return clock }
			enablePrivateSchedule(t, s, "private")
			s.researchScheduleTick(context.Background())
			s.runResearchPending(context.Background())
			v := scheduleForTest(t, s, "private")
			if v.AutoResume != automatic || f.Calls.Load() != 3 {
				t.Fatal("wrong private daily intent", v)
			}
			for i := 0; i < 3; i++ {
				s.researchScheduleTick(context.Background())
				s.runResearchPending(context.Background())
			}
			if f.Calls.Load() != 3 {
				t.Fatal("private daily wait spun")
			}
			clock = clock.Add(24 * time.Hour)
			s.researchScheduleTick(context.Background())
			s.runResearchPending(context.Background())
			if automatic {
				if f.Calls.Load() != 4 || scheduleForTest(t, s, "private").RunStatus != "COMPLETED" {
					t.Fatal("explicit automatic daily resume failed", f.Calls.Load())
				}
			} else if f.Calls.Load() != 3 {
				t.Fatal("UTC date granted unapproved automatic resume")
			}
		})
	}
}

func TestResearchPrivateScheduleInterruptedInferenceNeverAutoReplays(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 5}, false)
	s := f.Server
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	v := scheduleForTest(t, s, "private")
	if f.Calls.Load() != 2 {
		t.Fatal("fixture inference incomplete")
	}
	if _, err := s.Store.DB.Exec("UPDATE research_runs SET status='RUNNING' WHERE id=?", v.LastRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Store.DB.Exec("UPDATE research_tasks SET status='RUNNING' WHERE run_id=? AND kind='MINIMAL_INFERENCE'", v.LastRunID); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatal("interrupted inference was resent")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE' AND status='FAILED' AND json_extract(result_json,'$.reason')='interrupted private inference is never automatically replayed'", v.LastRunID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
}

func TestResearchPrivateScheduleAtomicFailuresAndResourceCaps(t *testing.T) {
	for _, metric := range []string{"TOKENS", "USD", "DB"} {
		t.Run(metric, func(t *testing.T) {
			policy := schedulePrivatePolicy{DailyInferences: 10}
			tokens, usd := int64(0), "0"
			if metric == "TOKENS" {
				policy.DailyTokens = &tokens
			}
			if metric == "USD" {
				policy.DailyUSD = &usd
			}
			f := privateScheduleFixture(t, policy, false)
			s := f.Server
			enablePrivateSchedule(t, s, "private")
			s.researchScheduleTick(context.Background())
			v := scheduleForTest(t, s, "private")
			if metric == "DB" {
				if _, err := s.Store.DB.Exec("CREATE TRIGGER private_admission_fault BEFORE INSERT ON research_schedule_inference_admissions BEGIN SELECT RAISE(ABORT,'injected private admission fault'); END"); err != nil {
					t.Fatal(err)
				}
			}
			var task string
			if err := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", v.LastRunID).Scan(&task); err != nil {
				t.Fatal(err)
			}
			ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, v.LastRunID), researchTaskBudgetKey{}, task)
			r, code := inferBudgetAttempt(t, s, ctx, f.Scope, "CRAWLER_VERIFY")
			limit, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError)
			if code != 429 || !ok || limit.Scope != "SCHEDULE" || metric != "DB" && limit.Metric != metric || f.Calls.Load() != 0 {
				t.Fatal("private admission cap", code, r.Outcome, limit)
			}
			for _, table := range []string{"research_inference_admissions", "research_resource_admissions", "research_schedule_inference_admissions", "telemetry", "messages"} {
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+table)
			}
			assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", v.LastRunID)
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_schedule_daily")
		})
	}
}

func TestResearchPrivateScheduleLocalAuthorityAndMigration(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 5}, false)
	s := f.Server
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	peerTLS := testServer(t)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	workspacePairForTest(t, s, peerTLS, host, false)
	workspaceRunForTest(t, peerTLS)
	assertSQLInt(t, peerTLS, 0, "SELECT COUNT(*) FROM research_schedules")
	assertSQLInt(t, peerTLS, 0, "SELECT COUNT(*) FROM research_schedule_private_approvals")
	assertSQLInt(t, peerTLS, 0, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
	prior := f.Calls.Load()
	peerTLS.runResearchPending(context.Background())
	if f.Calls.Load() != prior {
		t.Fatal("synchronized private history executed on peer")
	}
	for _, name := range []string{"research_schedule_private_approvals", "research_schedule_inference_admissions"} {
		if workspaceAllowed(name) {
			t.Fatal("private execution authority entered sync")
		}
	}
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	var doc BackupDocument
	json.Unmarshal(backup.Body.Bytes(), &doc)
	peer := testServer(t)
	requireCode(t, req(t, peer, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": doc}), 201)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_schedules")
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_schedule_private_approvals")
	if _, err := s.Store.DB.Exec("DROP TABLE research_schedule_inference_admissions;DROP TABLE research_schedule_private_approvals;PRAGMA user_version=29"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	s.researchScheduleTick(context.Background())
	if scheduleForTest(t, s, "private").Enabled {
		t.Fatal("migration invented missing approval")
	}
	files, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-29-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("private upgrade lacks original consistent snapshot", err)
	}
}

func TestResearchPrivateScheduleDisableCancelsFlightAndKeepsAdmission(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 5}, false)
	s := f.Server
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	f.Mode.Store(3)
	defer close(f.Release) // Allow the fixture's late reply after client cancellation.
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(ctx) }()
	defer func() {
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("private flight did not stop")
		}
	}()
	select {
	case <-f.Started:
	case <-time.After(5 * time.Second):
		t.Fatal("private TLS inference did not start")
	}
	controlScheduleForTest(t, s, "private", "DISABLE")
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("disable did not cancel private TLS flight")
	}
	if f.Calls.Load() != 2 {
		t.Fatal("unexpected private flight request count", f.Calls.Load())
	}
	v := scheduleForTest(t, s, "private")
	if v.Enabled || v.AutoResume || v.PrivateUsage["requests"].(float64) != 1 || v.PrivateUsage["accounted_tokens"] != "256" {
		t.Fatal("disable erased admission", v.PrivateUsage)
	}
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	if f.Calls.Load() != 2 || scheduleForTest(t, s, "private").Enabled {
		t.Fatal("restart enabled disabled private work")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
}

func TestResearchPrivateScheduleApprovalHistoryIsPagedAndAtomic(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 5}, false)
	s := f.Server
	if _, err := s.Store.DB.Exec("CREATE TRIGGER private_approval_fault BEFORE INSERT ON research_schedule_private_approvals BEGIN SELECT RAISE(ABORT,'injected approval failure'); END"); err != nil {
		t.Fatal(err)
	}
	w := req(t, s, "GET", "/api/v1/research/schedules/review?id=private", nil)
	requireCode(t, w, 200)
	var review map[string]any
	json.Unmarshal(w.Body.Bytes(), &review)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": "private", "revision": review["revision"], "action": "ENABLE", "confirm_private": true, "expected_private_fingerprint": review["fingerprint"]}), 500)
	if v := scheduleForTest(t, s, "private"); v.Enabled || v.Revision != 1 {
		t.Fatal("partial private approval")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_schedule_private_approvals")
	s.Store.DB.Exec("DROP TRIGGER private_approval_fault")
	for i := 0; i < 53; i++ {
		enablePrivateSchedule(t, s, "private")
	}
	var page struct {
		History []json.RawMessage `json:"history"`
		Next    int64             `json:"next_before"`
	}
	w = req(t, s, "GET", "/api/v1/research/schedules/private-history?id=private", nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.History) != 50 || page.Next == 0 {
		t.Fatal("unbounded private approval history")
	}
	w = req(t, s, "GET", fmt.Sprintf("/api/v1/research/schedules/private-history?id=private&before=%d", page.Next), nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.History) != 3 || page.Next != 0 {
		t.Fatal("private approval page lost history")
	}
	if f.Calls.Load() != 0 {
		t.Fatal("approval reads caused upstream HTTP")
	}
}

func TestResearchPrivateScheduleSharesOneUTCAdmissionDay(t *testing.T) {
	f := privateScheduleFixture(t, schedulePrivatePolicy{DailyInferences: 1}, false)
	s := f.Server
	before := time.Date(2026, 10, 24, 23, 59, 59, 999000000, time.UTC)
	after := before.Add(time.Millisecond)
	s.scheduleClock = func() time.Time { return before }
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	v := scheduleForTest(t, s, "private")
	var task string
	s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", v.LastRunID).Scan(&task)
	ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, v.LastRunID), researchTaskBudgetKey{}, task)
	if r, code := inferBudgetAttempt(t, s, ctx, f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("initial admission failed", code, r.Outcome)
	}
	calls := 0
	s.scheduleClock = func() time.Time {
		calls++
		if calls == 1 {
			return before
		}
		return after
	}
	r, code := inferBudgetAttempt(t, s, ctx, f.Scope, "CRAWLER_VERIFY")
	b, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || b.Scope != "SCHEDULE" || b.Day != "2026-10-24" || f.Calls.Load() != 1 {
		t.Fatal("one request split HTTP/inference across UTC days", code, r.Outcome, b, f.Calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT used FROM research_schedule_daily WHERE scope='schedule:private' AND day='2026-10-24'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
}

func TestResearchPrivateScheduleCannotBorrowAccountPATOrAnotherKey(t *testing.T) {
	var pats, accounts, inferences atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer account-pat-secret" {
			pats.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/models":
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000}}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[{"model_name":"m","model_price":0.3}],"pricing_version":"fixture"}`))
		case "/api/usage/token/":
			w.Write([]byte(`{"code":true,"data":{"total_available":12345,"unlimited_quota":false}}`))
		case "/api/user/self":
			accounts.Add(1)
			w.Write([]byte(`{"success":true,"data":{"quota":12345}}`))
		case "/v1/chat/completions":
			inferences.Add(1)
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Observed\"}}]}\n\ndata: [DONE]\n\n"))
		default:
			w.Write([]byte(`{"success":true,"data":{}}`))
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	scope := seed(t, s, up.URL+"/v1")
	sessionForTest(t, s, scope)
	s.runPending(context.Background())
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "a", ID: "pat", Label: "Account", Role: "ACCOUNT_PAT", Secret: "account-pat-secret"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": ProviderConfig{Adapter: "NEW_API", AuthMode: "BEARER"}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "private", "name": "Role bound", "interval_minutes": 15, "daily_http_limit": 100, "options": ResearchOptions{Selections: []Selection{scope}, MinimalInference: true, Budget: 40, ProbeBudget: 1}, "private": schedulePrivatePolicy{DailyInferences: 5}}), 200)
	enablePrivateSchedule(t, s, "private")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	if pats.Load() != 0 || accounts.Load() != 0 || inferences.Load() != 1 {
		t.Fatal("private schedule borrowed management credential", pats.Load(), accounts.Load(), inferences.Load())
	}
	ctx := context.WithValue(context.Background(), privateScheduleCredentialKey{}, scope)
	if _, _, err := s.accountPAT(ctx, NewApiQuery{ProviderID: "p", AccountID: "a", CredentialID: "pat"}); err == nil {
		t.Fatal("private context decoded account PAT")
	}
	if _, _, err := s.endpointAuth(ctx, "p", "a", "other"); err == nil {
		t.Fatal("private context borrowed another inference key")
	}
}
