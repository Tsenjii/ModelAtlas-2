package main

import (
	"context"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserPrivateResearchScheduleWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "CANCEL"}), 200)
	addPoolKey(t, s, f.Scope, "a", "second")
	s.runPending(context.Background())
	f.Calls.Store(0)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/private-research-schedules.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("private schedule browser: %v\n%s", err, out)
	}
	if f.Calls.Load() != 6 {
		t.Fatal("private UI wire count", f.Calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_schedule_private_approvals")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
