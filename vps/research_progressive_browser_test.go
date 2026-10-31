package main

import (
	"context"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserResearchProgressiveWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	setInferencePolicyForTest(t, s, 2, 5)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-progressive.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("progressive browser: %v\n%s", err, out)
	}
	if f.Calls.Load() != 6 {
		t.Fatal("progressive browser wire count", f.Calls.Load())
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM research_probe_observations")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_schedule_private_approvals")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
