package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type researchExecutionFixture struct {
	Server  *Server
	Arrived chan string
	Release chan struct{}
	Once    sync.Once
	Active  atomic.Int32
	Peak    atomic.Int32
	Calls   atomic.Int32
}

func newResearchExecutionFixture(t *testing.T) *researchExecutionFixture {
	t.Helper()
	f := &researchExecutionFixture{Server: testServer(t), Arrived: make(chan string, 16), Release: make(chan struct{})}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("public research borrowed a credential")
		}
		active := f.Active.Add(1)
		defer f.Active.Add(-1)
		for old := f.Peak.Load(); active > old && !f.Peak.CompareAndSwap(old, active); old = f.Peak.Load() {
		}
		f.Calls.Add(1)
		f.Arrived <- r.URL.Path
		select {
		case <-f.Release:
		case <-r.Context().Done():
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"model_id": strings.TrimPrefix(r.URL.Path, "/"), "rpd": 100, "tpd": 1000000, "free": true, "recurring": "DAILY_RESET"}}})
	}))
	t.Cleanup(up.Close)
	t.Cleanup(func() { f.Once.Do(func() { close(f.Release) }) })
	f.Server.Client = mappedFixture(up)
	f.Server.PublicClient = mappedFixture(up)
	sourcesForSchedule(t, f.Server, "first", "first-later", "second")
	return f
}

func (f *researchExecutionFixture) run(t *testing.T) <-chan struct{} {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); f.Server.runResearchPending(ctx) }()
	t.Cleanup(func() {
		cancel()
		f.Once.Do(func() { close(f.Release) })
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("research worker did not stop")
		}
	})
	return done
}

func TestResearchExecutionParallelRunsKeepEachRunSequentialAndReceiptsScoped(t *testing.T) {
	t.Parallel()
	f := newResearchExecutionFixture(t)
	_, err := f.Server.Store.DB.Exec("INSERT INTO settings VALUES('research_execution_policy',?)", `{"revision":1,"max_parallel_runs":2,"updated_at":1}`)
	if err != nil {
		t.Fatal(err)
	}
	first := researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"first", "first-later"}, Budget: 4})
	second := researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"second"}, Budget: 4})
	done := f.run(t)
	seen := map[string]bool{}
	for range 2 {
		select {
		case path := <-f.Arrived:
			seen[path] = true
		case <-time.After(2 * time.Second):
			t.Fatal("configured second research run never reached the real TLS fixture while the first was held")
		}
	}
	if !seen["/first"] || !seen["/second"] || seen["/first-later"] {
		t.Fatalf("same-run work ran ahead of its predecessor: %v", seen)
	}
	f.Once.Do(func() { close(f.Release) })
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("parallel batch did not finish")
	}
	if f.Peak.Load() != 2 || f.Calls.Load() != 3 {
		t.Fatalf("actual TLS peak=%d calls=%d", f.Peak.Load(), f.Calls.Load())
	}
	assertSQLInt(t, f.Server, 2, "SELECT used FROM research_run_options WHERE run_id=?", first)
	assertSQLInt(t, f.Server, 1, "SELECT used FROM research_run_options WHERE run_id=?", second)
	assertSQLInt(t, f.Server, 3, "SELECT SUM(http_requests) FROM research_task_usage")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM my_models")
}

func executionPolicyForTest(t *testing.T, s *Server) researchExecutionPolicy {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/execution-policy", nil)
	requireCode(t, w, 200)
	var value struct {
		Policy researchExecutionPolicy `json:"policy"`
	}
	if json.Unmarshal(w.Body.Bytes(), &value) != nil {
		t.Fatal("invalid execution policy response")
	}
	return value.Policy
}

func saveExecutionPolicyForTest(t *testing.T, s *Server, limit int) {
	t.Helper()
	v := executionPolicyForTest(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/execution-policy", map[string]any{"revision": v.Revision, "max_parallel_runs": limit}), 200)
}

func TestResearchExecutionPolicyCASPersistsLocallyAndRejectsUnknownAuthority(t *testing.T) {
	t.Parallel()
	s := testServer(t)
	if v := executionPolicyForTest(t, s); v.Revision != 0 || v.MaxParallelRuns != 1 || v.UpdatedAt != 0 {
		t.Fatalf("fresh default %+v", v)
	}
	for _, value := range []map[string]any{
		{"max_parallel_runs": 2}, {"revision": nil, "max_parallel_runs": 2}, {"revision": -1, "max_parallel_runs": 2},
		{"revision": 0, "max_parallel_runs": 0}, {"revision": 0, "max_parallel_runs": 5}, {"revision": 0, "max_parallel_runs": 2, "source_ids": []string{"unapproved"}},
	} {
		requireCode(t, req(t, s, "POST", "/api/v1/research/execution-policy", value), 400)
	}
	var saved, stale atomic.Int32
	var wg sync.WaitGroup
	for range 12 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w := req(t, s, "POST", "/api/v1/research/execution-policy", map[string]any{"revision": 0, "max_parallel_runs": 2})
			switch w.Code {
			case 200:
				saved.Add(1)
			case 409:
				stale.Add(1)
			default:
				t.Errorf("unexpected concurrent policy response %d", w.Code)
			}
		}()
	}
	wg.Wait()
	if saved.Load() != 1 || stale.Load() != 11 {
		t.Fatalf("CAS saves=%d stale=%d", saved.Load(), stale.Load())
	}
	before := executionPolicyForTest(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/settings", map[string]any{"research_execution_policy": map[string]any{"max_parallel_runs": 4}}), 400)
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	if strings.Contains(backup.Body.String(), "research_execution_policy") {
		t.Fatal("local execution preference entered portable backup")
	}
	s = reopenScheduleStore(t, s)
	if after := executionPolicyForTest(t, s); after != before || after.Revision != 1 || after.MaxParallelRuns != 2 {
		t.Fatalf("restart changed policy %+v -> %+v", before, after)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchExecutionPolicyFaultsStopWorkWithoutChangingThePreference(t *testing.T) {
	t.Parallel()
	f := newResearchExecutionFixture(t)
	s := f.Server
	researchRunID(t, s, ResearchOptions{SourceIDs: []string{"first"}, Budget: 2})
	if _, err := s.Store.DB.Exec("CREATE TRIGGER execution_policy_fault BEFORE INSERT ON settings WHEN NEW.key='research_execution_policy' BEGIN SELECT RAISE(ABORT,'controlled policy write failure'); END"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/execution-policy", map[string]any{"revision": 0, "max_parallel_runs": 2}), 503)
	if v := executionPolicyForTest(t, s); v.Revision != 0 || v.MaxParallelRuns != 1 {
		t.Fatal("failed save partially changed preference", v)
	}
	if _, err := s.Store.DB.Exec("DROP TRIGGER execution_policy_fault"); err != nil {
		t.Fatal(err)
	}
	saveExecutionPolicyForTest(t, s, 2)
	const corrupt = `{"revision":1,"max_parallel_runs":0,"updated_at":1}`
	if _, err := s.Store.DB.Exec("UPDATE settings SET value_json=? WHERE key='research_execution_policy'", corrupt); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/execution-policy", nil), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/research/execution-policy", map[string]any{"revision": 1, "max_parallel_runs": 4}), 503)
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 0 {
		t.Fatal("unreadable policy sent HTTP")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE status='QUEUED'")
	var raw string
	s.Store.DB.QueryRow("SELECT value_json FROM settings WHERE key='research_execution_policy'").Scan(&raw)
	if raw != corrupt {
		t.Fatal("unavailable preference was silently reset")
	}
}

func TestResearchExecutionDefaultAndFourTaskBatchRemainBounded(t *testing.T) {
	t.Parallel()
	for _, limit := range []int{1, 4} {
		t.Run(string(rune('0'+limit)), func(t *testing.T) {
			f := newResearchExecutionFixture(t)
			if limit != 1 {
				saveExecutionPolicyForTest(t, f.Server, limit)
			}
			for range 5 {
				researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"first"}, Budget: 1})
			}
			done := f.run(t)
			for range limit {
				select {
				case <-f.Arrived:
				case <-time.After(2 * time.Second):
					t.Fatal("configured lanes never reached TLS")
				}
			}
			select {
			case path := <-f.Arrived:
				t.Fatal("execution cap exceeded", path)
			case <-time.After(80 * time.Millisecond):
			}
			f.Once.Do(func() { close(f.Release) })
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("bounded batch did not stop")
			}
			if f.Calls.Load() != 4 || f.Peak.Load() != int32(limit) {
				t.Fatalf("batch calls=%d peak=%d limit=%d", f.Calls.Load(), f.Peak.Load(), limit)
			}
			assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_tasks WHERE status='QUEUED'")
			f.Server.runResearchPending(context.Background())
			if f.Calls.Load() != 5 || f.Peak.Load() > int32(limit) {
				t.Fatal("next batch lost or exceeded its cap")
			}
			assertSQLInt(t, f.Server, 5, "SELECT SUM(http_requests) FROM research_task_usage")
		})
	}
}

func TestResearchExecutionCancellationAndRestartKeepOtherRunAndExactReceipts(t *testing.T) {
	t.Parallel()
	f := newResearchExecutionFixture(t)
	saveExecutionPolicyForTest(t, f.Server, 2)
	first := researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"first", "first-later"}, Budget: 4})
	second := researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"second"}, Budget: 4})
	done := f.run(t)
	for range 2 {
		select {
		case <-f.Arrived:
		case <-time.After(2 * time.Second):
			t.Fatal("both controlled requests did not start")
		}
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/control", map[string]any{"id": first, "action": "CANCEL"}), 200)
	deadline := time.Now().Add(2 * time.Second)
	for f.Active.Load() != 1 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if f.Active.Load() != 1 {
		t.Fatal("cancel failed to isolate one run")
	}
	if v := executionPolicyForTest(t, f.Server); v.MaxParallelRuns != 2 {
		t.Fatal("cancel changed policy")
	}
	f.Once.Do(func() { close(f.Release) })
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("uncancelled run did not finish")
	}
	if f.Calls.Load() != 2 {
		t.Fatalf("cancelled successor sent HTTP: %d", f.Calls.Load())
	}
	assertSQLInt(t, f.Server, 1, "SELECT used FROM research_run_options WHERE run_id=?", first)
	assertSQLInt(t, f.Server, 1, "SELECT used FROM research_run_options WHERE run_id=?", second)
	assertSQLInt(t, f.Server, 2, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='CANCELLED'", first)
	third := researchRunID(t, f.Server, ResearchOptions{SourceIDs: []string{"first-later"}, Budget: 1})
	f.Server = reopenScheduleStore(t, f.Server)
	f.Server.runResearchPending(context.Background())
	if f.Calls.Load() != 2 {
		t.Fatalf("restart sent unconfirmed manual work: calls=%d", f.Calls.Load())
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", third)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/research/control", map[string]any{"id": third, "action": "RESUME"}), 200)
	f.Server.runResearchPending(context.Background())
	if f.Calls.Load() != 3 {
		t.Fatalf("explicit resume lost queued work: calls=%d", f.Calls.Load())
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='COMPLETED'", third)
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='CANCELLED'", first)
	assertSQLInt(t, f.Server, 3, "SELECT SUM(http_requests) FROM research_task_usage")
}

func TestResearchExecutionScheduledSlotsPreserveDedupResumeAndDailyBudget(t *testing.T) {
	t.Parallel()
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "first")
	saveExecutionPolicyForTest(t, s, 2)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/limit", map[string]int{"daily_http_limit": 1}), 200)
	for _, id := range []string{"one", "two", "three"} {
		saveScheduleForTest(t, s, id, ResearchOptions{SourceIDs: []string{"first"}, Budget: 1}, 10)
		controlScheduleForTest(t, s, id, "ENABLE")
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); s.researchScheduleTick(context.Background()) }()
	}
	wg.Wait()
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_runs")
	var active []researchSchedule
	var waiting researchSchedule
	for _, v := range schedulesForTest(t, s).Schedules {
		if v.LastRunID != "" {
			active = append(active, v)
		} else {
			waiting = v
		}
	}
	if len(active) != 2 || waiting.ID == "" {
		t.Fatal("schedule slots not bounded")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": active[0].LastRunID, "action": "PAUSE"}), 200)
	s.researchScheduleTick(context.Background())
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_schedule_runs")
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": active[0].LastRunID, "action": "RESUME"}), 409)
	controlScheduleForTest(t, s, waiting.ID, "DISABLE")
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": active[0].LastRunID, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatalf("parallel schedules exceeded daily HTTP cap: %d", calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	assertSQLInt(t, s, 1, "SELECT SUM(http_requests) FROM research_task_usage")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_schedule_runs")
}

func TestResearchExecutionParallelPrivateRunsStillShareCredentialBudget(t *testing.T) {
	t.Parallel()
	f := newResearchAdmissionFixture(t, 4, 1)
	s := f.Server
	saveExecutionPolicyForTest(t, s, 2)
	setInferencePolicyForTest(t, s, 1, 1)
	other := researchRunID(t, s, ResearchOptions{Selections: []Selection{f.Scope}, MinimalInference: true, Budget: 4, ProbeBudget: 1})
	f.Mode.Store(3)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(ctx) }()
	var release sync.Once
	t.Cleanup(func() {
		cancel()
		release.Do(func() { close(f.Release) })
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("private worker did not stop")
		}
	})
	select {
	case <-f.Started:
	case <-time.After(3 * time.Second):
		t.Fatal("authorized inference did not reach TLS")
	}
	release.Do(func() { close(f.Release) })
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("private batch did not finish")
	}
	if f.Calls.Load() != 3 {
		t.Fatalf("expected two original catalog GETs plus one admitted inference, got %d", f.Calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
	assertSQLInt(t, s, 1, "SELECT SUM(probes_used) FROM research_run_options WHERE run_id IN (?,?)", f.RunID, other)
	assertSQLInt(t, s, 3, "SELECT SUM(http_requests) FROM research_task_usage")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry")
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 3 {
		t.Fatal("exhausted parallel run automatically retried inference")
	}
}
