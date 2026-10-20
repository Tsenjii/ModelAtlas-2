package main

import (
	"context"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserResearchScheduleWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	s, calls := scheduleFixture(t)
	seed(t, s, "https://provider.example/v1")
	sourcesForSchedule(t, s, "browser-one", "browser-two")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-schedules.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("research schedule browser: %v\n%s", err, out)
	}
	if calls.Load() != 2 {
		t.Fatalf("UI schedule sent %d HTTP requests; want exactly 2 across retained daily budgets", calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_runs")
	assertSQLInt(t, s, 2, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	t.Log(string(out))
}
