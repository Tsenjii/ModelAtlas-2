package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type anonymousLiveTransport struct {
	base  http.RoundTripper
	calls atomic.Int32
}

func (t *anonymousLiveTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Scheme != "https" || r.URL.Host != "api.kilo.ai" || r.Header.Get("Authorization") != "" {
		return nil, fmt.Errorf("live fixture permits only anonymous Kilo requests")
	}
	if t.calls.Add(1) > 10 {
		return nil, fmt.Errorf("live fixture request budget exhausted")
	}
	return t.base.RoundTrip(r)
}

// Explicit opt-in only: synthetic app state and a fresh disposable SQLite DB.
// This tests the production engine against the real anonymous service. It does
// not establish recurring-free eligibility, Taiwan availability or model identity.
func TestLiveKiloAnonymousConversationToolsAndAssistant(t *testing.T) {
	if os.Getenv("MODELATLAS_LIVE_KILO_TEST") != "1" {
		t.Skip("explicit live Kilo integration opt-in")
	}
	s := testServer(t)
	transport := &anonymousLiveTransport{base: http.DefaultTransport}
	s.Client = &http.Client{Transport: transport, Timeout: 120 * time.Second}
	ctx, stop := context.WithTimeout(context.Background(), 7*time.Minute)
	defer stop()
	base := "https://api.kilo.ai/api/gateway"
	provider := Provider{ID: "kilo-live", Name: "Kilo live integration fixture", BaseURL: base, AuthMode: "NONE"}
	requireCode(t, req(t, s, "POST", "/api/v1/providers", provider), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "fixture-account", ProviderID: provider.ID, Label: "Disposable fixture"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "anonymous", ProviderID: provider.ID, AccountID: "fixture-account", Label: "Anonymous", Role: "INFERENCE"}), 201)
	root, _, e := s.getJSON(ctx, provider, ProviderConfig{Adapter: "GENERIC", AuthMode: "NONE"}, base+"/models", "", 4<<20)
	if e != nil {
		t.Fatal(e)
	}
	var catalog []map[string]json.RawMessage
	if json.Unmarshal(root["data"], &catalog) != nil {
		t.Fatal("invalid live catalog")
	}
	model := os.Getenv("MODELATLAS_KILO_MODEL")
	if model == "" {
		model = "nvidia/nemotron-3.5-lightning:free"
	}
	available := false
	for _, row := range catalog {
		if stringField(row, "id") != model {
			continue
		}
		var price map[string]json.RawMessage
		var supported []string
		json.Unmarshal(row["pricing"], &price)
		json.Unmarshal(row["supported_parameters"], &supported)
		input, output := scalePrice(price["prompt"], 1), scalePrice(price["completion"], 1)
		available = strings.HasSuffix(model, ":free") && input != nil && output != nil && *input == "0" && *output == "0" && oneOf("tools", supported...)
	}
	if !available {
		t.Fatal("chosen model is not currently listed as zero-priced :free with tools; explicitly select another live model")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: provider.ID, ID: model, DisplayName: model}), 201)
	v := Selection{ProviderID: provider.ID, AccountID: "fixture-account", CredentialID: "anonymous", ModelID: model, Endpoint: "CHAT_COMPLETIONS"}
	configureAssistant(t, s, v)
	session, e := s.createTestSession(ctx, v)
	if e != nil {
		t.Fatal(e)
	}
	chat, _ := s.execute(ctx, inferRequest{SessionID: session, Prompt: "Reply exactly KILO_LIVE_OK in your final answer. No analysis needed.", MaxTokens: 1024}, "LIVE_KILO_CHAT", func(string, any) error { return nil })
	if chat.Outcome != "SUCCESS" || !strings.Contains(chat.Text, "KILO_LIVE_OK") {
		t.Fatalf("real conversation: %s text=%q details=%v", chat.Outcome, excerpt(chat.Text, 200), chat.Details)
	}
	t.Logf("Anonymous live conversation: model=%s response_model=%v outcome=%s latency=%v text=%q", model, chat.Details["response_model_id"], chat.Outcome, chat.Details["latency_ms"], excerpt(chat.Text, 120))
	session, e = s.createTestSession(ctx, v)
	if e != nil {
		t.Fatal(e)
	}
	stream := false
	tools := []ToolDefinition{{Type: "function", Function: ToolFunction{Name: "echo_value", Description: "Return the provided value. Use this before answering.", Parameters: toolSchema(map[string]any{"value": stringSchema()}, "value")}}}
	tool, _ := s.execute(ctx, inferRequest{SessionID: session, Prompt: "Call echo_value with value KILO_TOOL_OK. Do not answer before calling this function.", MaxTokens: 1024, Streaming: &stream, Tools: tools}, "LIVE_KILO_TOOLS", func(string, any) error { return nil })
	if tool.Outcome != "SUCCESS" || len(tool.ToolCalls) != 1 || tool.ToolCalls[0].Function.Name != "echo_value" {
		t.Fatalf("real tools: %s calls=%v text=%q", tool.Outcome, tool.ToolCalls, excerpt(tool.Text, 120))
	}
	var arguments struct {
		Value string `json:"value"`
	}
	if strictArguments(tool.ToolCalls[0].Function.Arguments, &arguments) != nil || arguments.Value != "KILO_TOOL_OK" {
		t.Fatal("live tool arguments do not match task")
	}
	continued, _ := s.execute(ctx, inferRequest{SessionID: session, MaxTokens: 1024, Streaming: &stream, ToolResults: []ToolResult{{CallID: tool.ToolCalls[0].ID, Content: `{"value":"KILO_TOOL_OK"}`}}}, "LIVE_KILO_TOOLS", func(string, any) error { return nil })
	if continued.Outcome != "SUCCESS" || !strings.Contains(continued.Text, "KILO_TOOL_OK") {
		t.Fatalf("real tool continuation: %s text=%q", continued.Outcome, excerpt(continued.Text, 160))
	}
	t.Logf("Anonymous native tool call and continuation: %s / %s", tool.Outcome, continued.Outcome)
	selection := v
	selection.ModelID = "live-workflow-fixture-model"
	raw, _ := json.Marshal(selection)
	message := "Use add_model exactly once to prepare membership for this synthetic workflow fixture. This is an app operation test, not a claim that Kilo offers this model. Display name is Live workflow fixture. Selection: " + string(raw) + ". Do not infer, sync, test, research or fetch anything. Do not confirm; stop after preparing the Review."
	response := req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: message})
	requireCode(t, response, 200)
	if !strings.Contains(response.Body.String(), "event: done") {
		t.Fatalf("live assistant failed: %s", response.Body.String())
	}
	var id string
	if s.Store.DB.QueryRow("SELECT id FROM action_ledger WHERE action='ADD_MY_MODEL' AND json_extract(after_json,'$.model_id')=? AND state='PENDING'", selection.ModelID).Scan(&id) != nil {
		t.Fatal("live model did not perform the requested app tool operation")
	}
	var before int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models WHERE model_id=?", selection.ModelID).Scan(&before)
	if before != 0 {
		t.Fatal("live model bypassed review")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/confirm", map[string]any{"id": id, "confirm": true}), 200)
	if !s.validateSelection(ctx, selection) {
		t.Fatal("live Assistant Review did not commit a real membership")
	}
	t.Logf("Anonymous live Assistant: actual pending action → shared confirmation → durable membership; HTTP requests=%d; synthetic fixture only.", transport.calls.Load())
}
