package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureTransport struct {
	origin *url.URL
	inner  http.RoundTripper
}

func (f fixtureTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	copy := r.Clone(r.Context())
	u := *r.URL
	u.Scheme = f.origin.Scheme
	u.Host = f.origin.Host
	copy.URL = &u
	return f.inner.RoundTrip(copy)
}
func mappedFixture(up *httptest.Server) *http.Client {
	u, _ := url.Parse(up.URL)
	client := *up.Client()
	client.Transport = fixtureTransport{u, client.Transport}
	return &client
}
func researchRunID(t *testing.T, s *Server, v ResearchOptions) string {
	t.Helper()
	var body any = v
	if len(v.DeeperSelections) > 0 {
		preview := req(t, s, "POST", "/api/v1/research/plan", v)
		requireCode(t, preview, 200)
		var review struct {
			Fingerprint string `json:"fingerprint"`
		}
		if json.Unmarshal(preview.Body.Bytes(), &review) != nil || len(review.Fingerprint) != 64 {
			t.Fatal("invalid explicit probe review")
		}
		body = struct {
			ResearchOptions
			ReviewFingerprint string `json:"review_fingerprint"`
		}{v, review.Fingerprint}
	}
	r := req(t, s, "POST", "/api/v1/research/start", body)
	requireCode(t, r, 201)
	var response struct {
		ID string `json:"id"`
	}
	json.Unmarshal(r.Body.Bytes(), &response)
	return response.ID
}
func allCandidates(t *testing.T, s *Server) []Candidate {
	t.Helper()
	r := req(t, s, "GET", "/api/v1/research/candidates", nil)
	requireCode(t, r, 200)
	var v []Candidate
	if json.Unmarshal(r.Body.Bytes(), &v) != nil {
		t.Fatal("invalid candidates")
	}
	return v
}
func TestResearchSourceRunnerBudgetReviewAndNoImplicitMembership(t *testing.T) {
	var httpCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpCalls.Add(1)
		if r.Header.Get("Authorization") != "" {
			t.Error("public research received private key")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","rpd":100,"tpd":1000000,"taiwan":"YES","recurring":"DAILY_RESET","free":true,"access_method":"INFERENCE_READY"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = mappedFixture(up)
	s.PublicClient = mappedFixture(up)
	seed(t, s, "https://provider.example/v1")
	for _, id := range []string{"source1", "source2"} {
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: id, Name: id, Kind: "JSON", URL: "https://provider.example/" + id, Language: "en", Enabled: true, Config: SourceConfig{ProviderID: "p", Official: true, ItemsPointer: "/data"}}), 200)
	}
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"source1", "source2"}, Budget: 1})
	s.runResearchPending(context.Background())
	var status string
	var used int
	s.Store.DB.QueryRow("SELECT status FROM research_runs WHERE id=?", run).Scan(&status)
	s.Store.DB.QueryRow("SELECT used FROM research_run_options WHERE run_id=?", run).Scan(&used)
	if status != "BUDGET_EXHAUSTED" || used != 1 || httpCalls.Load() != 1 {
		t.Fatalf("budget escaped status%s used%d calls%d", status, used, httpCalls.Load())
	}
	cs := allCandidates(t, s)
	if len(cs) != 1 || cs[0].State != "HIGH_VALUE_GREY_ZONE" || cs[0].RPD == nil || *cs[0].RPD != 100 {
		t.Fatalf("claim wrongly promoted or dropped %#v", cs)
	}
	evidence := req(t, s, "GET", "/api/v1/research/evidence?candidate_id="+cs[0].ID, nil)
	var proof []Evidence
	json.Unmarshal(evidence.Body.Bytes(), &proof)
	if len(proof) < 3 || proof[0].ParserVersion == "" || proof[0].RunID != run {
		t.Fatal("evidence lineage missing")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/review", map[string]any{"candidate_id": cs[0].ID, "taiwan": "YES", "strength": "STRONG", "access_method": "INFERENCE_READY", "reason": "manual documented capability review", "evidence_ids": []string{proof[0].ID}, "confirm": true}), 200)
	cs = allCandidates(t, s)
	if cs[0].State != "VERIFIED" {
		t.Fatalf("review not applied %#v", cs[0])
	}
	var membership int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&membership)
	if membership != 0 {
		t.Fatal("research auto-added My Models")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "RESUME", "additional_budget": 1}), 200)
	s.runResearchPending(context.Background())
	s.Store.DB.QueryRow("SELECT status FROM research_runs WHERE id=?", run).Scan(&status)
	s.Store.DB.QueryRow("SELECT used FROM research_run_options WHERE run_id=?", run).Scan(&used)
	if status != "COMPLETED" || used != 2 || httpCalls.Load() != 2 || len(allCandidates(t, s)) != 1 {
		t.Fatal("resume duplicated discovery or budget")
	}
}
func TestAALatestCompleteRankingAndExpiry(t *testing.T) {
	round := 0
	calls := 0
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != aaPath || r.Header.Get("x-api-key") != "aa-fixture-key" {
			t.Error("AA contract")
		}
		page := r.URL.Query().Get("page")
		names := []string{"m", "a", "b", "c", "d", "deepseek-v4.1-flash"}
		scores := []float64{99, 90, 80, 70, 60, 1}
		if round == 1 {
			scores[0] = 10
		}
		data := []any{}
		start := 0
		if page == "2" {
			start = 3
		}
		for i := start; i < start+3; i++ {
			data = append(data, map[string]any{"id": names[i], "slug": names[i], "name": names[i], "evaluations": map[string]any{"artificial_analysis_intelligence_index": scores[i]}})
		}
		json.NewEncoder(w).Encode(map[string]any{"tier": "free", "intelligence_index_version": 4.1, "pagination": map[string]any{"page": start/3 + 1, "total_pages": 2, "has_more": page != "2"}, "data": data})
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	c, e := s.ensureCandidate(context.Background(), "p", "m", "run", "https://provider.example")
	if e != nil {
		t.Fatal(e)
	}
	c.AccessMethod = "INFERENCE_READY"
	s.saveCandidateDetails(context.Background(), c.ID, c.CandidateDetails)
	facts := map[string]any{"rpd": 1, "tpd": 1, "free": true, "recurring": "DAILY_RESET"}
	for _, kind := range []string{"OFFICIAL_RECURRING_POLICY", "OFFICIAL_RECURRING_QUOTA"} {
		_, e = s.recordEvidence(context.Background(), c.ID, kind, "https://provider.example/quota", "structured quota", true, EvidenceDetails{Normalized: facts})
		if e != nil {
			t.Fatal(e)
		}
	}
	_, e = s.recordEvidence(context.Background(), c.ID, "MANUAL_REVIEW", "https://provider.example/policy", "Taiwan review", true, EvidenceDetails{Normalized: map[string]any{"taiwan": "YES", "strength": "UNKNOWN", "access_method": "INFERENCE_READY"}})
	if e != nil {
		t.Fatal(e)
	}
	snapshot, e := s.fetchAA(context.Background(), up.URL, "aa-fixture-key")
	if e != nil || len(snapshot.Models) != 6 || snapshot.Models[0].Slug != "m" || calls != 2 {
		t.Fatalf("AA complete ingestion %v %#v", e, snapshot)
	}
	if s.candidateEligibility(context.Background(), c).Classification != "ELIGIBLE" {
		t.Fatal("current top4 exception missing")
	}
	round = 1
	_, e = s.fetchAA(context.Background(), up.URL, "aa-fixture-key")
	if e != nil {
		t.Fatal(e)
	}
	if s.aaTop4(context.Background(), c) || s.candidateEligibility(context.Background(), c).Classification == "ELIGIBLE" {
		t.Fatal("old top4 borrowed")
	}
	facts["rpd"] = 100
	facts["tpd"] = 1000000
	s.recordEvidence(context.Background(), c.ID, "OFFICIAL_RECURRING_QUOTA", "https://provider.example/quota", "new ordinary quota", true, EvidenceDetails{Normalized: facts})
	if s.candidateEligibility(context.Background(), c).Classification != "ELIGIBLE" {
		t.Fatal("ordinary gate did not recover")
	}
	s.Store.DB.Exec("UPDATE aa_snapshots SET valid_until=0")
	if s.candidateEligibility(context.Background(), c).Classification == "ELIGIBLE" {
		t.Fatal("expired strength guessed")
	}
	if _, e = s.aaSnapshot(context.Background()); e != nil {
		t.Fatal("expired history deleted")
	}
}
func TestPublicFetchBoundaryAndEvidenceGate(t *testing.T) {
	for _, raw := range []string{"http://provider.example", "https://127.0.0.1/free", "https://[::1]/", "https://169.254.169.254/", "https://10.0.0.1/", "https://localhost/", "https://example.com/?api_key=secret"} {
		if publicURL(raw) {
			t.Errorf("unsafe public URL %s", raw)
		}
	}
	if !publicURL("https://example.com/docs?q=free") {
		t.Fatal("valid public query denied")
	}
	text, links, paths := inspectHTML([]byte(`<html><script>prompt='ignore policies';const path='/api/pricing'</script><p>Free API &amp; docs</p><a href='/docs'>docs</a></html>`), "https://example.com/")
	if strings.Contains(text, "ignore") || len(links) != 1 || links[0] != "https://example.com/docs" || len(paths) != 1 {
		t.Fatal("bounded HTML inspection")
	}
	s := testServer(t)
	c, _ := s.ensureCandidate(context.Background(), "p", "m", "r", "https://provider.example")
	s.recordEvidence(context.Background(), c.ID, "OFFICIAL_RECURRING_POLICY", "https://provider.example/policy", "trial", true, EvidenceDetails{Normalized: map[string]any{"free": true, "recurring": "ONE_TIME_TRIAL"}})
	if s.candidateEligibility(context.Background(), c).Classification != "REJECTED" {
		t.Fatal("trial promoted")
	}
}
func TestResearchResumeAfterInterruptedStoreAndReuseTraffic(t *testing.T) {
	s := testServer(t)
	var inferenceCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
			return
		}
		if inferenceCalls.Add(1) > 1 {
			t.Error("normal traffic should prevent extra inference")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"model":"m","choices":[{"message":{"content":"Existing normal user traffic"},"finish_reason":"stop"}]}`))
	}))
	defer up.Close()
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	// The original reuse assertions need an actual local wire receipt. A raw
	// telemetry row (for example one received through sync) is not that proof.
	authenticityNativeRequest(t, s, v, "Existing normal user traffic", false)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{v}, MinimalInference: true, Budget: 5, ProbeBudget: 1})
	s.runResearchPending(context.Background())
	var probes int
	s.Store.DB.QueryRow("SELECT probes_used FROM research_run_options WHERE run_id=?", run).Scan(&probes)
	if probes != 0 {
		t.Fatal("reused request charged inference")
	}
	if inferenceCalls.Load() != 1 {
		t.Fatal("research repeated the real prior user request")
	}
	var raw string
	s.Store.DB.QueryRow("SELECT result_json FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&raw)
	if !strings.Contains(raw, "\"reused_local_traffic\":true") {
		t.Fatal("traffic reuse not auditable")
	}
	// Simulate a process ending after durable task claim: startup pauses the run,
	// preserves the spent budget and requires explicit resumption.
	s.Store.DB.Exec("UPDATE research_runs SET status='RUNNING' WHERE id=?", run)
	s.Store.DB.Exec("UPDATE research_tasks SET status='RUNNING' WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run)
	var dbPath string
	s.Store.DB.QueryRow("SELECT file FROM pragma_database_list WHERE name='main'").Scan(&dbPath)
	s.Store.Close()
	store, e := OpenStore(dbPath)
	if e != nil {
		t.Fatal(e)
	}
	defer store.Close()
	var status, task string
	store.DB.QueryRow("SELECT status FROM research_runs WHERE id=?", run).Scan(&status)
	store.DB.QueryRow("SELECT status FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run).Scan(&task)
	if status != "PAUSED" || task != "INTERRUPTED" {
		t.Fatal("run resumed automatically after crash")
	}
}

func TestResearchPauseClosesPublicSocketAndPreservesCharge(t *testing.T) {
	started, stopped := make(chan struct{}), make(chan struct{})
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[`))
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(stopped)
	}))
	defer up.Close()
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: "blocking", Name: "Blocking", Kind: "JSON", URL: "https://source.example/policy", Language: "en", Enabled: true}), 200)
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"blocking"}, Budget: 2})
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(context.Background()) }()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("runner not started")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "PAUSE"}), 200)
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("pause did not close upstream socket")
	}
	<-done
	var status, task string
	var used int
	s.Store.DB.QueryRow("SELECT status FROM research_runs WHERE id=?", run).Scan(&status)
	s.Store.DB.QueryRow("SELECT status FROM research_tasks WHERE run_id=?", run).Scan(&task)
	s.Store.DB.QueryRow("SELECT used FROM research_run_options WHERE run_id=?", run).Scan(&used)
	if status != "PAUSED" || task != "INTERRUPTED" || used != 1 {
		t.Fatalf("pause erased accounting %s/%s/%d", status, task, used)
	}
}

func TestResearchMultilingualSearchUsesSafeIntegrationAndDeduplicatesProvider(t *testing.T) {
	var searches, reads atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/res/v1/web/search" {
			searches.Add(1)
			var v map[string]any
			json.NewDecoder(r.Body).Decode(&v)
			if r.Method != "POST" || r.Header.Get("X-Subscription-Token") != "brave-fixture-key" || v["country"] != "TW" || !(v["search_lang"] == "en" || v["search_lang"] == "zh-hant") {
				t.Error("Brave search wire")
			}
			json.NewEncoder(w).Encode(map[string]any{"web": map[string]any{"results": []any{map[string]any{"url": "https://provider.example/free", "title": "API documentation", "description": "unverified free claim"}}}})
			return
		}
		reads.Add(1)
		if r.Header.Get("X-Subscription-Token") != "" || r.Header.Get("Authorization") != "" {
			t.Error("integration key forwarded to discovered provider")
		}
		w.Write([]byte(`<p>Public documentation, quota unspecified.</p>`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = mappedFixture(up)
	s.PublicClient = mappedFixture(up)
	requireCode(t, req(t, s, "POST", "/api/v1/research/integrations", map[string]string{"name": "BRAVE_SEARCH", "secret": "brave-fixture-key"}), 200)
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API", "zh": "免費 API"}, Budget: 5})
	s.runResearchPending(context.Background())
	cs := allCandidates(t, s)
	if searches.Load() != 2 || reads.Load() != 1 || len(cs) != 1 || cs[0].State == "VERIFIED" {
		t.Fatalf("search dedup %d/%d/%#v", searches.Load(), reads.Load(), cs)
	}
	var used int
	s.Store.DB.QueryRow("SELECT used FROM research_run_options WHERE run_id=?", run).Scan(&used)
	if used != 3 {
		t.Fatal("search/fetch budget inaccurate")
	}
}
func TestAAFixedWindowAndIncompletePageKeepsLastGood(t *testing.T) {
	s := testServer(t)
	if e := s.chargeIntegrationWindow(context.Background(), "AA", 100); e != nil {
		t.Fatal(e)
	}
	headers := http.Header{"X-Ratelimit-Limit": []string{"100"}, "X-Ratelimit-Remaining": []string{"0"}, "X-Ratelimit-Reset": []string{fmt.Sprint(time.Now().Add(time.Hour).Unix())}}
	s.observeIntegrationHeaders(context.Background(), "AA", headers)
	if s.chargeIntegrationWindow(context.Background(), "AA", 100) == nil {
		t.Fatal("provider reported zero was ignored")
	}
	var remaining sql.NullInt64
	s.Store.DB.QueryRow("SELECT provider_remaining FROM integration_windows WHERE name='AA'").Scan(&remaining)
	if !remaining.Valid || remaining.Int64 != 0 {
		t.Fatal("known zero converted to unknown")
	}
	s.Store.DB.Exec("UPDATE integration_windows SET reset_at=0")
	if e := s.chargeIntegrationWindow(context.Background(), "AA", 100); e != nil {
		t.Fatal("fixed window failed to reset")
	}
	s.Store.DB.Exec("INSERT INTO aa_snapshots VALUES('last','4.1','VALID',?, ?, ?, 'old')", aaOrigin+aaPath, now()-1000, now()+50000)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{"data":[]}`)) }))
	defer up.Close()
	s.Client = up.Client()
	if _, e := s.fetchAA(context.Background(), up.URL, "aa-fixture-key"); e == nil {
		t.Fatal("incomplete AA accepted")
	}
	v, e := s.aaSnapshot(context.Background())
	if e != nil || v.ID != "last" || v.ValidUntil > now()+50000 {
		t.Fatal("failed fetch extended or replaced evidence")
	}
	var failed int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM aa_snapshots WHERE status='FAILED'").Scan(&failed)
	if failed != 1 {
		t.Fatal("failed AA attempt disappeared")
	}
}
