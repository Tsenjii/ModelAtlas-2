package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserResearchOriginCooldownWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path == "/origin-limited" {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "temporarily limited", 429)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"recovered-origin-model"}]}`))
	})
	seed(t, s, "https://provider.example/v1")
	sourcesForSchedule(t, s, "origin-limited", "origin-waiting")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/test/advance-origin-clock" && r.Method == "POST" {
			clock.Add(120000)
			s.wakeWorker()
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-origin.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("origin cooldown browser: %v\n%s", err, out)
	}
	if calls.Load() != 2 {
		t.Fatalf("want exactly one 429 and one explicit recovery; got %d TLS requests", calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT SUM(used) FROM research_run_options")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SOURCE_FETCH_FAILURE'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
