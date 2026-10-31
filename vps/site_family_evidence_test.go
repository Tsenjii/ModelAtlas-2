package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"testing"
)

func TestSiteFamilySourceConstantsStayPassiveBoundedAndIndependentlyAttributed(t *testing.T) {
	parallelResearchBudgetTest(t)
	var mu sync.Mutex
	paths := map[string]int{}
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths[r.URL.Path]++
		mu.Unlock()
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("public source lookup sent a selected credential")
		}
		switch r.URL.Path {
		case "/":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":{"version":"v1.2.3","quota_per_unit":500000,"checkin_enabled":true}}`))
		case "/acme/adapter/main/constants.ts":
			w.Header().Set("Content-Type", "text/plain")
			w.Write([]byte(`const project='https://github.com/acme/new-api'; const metadata='/api/metadata'; const privateAction='/api/user/redeem'; const foreign='https://foreign.example/api/pricing'; globalThis.fetch('/login'); const version='v999';`))
		case "/api/metadata":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":{"version":"v2.0.0","quota_per_unit":500000,"checkin_enabled":true},"metadata":{"models":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"}}]}}`))
		default:
			t.Error("unobserved/action/foreign source fetched", r.URL.Path)
			http.Error(w, "unexpected", 400)
		}
	})
	selection := seed(t, s, "https://source-constant.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	input := connectorDiscoveryRequest{ID: "reference-source", Selection: selection, SourcePath: "/", ReferenceURLs: []string{"https://raw.githubusercontent.com/acme/adapter/main/constants.ts"}}
	r := req(t, s, "POST", "/api/v1/connectors/discover", input)
	requireCode(t, r, 201)
	report := connectorDiscoveryReport{}
	json.Unmarshal(r.Body.Bytes(), &report)
	if report.HTTPRequests == nil || *report.HTTPRequests != 3 || report.Attempts != 3 || report.Family != "NEW_API" || report.Version != "CONFLICTING" || len(report.FamilyEvidence) != 3 || len(report.DraftIDs) == 0 {
		t.Fatalf("source attribution or bounded static lookup incorrect: %s", r.Body.String())
	}
	for _, e := range report.FamilyEvidence {
		if len(e.SourceHash) != 64 || e.Parser != siteFamilyParser || e.AuthRole != "UNKNOWN" || e.PrivateStatus != "NOT_TESTED" {
			t.Fatal("hint became private API certification", e)
		}
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/discover", input), 200)
	mu.Lock()
	defer mu.Unlock()
	if len(paths) != 3 || paths["/"] != 1 || paths["/api/metadata"] != 1 || paths["/acme/adapter/main/constants.ts"] != 1 {
		t.Fatal("passive constants were executed or a duplicate request repeated", paths)
	}
}

func TestSiteFamilyReferencesRejectSecretsOriginsAndUnboundedPaths(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) { t.Error("invalid source input reached the network") })
	selection := seed(t, s, "https://reference-policy.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	for _, ref := range []string{"https://github.com/acme/repo/blob/main/constants.ts", "https://raw.githubusercontent.com@foreign.example/a/b/main/x.ts", "https://raw.githubusercontent.com/acme/repo/main/../secrets.go", "https://raw.githubusercontent.com/acme/repo/main/x.ts?key=secret", "https://raw.githubusercontent.com/acme/repo/main/binary.exe"} {
		r := req(t, s, "POST", "/api/v1/connectors/discover", connectorDiscoveryRequest{ID: newID(), Selection: selection, SourcePath: "/", ReferenceURLs: []string{ref}})
		if r.Code < 400 {
			t.Fatal("unsafe source reference accepted", ref)
		}
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_discoveries")
}
