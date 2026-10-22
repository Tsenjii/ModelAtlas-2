package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserBraveResearchPageRecoveryWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var first, second, reads atomic.Int32
	s, clock := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/res/v1/web/search" {
			reads.Add(1)
			if r.Header.Get("X-Subscription-Token") != "" || r.Header.Get("Authorization") != "" {
				t.Error("integration key forwarded to discovery")
			}
			w.Write([]byte("<p>Public API documentation, quota unspecified</p>"))
			return
		}
		var in struct {
			Offset   int    `json:"offset"`
			Country  string `json:"country"`
			Language string `json:"search_lang"`
		}
		json.NewDecoder(r.Body).Decode(&in)
		if r.Method != "POST" || r.Header.Get("X-Subscription-Token") != "brave-fixture-key" || in.Country != "JP" || in.Language != "en" {
			t.Error("Brave UI did not freeze exact POST/region/language")
		}
		if in.Offset == 0 {
			first.Add(1)
			braveFixturePage(w, true)
			return
		}
		if second.Add(1) == 1 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "private-search-error-body", 429)
			return
		}
		braveFixturePage(w, false, "https://provider.example/free", "https://provider.example/free", "https://other.example/free")
	})
	seed(t, s, "https://provider.example/v1")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/test/advance-brave-clock" && r.Method == "POST" {
			clock.Add(120000)
			s.wakeWorker()
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-brave.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Brave page recovery browser: %v\n%s", err, out)
	}
	if first.Load() != 1 || second.Load() != 2 || reads.Load() != 2 {
		t.Fatalf("page/recovery/dedup requests: first=%d second=%d inspect=%d", first.Load(), second.Load(), reads.Load())
	}
	assertSQLInt(t, s, 3, "SELECT used FROM request_budgets WHERE name='BRAVE_SEARCH'")
	assertSQLInt(t, s, 3, "SELECT SUM(used) FROM research_brave_daily")
	assertSQLInt(t, s, 5, "SELECT SUM(used) FROM research_run_options")
	assertSQLInt(t, s, 5, "SELECT used FROM research_schedule_daily WHERE scope='global'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SEARCH_DISCOVERY'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	t.Log(string(out))
}
