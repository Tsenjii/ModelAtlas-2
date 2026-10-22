package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

func TestBrowserConnectorDiscoveryReviewAndPersistentRollback(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var changed atomic.Bool
	var roots, metadata atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" || r.URL.Path == "/api/metadata" {
			if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
				t.Error("public discovery/connector forwarded secrets")
			}
		}
		if r.URL.Path == "/" {
			roots.Add(1)
			w.Write([]byte(`<a href="/api/metadata">公開模型價格</a>`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/metadata" {
			metadata.Add(1)
			body := strings.Replace(connectorPriceFixture, `"input":"1"`, `"input":"0"`, 1)
			if changed.Load() {
				body = strings.Replace(body, "USD/1M tokens", "USD/token", 1)
			}
			w.Write([]byte(body))
			return
		}
		w.Write([]byte(`{"data":[{"id":"m","object":"model"}]}`))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/test/change-connector-unit" && r.Method == "POST" {
			changed.Store(true)
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/connector-discovery.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("connector discovery browser: %v\n%s", err, out)
	}
	if roots.Load() != 1 || metadata.Load() != 4 {
		t.Fatalf("duplicate analysis/test requests: root=%d metadata=%d", roots.Load(), metadata.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_discoveries")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_generated_versions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_test_history")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	t.Log(string(out))
}
