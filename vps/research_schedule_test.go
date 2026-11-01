package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The existing v16/v17 migration fixtures must omit tables added in v19.
func dropScheduleSchemaFixture(t *testing.T, db *sql.DB) {
	t.Helper()
	for _, name := range []string{"research_schedule_runs", "research_schedule_daily", "research_schedules"} {
		if _, err := db.Exec("DROP TABLE " + name); err != nil {
			t.Fatal(err)
		}
	}
}

type scheduleResponse struct {
	Schedules  []researchSchedule `json:"schedules"`
	DailyLimit int                `json:"daily_http_limit"`
	TodayUsed  int                `json:"today_used"`
	ResetAt    int64              `json:"reset_at"`
}

func schedulesForTest(t *testing.T, s *Server) scheduleResponse {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/schedules", nil)
	requireCode(t, w, 200)
	var v scheduleResponse
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func scheduleForTest(t *testing.T, s *Server, id string) researchSchedule {
	t.Helper()
	for _, v := range schedulesForTest(t, s).Schedules {
		if v.ID == id {
			return v
		}
	}
	t.Fatal("schedule missing", id)
	return researchSchedule{}
}
func saveScheduleForTest(t *testing.T, s *Server, id string, options ResearchOptions, daily int) {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": id, "name": "持久公開探索 " + id, "interval_minutes": 15, "daily_http_limit": daily, "options": options, "languages": []string{"en", "zh"}}), 200)
}
func controlScheduleForTest(t *testing.T, s *Server, id, action string) {
	t.Helper()
	v := scheduleForTest(t, s, id)
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": id, "revision": v.Revision, "action": action}), 200)
}
func sourcesForSchedule(t *testing.T, s *Server, ids ...string) {
	t.Helper()
	for _, id := range ids {
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: id, Name: id, Kind: "JSON", URL: "https://provider.example/" + id, Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data"}}), 200)
	}
}
func scheduleFixture(t *testing.T) (*Server, *atomic.Int32) {
	t.Helper()
	calls := &atomic.Int32{}
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Subscription-Token") != "" {
			t.Error("public schedule forwarded a credential")
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"model_id": strings.TrimPrefix(r.URL.Path, "/"), "rpd": 100, "tpd": 1000000, "free": true, "recurring": "DAILY_RESET"}}})
	}))
	t.Cleanup(upstream.Close)
	s := testServer(t)
	s.Client = mappedFixture(upstream)
	s.PublicClient = mappedFixture(upstream)
	return s, calls
}
func reopenScheduleStore(t *testing.T, s *Server) *Server {
	t.Helper()
	path := s.Store.path
	if err := s.Store.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return &Server{Store: db, Token: s.Token, Key: s.Key, Client: s.Client, PublicClient: s.PublicClient, scheduleClock: s.scheduleClock}
}
func assertSQLInt(t *testing.T, s *Server, want int, query string, args ...any) {
	t.Helper()
	var actual int
	if err := s.Store.DB.QueryRow(query, args...).Scan(&actual); err != nil {
		t.Fatal(err)
	}
	if actual != want {
		t.Fatalf("%s = %d; want %d", query, actual, want)
	}
}
func TestResearchScheduleDisabledDueDedupAndNoMembership(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "public-one")
	instant := time.Date(2026, 10, 20, 13, 0, 0, 0, time.UTC)
	s.scheduleClock = func() time.Time { return instant }
	saveScheduleForTest(t, s, "daily", ResearchOptions{SourceIDs: []string{"public-one", "public-one"}, Budget: 4}, 10)
	s.workerTick(context.Background())
	if calls.Load() != 0 || scheduleForTest(t, s, "daily").Enabled {
		t.Fatal("saving enabled automatic research")
	}
	controlScheduleForTest(t, s, "daily", "ENABLE")
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); s.researchScheduleTick(context.Background()) }()
	}
	wg.Wait()
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks")
	s.runResearchPending(context.Background())
	s.researchScheduleTick(context.Background())
	v := scheduleForTest(t, s, "daily")
	if calls.Load() != 1 || v.RunStatus != "COMPLETED" || v.RunUsed != 1 || v.TodayUsed != 1 {
		t.Fatalf("actual round %d/%+v", calls.Load(), v)
	}
	if len(allCandidates(t, s)) != 1 || allCandidates(t, s)[0].State == "VERIFIED" {
		t.Fatal("external claim became verified or missing")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs")
	instant = instant.Add(15 * time.Minute)
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_runs")
	if calls.Load() != 2 || len(allCandidates(t, s)) != 1 {
		t.Fatal("next round did not recheck the same candidate")
	}
}
func TestResearchScheduleDailyLimitsSurviveRestartAndResumeAtUTC(t *testing.T) {
	for _, scope := range []string{"global", "schedule"} {
		t.Run(scope, func(t *testing.T) {
			s, calls := scheduleFixture(t)
			sourcesForSchedule(t, s, "first", "second")
			instant := time.Date(2026, 10, 20, 23, 59, 0, 0, time.UTC)
			s.scheduleClock = func() time.Time { return instant }
			daily := 10
			if scope == "schedule" {
				daily = 1
			} else {
				requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/limit", map[string]int{"daily_http_limit": 1}), 200)
			}
			saveScheduleForTest(t, s, "bounded", ResearchOptions{SourceIDs: []string{"first", "second"}, Budget: 6}, daily)
			controlScheduleForTest(t, s, "bounded", "ENABLE")
			s.researchScheduleTick(context.Background())
			s.runResearchPending(context.Background())
			v := scheduleForTest(t, s, "bounded")
			if calls.Load() != 1 || v.RunUsed != 1 || v.RunStatus != "BUDGET_EXHAUSTED" || v.State != "DAILY_LIMIT" || v.ResumeAt != nextBudgetDay(instant) {
				t.Fatalf("daily cap %d/%+v", calls.Load(), v)
			}
			assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=? AND status='BUDGET_EXHAUSTED'", v.LastRunID)
			if len(allCandidates(t, s)) != 1 {
				t.Fatal("budget admission fabricated a source failure")
			}
			run := v.LastRunID
			s = reopenScheduleStore(t, s)
			for i := 0; i < 5; i++ {
				s.workerTick(context.Background())
			}
			if calls.Load() != 1 || scheduleForTest(t, s, "bounded").LastRunID != run {
				t.Fatal("restart reset the cap or duplicated a run")
			}
			instant = instant.Add(time.Minute)
			s.workerTick(context.Background())
			v = scheduleForTest(t, s, "bounded")
			if calls.Load() != 2 || v.LastRunID != run || v.RunUsed != 2 || v.RunStatus != "COMPLETED" || v.TodayUsed != 1 {
				t.Fatalf("checkpoint/reset %d/%+v", calls.Load(), v)
			}
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs")
			assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_daily WHERE scope='global'")
		})
	}
}
func TestResearchScheduleRestartIntentAndHumanPause(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "restart", "paused")
	for _, id := range []string{"restart", "paused"} {
		saveScheduleForTest(t, s, id, ResearchOptions{SourceIDs: []string{id}, Budget: 4}, 10)
		controlScheduleForTest(t, s, id, "ENABLE")
	}
	// Only one automatic round is ever queued globally.
	s.researchScheduleTick(context.Background())
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs")
	var first string
	s.Store.DB.QueryRow("SELECT run_id FROM research_schedule_runs").Scan(&first)
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	if calls.Load() != 1 {
		t.Fatal("durable intent failed to recover a queued round")
	}
	s.researchScheduleTick(context.Background())
	var second string
	s.Store.DB.QueryRow("SELECT run_id FROM research_schedule_runs WHERE run_id!=?", first).Scan(&second)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": second, "action": "PAUSE"}), 200)
	s = reopenScheduleStore(t, s)
	for i := 0; i < 3; i++ {
		s.workerTick(context.Background())
	}
	if calls.Load() != 1 {
		t.Fatal("process restart overrode a human pause")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": second, "action": "RESUME"}), 200)
	s.workerTick(context.Background())
	if calls.Load() != 2 {
		t.Fatal("explicit resume failed")
	}
	// A manual round does not acquire schedule intent just because a scheduler exists.
	manual := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"paused"}, Budget: 4})
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", manual)
	if calls.Load() != 2 {
		t.Fatal("manual history resumed automatically")
	}
}
func TestResearchScheduleDisableCancelsLiveTLSAndProtectsOtherEntryPoint(t *testing.T) {
	entered := make(chan struct{})
	stopped := make(chan struct{})
	var calls atomic.Int32
	var block atomic.Bool
	block.Store(true)
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if block.Load() {
			close(entered)
			<-r.Context().Done()
			close(stopped)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"kept"}]}`))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = mappedFixture(upstream)
	s.PublicClient = mappedFixture(upstream)
	sourcesForSchedule(t, s, "cancel-live")
	saveScheduleForTest(t, s, "disable", ResearchOptions{SourceIDs: []string{"cancel-live"}, Budget: 4}, 10)
	controlScheduleForTest(t, s, "disable", "ENABLE")
	s.researchScheduleTick(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(context.Background()) }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("real HTTP never entered")
	}
	controlScheduleForTest(t, s, "disable", "DISABLE")
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("disabling did not cancel live HTTP")
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("runner did not checkpoint")
	}
	v := scheduleForTest(t, s, "disable")
	if v.RunStatus != "PAUSED" || v.RunUsed != 1 || v.TodayUsed != 1 || v.AutoResume {
		t.Fatalf("lost pause/accounting %+v", v)
	}
	if len(allCandidates(t, s)) != 0 {
		t.Fatal("user cancellation fabricated a failed candidate")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": v.LastRunID, "action": "RESUME"}), 409)
	s.workerTick(context.Background())
	if calls.Load() != 1 {
		t.Fatal("disabled owner executed")
	}
	block.Store(false)
	controlScheduleForTest(t, s, "disable", "ENABLE")
	s.workerTick(context.Background())
	if calls.Load() != 2 || scheduleForTest(t, s, "disable").RunStatus != "COMPLETED" {
		t.Fatal("explicit re-enable did not resume its checkpoint")
	}
}
func TestResearchScheduleAtomicDailyReservationsAcrossConcurrentTLS(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "atomic")
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/limit", map[string]int{"daily_http_limit": 3}), 200)
	saveScheduleForTest(t, s, "atomic", ResearchOptions{SourceIDs: []string{"atomic"}, Budget: 20}, 10)
	controlScheduleForTest(t, s, "atomic", "ENABLE")
	s.researchScheduleTick(context.Background())
	run := scheduleForTest(t, s, "atomic").LastRunID
	ctx := context.WithValue(context.Background(), researchBudgetKey{}, run)
	var wg sync.WaitGroup
	var denied atomic.Int32
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _, _, err := s.publicFetch(ctx, "https://provider.example/atomic")
			if errors.Is(err, errScheduledDailyBudget) {
				denied.Add(1)
			} else if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if calls.Load() != 3 || denied.Load() != 17 {
		t.Fatalf("atomic cap sent=%d denied=%d", calls.Load(), denied.Load())
	}
	v := scheduleForTest(t, s, "atomic")
	if v.TodayUsed != 3 || v.RunUsed != 3 || schedulesForTest(t, s).TodayUsed != 3 {
		t.Fatalf("reservation drift %+v", v)
	}
}
func TestResearchScheduleSourceEditsRequireLocalReviewAndKeepHistory(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "changed")
	saveScheduleForTest(t, s, "review", ResearchOptions{SourceIDs: []string{"changed"}, Budget: 6}, 10)
	controlScheduleForTest(t, s, "review", "ENABLE")
	s.researchScheduleTick(context.Background())
	old := scheduleForTest(t, s, "review").LastRunID
	src, _ := s.source(context.Background(), "changed")
	src.URL = "https://provider.example/new-version"
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	// The reservation guard also protects work already queued before the edit.
	s.runResearchPending(context.Background())
	v := scheduleForTest(t, s, "review")
	if calls.Load() != 0 || v.Enabled || v.LastReason != "SOURCES_CHANGED" || v.RunStatus != "PAUSED" {
		t.Fatalf("changed source executed %+v", v)
	}
	if len(allCandidates(t, s)) != 0 {
		t.Fatal("local guard became upstream failure evidence")
	}
	controlScheduleForTest(t, s, "review", "ENABLE")
	s.workerTick(context.Background())
	v = scheduleForTest(t, s, "review")
	if calls.Load() != 1 || v.LastRunID == old || allCandidates(t, s)[0].ModelID != "new-version" {
		t.Fatal("new approval replayed an old source snapshot")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='CANCELLED'", old)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": old, "action": "RESUME"}), 409)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=?", old)
	// Editing a disabled definition similarly retires only unfinished work.
	controlScheduleForTest(t, s, "review", "DISABLE")
	v = scheduleForTest(t, s, "review")
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": v.ID, "revision": v.Revision, "name": "更新後", "interval_minutes": 30, "daily_http_limit": 4, "options": ResearchOptions{SourceIDs: []string{"changed"}, Budget: 2}}), 200)
	if scheduleForTest(t, s, "review").Enabled {
		t.Fatal("editing enabled a schedule")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_runs")
}
func TestResearchScheduleWorkspaceHistoryNeverResumesAndDefinitionsStayLocal(t *testing.T) {
	hub, calls := scheduleFixture(t)
	peer := testServer(t)
	sourcesForSchedule(t, hub, "shared")
	saveScheduleForTest(t, hub, "local-only", ResearchOptions{SourceIDs: []string{"shared"}, Budget: 6}, 10)
	controlScheduleForTest(t, hub, "local-only", "ENABLE")
	hub.researchScheduleTick(context.Background())
	run := scheduleForTest(t, hub, "local-only").LastRunID
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_schedules")
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", run)
	peer.workerTick(context.Background())
	if calls.Load() != 0 {
		t.Fatal("imported history ran on peer")
	}
	if _, err := peer.Store.DB.Exec("UPDATE research_run_options SET budget=7 WHERE run_id=?", run); err != nil {
		t.Fatal(err)
	}
	workspaceRunForTest(t, peer)
	hub.workerTick(context.Background())
	v := scheduleForTest(t, hub, "local-only")
	if v.Enabled || v.LastReason != "SYNC_CHANGED" || calls.Load() != 0 {
		t.Fatalf("incoming work mutated local intent %+v", v)
	}
	requireCode(t, req(t, hub, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 409)
	controlScheduleForTest(t, hub, "local-only", "ENABLE")
	hub.workerTick(context.Background())
	if calls.Load() != 1 || scheduleForTest(t, hub, "local-only").LastRunID == run {
		t.Fatal("approval resumed imported task content")
	}
}
func TestResearchScheduleBoundsRevisionsAndPrivateInferenceRejected(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "bounds")
	saveScheduleForTest(t, s, "bounds", ResearchOptions{SourceIDs: []string{"bounds"}, Budget: 4}, 10)
	for _, options := range []ResearchOptions{{SourceIDs: []string{"bounds"}, MinimalInference: true, Budget: 4}, {SourceIDs: []string{"bounds"}, ProbeBudget: 1, Budget: 4}, {SourceIDs: []string{"bounds"}, Selections: []Selection{{ProviderID: "p"}}, Budget: 4}} {
		requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "invalid", "name": "invalid", "interval_minutes": 15, "daily_http_limit": 4, "options": options}), 400)
	}
	v := scheduleForTest(t, s, "bounds")
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules/control", map[string]any{"id": v.ID, "revision": v.Revision - 1, "action": "ENABLE"}), 409)
	controlScheduleForTest(t, s, "bounds", "ENABLE")
	s.researchScheduleTick(context.Background())
	v = scheduleForTest(t, s, "bounds")
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": v.ID, "revision": v.Revision, "name": "overwrite", "interval_minutes": 15, "daily_http_limit": 4, "options": v.Options}), 409)
	ctx := context.WithValue(context.Background(), researchBudgetKey{}, v.LastRunID)
	if err := s.chargeNetwork(ctx, true); err == nil {
		t.Fatal("automatic inference reservation accepted")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": v.LastRunID, "action": "PAUSE"}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": v.LastRunID, "action": "RESUME", "additional_probes": 1}), 409)
	if calls.Load() != 0 || scheduleForTest(t, s, "bounds").RunUsed != 0 {
		t.Fatal("rejected options sent or charged HTTP")
	}
	for _, path := range []string{"/api/v1/research/schedules", "/api/v1/research/schedules/control", "/api/v1/research/schedules/limit"} {
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		requireCode(t, w, 401)
	}
}
func TestResearchScheduleV18UpgradeKeepsManualHistoryAndDefaultsOff(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "legacy")
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"legacy"}, Budget: 3})
	dropScheduleSchemaFixture(t, s.Store.DB)
	if _, err := s.Store.DB.Exec("PRAGMA user_version=18"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	s.workerTick(context.Background())
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", run)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=?", run)
	if calls.Load() != 0 || len(schedulesForTest(t, s).Schedules) != 0 || schedulesForTest(t, s).DailyLimit != 100 {
		t.Fatal("migration auto-enabled work or lost defaults")
	}
	backups, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", "*.sqlite"))
	if err != nil || len(backups) != 1 {
		t.Fatal("missing original v18 backup", err)
	}
	if err = verifySnapshot(backups[0]); err != nil {
		t.Fatal(err)
	}
}

func TestResearchScheduleRoundLimitContinuesRemainingTasksWithoutStarvation(t *testing.T) {
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "early", "later")
	instant := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)
	s.scheduleClock = func() time.Time { return instant }
	saveScheduleForTest(t, s, "continue", ResearchOptions{SourceIDs: []string{"early", "later"}, Budget: 1}, 10)
	controlScheduleForTest(t, s, "continue", "ENABLE")
	s.workerTick(context.Background())
	first := scheduleForTest(t, s, "continue")
	if calls.Load() != 1 || first.State != "ROUND_LIMIT" {
		t.Fatalf("first bounded round %+v", first)
	}
	for i := 0; i < 3; i++ {
		s.workerTick(context.Background())
	}
	if calls.Load() != 1 {
		t.Fatal("round limit triggered immediate spin")
	}
	instant = instant.Add(15 * time.Minute)
	s.workerTick(context.Background())
	second := scheduleForTest(t, s, "continue")
	if calls.Load() != 2 || second.LastRunID == first.LastRunID || second.RunUsed != 1 || second.RunStatus != "COMPLETED" || len(allCandidates(t, s)) != 2 {
		t.Fatal("small budget starved later sources")
	}
	var continued string
	if err := s.Store.DB.QueryRow("SELECT continued_from FROM research_schedule_runs WHERE run_id=?", second.LastRunID).Scan(&continued); err != nil || continued != first.LastRunID {
		t.Fatal("lost continuation lineage", err)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='QUEUED'", first.LastRunID)
	// Older rounds remain read-only; repeating resume cannot bypass concurrency.
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": first.LastRunID, "action": "RESUME", "additional_budget": 1}), 409)
	instant = instant.Add(24 * time.Hour)
	s.workerTick(context.Background())
	if calls.Load() != 3 {
		t.Fatal("scheduler backfilled missed intervals or failed to begin a fresh scan")
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_schedule_runs")
}
func TestResearchScheduleUsesOnlySelectedLanguagesAndCurrentApprovedKnowledge(t *testing.T) {
	var searches atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" || r.Header.Get("X-Subscription-Token") != "fixture-brave-schedule-key" {
			t.Error("unexpected search transport")
		}
		searches.Add(1)
		var input map[string]any
		json.NewDecoder(r.Body).Decode(&input)
		if input["search_lang"] != "zh-hant" {
			t.Error("unselected language executed", input["search_lang"])
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"web":{"results":[]}}`))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = mappedFixture(upstream)
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "fixture-brave-schedule-key"}), 200)
	// Real retained evidence and formal activation API; an unconfirmed proposal
	// never controls execution. The next round resolves current active versions.
	sourcesForSchedule(t, s, "proof-source")
	candidate, err := s.ensureCandidate(context.Background(), "discovered-proof", "UNKNOWN", "", "https://provider.example/docs")
	if err != nil {
		t.Fatal(err)
	}
	proof, err := s.recordEvidence(context.Background(), candidate.ID, "PUBLIC_WEB_CLAIM", "https://provider.example/docs", "Public research evidence", false, EvidenceDetails{})
	if err != nil {
		t.Fatal(err)
	}
	proposed := KnowledgeVersion{Kind: "RESEARCH_STRATEGY", Subject: "zh", Data: map[string]any{"language": "zh", "query": "已確認的公開 API 額度研究", "priority": 1}, EvidenceIDs: []string{proof}, Reason: "保留證據的多語探索策略"}
	w := req(t, s, "POST", "/api/v1/research/knowledge", proposed)
	requireCode(t, w, 201)
	var proposal KnowledgeVersion
	json.Unmarshal(w.Body.Bytes(), &proposal)
	instant := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)
	s.scheduleClock = func() time.Time { return instant }
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "language", "name": "中文探索", "interval_minutes": 15, "daily_http_limit": 10, "languages": []string{"zh"}, "options": ResearchOptions{Search: true, Budget: 4}}), 200)
	controlScheduleForTest(t, s, "language", "ENABLE")
	s.workerTick(context.Background())
	first := scheduleForTest(t, s, "language")
	var payload string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=?", first.LastRunID).Scan(&payload)
	var before taskInput
	json.Unmarshal([]byte(payload), &before)
	if before.Query != searchQueries["zh"] || searches.Load() != 1 {
		t.Fatal("tentative knowledge activated itself")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: proposal.ID, Confirm: true}), 200)
	instant = instant.Add(15 * time.Minute)
	s.workerTick(context.Background())
	second := scheduleForTest(t, s, "language")
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=?", second.LastRunID).Scan(&payload)
	var after taskInput
	json.Unmarshal([]byte(payload), &after)
	if after.Query != str(proposed.Data["query"]) || searches.Load() != 2 {
		t.Fatal("periodic run ignored current approved knowledge")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestResearchScheduleIntegrationAdmissionWaitDoesNotSpin(t *testing.T) {
	s, calls := scheduleFixture(t)
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "AA", "secret": "fixture-aa-schedule-key"}), 200)
	reset := now() + int64(time.Hour/time.Millisecond)
	if _, err := s.Store.DB.Exec("INSERT INTO integration_windows VALUES('AA',?,?,100,100,0)", now(), reset); err != nil {
		t.Fatal(err)
	}
	saveScheduleForTest(t, s, "aa-limited", ResearchOptions{RefreshAA: true, Budget: 8}, 10)
	controlScheduleForTest(t, s, "aa-limited", "ENABLE")
	s.workerTick(context.Background())
	v := scheduleForTest(t, s, "aa-limited")
	if calls.Load() != 0 || v.RunUsed != 0 || v.ResumeAt != reset || v.State != "INTEGRATION_LIMIT" {
		t.Fatalf("wrong AA admission state %+v", v)
	}
	for i := 0; i < 5; i++ {
		s.workerTick(context.Background())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_runs")
	assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=?", v.LastRunID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE status='FAILED'")
}
func TestResearchScheduleWorkspaceSourceEditStopsExistingRound(t *testing.T) {
	hub, calls := scheduleFixture(t)
	peer := testServer(t)
	sourcesForSchedule(t, hub, "synced-source")
	saveScheduleForTest(t, hub, "source-owner", ResearchOptions{SourceIDs: []string{"synced-source"}, Budget: 8}, 10)
	controlScheduleForTest(t, hub, "source-owner", "ENABLE")
	hub.researchScheduleTick(context.Background())
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	src, err := peer.source(context.Background(), "synced-source")
	if err != nil {
		t.Fatal(err)
	}
	src.URL = "https://provider.example/remote-change"
	requireCode(t, req(t, peer, "POST", "/api/v1/research/sources", src), 200)
	workspaceRunForTest(t, peer)
	hub.workerTick(context.Background())
	v := scheduleForTest(t, hub, "source-owner")
	if calls.Load() != 0 || v.Enabled || v.LastReason != "SOURCES_CHANGED" {
		t.Fatalf("incoming source silently executed %+v", v)
	}
	if len(allCandidates(t, hub)) != 0 {
		t.Fatal("sync review admission fabricated a source failure")
	}
}

func TestResearchScheduleOwningPeerSyncAcknowledgementPreservesLocalIntent(t *testing.T) {
	hub := testServer(t)
	peer, calls := scheduleFixture(t)
	sourcesForSchedule(t, peer, "peer-owned")
	saveScheduleForTest(t, peer, "owning-peer", ResearchOptions{SourceIDs: []string{"peer-owned"}, Budget: 4}, 10)
	controlScheduleForTest(t, peer, "owning-peer", "ENABLE")
	peer.researchScheduleTick(context.Background())
	run := scheduleForTest(t, peer, "owning-peer").LastRunID
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	for i := 0; i < 3; i++ {
		workspaceRunForTest(t, peer)
	}
	v := scheduleForTest(t, peer, "owning-peer")
	if !v.Enabled || !v.AutoResume || v.RunStatus != "QUEUED" {
		t.Fatalf("own acknowledgement suspended the owning scheduler %+v", v)
	}
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM research_schedules")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='PAUSED'", run)
	// Pairing uses a separate HTTP client; public discovery retains its guarded TLS client.
	peer.workerTick(context.Background())
	if calls.Load() != 1 || scheduleForTest(t, peer, "owning-peer").RunStatus != "COMPLETED" {
		t.Fatal("owned checkpoint failed after synchronization")
	}
}
