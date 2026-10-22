package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserKnowledgeEvaluationTrialAndPersistentRollback(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	s, empty, calls := qualityFixture(t)
	qualityRounds(t, s, 3)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" && r.URL.Path == "/test/change-research-yield" {
			empty.Store(true)
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/knowledge-evaluation.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("research evaluation browser: %v\n%s", err, out)
	}
	if calls.Load() != 14 {
		t.Fatal("review/evaluation/reload/rollback added requests", calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM knowledge_evaluations")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE state='UNDONE'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
