package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserMobileAlignmentWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var inference, catalogs atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","system_name":"手機對齊工作站"}}`))
		case "/api/notice":
			w.Write([]byte(`{"success":true,"data":"手機介面測試公告"}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[],"group_ratio":{}}`))
		case "/api/perf-metrics/summary":
			w.Write([]byte(`{"success":true,"data":{"summary":{},"models":[]}}`))
		case "/api/perf-metrics":
			w.Write([]byte(publicPerformanceFixture))
		case "/v1/models":
			catalogs.Add(1)
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
		case "/v1/chat/completions":
			inference.Add(1)
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"對齊比較完成\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
		default:
			t.Errorf("unexpected mobile upstream %s", r.URL.Path)
			http.Error(w, "unexpected mobile upstream", 500)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	a := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": ProviderConfig{Adapter: "NEW_API", AuthMode: "BEARER", RequiresCheckin: true, CheckinURL: "https://example.com/checkin"}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas 手機工作模型與長名稱測試"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", a), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "研究團隊與長帳戶名稱"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "研究 Key", Secret: "synthetic-mobile-team"}), 201)
	b := a
	b.AccountID = "b"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 201)
	for _, query := range []string{
		"INSERT INTO sessions VALUES('mobile-session','p','a','c','m','','CHAT_COMPLETIONS','手機介面測試對話',1)",
		"INSERT INTO messages(id,session_id,role,content,created_at) VALUES('mobile-message','mobile-session','assistant','保留對話內容與草稿。',1)",
	} {
		if _, e := s.Store.DB.Exec(query); e != nil {
			t.Fatal(e)
		}
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/mobile-alignment.cjs", app.URL, up.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("mobile alignment browser: %v\n%s", err, out)
	} else {
		t.Log(string(out))
	}
	if inference.Load() != 2 || catalogs.Load() != 1 {
		t.Fatalf("browsing made extra upstream calls: inference=%d catalog=%d", inference.Load(), catalogs.Load())
	}
	var members, messages int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages WHERE id='mobile-message'").Scan(&messages)
	if members != 2 || messages != 1 {
		t.Fatalf("mobile navigation changed members/history: %d/%d", members, messages)
	}
}
