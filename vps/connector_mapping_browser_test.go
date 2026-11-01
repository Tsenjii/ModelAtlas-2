package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

func TestBrowserConnectorMappingUnknownFieldsRecoveryRuntimeAndUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var changed atomic.Bool
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/pricing" {
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("public mapping read forwarded credentials")
		}
		body := mappingPublicFixture
		if changed.Load() {
			body = strings.Replace(body, "USD/1M tokens", "USD/token", 1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(body))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__fixture/mapping" {
			if r.Method == "POST" {
				changed.Store(true)
			}
			var versions, active, models, telemetry int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_versions").Scan(&versions)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_active").Scan(&active)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&models)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&telemetry)
			json.NewEncoder(w).Encode(map[string]any{"http": calls.Load(), "versions": versions, "active": active, "models": models, "telemetry": telemetry})
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/connector-mapping.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("mapping Browser: %v\n%s", e, out)
	}
	if calls.Load() != 5 {
		t.Fatalf("expected one discovery, two real tests and two runtime reads: %d\n%s", calls.Load(), out)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_samples")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	t.Log(string(out))
}
