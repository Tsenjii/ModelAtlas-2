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

func TestBrowserSiteRecipePublicEvidenceReviewAtomicUseAndUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var roots, references, metadata, private atomic.Int32
	var changed atomic.Bool
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/inherited/models" {
			private.Add(1)
			if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("inherited catalog lost explicit auth")
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
			return
		}
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("public recipe sent private credentials")
		}
		switch r.URL.Path {
		case "/":
			roots.Add(1)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"meta":{"generator":"New API"},"paths":{"/api/metadata":{"get":{}}}}`))
		case "/owner/new-api/main/constants.ts":
			references.Add(1)
			w.Write([]byte(`export const metadataPath="/api/metadata"; export const project="https://github.com/example/new-api";`))
		case "/api/metadata":
			metadata.Add(1)
			w.Header().Set("Content-Type", "application/json")
			body := `{"info":{"title":"One API","version":"schema-2"},"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`
			if changed.Load() {
				body = strings.Replace(body, "USD/1M tokens", "USD/token", 1)
			}
			w.Write([]byte(body))
		default:
			t.Error("unexpected recipe endpoint", r.URL.Path)
			w.WriteHeader(404)
		}
	})
	selection := seed(t, s, "https://site-recipe.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"catalog_path": "/inherited/models"}, "reason": "Keep the explicitly configured catalog path."}), 201)
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/test/recipe-source-change" && r.Method == "POST" {
			changed.Store(true)
			w.WriteHeader(204)
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/site-recipe.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("site recipe Browser: %v\n%s", e, out)
	}
	if roots.Load() != 1 || references.Load() != 1 || metadata.Load() != 7 || private.Load() != 2 {
		t.Fatalf("actual recipe workload changed: root=%d reference=%d metadata=%d private=%d", roots.Load(), references.Load(), metadata.Load(), private.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_generated_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM connector_test_history")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE state='UNDONE'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	var raw string
	s.Store.DB.QueryRow("SELECT report_json FROM connector_discoveries").Scan(&raw)
	var report connectorDiscoveryReport
	json.Unmarshal([]byte(raw), &report)
	if report.Family != "CONFLICTING" || report.Version != "UNKNOWN" || report.HTTPRequests == nil || *report.HTTPRequests != 3 {
		t.Fatal("family/reference evidence was promoted or miscounted", report)
	}
	t.Log(string(out))
}
