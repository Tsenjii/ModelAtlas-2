package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserCatalogExperienceWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var providerCalls atomic.Int32
	upstream := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		providerCalls.Add(1)
		http.Error(w, "catalog browsing must not call provider", 500)
	})
	a := httptest.NewTLSServer(upstream)
	defer a.Close()
	b := httptest.NewTLSServer(upstream)
	defer b.Close()
	s := testServer(t)
	s.Client = a.Client()
	v := seed(t, s, a.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "a", ID: "second", Label: "第二組 Key", Secret: "synthetic-b-key", Group: "研發", BaseURL: b.URL + "/v2", Role: "INFERENCE"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "a", ID: "disabled", Label: "已停用 Key", Secret: "synthetic-disabled", Role: "INFERENCE"}), 201)
	if _, err := s.Store.DB.Exec("UPDATE credentials SET enabled=0 WHERE id='disabled'"); err != nil {
		t.Fatal(err)
	}
	for _, p := range []Provider{{ID: "bare", Name: "尚未設定 Key", BaseURL: a.URL + "/bare", AuthMode: "BEARER"}, {ID: "off", Name: "停用連線", BaseURL: a.URL + "/off", AuthMode: "BEARER"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/providers", p), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: p.ID, ID: "m", DisplayName: p.Name}), 201)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ProviderID: "off", ID: "a", Label: "停用帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "off", AccountID: "a", ID: "c", Label: "停用 Key", Secret: "synthetic-off"}), 201)
	if _, err := s.Store.DB.Exec("UPDATE credentials SET enabled=0 WHERE provider_id='off'"); err != nil {
		t.Fatal(err)
	}
	for _, m := range []Model{{ProviderID: "p", ID: "m", DisplayName: "日常助手"}, {ProviderID: "p", ID: "other", DisplayName: "程式夥伴"}, {ProviderID: "p", ID: "archived", DisplayName: "封存模型"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", m), 201)
	}
	for i := 0; i < 103; i++ {
		id := fmt.Sprintf("bulk-%03d", i)
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: id, DisplayName: id}), 201)
	}
	for _, meta := range []ModelMetadata{{ProviderID: "p", ModelID: "m", CanonicalID: "common-chat", Aliases: []string{"AuroraChat"}, Category: "CHAT_REASONING", Capabilities: []string{"VISION"}}, {ProviderID: "p", ModelID: "other", CanonicalID: "code-family", Aliases: []string{"NebulaCoder"}, Category: "CODING", Vendor: "AtlasLab"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", meta), 200)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	archived := v
	archived.ModelID = "archived"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", archived), 201)
	if _, err := s.Store.DB.Exec("UPDATE membership_state SET state='ARCHIVED' WHERE scope_key=?", scopeKey(archived)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,input_tokens,output_tokens,details_json,created_at) VALUES('retained','p','a','c','archived','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',0,1,'{}',?)", now()); err != nil {
		t.Fatal(err)
	}
	var initialSync int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history").Scan(&initialSync)
	var writes, modelWrites, endpointWrites atomic.Int32
	var failFirst, holdNext, ready atomic.Bool
	failFirst.Store(true)
	holdNext.Store(true)
	release := make(chan struct{})
	var once sync.Once
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__catalog_fixture/state":
			var members int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
			jsonOK(w, 200, map[string]any{"writes": writes.Load(), "models": modelWrites.Load(), "endpoints": endpointWrites.Load(), "members": members, "ready": ready.Load(), "provider_calls": providerCalls.Load()})
			return
		case "/__catalog_fixture/release":
			once.Do(func() { close(release) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		}
		if r.Method == "POST" && r.URL.Path == "/api/v1/models" {
			modelWrites.Add(1)
		}
		if r.Method == "POST" && r.URL.Path == "/api/v1/endpoints" {
			endpointWrites.Add(1)
		}
		if r.Method != "POST" || r.URL.Path != "/api/v1/my-models" {
			handler.ServeHTTP(w, r)
			return
		}
		writes.Add(1)
		if failFirst.Swap(false) {
			http.Error(w, "synthetic membership interruption", 503)
			return
		}
		if !holdNext.Swap(false) {
			handler.ServeHTTP(w, r)
			return
		}
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, r)
		ready.Store(true)
		select {
		case <-release:
		case <-r.Context().Done():
			return
		}
		for name, values := range recorder.Header() {
			w.Header()[name] = values
		}
		w.WriteHeader(recorder.Code)
		w.Write(recorder.Body.Bytes())
	}))
	defer func() { once.Do(func() { close(release) }); app.Close() }()
	cmd := exec.Command("node", "e2e/catalog-experience.cjs", app.URL, b.URL+"/v2/chat/completions")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("catalog browser: %v\n%s", err, out)
	}
	var members, history, archivedCount, newSync, scoped int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE id='retained'").Scan(&history)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM membership_state WHERE scope_key=? AND state='ARCHIVED'", scopeKey(archived)).Scan(&archivedCount)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history").Scan(&newSync)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models WHERE credential_id='second' AND group_id='研發' AND endpoint!='CHAT_COMPLETIONS'").Scan(&scoped)
	if members != 5 || history != 1 || archivedCount != 1 || newSync-initialSync != 3 || scoped != 2 || writes.Load() != 4 || modelWrites.Load() != 1 || endpointWrites.Load() != 1 || providerCalls.Load() != 0 {
		t.Fatalf("catalog invariants: members=%d history=%d archived=%d sync_delta=%d B_scopes=%d writes=%d model_writes=%d endpoints=%d provider_http=%d", members, history, archivedCount, newSync-initialSync, scoped, writes.Load(), modelWrites.Load(), endpointWrites.Load(), providerCalls.Load())
	}
	t.Log(string(out))
}
