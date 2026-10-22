package main

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestResearchOriginCooldownSharedAcrossTasksAndRuns(t *testing.T) {
	var limited, blocked, healthy atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/limited":
			limited.Add(1)
			w.Header().Set("Retry-After", "120")
			http.Error(w, "private remote body", 429)
			return
		case "/blocked":
			blocked.Add(1)
		default:
			healthy.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"retained-model"}]}`))
	})
	sourcesForSchedule(t, s, "limited", "blocked", "healthy")
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: "healthy", Name: "healthy", Kind: "JSON", URL: "https://healthy.example/healthy", Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data"}}), 200)
	first := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"limited", "blocked", "healthy"}, Budget: 6})
	s.runResearchPending(context.Background())
	if limited.Load() != 1 || blocked.Load() != 0 || healthy.Load() != 1 {
		t.Fatalf("same origin ignored Retry-After or unrelated origin blocked: limited=%d blocked=%d healthy=%d", limited.Load(), blocked.Load(), healthy.Load())
	}
	assertSQLInt(t, s, 2, "SELECT used FROM research_run_options WHERE run_id=?", first)
	assertSQLInt(t, s, 0, "SELECT attempts FROM research_tasks WHERE run_id=? AND dedupe='source:blocked'", first)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SOURCE_FETCH_FAILURE'")
	// A new run and manual resume must both respect the shared, durable wait.
	second := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"blocked"}, Budget: 3})
	s.runResearchPending(context.Background())
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", second)
	w := req(t, s, "GET", "/api/v1/research/tasks?run_id="+second, nil)
	requireCode(t, w, 200)
	var tasks []ResearchTask
	if json.Unmarshal(w.Body.Bytes(), &tasks) != nil || len(tasks) != 1 || tasks[0].Status != "QUEUED" || !strings.Contains(string(tasks[0].Result), "origin_wait") || strings.Contains(string(tasks[0].Result), "private remote body") {
		t.Fatalf("waiting task missing truthful admission result: %s", w.Body.String())
	}
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": second, "action": "RESUME"}), 200)
	clock.Add(119999)
	s.runResearchPending(context.Background())
	if blocked.Load() != 0 {
		t.Fatal("restart or new round bypassed shared cooldown")
	}
	// Cancel the original failing task so only the admitted waiting tasks resume.
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": first, "action": "CANCEL"}), 200)
	clock.Add(1)
	s.runResearchPending(context.Background())
	if blocked.Load() != 1 {
		t.Fatal("origin did not resume at the exact remote lower bound")
	}
	assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", second)
	assertSQLInt(t, s, 1, "SELECT attempts FROM research_tasks WHERE run_id=?", second)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
}

func TestResearchOriginCooldownLongRemoteWaitIsNeverShortened(t *testing.T) {
	for _, header := range []string{"90000", "9999999999999999999999999999999"} {
		t.Run(header, func(t *testing.T) {
			var calls atomic.Int32
			s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				w.Header().Set("Retry-After", header)
				http.Error(w, "do not store this body", 429)
			})
			sourcesForSchedule(t, s, "long", "another")
			first := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"long"}, Budget: 5})
			s.runResearchPending(context.Background())
			if retryForTest(t, s, first).StoppedReason != "REMOTE_WAIT_TOO_LONG" {
				t.Fatal("single-task automatic retry should stop beyond 24h")
			}
			var deadline int64
			if err := s.Store.DB.QueryRow("SELECT not_before FROM research_origin_cooldowns").Scan(&deadline); err != nil {
				t.Fatal(err)
			}
			if header == "90000" && deadline != clock.Load()+90000000 || header != "90000" && deadline != math.MaxInt64 {
				t.Fatal("remote wait was truncated", deadline)
			}
			s = reopenScheduleStore(t, s)
			second := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"another"}, Budget: 5})
			clock.Add(86400000)
			s.runResearchPending(context.Background())
			if calls.Load() != 1 || retryTaskForTest(t, s, second).Attempts != 0 {
				t.Fatal("a new day/run or restart bypassed the remote lower bound")
			}
			assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", second)
		})
	}
}

func TestResearchOriginCooldownCanonicalScopeAndLateSuccess(t *testing.T) {
	s := testServer(t)
	for _, raw := range []string{"https://Provider.Example/one?view=public", "https://provider.example:443/two", "https://provider.example./three"} {
		if researchPublicOrigin(raw) != "https://provider.example" {
			t.Fatal("equivalent origins did not share admission", raw)
		}
	}
	if researchPublicOrigin("https://provider.example:8443/test") == researchPublicOrigin("https://provider.example/test") {
		t.Fatal("distinct ports shared a cooldown")
	}
	if researchPublicOrigin("https://localhost/test") != "" || researchPublicOrigin("https://provider.example/?token=private") != "" {
		t.Fatal("unsafe public origin accepted")
	}
	clock := &atomic.Int64{}
	clock.Store(time.Now().UnixMilli())
	s.scheduleClock = func() time.Time { return time.UnixMilli(clock.Load()) }
	origin := "https://provider.example"
	first := &researchHTTPFailure{Status: 429, NotBefore: clock.Load() + 120000}
	if err := s.recordResearchOriginFailure(origin, first); err != nil {
		t.Fatal(err)
	}
	// Another already-admitted response arrives later with a shorter wait.
	clock.Add(1000)
	second := &researchHTTPFailure{Status: 503, NotBefore: clock.Load() + 1000}
	if err := s.recordResearchOriginFailure(origin, second); err != nil || second.NotBefore != first.NotBefore {
		t.Fatal("late response shortened a newer remote deadline", err)
	}
	s.researchOriginSucceeded(origin, clock.Load())
	assertSQLInt(t, s, 2, "SELECT failure_count FROM research_origin_cooldowns")
	clock.Store(first.NotBefore)
	s.researchOriginSucceeded(origin, clock.Load())
	assertSQLInt(t, s, 0, "SELECT failure_count FROM research_origin_cooldowns")
}

func TestResearchOriginCooldownInFlightSuccessCannotClearLaterFailure(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	letGo := func() { once.Do(func() { close(release) }) }
	defer letGo()
	var calls atomic.Int32
	s, _ := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path == "/slow" {
			close(entered)
			<-release
			w.Write([]byte(`{"data":[]}`))
			return
		}
		w.Header().Set("Retry-After", "120")
		http.Error(w, "later", 429)
	})
	done := make(chan error, 1)
	go func() {
		_, _, _, err := s.publicFetch(context.Background(), "https://provider.example/slow")
		done <- err
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("real TLS slow request did not start")
	}
	_, _, status, err := s.publicFetch(context.Background(), "https://provider.example/limited")
	if status != 429 || err == nil {
		t.Fatal("real failure did not establish a cooldown", status, err)
	}
	letGo()
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	_, _, status, err = s.publicFetch(context.Background(), "https://provider.example/after-success")
	var wait *researchOriginWait
	if status != 0 || !errors.As(err, &wait) || calls.Load() != 2 {
		t.Fatal("already in-flight success cleared a newer failure", status, err, calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT failure_count FROM research_origin_cooldowns")
}

func TestResearchOriginCooldownSchedulePauseCancelAndNoBudgetCharge(t *testing.T) {
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path == "/limited" {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "later", 429)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"retained"}]}`))
	})
	sourcesForSchedule(t, s, "limited", "scheduled")
	researchRunID(t, s, ResearchOptions{SourceIDs: []string{"limited"}, Budget: 1})
	s.runResearchPending(context.Background())
	saveScheduleForTest(t, s, "origin", ResearchOptions{SourceIDs: []string{"scheduled"}, Budget: 3}, 3)
	controlScheduleForTest(t, s, "origin", "ENABLE")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	run := scheduleForTest(t, s, "origin").LastRunID
	if run == "" || retryTaskForTest(t, s, run).Status != "QUEUED" {
		t.Fatal("schedule did not retain waiting public task")
	}
	assertSQLInt(t, s, 0, "SELECT COALESCE(SUM(used),0) FROM research_schedule_daily")
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", run)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "PAUSE"}), 200)
	s = reopenScheduleStore(t, s)
	clock.Add(120000)
	s.workerTick(context.Background())
	if calls.Load() != 1 {
		t.Fatal("pause was bypassed after cooldown expired and service restarted")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if calls.Load() != 2 {
		t.Fatal("explicit schedule resume did not use the waiting task")
	}
	assertSQLInt(t, s, 2, "SELECT COALESCE(SUM(used),0) FROM research_schedule_daily") // global + schedule
	assertSQLInt(t, s, 1, "SELECT attempts FROM research_tasks WHERE run_id=?", run)
}

func TestResearchOriginCooldownHistorySyncDoesNotControlPeer(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "120")
		http.Error(w, "later", 429)
	}))
	defer up.Close()
	hub.PublicClient, peer.PublicClient = mappedFixture(up), mappedFixture(up)
	sourcesForSchedule(t, hub, "limited", "waiting")
	run := researchRunID(t, hub, ResearchOptions{SourceIDs: []string{"limited", "waiting"}, Budget: 5})
	hub.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("hub did not apply shared cooldown")
	}
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_origin_cooldowns")
	peer.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("synchronized waiting history became executable")
	}
	assertSQLInt(t, peer, 2, "SELECT COUNT(*) FROM research_tasks WHERE run_id=?", run)
	// Editing peer-local admission state cannot silently clear the hub's wait.
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM research_origin_cooldowns")
}

func TestResearchOriginCooldownV19UpgradeAndUnavailableStore(t *testing.T) {
	s := testServer(t)
	sourcesForSchedule(t, s, "retained")
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"retained"}, Budget: 3})
	if _, err := s.Store.DB.Exec("DROP TABLE research_origin_cooldowns; PRAGMA user_version=19"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, 20, "PRAGMA user_version")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_origin_cooldowns")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=?", run)
	backups, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", "schema-19-to-20-*.sqlite"))
	if err != nil || len(backups) != 1 {
		t.Fatal("pre-migration consistent snapshot missing", err, backups)
	}
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer up.Close()
	s.PublicClient = mappedFixture(up)
	if _, err := s.Store.DB.Exec("DROP TABLE research_origin_cooldowns"); err != nil {
		t.Fatal(err)
	}
	_, _, _, err = s.publicFetch(context.Background(), "https://provider.example/retained")
	if err == nil || calls.Load() != 0 {
		t.Fatal("unavailable admission storage allowed an unmetered public request")
	}
	var wait *researchOriginWait
	if errors.As(err, &wait) {
		t.Fatal("storage failure was misreported as a remote cooldown")
	}
}
