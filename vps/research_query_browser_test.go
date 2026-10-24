package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserReflectionGeneratedQueryPairedTrialWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	f := newQueryResearchFixture(t)
	s := f.Server
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" && r.URL.Path == "/test/query-empty" {
			f.Empty.Store(true)
			w.WriteHeader(204)
			return
		}
		if r.Method == "POST" && r.URL.Path == "/test/query-key" {
			req := req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "rotated-browser-query-fixture-key"})
			w.WriteHeader(req.Code)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-query.cjs", app.URL, f.Draft.ID)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("query comparison Browser: %v\n%s", e, out)
	}
	// Initial public search+read, six actual paired searches+reads, three default
	// eight-language monitoring rounds, and one post-Undo default round.
	if f.Searches.Load() != 39 || f.Reads.Load() != 7 {
		t.Fatal("unexpected real wire workload", f.Searches.Load(), f.Reads.Load())
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_query_comparisons")
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM research_query_comparison_runs")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE state='UNDONE'")
	t.Log(string(out))
}
