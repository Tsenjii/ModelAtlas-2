package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestBrowserProductionWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	encoded := testPNG(t)
	var mediaCalls atomic.Int32
	var blockedMediaStarted atomic.Bool
	var requestMu sync.Mutex
	requests := map[string]int{}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestMu.Lock()
		requests[r.URL.Path]++
		requestMu.Unlock()
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25"}}`))
		case "/v1/models":
			w.Write([]byte(`{"data":[{"id":"m"},{"id":"catalog-only"}]}`))
		case "/v1/chat/completions":
			var body struct {
				Messages []map[string]any `json:"messages"`
				Tools    []ToolDefinition `json:"tools"`
			}
			json.NewDecoder(r.Body).Decode(&body)
			if len(body.Tools) > 0 {
				w.Header().Set("Content-Type", "text/event-stream")
				if body.Messages[len(body.Messages)-1]["role"] == "tool" {
					w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Tool continued\"}}]}\n\n"))
				} else {
					w.Write([]byte("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"browser-call\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]}}]}\n\n"))
				}
				w.Write([]byte("data: [DONE]\n\n"))
				return
			}
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Browser reply\"}}]}\n\n"))
			w.(http.Flusher).Flush()
			time.Sleep(50 * time.Millisecond)
			w.Write([]byte("data: [DONE]\n\n"))
		case "/v1/audio/speech":
			mediaCalls.Add(1)
			w.Header().Set("Content-Type", "audio/wav")
			w.Write(validTestWAV())
		case "/v1/embeddings":
			mediaCalls.Add(1)
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"index": 0, "embedding": []float64{0.2, 0.4}}}})
		case "/v1/rerank":
			mediaCalls.Add(1)
			json.NewEncoder(w).Encode(map[string]any{"results": []any{map[string]any{"index": 0, "relevance_score": 0.8}}})
		case "/v1/images/generations":
			mediaCalls.Add(1)
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "m" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("browser media selected the wrong credential/model")
			}
			switch body["prompt"] {
			case "blocked image":
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(`{"data":[`))
				w.(http.Flusher).Flush()
				blockedMediaStarted.Store(true)
				select {
				case <-r.Context().Done():
				case <-time.After(5 * time.Second):
					t.Error("browser stop did not cancel upstream body")
				}
			case "invalid image":
				w.Write([]byte(`{"data":[{"b64_json":"not-an-image"}]}`))
			case "image URL":
				w.Write([]byte(`{"data":[{"url":"https://images.example.invalid/result.png"}]}`))
			case "rate limited":
				http.Error(w, "provider error body", 429)
			default:
				json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"b64_json": encoded}}})
			}
		case "/v1/images/edits", "/v1/audio/transcriptions":
			mediaCalls.Add(1)
			if r.ParseMultipartForm(5<<20) != nil {
				t.Error("browser media did not use multipart")
				return
			}
			defer r.MultipartForm.RemoveAll()
			field, wantType := "image", "image/png"
			if r.URL.Path == "/v1/audio/transcriptions" {
				field, wantType = "file", "audio/wav"
			}
			file, header, err := r.FormFile(field)
			if err != nil {
				t.Error("browser omitted local media")
				return
			}
			file.Close()
			if header.Header.Get("Content-Type") != wantType || r.FormValue("model") != "m" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("browser media scope/MIME mismatch")
			}
			if field == "file" {
				json.NewEncoder(w).Encode(map[string]string{"text": "Browser transcript\n第二行轉寫"})
			} else {
				json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"b64_json": encoded}}})
			}
		default:
			http.Error(w, "unavailable", 404)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Read-only fixture observation: never replaces a production API action.
		if r.URL.Path == "/__fixture/media-state" {
			json.NewEncoder(w).Encode(map[string]bool{"blocked": blockedMediaStarted.Load()})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/browser.cjs", app.URL, up.URL+"/v1")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_TEST_PNG="+encoded, "MODELATLAS_TEST_WAV="+base64.StdEncoding.EncodeToString(validTestWAV()))
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("browser workflow failed: %v\n%s", err, output)
	}
	if mediaCalls.Load() != 10 {
		requestMu.Lock()
		defer requestMu.Unlock()
		t.Fatalf("browser media flow made %d upstream calls, expected 10; paths=%v; browser=%s", mediaCalls.Load(), requests, output)
	}
	t.Log(string(output))
}

func TestBrowserResearchWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Error("public source received a credential")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","rpd":100,"tpd":1000000,"taiwan":"YES","recurring":"DAILY_RESET","free":true,"access_method":"INFERENCE_READY"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = mappedFixture(up)
	s.PublicClient = mappedFixture(up)
	seed(t, s, "https://provider.example/v1")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { cancel(); <-done }()
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/research.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	output, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("research browser: %v\n%s", e, output)
	}
	var n int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&n)
	if n != 0 {
		t.Fatal("Browser research implicitly added membership")
	}
	t.Log(string(output))
}

func TestBrowserAssistantWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s := testServer(t)
	var round atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("assistant request not JSON")
			return
		}
		raw, _ := json.Marshal(body)
		for _, secret := range []string{"top-secret-example-key", "browser-private-ref-key", s.Token} {
			if strings.Contains(string(raw), secret) {
				t.Error("Assistant Browser exposed secret to model")
			}
		}
		if round.Add(1) == 1 {
			selection := Selection{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "browser-added", Endpoint: "CHAT_COMPLETIONS"}
			assistantReply(w, "", functionCall("browser-add", "add_model", map[string]any{"selection": selection, "display_name": "Browser Added"}), functionCall("browser-notes", "preview_change", map[string]any{"kind": "NOTES", "provider_id": "p", "patch": map[string]any{"notes": "Browser assistant note", "tags": []string{"browser"}}}))
		} else {
			assistantReply(w, "已準備模型和備註，請檢查變更。")
		}
	}))
	defer up.Close()
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	configureAssistant(t, s, v)
	// The Browser must configure the selection through the real UI itself.
	s.Store.DB.Exec("DELETE FROM settings WHERE key='assistant_selection'")
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/assistant.cjs", app.URL, up.URL+"/v1")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Assistant Browser: %v\n%s", e, out)
	}
	if round.Load() != 2 {
		t.Fatal("Browser agent did not perform native tool round trip")
	}
	var members int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if members != 2 {
		t.Fatal("Browser did not confirm real membership")
	}
	var notes string
	s.Store.DB.QueryRow("SELECT notes FROM entity_notes WHERE subject_type='PROVIDER' AND subject_id='p'").Scan(&notes)
	if notes != "" {
		t.Fatal("Browser Undo did not restore notes")
	}
	t.Log(string(out))
}

func TestBrowserKnowledgeWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/source":
			w.Write([]byte(`{"data":[{"model_id":"m","rpd":100,"tpd":1000000,"taiwan":"YES","recurring":"DAILY_RESET","free":true,"access_method":"INFERENCE_READY"}]}`))
		case "/metadata":
			if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("Browser connector used wrong credential")
			}
			w.Write([]byte(`{"data":[{"model":"m","group":"","quota":{"rpd":100,"tpd":1000000}}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = mappedFixture(up)
	s.PublicClient = mappedFixture(up)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", ResearchSource{ID: "source", Name: "Fixture source", Kind: "JSON", URL: "https://provider.example/source", Language: "en", Enabled: true, Config: SourceConfig{ProviderID: "p", Official: true, ItemsPointer: "/data"}}), 200)
	researchRunID(t, s, ResearchOptions{SourceIDs: []string{"source"}, Budget: 2})
	s.runResearchPending(context.Background())
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/knowledge.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Knowledge Browser: %v\n%s", e, out)
	}
	var active int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM connector_active").Scan(&active)
	if active != 0 {
		t.Fatal("Browser connector Undo did not restore activation")
	}
	t.Log(string(out))
}

func TestBrowserManagementWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	for _, id := range []string{"unknown-model", "catalog-only"} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: id, DisplayName: id}), 201)
	}
	other := v
	other.ModelID = "unknown-model"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", other), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: "p", ModelID: "m", CanonicalID: "m", Aliases: []string{"Synthetic Echo"}, Category: "CHAT_REASONING", ContextTokens: new(int64(16000))}), 200)
	zero := "0"
	_ = s.observe(context.Background(), v, "PRICING", "MANUAL", "SUCCESS", map[string]any{"price": PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true, Unit: "USD/1K tokens"}})
	_, e := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,details_json,created_at) VALUES('sample','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS','{\"tps\":20}',?)", now())
	if e != nil {
		t.Fatal(e)
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/management.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Management Browser: %v\n%s", e, out)
	}
	if !loadSetting(s, context.Background(), "background", BackgroundSettings{}).Enabled {
		t.Fatal("Browser settings did not reach scheduler")
	}
	var notes string
	s.Store.DB.QueryRow("SELECT notes FROM entity_notes WHERE subject_id=?", scopeKey(v)).Scan(&notes)
	if notes != "" {
		t.Fatal("Browser Undo did not restore notes")
	}
	t.Log(string(out))
}

func TestBrowserOriginWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("original A key relocated")
		}
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Origin browser reply\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = mappedFixture(up)
	v := seed(t, s, "https://a.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/origins.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Origin Browser: %v\n%s", e, out)
	}
	p, e := s.provider(context.Background(), "p")
	if e != nil || p.BaseURL != "https://a.example/v1" {
		t.Fatal("Browser Undo did not restore primary URL")
	}
	t.Log(string(out))
}

func TestBrowserProtocolAndSpeechWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in Browser CI")
	}
	parallelBrowserFlow(t)
	var voices, customCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("Browser protocol credential mismatch")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("protocol request not JSON")
			return
		}
		if r.URL.Path == "/v1/models/m:generateContent" {
			voices.Add(1)
			config := object(body["generationConfig"])
			speech := object(config["speechConfig"])
			if voice := object(speech["voiceConfig"]); voice["prebuiltVoiceConfig"] != nil {
				json.NewEncoder(w).Encode(speechFixture(validTestWAV()[44:], "audio/L16;codec=pcm;rate=24000"))
			} else {
				json.NewEncoder(w).Encode(speechFixture(validTestWAV(), "audio/wav"))
			}
			return
		}
		if r.URL.Path == "/mapped" {
			customCalls.Add(1)
			if body["target"] != "m" || body["instruction"] != "Browser 繁中系統指示" || body["limit"] != float64(777) || body["streaming"] != false {
				t.Error("Browser settings not sent to exact custom endpoint")
			}
			json.NewEncoder(w).Encode(map[string]any{"result": map[string]any{"text": "自訂協定完成"}, "state": "stop", "usage": map[string]int{"in": 0, "out": 0}})
			return
		}
		http.NotFound(w, r)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	scope := seed(t, s, up.URL+"/v1")
	scope.Endpoint = "GEMINI_SPEECH"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	confirmCaps(t, s, scope, []string{"AUDIO_OUTPUT"})
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cfg, _ := json.Marshal(customForTest())
	cmd := exec.Command("node", "e2e/protocols.cjs", app.URL, up.URL+"/mapped")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_CUSTOM="+string(cfg))
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Protocol Browser: %v\n%s", e, out)
	}
	if voices.Load() != 3 || customCalls.Load() != 1 {
		t.Fatalf("missing real HTTP voices=%d custom=%d", voices.Load(), customCalls.Load())
	}
	t.Log(string(out))
}
