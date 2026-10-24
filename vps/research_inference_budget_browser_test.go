package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
	"time"
)

func TestBrowserResearchInferenceDailyBudgetWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var catalogs, inferences atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong credential in budget browser fixture")
		}
		if r.Method == "GET" && r.URL.Path == "/v1/models" {
			catalogs.Add(1)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":[{"id":"m"},{"id":"other"}]}`))
			return
		}
		inferences.Add(1)
		var payload struct {
			Model string `json:"model"`
		}
		if r.Method != "POST" || r.URL.Path != "/v1/chat/completions" || json.NewDecoder(r.Body).Decode(&payload) != nil || !oneOf(payload.Model, "m", "other") {
			t.Error("unexpected inference in budget browser fixture")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "data: {\"model\":%q,\"choices\":[{\"delta\":{\"content\":\"Observed answer\"}}]}\n\ndata: [DONE]\n\n", payload.Model)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	scope := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "other", DisplayName: "Other"}), 201)
	scope.ModelID = "other"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	s.runPending(context.Background())
	if catalogs.Load() != 2 || inferences.Load() != 0 {
		t.Fatalf("existing membership enrichment baseline: catalogs=%d inference=%d", catalogs.Load(), inferences.Load())
	}
	var clock atomic.Int64
	clock.Store(time.Date(2026, 10, 23, 12, 0, 0, 0, time.UTC).UnixMilli())
	s.scheduleClock = func() time.Time { return time.UnixMilli(clock.Load()).UTC() }
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" && oneOf(r.URL.Path, "/test/advance-budget-day", "/test/tick-budget") {
			if r.URL.Path == "/test/advance-budget-day" {
				clock.Add(int64(24 * time.Hour / time.Millisecond))
			}
			s.runResearchPending(context.Background())
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/research-inference-budget.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("daily inference budget browser: %v\n%s", err, out)
	}
	if catalogs.Load() != 4 || inferences.Load() != 2 {
		t.Fatalf("UI budget wire: catalogs=%d inferences=%d", catalogs.Load(), inferences.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions WHERE day='2026-10-23'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions WHERE day='2026-10-24'")
	assertSQLInt(t, s, 4, "SELECT SUM(used) FROM research_run_options")
	assertSQLInt(t, s, 2, "SELECT SUM(probes_used) FROM research_run_options")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_resource_admissions WHERE reserve_tokens=32 AND reserve_usd='0.01'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_resource_settlements WHERE reported_tokens IS NULL AND reported_usd IS NULL AND accounted_tokens='32' AND accounted_usd='0.01' AND outcome='SUCCESS'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_quota_pool_admissions WHERE basis='ADMISSION'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_quota_pool_admissions WHERE basis='EXISTING_USAGE'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_quota_pools")
	t.Log(string(out))
}
