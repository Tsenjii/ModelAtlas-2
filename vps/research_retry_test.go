package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func retryTaskForTest(t *testing.T, s *Server, run string) ResearchTask {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/tasks?run_id="+run, nil)
	requireCode(t, w, 200)
	var tasks []ResearchTask
	if json.Unmarshal(w.Body.Bytes(), &tasks) != nil || len(tasks) == 0 {
		t.Fatal("missing actual task")
	}
	return tasks[0]
}

func retryForTest(t *testing.T, s *Server, run string) *researchRetryState {
	t.Helper()
	retry := readResearchRetry(retryTaskForTest(t, s, run).Result)
	if retry == nil {
		t.Fatal("missing durable retry journal")
	}
	return retry
}

func TestPublicResearchRetryAfterParsing(t *testing.T) {
	stamp := time.Date(2026, 10, 21, 2, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		header  string
		seconds int64
		long    bool
	}{
		{"120", 120, false}, {"0", 0, false}, {" 90 ", 90, false},
		{stamp.Add(3 * time.Minute).Format(http.TimeFormat), 180, false},
		{stamp.Add(-time.Minute).Format(http.TimeFormat), 0, false},
		{"86400", 86400, false}, {"86401", 0, true},
		{"9999999999999999999999999999999", 0, true},
		{stamp.Add(25 * time.Hour).Format(http.TimeFormat), 0, true},
		{"", 0, false}, {"-1", 0, false}, {"+1", 0, false},
		{"1.5", 0, false}, {"3, 4", 0, false}, {"Bearer must-not-be-persisted", 0, false},
	} {
		t.Run(tc.header, func(t *testing.T) {
			err := publicHTTPFailure(429, http.Header{"Retry-After": {tc.header}}, stamp)
			var failure *researchHTTPFailure
			if !errors.As(err, &failure) || failure.WaitTooLong != tc.long {
				t.Fatalf("incorrect wait: %#v", err)
			}
			if tc.seconds > 0 && failure.NotBefore != stamp.Add(time.Duration(tc.seconds)*time.Second).UnixMilli() {
				t.Fatal("remote lower bound lost")
			}
			if strings.Contains(err.Error(), tc.header) && tc.header == "Bearer must-not-be-persisted" {
				t.Fatal("raw header persisted")
			}
		})
	}
	for _, status := range []int{200, 301, 400, 401, 403, 404, 408, 500, 501} {
		var retry *researchHTTPFailure
		if errors.As(publicHTTPFailure(status, http.Header{}, stamp), &retry) {
			t.Fatal("unexpected implicit retry", status)
		}
	}
}

func retryFixture(t *testing.T, handler http.HandlerFunc) (*Server, *atomic.Int64) {
	t.Helper()
	up := httptest.NewTLSServer(handler)
	t.Cleanup(up.Close)
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	clock := &atomic.Int64{}
	clock.Store(time.Now().UTC().UnixMilli())
	s.scheduleClock = func() time.Time { return time.UnixMilli(clock.Load()) }
	return s, clock
}

func TestPublicResearchRetryTLSBudgetsAndOtherTasksProceed(t *testing.T) {
	var failing, healthy atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("credential sent to public source")
		}
		if r.URL.Path == "/retry" {
			switch failing.Add(1) {
			case 1:
				w.Header().Set("Retry-After", "120")
				http.Error(w, "secret fixture body", 429)
				return
			case 2:
				http.Error(w, "gateway body", 502)
				return
			}
		} else {
			healthy.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"retained-model","rpd":100,"tpd":1000000}]}`))
	})
	sourcesForSchedule(t, s, "retry", "healthy")
	// Unrelated origins must still proceed while provider.example cools down.
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: "healthy", Name: "healthy", Kind: "JSON", URL: "https://healthy.example/healthy", Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data"}}), 200)
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"retry", "healthy"}, Budget: 5})
	s.runResearchPending(context.Background())
	r := retryForTest(t, s, run)
	if failing.Load() != 1 || healthy.Load() != 1 || r.NextAttemptAt != clock.Load()+120000 || len(r.History) != 1 {
		t.Fatalf("no delayed retry/other task blocked: %+v", r)
	}
	assertSQLInt(t, s, 2, "SELECT used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='QUEUED' AND finished_at IS NULL", run)
	for i := 0; i < 8; i++ {
		s.runResearchPending(context.Background())
	}
	clock.Store(r.NextAttemptAt - 1)
	s.runResearchPending(context.Background())
	if failing.Load() != 1 {
		t.Fatal("Retry-After ignored or tight loop")
	}
	clock.Add(1)
	s.runResearchPending(context.Background())
	r = retryForTest(t, s, run)
	if failing.Load() != 2 || len(r.History) != 2 || r.NextAttemptAt < clock.Load()+60000 || r.NextAttemptAt > clock.Load()+65000 {
		t.Fatal("exponential bounded backoff missing")
	}
	clock.Store(r.NextAttemptAt)
	s.runResearchPending(context.Background())
	task := retryTaskForTest(t, s, run)
	r = retryForTest(t, s, run)
	if task.Status != "SUCCESS" || task.Attempts != 3 || r.NextAttemptAt != 0 || len(r.History) != 2 || failing.Load() != 3 {
		t.Fatalf("recovery/history %+v/%+v", task, r)
	}
	assertSQLInt(t, s, 4, "SELECT used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='COMPLETED'", run)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SOURCE_FETCH_FAILURE'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	if strings.Contains(string(task.Result), "secret fixture body") {
		t.Fatal("upstream response body persisted in journal")
	}
}

func TestPublicResearchRetryLimitAndPermanentFailures(t *testing.T) {
	for _, status := range []int{429, 502, 503, 504, 401, 403, 404, 500} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			var calls atomic.Int32
			s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); http.Error(w, "opaque fixture", status) })
			sourcesForSchedule(t, s, "failure")
			run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"failure"}, Budget: 10})
			for i := 0; i < 6; i++ {
				s.runResearchPending(context.Background())
				clock.Add(3600000)
			}
			task := retryTaskForTest(t, s, run)
			want := int32(1)
			if status == 429 || status == 502 || status == 503 || status == 504 {
				want = 3
				r := retryForTest(t, s, run)
				if r.StoppedReason != "ATTEMPT_LIMIT" || len(r.History) != 3 {
					t.Fatal("retry limit/history lost", r)
				}
			}
			if calls.Load() != want || task.Status != "FAILED" || task.Attempts != int(want) {
				t.Fatalf("unbounded or permanent retry: %d/%+v", calls.Load(), task)
			}
			assertSQLInt(t, s, int(want), "SELECT used FROM research_run_options WHERE run_id=?", run)
		})
	}
}

func TestPublicResearchRetryRestartPauseCancelAndRemainingBudget(t *testing.T) {
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "120")
		http.Error(w, "later", 429)
	})
	sourcesForSchedule(t, s, "restart")
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"restart"}, Budget: 1})
	s.runResearchPending(context.Background())
	deadline := retryForTest(t, s, run).NextAttemptAt
	s = reopenScheduleStore(t, s)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 || retryForTest(t, s, run).NextAttemptAt != deadline {
		t.Fatal("restart replayed or reset wait")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME", "additional_budget": 2}), 200)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("manual resume bypassed remote wait")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "PAUSE"}), 200)
	clock.Store(deadline)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("paused task retried")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if calls.Load() != 2 {
		t.Fatal("eligible task did not resume")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "CANCEL"}), 200)
	clock.Add(86400000)
	s.workerTick(context.Background())
	if calls.Load() != 2 || retryTaskForTest(t, s, run).Status != "CANCELLED" || len(retryForTest(t, s, run).History) != 2 {
		t.Fatal("cancel retried or lost history")
	}
	assertSQLInt(t, s, 2, "SELECT used FROM research_run_options WHERE run_id=?", run)
}

func TestPublicResearchRetryScheduleContinuationRetainsAllowance(t *testing.T) {
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "3600")
		http.Error(w, "later", 429)
	})
	sourcesForSchedule(t, s, "continued")
	saveScheduleForTest(t, s, "retry-chain", ResearchOptions{SourceIDs: []string{"continued"}, Budget: 1}, 10)
	controlScheduleForTest(t, s, "retry-chain", "ENABLE")
	s.workerTick(context.Background())
	first := scheduleForTest(t, s, "retry-chain").LastRunID
	deadline := retryForTest(t, s, first).NextAttemptAt
	clock.Add(15 * 60000)
	s.workerTick(context.Background())
	second := scheduleForTest(t, s, "retry-chain").LastRunID
	if first == second || calls.Load() != 1 || retryForTest(t, s, second).NextAttemptAt != deadline {
		t.Fatal("round reset cooldown")
	}
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	if calls.Load() != 1 {
		t.Fatal("scheduled restart replayed too soon")
	}
	clock.Store(deadline)
	s.workerTick(context.Background())
	if calls.Load() != 2 || len(retryForTest(t, s, second).History) != 2 {
		t.Fatal("continuation lost retry allowance")
	}
	deadline = retryForTest(t, s, second).NextAttemptAt
	clock.Add(15 * 60000)
	s.workerTick(context.Background())
	third := scheduleForTest(t, s, "retry-chain").LastRunID
	if third == second || calls.Load() != 2 || len(retryForTest(t, s, third).History) != 2 {
		t.Fatal("second continuation lost history")
	}
	clock.Store(deadline)
	s.workerTick(context.Background())
	if calls.Load() != 3 || retryTaskForTest(t, s, third).Status != "FAILED" || retryForTest(t, s, third).StoppedReason != "ATTEMPT_LIMIT" {
		t.Fatal("continuations reset bounded allowance")
	}
	assertSQLInt(t, s, 3, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
}

func TestPublicResearchRetryLongWaitAndPrivateTasksNeverAutoRetry(t *testing.T) {
	var calls atomic.Int32
	s, _ := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "999999999999999999999")
		http.Error(w, "later", 429)
	})
	sourcesForSchedule(t, s, "long-wait")
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"long-wait"}, Budget: 10})
	s.runResearchPending(context.Background())
	if calls.Load() != 1 || retryTaskForTest(t, s, run).Status != "FAILED" || retryForTest(t, s, run).StoppedReason != "REMOTE_WAIT_TOO_LONG" {
		t.Fatal("remote wait truncated into an early retry")
	}
	for _, kind := range []string{"CATALOG", "MINIMAL_INFERENCE", "AA", "SEARCH"} {
		_, status := s.researchRetryResult(ResearchTask{Kind: kind}, &researchHTTPFailure{Status: 429}, map[string]any{}, "FAILED")
		if status != "FAILED" {
			t.Fatal("private/integration task implicitly replayed", kind)
		}
	}
}

func TestPublicResearchRetryHistorySyncNeverActivatesPeer(t *testing.T) {
	var calls atomic.Int32
	hub, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "120")
		http.Error(w, "later", 429)
	})
	sourcesForSchedule(t, hub, "history-only")
	run := researchRunID(t, hub, ResearchOptions{SourceIDs: []string{"history-only"}, Budget: 5})
	hub.runResearchPending(context.Background())
	peer := testServer(t)
	peer.PublicClient = hub.PublicClient
	peer.scheduleClock = hub.scheduleClock
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	if len(retryForTest(t, peer, run).History) != 1 {
		t.Fatal("retry evidence not synchronized")
	}
	clock.Add(3600000)
	peer.workerTick(context.Background())
	if calls.Load() != 1 {
		t.Fatal("synchronized retry journal became executable intent")
	}
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", run)
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='INTERRUPTED'", run)
}

func TestPublicResearchRetryInspectKeepsLastGoodFacts(t *testing.T) {
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			http.Error(w, "temporarily unavailable", 503)
			return
		}
		w.Header().Set("Content-Type", "text/html")
		w.Write([]byte("<p>Public provider documentation recovered.</p>"))
	})
	c, err := s.ensureCandidate(context.Background(), "retained-provider", "retained-model", "prior-run", "https://provider.example/docs")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.Store.DB.Exec("UPDATE research_candidates SET rpd=100,tpd=1000000,state='VERIFIED' WHERE id=?", c.ID); err != nil {
		t.Fatal(err)
	}
	proof, err := s.recordEvidence(context.Background(), c.ID, "OFFICIAL_RECURRING_QUOTA", c.Website, "retained prior quota", true, EvidenceDetails{Normalized: map[string]any{"rpd": 100, "tpd": 1000000}})
	if err != nil {
		t.Fatal(err)
	}
	run := researchRunID(t, s, ResearchOptions{CandidateIDs: []string{c.ID}, Budget: 3})
	s.runResearchPending(context.Background())
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_candidates WHERE id=? AND rpd=100 AND tpd=1000000", c.ID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_evidence WHERE id=?", proof)
	clock.Store(retryForTest(t, s, run).NextAttemptAt)
	s.runResearchPending(context.Background())
	if calls.Load() != 2 || retryTaskForTest(t, s, run).Status != "SUCCESS" || len(retryForTest(t, s, run).History) != 1 {
		t.Fatal("inspection did not recover with retained failure history")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_candidates WHERE id=? AND rpd=100 AND tpd=1000000", c.ID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_evidence WHERE id=?", proof)
}

func TestPublicResearchRetryInterruptedLimitRetainsHistory(t *testing.T) {
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); http.Error(w, "later", 502) })
	sourcesForSchedule(t, s, "crash-limit")
	saveScheduleForTest(t, s, "crash-limit", ResearchOptions{SourceIDs: []string{"crash-limit"}, Budget: 6}, 10)
	controlScheduleForTest(t, s, "crash-limit", "ENABLE")
	s.workerTick(context.Background())
	run := scheduleForTest(t, s, "crash-limit").LastRunID
	clock.Store(retryForTest(t, s, run).NextAttemptAt)
	s.runResearchPending(context.Background())
	if len(retryForTest(t, s, run).History) != 2 || calls.Load() != 2 {
		t.Fatal("missing two actual failures")
	}
	// Durable state at the start of a third attempt, before it could finish.
	if _, err := s.Store.DB.Exec("UPDATE research_tasks SET status='RUNNING',attempts=3 WHERE run_id=?", run); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	if calls.Load() != 2 || retryTaskForTest(t, s, run).Status != "FAILED" || len(retryForTest(t, s, run).History) != 2 {
		t.Fatal("restart limit replayed or erased prior HTTP failures")
	}
}
