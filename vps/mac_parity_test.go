package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

// All UI actions go through the real Handler, SQLite and vault. The TLS service
// only supplies deterministic upstream data; browsing must not infer a model.
func TestBrowserMacParityWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var calls, privateCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","system_name":"Parity Lab"}}`))
		case "/api/notice":
			w.Write([]byte(`{"success":true,"data":"站點公開公告"}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[],"group_ratio":{}}`))
		case "/api/perf-metrics/summary":
			w.Write([]byte(`{"success":true,"data":{"summary":{},"models":[]}}`))
		case "/api/perf-metrics":
			w.Write([]byte(publicPerformanceFixture))
		case "/api/usage/token/":
			privateCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("token usage borrowed another credential")
			}
			w.Write([]byte(`{"code":true,"data":{"total_available":0,"total_used":123,"total_granted":123}}`))
		case "/api/user/self":
			privateCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer synthetic-parity-pat" {
				t.Error("account usage did not use its PAT")
			}
			w.Write([]byte(`{"success":true,"data":{"id":8,"quota":456,"used_quota":12}}`))
		case "/v1/chat/completions":
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Parity comparison reply\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
		default:
			t.Errorf("unexpected parity upstream %s", r.URL.Path)
			http.Error(w, "unexpected", 500)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	a := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": ProviderConfig{Adapter: "NEW_API", AuthMode: "BEARER"}}), 200)
	s.Store.DB.Exec("UPDATE providers SET name='Parity Lab' WHERE id='p'")
	s.Store.DB.Exec("UPDATE accounts SET label='個人帳戶' WHERE id='a' AND provider_id='p'")
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "團隊帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "團隊 Key", Secret: "synthetic-parity-team"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Label: "帳戶 PAT", Secret: "synthetic-parity-pat", Role: "ACCOUNT_PAT"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas Reasoner"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", a), 201)
	b := a
	b.AccountID = "b"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 201)
	hidden := a
	hidden.ModelID = "retained-model"
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: hidden.ModelID, DisplayName: "保留歷史的模型"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", hidden), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": hidden.ModelID, "endpoint": "CHAT_COMPLETIONS", "state": "ARCHIVED"}), 200)
	for i := 0; i < 75; i++ {
		v := a
		title := fmt.Sprintf("歷史對話 %03d", i)
		if i == 74 {
			v = b
			title = "團隊專案對話"
		}
		id := fmt.Sprintf("parity-session-%d", i)
		if _, e := s.Store.DB.Exec("INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?)", id, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, title, now()-int64(i)*1000); e != nil {
			t.Fatal(e)
		}
		if i == 74 {
			s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('parity-message',?,'user','只在內容出現的關鍵字',?)", id, now())
		}
	}
	for i := 0; i < 205; i++ {
		archiveInsert(t, s, a, fmt.Sprintf("parity-price-%03d", i), "MANUAL_PRICE", "MANUAL", "SUCCESS", `{"price":{"input":"0.000000000123456789","output":"0","cache":"0.00005","unit":"USD/1K tokens"}}`, now()-int64(i)*1000)
	}
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__mac_parity_fixture/state" {
			var facts string
			s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE kind LIKE 'MANUAL_QUOTA:%' ORDER BY rowid DESC LIMIT 1").Scan(&facts)
			jsonOK(w, 200, map[string]any{"calls": calls.Load(), "private_calls": privateCalls.Load(), "quota_facts": facts})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/mac-parity.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_PARITY_PNG="+testPNG(t))
	if out, e := cmd.CombinedOutput(); e != nil {
		t.Fatalf("Mac parity browser: %v\n%s", e, out)
	} else {
		t.Log(string(out))
	}
	var raw string
	if e := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE kind LIKE 'MANUAL_QUOTA:%' ORDER BY rowid DESC LIMIT 1").Scan(&raw); e != nil || !strings.Contains(raw, `"remaining":0`) {
		t.Fatalf("manual quota not persisted: %v", e)
	}
}
