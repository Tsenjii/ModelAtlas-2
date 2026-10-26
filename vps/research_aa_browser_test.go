package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"sync/atomic"
	"testing"
)

func TestBrowserAAResearchPageRecoveryWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var first, second atomic.Int32
	s, clock := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-api-key") != "aa-fixture-key" || r.URL.Path != aaPath {
			t.Error("browser AA credential or endpoint mismatch")
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page == 1 {
			first.Add(1)
		} else if second.Add(1) == 1 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "remote-private-body", 429)
			return
		}
		aaFixturePage(w, page, 2)
	})
	seed(t, s, "https://provider.example/v1")
	seedAALastGood(t, s)
	for rank := 1; rank <= 4; rank++ {
		if _, e := s.Store.DB.Exec("INSERT INTO aa_models VALUES('aa-last-good',?,?,?, ?,70)", "old-"+strconv.Itoa(rank), "old-"+strconv.Itoa(rank), "上一份模型 "+strconv.Itoa(rank), rank); e != nil {
			t.Fatal(e)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/test/advance-aa-clock" && r.Method == "POST" {
			clock.Add(120000)
			s.wakeWorker()
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-aa.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("AA page recovery browser: %v\n%s", err, out)
	}
	if first.Load() != 3 || second.Load() != 4 {
		t.Logf("Browser output before AA request mismatch: %s", out)
		t.Logf("AA runs at mismatch: %s", req(t, s, "GET", "/api/v1/research/runs", nil).Body.String())
		rows, e := s.Store.DB.Query("SELECT run_id,id,status,result_json FROM research_tasks ORDER BY rowid")
		if e != nil {
			t.Logf("AA task diagnostics unavailable: %v", e)
		} else {
			defer rows.Close()
			for rows.Next() {
				var run, task, status, result string
				if e := rows.Scan(&run, &task, &status, &result); e != nil {
					t.Logf("AA task diagnostic read failed: %v", e)
					break
				}
				t.Logf("AA task run=%s id=%s status=%s result=%s", run, task, status, result)
			}
		}
		t.Fatalf("three explicit updates should send seven TLS requests with no repeated completed page: first=%d second=%d", first.Load(), second.Load())
	}
	assertSQLInt(t, s, 7, "SELECT used FROM integration_windows WHERE name='AA'")
	assertSQLInt(t, s, 7, "SELECT SUM(used) FROM research_run_options")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
