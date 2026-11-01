package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

func historyFixture(t *testing.T) *Server {
	t.Helper()
	s := testServer(t)
	sourcesForSchedule(t, s, "history-source")
	for _, id := range []string{"history-a", "history-b"} {
		saveScheduleForTest(t, s, id, ResearchOptions{SourceIDs: []string{"history-source"}, Budget: 2}, 10)
	}
	tx, err := s.Store.DB.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	for i := 0; i < 137; i++ {
		id := fmt.Sprintf("history-%03d", i)
		if _, err = tx.Exec("INSERT INTO research_runs(id,status,checkpoint,started_at) VALUES(?,'COMPLETED','',?)", id, 1000+i/4); err != nil {
			t.Fatal(err)
		}
		if i%3 != 0 {
			owner := "history-a"
			if i%3 == 2 {
				owner = "history-b"
			}
			if _, err = tx.Exec("INSERT INTO research_schedule_runs(schedule_id,run_id,scheduled_for,revision) VALUES(?,?,?,1)", owner, id, i); err != nil {
				t.Fatal(err)
			}
		}
	}
	if _, err = tx.Exec("INSERT INTO research_run_options(run_id,budget,used,probe_budget,probes_used,summary_json,config_json) VALUES('history-000',0,0,0,0,'{}','{}')"); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	return s
}

func historyPageForTest(t *testing.T, s *Server, query string) researchHistoryPage {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/history?"+query, nil)
	requireCode(t, w, 200)
	var page researchHistoryPage
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	return page
}

func TestResearchHistoryArchiveKeepsAllRowsStableAcrossInsertionsAndScheduleScopes(t *testing.T) {
	s := historyFixture(t)
	page := historyPageForTest(t, s, "limit=17")
	if page.Total != 137 || len(page.Runs) != 17 || page.Runs[0].ID != "history-136" || page.NextCursor == "" {
		t.Fatalf("first page: %+v", page)
	}
	for _, row := range []struct {
		id      string
		started int
	}{{"history-new", 5000}, {"history-imported-old", 1}} {
		if _, err := s.Store.DB.Exec("INSERT INTO research_runs(id,status,checkpoint,started_at) VALUES(?,'COMPLETED','',?)", row.id, row.started); err != nil {
			t.Fatal(err)
		}
	}
	s = reopenScheduleStore(t, s)
	var runs []ResearchRun
	for {
		if page.Total != 137 || len(page.Runs) > 17 {
			t.Fatalf("snapshot moved: %+v", page)
		}
		runs = append(runs, page.Runs...)
		if page.NextCursor == "" {
			break
		}
		page = historyPageForTest(t, s, "limit=17&cursor="+url.QueryEscape(page.NextCursor))
	}
	if len(runs) != 137 {
		t.Fatalf("lost history: %d", len(runs))
	}
	for i, r := range runs {
		if r.ID != fmt.Sprintf("history-%03d", 136-i) {
			t.Fatalf("tied ordering/duplication at %d: %s", i, r.ID)
		}
	}
	if runs[0].Budget != nil || runs[0].Used != nil || runs[136].Budget == nil || *runs[136].Budget != 0 || runs[136].Used == nil || *runs[136].Used != 0 {
		t.Fatal("unknown and known zero budgets were conflated")
	}
	fresh := historyPageForTest(t, s, "limit=1")
	if fresh.Total != 139 || fresh.Runs[0].ID != "history-new" {
		t.Fatalf("fresh snapshot: %+v", fresh)
	}
	page = historyPageForTest(t, s, "limit=7&schedule_id=history-a")
	requireCode(t, req(t, s, "GET", "/api/v1/research/history?limit=7&schedule_id=history-b&cursor="+url.QueryEscape(page.NextCursor), nil), 400)
	seen := map[string]bool{}
	for {
		if page.Total != 46 || len(page.Runs) > 7 {
			t.Fatalf("owner count: %+v", page)
		}
		for _, r := range page.Runs {
			if seen[r.ID] || r.ScheduleID != "history-a" || r.ScheduleName != "持久公開探索 history-a" {
				t.Fatalf("borrowed or duplicate owner: %+v", r)
			}
			seen[r.ID] = true
		}
		if page.NextCursor == "" {
			break
		}
		page = historyPageForTest(t, s, "limit=7&schedule_id=history-a&cursor="+url.QueryEscape(page.NextCursor))
	}
	if len(seen) != 46 {
		t.Fatal("owner history was truncated")
	}
	empty := historyPageForTest(t, s, "schedule_id=unknown")
	if empty.Total != 0 || len(empty.Runs) != 0 || empty.Runs == nil || empty.NextCursor != "" {
		t.Fatalf("unknown owner borrowed history: %+v", empty)
	}
	assertSQLInt(t, s, 139, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestResearchHistoryRejectsBadCursorsAndUnreadableRowsWithoutInventingEmptyHistory(t *testing.T) {
	s := historyFixture(t)
	for _, q := range []string{"limit=0", "limit=101", "limit=-1", "limit=bad", "limit=1&limit=2", "cursor=%%%", "cursor=not-base64", "schedule_id=bad%20owner", "status=COMPLETED", "id=history-000"} {
		requireCode(t, req(t, s, "GET", "/api/v1/research/history?"+q, nil), 400)
	}
	page := historyPageForTest(t, s, "limit=1")
	raw, err := base64.RawURLEncoding.DecodeString(page.NextCursor)
	if err != nil {
		t.Fatal(err)
	}
	var cursor researchHistoryCursor
	if err = json.Unmarshal(raw, &cursor); err != nil {
		t.Fatal(err)
	}
	bad := []string{"{}", "[]", strings.Replace(string(raw), `"version":1`, `"version":2`, 1), strings.Replace(string(raw), `"snapshot":137`, `"snapshot":99999`, 1), strings.Replace(string(raw), `"before_rowid":137`, `"before_rowid":0`, 1), strings.Replace(string(raw), `"query":"`, `"query":"other`, 1), strings.TrimSuffix(string(raw), "}") + `,"unrecognized":true}`}
	for _, value := range bad {
		q := base64.RawURLEncoding.EncodeToString([]byte(value))
		requireCode(t, req(t, s, "GET", "/api/v1/research/history?cursor="+q, nil), 400)
	}
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/research/history", nil))
	requireCode(t, w, 401)
	if _, err = s.Store.DB.Exec("UPDATE research_run_options SET summary_json='invalid JSON' WHERE run_id='history-000'"); err != nil {
		t.Fatal(err)
	}
	if got := historyPageForTest(t, s, "limit=100&schedule_id=history-a"); got.Total != 46 {
		t.Fatal("unrelated unreadable record poisoned owner")
	}
	page = historyPageForTest(t, s, "limit=100")
	requireCode(t, req(t, s, "GET", "/api/v1/research/history?limit=100&cursor="+url.QueryEscape(page.NextCursor), nil), 500)
	requireCode(t, req(t, s, "GET", "/api/v1/research/runs?id=history-000", nil), 500)
	if err = s.Store.Close(); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/history", nil), 500)
}

func TestResearchHistorySchema37UpgradePreservesRecordsAndUsesBoundedOrderedIndex(t *testing.T) {
	s := historyFixture(t)
	if _, err := s.Store.DB.Exec("DROP INDEX research_runs_started;DROP INDEX research_schedule_runs_owner;PRAGMA user_version=37"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 137, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 91, "SELECT COUNT(*) FROM research_schedule_runs")
	assertSQLInt(t, s, 0, "SELECT budget FROM research_run_options WHERE run_id='history-000'")
	files, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-37-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatalf("upgrade backup: %v %v", files, err)
	}
	rows, err := s.Store.DB.Query("EXPLAIN QUERY PLAN SELECT "+researchRunColumns+researchRunTables+" WHERE r.rowid<=? AND (r.started_at,r.rowid)<(?,?) ORDER BY r.started_at DESC,r.rowid DESC LIMIT ?", 137, 1030, 120, 51)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var plans []string
	for rows.Next() {
		var a, b, c int
		var plan string
		if err = rows.Scan(&a, &b, &c, &plan); err != nil {
			t.Fatal(err)
		}
		plans = append(plans, plan)
	}
	if err = rows.Err(); err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(plans, "\n")
	if !strings.Contains(joined, "research_runs_started") || strings.Contains(joined, "TEMP B-TREE") {
		t.Fatalf("unbounded history sort:\n%s", joined)
	}
}

func TestResearchScheduleMetadataReportsSavedConcurrencyAndKeepsUnavailableUnknown(t *testing.T) {
	s := testServer(t)
	read := func(want int) {
		t.Helper()
		w := req(t, s, "GET", "/api/v1/research/schedules", nil)
		requireCode(t, w, 200)
		var got struct {
			MaxActive int `json:"max_active"`
		}
		if json.Unmarshal(w.Body.Bytes(), &got) != nil || got.MaxActive != want {
			t.Fatalf("saved concurrency: %s", w.Body.String())
		}
	}
	read(1)
	saveExecutionPolicyForTest(t, s, 2)
	read(2)
	s = reopenScheduleStore(t, s)
	read(2)
	if _, err := s.Store.DB.Exec("UPDATE settings SET value_json='{}' WHERE key='research_execution_policy'"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/schedules", nil), 503)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
}
