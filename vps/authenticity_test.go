package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAuthenticityReportReusesScopedTrafficWithoutSendingInference(t *testing.T) {
	var calls atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	v := seed(t, s, upstream.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	var session Session
	r := req(t, s, "POST", "/api/v1/sessions", Session{Selection: v, Title: "Observation"})
	requireCode(t, r, 201)
	json.Unmarshal(r.Body.Bytes(), &session)
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "Reply OK", MaxTokens: 16}), 200)
	r = req(t, s, "POST", "/api/v1/authenticity/analyze", map[string]any{"id": "auth-first", "selection": v})
	requireCode(t, r, 201)
	if calls.Load() != 1 {
		t.Fatalf("analysis sent provider traffic: %d", calls.Load())
	}
}

type authenticityFixture struct {
	Server            *Server
	Target, Reference Selection
	Calls             atomic.Int32
	Mode              atomic.Int32
}

func newAuthenticityFixture(t *testing.T) *authenticityFixture {
	t.Helper()
	f := &authenticityFixture{Server: testServer(t)}
	response := func(target bool) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			f.Calls.Add(1)
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				http.Error(w, "json", 400)
				return
			}
			mode := int32(0)
			if target {
				mode = f.Mode.Load()
			}
			if mode == 4 {
				http.Error(w, "not authorized", 401)
				return
			}
			model := "m"
			if mode == 1 {
				model = "different-family"
			}
			if mode == 3 {
				model = "sk-leaked-unrecognized-token-12345678901234567890"
			}
			delta := map[string]any{"content": "Observed answer"}
			if _, ok := body["tools"]; ok {
				name := "echo"
				if mode == 2 {
					name = "not_offered"
				}
				delta = map[string]any{"tool_calls": []any{map[string]any{"index": 0, "id": "call-1", "type": "function", "function": map[string]any{"name": name, "arguments": "{\"value\":\"x\"}"}}}}
			}
			if mode == 5 {
				delta["reasoning_content"] = "Visible reasoning"
			}
			raw, _ := json.Marshal(map[string]any{"model": model, "choices": []any{map[string]any{"index": 0, "delta": delta}}, "usage": map[string]any{"prompt_tokens": 12, "completion_tokens": 3, "cost": "0.0000001"}})
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
		}
	}
	target := httptest.NewTLSServer(response(true))
	reference := httptest.NewTLSServer(response(false))
	t.Cleanup(target.Close)
	t.Cleanup(reference.Close)
	f.Server.Client = target.Client()
	f.Target = seed(t, f.Server, target.URL+"/v1")
	s := f.Server
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "reference", Name: "Reference", BaseURL: reference.URL + "/v1", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "reference", Label: "A"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "reference", AccountID: "a", Label: "C", Secret: "reference-secret-example-key"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "reference", ID: "m", DisplayName: "M"}), 201)
	f.Reference = Selection{ProviderID: "reference", AccountID: "a", CredentialID: "c", ModelID: "m", Endpoint: "CHAT_COMPLETIONS"}
	for _, v := range []Selection{f.Target, f.Reference} {
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", struct {
			Selection
			Capabilities []string `json:"capabilities"`
			Confirm      bool     `json:"confirm"`
		}{v, []string{"TOOL_CALLING"}, true}), 200)
	}
	return f
}
func authenticityTools() []ToolDefinition {
	return []ToolDefinition{{Type: "function", Function: ToolFunction{Name: "echo", Parameters: map[string]any{"type": "object", "properties": map[string]any{"value": map[string]any{"type": "string"}}, "required": []string{"value"}}}}}
}
func authenticityRequest(t *testing.T, s *Server, v Selection, prompt string, maxTokens int, tools bool) InferenceResult {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/sessions", Session{Selection: v, Title: "Auth evidence"})
	requireCode(t, r, 201)
	var session Session
	json.Unmarshal(r.Body.Bytes(), &session)
	request := inferRequest{SessionID: session.ID, Prompt: prompt, MaxTokens: maxTokens}
	if tools {
		request.Tools = authenticityTools()
	}
	result, code := s.execute(context.Background(), request, "PLAYGROUND", func(string, any) error { return nil })
	if code != 0 {
		t.Fatalf("real inference failed: %d %s", code, result.Outcome)
	}
	return result
}
func (f *authenticityFixture) populate(t *testing.T, v Selection) {
	authenticityRequest(t, f.Server, v, "Normal short request", 32, false)
	authenticityRequest(t, f.Server, v, strings.Repeat("Long context observation. ", 200), 32, false)
	authenticityRequest(t, f.Server, v, "Use echo with value x", 32, true)
}
func authenticityAnalyze(t *testing.T, s *Server, v Selection, id string) authenticityReport {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/authenticity/analyze", authenticityAnalysisRequest{id, v})
	requireCode(t, r, 201)
	var out authenticityReport
	if json.Unmarshal(r.Body.Bytes(), &out) != nil {
		t.Fatal("invalid report")
	}
	return out
}
func authenticityPreview(t *testing.T, f *authenticityFixture, id string) authenticityReference {
	t.Helper()
	r := req(t, f.Server, "POST", "/api/v1/authenticity/references/preview", authenticityReferenceRequest{ID: id, Target: f.Target, Reference: f.Reference, Reason: "I reviewed this independent source and accept it only as a behavior reference."})
	requireCode(t, r, 201)
	var out authenticityReference
	if json.Unmarshal(r.Body.Bytes(), &out) != nil {
		t.Fatal("invalid reference")
	}
	return out
}
func TestAuthenticityReviewedReferenceComparisonAndPersistentUndo(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	calls := f.Calls.Load()
	noRef := authenticityAnalyze(t, f.Server, f.Target, "before-reference")
	if noRef.Status != "INSUFFICIENT" || noRef.Identity != "UNPROVEN" || noRef.Target.Attested != 3 || noRef.Target.Usable != 3 || noRef.Target.Tools != 1 || noRef.Target.LargestPayload < 4096 || noRef.Target.ReportedCost.Total != nil || noRef.Target.ReportedCost.Unknown != 3 {
		t.Fatalf("invalid evidence: %+v", noRef)
	}
	ref := authenticityPreview(t, f, "reference-v1")
	if ref.ReviewStatus != "READY_FOR_REVIEW" || ref.ActiveID != "" || ref.Comparison.Status != "CONSISTENT_OBSERVATIONS" || ref.Comparison.ReferenceState != "UNCONFIRMED_DRAFT" {
		t.Fatalf("invalid Review: %+v", ref)
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID}), 422)
	r := req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true})
	requireCode(t, r, 200)
	report := authenticityAnalyze(t, f.Server, f.Target, "with-reference")
	if report.Status != "CONSISTENT_OBSERVATIONS" || report.Identity != "UNPROVEN" || report.Confidence != "LOW" || report.MatchedConditions != 3 || report.MatchedSamples != 3 || report.ReferenceState != "USER_CONFIRMED" || report.ReferenceID != ref.ID {
		t.Fatalf("untruthful comparison: %+v", report)
	}
	if f.Calls.Load() != calls {
		t.Fatal("reference or analysis sent provider HTTP")
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/analyze", authenticityAnalysisRequest{"with-reference", f.Target}), 200)
	assertSQLInt(t, f.Server, 2, "SELECT COUNT(*) FROM authenticity_reports")
	f.Server = reopenScheduleStore(t, f.Server)
	r = req(t, f.Server, "GET", "/api/v1/authenticity/references?"+selectionURL(f.Target), nil)
	requireCode(t, r, 200)
	var list struct {
		Versions []authenticityReference `json:"versions"`
		ActionID string                  `json:"activation_action_id"`
	}
	json.Unmarshal(r.Body.Bytes(), &list)
	if list.ActionID == "" || len(list.Versions) != 1 || list.Versions[0].ActionID != list.ActionID {
		t.Fatal("persistent Undo unavailable: " + r.Body.String())
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/versions/undo", map[string]any{"id": list.ActionID, "confirm": true}), 200)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/versions/undo", map[string]any{"id": list.ActionID, "confirm": true}), 409)
	if after := authenticityAnalyze(t, f.Server, f.Target, "after-undo"); after.ReferenceState != "MISSING" || after.Status != "INSUFFICIENT" {
		t.Fatal("Undo did not remove reference authority")
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM authenticity_reference_versions")
}
func selectionURL(v Selection) string {
	q := url.Values{}
	raw, _ := json.Marshal(v)
	var fields map[string]string
	json.Unmarshal(raw, &fields)
	for k, value := range fields {
		q.Set(k, value)
	}
	return q.Encode()
}
func TestAuthenticityConflictingNamesAndToolFormatNeverProveSubstitution(t *testing.T) {
	for _, mode := range []int32{1, 2} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			f := newAuthenticityFixture(t)
			f.populate(t, f.Reference)
			f.Mode.Store(mode)
			f.populate(t, f.Target)
			ref := authenticityPreview(t, f, "ref")
			requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 200)
			out := authenticityAnalyze(t, f.Server, f.Target, "conflict")
			if out.Status != "CONFLICTING_OBSERVATIONS" || out.Identity != "UNPROVEN" || out.Confidence != "LOW" {
				t.Fatalf("conflict misclassified: %+v", out)
			}
			found := false
			for _, finding := range out.Findings {
				found = found || finding.State == "CONFLICT" && len(finding.SampleIDs) > 0
			}
			if !found {
				t.Fatal("conflict lacks evidence IDs")
			}
		})
	}
}
func TestAuthenticityRequiresExactConditionsAndMeaningfulReference(t *testing.T) {
	f := newAuthenticityFixture(t)
	for i := 0; i < 3; i++ {
		authenticityRequest(t, f.Server, f.Reference, "Reply OK", 16, false)
	}
	weak := authenticityPreview(t, f, "weak")
	if weak.ReviewStatus != "INSUFFICIENT" {
		t.Fatal("three Reply OKs became a reference")
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: weak.ID, Confirm: true}), 409)
	f.populate(t, f.Reference)
	// Same prompt/tool set, different max-output limit: never comparable.
	for _, p := range []string{"Normal short request", strings.Repeat("Long context observation. ", 200), "Use echo with value x"} {
		authenticityRequest(t, f.Server, f.Target, p, 64, p == "Use echo with value x")
	}
	ref := authenticityPreview(t, f, "different-conditions")
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 200)
	out := authenticityAnalyze(t, f.Server, f.Target, "insufficient")
	if out.Status != "INSUFFICIENT" || out.MatchedSamples != 0 || out.Identity != "UNPROVEN" {
		t.Fatalf("mismatched parameters compared: %+v", out)
	}
}
func TestAuthenticityUnattestedChangedAndCancelledRecordsAreNotEvidence(t *testing.T) {
	for _, kind := range []string{"unattested", "tampered", "cancelled", "interrupted", "running", "material", "config", "origin"} {
		t.Run(kind, func(t *testing.T) {
			f := newAuthenticityFixture(t)
			f.populate(t, f.Target)
			var query string
			switch kind {
			case "unattested":
				query = "DELETE FROM authenticity_local_samples"
			case "tampered":
				query = "UPDATE telemetry SET details_json='{}'"
			case "cancelled":
				query = "UPDATE telemetry SET outcome='CANCELLED'"
			case "interrupted":
				query = "UPDATE telemetry SET outcome='INTERRUPTED'"
			case "running":
				query = "UPDATE telemetry SET outcome='RUNNING'"
			case "material":
				query = "UPDATE authenticity_local_samples SET material_stamp='foreign'"
			case "config":
				query = `UPDATE provider_config SET config_json='{"adapter":"GENERIC","auth_mode":"NONE"}' WHERE provider_id='p'`
			case "origin":
				query = "UPDATE credential_origins SET base_url='https://other.example/v1' WHERE provider_id='p'"
			}
			if _, err := f.Server.Store.DB.Exec(query); err != nil {
				t.Fatal(err)
			}
			out := authenticityAnalyze(t, f.Server, f.Target, "changed")
			if out.Target.Attested != 0 || out.Target.Usable != 0 || out.Target.Failed != 0 || out.Target.ReportedCost.Total != nil || out.Status != "INSUFFICIENT" {
				t.Fatalf("borrowed invalid evidence: %+v", out)
			}
			if oneOf(kind, "cancelled", "interrupted", "running") && out.Target.Excluded != 3 {
				t.Fatal("cancellation counted in health")
			}
		})
	}
}
func TestAuthenticityCredentialRotationAndLateOldTrafficStayIsolated(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	ref := authenticityPreview(t, f, "ref")
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 200)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": f.Target.ProviderID, "account_id": f.Target.AccountID, "credential_id": f.Target.CredentialID, "secret": "rotated-secret-example-key", "confirm": true}), 200)
	out := authenticityAnalyze(t, f.Server, f.Target, "rotated")
	if out.Target.Attested != 0 || out.ReferenceState != "STALE" || out.Identity != "UNPROVEN" {
		t.Fatalf("old Key reused: %+v", out)
	}
	assertSQLInt(t, f.Server, 6, "SELECT COUNT(*) FROM telemetry")
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true, ExpectedID: ref.ID}), 409)
}
func TestAuthenticityReferenceCASStalenessCanonicalAndOriginGates(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	for _, reference := range []Selection{f.Target, {ProviderID: f.Reference.ProviderID, AccountID: f.Reference.AccountID, CredentialID: f.Reference.CredentialID, ModelID: f.Reference.ModelID, Endpoint: "RESPONSES"}} {
		requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/preview", authenticityReferenceRequest{ID: newID(), Target: f.Target, Reference: reference, Reason: "review"}), 422)
	}
	ref1 := authenticityPreview(t, f, "first")
	ref2 := authenticityPreview(t, f, "second")
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref1.ID, Confirm: true}), 200)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref2.ID, Confirm: true}), 409)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref2.ID, Confirm: true, ExpectedID: ref1.ID}), 200)
	if _, err := f.Server.Store.DB.Exec("UPDATE authenticity_local_samples SET telemetry_hash='changed' WHERE sample_id IN (SELECT id FROM telemetry WHERE provider_id='reference')"); err != nil {
		t.Fatal(err)
	}
	out := authenticityAnalyze(t, f.Server, f.Target, "stale")
	if out.ReferenceState != "STALE" || out.Reference != nil || out.Status != "INSUFFICIENT" {
		t.Fatal("changed reference still compared")
	}
	if !authenticityOriginEqual("https://example.com:00443/v1", "https://EXAMPLE.COM/") {
		t.Fatal("default/numeric port aliases bypass self-reference gate")
	}
}
func TestAuthenticityCostPrecisionAndUnknownsRemainSeparate(t *testing.T) {
	samples := []authenticitySample{{ReportedCost: authenticityString("0.000000100000000000000001")}, {ReportedCost: authenticityString("0.000000200000000000000002")}, {EstimatedCost: authenticityString("0")}}
	out := sumAuthenticityCosts(samples, func(v authenticitySample) *string { return v.ReportedCost })
	if out.KnownSubtotal == nil || *out.KnownSubtotal != "0.000000300000000000000003" || out.Total != nil || out.Known != 2 || out.Unknown != 1 {
		t.Fatalf("precision or Unknown lost: %+v", out)
	}
	zero := sumAuthenticityCosts([]authenticitySample{{ReportedCost: authenticityString("0")}}, func(v authenticitySample) *string { return v.ReportedCost })
	if zero.Total == nil || *zero.Total != "0" {
		t.Fatal("known zero lost")
	}
	empty := sumAuthenticityCosts(nil, func(v authenticitySample) *string { return v.ReportedCost })
	if empty.Total != nil || empty.KnownSubtotal != nil {
		t.Fatal("empty became free")
	}
}
func TestAuthenticityDoesNotPersistPromptsResponsesOrRawSecrets(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.Mode.Store(3)
	authenticityRequest(t, f.Server, f.Target, "Private prompt that must not enter fingerprint evidence", 16, false)
	out := authenticityAnalyze(t, f.Server, f.Target, "safe")
	if out.Target.Attested != 1 || out.Target.Samples[0].ResponseModel != "" || out.Status != "INSUFFICIENT" {
		t.Fatal("unsafe model identifier became evidence")
	}
	for _, table := range []string{"authenticity_reports", "authenticity_local_samples"} {
		column := "report_json"
		if table == "authenticity_local_samples" {
			column = "features_json"
		}
		var raw string
		f.Server.Store.DB.QueryRow("SELECT " + column + " FROM " + table).Scan(&raw)
		for _, text := range []string{"Private prompt", "Observed answer", "top-secret-example-key", "sk-leaked"} {
			if strings.Contains(raw, text) {
				t.Fatal("private content in fingerprint: " + table)
			}
		}
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/preview", authenticityReferenceRequest{ID: "secret-ref", Target: f.Target, Reference: f.Reference, Reason: "top-secret-example-key"}), 422)
}
func TestAuthenticityIdempotencyHistoryPagingAndUnavailableDatabase(t *testing.T) {
	f := newAuthenticityFixture(t)
	for i := 0; i < 4; i++ {
		authenticityAnalyze(t, f.Server, f.Target, fmt.Sprint("report-", i))
	}
	r := req(t, f.Server, "GET", "/api/v1/authenticity/reports?"+selectionURL(f.Target)+"&limit=2&offset=2", nil)
	requireCode(t, r, 200)
	var reports []authenticityReport
	json.Unmarshal(r.Body.Bytes(), &reports)
	if len(reports) != 2 {
		t.Fatal("history inaccessible")
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/analyze", authenticityAnalysisRequest{"report-0", f.Reference}), 409)
	assertSQLInt(t, f.Server, 4, "SELECT COUNT(*) FROM authenticity_reports")
	f.Server.Store.DB.Close()
	r = req(t, f.Server, "POST", "/api/v1/authenticity/analyze", authenticityAnalysisRequest{"unavailable", f.Target})
	if r.Code < 400 {
		t.Fatal("unavailable database reported success")
	}
}

func authenticityString(v string) *string { return &v }

func TestAuthenticityAssistantUsesSharedLocalSuiteWithoutActivation(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	calls := f.Calls.Load()
	budget := assistantBudget{}
	out, err := f.Server.assistantTool(context.Background(), "assistant-run", functionCall("analyze", "analyze_authenticity", authenticityAnalysisRequest{"assistant-report", f.Target}), &budget)
	report, ok := out.(authenticityReport)
	if err != nil || !ok || report.Identity != "UNPROVEN" || report.Target.Attested != 3 {
		t.Fatalf("assistant bypassed suite: %v %T", err, out)
	}
	_, err = f.Server.assistantTool(context.Background(), "assistant-run", functionCall("reference", "preview_authenticity_reference", authenticityReferenceRequest{ID: "assistant-reference", Target: f.Target, Reference: f.Reference, Reason: "User requested a review draft."}), &budget)
	if err != nil {
		t.Fatal(err)
	}
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM authenticity_reference_active")
	if f.Calls.Load() != calls || budget.web != 0 || budget.tests != 0 {
		t.Fatal("local tools consumed provider traffic or inference budget")
	}
	for _, tool := range assistantTools() {
		if strings.Contains(tool.Function.Name, "activate_authenticity") {
			t.Fatal("assistant can activate its own reference")
		}
	}
}
func TestAuthenticitySchema25UpgradeAndSyncNeverCarryLocalAuthority(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	authenticityAnalyze(t, f.Server, f.Target, "local-report")
	host := httptest.NewTLSServer(f.Server.Handler())
	defer host.Close()
	peer := testServer(t)
	workspacePairForTest(t, f.Server, peer, host, true)
	workspaceRunForTest(t, peer)
	for _, table := range []string{"authenticity_reports", "authenticity_local_samples", "authenticity_reference_active", "authenticity_reference_versions"} {
		if oneOf(table, workspaceTables...) {
			t.Fatal("local authority sync-enabled", table)
		}
		assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM "+table)
	}
	peerOut := authenticityAnalyze(t, peer, f.Target, "synced-traffic")
	if peerOut.Target.Attested != 0 || peerOut.Target.Usable != 0 || peerOut.Status != "INSUFFICIENT" {
		t.Fatal("synced traffic acquired local proof")
	}
	legacy := testServer(t)
	seed(t, legacy, "https://legacy.example/v1")
	for _, table := range []string{"authenticity_reference_active", "authenticity_reference_versions", "authenticity_reports", "authenticity_local_samples"} {
		if _, err := legacy.Store.DB.Exec("DROP TABLE " + table); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := legacy.Store.DB.Exec("PRAGMA user_version=25"); err != nil {
		t.Fatal(err)
	}
	legacy = reopenScheduleStore(t, legacy)
	assertSQLInt(t, legacy, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM credentials")
	backups, err := filepath.Glob(filepath.Join(filepath.Dir(legacy.Store.path), "upgrade-backups", fmt.Sprintf("schema-25-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(backups) != 1 || verifySnapshot(backups[0]) != nil {
		t.Fatal("missing consistent prior-schema snapshot", err)
	}
}
func TestAuthenticityCaptureFailurePreservesAnswerAndRequestHistory(t *testing.T) {
	f := newAuthenticityFixture(t)
	if _, err := f.Server.Store.DB.Exec(`CREATE TRIGGER fail_auth_capture BEFORE INSERT ON authenticity_local_samples BEGIN SELECT RAISE(ABORT,'capture unavailable'); END`); err != nil {
		t.Fatal(err)
	}
	result := authenticityRequest(t, f.Server, f.Target, "User prompt", 16, false)
	if result.Outcome != "SUCCESS" || result.Text != "Observed answer" {
		t.Fatal("auxiliary evidence failure erased a valid answer")
	}
	assertSQLInt(t, f.Server, 1, "SELECT COUNT(*) FROM telemetry WHERE outcome='SUCCESS'")
	assertSQLInt(t, f.Server, 2, "SELECT COUNT(*) FROM messages")
	assertSQLInt(t, f.Server, 0, "SELECT COUNT(*) FROM authenticity_local_samples")
	report := authenticityAnalyze(t, f.Server, f.Target, "no-proof")
	if report.Target.Attested != 0 || report.Status != "INSUFFICIENT" {
		t.Fatal("failed capture silently trusted")
	}
}

func TestAuthenticityReferenceExpiryCanonicalAndDuplicateOriginRequireNewReview(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	ref := authenticityPreview(t, f, "fresh")
	secrets, err := f.Server.knownSecrets(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.Server.currentAuthenticityReference(context.Background(), f.Server.Store.DB, ref, now()+8*86400000, secrets); err == nil {
		t.Fatal("expired seven-day reference remained usable")
	}
	metadata := f.Server.metadata(context.Background(), f.Reference.ProviderID, f.Reference.ModelID)
	metadata.CanonicalID = "different-canonical"
	requireCode(t, req(t, f.Server, "POST", "/api/v1/models/metadata", metadata), 200)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 409)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/preview", authenticityReferenceRequest{ID: "wrong-canonical", Target: f.Target, Reference: f.Reference, Reason: "review"}), 422)
	metadata.CanonicalID = "m"
	requireCode(t, req(t, f.Server, "POST", "/api/v1/models/metadata", metadata), 200)
	var origin string
	if err = f.Server.Store.DB.QueryRow("SELECT base_url FROM credential_origins WHERE provider_id='p'").Scan(&origin); err != nil {
		t.Fatal(err)
	}
	if _, err = f.Server.Store.DB.Exec("UPDATE credential_origins SET base_url=? WHERE provider_id='reference'", origin); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/preview", authenticityReferenceRequest{ID: "same-origin", Target: f.Target, Reference: f.Reference, Reason: "review"}), 422)
}
func TestAuthenticityExactAccountKeyGroupAndOperationDoNotBorrowSamples(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	// An explicitly added second account/key with the same model and origin has
	// no traffic. Equal labels and model names must not fill its evidence gap.
	var base string
	f.Server.Store.DB.QueryRow("SELECT base_url FROM providers WHERE id='p'").Scan(&base)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/accounts", Account{ProviderID: "p", ID: "other", Label: "A"}), 201)
	requireCode(t, req(t, f.Server, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "other", ID: "c", Label: "C", Secret: "another-account-secret", Group: "special"}), 201)
	other := f.Target
	other.AccountID = "other"
	other.Group = "special"
	requireCode(t, req(t, f.Server, "POST", "/api/v1/my-models", other), 201)
	out := authenticityAnalyze(t, f.Server, other, "other-scope")
	if len(out.Target.Samples) != 0 || out.Status != "INSUFFICIENT" {
		t.Fatal("borrowed another account/key/group samples")
	}
	response := f.Target
	response.Endpoint = "RESPONSES"
	requireCode(t, req(t, f.Server, "POST", "/api/v1/my-models", response), 201)
	out = authenticityAnalyze(t, f.Server, response, "other-operation")
	if len(out.Target.Samples) != 0 || out.Status != "INSUFFICIENT" {
		t.Fatal("borrowed another endpoint/operation")
	}
}
func TestAuthenticityInFlightCredentialRotationDoesNotAttestCurrentRevision(t *testing.T) {
	s := testServer(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	defer close(release)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-release
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"Old request succeeded\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	t.Cleanup(up.Close)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	id, err := s.createTestSession(context.Background(), v)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan InferenceResult, 1)
	go func() {
		out, _ := s.execute(context.Background(), inferRequest{SessionID: id, Prompt: "In flight", MaxTokens: 16}, "PLAYGROUND", func(string, any) error { return nil })
		done <- out
	}()
	<-entered
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "new-material-during-flight", "confirm": true}), 200)
	release <- struct{}{}
	result := <-done
	if result.Outcome != "SUCCESS" {
		t.Fatal("late request history was erased")
	}
	out := authenticityAnalyze(t, s, v, "late-current")
	if out.Target.Attested != 0 || len(out.Target.Samples) != 0 || out.Status != "INSUFFICIENT" {
		t.Fatal("old in-flight material acquired current revision")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM authenticity_local_samples")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE credential_version=0")
}
func TestAuthenticityProviderReportedUSDUsesExactReceiptAndKnownZero(t *testing.T) {
	for _, amount := range []string{"0", "0.000000100000000001"} {
		t.Run(amount, func(t *testing.T) {
			s := testServer(t)
			up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				fmt.Fprintf(w, "data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"Cost evidence\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":1,\"cost\":%s}}\n\ndata: [DONE]\n\n", amount)
			}))
			defer up.Close()
			s.Client = mappedFixture(up)
			v := seed(t, s, "https://openrouter.ai/v1")
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
			authenticityRequest(t, s, v, "One request", 16, false)
			out := authenticityAnalyze(t, s, v, "cost")
			cost := out.Target.ReportedCost
			if cost.Total == nil || *cost.Total != amount || cost.Known != 1 || cost.Unknown != 0 || out.Target.EstimatedCost.Total != nil || out.Target.NominalCost.Total != nil {
				t.Fatalf("cost basis/unit lost: %+v", out.Target)
			}
		})
	}
}

func TestAuthenticityResearchReportAndEvidenceUseBoundCredentialOrigin(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	calls := f.Calls.Load()
	var bound string
	f.Server.Store.DB.QueryRow("SELECT base_url FROM credential_origins WHERE provider_id='p'").Scan(&bound)
	if _, err := f.Server.Store.DB.Exec("UPDATE providers SET base_url='https://different-primary.example/v1' WHERE id='p'"); err != nil {
		t.Fatal(err)
	}
	run := researchRunID(t, f.Server, ResearchOptions{Selections: []Selection{f.Target}, MinimalInference: true, Budget: 8, ProbeBudget: 1})
	result, err := f.Server.executeResearchTask(context.Background(), run, "MINIMAL_INFERENCE", taskInput{Selection: &f.Target})
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(result)
	if !strings.Contains(string(raw), authenticitySuiteVersion) || !strings.Contains(string(raw), "UNPROVEN") {
		t.Fatal("research did not call shared authenticity suite")
	}
	var source string
	if err = f.Server.Store.DB.QueryRow("SELECT source_url FROM research_evidence WHERE source_type='SCOPED_INFERENCE'").Scan(&source); err != nil {
		t.Fatal(err)
	}
	if source != bound {
		t.Fatalf("evidence borrowed Provider primary origin: got %s want %s", source, bound)
	}
	if f.Calls.Load() != calls {
		t.Fatal("reuse or authenticity analysis sent HTTP")
	}
}
