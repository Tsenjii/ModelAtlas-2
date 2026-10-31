package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserExtractionMeasuredTrialRecoveryMonitoringAndUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	mode := &atomic.Int32{}
	s, _, _, calls := extractionFixture(t, mode)
	var failRead atomic.Bool
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__fixture/extraction" {
			if r.Method == "POST" {
				var in struct {
					ReadFailure bool  `json:"read_failure"`
					Mode        int32 `json:"mode"`
				}
				if json.NewDecoder(r.Body).Decode(&in) != nil {
					http.Error(w, "bad fixture control", 400)
					return
				}
				failRead.Store(in.ReadFailure)
				mode.Store(in.Mode)
			}
			var active, actions, undone, models, telemetry, comparisons int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM extraction_active").Scan(&active)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_EXTRACTION'").Scan(&actions)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_EXTRACTION' AND state='UNDONE'").Scan(&undone)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&models)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&telemetry)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM extraction_comparisons").Scan(&comparisons)
			json.NewEncoder(w).Encode(map[string]any{"metadata_http": calls.Load(), "active": active, "activations": actions, "undone": undone, "models": models, "telemetry": telemetry, "comparisons": comparisons})
			return
		}
		if r.URL.Path == "/api/v1/extraction" && r.Method == "GET" && failRead.Load() {
			http.Error(w, "synthetic storage read unavailable", 503)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/extraction.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("extraction Browser: %v\n%s", e, out)
	}
	if calls.Load() != 22 {
		t.Fatal("original new-flow wire workload changed", calls.Load(), string(out))
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_versions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM extraction_comparisons")
	assertSQLInt(t, s, 5, "SELECT COUNT(*) FROM extraction_observations")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_active")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_EXTRACTION' AND state='UNDONE'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	t.Log(string(out))
}
