package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestBraveResearchRateLimitWaitsDurably(t *testing.T) {
	s, _ := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "120")
		http.Error(w, "private body", 429)
	})
	s.Client = s.PublicClient
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "brave-fixture-key"}), 200)
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
	s.runResearchPending(context.Background())
	if task := retryTaskForTest(t, s, run); task.Status != "QUEUED" {
		t.Fatalf("Brave 429 must retain a durable bounded wait: %s %s", task.Status, task.Result)
	}
}

func braveFixture(t *testing.T, handler http.HandlerFunc) (*Server, *atomic.Int64) {
	t.Helper()
	s, clock := retryFixture(t, handler)
	s.Client = s.PublicClient
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "brave-fixture-key"}), 200)
	return s, clock
}
func braveCheckpointForTest(t *testing.T, s *Server, run string) *braveResearchCheckpoint {
	t.Helper()
	c, err := loadBraveCheckpoint(context.Background(), s.Store.DB, retryTaskForTest(t, s, run).ID)
	if err != nil {
		t.Fatal(err)
	}
	return c
}
func braveFixturePage(w http.ResponseWriter, more bool, urls ...string) {
	rows := []any{}
	for _, url := range urls {
		rows = append(rows, map[string]any{"url": url, "title": "公開 API 文件", "description": "未經驗證的免費線索"})
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"query": map[string]any{"more_results_available": more}, "web": map[string]any{"results": rows}})
}

func TestBraveResearchAdmissionIsAtomicAndRotatingKeyDoesNotResetGlobalCap(t *testing.T) {
	for _, gate := range []string{"run", "integration", "scheduled-day"} {
		t.Run(gate, func(t *testing.T) {
			var calls atomic.Int32
			s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); braveFixturePage(w, false) })
			run := ""
			if gate == "scheduled-day" {
				saveScheduleForTest(t, s, "brave-gate", ResearchOptions{Search: true, Budget: 5}, 1)
				controlScheduleForTest(t, s, "brave-gate", "ENABLE")
				s.researchScheduleTick(context.Background())
				run = scheduleForTest(t, s, "brave-gate").LastRunID
				s.Store.DB.Exec("INSERT INTO research_schedule_daily VALUES(?,'global',1),(?,'schedule:brave-gate',1)", s.scheduleTime().Format("2006-01-02"), s.scheduleTime().Format("2006-01-02"))
			} else {
				run = researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
			}
			if gate == "run" {
				s.Store.DB.Exec("UPDATE research_run_options SET used=budget WHERE run_id=?", run)
			}
			if gate == "integration" {
				s.Store.DB.Exec("INSERT INTO request_budgets VALUES('BRAVE_SEARCH',?,1000)", s.scheduleTime().Format("2006-01-02"))
			}
			s.runResearchPending(context.Background())
			if calls.Load() != 0 {
				t.Fatal("locally denied request reached network")
			}
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_task_usage")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_brave_daily")
			assertSQLInt(t, s, 0, "SELECT SUM(CAST(json_extract(state_json,'$.attempts') AS INTEGER)) FROM research_brave_checkpoints")
			if gate == "integration" {
				assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", run)
				requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "new-brave-fixture-key"}), 200)
				other := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "another search"}, Budget: 3})
				s.runResearchPending(context.Background())
				if calls.Load() != 0 {
					t.Fatal("rotation reset shared local cap")
				}
				assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", other)
				clock.Add(int64(24 * time.Hour / time.Millisecond))
				fresh := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "next day"}, Budget: 3})
				s.runResearchPending(context.Background())
				if calls.Load() != 1 {
					t.Fatal("UTC day did not restore admission")
				}
				assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", fresh)
			}
			if gate == "scheduled-day" {
				assertSQLInt(t, s, 1, "SELECT used FROM research_schedule_daily WHERE scope='global'")
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM request_budgets WHERE name='BRAVE_SEARCH'")
			}
		})
	}
}

func TestBraveResearchOverlapCompleteReplayAndScopedReflection(t *testing.T) {
	var searches, reads atomic.Int32
	s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" {
			reads.Add(1)
			if r.Header.Get("X-Subscription-Token") != "" || r.Header.Get("Authorization") != "" {
				t.Error("key forwarded to discovered site")
			}
			w.Write([]byte("<p>API documentation</p>"))
			return
		}
		searches.Add(1)
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		if body["country"] != "JP" || body["search_lang"] != "zh-hant" || body["count"] != float64(10) {
			t.Error("frozen region/language/page size lost")
		}
		if body["offset"] == float64(0) {
			braveFixturePage(w, true, "https://provider.example/free", "https://provider.example/free")
		} else {
			braveFixturePage(w, false, "https://provider.example/free", "https://other.example/free")
		}
	})
	run := researchRunID(t, s, ResearchOptions{Search: true, SearchPages: 4, SearchCountry: "JP", Queries: map[string]string{"zh": "免費 API"}, Budget: 8})
	s.workerTick(context.Background())
	c := braveCheckpointForTest(t, s, run)
	if !c.Complete || c.NextOffset != 2 || c.DuplicateHits != 2 || len(c.CandidateIDs) != 2 || searches.Load() != 2 || reads.Load() != 2 {
		t.Fatalf("overlap/termination %v requests=%d/%d", c, searches.Load(), reads.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_local_evidence WHERE json_extract(record_json,'$.kind')='SEARCH_DISCOVERY'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	task := retryTaskForTest(t, s, run)
	s.Store.DB.Exec("UPDATE research_tasks SET status='QUEUED' WHERE id=?", task.ID)
	s.Store.DB.Exec("UPDATE research_runs SET status='QUEUED' WHERE id=?", run)
	s.runResearchPending(context.Background())
	if searches.Load() != 2 {
		t.Fatal("completed page replay sent HTTP")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
	if err := s.reflectResearch(context.Background(), run); err != nil {
		t.Fatal(err)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(DISTINCT json_extract(data_json,'$.scope_hash')) FROM knowledge_versions WHERE kind='RESEARCH_STRATEGY' AND json_extract(data_json,'$.search_country')='JP' AND json_extract(data_json,'$.search_pages')=4")
	if researchInputScope("SEARCH", taskInput{Language: "zh", Query: "免費 API"}) == c.ScopeHash {
		t.Fatal("different regions/pages share quality scope")
	}
	for _, v := range allCandidates(t, s) {
		if v.State == "VERIFIED" || v.Taiwan == "YES" {
			t.Fatal("search discovery became verified qualification")
		}
	}
}

func TestBraveResearchBoundedRetriesAndSharedCooldown(t *testing.T) {
	var calls atomic.Int32
	s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "120")
		http.Error(w, "private remote body", 503)
	})
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API", "zh": "免費 API"}, Budget: 10})
	for attempt := 1; attempt <= 3; attempt++ {
		s.runResearchPending(context.Background())
		if calls.Load() != int32(attempt) {
			t.Fatal("shared origin allowed another language to bypass cooldown", calls.Load())
		}
		c := braveCheckpointForTest(t, s, run)
		if attempt < 3 {
			clock.Store(c.NextAttemptAt)
		} else if c.StoppedReason != "ATTEMPT_LIMIT" || len(c.History) != 3 || c.Attempts != 3 {
			t.Fatal("lost attempt bound or journal", c)
		}
	}
	// The first language is terminal; another language is still independently
	// waiting for the same origin. Retrying terminal work cannot reset its cap.
	task := retryTaskForTest(t, s, run)
	s.Store.DB.Exec("UPDATE research_tasks SET status='QUEUED' WHERE id=?", task.ID)
	s.runResearchPending(context.Background())
	if calls.Load() != 3 {
		t.Fatal("terminal checkpoint reissued HTTP")
	}
	assertSQLInt(t, s, 3, "SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH'")
	raw := req(t, s, "GET", "/api/v1/research/tasks?run_id="+run, nil).Body.String()
	if strings.Contains(raw, "brave-fixture-key") || strings.Contains(raw, "credential_stamp") || strings.Contains(raw, "private remote body") {
		t.Fatal("secret or local revision in public history")
	}
}

func TestBraveResearchFailureSemanticsAndOptionBounds(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		reason string
		pages  int
	}{
		{"auth", 401, `{"error":"private"}`, "HTTP_REJECTED", 2},
		{"redirect", 302, ``, "HTTP_REJECTED", 2},
		{"schema", 200, `{"web":{}}`, "INVALID_SCHEMA", 2},
		{"bad-more", 200, `{"query":{"more_results_available":"true"},"web":{"results":[]}}`, "INVALID_SCHEMA", 2},
		{"missing-hint", 200, `{"web":{"results":[]}}`, "PAGINATION_UNAVAILABLE", 2},
		{"no-more", 200, `{"query":{"more_results_available":false},"web":{"results":[]}}`, "", 10},
		{"legacy-one-page", 200, `{"web":{"results":[]}}`, "", 0},
		{"ten-page-limit", 200, `{"query":{"more_results_available":true},"web":{"results":[]}}`, "", 10},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				w.Header().Set("Location", "https://attacker.example/leak")
				w.WriteHeader(tc.status)
				w.Write([]byte(tc.body))
			})
			run := researchRunID(t, s, ResearchOptions{Search: true, SearchPages: tc.pages, Queries: map[string]string{"en": "free API"}, Budget: 20})
			s.runResearchPending(context.Background())
			c := braveCheckpointForTest(t, s, run)
			if c.StoppedReason != tc.reason || c.Complete != (tc.reason == "") {
				t.Fatalf("unexpected completion: %#v", c)
			}
			expected := int32(1)
			if tc.name == "ten-page-limit" {
				expected = 10
			}
			if calls.Load() != expected {
				t.Fatal("unsafe redirect, unnecessary page or wrong cap", calls.Load())
			}
		})
	}
	s := testServer(t)
	for _, in := range []map[string]any{{"search_pages": 11}, {"search_pages": -1}, {"search_country": "XX"}, {"search_country": "us"}} {
		in["search"] = true
		requireCode(t, req(t, s, "POST", "/api/v1/research/start", in), 400)
	}
}

func TestBraveResearchCheckpointSurvivesScheduledBudgetContinuation(t *testing.T) {
	researchBraveContinuationForTest(t, researchContinuationAnchor(12, 0))
	t.Run("UTC-midnight", func(t *testing.T) { researchBraveContinuationForTest(t, researchContinuationAnchor(23, 45)) })
}

func researchBraveContinuationForTest(t *testing.T, start time.Time) {
	var offsets [3]atomic.Int32
	s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" {
			w.Write([]byte("<p>Public API</p>"))
			return
		}
		var body struct {
			Offset int `json:"offset"`
		}
		json.NewDecoder(r.Body).Decode(&body)
		offsets[body.Offset].Add(1)
		if body.Offset == 1 && offsets[1].Load() == 1 {
			w.Header().Set("Retry-After", "1800")
			http.Error(w, "not stored", 429)
			return
		}
		braveFixturePage(w, body.Offset < 2)
	})
	clock.Store(start.UnixMilli())
	defer func() { traceResearchContinuationDays(t, s, clock, start) }()
	saveScheduleForTest(t, s, "brave-chain", ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, SearchPages: 3, SearchCountry: "ALL", Budget: 2}, 20)
	controlScheduleForTest(t, s, "brave-chain", "ENABLE")
	s.workerTick(context.Background())
	first := scheduleForTest(t, s, "brave-chain").LastRunID
	c := braveCheckpointForTest(t, s, first)
	if c.NextOffset != 1 || c.Attempts != 1 || len(c.History) != 1 {
		t.Fatal("page journal not saved", c)
	}
	deadline := c.NextAttemptAt
	id := c.ID
	clock.Add(15 * 60000)
	s.workerTick(context.Background())
	second := scheduleForTest(t, s, "brave-chain").LastRunID
	c = braveCheckpointForTest(t, s, second)
	if first == second || c.ID != id || c.NextAttemptAt != deadline || offsets[0].Load() != 1 || offsets[1].Load() != 1 {
		t.Fatal("continuation lost page, identity or Retry-After")
	}
	s = reopenScheduleStore(t, s)
	clock.Store(deadline)
	s.workerTick(context.Background())
	c = braveCheckpointForTest(t, s, second)
	if !c.Complete || c.NextOffset != 3 || offsets[0].Load() != 1 || offsets[1].Load() != 2 || offsets[2].Load() != 1 {
		t.Fatal("continued pages repeated or not finished", c)
	}
	if start.UTC().Format("2006-01-02") == start.Add(30*time.Minute).UTC().Format("2006-01-02") {
		assertSQLInt(t, s, 4, "SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH'")
		assertSQLInt(t, s, 4, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	}
	assertResearchContinuationDayReceipts(t, s, "brave-chain", "BRAVE_SEARCH",
		researchContinuationCharge{start, 2}, researchContinuationCharge{start.Add(30 * time.Minute), 2})
	assertSQLInt(t, s, 4, "SELECT SUM(http_requests) FROM research_task_usage")
}

func TestBraveResearchCheckpointSyncIsReadOnlyAndV23UpgradeKeepsHistory(t *testing.T) {
	var calls atomic.Int32
	hub, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "120")
		http.Error(w, "not stored", 429)
	})
	run := researchRunID(t, hub, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
	hub.runResearchPending(context.Background())
	peer := testServer(t)
	peer.Client, peer.scheduleClock = hub.Client, hub.scheduleClock
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_brave_checkpoints")
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_brave_daily")
	peer.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("synced history executed search")
	}
	legacy := testServer(t)
	seedAALastGood(t, legacy)
	if _, err := legacy.Store.DB.Exec("DROP TABLE research_brave_checkpoints;DROP TABLE research_brave_daily;PRAGMA user_version=23"); err != nil {
		t.Fatal(err)
	}
	legacy = reopenScheduleStore(t, legacy)
	assertSQLInt(t, legacy, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM aa_snapshots WHERE id='aa-last-good'")
	assertSQLInt(t, legacy, 0, "SELECT COUNT(*) FROM research_brave_checkpoints")
	files, err := filepath.Glob(filepath.Join(filepath.Dir(legacy.Store.path), "upgrade-backups", fmt.Sprintf("schema-23-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("missing valid upgrade snapshot", files, err)
	}
	// Local storage loss cannot reconstruct authority from public progress.
	hub.Store.DB.Exec("DELETE FROM research_brave_checkpoints")
	hub.scheduleClock = func() time.Time { return time.Now().Add(time.Hour) }
	hub.runResearchPending(context.Background())
	if calls.Load() != 1 || retryTaskForTest(t, hub, run).Status != "FAILED" {
		t.Fatal("missing local state replayed first page")
	}
}

func TestBraveResearchLateKeyChangePauseAndCrashAttempts(t *testing.T) {
	for _, control := range []string{"rotate", "pause", "cancel"} {
		t.Run(control, func(t *testing.T) {
			entered, release := make(chan struct{}), make(chan struct{})
			var calls atomic.Int32
			s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				close(entered)
				<-release
				braveFixturePage(w, false, "https://provider.example/free")
			})
			run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
			done := make(chan struct{})
			go func() { defer close(done); s.runResearchPending(context.Background()) }()
			<-entered
			if control == "rotate" {
				requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "new-brave-fixture-key"}), 200)
			} else {
				requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": strings.ToUpper(control)}), 200)
			}
			close(release)
			<-done
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
			assertSQLInt(t, s, 1, "SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH'")
			if calls.Load() != 1 {
				t.Fatal("late completion retried")
			}
			if control == "rotate" && braveCheckpointForTest(t, s, run).StoppedReason != "CREDENTIAL_CHANGED" {
				t.Fatal("changed credential accepted response")
			}
		})
	}
	t.Run("crash-bound", func(t *testing.T) {
		var calls atomic.Int32
		s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); braveFixturePage(w, false) })
		run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 6})
		task := retryTaskForTest(t, s, run)
		_, stamp, _ := s.braveCredential(context.Background())
		in := taskInput{Language: "en", Query: "free API"}
		c := &braveResearchCheckpoint{ID: newID(), ScopeHash: researchInputScope("SEARCH", in), CredentialStamp: stamp, PageLimit: 1, Country: "TW", StartedAt: clock.Load(), ValidUntil: clock.Load() + int64(time.Hour/time.Millisecond)}
		ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, run), researchTaskBudgetKey{}, task.ID)
		for i := 0; i < 3; i++ {
			if err := s.chargeBraveRequest(ctx, task.ID, c); err != nil {
				t.Fatal(err)
			}
			clock.Store(c.NextAttemptAt)
		}
		s = reopenScheduleStore(t, s)
		requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME"}), 200)
		s.runResearchPending(context.Background())
		if calls.Load() != 0 || braveCheckpointForTest(t, s, run).StoppedReason != "ATTEMPT_LIMIT" {
			t.Fatal("crash reset admitted attempts")
		}
		assertSQLInt(t, s, 3, "SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH'")
	})
}

func TestBraveResearchTransportRecoveryAndLongWait(t *testing.T) {
	for _, mode := range []string{"transport", "long-wait", "expired", "secret-url"} {
		t.Run(mode, func(t *testing.T) {
			var calls atomic.Int32
			s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
				n := calls.Add(1)
				if mode == "transport" && n == 1 {
					w.Header().Set("Content-Length", "10000")
					w.Write([]byte(`{"web":`))
					return
				}
				if mode == "long-wait" || mode == "expired" {
					w.Header().Set("Retry-After", map[bool]string{true: "86401", false: "120"}[mode == "long-wait"])
					http.Error(w, "not stored", 429)
					return
				}
				if mode == "secret-url" {
					braveFixturePage(w, false, "https://provider.example/brave-fixture-key", "http://127.0.0.1/private")
					return
				}
				braveFixturePage(w, false)
			})
			run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
			s.runResearchPending(context.Background())
			c := braveCheckpointForTest(t, s, run)
			if mode == "transport" {
				if c.NextAttemptAt == 0 || len(c.History) != 1 || c.History[0].Kind != "TRANSPORT" {
					t.Fatal("body interruption was not bounded retry")
				}
				clock.Store(c.NextAttemptAt)
				s.runResearchPending(context.Background())
				if calls.Load() != 2 || !braveCheckpointForTest(t, s, run).Complete {
					t.Fatal("transport did not recover")
				}
			}
			if mode == "long-wait" {
				if c.StoppedReason != "REMOTE_WAIT_TOO_LONG" {
					t.Fatal("long wait ignored")
				}
				other := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "another"}, Budget: 5})
				s.runResearchPending(context.Background())
				if calls.Load() != 1 || retryTaskForTest(t, s, other).Status != "QUEUED" {
					t.Fatal("new run bypassed long origin wait")
				}
			}
			if mode == "expired" {
				clock.Store(c.ValidUntil)
				s.runResearchPending(context.Background())
				if calls.Load() != 1 || braveCheckpointForTest(t, s, run).StoppedReason != "CHECKPOINT_EXPIRED" {
					t.Fatal("expired partial search continued")
				}
			}
			if mode == "secret-url" {
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_candidates")
				if c.SkippedHits != 2 {
					t.Fatal("unsafe URLs accepted")
				}
			}
		})
	}
}

func TestBraveResearchStorageFailuresRollbackAdmissionAndPage(t *testing.T) {
	for _, mode := range []string{"admission", "page"} {
		t.Run(mode, func(t *testing.T) {
			var calls atomic.Int32
			s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				braveFixturePage(w, false, "https://provider.example/free")
			})
			run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
			trigger := `CREATE TRIGGER reject_brave BEFORE UPDATE ON research_brave_checkpoints BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END;`
			if mode == "page" {
				trigger = `CREATE TRIGGER reject_brave BEFORE INSERT ON research_evidence_details BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END;`
			}
			if _, err := s.Store.DB.Exec(trigger); err != nil {
				t.Fatal(err)
			}
			s.runResearchPending(context.Background())
			c := braveCheckpointForTest(t, s, run)
			if c.NextOffset != 0 || len(c.CandidateIDs) != 0 || len(c.Hashes) != 0 {
				t.Fatal("failed transaction advanced checkpoint")
			}
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_candidates")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_evidence")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_tasks WHERE kind='INSPECT'")
			if mode == "admission" {
				if calls.Load() != 0 || c.Attempts != 0 {
					t.Fatal("failed admission sent HTTP or consumed attempt")
				}
				assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", run)
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM request_budgets WHERE name='BRAVE_SEARCH'")
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_brave_daily")
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_task_usage")
			} else {
				if calls.Load() != 1 || c.Attempts != 1 {
					t.Fatal("failed page receipt lost admitted request")
				}
				assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", run)
				s.Store.DB.Exec("DROP TRIGGER reject_brave")
				clock.Store(c.NextAttemptAt)
				s.runResearchPending(context.Background())
				if !braveCheckpointForTest(t, s, run).Complete {
					t.Fatal("page receipt could not recover")
				}
				assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
			}
		})
	}
}

func TestBraveResearchLMSpeedSearchKeepsFrozenRegionAndPageLimit(t *testing.T) {
	s := testServer(t)
	if _, err := s.Store.DB.Exec("INSERT INTO external_models VALUES('seed','canonical-model','LMSPEED','{}',?)", now()); err != nil {
		t.Fatal(err)
	}
	run := researchRunID(t, s, ResearchOptions{Search: true, LMSpeedSeeds: true, SearchCountry: "JP", SearchPages: 2, Queries: map[string]string{"en": "free API"}, Budget: 5})
	if _, err := s.executeResearchTask(context.Background(), run, "LMSPEED_SEEDS", taskInput{}); err != nil {
		t.Fatal(err)
	}
	var raw string
	if err := s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? AND dedupe='lms-search:canonical-model'", run).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var input taskInput
	if json.Unmarshal([]byte(raw), &input) != nil || input.SearchCountry != "JP" || input.SearchPages != 2 {
		t.Fatal("LMSpeed discovery silently reset explicit search settings", raw)
	}
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", run)
}

func TestBraveResearchPersistsPageAndResumesAfterRestart(t *testing.T) {
	var first, second atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" {
			w.Write([]byte("<p>Public API documentation</p>"))
			return
		}
		var body struct {
			Offset int `json:"offset"`
		}
		json.NewDecoder(r.Body).Decode(&body)
		if r.Method != "POST" || r.Header.Get("X-Subscription-Token") != "brave-fixture-key" {
			t.Error("search credential or method boundary")
		}
		if body.Offset == 0 {
			first.Add(1)
		} else if second.Add(1) == 1 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "private-upstream-body", 429)
			return
		}
		url := "https://provider.example/free"
		if body.Offset > 0 {
			url = "https://other.example/free"
		}
		json.NewEncoder(w).Encode(map[string]any{"query": map[string]any{"more_results_available": body.Offset == 0}, "web": map[string]any{"results": []any{map[string]any{"url": url, "title": "API documentation", "description": "unverified free claim"}}}})
	})
	s.Client = s.PublicClient
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "brave-fixture-key"}), 200)
	w := req(t, s, "POST", "/api/v1/research/start", map[string]any{"search": true, "queries": map[string]string{"en": "free API"}, "search_pages": 2, "budget": 10})
	requireCode(t, w, 201)
	var response struct {
		ID string `json:"id"`
	}
	json.Unmarshal(w.Body.Bytes(), &response)
	s.runResearchPending(context.Background())
	task := retryTaskForTest(t, s, response.ID)
	if task.Status != "QUEUED" || first.Load() != 1 || second.Load() != 1 {
		t.Fatalf("search must preserve page 1 and wait for page 2: %s %s first=%d second=%d", task.Status, task.Result, first.Load(), second.Load())
	}
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": response.ID, "action": "RESUME"}), 200)
	clock.Add(119999)
	s.runResearchPending(context.Background())
	if first.Load() != 1 || second.Load() != 1 {
		t.Fatal("restart bypassed Retry-After or repeated completed page")
	}
	clock.Add(1)
	s.runResearchPending(context.Background())
	if task = retryTaskForTest(t, s, response.ID); task.Status != "SUCCESS" || first.Load() != 1 || second.Load() != 2 {
		t.Fatalf("resume page 2: %s %s first=%d second=%d", task.Status, task.Result, first.Load(), second.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
}
