package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

type hostAwareFixture struct {
	inner    http.RoundTripper
	up       *httptest.Server
	mu       sync.Mutex
	requests []string
}

func (f *hostAwareFixture) RoundTrip(r *http.Request) (*http.Response, error) {
	f.mu.Lock()
	f.requests = append(f.requests, r.URL.Host+" "+r.URL.Path+" "+r.Header.Get("Authorization"))
	f.mu.Unlock()
	clone := r.Clone(r.Context())
	u := *r.URL
	target := f.up.URL
	u.Host = strings.TrimPrefix(target, "https://")
	clone.URL = &u
	clone.Host = r.URL.Host
	return f.inner.RoundTrip(clone)
}

func TestMultipleOriginsKeepKeysSessionsPATsAndUndoIsolated(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "chat") {
			w.Write([]byte(`{"model":"m","choices":[{"message":{"content":"origin reply"},"finish_reason":"stop"}]}`))
			return
		}
		w.Write([]byte(`{"data":[{"model":"m","group":"","rpd":100}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	transport := &hostAwareFixture{inner: up.Client().Transport, up: up}
	s.Client = &http.Client{Transport: transport}
	a := seed(t, s, "https://a.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", a), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "b-key", ProviderID: "p", AccountID: "a", Label: "B key", Secret: "synthetic-b-key", BaseURL: "https://b.example/v2"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/endpoints", EndpointProfile{ProviderID: "p", ID: "b-chat", Operation: "CHAT_COMPLETIONS", URL: "https://b.example/v2/chat"}), 201)
	b := a
	b.CredentialID = "b-key"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 422)
	b.Endpoint = "b-chat"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 201)
	cross := a
	cross.Endpoint = "b-chat"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", cross), 422)
	// A primary origin can change only after an explicit new credential binding exists.
	preview := req(t, s, "POST", "/api/v1/assistant/actions/preview", map[string]any{"kind": "PROVIDER", "provider_id": "p", "patch": map[string]any{"base_url": "https://b.example/v2"}})
	requireCode(t, preview, 201)
	var action map[string]any
	_ = json.Unmarshal(preview.Body.Bytes(), &action)
	id := action["id"].(string)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/confirm", map[string]any{"id": id, "confirm": true}), 200)
	stream := false
	for _, selection := range []Selection{a, b} {
		session, e := s.createTestSession(context.Background(), selection)
		if e != nil {
			t.Fatal(e)
		}
		result, _ := s.execute(context.Background(), inferRequest{SessionID: session, Prompt: "origin", Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
		if result.Outcome != "SUCCESS" {
			t.Fatal(result.Outcome)
		}
	}
	transport.mu.Lock()
	requests := strings.Join(transport.requests, "\n")
	transport.mu.Unlock()
	if !strings.Contains(requests, "a.example /v1/chat/completions Bearer top-secret-example-key") || !strings.Contains(requests, "b.example /v2/chat Bearer synthetic-b-key") || strings.Contains(requests, "b.example /v2/chat Bearer top-secret") {
		t.Fatalf("wrong origin requests: %s", requests)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/undo", map[string]any{"id": id, "confirm": true}), 200)
	p, _, e := s.endpointAuth(context.Background(), "p", "a", "b-key")
	if e != nil || p.BaseURL != "https://b.example/v2" {
		t.Fatal("Undo relocated B credential")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/preview", map[string]any{"kind": "PROVIDER", "provider_id": "p", "patch": map[string]any{"base_url": "https://unbound.example"}}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat-a", ProviderID: "p", AccountID: "a", Label: "PAT A", Secret: "synthetic-a-pat", Role: "ACCOUNT_PAT"}), 201)
	cfg := ConnectorConfig{Kind: "QUOTA", Path: "/metadata", Role: "ACCOUNT_PAT", PATID: "pat-a", Scope: "MODEL_KEY_GROUP", Root: "/data", Fields: map[string]string{"model_id": "/model", "group": "/group", "rpd": "/rpd"}, Unit: "REQUESTS"}
	before := len(transport.requests)
	if _, e := s.connectorRows(context.Background(), cfg, b); e == nil {
		t.Fatal("A PAT borrowed for B origin")
	}
	if len(transport.requests) != before {
		t.Fatal("foreign PAT was sent before validation")
	}
	backupResponse := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backupResponse, 200)
	var backup BackupDocument
	_ = json.Unmarshal(backupResponse.Body.Bytes(), &backup)
	restored := testServer(t)
	requireCode(t, req(t, restored, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 201)
	bound, e := boundProvider(context.Background(), restored.Store.DB, "p", "a", "b-key")
	if e != nil || bound.BaseURL != "https://b.example/v2" {
		t.Fatal("backup lost immutable B origin")
	}
}

func TestAssistantSafeReferenceCanPrepareReviewedSecondaryCredential(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://a.example/v1")
	reference := req(t, s, "POST", "/api/v1/assistant/secrets", map[string]any{"base_url": "https://b.example/v2", "label": "B key", "role": "INFERENCE", "secret": "synthetic-ref-b"})
	requireCode(t, reference, 201)
	var ref map[string]any
	_ = json.Unmarshal(reference.Body.Bytes(), &ref)
	action := req(t, s, "POST", "/api/v1/assistant/actions/preview", map[string]any{"kind": "CREDENTIAL_ADD", "provider_id": "p", "account_id": "a", "patch": map[string]any{"safe_secret_ref": ref["id"]}})
	requireCode(t, action, 201)
	var change map[string]any
	_ = json.Unmarshal(action.Body.Bytes(), &change)
	id := change["id"].(string)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/confirm", map[string]any{"id": id, "confirm": true}), 200)
	var credentialID string
	s.Store.DB.QueryRow("SELECT c.id FROM credentials c JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id WHERE o.base_url='https://b.example/v2'").Scan(&credentialID)
	bound, key, e := s.endpointAuth(context.Background(), "p", "a", credentialID)
	if e != nil || bound.BaseURL != "https://b.example/v2" || key != "synthetic-ref-b" {
		t.Fatal("shared Review did not bind B safely")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/preview", map[string]any{"kind": "CREDENTIAL_ROTATE", "provider_id": "p", "account_id": "a", "credential_id": "c", "patch": map[string]any{"safe_secret_ref": ref["id"]}}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/undo", map[string]any{"id": id, "confirm": true}), 200)
	if _, _, e = s.endpointAuth(context.Background(), "p", "a", credentialID); e == nil {
		t.Fatal("Undo did not disable secondary credential")
	}
}

func TestAssistantPublicSourceAndAnonymousOnboardingBoundary(t *testing.T) {
	var calls int
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("Authorization") != "" {
			t.Error("anonymous onboarding leaked credentials")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"id":"anonymous-model"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	seed(t, s, "https://a.example/v1")
	s.PublicClient = mappedFixture(up)
	s.Client = &http.Client{Transport: originsRoundTripFunc(func(*http.Request) (*http.Response, error) {
		t.Error("Agent public onboarding used privileged client")
		return nil, fmt.Errorf("privileged client forbidden")
	})}
	call := func(name string, args any) (any, error) {
		raw, _ := json.Marshal(args)
		return s.assistantTool(context.Background(), "fixture", ToolCall{ID: "call", Function: ToolFunction{Name: name, Arguments: string(raw)}}, &assistantBudget{})
	}
	value, e := call("save_research_source", map[string]any{"name": "Public docs", "url": "https://b.example/docs", "language": "en", "kind": "HTML"})
	if e != nil {
		t.Fatal(e)
	}
	source := value.(map[string]any)
	if source["id"] == nil {
		t.Fatal("source not saved")
	}
	value, e = call("onboard_provider", map[string]any{"name": "Anonymous B", "base_url": "https://b.example/v1", "model_ids": []string{"anonymous-model"}, "config": ProviderConfig{Adapter: "GENERIC", AuthMode: "NONE"}})
	if e != nil {
		t.Fatal(e)
	}
	review := value.(map[string]any)
	if review["status"] != "REVIEW" || calls != 1 {
		t.Fatalf("anonymous shared Review did not use actual public catalog: %v calls=%d", review, calls)
	}
	var providers int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM providers").Scan(&providers)
	if providers != 1 {
		t.Fatal("onboarding bypassed human confirmation")
	}
	if _, e = call("onboard_provider", map[string]any{"name": "Private", "base_url": "https://127.0.0.1", "model_ids": []string{}}); e == nil {
		t.Fatal("public Agent attempted private anonymous origin")
	}
	if !validTools(assistantTools()) {
		t.Fatal("real static Agent tool registry rejected by shared engine")
	}
}

type originsRoundTripFunc func(*http.Request) (*http.Response, error)

func (f originsRoundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestReportedTokenGroupCannotBorrowAnotherGroupOrCredentialEpoch(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://a.example/v1")
	ctx := context.Background()
	raw := `{"group":"vip","group_known":true,"credential_version":0,"origin":"https://a.example/v1"}`
	_, e := s.Store.DB.Exec("INSERT INTO newapi_snapshots VALUES('group','p','a','c','INFERENCE','/api/usage/token/',?,'v1.0.0-rc.25','NEW_API_OFFICIAL',?)", raw, now())
	if e != nil {
		t.Fatal(e)
	}
	if s.groupBound(ctx, v) {
		t.Fatal("known actual token Group VIP borrowed default Group")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "synthetic-rotated-key", "confirm": true}), 200)
	if !s.groupBound(ctx, v) {
		t.Fatal("old material group incorrectly blocked newly rotated material")
	}
}
