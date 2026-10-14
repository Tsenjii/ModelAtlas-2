package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func newSession(t *testing.T, s *Server, v Selection) Session {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	res := req(t, s, "POST", "/api/v1/sessions", Session{Selection: v})
	requireCode(t, res, 201)
	var x Session
	json.Unmarshal(res.Body.Bytes(), &x)
	return x
}
func confirmCaps(t *testing.T, s *Server, v Selection, caps []string) {
	t.Helper()
	b, _ := json.Marshal(v)
	m := map[string]any{}
	json.Unmarshal(b, &m)
	m["capabilities"] = caps
	m["confirm"] = true
	requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", m), 200)
}
func TestMultimodalHistoryRevalidationAndNoRawPersistence(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var b struct {
			Messages []struct {
				Content json.RawMessage `json:"content"`
			} `json:"messages"`
		}
		json.NewDecoder(r.Body).Decode(&b)
		if !strings.Contains(string(b.Messages[0].Content), "image_url") {
			t.Error("image not wired")
		}
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	image := Attachment{Kind: "image", Filename: "a.png", MIME: "image/png", Data: base64.StdEncoding.EncodeToString([]byte{137, 80, 78, 71, 13, 10, 26, 10, 0})}
	payload := inferRequest{SessionID: session.ID, Prompt: "image", Attachments: []Attachment{image}}
	requireCode(t, req(t, s, "POST", "/api/v1/infer", payload), 422)
	if calls.Load() != 0 {
		t.Fatal("unconfirmed media reached HTTP")
	}
	confirmCaps(t, s, v, []string{"VISION"})
	requireCode(t, req(t, s, "POST", "/api/v1/infer", payload), 200)
	var raw string
	s.Store.DB.QueryRow("SELECT media_metadata FROM messages WHERE role='user'").Scan(&raw)
	if strings.Contains(raw, image.Data) {
		t.Fatal("raw media entered DB")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "follow-up"}), 200)
	confirmCaps(t, s, v, []string{})
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "revoked"}), 422)
	if calls.Load() != 2 {
		t.Fatal("revoked historical image sent")
	}
}
func TestCancelBlockedSocketExcludedFromUptime(t *testing.T) {
	entered := make(chan struct{})
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"))
		w.(http.Flusher).Flush()
		close(entered)
		<-r.Context().Done()
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "cancel"})
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("not started")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/cancel", map[string]string{"session_id": session.ID}), 200)
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("cancel did not close socket")
	}
	var outcome string
	var ttft any
	s.Store.DB.QueryRow("SELECT outcome,ttft_ms FROM telemetry").Scan(&outcome, &ttft)
	if outcome != "CANCELLED" || ttft != nil {
		t.Fatalf("wrong cancel: %s %v", outcome, ttft)
	}
	p := s.performance(context.Background(), v, 86400000)
	if p["samples"] != 0 || p["cancelled"] != 1 || p["success_rate"] != nil {
		t.Fatalf("cancel polluted uptime: %v", p)
	}
}
func TestCompleteHistoryZeroUsageAndNonStreamingTTFT(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Messages []any `json:"messages"`
			Stream   bool  `json:"stream"`
		}
		json.NewDecoder(r.Body).Decode(&b)
		if len(b.Messages) != 105 || b.Stream {
			t.Errorf("wrong history/stream %d %v", len(b.Messages), b.Stream)
		}
		w.Write([]byte(`{"choices":[{"message":{"content":"done"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	for i := 0; i < 104; i++ {
		role := "user"
		if i%2 == 1 {
			role = "assistant"
		}
		s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)", newID(), session.ID, role, "old", 1)
	}
	stream := false
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "latest", Streaming: &stream}), 200)
	var in, out int64
	var ttft any
	if err := s.Store.DB.QueryRow("SELECT input_tokens,output_tokens,ttft_ms FROM telemetry").Scan(&in, &out, &ttft); err != nil || in != 0 || out != 0 || ttft != nil {
		t.Fatalf("zero/TTFT incorrect: %v", err)
	}
}
func TestPDFAndAudioSignatureBoundaries(t *testing.T) {
	for _, a := range []Attachment{{Kind: "pdf", MIME: "application/pdf", Filename: "a.pdf", Data: base64.StdEncoding.EncodeToString([]byte("%PDF-1.7"))}, {Kind: "audio", MIME: "audio/wav", Filename: "a.wav", Data: base64.StdEncoding.EncodeToString([]byte("RIFF0000WAVE"))}, {Kind: "audio", MIME: "audio/mpeg", Filename: "a.mp3", Data: base64.StdEncoding.EncodeToString([]byte{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0})}} {
		if a.validate() != nil {
			t.Fatal("valid media rejected")
		}
		a.Data = base64.StdEncoding.EncodeToString([]byte("forged"))
		if a.validate() == nil {
			t.Fatal("forged media accepted")
		}
	}
}
func TestOnboardingReviewAtomicAndSecretIsolation(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/status" {
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25"}}`))
			return
		}
		w.Write([]byte(`{"data":[{"id":"m"},{"id":"other"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	res := req(t, s, "POST", "/api/v1/onboarding/preview", OnboardingInput{Mode: "QUICK", Provider: Provider{ID: "p", Name: "new", BaseURL: up.URL + "/v1"}, Account: Account{ID: "a"}, Credentials: []Credential{{ID: "c", Secret: "not-exported-secret"}}, ModelIDs: []string{"manual"}})
	requireCode(t, res, 201)
	if strings.Contains(res.Body.String(), "not-exported-secret") {
		t.Fatal("review leaked key")
	}
	var review OnboardingReview
	json.Unmarshal(res.Body.Bytes(), &review)
	if len(review.Models) != 3 || review.Provider.Config.Adapter != "NEW_API" {
		t.Fatal("review discovery incorrect")
	}
	var n int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM providers").Scan(&n)
	if n != 0 {
		t.Fatal("preview bypassed confirmation")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/onboarding/confirm", map[string]any{"id": review.ID, "confirm": true, "selected_model_ids": []string{"not-reviewed"}}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/onboarding/confirm", map[string]any{"id": review.ID, "confirm": true, "selected_model_ids": []string{"manual"}}), 201)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&n)
	if n != 1 {
		t.Fatal("catalog contaminated My Models")
	}
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history WHERE status='PENDING'").Scan(&n)
	if n != 1 {
		t.Fatal("no enrichment scheduled")
	}
	if strings.Contains(req(t, s, "GET", "/api/v1/backup", nil).Body.String(), "not-exported-secret") {
		t.Fatal("export leaked key")
	}
}
func TestLmspeedBudgetProvenanceAndLastGood(t *testing.T) {
	var calls int
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("x-api-key") != "lmspeed-example-key" || r.URL.Query().Get("page") != "1" {
			t.Error("contract mismatch")
		}
		w.Write([]byte(`{"data":{"models":[{"slug":"model-m","name":"M","stats":{"average_tokens_per_second":77}},{"slug":"lmspeed-example-key"}]}}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	n, err := s.lmspeedImport(context.Background(), up.URL, "lmspeed-example-key", "zh")
	if err != nil || n != 1 {
		t.Fatalf("import: %d %v", n, err)
	}
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&count)
	if count != 0 {
		t.Fatal("external entered local telemetry")
	}
	s.Store.DB.Exec("UPDATE request_budgets SET used=1000")
	if _, err = s.lmspeedImport(context.Background(), up.URL, "lmspeed-example-key", "zh"); err == nil || calls != 1 {
		t.Fatal("budget bypassed")
	}
	if len(s.externalFor(context.Background(), "m")) != 1 {
		t.Fatal("last good lost")
	}
}
func TestCSRFAuthenticationOriginBoundary(t *testing.T) {
	s := testServer(t)
	r := httptest.NewRequest("POST", "http://atlas.local/api/v1/accounts", strings.NewReader(`{}`))
	r.Header.Set("Authorization", "Bearer "+s.Token)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", "https://evil.example")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	requireCode(t, w, 403)
}
func TestCustomFieldsVaultAndCredentialRollback(t *testing.T) {
	var calls int
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("X-Secret") != "header-extra-secret" || r.URL.Query().Get("ticket") != "query-extra-secret" || r.Header.Get("Authorization") != "" {
			t.Error("wrong custom auth")
		}
		w.Write([]byte(`{"data":[{"id":"m"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "p", Name: "Custom", BaseURL: up.URL + "/v1", AuthMode: "CUSTOM_HEADER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "p"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "a", AuthFields: []AuthField{{Name: "X-Secret", Secret: "header-extra-secret"}, {Name: "ticket", Query: true, Secret: "query-extra-secret"}}}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", syncRequest{ProviderID: "p", AccountID: "a", CredentialID: "c"}), 200)
	if calls != 1 {
		t.Fatal("no actual custom auth HTTP")
	}
	var raw string
	s.Store.DB.QueryRow("SELECT fields_json FROM credential_auth_fields").Scan(&raw)
	if strings.Contains(raw, "extra-secret") {
		t.Fatal("custom secret was persisted raw")
	}
	if strings.Contains(req(t, s, "GET", "/api/v1/credentials", nil).Body.String(), "extra-secret") {
		t.Fatal("custom secret exposed")
	}
	s2 := testServer(t)
	seed(t, s2, "https://provider.example/v1")
	payload := map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "replacement-secret", "confirm": true}
	requireCode(t, req(t, s2, "POST", "/api/v1/credentials/rotate", payload), 200)
	delete(payload, "secret")
	payload["version"] = 1
	requireCode(t, req(t, s2, "POST", "/api/v1/credentials/rotate", payload), 200)
	_, key, err := s2.endpointAuth(context.Background(), "p", "a", "c")
	if err != nil || key != "top-secret-example-key" {
		t.Fatal("credential rollback failed")
	}
}
func TestPricingExactArithmeticAndParserBounds(t *testing.T) {
	n, err := evaluateExpression("(tokens / 1000) * coeff + 0.1", map[string]string{"tokens": "2000000", "coeff": "3"})
	if err != nil || *exactString(n) != "6000.1" {
		t.Fatalf("exact expression %v %v", n, err)
	}
	for _, expression := range []string{"1/0", "fetch(secret)", strings.Repeat("(", 33) + "1" + strings.Repeat(")", 33)} {
		if _, err := evaluateExpression(expression, nil); err == nil {
			t.Fatal("unsafe/unbounded expression accepted")
		}
	}
	zero, nonzero := "0.00", "1.2"
	if priceState(PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true}) != "FREE_NOW" || priceState(PriceLegs{Input: &zero, Output: &zero}) != "UNKNOWN" || priceState(PriceLegs{Input: &zero, Output: &zero, Fixed: &nonzero}) != "PARTIALLY_FREE" {
		t.Fatal("Unknown/free price rules broken")
	}
}
func TestNewApiExactPricingAndMalformedPerfNeverPanics(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000}}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[{"model_name":"m","quota_type":0,"model_ratio":0.5,"completion_ratio":2,"enable_groups":["vip"],"supported_endpoint_types":["openai"]}],"group_ratio":{"vip":0.2},"usable_group":["vip"]}`))
		case "/api/perf-metrics":
			w.Write([]byte(`{"success":true,"data":{"wrong":"schema"}}`))
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Role: "ACCOUNT_PAT", Secret: "pat-example-secret"}), 201)
	q := NewApiQuery{ProviderID: "p", AccountID: "a", CredentialID: "pat", ModelID: "m"}
	requireCode(t, req(t, s, "POST", "/api/v1/newapi/pricing", q), 200)
	obs := s.observations(context.Background(), Selection{ProviderID: "p", AccountID: "a", ModelID: "m", Group: "vip", Endpoint: "CHAT_COMPLETIONS"})
	if len(obs) != 1 {
		t.Fatal("group scoped price not persisted")
	}
	b, _ := json.Marshal(obs[0].Facts)
	if !strings.Contains(string(b), `"input":"0.0002"`) || !strings.Contains(string(b), `"output":"0.0004"`) {
		t.Fatalf("inexact price %s", b)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/newapi/performance", q), 502)
}
func TestQueuedEnrichmentHasRealProductionHTTPCaller(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Write([]byte(`{"data":[{"id":"m"}]}`)) }))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	s.runPending(context.Background())
	if calls.Load() != 1 {
		t.Fatal("enrichment did not call provider")
	}
	var status string
	s.Store.DB.QueryRow("SELECT status FROM sync_history").Scan(&status)
	if status != "SUCCESS" {
		t.Fatal("queued sync did not finish")
	}
}
func TestMetadataSearchLifecycleAndHistoryScopes(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	newSession(t, s, v)
	requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: "p", ModelID: "m", CanonicalID: "canonical", Aliases: []string{"alias"}, Category: "CODING", Capabilities: []string{"CODING"}, Modalities: []string{"TEXT_INPUT", "TEXT_OUTPUT"}, Endpoints: []string{"CHAT_COMPLETIONS"}}), 200)
	result := req(t, s, "GET", "/api/v1/models?q=alias&category=CODING", nil)
	requireCode(t, result, 200)
	var models []Model
	json.Unmarshal(result.Body.Bytes(), &models)
	if len(models) != 1 {
		t.Fatal("alias/category search failed")
	}
	b, _ := json.Marshal(v)
	m := map[string]any{}
	json.Unmarshal(b, &m)
	m["state"] = "HIDDEN"
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", m), 200)
	if s.validateSelection(context.Background(), v) {
		t.Fatal("hidden offering remained active")
	}
	if req(t, s, "GET", "/api/v1/sessions", nil).Body.String() == "[]\n" {
		t.Fatal("hiding erased history")
	}
}
func TestSafeBackupRoundTripDisablesCredentialsAndIsAtomic(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	newSession(t, s, v)
	b := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, b, 200)
	var backup BackupDocument
	if err := json.Unmarshal(b.Body.Bytes(), &backup); err != nil {
		t.Fatal(err)
	}
	other := testServer(t)
	requireCode(t, req(t, other, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 201)
	if other.validateSelection(context.Background(), v) {
		t.Fatal("restored key enabled without reentry")
	}
	var enabled int
	var enc string
	if other.Store.DB.QueryRow("SELECT enabled,encrypted_key FROM credentials").Scan(&enabled, &enc) != nil || enabled != 0 || enc != "" {
		t.Fatal("restore invented secret")
	}
	requireCode(t, req(t, other, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 409)
	var providers int
	other.Store.DB.QueryRow("SELECT COUNT(*) FROM providers").Scan(&providers)
	if providers != 1 {
		t.Fatal("conflicting backup partially wrote")
	}
}
func TestStartupInterruptRecoveryAndSingleWriterLock(t *testing.T) {
	s := testServer(t)
	s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES('inflight','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','RUNNING',1)")
	var path string
	if err := s.Store.DB.QueryRow("SELECT file FROM pragma_database_list WHERE name='main'").Scan(&path); err != nil {
		t.Fatal(err)
	}
	if other, err := OpenStore(path); err == nil {
		other.Close()
		t.Fatal("second process/store accepted same DB")
	}
	s.Store.Close()
	db, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var outcome string
	db.DB.QueryRow("SELECT outcome FROM telemetry WHERE id='inflight'").Scan(&outcome)
	if outcome != "INTERRUPTED" {
		t.Fatal("crash recovery lost the request")
	}
}
func TestRegisteredMediaTransportsUseScopedTelemetry(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong media credential")
		}
		switch r.URL.Path {
		case "/v1/images/generations":
			w.Write([]byte(`{"data":[{"b64_json":"aW1hZ2U="}],"usage":{"input_tokens":0,"output_tokens":0}}`))
		case "/v1/audio/transcriptions":
			if err := r.ParseMultipartForm(5 << 20); err != nil {
				t.Error(err)
			}
			if r.FormValue("model") != "m" {
				t.Error("wrong audio model")
			}
			w.Write([]byte(`{"text":"transcribed text"}`))
		default:
			t.Error("media went to chat/unknown route")
			http.Error(w, "wrong", 404)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	base := seed(t, s, up.URL+"/v1")
	v := base
	v.Endpoint = "IMAGES_GENERATION"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"IMAGE_GENERATION"})
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: v, Prompt: "draw", Size: "1024x1024"}), 200)
	v.Endpoint = "AUDIO"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"AUDIO_INPUT"})
	clip := Attachment{Kind: "audio", Filename: "a.wav", MIME: "audio/wav", Data: base64.StdEncoding.EncodeToString([]byte("RIFF0000WAVE"))}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: v, Audio: &clip}), 200)
	if calls.Load() != 2 {
		t.Fatal("missing media caller")
	}
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE source='PLAYGROUND_MEDIA' AND outcome='SUCCESS' AND ttft_ms IS NULL").Scan(&count)
	if count != 2 {
		t.Fatal("media bypassed proper telemetry")
	}
}
func TestEmptyTelemetryUnknownAndLongLastGoodHistory(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	newSession(t, s, v)
	p := s.performance(context.Background(), v, 86400000)
	if p["observed_rpm"] != nil || p["observed_total_tpm"] != nil || p["success_rate"] != nil {
		t.Fatal("no traffic treated as observed zero")
	}
	s.observe(context.Background(), v, "PRICE", "OFFICIAL", "SUCCESS", map[string]any{"value": "last-good"})
	for i := 0; i < 205; i++ {
		s.observe(context.Background(), v, "PRICE", "OFFICIAL", "FAILED", map[string]any{})
	}
	result := req(t, s, "GET", "/api/v1/models/detail?provider_id=p&account_id=a&credential_id=c&model_id=m&group=&endpoint=CHAT_COMPLETIONS", nil)
	requireCode(t, result, 200)
	if !strings.Contains(result.Body.String(), "last-good") {
		t.Fatal("last-good hidden after repeated failures")
	}
	if quantile([]float64{10, 20}, .5) != 15 {
		t.Fatal("quantile diverged from Android")
	}
}
func TestStaleAssistantUndoCannotRemoveLaterMembership(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	p := req(t, s, "POST", "/api/v1/assistant/preview", AssistantAction{Action: "ADD_MY_MODEL", Selection: v})
	requireCode(t, p, 201)
	var action AssistantAction
	json.Unmarshal(p.Body.Bytes(), &action)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/confirm", map[string]any{"id": action.ID, "confirm": true}), 200)
	requireCode(t, req(t, s, "DELETE", "/api/v1/my-models", v), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/undo", map[string]any{"id": action.ID, "confirm": true}), 409)
	if !s.validateSelection(context.Background(), v) {
		t.Fatal("stale undo removed later membership")
	}
}
func TestTinyExactPricesNeverRoundToFree(t *testing.T) {
	tiny := decimal(json.Number("1e-40"))
	if tiny == nil {
		t.Fatal("valid provider decimal exponent lost")
	}
	rendered := exactString(tiny)
	if rendered == nil || *rendered == "0" {
		t.Fatal("small positive price rounded to zero")
	}
	zero := "0"
	state := priceState(PriceLegs{Input: rendered, Output: &zero, FixedAbsent: true})
	if state == "FREE_NOW" {
		t.Fatal("tiny nonzero cost became free")
	}
}
func TestMultipleEndpointProfilesKeepHistoryAndCapabilitiesSeparate(t *testing.T) {
	var paths []string
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	base := seed(t, s, up.URL+"/v1")
	for _, id := range []string{"endpoint-a", "endpoint-b"} {
		requireCode(t, req(t, s, "POST", "/api/v1/endpoints", EndpointProfile{ProviderID: "p", ID: id, Operation: "CHAT_COMPLETIONS", URL: up.URL + "/" + id}), 201)
		v := base
		v.Endpoint = id
		session := newSession(t, s, v)
		requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "profile"}), 200)
	}
	if len(paths) != 2 || paths[0] != "/endpoint-a" || paths[1] != "/endpoint-b" {
		t.Fatalf("profile routing wrong %v", paths)
	}
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(DISTINCT endpoint) FROM telemetry WHERE operation='CHAT_COMPLETIONS'").Scan(&count)
	if count != 2 {
		t.Fatal("physical endpoints mixed telemetry")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/endpoints", EndpointProfile{ProviderID: "p", ID: "evil", Operation: "CHAT_COMPLETIONS", URL: "https://other.example/chat"}), 422)
}
func TestEndpointPathEditCannotRewriteOldConversation(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("old conversation migrated to %s", r.URL.Path)
		}
		w.Write([]byte("data: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": ProviderConfig{Adapter: "GENERIC", AuthMode: "BEARER", Endpoints: map[string]string{"CHAT_COMPLETIONS": "/changed"}}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "bound old endpoint"}), 200)
}
