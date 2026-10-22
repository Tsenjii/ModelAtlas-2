package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserAuthenticityEvidenceReviewAndPersistentUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	handler := f.Server.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__authenticity_fixture/state" {
			json.NewEncoder(w).Encode(map[string]any{"calls": f.Calls.Load(), "target": f.Target})
			return
		}
		if r.URL.Path == "/__authenticity_fixture/change-reference" && r.Method == "POST" {
			_, err := f.Server.Store.DB.Exec("UPDATE authenticity_local_samples SET telemetry_hash='changed' WHERE sample_id IN (SELECT id FROM telemetry WHERE provider_id='reference')")
			if err != nil {
				t.Error(err)
			}
			w.WriteHeader(204)
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/authenticity.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+f.Server.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("authenticity browser: %v\n%s", err, out)
	}
	if f.Calls.Load() != 6 {
		t.Fatalf("local analysis sent provider requests: %d", f.Calls.Load())
	}
	assertSQLInt(t, f.Server, 3, "SELECT COUNT(*) FROM authenticity_reports")
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM authenticity_reference_versions")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM authenticity_reference_active")
	assertSQLInt(t, f.Server, 6, "SELECT COUNT(*) FROM authenticity_local_samples")
	t.Log(string(out))
}
