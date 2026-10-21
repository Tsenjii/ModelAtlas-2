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

func TestBrowserResearchRetryWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var calls atomic.Int32
	s, clock := retryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		switch calls.Add(1) {
		case 1:
			w.Header().Set("Retry-After", "120")
			http.Error(w, "temporarily limited", 429)
			return
		case 2:
			http.Error(w, "temporary gateway error", 502)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"recovered-public-model","rpd":100,"tpd":1000000}]}`))
	})
	seed(t, s, "https://provider.example/v1")
	sourcesForSchedule(t, s, "browser-retry")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Test-only clock advancement; all product APIs, task execution and TLS
		// responses are real. Production has no such endpoint or clock control.
		if r.URL.Path == "/test/advance-retry-clock" && r.Method == "POST" {
			clock.Add(180000)
			s.wakeWorker()
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-retry.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("public research recovery browser: %v\n%s", err, out)
	}
	if calls.Load() != 3 {
		t.Fatalf("actual TLS requests %d; want 429 -> 502 -> 200 exactly", calls.Load())
	}
	assertSQLInt(t, s, 3, "SELECT used FROM research_run_options")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
