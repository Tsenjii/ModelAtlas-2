package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func aaFixturePage(w http.ResponseWriter, page, total int) {
	data := []any{}
	for i := 0; i < 2; i++ {
		id := "aa-model-" + strconv.Itoa((page-1)*2+i)
		data = append(data, map[string]any{"id": id, "slug": id, "name": id, "evaluations": map[string]any{"artificial_analysis_intelligence_index": 90 - (page-1)*2 - i}})
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"intelligence_index_version": 4.1, "pagination": map[string]any{"page": page, "total_pages": total, "has_more": page < total}, "data": data})
}

func TestAAResearchResumesFailedPageAfterRestart(t *testing.T) {
	var first, second atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != aaPath || r.Header.Get("x-api-key") != "aa-fixture-key" {
			t.Error("AA credential or endpoint boundary")
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page == 1 {
			first.Add(1)
		} else if second.Add(1) == 1 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "remote-private-body", 429)
			return
		}
		aaFixturePage(w, page, 2)
	})
	s.Client = s.PublicClient
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "AA", "secret": "aa-fixture-key"}), 200)
	run := researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: 5})
	s.runResearchPending(context.Background())
	if task := retryTaskForTest(t, s, run); task.Status != "QUEUED" {
		t.Fatalf("failed AA page must wait durably rather than discard completed pages: %s %s", task.Status, task.Result)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM aa_snapshots WHERE status='VALID'")
	assertSQLInt(t, s, 2, "SELECT used FROM integration_windows WHERE name='AA'")
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	clock.Add(119999)
	s.runResearchPending(context.Background())
	if first.Load() != 1 || second.Load() != 1 {
		t.Fatal("restart ignored Retry-After or repeated completed page")
	}
	clock.Add(1)
	s.runResearchPending(context.Background())
	if task := retryTaskForTest(t, s, run); task.Status != "SUCCESS" || first.Load() != 1 || second.Load() != 2 {
		t.Fatalf("page resume did not complete with exactly three HTTP requests: %s %s first=%d second=%d", task.Status, task.Result, first.Load(), second.Load())
	}
	assertSQLInt(t, s, 3, "SELECT used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 3, "SELECT used FROM integration_windows WHERE name='AA'")
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM aa_models")
}

func aaCheckpointForTest(t *testing.T, s *Server, run string) *aaResearchCheckpoint {
	t.Helper()
	c, err := loadAACheckpoint(context.Background(), s.Store.DB, retryTaskForTest(t, s, run).ID)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func aaResearchFixture(t *testing.T, handler http.HandlerFunc) (*Server, *atomic.Int64) {
	t.Helper()
	s, clock := retryFixture(t, handler)
	s.Client = s.PublicClient
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "AA", "secret": "aa-fixture-key"}), 200)
	return s, clock
}

func TestAAResearchAdmissionChargesBothBudgetsOnlyForAdmittedPages(t *testing.T) {
	for _, exhausted := range []string{"run", "integration", "scheduled-day"} {
		t.Run(exhausted, func(t *testing.T) {
			var first, second atomic.Int32
			s, _ := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
				page, _ := strconv.Atoi(r.URL.Query().Get("page"))
				if page == 1 {
					first.Add(1)
				} else {
					second.Add(1)
				}
				aaFixturePage(w, page, 2)
			})
			budget := 5
			if exhausted == "run" {
				budget = 1
			}
			if exhausted == "integration" {
				if e := s.chargeIntegrationWindow(context.Background(), "AA", 100); e != nil {
					t.Fatal(e)
				}
				s.Store.DB.Exec("UPDATE integration_windows SET used=99 WHERE name='AA'")
			}
			var run string
			if exhausted == "scheduled-day" {
				saveScheduleForTest(t, s, "aa-day", ResearchOptions{RefreshAA: true, Budget: budget}, 1)
				controlScheduleForTest(t, s, "aa-day", "ENABLE")
				s.researchScheduleTick(context.Background())
				run = scheduleForTest(t, s, "aa-day").LastRunID
			} else {
				run = researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: budget})
			}
			s.runResearchPending(context.Background())
			if first.Load() != 1 || second.Load() != 0 || aaCheckpointForTest(t, s, run).NextPage != 2 {
				t.Fatal("local admission lost completed page or sent a rejected request")
			}
			assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", run)
			used := 1
			if exhausted == "integration" {
				used = 100
			}
			assertSQLInt(t, s, used, "SELECT used FROM integration_windows WHERE name='AA'")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM aa_snapshots WHERE status='VALID'")
			if exhausted == "scheduled-day" {
				assertSQLInt(t, s, 1, "SELECT used FROM research_schedule_daily WHERE scope='global'")
				return
			}
			if exhausted == "integration" {
				s.Store.DB.Exec("UPDATE integration_windows SET reset_at=0 WHERE name='AA'")
			}
			requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME", "additional_budget": 1}), 200)
			s.runResearchPending(context.Background())
			if first.Load() != 1 || second.Load() != 1 || retryTaskForTest(t, s, run).Status != "SUCCESS" {
				t.Fatal("budget resume repeated a completed page")
			}
			assertSQLInt(t, s, 2, "SELECT used FROM research_run_options WHERE run_id=?", run)
		})
	}
}

func TestAAResearchRetryLimitIsPerPageAndContinuationKeepsJournal(t *testing.T) {
	var calls [5]atomic.Int32
	s, clock := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if calls[page].Add(1) == 1 && page%2 == 0 {
			w.Header().Set("Retry-After", "1800")
			http.Error(w, "not persisted", 503)
			return
		}
		aaFixturePage(w, page, 4)
	})
	saveScheduleForTest(t, s, "aa-chain", ResearchOptions{RefreshAA: true, Budget: 2}, 20)
	controlScheduleForTest(t, s, "aa-chain", "ENABLE")
	s.workerTick(context.Background())
	first := scheduleForTest(t, s, "aa-chain").LastRunID
	c := aaCheckpointForTest(t, s, first)
	deadline := c.NextAttemptAt
	if c.NextPage != 2 || len(c.History) != 1 {
		t.Fatal("missing first durable page failure")
	}
	clock.Add(15 * 60000)
	s.workerTick(context.Background())
	second := scheduleForTest(t, s, "aa-chain").LastRunID
	if second == first || aaCheckpointForTest(t, s, second).NextAttemptAt != deadline || calls[2].Load() != 1 {
		t.Fatal("continuation reset checkpoint, retry allowance or deadline")
	}
	s = reopenScheduleStore(t, s)
	clock.Store(deadline)
	s.workerTick(context.Background()) // page 2 then 3; new round budget consumed
	if calls[1].Load() != 1 || calls[2].Load() != 2 || calls[3].Load() != 1 || aaCheckpointForTest(t, s, second).NextPage != 4 {
		t.Fatal("continued pages were lost or repeated")
	}
	clock.Add(15 * 60000)
	s.workerTick(context.Background()) // page 4's independent retry allowance
	third := scheduleForTest(t, s, "aa-chain").LastRunID
	c = aaCheckpointForTest(t, s, third)
	if c.NextPage != 4 || len(c.History) != 2 || c.History[1].Attempt != 1 {
		t.Fatal("page 4 inherited page 2 attempts or lost history")
	}
	clock.Store(c.NextAttemptAt)
	s.workerTick(context.Background())
	if retryTaskForTest(t, s, third).Status != "SUCCESS" || calls[4].Load() != 2 {
		t.Fatal("second independent page could not recover")
	}
	assertSQLInt(t, s, 6, "SELECT used FROM integration_windows WHERE name='AA'")
	assertSQLInt(t, s, 6, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	assertSQLInt(t, s, 8, "SELECT COUNT(*) FROM aa_models")
	if aaCheckpointForTest(t, s, third).Snapshot.ValidUntil != c.Snapshot.ValidUntil {
		t.Fatal("continuation extended evidence freshness")
	}
}

func seedAALastGood(t *testing.T, s *Server) {
	t.Helper()
	if _, e := s.Store.DB.Exec("INSERT INTO aa_snapshots VALUES('aa-last-good','4.1','VALID',?,?,?,'previous')", aaOrigin+aaPath, now()-1000, now()+60000); e != nil {
		t.Fatal(e)
	}
}

func TestAAResearchStopsWithoutReplacingLastGood(t *testing.T) {
	for _, mode := range []string{"attempt-limit", "long-wait", "key-change", "expired", "version-change", "page-count-change", "duplicate", "secret-echo", "malformed", "auth"} {
		t.Run(mode, func(t *testing.T) {
			var first, second atomic.Int32
			s, clock := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
				page, _ := strconv.Atoi(r.URL.Query().Get("page"))
				if page == 1 {
					first.Add(1)
					aaFixturePage(w, page, 2)
					return
				}
				second.Add(1)
				switch mode {
				case "attempt-limit", "key-change", "expired", "long-wait":
					header := "120"
					if mode == "long-wait" {
						header = "99999999999999999999999"
					}
					w.Header().Set("Retry-After", header)
					http.Error(w, "remote-private-body", 429)
				case "auth":
					http.Error(w, "remote-private-body", 401)
				case "malformed":
					w.Write([]byte("not-json"))
				default:
					recorded := httptest.NewRecorder()
					aaFixturePage(recorded, page, 2)
					raw := recorded.Body.String()
					switch mode {
					case "version-change":
						raw = strings.Replace(raw, "4.1", "4.2", 1)
					case "page-count-change":
						raw = strings.ReplaceAll(strings.Replace(raw, "\"total_pages\":2", "\"total_pages\":3", 1), "\"has_more\":false", "\"has_more\":true")
					case "duplicate":
						raw = strings.ReplaceAll(raw, "aa-model-2", "aa-model-0")
					case "secret-echo":
						raw = strings.ReplaceAll(raw, "aa-model-2", "aa-fixture-key")
					}
					w.Write([]byte(raw))
				}
			})
			seedAALastGood(t, s)
			run := researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: 10})
			s.runResearchPending(context.Background())
			switch mode {
			case "attempt-limit":
				for i := 0; i < 2; i++ {
					clock.Store(aaCheckpointForTest(t, s, run).NextAttemptAt)
					s.runResearchPending(context.Background())
				}
			case "key-change":
				requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "AA", "secret": "replacement-fixture-key"}), 200)
				clock.Add(120000)
				s.runResearchPending(context.Background())
			case "expired":
				clock.Add(24 * 60 * 60000)
				s.runResearchPending(context.Background())
			}
			c := aaCheckpointForTest(t, s, run)
			if c.StoppedReason == "" || retryTaskForTest(t, s, run).Status != "FAILED" || first.Load() != 1 || second.Load() > 3 {
				t.Fatalf("unsafe retry or missing terminal reason: %#v first=%d second=%d", c.progress(), first.Load(), second.Load())
			}
			if mode == "attempt-limit" && second.Load() != 3 || mode != "attempt-limit" && second.Load() != 1 {
				t.Fatal("unexpected network retries")
			}
			last, e := s.aaSnapshot(context.Background())
			if e != nil || last.ID != "aa-last-good" || last.ValidUntil > now()+60000 {
				t.Fatal("partial/failed ranking replaced or extended last-good")
			}
			incomplete := 1
			if mode == "attempt-limit" {
				incomplete = 3
			} else if mode == "key-change" || mode == "expired" {
				incomplete = 2
			}
			assertSQLInt(t, s, incomplete, "SELECT COUNT(*) FROM aa_snapshots WHERE status='FAILED'")
			var local string
			s.Store.DB.QueryRow("SELECT state_json FROM research_aa_checkpoints").Scan(&local)
			public := string(retryTaskForTest(t, s, run).Result)
			for _, secret := range []string{"aa-fixture-key", "replacement-fixture-key", "remote-private-body"} {
				if strings.Contains(local+public, secret) {
					t.Fatal("secret or remote error body entered checkpoint/history")
				}
			}
			before := first.Load() + second.Load()
			requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME", "retry_failed": true}), 409)
			clock.Add(48 * 60 * 60000)
			s.runResearchPending(context.Background())
			if first.Load()+second.Load() != before {
				t.Fatal("terminal task silently resumed")
			}
		})
	}
}

func TestAAResearchCheckpointSyncIsHistoryOnlyAndUpgradeRetainsData(t *testing.T) {
	var calls atomic.Int32
	hub, _ := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page == 2 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "not persisted", 503)
			return
		}
		aaFixturePage(w, page, 2)
	})
	run := researchRunID(t, hub, ResearchOptions{RefreshAA: true, Budget: 5})
	hub.runResearchPending(context.Background())
	peer := testServer(t)
	peer.Client, peer.scheduleClock = hub.Client, hub.scheduleClock
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_aa_checkpoints")
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=?", run)
	peer.runResearchPending(context.Background())
	if calls.Load() != 2 {
		t.Fatal("synchronized history activated an integration request")
	}
	if strings.Contains(req(t, peer, "GET", "/api/v1/research/tasks?run_id="+run, nil).Body.String(), "credential_stamp") {
		t.Fatal("local credential revision leaked through history sync")
	}
	// A real v20 upgrade keeps history, takes a consistent snapshot and cannot
	// invent page checkpoints or activate work for the previous application.
	legacy := testServer(t)
	seedAALastGood(t, legacy)
	if _, e := legacy.Store.DB.Exec("DROP TABLE research_aa_checkpoints; PRAGMA user_version=20"); e != nil {
		t.Fatal(e)
	}
	legacy = reopenScheduleStore(t, legacy)
	assertSQLInt(t, legacy, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, legacy, 0, "SELECT COUNT(*) FROM research_aa_checkpoints")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE id='aa-last-good'")
	backups, e := filepath.Glob(filepath.Join(filepath.Dir(legacy.Store.path), "upgrade-backups", fmt.Sprintf("schema-20-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(backups) != 1 || verifySnapshot(backups[0]) != nil {
		t.Fatal("missing consistent pre-upgrade snapshot", backups, e)
	}
	if _, e = hub.Store.DB.Exec("DROP TABLE research_aa_checkpoints"); e != nil {
		t.Fatal(e)
	}
	// Storage loss fails closed instead of replaying page 1.
	hub.scheduleClock = func() time.Time { return time.Now().Add(time.Hour) }
	hub.runResearchPending(context.Background())
	if calls.Load() != 2 || retryTaskForTest(t, hub, run).Status != "FAILED" {
		t.Fatal("missing checkpoint storage caused an unsafe replay")
	}
}

func TestAAResearchInFlightRestartsCannotResetPageAttemptLimit(t *testing.T) {
	entered := make(chan int, 3)
	var calls atomic.Int32
	s, _ := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("page") == "1" {
			aaFixturePage(w, 1, 2)
			return
		}
		entered <- int(calls.Add(1))
		<-r.Context().Done()
	})
	run := researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: 10})
	for attempt := 1; attempt <= 3; attempt++ {
		done := make(chan struct{})
		go func() { defer close(done); s.runResearchPending(context.Background()) }()
		select {
		case n := <-entered:
			if n != attempt {
				t.Fatal("unexpected actual TLS attempt", n)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("AA page did not enter real TLS transport")
		}
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "PAUSE"}), 200)
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("pause did not cancel AA transport")
		}
		c := aaCheckpointForTest(t, s, run)
		if c.Attempts != attempt || c.NextPage != 2 || c.StoppedReason != "" {
			t.Fatalf("interrupted checkpoint lost page or falsely blamed credential: %+v", c.progress())
		}
		s = reopenScheduleStore(t, s)
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	}
	s.runResearchPending(context.Background())
	if calls.Load() != 3 || aaCheckpointForTest(t, s, run).StoppedReason != "ATTEMPT_LIMIT" || retryTaskForTest(t, s, run).Status != "FAILED" {
		t.Fatal("restarts reset the page's admitted attempt limit")
	}
	assertSQLInt(t, s, 4, "SELECT used FROM integration_windows WHERE name='AA'")
	assertSQLInt(t, s, 4, "SELECT used FROM research_run_options WHERE run_id=?", run)
}

func TestAAResearchKeyChangedDuringLastPageCannotPublish(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	s, _ := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page == 2 {
			close(entered)
			select {
			case <-release:
			case <-r.Context().Done():
				return
			}
		}
		aaFixturePage(w, page, 2)
	})
	defer close(release)
	seedAALastGood(t, s)
	run := researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: 5})
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(context.Background()) }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("final page did not reach TLS handler")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "AA", "secret": "new-final-page-key"}), 200)
	release <- struct{}{}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("AA task did not finish after release")
	}
	if aaCheckpointForTest(t, s, run).StoppedReason != "CREDENTIAL_CHANGED" || retryTaskForTest(t, s, run).Status != "FAILED" {
		t.Fatal("late response published a ranking after credential revision changed")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE status='VALID'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE id='aa-last-good' AND status='VALID'")
}

func TestAAResearchDirectSyncExcludesWorkerAndCompletedReplayUsesNoHTTP(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	s, clock := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			close(entered)
			select {
			case <-release:
			case <-r.Context().Done():
				return
			}
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		aaFixturePage(w, page, 2)
	})
	defer close(release)
	response := make(chan int, 1)
	go func() { response <- req(t, s, "POST", "/api/v1/research/aa/sync", map[string]any{}).Code }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("direct API did not start AA sync")
	}
	run := researchRunID(t, s, ResearchOptions{RefreshAA: true, Budget: 5})
	s.runResearchPending(context.Background())
	if task := retryTaskForTest(t, s, run); task.Status != "QUEUED" || task.Attempts != 0 {
		t.Fatal("busy integration counted as a failed request", task)
	}
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", run)
	release <- struct{}{}
	select {
	case code := <-response:
		if code != 200 {
			t.Fatal("explicit sync did not complete", code)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("direct AA sync did not finish")
	}
	clock.Add(1000)
	s.runResearchPending(context.Background())
	if calls.Load() != 4 || !aaCheckpointForTest(t, s, run).Complete {
		t.Fatal("worker did not resume after exclusive sync completed")
	}
	// Simulate a crash after the atomic ranking/checkpoint commit but before
	// the worker's terminal status write. Replaying must not send another GET.
	s.Store.DB.Exec("UPDATE research_tasks SET status='INTERRUPTED' WHERE run_id=?", run)
	s.Store.DB.Exec("UPDATE research_runs SET status='PAUSED' WHERE id=?", run)
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
	s.runResearchPending(context.Background())
	if calls.Load() != 4 || retryTaskForTest(t, s, run).Status != "SUCCESS" {
		t.Fatal("completed checkpoint replay duplicated HTTP or ranking")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM aa_snapshots WHERE status='VALID'")
}
