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

func TestSiteRecipeActiveDefinitionCannotBorrowEarlierApproval(t *testing.T) {
	parallelResearchBudgetTest(t)
	var reviewed, changed atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/reviewed/models" {
			reviewed.Add(1)
		} else {
			changed.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"id":"m"}]}`))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	seed(t, s, upstream.URL+"/v1")
	response := req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"catalog_path": "/reviewed/models"}, "reason": "Explicitly apply this catalog path."})
	requireCode(t, response, 201)
	var recipe Recipe
	json.Unmarshal(response.Body.Bytes(), &recipe)
	request := syncRequest{ProviderID: "p", AccountID: "a", CredentialID: "c"}
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", request), 200)
	if reviewed.Load() != 1 || changed.Load() != 0 {
		t.Fatal("approved recipe did not reach its actual caller", reviewed.Load(), changed.Load())
	}
	// Same-row edits can arrive through imported/synchronized version history.
	// They are not another local execution approval, even on the same origin.
	if _, e := s.Store.DB.Exec(`UPDATE recipe_versions SET recipe_json='{"catalog_path":"/changed/models"}' WHERE id=?`, recipe.ID); e != nil {
		t.Fatal(e)
	}
	response = req(t, s, "POST", "/api/v1/models/sync", request)
	if response.Code < 400 || reviewed.Load() != 1 || changed.Load() != 0 {
		t.Fatalf("mutated version borrowed the old active approval: HTTP %d, reviewed %d, changed %d", response.Code, reviewed.Load(), changed.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM models WHERE provider_id='p' AND id='m'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
}

func TestSiteRecipeBindingReadFailureAndExplicitLegacyRollback(t *testing.T) {
	parallelResearchBudgetTest(t)
	var calls atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"id":"m"}]}`))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	seed(t, s, upstream.URL+"/v1")
	for _, path := range []string{"/first/models", "/second/models"} {
		requireCode(t, req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"catalog_path": path}, "reason": "Explicit legacy catalog override."}), 201)
	}
	input := syncRequest{ProviderID: "p", AccountID: "a", CredentialID: "c"}
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", input), 200)
	if _, e := s.Store.DB.Exec("ALTER TABLE recipe_execution_bindings RENAME TO recipe_bindings_unavailable"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", input), 503)
	if calls.Load() != 1 {
		t.Fatal("binding read failure fell back to a different path")
	}
	s.Store.DB.Exec("ALTER TABLE recipe_bindings_unavailable RENAME TO recipe_execution_bindings")
	s.Store.DB.Exec(`UPDATE recipe_versions SET recipe_json='{"catalog_path":"/changed/models"}' WHERE provider_id='p' AND version=2`)
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", input), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/rollback", map[string]any{"provider_id": "p", "version": 1, "confirm": true}), 200)
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", input), 200)
	if calls.Load() != 2 {
		t.Fatal("explicit legacy rollback did not restore the actual caller")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM recipe_versions")
}

func TestSiteRecipeSchema32SnapshotCapturesOnlyCurrentLegacyApproval(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	seed(t, s, "https://recipe-upgrade.example/v1")
	for _, path := range []string{"/first/models", "/second/models"} {
		requireCode(t, req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"catalog_path": path}, "reason": "Keep historical legacy versions."}), 201)
	}
	for _, table := range []string{"recipe_connector_action_changes", "recipe_test_receipts", "recipe_generated_versions", "recipe_execution_bindings"} {
		if workspaceAllowed(table) {
			t.Fatal("recipe execution authority entered sync", table)
		}
		if _, e := s.Store.DB.Exec("DROP TABLE " + table); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := s.Store.DB.Exec("PRAGMA user_version=32"); e != nil {
		t.Fatal(e)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, 33, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_execution_bindings")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_execution_bindings b JOIN recipe_versions v ON v.id=b.version_id WHERE v.version=2")
	p, e := s.provider(context.Background(), "p")
	if e != nil {
		t.Fatal(e)
	}
	cfg := s.config(context.Background(), p)
	if cfg.RecipeProblem != "" || cfg.Endpoints["MODELS"] != "/second/models" {
		t.Fatal("upgrade lost the explicitly active legacy configuration", cfg)
	}
	snapshots, e := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-32-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(snapshots) != 1 || verifySnapshot(snapshots[0]) != nil {
		t.Fatal("recipe upgrade lost its original consistent snapshot", e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/rollback", map[string]any{"provider_id": "p", "version": 1, "confirm": true}), 200)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM recipe_execution_bindings")
}

func TestSiteRecipePublicEvidenceGeneratesDraftAndRequiresActualRuleTests(t *testing.T) {
	parallelResearchBudgetTest(t)
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("public recipe borrowed a private credential")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`))
	})
	selection := seed(t, s, "https://site-recipe.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	report := discoverForTest(t, s, "recipe-evidence", "/api/metadata", selection)
	ids := []string{}
	for _, id := range report.DraftIDs {
		c := discoveredVersion(t, s, id)
		if oneOf(c.Config.Kind, "PRICING", "QUOTA") {
			ids = append(ids, id)
		}
	}
	if len(ids) != 2 {
		t.Fatal("fixture must create actual independent price and quota drafts", report)
	}
	input := siteRecipeRequest{ID: "recipe-proposal", DiscoveryID: report.ID, ConnectorIDs: ids}
	response := req(t, s, "POST", "/api/v1/recipes/proposals", input)
	requireCode(t, response, 201)
	var v Recipe
	if e := json.Unmarshal(response.Body.Bytes(), &v); e != nil {
		t.Fatal(e)
	}
	if v.Active || v.SiteDraft == nil || len(v.SiteDraft.Definition.Rules) != 2 || v.SiteDraft.Definition.Parser != siteRecipeParser || v.SiteDraft.Definition.Selection != selection || calls.Load() != 1 {
		t.Fatalf("generation invented activation or network work: %s", response.Body.String())
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/proposals", input), 200)
	input.ConnectorIDs = ids[:1]
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/proposals", input), 409)
	response = req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": v.ID})
	requireCode(t, response, 200)
	var evaluation siteRecipeEvaluation
	json.Unmarshal(response.Body.Bytes(), &evaluation)
	if evaluation.Status != "TEST_NEEDED" || evaluation.TestCalls != 2 || evaluation.Cost != nil || evaluation.Inference != 0 || calls.Load() != 1 {
		t.Fatal("fixture alone became actual test evidence", evaluation)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": v.Settings, "reason": "Cannot rebrand a generated draft as a manual approval."}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/rollback", map[string]any{"provider_id": "p", "version": v.Version, "confirm": true}), 409)
	for _, id := range ids {
		response = req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": selection})
		requireCode(t, response, 200)
		var result map[string]any
		json.Unmarshal(response.Body.Bytes(), &result)
		if result["status"] != "VALIDATED" {
			t.Fatal("actual rule GET test did not validate", response.Body.String())
		}
	}
	response = req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": v.ID})
	requireCode(t, response, 200)
	json.Unmarshal(response.Body.Bytes(), &evaluation)
	for _, rule := range evaluation.Rules {
		if rule.HTTP == nil || *rule.HTTP != 1 {
			t.Fatal("new public test admission count is missing or fabricated", rule)
		}
	}
	if evaluation.Status != "READY_FOR_REVIEW" || evaluation.TestCalls != 0 || calls.Load() != 3 {
		t.Fatal("actual scoped rule tests were not aggregated honestly", evaluation, calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM recipe_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_generated_versions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_test_receipts")
}

func TestSiteRecipeCompositeReviewActualCallerAndPersistentUndo(t *testing.T) {
	parallelResearchBudgetTest(t)
	var publicCalls, privateCalls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/inherited/models" {
			privateCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("legacy catalog lost its explicit credential")
			}
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
			return
		}
		publicCalls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("public recipe sent a model key")
		}
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`))
	})
	selection := seed(t, s, "https://composite.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	r := req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"catalog_path": "/inherited/models"}, "reason": "Keep the explicitly configured catalog endpoint."})
	requireCode(t, r, 201)
	var legacy Recipe
	json.Unmarshal(r.Body.Bytes(), &legacy)
	report := discoverForTest(t, s, "composite-discovery", "/api/metadata", selection)
	ids := []string{}
	for _, id := range report.DraftIDs {
		c := discoveredVersion(t, s, id)
		if oneOf(c.Config.Kind, "PRICING", "QUOTA") {
			ids = append(ids, id)
		}
	}
	r = req(t, s, "POST", "/api/v1/recipes/proposals", siteRecipeRequest{ID: "composite-recipe", DiscoveryID: report.ID, ConnectorIDs: ids})
	requireCode(t, r, 201)
	var draft Recipe
	json.Unmarshal(r.Body.Bytes(), &draft)
	if draft.SiteDraft == nil || draft.SiteDraft.Definition.InheritedID != legacy.ID || draft.Settings["catalog_path"] != "/inherited/models" {
		t.Fatal("generated metadata proposal changed a previously approved endpoint")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": draft.ID}), 409)
	for _, id := range ids {
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": selection}), 200)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": draft.ID}), 200)
	r = req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": draft.ID})
	requireCode(t, r, 200)
	var plan siteRecipeReview
	json.Unmarshal(r.Body.Bytes(), &plan)
	if plan.ExpectedID != legacy.ID || len(plan.Changes) != 2 || plan.MaximumHTTP != 0 || plan.Evaluation.Status != "READY_FOR_REVIEW" || publicCalls.Load() != 3 || privateCalls.Load() != 0 {
		t.Fatalf("review was not read-only and explicit: %+v", plan)
	}
	body := map[string]any{"id": draft.ID, "expected_active_id": legacy.ID, "confirm": true, "review_fingerprint": plan.Fingerprint}
	r = req(t, s, "POST", "/api/v1/recipes/activate", body)
	requireCode(t, r, 200)
	var activation map[string]string
	json.Unmarshal(r.Body.Bytes(), &activation)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_active")
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", selection), 200)
	if privateCalls.Load() != 1 || publicCalls.Load() != 5 {
		t.Fatal("actual caller did not retain inherited path and use both public rules", privateCalls.Load(), publicCalls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM observations WHERE source LIKE 'DECLARATIVE_PROVIDER_META:%' AND status='SUCCESS'")
	s = reopenScheduleStore(t, s)
	r = req(t, s, "GET", "/api/v1/recipes", nil)
	requireCode(t, r, 200)
	var all []Recipe
	json.Unmarshal(r.Body.Bytes(), &all)
	found := false
	for _, v := range all {
		if v.ID == draft.ID {
			found = true
			if !v.Active || v.SiteDraft == nil || v.ActivationActionID != activation["action_id"] {
				t.Fatal("generated recipe activation did not survive restart", v)
			}
		}
	}
	if !found {
		t.Fatal("recipe history disappeared")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": activation["action_id"], "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", selection), 200)
	if privateCalls.Load() != 2 || publicCalls.Load() != 5 {
		t.Fatal("Undo did not restore actual previous behavior", privateCalls.Load(), publicCalls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_active a JOIN recipe_versions r ON r.provider_id=a.provider_id AND r.version=a.version WHERE r.id=?", legacy.ID)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM recipe_versions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
}

func TestSiteRecipeCatalogReadFailureKeepsLastGoodWithoutAnotherEndpoint(t *testing.T) {
	parallelResearchBudgetTest(t)
	var publicCalls, privateCalls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/public/catalog" {
			publicCalls.Add(1)
			if r.Header.Get("Authorization") != "" {
				t.Error("public catalog received a private key")
			}
		} else {
			privateCalls.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"id":"m"}]}`))
	})
	selection := seed(t, s, "https://catalog-read.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	cfg := ConnectorConfig{Kind: "CATALOG", Path: "/public/catalog", Role: "NONE", Scope: "CATALOG", Root: "/data", Unit: "NONE", Fields: map[string]string{"model_id": "/id"}}
	r := req(t, s, "POST", "/api/v1/connectors", map[string]any{"provider_id": "p", "name": "catalog", "reason": "Explicit public catalog source.", "config": cfg})
	requireCode(t, r, 201)
	var version ConnectorVersion
	json.Unmarshal(r.Body.Bytes(), &version)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": version.ID, "selection": selection}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: version.ID, Confirm: true}), 200)
	input := syncRequest{ProviderID: "p", AccountID: "a", CredentialID: "c"}
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", input), 200)
	if publicCalls.Load() != 2 || privateCalls.Load() != 0 {
		t.Fatal("explicit public catalog was not used", publicCalls.Load(), privateCalls.Load())
	}
	if _, e := s.Store.DB.Exec("ALTER TABLE connector_active RENAME TO connector_active_unavailable"); e != nil {
		t.Fatal(e)
	}
	r = req(t, s, "POST", "/api/v1/models/sync", input)
	if r.Code != 503 || publicCalls.Load() != 2 || privateCalls.Load() != 0 {
		t.Fatalf("catalog read fault selected an unreviewed endpoint: HTTP %d public=%d private=%d", r.Code, publicCalls.Load(), privateCalls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM models WHERE provider_id='p' AND id='m'")
}

type siteRecipeFixture struct {
	server    *Server
	selection Selection
	draft     Recipe
	calls     *atomic.Int32
}

func newSiteRecipeFixture(t *testing.T) siteRecipeFixture {
	t.Helper()
	calls := &atomic.Int32{}
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("public rules received private authentication")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`))
	})
	v := seed(t, s, "https://recipe-tests.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	discovery := discoverForTest(t, s, "group-discovery", "/api/metadata", v)
	r := req(t, s, "POST", "/api/v1/recipes/proposals", siteRecipeRequest{ID: "group-recipe", DiscoveryID: discovery.ID, ConnectorIDs: discovery.DraftIDs})
	requireCode(t, r, 201)
	var draft Recipe
	json.Unmarshal(r.Body.Bytes(), &draft)
	if draft.SiteDraft == nil || len(draft.SiteDraft.Definition.Rules) != 2 {
		t.Fatal("fixture must have two independent real public rules")
	}
	return siteRecipeFixture{s, v, draft, calls}
}

func (f siteRecipeFixture) testedPlan(t *testing.T) siteRecipeReview {
	t.Helper()
	for _, rule := range f.draft.SiteDraft.Definition.Rules {
		requireCode(t, req(t, f.server, "POST", "/api/v1/connectors/test", map[string]any{"id": rule.VersionID, "selection": f.selection}), 200)
	}
	requireCode(t, req(t, f.server, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": f.draft.ID}), 200)
	r := req(t, f.server, "POST", "/api/v1/recipes/plan", map[string]string{"id": f.draft.ID})
	requireCode(t, r, 200)
	var plan siteRecipeReview
	json.Unmarshal(r.Body.Bytes(), &plan)
	return plan
}

func recipeActivationBody(p siteRecipeReview) map[string]any {
	return map[string]any{"id": p.VersionID, "confirm": true, "expected_active_id": p.ExpectedID, "review_fingerprint": p.Fingerprint}
}

func TestSiteRecipeAtomicActivationAndUndoFailuresKeepWholeGroup(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	second := strings.ReplaceAll(plan.Changes[1].Name, "'", "''")
	_, e := s.Store.DB.Exec("CREATE TRIGGER recipe_rule_fault BEFORE INSERT ON connector_active WHEN NEW.name='" + second + "' BEGIN SELECT RAISE(ABORT,'second rule unavailable'); END")
	if e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 503)
	for _, table := range []string{"recipe_active", "connector_active", "recipe_connector_action_changes", "recipe_execution_bindings", "action_ledger"} {
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+table)
	}
	s.Store.DB.Exec("DROP TRIGGER recipe_rule_fault")
	r := req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan))
	requireCode(t, r, 200)
	var applied map[string]any
	json.Unmarshal(r.Body.Bytes(), &applied)
	_, e = s.Store.DB.Exec("CREATE TRIGGER recipe_undo_fault BEFORE DELETE ON connector_active WHEN OLD.name='" + second + "' BEGIN SELECT RAISE(ABORT,'second undo unavailable'); END")
	if e != nil {
		t.Fatal(e)
	}
	body := map[string]any{"id": applied["action_id"], "confirm": true}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", body), 409)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM recipe_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE state='CONFIRMED'")
	s.Store.DB.Exec("DROP TRIGGER recipe_undo_fault")
	// An independent, explicit replacement must never be overwritten by group Undo.
	first := plan.Changes[0]
	r = req(t, s, "POST", "/api/v1/connectors", map[string]any{"provider_id": "p", "name": first.Name, "config": first.Config, "reason": "Explicit independent revision."})
	requireCode(t, r, 201)
	var replacement ConnectorVersion
	json.Unmarshal(r.Body.Bytes(), &replacement)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": replacement.ID, "selection": f.selection}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: replacement.ID, ExpectedID: first.VersionID, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", body), 409)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_active WHERE version_id=?", replacement.ID)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_active")
	if f.calls.Load() != 4 {
		t.Fatal("activation or Undo invented HTTP", f.calls.Load())
	}
}

func TestSiteRecipeReviewCannotBorrowChangedTestsKeySourceOrDefinition(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	// A later real test changes the exact reviewed receipt, even if it succeeds.
	first := plan.Changes[0].VersionID
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": first, "selection": f.selection}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": f.draft.ID}), 200)
	r := req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": f.draft.ID})
	requireCode(t, r, 200)
	json.Unmarshal(r.Body.Bytes(), &plan)
	mutations := []struct {
		read, change, restore string
		args                  []any
	}{
		{"SELECT encrypted_key FROM credentials WHERE id='c'", "UPDATE credentials SET encrypted_key='changed-material' WHERE id='c'", "UPDATE credentials SET encrypted_key=? WHERE id='c'", nil},
		{"SELECT report_json FROM connector_discoveries WHERE id='group-discovery'", "UPDATE connector_discoveries SET report_json=json_set(report_json,'$.version_hint','different') WHERE id='group-discovery'", "UPDATE connector_discoveries SET report_json=? WHERE id='group-discovery'", nil},
		{"SELECT definition_json FROM recipe_generated_versions WHERE version_id='group-recipe'", "UPDATE recipe_generated_versions SET definition_json=json_set(definition_json,'$.version_hint','different') WHERE version_id='group-recipe'", "UPDATE recipe_generated_versions SET definition_json=? WHERE version_id='group-recipe'", nil},
		{"SELECT config_json FROM connector_versions WHERE id=?", "UPDATE connector_versions SET config_json=json_set(config_json,'$.path','/changed') WHERE id=?", "UPDATE connector_versions SET config_json=? WHERE id=?", []any{first}},
	}
	for _, m := range mutations {
		var original string
		if e := s.Store.DB.QueryRow(m.read, m.args...).Scan(&original); e != nil {
			t.Fatal(e)
		}
		if _, e := s.Store.DB.Exec(m.change, m.args...); e != nil {
			t.Fatal(e)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 409)
		args := append([]any{original}, m.args...)
		if _, e := s.Store.DB.Exec(m.restore, args...); e != nil {
			t.Fatal(e)
		}
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM recipe_active")
	}
	s.Store.DB.Exec("UPDATE connector_test_receipts SET tested_at=0 WHERE version_id=?", first)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": f.draft.ID}), 409)
	if f.calls.Load() != 4 {
		t.Fatal("stale review issued HTTP", f.calls.Load())
	}
}

func TestSiteRecipeLocalExecutionAuthorityDoesNotTravelWithHistory(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 200)
	peer := testServer(t)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	workspacePairForTest(t, s, peer, host, false)
	workspaceRunForTest(t, peer)
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	var document BackupDocument
	json.Unmarshal(backup.Body.Bytes(), &document)
	portable := testServer(t)
	requireCode(t, req(t, portable, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	for _, target := range []*Server{peer, portable} {
		for _, table := range []string{"recipe_execution_bindings", "recipe_generated_versions", "recipe_test_receipts", "recipe_connector_action_changes"} {
			if workspaceAllowed(table) {
				t.Fatal("local authority included in sync", table)
			}
			assertSQLInt(t, target, 0, "SELECT COUNT(*) FROM "+table)
		}
		r := req(t, target, "GET", "/api/v1/recipes", nil)
		requireCode(t, r, 200)
		var versions []Recipe
		json.Unmarshal(r.Body.Bytes(), &versions)
		if len(versions) != 1 || versions[0].ID != f.draft.ID || versions[0].Status != "LOCAL_EVIDENCE_UNAVAILABLE" || versions[0].SiteDraft != nil || versions[0].Active {
			t.Fatalf("imported history borrowed local proof: %s", r.Body.String())
		}
		requireCode(t, req(t, target, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 409)
		requireCode(t, req(t, target, "POST", "/api/v1/recipes/rollback", map[string]any{"provider_id": "p", "version": f.draft.Version, "confirm": true}), 409)
	}
	if f.calls.Load() != 3 {
		t.Fatal("import executed a public recipe", f.calls.Load())
	}
}

func TestSiteRecipeManualSettingsCannotRemoveGroupScopeApproval(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 200)
	cipher, e := s.encrypt("rotated-public-recipe-fixture-key")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Store.DB.Exec("UPDATE credentials SET encrypted_key=? WHERE id='c'", cipher); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes", map[string]any{"provider_id": "p", "settings": map[string]any{"notes": "A separate manual note is not another group approval."}, "reason": "Explicit manual settings only."}), 201)
	result := s.enrichConnectors(context.Background(), f.selection)
	if f.calls.Load() != 3 || len(result) != 2 {
		t.Fatal("manual settings bypassed group scope", result, f.calls.Load())
	}
	for _, status := range result {
		if status != "FAILED" {
			t.Fatal("changed key borrowed grouped tests", result)
		}
	}
}

func TestSiteRecipeApprovedReadersContinueAfterDraftAdmissionExpires(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 200)
	if _, e := s.Store.DB.Exec("UPDATE recipe_generated_versions SET proof_json=json_set(proof_json,'$.at',?,'$.expires_at',?) WHERE version_id=?", now()-int64(25*time.Hour/time.Millisecond), now()-int64(time.Hour/time.Millisecond), f.draft.ID); e != nil {
		t.Fatal(e)
	}
	// Already-approved readers fetch new facts. Draft expiry cannot silently
	// disable ordinary sync the next day, and still cannot authorize a new apply.
	result := s.enrichConnectors(context.Background(), f.selection)
	for _, status := range result {
		if status != "SUCCESS" {
			t.Fatal("approved reader expired with its draft", result)
		}
	}
	if f.calls.Load() != 5 {
		t.Fatal("expired draft changed normal read workload", f.calls.Load())
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": f.draft.ID}), 409)
	r := req(t, s, "GET", "/api/v1/recipes", nil)
	requireCode(t, r, 200)
	var versions []Recipe
	json.Unmarshal(r.Body.Bytes(), &versions)
	if len(versions) != 1 || !versions[0].Active || versions[0].Status != "READY_FOR_REVIEW" {
		t.Fatal("active recipe lost its readable state", r.Body.String())
	}
}

func TestSiteRecipeReceiptReadFailureIsUnavailableWithoutPartialWrites(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newSiteRecipeFixture(t)
	s := f.server
	plan := f.testedPlan(t)
	if _, e := s.Store.DB.Exec("ALTER TABLE connector_test_receipts RENAME TO receipt_unavailable"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/recipes", nil), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": f.draft.ID}), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": f.draft.ID}), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/activate", recipeActivationBody(plan)), 503)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM recipe_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM action_ledger")
	if f.calls.Load() != 3 {
		t.Fatal("read failure repeated a rule GET", f.calls.Load())
	}
}

func TestSiteRecipeCancelledRuleKeepsPartialResultsAndActualAdmission(t *testing.T) {
	parallelResearchBudgetTest(t)
	var calls atomic.Int32
	var cancel context.CancelFunc
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 3 {
			cancel()
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"},"quota":{"rpd":120,"tpd":1200000}}]}`))
	})
	selection := seed(t, s, "https://cancelled-recipe.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	d := discoverForTest(t, s, "cancelled-discovery", "/api/metadata", selection)
	r := req(t, s, "POST", "/api/v1/recipes/proposals", siteRecipeRequest{ID: "cancelled-recipe", DiscoveryID: d.ID, ConnectorIDs: d.DraftIDs})
	requireCode(t, r, 201)
	var draft Recipe
	json.Unmarshal(r.Body.Bytes(), &draft)
	rules := draft.SiteDraft.Definition.Rules
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": rules[0].VersionID, "selection": selection}), 200)
	ctx, stop := context.WithCancel(context.Background())
	cancel = stop
	defer stop()
	body, _ := json.Marshal(map[string]any{"id": rules[1].VersionID, "selection": selection})
	request := httptest.NewRequest("POST", "/api/v1/connectors/test", strings.NewReader(string(body))).WithContext(ctx)
	request.Header.Set("Authorization", "Bearer "+s.Token)
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	s.Handler().ServeHTTP(response, request)
	requireCode(t, response, 200)
	var result struct {
		Status string `json:"status"`
		Test   struct {
			HTTP *int    `json:"http_requests"`
			Cost *string `json:"total_monetary_cost"`
		} `json:"test"`
	}
	json.Unmarshal(response.Body.Bytes(), &result)
	if result.Status != "FAILED" || result.Test.HTTP == nil || *result.Test.HTTP != 1 || result.Test.Cost != nil || calls.Load() != 3 {
		t.Fatal("cancelled admission was refunded or invented", response.Body.String(), calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_test_receipts")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_test_history")
	r = req(t, s, "POST", "/api/v1/recipes/evaluate", map[string]string{"id": draft.ID})
	requireCode(t, r, 200)
	var evaluation siteRecipeEvaluation
	json.Unmarshal(r.Body.Bytes(), &evaluation)
	if evaluation.Status != "TEST_NEEDED" || evaluation.TestCalls != 1 {
		t.Fatal("partial tests became complete group approval", evaluation)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/recipes/plan", map[string]string{"id": draft.ID}), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM recipe_active")
}
