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

func TestBrowserConnectorPrivateMappingExplicitAuthorityGroupAccountAndUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var changed atomic.Bool
	var calls, keyCalls, patCalls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		if r.Method != "GET" || r.Header.Get("Cookie") != "" {
			t.Error("private browser used an action or cookies")
			http.Error(w, "read only", 400)
			return
		}
		switch r.URL.Path {
		case "/api/model-plan":
			keyCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("private model metadata did not use the selected Key")
				http.Error(w, "forbidden", 403)
				return
			}
			body := privateMappingGroupedFixture
			if changed.Load() {
				body = strings.Replace(body, "REQUESTS/day", "REQUESTS/minute", 1)
			}
			w.Write([]byte(body))
		case "/api/user/self":
			patCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer fixture-private-account-pat" {
				t.Error("account data borrowed the model Key")
				http.Error(w, "forbidden", 403)
				return
			}
			w.Write([]byte(privateMappingAccountFixture))
		default:
			t.Error("browser reached an unselected private path")
			http.NotFound(w, r)
		}
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Label: "帳戶 PAT", Role: "ACCOUNT_PAT", Secret: "fixture-private-account-pat"}), 201)
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__fixture/private-mapping" {
			if r.Method == "POST" {
				changed.Store(true)
			}
			var active, models, telemetry int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_active").Scan(&active)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&models)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&telemetry)
			json.NewEncoder(w).Encode(map[string]any{"http": calls.Load(), "key_http": keyCalls.Load(), "pat_http": patCalls.Load(), "active": active, "models": models, "telemetry": telemetry})
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/connector-private-mapping.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("private mapping Browser: %v\n%s", e, out)
	}
	if calls.Load() != 8 || keyCalls.Load() != 5 || patCalls.Load() != 3 {
		t.Fatalf("private browser reached extra paths/credentials: total=%d key=%d pat=%d\n%s", calls.Load(), keyCalls.Load(), patCalls.Load(), out)
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_mapping_samples")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	t.Log(string(out))
}
