package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestResearchCatalogEvidenceUsesWholeExactResponse(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong research Key")
		}
		models := []any{}
		for i := 0; i < 65; i++ {
			models = append(models, map[string]string{"id": fmt.Sprintf("current-%d", i)})
		}
		json.NewEncoder(w).Encode(map[string]any{"data": models})
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "stale-other-key", DisplayName: "Old other Key catalog"}), 201)
	id := researchRunID(t, s, ResearchOptions{Selections: []Selection{v}, Budget: 5})
	s.runResearchPending(context.Background())
	var candidates, members, evidence int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_candidates").Scan(&candidates)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_evidence_details WHERE json_extract(details_json,'$.run_id')=?", id).Scan(&evidence)
	if candidates != 65 || evidence != 65 || members != 1 {
		t.Fatalf("exact catalog incomplete/leaked: candidates=%d evidence=%d membership=%d", candidates, evidence, members)
	}
	var leaked int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_candidates WHERE model_id IN ('m','stale-other-key')").Scan(&leaked)
	if leaked != 0 {
		t.Fatal("union Catalog borrowed as current Key evidence")
	}
	var bad int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_evidence_details WHERE json_extract(details_json,'$.normalized.origin')!=? OR json_extract(details_json,'$.normalized.model_id')!=json_extract(details_json,'$.normalized.selection.model_id')", up.URL+"/v1").Scan(&bad)
	if bad != 0 {
		t.Fatal("research evidence scope/version/origin mismatch")
	}
}
