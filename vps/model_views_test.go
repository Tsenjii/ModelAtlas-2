package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserModelViewsWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var calls, personalCalls, researchCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var body struct {
			Model string `json:"model"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || body.Model != "m" || r.URL.Path != "/v1/chat/completions" {
			t.Error("unexpected request from model views workflow")
		}
		secret := r.Header.Get("Authorization")
		if secret == "Bearer top-secret-example-key" {
			personalCalls.Add(1)
		}
		if secret == "Bearer synthetic-view-research" {
			researchCalls.Add(1)
		}
		if secret != "Bearer top-secret-example-key" && secret != "Bearer synthetic-view-research" {
			t.Error("model connection switched to the wrong credential")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Saved view reply\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	a := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas Reasoner"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: "p", ModelID: "m", CanonicalID: "atlas-reasoner", Category: "CHAT_REASONING"}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", a), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "endpoint": "CHAT_COMPLETIONS", "state": "ACTIVE", "watchlist": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "研究帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "研究 Key", Secret: "synthetic-view-research", Group: "research"}), 201)
	b := a
	b.AccountID, b.Group = "b", "research"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "b", "credential_id": "c", "model_id": "m", "group": "research", "endpoint": "CHAT_COMPLETIONS", "state": "ACTIVE", "favorite": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "q", Name: "Unknown Station", BaseURL: up.URL + "/other/v1", AuthMode: "NONE"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "q", Label: "另一帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "q", AccountID: "a", Label: "另一 Key", AuthMode: "NONE"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "q", ID: "other", DisplayName: "Unknown Reasoner"}), 201)
	n := Selection{ProviderID: "q", AccountID: "a", CredentialID: "c", ModelID: "other", Endpoint: "CHAT_COMPLETIONS"}
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", n), 201)
	if err := s.observe(context.Background(), n, "MODEL_PERFORMANCE", "NEW_API", "SUCCESS", map[string]any{"tps": 9999}); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"catalog-only", "hidden"} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: id, DisplayName: id}), 201)
	}
	h := a
	h.ModelID = "hidden"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", h), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "hidden", "endpoint": "CHAT_COMPLETIONS", "state": "HIDDEN"}), 200)
	for _, query := range []string{
		"INSERT INTO sessions VALUES('view-a','p','a','c','m','','CHAT_COMPLETIONS','個人保留對話',1)",
		"INSERT INTO sessions VALUES('view-b','p','b','c','m','research','CHAT_COMPLETIONS','研究保留對話',2)",
		"INSERT INTO messages(id,session_id,role,content,created_at) VALUES('view-msg-a','view-a','assistant','個人既有內容',1)",
		"INSERT INTO messages(id,session_id,role,content,created_at) VALUES('view-msg-b','view-b','assistant','研究既有內容',2)",
	} {
		if _, err := s.Store.DB.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,status_code,ttft_ms,latency_ms,details_json,created_at) VALUES('view-recent','p','b','c','m','research','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',200,100,1000,'{\"tps\":120}',?)", now()-1000); err != nil {
		t.Fatal(err)
	}
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__views_fixture/state" {
			var sessions, messages, members int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM sessions").Scan(&sessions)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
			jsonOK(w, 200, map[string]any{"calls": calls.Load(), "sessions": sessions, "messages": messages, "members": members})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/model-views.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("model views browser: %v\n%s", err, out)
	}
	if calls.Load() != 3 || personalCalls.Load() != 1 || researchCalls.Load() != 2 {
		t.Fatalf("views/navigation unexpectedly issued inference: %d", calls.Load())
	}
	t.Log(string(out))
}
