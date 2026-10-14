package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestCatalogSyncFailurePreservesLastGood(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "temporary failure", 503) }))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	v := seed(t, s, upstream.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	payload := map[string]string{"provider_id": "p", "account_id": "a", "credential_id": "c"}
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", payload), 502)
	var models []Model
	if json.Unmarshal(req(t, s, "GET", "/api/v1/models", nil).Body.Bytes(), &models) != nil || len(models) != 1 || models[0].ID != "m" {
		t.Fatal("failed sync erased last-good Catalog")
	}
	var saved []Selection
	if json.Unmarshal(req(t, s, "GET", "/api/v1/my-models", nil).Body.Bytes(), &saved) != nil || len(saved) != 1 {
		t.Fatal("failed sync erased My Models")
	}
}
func TestUnimplementedGroupIsRefusedBeforeAnyRequest(t *testing.T) {
	var calls atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(200) }))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	v := seed(t, s, upstream.URL+"/v1")
	v.Group = "premium"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	created := req(t, s, "POST", "/api/v1/sessions", Session{Selection: v, Title: "Strict group"})
	requireCode(t, created, 201)
	var sess Session
	_ = json.Unmarshal(created.Body.Bytes(), &sess)
	result := req(t, s, "POST", "/api/v1/infer", map[string]string{"session_id": sess.ID, "prompt": "Hello"})
	requireCode(t, result, 422)
	if calls.Load() != 0 {
		t.Fatal("premium group silently fell back to default upstream")
	}
}
func TestResearchManualEvidenceCannotForgeVerifiedQuota(t *testing.T) {
	s := testServer(t)
	candidate := Candidate{ProviderID: "p", ModelID: "m", Taiwan: "YES", Recurring: "DAILY_RESET", Strength: "STRONG"}
	r := req(t, s, "POST", "/api/v1/research/candidates", candidate)
	requireCode(t, r, 201)
	var c Candidate
	_ = json.Unmarshal(r.Body.Bytes(), &c)
	evidence := Evidence{CandidateID: c.ID, SourceType: "OFFICIAL_RECURRING_QUOTA", SourceURL: "https://provider.example/quota", Claim: "claimed unlimited", Verified: true}
	r = req(t, s, "POST", "/api/v1/research/evidence", evidence)
	requireCode(t, r, 201)
	var e Evidence
	_ = json.Unmarshal(r.Body.Bytes(), &e)
	if e.Verified {
		t.Fatal("manual evidence was mislabelled verified")
	}
	var rows []Eligibility
	if json.Unmarshal(req(t, s, "GET", "/api/v1/research/eligibility", nil).Body.Bytes(), &rows) != nil || len(rows) != 1 || rows[0].Classification == "ELIGIBLE" {
		t.Fatal("manual evidence bypassed quota gate")
	}
}
func TestProtectedResponsiveAssets(t *testing.T) {
	s := testServer(t)
	noAuth := httptest.NewRecorder()
	s.Handler().ServeHTTP(noAuth, httptest.NewRequest("GET", "/app.css", nil))
	requireCode(t, noAuth, 401)
	page := req(t, s, "GET", "/", nil)
	requireCode(t, page, 200)
	if !strings.Contains(page.Body.String(), "Playground") || !strings.Contains(page.Body.String(), "Providers") || !strings.Contains(page.Body.String(), "Models") {
		t.Fatal("missing three-tab UI")
	}
	css := req(t, s, "GET", "/app.css", nil)
	requireCode(t, css, 200)
	if !strings.Contains(css.Body.String(), "min-height:48px") || !strings.Contains(css.Body.String(), "prefers-color-scheme:dark") {
		t.Fatal("responsive dark UI/accessibility contract missing")
	}
	js := req(t, s, "GET", "/app.js", nil)
	requireCode(t, js, 200)
	if !strings.Contains(js.Body.String(), "/api/v1/infer") {
		t.Fatal("Playground lacks real API wiring")
	}
}
