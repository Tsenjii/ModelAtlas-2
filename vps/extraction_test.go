package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const extractionFixtureJSON = `{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`

func extractionFixture(t *testing.T, modes ...*atomic.Int32) (*Server, Selection, []string, *atomic.Int32) {
	t.Helper()
	calls := &atomic.Int32{}
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/metadata" {
			calls.Add(1)
			if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("X-Api-Key") != "" {
				t.Error("anonymous extraction borrowed a Key")
			}
			if len(modes) > 0 {
				switch modes[0].Load() {
				case 1:
					http.Error(w, "synthetic unavailable", 503)
					return
				case 2:
					if calls.Load()%3 == 0 {
						w.Write([]byte(strings.Replace(extractionFixtureJSON, `"output":"2"`, `"output":"99"`, 1)))
						return
					}
				case 3:
					<-r.Context().Done()
					return
				}
			}
			w.Write([]byte(extractionFixtureJSON))
			return
		}
		w.Write([]byte(`{"data":[{"id":"m"}]}`))
	})
	v := seed(t, s, "https://extraction.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	discovery := discoverForTest(t, s, "extraction-source", "/api/metadata", v)
	ids := []string{}
	for _, id := range discovery.DraftIDs {
		c := discoveredVersion(t, s, id)
		if oneOf(c.Config.Kind, "PRICING", "QUOTA") {
			requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
			requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
			ids = append(ids, id)
		}
	}
	if len(ids) != 2 || calls.Load() != 3 {
		t.Fatal("exact two-rule fixture required", ids, calls.Load())
	}
	return s, v, ids, calls
}

func proposeExtractionTest(t *testing.T, s *Server, v Selection, ids []string, id string) {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/proposals", map[string]any{"id": id, "selection": v, "connector_ids": ids}), 201)
}
func activateExtractionTest(t *testing.T, s *Server, id string) string {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": newID(), "version_id": id, "confirm": true}), 200)
	r := req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": id})
	requireCode(t, r, 200)
	var plan extractionReview
	if json.Unmarshal(r.Body.Bytes(), &plan) != nil {
		t.Fatal("invalid plan")
	}
	r = req(t, s, "POST", "/api/v1/extraction/activate", map[string]any{"id": id, "confirm": true, "expected_active_id": plan.Expected, "review_fingerprint": plan.Fingerprint})
	requireCode(t, r, 200)
	var out map[string]any
	json.Unmarshal(r.Body.Bytes(), &out)
	return str(out["action_id"])
}

func TestExtractionIntentRecoveryDoesNotRepeatProposalOrComparisonHTTP(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	input := map[string]any{"id": "stable-proposal", "selection": v, "connector_ids": ids}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/proposals", input), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/proposals", input), 200)
	input["connector_ids"] = ids[:1]
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/proposals", input), 422)
	body := map[string]any{"id": "stable-comparison", "version_id": "stable-proposal", "confirm": true}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", body), 200)
	before := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", body), 200)
	requireCode(t, req(t, s, "GET", "/api/v1/extraction", nil), 200)
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", body), 200)
	if calls.Load() != before {
		t.Fatal("saved comparison was automatically replayed", calls.Load(), before)
	}
	body["version_id"] = "another-version"
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", body), 409)
}

func TestExtractionChangedKeyBlocksComparisonAndProductionWithoutLosingHistory(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "bound-plan")
	activateExtractionTest(t, s, "bound-plan")
	s.enrichConnectors(context.Background(), v)
	before := calls.Load()
	encrypted, e := s.encrypt("synthetic-rotated-key")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Store.DB.Exec("UPDATE credentials SET encrypted_key=? WHERE provider_id=? AND id=?", encrypted, v.ProviderID, v.CredentialID); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "changed-key-comparison", "version_id": "bound-plan", "confirm": true}), 409)
	out := s.enrichConnectors(context.Background(), v)
	if calls.Load() != before || out["extraction:bound-plan"] != "SCOPE_CHANGED" {
		t.Fatal("changed scope made public requests", out, calls.Load(), before)
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM observations WHERE kind IN('PRICING','QUOTA') AND status='SUCCESS'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
}

func TestExtractionFailureSharesOneAttemptKeepsLastGoodAndAutoUndoIsEpochBound(t *testing.T) {
	parallelResearchBudgetTest(t)
	mode := &atomic.Int32{}
	s, v, ids, calls := extractionFixture(t, mode)
	proposeExtractionTest(t, s, v, ids, "monitored")
	firstAction := activateExtractionTest(t, s, "monitored")
	s.enrichConnectors(context.Background(), v)
	mode.Store(1)
	for i := 0; i < 3; i++ {
		before := calls.Load()
		out := s.enrichConnectors(context.Background(), v)
		if calls.Load() != before+1 {
			t.Fatal("failed exact GET was repeated", i, calls.Load(), before)
		}
		if i == 2 && out["extraction:monitored"] != "AUTO_ROLLED_BACK" {
			t.Fatal("three real failures did not restore original mode", out)
		}
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE id=? AND state='UNDONE'", firstAction)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM observations WHERE kind IN('PRICING','QUOTA') AND status='SUCCESS'")
	before := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "monitored"}), 409)
	s.enrichConnectors(context.Background(), v)
	if calls.Load() != before+2 {
		t.Fatal("automatic Undo did not restore original independent reads")
	}
	mode.Store(0)
	secondAction := activateExtractionTest(t, s, "monitored")
	if secondAction == firstAction {
		t.Fatal("new trial borrowed prior activation")
	}
	mode.Store(1)
	s.enrichConnectors(context.Background(), v)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE id=? AND state='CONFIRMED'", secondAction)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestExtractionDifferentValidFactsCannotQualifyAsImprovement(t *testing.T) {
	parallelResearchBudgetTest(t)
	mode := &atomic.Int32{}
	s, v, ids, calls := extractionFixture(t, mode)
	proposeExtractionTest(t, s, v, ids, "different-facts")
	mode.Store(2)
	r := req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "unequal", "version_id": "different-facts", "confirm": true})
	requireCode(t, r, 200)
	var c extractionComparison
	json.Unmarshal(r.Body.Bytes(), &c)
	if c.Status != "NO_IMPROVEMENT" || c.HTTP == nil || *c.HTTP != 3 || len(c.Rounds) != 1 || c.Rounds[0].Equal || calls.Load() != 6 {
		t.Fatal("different facts were labeled improved", r.Body.String(), calls.Load())
	}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "different-facts"}), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_active")
}

func TestExtractionCancellationRetainsActualAdmissionWithoutModelFailure(t *testing.T) {
	parallelResearchBudgetTest(t)
	mode := &atomic.Int32{}
	s, v, ids, calls := extractionFixture(t, mode)
	proposeExtractionTest(t, s, v, ids, "cancelled-plan")
	mode.Store(3)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := httptest.NewRequest("POST", "/api/v1/extraction/compare", strings.NewReader(`{"id":"cancelled-comparison","version_id":"cancelled-plan","confirm":true}`)).WithContext(ctx)
	request.Header.Set("Authorization", "Bearer "+s.Token)
	request.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { s.Handler().ServeHTTP(recorder, request); close(done) }()
	deadline := time.Now().Add(3 * time.Second)
	for calls.Load() < 4 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if calls.Load() != 4 {
		cancel()
		<-done
		t.Fatal("actual request not admitted")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not stop public comparison")
	}
	var c extractionComparison
	if json.Unmarshal(recorder.Body.Bytes(), &c) != nil || c.Status != "CANCELLED" || c.HTTP == nil || *c.HTTP != 1 {
		t.Fatal("cancel lost admitted HTTP", recorder.Body.String())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_active")
	before := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "cancelled-comparison", "version_id": "cancelled-plan", "confirm": true}), 200)
	if calls.Load() != before {
		t.Fatal("cancelled comparison replayed")
	}
}

func TestExtractionInterruptedComparisonAndBatchStayUnknownWithoutAutomaticHTTP(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "interrupted-plan")
	c := extractionComparison{ID: "interrupted-comparison", VersionID: "interrupted-plan", Status: "RUNNING", CompletedHTTP: 3, Rounds: []extractionRound{{BaselineHTTP: 2, ProposedHTTP: 1, Equal: true}}, At: now()}
	raw, _ := json.Marshal(c)
	if _, e := s.Store.DB.Exec("INSERT INTO extraction_comparisons VALUES(?,?,?,?,?)", c.ID, c.VersionID, c.Status, string(raw), now()); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Store.DB.Exec(`INSERT INTO extraction_observations VALUES('interrupted-batch','interrupted-plan','RUNNING','{"status":"RUNNING","http_requests":null}',?)`, now()); e != nil {
		t.Fatal(e)
	}
	before := calls.Load()
	s = reopenScheduleStore(t, s)
	r := req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": c.ID, "version_id": c.VersionID, "confirm": true})
	requireCode(t, r, 200)
	json.Unmarshal(r.Body.Bytes(), &c)
	if c.Status != "INTERRUPTED" || c.HTTP != nil || c.CompletedHTTP != 3 || len(c.Rounds) != 1 || calls.Load() != before {
		t.Fatal("interruption invented zero cost or replayed HTTP", r.Body.String())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_observations WHERE status='INTERRUPTED' AND json_extract(report_json,'$.http_requests') IS NULL")
	for _, table := range []string{"extraction_versions", "extraction_comparisons", "extraction_active", "extraction_bindings", "extraction_observations"} {
		if workspaceAllowed(table) {
			t.Fatal("local execution crossed workspace sync", table)
		}
	}
}

func TestExtractionLatestNegativeMeasurementCannotBorrowOldGreenReceipt(t *testing.T) {
	parallelResearchBudgetTest(t)
	mode := &atomic.Int32{}
	s, v, ids, _ := extractionFixture(t, mode)
	proposeExtractionTest(t, s, v, ids, "latest-proof")
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "initial-green", "version_id": "latest-proof", "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "latest-proof"}), 200)
	mode.Store(2)
	r := req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "later-negative", "version_id": "latest-proof", "confirm": true})
	requireCode(t, r, 200)
	var report extractionComparison
	json.Unmarshal(r.Body.Bytes(), &report)
	if report.Status != "NO_IMPROVEMENT" {
		t.Fatal("expected actual unequal later facts", r.Body.String())
	}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "latest-proof"}), 409)
}

func TestExtractionMutatedApprovedProofOrRuleBlocksBeforeAnotherHTTP(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, variant := range []string{"receipt", "rule", "binding"} {
		t.Run(variant, func(t *testing.T) {
			s, v, ids, calls := extractionFixture(t)
			proposeExtractionTest(t, s, v, ids, "immutable")
			activateExtractionTest(t, s, "immutable")
			before := calls.Load()
			var e error
			switch variant {
			case "receipt":
				_, e = s.Store.DB.Exec(`UPDATE extraction_comparisons SET report_json=json_set(report_json,'$.rounds[0].facts_sha256',?) WHERE version_id='immutable'`, strings.Repeat("0", 64))
			case "rule":
				_, e = s.Store.DB.Exec(`UPDATE connector_versions SET config_json=json_set(config_json,'$.path','/changed/metadata') WHERE id=?`, ids[0])
			case "binding":
				_, e = s.Store.DB.Exec(`DELETE FROM extraction_bindings WHERE version_id='immutable'`)
			}
			if e != nil {
				t.Fatal(e)
			}
			out := s.enrichConnectors(context.Background(), v)
			if calls.Load() != before || out["extraction:immutable"] != "SCOPE_CHANGED" {
				t.Fatal("changed local proof acquired an HTTP", variant, out, calls.Load(), before)
			}
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
		})
	}
}

func TestExtractionReviewRotationRaceKeepsActivationAtomic(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "review-race")
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "review-proof", "version_id": "review-race", "confirm": true}), 200)
	r := req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "review-race"})
	requireCode(t, r, 200)
	var plan extractionReview
	json.Unmarshal(r.Body.Bytes(), &plan)
	cipher, e := s.encrypt("synthetic-changed-during-review")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Store.DB.Exec("UPDATE credentials SET encrypted_key=? WHERE id=?", cipher, v.CredentialID); e != nil {
		t.Fatal(e)
	}
	before := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/activate", map[string]any{"id": "review-race", "confirm": true, "expected_active_id": plan.Expected, "review_fingerprint": plan.Fingerprint}), 409)
	if calls.Load() != before {
		t.Fatal("activation emitted HTTP")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_EXTRACTION'")
}

func TestExtractionStaleUndoAndAtomicBindingFailureKeepWholeCurrentPlan(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "before-plan")
	beforeAction := activateExtractionTest(t, s, "before-plan")
	proposeExtractionTest(t, s, v, ids, "after-plan")
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "after-measurement", "version_id": "after-plan", "confirm": true}), 200)
	r := req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "after-plan"})
	requireCode(t, r, 200)
	var plan extractionReview
	json.Unmarshal(r.Body.Bytes(), &plan)
	body := map[string]any{"id": "after-plan", "confirm": true, "expected_active_id": plan.Expected, "review_fingerprint": plan.Fingerprint}
	if _, e := s.Store.DB.Exec(`CREATE TRIGGER fail_extraction_binding BEFORE INSERT ON extraction_bindings WHEN NEW.version_id='after-plan' BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END`); e != nil {
		t.Fatal(e)
	}
	beforeHTTP := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/activate", body), 503)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_active WHERE version_id='before-plan'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_EXTRACTION'")
	if _, e := s.Store.DB.Exec("DROP TRIGGER fail_extraction_binding"); e != nil {
		t.Fatal(e)
	}
	r = req(t, s, "POST", "/api/v1/extraction/activate", body)
	requireCode(t, r, 200)
	var changed map[string]any
	json.Unmarshal(r.Body.Bytes(), &changed)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": beforeAction, "confirm": true}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": changed["action_id"], "confirm": true}), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_active WHERE version_id='before-plan'")
	if calls.Load() != beforeHTTP {
		t.Fatal("activation or Undo made network requests")
	}
}

func TestExtractionStorageFailureIsNotReclassifiedAsChangedReview(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "storage-plan")
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "storage-proof", "version_id": "storage-plan", "confirm": true}), 200)
	before := calls.Load()
	if _, e := s.Store.DB.Exec("ALTER TABLE extraction_comparisons RENAME TO extraction_comparisons_unavailable"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "storage-plan"}), 503)
	if calls.Load() != before {
		t.Fatal("failed local read acquired a network attempt")
	}
}

func TestExtractionLocalBudgetRefusalDoesNotDegradeOrDisableTrial(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	proposeExtractionTest(t, s, v, ids, "budget-trial")
	action := activateExtractionTest(t, s, "budget-trial")
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{v}, Budget: 1, ProbeBudget: 1})
	if _, e := s.Store.DB.Exec("UPDATE research_run_options SET used=budget WHERE run_id=?", run); e != nil {
		t.Fatal(e)
	}
	ctx := context.WithValue(context.Background(), researchBudgetKey{}, run)
	before := calls.Load()
	for i := 0; i < 3; i++ {
		s.enrichConnectors(ctx, v)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM extraction_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE id=? AND state='CONFIRMED'", action)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM extraction_observations WHERE status='FAILED'")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM extraction_observations WHERE status='LOCAL_WAIT' AND json_extract(report_json,'$.http_requests')=0")
	if calls.Load() != before {
		t.Fatal("local budget refusal acquired a network request")
	}
	assertSQLInt(t, s, 1, "SELECT used FROM research_run_options WHERE run_id=?", run)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestExtractionSchema34UpgradeKeepsExactOriginalDataAndConsistentSnapshot(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, _, calls := extractionFixture(t)
	before := calls.Load()
	for _, table := range []string{"extraction_observations", "extraction_bindings", "extraction_active", "extraction_comparisons", "extraction_versions"} {
		if _, e := s.Store.DB.Exec("DROP TABLE " + table); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := s.Store.DB.Exec("PRAGMA user_version=34"); e != nil {
		t.Fatal(e)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND id=? AND encrypted_key!=''", v.ProviderID, v.CredentialID)
	files, e := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-34-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(files) != 1 || verifySnapshot(files[0]) != nil || calls.Load() != before {
		t.Fatal("upgrade lost original consistent snapshot or emitted HTTP", files, e)
	}
}

func TestExtractionReviewedComparisonProductionBatchAndPersistentUndo(t *testing.T) {
	parallelResearchBudgetTest(t)
	s, v, ids, calls := extractionFixture(t)
	r := req(t, s, "POST", "/api/v1/extraction/proposals", map[string]any{"id": "extraction-plan", "selection": v, "connector_ids": ids})
	requireCode(t, r, 201)
	if calls.Load() != 3 {
		t.Fatal("proposal issued HTTP")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/extraction/activate", map[string]any{"id": "extraction-plan", "confirm": true}), 409)
	r = req(t, s, "POST", "/api/v1/extraction/compare", map[string]any{"id": "extraction-measurement", "version_id": "extraction-plan", "confirm": true})
	requireCode(t, r, 200)
	var comparison map[string]any
	json.Unmarshal(r.Body.Bytes(), &comparison)
	if comparison["status"] != "READY_FOR_TRIAL" || comparison["http_requests"] != float64(9) || comparison["total_monetary_cost"] != nil || calls.Load() != 12 {
		t.Fatal("comparison invented quality or cost", r.Body.String(), calls.Load())
	}
	r = req(t, s, "POST", "/api/v1/extraction/plan", map[string]any{"id": "extraction-plan"})
	requireCode(t, r, 200)
	var plan map[string]any
	json.Unmarshal(r.Body.Bytes(), &plan)
	r = req(t, s, "POST", "/api/v1/extraction/activate", map[string]any{"id": "extraction-plan", "confirm": true, "expected_active_id": plan["expected_active_id"], "review_fingerprint": plan["fingerprint"]})
	requireCode(t, r, 200)
	var activated map[string]any
	json.Unmarshal(r.Body.Bytes(), &activated)
	before := calls.Load()
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	if calls.Load() != before+1 {
		t.Fatal("actual Intelligence did not share one exact public read", before, calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM observations WHERE kind IN('PRICING','QUOTA') AND status='SUCCESS'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": activated["action_id"], "confirm": true}), 200)
	before = calls.Load()
	s.enrichConnectors(context.Background(), v)
	if calls.Load() != before+2 {
		t.Fatal("Undo did not restore original separate reads", calls.Load(), before)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
}
