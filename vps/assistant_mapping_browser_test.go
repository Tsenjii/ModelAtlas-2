package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserAssistantMappingPublicDraftSharedReviewAndPersistentUndo(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s, _, metadata, models := assistantMappingFlowFixture(t)
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__fixture/assistant-mapping" {
			var versions, active, members, facts int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_versions").Scan(&versions)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_active").Scan(&active)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM observations WHERE source LIKE 'DECLARATIVE_PROVIDER_META:%' AND status='SUCCESS'").Scan(&facts)
			json.NewEncoder(w).Encode(map[string]any{"metadata_http": metadata.Load(), "model_http": models.Load(), "versions": versions, "active": active, "members": members, "facts": facts})
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/assistant-mapping.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Assistant mapping Browser: %v\n%s", e, out)
	}
	if metadata.Load() != 3 || models.Load() != 4 {
		t.Fatalf("expected discovery/test/runtime source reads and four explicit Assistant model requests: %d/%d\n%s", metadata.Load(), models.Load(), out)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM assistant_tool_events WHERE status='SUCCESS'")
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM telemetry WHERE source='ASSISTANT_AGENT' AND outcome='SUCCESS'")
	t.Log(string(out))
}
