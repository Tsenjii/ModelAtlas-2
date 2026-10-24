package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
)

func probePlanFingerprint(t *testing.T, s *Server, v ResearchOptions) string {
	t.Helper()
	w := req(t, s, "POST", "/api/v1/research/plan", v)
	requireCode(t, w, 200)
	var body struct {
		Fingerprint string `json:"fingerprint"`
	}
	if json.Unmarshal(w.Body.Bytes(), &body) != nil || len(body.Fingerprint) != 64 {
		t.Fatal("invalid read-only plan review")
	}
	return body.Fingerprint
}

func TestResearchProgressiveReviewedPlanRejectsChangedScopeAndAllocation(t *testing.T) {
	parallelProgressiveTest(t)
	for _, change := range []string{"connection", "allocation"} {
		t.Run(change, func(t *testing.T) {
			f := newResearchProbeFixture(t, true)
			s, v := f.Server, f.options()
			requireCode(t, req(t, s, "POST", "/api/v1/research/start", v), 409)
			fingerprint := probePlanFingerprint(t, s, v)
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_probe_plans")
			if change == "connection" {
				p, err := s.provider(context.Background(), "p")
				if err != nil {
					t.Fatal(err)
				}
				cfg := s.config(context.Background(), p)
				cfg.Headers = map[string]string{"OpenAI-Project": "new-reviewed-project"}
				requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": cfg}), 200)
			} else {
				if _, err := s.Store.DB.Exec("UPDATE research_resource_policy SET revision=revision+1,policy_json=json_set(policy_json,'$.reserve_usd','0.02')"); err != nil {
					t.Fatal(err)
				}
			}
			requireCode(t, req(t, s, "POST", "/api/v1/research/start", struct {
				ResearchOptions
				Review string `json:"review_fingerprint"`
			}{v, fingerprint}), 409)
			if f.Calls.Load() != 0 {
				t.Fatal("preview or stale confirmation sent traffic")
			}
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
			run := researchRunID(t, s, v)
			s.runResearchPending(context.Background())
			if f.Calls.Load() != 4 || len(probeReportsForTest(t, s, run)) != 2 {
				t.Fatal("fresh explicit review did not recover")
			}
		})
	}
}

func TestResearchProgressiveTaskPayloadCannotAuthorizeDeeperWork(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, true)
	s, v := f.Server, f.options()
	v.DeeperSelections = nil
	run := researchRunID(t, s, v)
	if _, err := s.Store.DB.Exec("UPDATE research_tasks SET kind='SEMANTIC_PROBE',payload_json=json_set(payload_json,'$.observation_minutes',10080) WHERE run_id=? AND kind='MINIMAL_INFERENCE'", run); err != nil {
		t.Fatal(err)
	}
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 1 {
		t.Fatal("unapproved task payload added inference", f.Calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_probe_observations")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind='SEMANTIC_PROBE' AND status='FAILED'", run)
}

func TestResearchProgressiveTokenPrecisionAndPagedImmutableHistory(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, true)
	f.InputTokens.Store(9007199254740993)
	s := f.Server
	run := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	reports := probeReportsForTest(t, s, run)
	if len(reports) != 2 {
		t.Fatal(reports)
	}
	for _, r := range reports {
		if r.InputTokens == nil || *r.InputTokens != "9007199254740993" {
			t.Fatal("provider token precision lost", r.InputTokens)
		}
	}
	// Expand a genuine locally attested report history, preserving its exact
	// original sample proof. No synthetic telemetry can become an observation.
	var hash string
	if err := s.Store.DB.QueryRow("SELECT sample_hash FROM research_probe_observations WHERE id=?", reports[0].ID).Scan(&hash); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 51; i++ {
		r := reports[0]
		r.ID = newID()
		if _, err := s.saveResearchProbe(context.Background(), r, hash); err != nil {
			t.Fatal(err)
		}
	}
	w := req(t, s, "GET", "/api/v1/research/probes?run_id="+run, nil)
	requireCode(t, w, 200)
	var page struct {
		Reports []researchProbeReport `json:"reports"`
		Next    int64                 `json:"next_before"`
	}
	if json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Reports) != 50 || page.Next < 1 {
		t.Fatal("first history page", w.Body.String())
	}
	seen := map[string]bool{}
	for _, r := range page.Reports {
		seen[r.ID] = true
	}
	w = req(t, s, "GET", "/api/v1/research/probes?run_id="+run+"&before="+strconv.FormatInt(page.Next, 10), nil)
	requireCode(t, w, 200)
	if json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Reports) != 3 || page.Next != 0 {
		t.Fatal("history cursor skipped rows", w.Body.String())
	}
	for _, r := range page.Reports {
		if seen[r.ID] {
			t.Fatal("duplicate history row")
		}
	}
	if _, err := s.Store.DB.Exec("UPDATE research_probe_observations SET report_json=json_set(report_json,'$.grade.correct',99) WHERE id=?", reports[0].ID); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/probes?run_id="+run+"&before="+strconv.FormatInt(10, 10), nil), 503)
	assertSQLInt(t, s, 53, "SELECT COUNT(*) FROM research_probe_observations")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM telemetry")
	if f.Calls.Load() != 4 {
		t.Fatal("history read or corruption added paid traffic")
	}
}

func TestResearchProgressiveCandidateRevalidationUsesOnlyFreshLocalReceipt(t *testing.T) {
	parallelProgressiveTest(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "" {
			t.Error("public candidate received credential")
		}
		fmt.Fprint(w, "<html><body>Public model information</body></html>")
	}))
	defer up.Close()
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	c, err := s.ensureCandidate(context.Background(), "public-provider", "m", "imported", "https://provider.example/models")
	if err != nil {
		t.Fatal(err)
	}
	c.LastChecked = now()
	if err = s.saveCandidateDetails(context.Background(), c.ID, c.CandidateDetails); err != nil {
		t.Fatal(err)
	}
	options := ResearchOptions{CandidateIDs: []string{c.ID}, ObservationMinutes: 15, Budget: 10}
	run := researchRunID(t, s, options)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("foreign last-checked metadata bypassed revalidation", calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_candidate_revalidations")
	run2 := researchRunID(t, s, options)
	s.runResearchPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("fresh local source fetched twice")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind='REVALIDATE_CANDIDATE' AND json_extract(result_json,'$.additional_http_requests')=0", run2)
	for _, mutation := range []string{"UPDATE research_candidate_revalidations SET checked_at=0", "UPDATE research_candidates SET state='FAILED'"} {
		if _, err = s.Store.DB.Exec(mutation); err != nil {
			t.Fatal(err)
		}
		researchRunID(t, s, options)
		s.runResearchPending(context.Background())
	}
	if calls.Load() != 3 {
		t.Fatal("stale or failed candidate borrowed cached observation", calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	// A changed public source cannot make a previously planned task follow it.
	run3 := researchRunID(t, s, options)
	c.Website = "https://provider.example/new-models"
	if err = s.saveCandidateDetails(context.Background(), c.ID, c.CandidateDetails); err != nil {
		t.Fatal(err)
	}
	s.runResearchPending(context.Background())
	if calls.Load() != 3 {
		t.Fatal("changed candidate source silently followed")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='FAILED'", run3)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status='SUCCESS'", run)
}
