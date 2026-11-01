package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func authenticityNativeRequest(t *testing.T, s *Server, scope Selection, prompt string, stream bool) InferenceResult {
	t.Helper()
	id, err := s.createTestSession(context.Background(), scope)
	if err != nil {
		t.Fatal(err)
	}
	result, code := s.execute(context.Background(), inferRequest{SessionID: id, Prompt: prompt, MaxTokens: 32, Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if code != 0 || result.Outcome != "SUCCESS" {
		t.Fatalf("native request: code=%d outcome=%s", code, result.Outcome)
	}
	return result
}

func TestAuthenticityGeminiWireModeIsAComparisonCondition(t *testing.T) {
	var mu sync.Mutex
	var payloads []map[string]any
	var modes []bool
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		if json.NewDecoder(r.Body).Decode(&payload) != nil {
			t.Error("invalid native payload")
		}
		stream := strings.HasSuffix(r.URL.Path, ":streamGenerateContent")
		if !stream && !strings.HasSuffix(r.URL.Path, ":generateContent") || stream && r.URL.Query().Get("alt") != "sse" {
			t.Error("native request mode missing from wire URL")
		}
		mu.Lock()
		payloads = append(payloads, payload)
		modes = append(modes, stream)
		mu.Unlock()
		raw := `{"modelVersion":"m","candidates":[{"content":{"parts":[{"text":"Observed answer"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}`
		if stream {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, "data: %s\n\n", raw)
		} else {
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, raw)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	scope := seed(t, s, up.URL+"/v1")
	scope.Endpoint = "GEMINI_NATIVE"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	for _, stream := range []bool{false, true} {
		authenticityNativeRequest(t, s, scope, "Same prompt and output limit", stream)
	}
	report := authenticityAnalyze(t, s, scope, "native-modes")
	if report.Target.Attested != 2 || report.Target.Usable != 2 || len(report.Target.Samples) != 2 {
		t.Fatalf("native requests lost local proof: %+v", report.Target)
	}
	mu.Lock()
	defer mu.Unlock()
	if !reflect.DeepEqual(modes, []bool{false, true}) || !reflect.DeepEqual(payloads[0], payloads[1]) {
		t.Fatal("fixture did not exercise identical JSON with distinct wire modes")
	}
	a, b := report.Target.Samples[0].Features, report.Target.Samples[1].Features
	if a.ConditionHash == "" || b.ConditionHash == "" || a.ConditionHash == b.ConditionHash {
		t.Fatal("streaming and nonstreaming Gemini traffic borrowed the same comparison condition")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM telemetry")
}

func TestAuthenticityEffectiveAnthropicVersionIsAComparisonCondition(t *testing.T) {
	var mu sync.Mutex
	var versions []string
	var payloads []map[string]any
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		if json.NewDecoder(r.Body).Decode(&payload) != nil {
			t.Error("invalid native payload")
		}
		mu.Lock()
		versions = append(versions, r.Header.Get("anthropic-version"))
		payloads = append(payloads, payload)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"model":"m","content":[{"type":"text","text":"Observed answer"}],"stop_reason":"end_turn","usage":{"input_tokens":4,"output_tokens":2}}`)
	})
	target, reference := httptest.NewTLSServer(handler), httptest.NewTLSServer(handler)
	defer target.Close()
	defer reference.Close()
	s := testServer(t)
	s.Client = target.Client()
	v := seed(t, s, target.URL+"/v1")
	v.Endpoint = "ANTHROPIC_MESSAGES"
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "reference", Name: "Reference", BaseURL: reference.URL + "/v1", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "reference", Label: "A"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "reference", AccountID: "a", Label: "C", Secret: "synthetic-reference-key"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "reference", ID: "m", DisplayName: "M"}), 201)
	refScope := v
	refScope.ProviderID = "reference"
	for _, scope := range []Selection{v, refScope} {
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	}
	// The adapter overrides configured versions. Compare the effective header,
	// so the same actual version still matches despite a different config value.
	for _, scope := range []Selection{v, refScope} {
		adapter, version := "GENERIC", "2023-06-01"
		if scope == refScope {
			adapter, version = "ANTHROPIC", "2023-01-01"
		}
		requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": scope.ProviderID, "config": ProviderConfig{Adapter: adapter, AuthMode: "BEARER", Headers: map[string]string{"anthropic-version": version}}}), 200)
	}
	prompts := []string{"Short observation", strings.Repeat("Long context. ", 400), "Another observation"}
	populate := func(scope Selection) {
		for _, prompt := range prompts {
			authenticityNativeRequest(t, s, scope, prompt, false)
		}
	}
	populate(v)
	populate(refScope)
	f := &authenticityFixture{Server: s, Target: v, Reference: refScope}
	matching := authenticityPreview(t, f, "effective-same-version")
	if matching.Comparison.MatchedSamples != 3 || matching.Comparison.Status != "CONSISTENT_OBSERVATIONS" {
		t.Fatal("identical effective versions stopped matching")
	}
	// Same JSON, but now a genuinely different header reaches the provider.
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": v.ProviderID, "config": ProviderConfig{Adapter: "GENERIC", AuthMode: "BEARER", Headers: map[string]string{"anthropic-version": "2023-01-01"}}}), 200)
	populate(v)
	different := authenticityPreview(t, f, "effective-different-version")
	if different.Comparison.MatchedSamples != 0 || different.Comparison.Status != "INSUFFICIENT" || different.Comparison.Identity != "UNPROVEN" {
		t.Fatalf("different wire API versions were compared: matched=%d status=%s", different.Comparison.MatchedSamples, different.Comparison.Status)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(versions) != 9 {
		t.Fatalf("local comparison sent extra traffic: %d", len(versions))
	}
	for i, version := range versions {
		want := "2023-06-01"
		if i >= 6 {
			want = "2023-01-01"
		}
		if version != want {
			t.Fatalf("request %d did not carry the effective fixture version", i)
		}
		if !reflect.DeepEqual(payloads[i], payloads[i%3]) {
			t.Fatalf("request %d changed JSON instead of only the wire API version", i)
		}
	}
}

func TestAuthenticityOlderPolicyKeepsHistoryWithoutComparisonAuthority(t *testing.T) {
	f := newAuthenticityFixture(t)
	f.populate(t, f.Target)
	f.populate(t, f.Reference)
	ref := authenticityPreview(t, f, "policy-reference")
	requireCode(t, req(t, f.Server, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 200)
	before := authenticityAnalyze(t, f.Server, f.Target, "policy-report")
	if before.Status != "CONSISTENT_OBSERVATIONS" {
		t.Fatal("fixture did not establish a current reference")
	}
	s := f.Server
	// Restore a persisted prior-policy snapshot. Neither history nor telemetry
	// should be rewritten to grant it the newer comparison policy's authority.
	for _, statement := range []string{
		"UPDATE authenticity_local_samples SET features_json=replace(features_json,'local-request-v2','local-request-v1')",
		"UPDATE authenticity_reference_versions SET definition_json=replace(replace(definition_json,'traffic-fingerprint-v2','traffic-fingerprint-v1'),'local-request-v2','local-request-v1')",
		"UPDATE authenticity_reports SET report_json=replace(replace(report_json,'traffic-fingerprint-v2','traffic-fingerprint-v1'),'local-request-v2','local-request-v1')",
	} {
		if _, err := s.Store.DB.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	var original string
	if err := s.Store.DB.QueryRow("SELECT report_json FROM authenticity_reports WHERE id='policy-report'").Scan(&original); err != nil {
		t.Fatal(err)
	}
	f.Server = reopenScheduleStore(t, s)
	s = f.Server
	current := authenticityAnalyze(t, s, f.Target, "new-policy-report")
	if current.Suite != "traffic-fingerprint-v2" || current.ReferenceState != "STALE" || current.Status != "INSUFFICIENT" || current.Target.Attested != 0 || current.Identity != "UNPROVEN" {
		t.Fatalf("older policy obtained new authority: suite=%s reference=%s attested=%d", current.Suite, current.ReferenceState, current.Target.Attested)
	}
	for _, sample := range current.Target.Samples {
		if sample.Proof != "CONDITIONS_CHANGED" || sample.Features != nil {
			t.Fatal("prior-policy capture was promoted to a current proof")
		}
	}
	r := req(t, s, "GET", "/api/v1/authenticity/reports?"+selectionURL(f.Target)+"&id=policy-report", nil)
	requireCode(t, r, 200)
	var saved []authenticityReport
	var expected authenticityReport
	if json.Unmarshal(r.Body.Bytes(), &saved) != nil || json.Unmarshal([]byte(original), &expected) != nil || len(saved) != 1 || !reflect.DeepEqual(saved[0], expected) {
		t.Fatal("prior immutable report is no longer readable")
	}
	replayed := req(t, s, "POST", "/api/v1/authenticity/analyze", authenticityAnalysisRequest{"policy-report", f.Target})
	requireCode(t, replayed, 200)
	var replay authenticityReport
	if json.Unmarshal(replayed.Body.Bytes(), &replay) != nil || !reflect.DeepEqual(replay, expected) {
		t.Fatal("idempotent replay replaced the original policy report")
	}
	var unchanged string
	if err := s.Store.DB.QueryRow("SELECT report_json FROM authenticity_reports WHERE id='policy-report'").Scan(&unchanged); err != nil || unchanged != original {
		t.Fatal("analysis rewrote the historical report")
	}
	actionID := authenticityReferenceAction(context.Background(), s.Store.DB, scopeKey(f.Target), ref.ID)
	if actionID == "" {
		t.Fatal("prior reference lost persistent Undo")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": actionID, "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/authenticity/references/activate", versionActivation{ID: ref.ID, Confirm: true}), 409)
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM authenticity_local_samples")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM authenticity_reference_versions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM authenticity_reports")
	if f.Calls.Load() != 6 {
		t.Fatal("policy upgrade sent implicit provider traffic")
	}
}
