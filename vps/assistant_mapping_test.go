package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAssistantMappingSavedPublicEvidenceUsesSharedReviewAndUndo(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	args := map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection, "root_pointer": "/tariffs", "model_path": "/handle"}
	budget := &assistantBudget{}
	view, e := s.assistantTool(context.Background(), "mapping-test", functionCall("read-fields", "read_connector_mapping_sample", args), budget)
	if e != nil {
		t.Fatal("Assistant cannot inspect saved public field descriptors:", e)
	}
	raw, _ := json.Marshal(view)
	if !strings.Contains(string(raw), "/fees/entry") || strings.Contains(string(raw), "credential-echo") || strings.Contains(string(raw), "sk-synthetic") {
		t.Fatal("saved public descriptors lost fields or leaked secrets:", string(raw))
	}
	request := mappingTestRequest(report)
	request.ID = "assistant-public-mapping"
	args = map[string]any{"selection": selection, "mapping": request}
	value, e := s.assistantTool(context.Background(), "mapping-test", functionCall("propose-fields", "preview_connector_mapping", args), budget)
	if e != nil {
		t.Fatal("Assistant cannot prepare the shared mapping Review:", e)
	}
	id := str(value.(map[string]any)["id"])
	if id == "" || calls.Load() != 1 || budget.web != 0 || budget.tests != 0 {
		t.Fatal("local draft consumed provider HTTP/inference or lost its version")
	}
	version := discoveredVersion(t, s, id)
	raw, _ = json.Marshal(version)
	if version.Status != "DRAFT" || version.Active || !strings.Contains(string(raw), `"mapping_author":"ASSISTANT"`) || strings.Contains(string(raw), "User-reviewed field semantics") {
		t.Fatal("Assistant proposal impersonated user review or activation:", string(raw))
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	// A lost tool reply can recover the same immutable version after a restart.
	s = reopenScheduleStore(t, s)
	value, e = s.assistantTool(context.Background(), "mapping-test", functionCall("recover-fields", "preview_connector_mapping", args), budget)
	if e != nil || str(value.(map[string]any)["id"]) != id {
		t.Fatal("mapping recovery duplicated or lost the saved proposal", e)
	}
	// User and Assistant proposals cannot borrow the other's request identity.
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", request), 409)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": selection}), 200)
	if discoveredVersion(t, s, id).Status != "VALIDATED" {
		t.Fatal("shared fixture and real source test did not validate")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", selection), 200)
	var facts string
	s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='PRICING' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id).Scan(&facts)
	if !strings.Contains(facts, `"input":"0"`) || !strings.Contains(facts, `"output":"0.002"`) || strings.Contains(facts, "effective_charge") {
		t.Fatal("formal nominal facts did not preserve zero versus unknown:", facts)
	}
	version = discoveredVersion(t, s, id)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": version.ActivationActionID, "confirm": true}), 200)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	if calls.Load() != 3 {
		t.Fatal("expected discovery, one real test, one formal runtime read:", calls.Load())
	}
}

func TestAssistantMappingOversizedDescriptorsKeepWholeSampleLocal(t *testing.T) {
	var doc map[string]any
	if json.Unmarshal([]byte(mappingPublicFixture), &doc) != nil {
		t.Fatal("fixture JSON")
	}
	notes := map[string]any{}
	for i := 0; i < 110; i++ {
		notes[fmt.Sprintf("note%03d", i)] = strings.Repeat("x", 200)
	}
	doc["public_notes"] = notes
	raw, _ := json.Marshal(doc)
	var body atomic.Value
	body.Store(string(raw))
	s, selection, report, calls := mappingTestSetup(t, &body)
	args := map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection, "root_pointer": "/tariffs", "model_path": "/handle"}
	value, e := s.assistantTool(context.Background(), "descriptor-limit", functionCall("too-many-fields", "read_connector_mapping_sample", args), &assistantBudget{})
	if e == nil || value != nil || !strings.Contains(e.Error(), "local field review") {
		t.Fatal("oversized descriptors were truncated into allegedly complete model evidence", e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil), 200)
	if calls.Load() != 1 {
		t.Fatal("descriptor limit refetched the source")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_samples")
}

func TestAssistantMappingRejectsOtherScopeGuessedFieldsAndStaleAuthority(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	read := map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}
	call := func(name string, value any) error {
		_, e := s.assistantTool(context.Background(), "mapping-rejections", functionCall("bounded-fields", name, value), &assistantBudget{})
		return e
	}
	// Containers can be inspected without choosing a row. Only a complete,
	// unique model row can subsequently supply field descriptors.
	if e := call("read_connector_mapping_sample", read); e != nil {
		t.Fatal(e)
	}
	for _, other := range []Selection{
		{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "other", Endpoint: "CHAT_COMPLETIONS"},
		{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "m", Group: "vip", Endpoint: "CHAT_COMPLETIONS"},
		{ProviderID: "p", AccountID: "a", CredentialID: "other", ModelID: "m", Endpoint: "CHAT_COMPLETIONS"},
	} {
		read["selection"] = other
		if call("read_connector_mapping_sample", read) == nil || call("preview_connector_mapping", map[string]any{"selection": other, "mapping": mappingTestRequest(report)}) == nil {
			t.Fatal("another selection borrowed public sample authority")
		}
	}
	read["selection"] = selection
	read["root_pointer"], read["model_path"] = "/tariffs/1", "/handle"
	if call("read_connector_mapping_sample", read) == nil {
		t.Fatal("an indexed row hid the complete source selection")
	}
	request := mappingTestRequest(report)
	request.Units["input"] = mappingLocation{Path: "/daily/calls_unit"}
	if call("preview_connector_mapping", map[string]any{"selection": selection, "mapping": request}) == nil {
		t.Fatal("guessed unit was accepted")
	}
	read = map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection, "confirm_private": true}
	if call("read_connector_mapping_sample", read) == nil {
		t.Fatal("unknown private authority argument was accepted")
	}
	request = mappingTestRequest(report)
	request.Fields["input"] = "top-secret-example-key"
	if call("preview_connector_mapping", map[string]any{"selection": selection, "mapping": request}) == nil {
		t.Fatal("raw secret entered a mapping proposal")
	}
	if _, e := s.Store.DB.Exec("INSERT INTO credential_secret_versions SELECT provider_id,account_id,id,999,encrypted_key,? FROM credentials WHERE id=?", now(), selection.CredentialID); e != nil {
		t.Fatal(e)
	}
	read = map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}
	if call("read_connector_mapping_sample", read) == nil || call("preview_connector_mapping", map[string]any{"selection": selection, "mapping": mappingTestRequest(report)}) == nil {
		t.Fatal("stale credential context retained mapping authority")
	}
	if calls.Load() != 1 {
		t.Fatal("rejected local operations emitted source HTTP")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestAssistantMappingPrivateSamplesStayLocalAfterExplicitPrivateRead(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/model-plan" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			http.Error(w, "explicit selected authority required", 403)
			return
		}
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingFixture))
	})
	selection := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	r := req(t, s, "POST", "/api/v1/connectors/discover", map[string]any{"id": "explicit-private-source", "selection": selection, "source_path": "/api/model-plan", "credential_role": "INFERENCE", "confirm_private": true})
	requireCode(t, r, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(r.Body.Bytes(), &report) != nil || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" {
		t.Fatal("real selected private read lost its local sample")
	}
	for name, args := range map[string]any{
		"read_connector_mapping_sample": map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection},
		"preview_connector_mapping":     map[string]any{"selection": selection, "mapping": mappingTestRequest(report)},
	} {
		value, e := s.assistantTool(context.Background(), "private-denial", functionCall("private-fields", name, args), &assistantBudget{})
		if e == nil || value != nil || !strings.Contains(e.Error(), "private") || strings.Contains(e.Error(), "150") || strings.Contains(e.Error(), "top-secret-example-key") {
			t.Fatal("local private consent became model data permission:", name, value, e)
		}
	}
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil), 200)
	if calls.Load() != 1 {
		t.Fatal("private denial reissued a source read")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestAssistantMappingStorageFailureCannotLeaveHalfAProposal(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	if _, e := s.Store.DB.Exec("CREATE TRIGGER reject_assistant_mapping BEFORE UPDATE ON connector_mapping_requests BEGIN SELECT RAISE(ABORT,'synthetic durable storage fault'); END"); e != nil {
		t.Fatal(e)
	}
	args := map[string]any{"selection": selection, "mapping": mappingTestRequest(report)}
	if _, e := s.assistantTool(context.Background(), "storage-fault", functionCall("proposal", "preview_connector_mapping", args), &assistantBudget{}); e == nil {
		t.Fatal("failed transaction was reported as a saved proposal")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_generated_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests")
	if _, e := s.Store.DB.Exec("DROP TRIGGER reject_assistant_mapping"); e != nil {
		t.Fatal(e)
	}
	if _, e := s.assistantTool(context.Background(), "storage-fault", functionCall("proposal", "preview_connector_mapping", args), &assistantBudget{}); e != nil {
		t.Fatal("exact request could not recover after storage repair:", e)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	if calls.Load() != 1 {
		t.Fatal("draft transaction performed provider HTTP")
	}
}
