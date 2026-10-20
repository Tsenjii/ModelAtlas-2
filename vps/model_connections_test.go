package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserModelConnectionsWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var calls, writes atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil || !strings.HasSuffix(r.URL.Path, "/chat/completions") {
			t.Error("unexpected connection comparison request")
		}
		if r.URL.Path == "/p/v1/chat/completions" && (r.Header.Get("Authorization") != "Bearer top-secret-example-key" || body["model"] != "m") {
			t.Error("comparison changed the selected P credential/model")
		}
		if r.URL.Path == "/q/v1/chat/completions" && (r.Header.Get("Authorization") != "" || body["model"] != "vendor/shared") {
			t.Error("comparison borrowed another provider's auth/model")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Connection reply\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	a := seed(t, s, up.URL+"/p/v1")
	setModel := func(v Selection, name, canonical string, join bool) {
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: v.ProviderID, ID: v.ModelID, DisplayName: name}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: v.ProviderID, ModelID: v.ModelID, CanonicalID: canonical, Category: "CHAT_REASONING"}), 200)
		if join {
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
		}
	}
	setModel(a, "Shared Reasoner", "shared-reasoner", true)
	peers := []Selection{a}
	for _, row := range []struct{ id, name, model string }{{"q", "A Fast Station", "vendor/shared"}, {"r", "C Unknown Station", "shared-r"}, {"s", "B Zero Station", "shared-s"}, {"t", "D Other Station", "shared-t"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: row.id, Name: row.name, BaseURL: up.URL + "/" + row.id + "/v1", AuthMode: "NONE"}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: row.id, Label: "個人帳戶"}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: row.id, AccountID: "a", Label: "共用名稱 Key", AuthMode: "NONE"}), 201)
		v := Selection{ProviderID: row.id, AccountID: "a", CredentialID: "c", ModelID: row.model, Endpoint: "CHAT_COMPLETIONS"}
		setModel(v, "Shared "+row.id, "shared-reasoner", true)
		peers = append(peers, v)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "研究帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "研究 Key", Secret: "synthetic-research-key", Group: "research"}), 201)
	b := a
	b.AccountID, b.Group = "b", "research"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", b), 201)
	peers = append(peers, b)
	for i := range 26 {
		id := fmt.Sprintf("extra-%02d", i)
		requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: id, ProviderID: "t", Label: "分頁帳戶 " + id}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "t", AccountID: id, Label: "Key", AuthMode: "NONE"}), 201)
		v := peers[4]
		v.AccountID = id
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	}
	for _, row := range []struct {
		id, canonical string
		join, hidden  bool
	}{{"catalog-only", "shared-reasoner", false, false}, {"hidden-shared", "shared-reasoner", true, true}, {"shared-mini", "shared-reasoner-mini", true, false}} {
		v := a
		v.ModelID = row.id
		setModel(v, row.id, row.canonical, row.join)
		if row.hidden {
			requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": row.id, "endpoint": "CHAT_COMPLETIONS", "state": "HIDDEN"}), 200)
		}
	}
	for i, v := range peers {
		if i == 2 { // Provider-reported data must never fill missing local metrics.
			if e := s.observe(context.Background(), v, "MODEL_PERFORMANCE", "NEW_API", "SUCCESS", map[string]any{"tps": 9999, "ttft_ms": 9999}); e != nil {
				t.Fatal(e)
			}
			continue
		}
		tps, ttft, latency := []float64{50, 120, 0, 0, 90, 80}[i], []int{100, 300, 0, 0, 200, 150}[i], 1000
		if i == 3 {
			latency = 0
		}
		_, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,status_code,ttft_ms,latency_ms,details_json,created_at) VALUES(?,?,?,?,?,?,?,'CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',200,?,?,?,?)", fmt.Sprintf("connection-%d", i), v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, ttft, latency, fmt.Sprintf(`{"tps":%g}`, tps), now()-1000)
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, row := range []struct{ id, outcome, details string }{{"connection-cancel", "CANCELLED", `{"tps":999999}`}, {"connection-failure", "RATE_LIMITED", `{}`}} {
		if _, e := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,details_json,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND',?,?,?)", row.id, row.outcome, row.details, now()-1000); e != nil {
			t.Fatal(e)
		}
	}
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/toggle", map[string]any{"provider_id": "t", "account_id": "a", "ids": []string{"c"}, "enabled": false}), 200)
	checkinConfig(t, s, "p")
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "q", "config": ProviderConfig{Adapter: "GENERIC", AuthMode: "NONE", RequiresCheckin: true, CheckinURL: "https://example.com/checkin"}}), 200)
	var failReads, holdRead, readReady atomic.Bool
	release := make(chan struct{})
	var releaseOnce sync.Once
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__connections_fixture/state":
			var count int
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM account_checkins").Scan(&count)
			jsonOK(w, 200, map[string]any{"calls": calls.Load(), "writes": writes.Load(), "records": count, "read_ready": readReady.Load()})
			return
		case "/__connections_fixture/fail-read":
			failReads.Store(true)
			jsonOK(w, 200, true)
			return
		case "/__connections_fixture/restore-read":
			failReads.Store(false)
			jsonOK(w, 200, true)
			return
		case "/__connections_fixture/hold-read":
			holdRead.Store(true)
			jsonOK(w, 200, true)
			return
		case "/__connections_fixture/release-read":
			releaseOnce.Do(func() { close(release) })
			jsonOK(w, 200, true)
			return
		}
		if r.URL.Path == "/api/v1/checkins/accounts" {
			if r.Method == "POST" {
				writes.Add(1)
			} else if failReads.Load() {
				http.Error(w, "temporary check-in read failure", 503)
				return
			} else if holdRead.Swap(false) {
				recorder := httptest.NewRecorder()
				handler.ServeHTTP(recorder, r)
				readReady.Store(true)
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
				return
			}
		}
		handler.ServeHTTP(w, r)
	}))
	defer func() { releaseOnce.Do(func() { close(release) }); app.Close() }()
	cmd := exec.Command("node", "e2e/model-connections.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("model connections browser: %v\n%s", err, out)
	}
	if calls.Load() != 2 || writes.Load() != 1 {
		t.Fatalf("unexpected inference/check-in writes: %d/%d", calls.Load(), writes.Load())
	}
	t.Log(string(out))
}
