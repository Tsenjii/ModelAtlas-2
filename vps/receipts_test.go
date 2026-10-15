package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

func TestRequestReceiptFreezesQuoteUsageQuotaAndActualCharge(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://openrouter.ai/api/v1")
	session := newSession(t, s, v)
	ctx := context.Background()
	legs := PriceLegs{Input: receiptString("0.002"), Output: receiptString("0.004"), Cache: receiptString("0.001"), FixedAbsent: true, Unit: "USD/1K tokens"}
	if e := s.observe(ctx, v, "CATALOG", "OPENROUTER_OFFICIAL", "SUCCESS", map[string]any{"origin": "https://openrouter.ai/api/v1", "price": legs}); e != nil {
		t.Fatal(e)
	}
	if e := s.observe(ctx, v, "QUOTA", "USER", "SUCCESS", map[string]any{"remaining": "0", "unit": "REQUESTS", "scope_kind": "CREDENTIAL"}); e != nil {
		t.Fatal(e)
	}
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong key")
		}
		newLegs := legs
		newLegs.Input = receiptString("100")
		_ = s.observe(ctx, v, "CATALOG", "OPENROUTER_OFFICIAL", "SUCCESS", map[string]any{"origin": "https://openrouter.ai/api/v1", "price": newLegs})
		_ = s.observe(ctx, v, "QUOTA", "USER", "SUCCESS", map[string]any{"remaining": "90", "unit": "REQUESTS", "scope_kind": "CREDENTIAL"})
		w.Header().Set("X-Ratelimit-Remaining-Requests", "0")
		w.Header().Set("Retry-After", "top-secret-example-key")
		w.Header().Set("X-Ratelimit-top-secret-example-key", "10")
		w.Write([]byte(`{"choices":[{"message":{"content":"receipt reply"}}],"usage":{"prompt_tokens":1000,"completion_tokens":500,"total_tokens":1500,"prompt_tokens_details":{"cached_tokens":200,"cache_write_tokens":0},"completion_tokens_details":{"reasoning_tokens":50},"cost":0.003}}`))
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	stream := false
	result, code := s.execute(ctx, inferRequest{SessionID: session.ID, Prompt: "cost", Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal(result, code)
	}
	r := result.Details["receipt"].(*RequestReceipt)
	if r.Cost["nominal_estimated"] == nil || r.Cost["nominal_estimated"].(*string) == nil || *r.Cost["nominal_estimated"].(*string) != "0.0038" || r.Cost["actual"] != "0.003" || r.PriceState != "UNKNOWN" || r.Cost["state"] != "PROVIDER_REPORTED" || r.NominalPriceState != "PAID" {
		t.Fatalf("wrong nominal/actual separation: %+v", r)
	}
	if r.QuotaBefore[0].Facts["remaining"] != "0" || r.QuotaAfter[0].Facts["remaining"] != "90" || r.ResponseQuota["x-ratelimit-remaining-requests"] != int64(0) || r.QuotaState != "RATE_LIMIT_ONLY" {
		t.Fatal("quota state or snapshot drift", r)
	}
	id := result.Details["sample_id"].(string)
	read := req(t, s, "GET", "/api/v1/telemetry/detail?id="+id, nil)
	requireCode(t, read, 200)
	if strings.Contains(read.Body.String(), "top-secret-example-key") || !strings.Contains(read.Body.String(), "0.0038") {
		t.Fatal("receipt secret or snapshot changed")
	}
	var messages []Message
	_ = json.Unmarshal(req(t, s, "GET", "/api/v1/messages?session_id="+session.ID, nil).Body.Bytes(), &messages)
	if len(messages) != 2 || messages[0].SampleID != id || messages[1].SampleID != id {
		t.Fatal("receipt not linked to actual history")
	}
	if calls.Load() != 1 {
		t.Fatal("receipt caused extra billing HTTP")
	}
	// A rotated key must not inherit the old material's quote/quota.
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "new-synthetic-key", "confirm": true}), 200)
	p, _, e := s.endpointAuth(ctx, "p", "a", "c")
	if e != nil {
		t.Fatal(e)
	}
	snapshot, e := s.newReceipt(ctx, v, p, now(), []string{s.Token})
	if e != nil || snapshot.Price != nil || len(snapshot.QuotaBefore) != 0 || snapshot.CredentialVersion != p.CredentialVersion || snapshot.CredentialVersion == 0 {
		t.Fatal("stale credential evidence borrowed", snapshot, e)
	}
}

func TestExplicitRetryRetainsOneUserMessageAndAllAttempts(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []chatMessage `json:"messages"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Messages) != 1 || body.Messages[0].Content != "retry me" {
			t.Error("retry duplicated/dropped actual user input")
		}
		if calls.Add(1) < 3 {
			http.Error(w, "limited", 429)
			return
		}
		w.Write([]byte(`{"choices":[{"message":{"content":"recovered"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}`))
	}))
	defer up.Close()
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	stream := false
	ctx := context.Background()
	result, _ := s.execute(ctx, inferRequest{SessionID: session.ID, Prompt: "retry me", Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if result.Outcome != "RATE_LIMITED" {
		t.Fatal(result)
	}
	first := result.Details["sample_id"].(string)
	result, _ = s.execute(ctx, inferRequest{SessionID: session.ID, RetryID: first, Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if result.Outcome != "RATE_LIMITED" {
		t.Fatal(result)
	}
	second := result.Details["sample_id"].(string)
	result, _ = s.execute(ctx, inferRequest{SessionID: session.ID, RetryID: second, Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if result.Outcome != "SUCCESS" {
		t.Fatal(result)
	}
	_, code := s.execute(ctx, inferRequest{SessionID: session.ID, RetryID: first, Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if code != 409 || calls.Load() != 3 {
		t.Fatal("stale retry sent HTTP")
	}
	var users, samples int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages WHERE role='user'").Scan(&users)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&samples)
	if users != 1 || samples != 3 {
		t.Fatal("retry destroyed audit or duplicated history")
	}
	exported := req(t, s, "GET", "/api/v1/telemetry/export?session_id="+session.ID, nil)
	requireCode(t, exported, 200)
	lines := strings.Split(strings.TrimSpace(exported.Body.String()), "\n")
	if len(lines) != 5 || !strings.Contains(lines[4], `"records":3`) || !strings.Contains(lines[4], `"complete":true`) {
		t.Fatal("complete request export missing")
	}
	if rows := req(t, s, "GET", "/api/v1/telemetry?session_id="+session.ID+"&outcome=RATE_LIMITED", nil); strings.Count(rows.Body.String(), `"outcome":"RATE_LIMITED"`) != 2 {
		t.Fatal("request filtering incomplete")
	}
}

func TestCostUnknownZeroCacheAndNativeUsageStayDistinct(t *testing.T) {
	if priceState(PriceLegs{Unit: "USD/request", Fixed: receiptString("1")}) != "PAID" {
		t.Fatal("fixed request fee classified partly free")
	}
	p := PriceLegs{Input: receiptString("0"), Output: receiptString("0"), FixedAbsent: true, Unit: "USD/1K tokens"}
	zero, one := int64(0), int64(1)
	amount, reason := estimateRequestCost(p, "CHAT_COMPLETIONS", &zero, &zero, map[string]any{})
	if amount == nil || *amount != "0" {
		t.Fatal(amount, reason)
	}
	if amount, _ := estimateRequestCost(p, "CHAT_COMPLETIONS", nil, &zero, map[string]any{}); amount != nil {
		t.Fatal("missing usage became zero")
	}
	p.Cache = receiptString("1")
	if amount, reason := estimateRequestCost(p, "CHAT_COMPLETIONS", &one, &zero, map[string]any{}); amount != nil || reason != "CACHE_USAGE_UNKNOWN" {
		t.Fatal("unknown cache invented", amount, reason)
	}
	if amount, reason := estimateRequestCost(p, "CHAT_COMPLETIONS", &one, &zero, map[string]any{"cache_read_tokens": int64(1)}); amount == nil || *amount != "0.001" {
		t.Fatal("free base with paid cache lost", amount, reason)
	}
	if amount, _ := estimateRequestCost(p, "ANTHROPIC_MESSAGES", &one, &zero, map[string]any{"cache_read_tokens": int64(1)}); amount != nil {
		t.Fatal("exclusive native input treated as inclusive")
	}
	if charge := reportedCharge(json.RawMessage(`0`), Provider{BaseURL: "https://openrouter.ai.evil.example/v1"}); charge["unit"] != "UNKNOWN" || charge["amount"] != "0" {
		t.Fatal("lookalike charge certified")
	}
}

func TestRawAttachmentSecretBlockedBeforeHTTPHistoryOrCache(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer up.Close()
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	confirmCaps(t, s, v, []string{"VISION"})
	png, e := base64.StdEncoding.DecodeString(testPNG(t))
	if e != nil {
		t.Fatal(e)
	}
	png = append(png, []byte("top-secret-example-key")...)
	a := Attachment{Kind: "image", MIME: "image/png", Filename: "private.png", Data: base64.StdEncoding.EncodeToString(png)}
	if a.validate() != nil {
		t.Fatal("valid PNG fixture required")
	}
	result, code := s.execute(context.Background(), inferRequest{SessionID: session.ID, Prompt: "image", Attachments: []Attachment{a}}, "PLAYGROUND", func(string, any) error { return nil })
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages").Scan(&count)
	if code != 422 || result.Outcome != "SECRET_IN_REQUEST_CONTENT" || calls.Load() != 0 || count != 0 || s.mediaBytes != 0 {
		t.Fatal("attachment secret escaped boundary", result, code)
	}
}

func TestPagedRequestExportBeyondUIWindow(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://provider.example/v1")
	tx, e := s.Store.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 501; i++ {
		_, e = tx.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',?)", fmt.Sprint(i), i)
		if e != nil {
			t.Fatal(e)
		}
	}
	if tx.Commit() != nil {
		t.Fatal("commit")
	}
	doc := req(t, s, "GET", "/api/v1/telemetry/export", nil)
	requireCode(t, doc, 200)
	lines := strings.Split(strings.TrimSpace(doc.Body.String()), "\n")
	if len(lines) != 503 || !strings.Contains(lines[502], `"records":501`) {
		t.Fatal("export inherited UI cap")
	}
}

func TestBrowserRequestReceiptsWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	s := testServer(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []chatMessage `json:"messages"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Messages) != 1 {
			t.Error("browser retry duplicated context")
		}
		w.Header().Set("X-Ratelimit-Remaining-Requests", "0")
		if calls.Add(1) == 1 {
			http.Error(w, "limited", 429)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"收據已保存\"}}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500,\"total_tokens\":1500,\"prompt_tokens_details\":{\"cached_tokens\":200,\"cache_write_tokens\":0},\"cost\":0.003}}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	v := seed(t, s, "https://openrouter.ai/api/v1")
	session := newSession(t, s, v)
	s.AutoSync = false
	legs := PriceLegs{Input: receiptString("0.002"), Output: receiptString("0.004"), Cache: receiptString("0.001"), FixedAbsent: true, Unit: "USD/1K tokens"}
	if e := s.observe(context.Background(), v, "CATALOG", "OPENROUTER_OFFICIAL", "SUCCESS", map[string]any{"origin": "https://openrouter.ai/api/v1", "price": legs}); e != nil {
		t.Fatal(e)
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/receipts.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_RECEIPT_SESSION="+session.ID)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("receipt browser: %v\n%s", e, out)
	}
	if calls.Load() != 2 {
		t.Fatalf("receipt inspection sent %d HTTP", calls.Load())
	}
	t.Log(string(out))
}

func receiptString(v string) *string { return &v }

type exportMutationWriter struct {
	*httptest.ResponseRecorder
	once   bool
	mutate func()
}

func (w *exportMutationWriter) Write(b []byte) (int, error) {
	if !w.once {
		w.once = true
		w.mutate()
	}
	return w.ResponseRecorder.Write(b)
}
func TestRequestExportFreezesSelectionDuringConcurrentUpdates(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://provider.example/v1")
	tx, e := s.Store.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 501; i++ {
		if _, e = tx.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',1)", fmt.Sprint(i)); e != nil {
			t.Fatal(e)
		}
	}
	if tx.Commit() != nil {
		t.Fatal("commit")
	}
	r := httptest.NewRequest("GET", "/api/v1/telemetry/export?outcome=SUCCESS", nil)
	r.Header.Set("Authorization", "Bearer "+s.Token)
	w := &exportMutationWriter{ResponseRecorder: httptest.NewRecorder(), mutate: func() {
		if _, e = s.Store.DB.Exec("UPDATE telemetry SET outcome='CANCELLED' WHERE id='300'"); e != nil {
			t.Fatal(e)
		}
		if _, e = s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES('new','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',1)"); e != nil {
			t.Fatal(e)
		}
	}}
	s.Handler().ServeHTTP(w, r)
	requireCode(t, w.ResponseRecorder, 200)
	rows := strings.Split(strings.TrimSpace(w.Body.String()), "\n")
	if len(rows) != 503 || !strings.Contains(rows[502], `"records":501`) {
		t.Fatal("concurrent update shifted export pages")
	}
	seen := map[string]bool{}
	for _, raw := range rows[1 : len(rows)-1] {
		var row map[string]any
		if json.Unmarshal([]byte(raw), &row) != nil {
			t.Fatal("invalid record")
		}
		id := row["id"].(string)
		if seen[id] || id == "new" {
			t.Fatal("duplicate or post-start record")
		}
		seen[id] = true
	}
	var temps int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sqlite_temp_master WHERE name LIKE 'request_export_%'").Scan(&temps)
	if temps != 0 {
		t.Fatal("export cursor cache retained")
	}
}
func TestUnavailableIntelligenceDoesNotBlockInference(t *testing.T) {
	s := testServer(t)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"choices":[{"message":{"content":"still usable"}}]}`))
	}))
	defer up.Close()
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	if _, e := s.Store.DB.Exec("INSERT INTO newapi_snapshots VALUES('broken','p','a','c','INFERENCE','/api/usage/token/','not JSON','v1.0.0-rc.25','NEW_API_OFFICIAL',?)", now()); e != nil {
		t.Fatal(e)
	}
	stream := false
	result, _ := s.execute(context.Background(), inferRequest{SessionID: session.ID, Prompt: "hello", Streaming: &stream}, "PLAYGROUND", func(string, any) error { return nil })
	if result.Outcome != "SUCCESS" || result.Details["receipt"].(*RequestReceipt).Cost["snapshot_before_status"] != "UNAVAILABLE" {
		t.Fatal("intelligence failure disabled a usable model", result)
	}
}

func TestNominalZeroDoesNotClaimKeyFreeAndExplicitConfirmationDoes(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	ctx := context.Background()
	legs := PriceLegs{Input: receiptString("0"), Output: receiptString("0"), FixedAbsent: true, Unit: "USD/1K tokens"}
	if e := s.observe(ctx, v, "CATALOG", "GENERIC_OFFICIAL", "SUCCESS", map[string]any{"origin": "https://provider.example/v1", "price": legs}); e != nil {
		t.Fatal(e)
	}
	var models []map[string]any
	_ = json.Unmarshal(req(t, s, "GET", "/api/v1/my-models", nil).Body.Bytes(), &models)
	if models[0]["price_state"] != "UNKNOWN" {
		t.Fatal("nominal zero became key free")
	}
	payload := map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "group": "", "endpoint": "CHAT_COMPLETIONS", "price": legs}
	requireCode(t, req(t, s, "POST", "/api/v1/models/facts", payload), 200)
	if s.offerSummary(ctx, v)["effective_price"] != nil {
		t.Fatal("unconfirmed manual quote became effective")
	}
	payload["confirmed_effective_price"] = true
	requireCode(t, req(t, s, "POST", "/api/v1/models/facts", payload), 200)
	_ = json.Unmarshal(req(t, s, "GET", "/api/v1/my-models", nil).Body.Bytes(), &models)
	if models[0]["price_state"] != "FREE_NOW" {
		t.Fatal("explicitly confirmed scoped price not connected")
	}
	cfg := ConnectorConfig{Kind: "PRICING", Path: "/prices", Role: "NONE", Scope: "MODEL_PROVIDER", Root: "/data", Fields: map[string]string{"model_id": "/id", "input": "/input"}, Unit: "USD/1K tokens", Effective: true}
	if safeConnector(cfg) {
		t.Fatal("anonymous/provider-wide quote accepted as key effective")
	}
	cfg.Role = "INFERENCE"
	cfg.Scope = "MODEL_KEY_GROUP"
	cfg.Fields["group"] = "/group"
	if !safeConnector(cfg) {
		t.Fatal("explicit scoped connector refused")
	}
	p, _, e := s.endpointAuth(ctx, "p", "a", "c")
	if e != nil {
		t.Fatal(e)
	}
	snapshot, e := s.newReceipt(ctx, v, p, now(), []string{s.Token})
	if e != nil || snapshot.PriceState != "FREE_NOW" {
		t.Fatal("confirmed receipt disconnected", snapshot, e)
	}
}
