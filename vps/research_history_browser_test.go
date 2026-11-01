package main

import (
	"context"
	"fmt"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserResearchHistoryArchiveAndExactRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s, calls := scheduleFixture(t)
	sourcesForSchedule(t, s, "history-source")
	for _, id := range []string{"history-a", "history-b"} {
		saveScheduleForTest(t, s, id, ResearchOptions{SourceIDs: []string{"history-source"}, Budget: 2}, 10)
	}
	saveExecutionPolicyForTest(t, s, 2)
	old := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"history-source"}, Budget: 2})
	base := now() - 150*60*1000
	tx, err := s.Store.DB.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err = tx.Exec("UPDATE research_runs SET status='PAUSED',started_at=? WHERE id=?", base, old); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec("UPDATE research_tasks SET status='INTERRUPTED' WHERE run_id=?", old); err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 136; i++ {
		id := fmt.Sprintf("history-%03d", i)
		if _, err = tx.Exec("INSERT INTO research_runs(id,status,checkpoint,started_at) VALUES(?,'COMPLETED','',?)", id, base+int64(i)*60000); err != nil {
			t.Fatal(err)
		}
		owner := "history-a"
		if i%2 != 0 {
			owner = "history-b"
		}
		if _, err = tx.Exec("INSERT INTO research_schedule_runs(schedule_id,run_id,scheduled_for,revision) VALUES(?,?,?,1)", owner, id, i); err != nil {
			t.Fatal(err)
		}
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-history.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_HISTORY_RUN="+old)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("research history browser: %v\n%s", err, out)
	}
	if calls.Load() != 0 {
		t.Fatalf("read-only history or explicit pause/resume performed %d unsolicited HTTP requests", calls.Load())
	}
	assertSQLInt(t, s, 137, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 3, "SELECT budget FROM research_run_options WHERE run_id=?", old)
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", old)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatalf("resumed original runner sent %d requests; want 1", calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", old)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='COMPLETED'", old)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='SUCCESS'", old)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	t.Log(string(out))
}
