package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Fail or hold the app's real response, after its SQLite work has completed.
// This exercises stale browser reads/mutations without replacing product APIs.
func TestBrowserDetailWorkspaceWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var providerCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		providerCalls.Add(1)
		http.Error(w, "detail browsing must not infer or sync", 500)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	for _, model := range []Model{{ProviderID: "p", ID: "m", DisplayName: "日常對話"}, {ProviderID: "p", ID: "other", DisplayName: "另一個模型"}, {ProviderID: "p", ID: "catalog-only", DisplayName: "未加入的候選"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", model), 201)
		if model.ID != "catalog-only" {
			selection := v
			selection.ModelID = model.ID
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
		}
	}
	zero := "0"
	if e := s.observe(context.Background(), v, "PRICING", "MANUAL", "SUCCESS", map[string]any{"price": PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true, Unit: "USD/1K tokens"}, "effective_charge": PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true, Unit: "USD/1K tokens"}}); e != nil {
		t.Fatal(e)
	}
	if e := s.observe(context.Background(), v, "PRICING", "MANUAL", "FAILED", map[string]any{"reason": "synthetic later sync failure"}); e != nil {
		t.Fatal(e)
	}
	for i, row := range []struct {
		age     time.Duration
		outcome string
	}{
		{26 * time.Hour, "SUCCESS"}, {2 * time.Hour, "SUCCESS"}, {20 * time.Minute, "RATE_LIMITED"}, {10 * time.Minute, "CANCELLED"},
	} {
		if _, e := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,ttft_ms,latency_ms,input_tokens,output_tokens,details_json,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND',?,250,1500,0,10,'{\"tps\":75}',?)", string(rune('a'+i)), row.outcome, now()-row.age.Milliseconds()); e != nil {
			t.Fatal(e)
		}
	}
	var failFirst, holdWindow, holdAction, windowReady, actionReady atomic.Bool
	failFirst.Store(true)
	holdWindow.Store(true)
	windowRelease, actionRelease := make(chan struct{}), make(chan struct{})
	var releaseWindow, releaseAction sync.Once
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__detail_fixture/state":
			jsonOK(w, 200, map[string]bool{"window": windowReady.Load(), "action": actionReady.Load()})
			return
		case "/__detail_fixture/release-window":
			releaseWindow.Do(func() { close(windowRelease) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		case "/__detail_fixture/arm-action":
			holdAction.Store(true)
			jsonOK(w, 200, map[string]bool{"armed": true})
			return
		case "/__detail_fixture/release-action":
			releaseAction.Do(func() { close(actionRelease) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		}
		isDetail := r.Method == "GET" && r.URL.Path == "/api/v1/models/detail" && r.URL.Query().Get("model_id") == "m"
		if isDetail && failFirst.Swap(false) {
			http.Error(w, "synthetic interrupted detail read", http.StatusServiceUnavailable)
			return
		}
		var release <-chan struct{}
		if isDetail && r.URL.Query().Get("window") == "1h" && holdWindow.Swap(false) {
			release = windowRelease
		} else if r.Method == "POST" && r.URL.Path == "/api/v1/models/lifecycle" && holdAction.Swap(false) {
			release = actionRelease
		}
		if release == nil {
			handler.ServeHTTP(w, r)
			return
		}
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, r)
		if release == windowRelease {
			windowReady.Store(true)
		} else {
			actionReady.Store(true)
		}
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
	defer func() {
		releaseWindow.Do(func() { close(windowRelease) })
		releaseAction.Do(func() { close(actionRelease) })
		app.Close()
	}()
	cmd := exec.Command("node", "e2e/detail-workspace.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("detail workspace browser: %v\n%s", e, out)
	}
	if providerCalls.Load() != 0 {
		t.Fatalf("detail browsing made %d provider HTTP requests", providerCalls.Load())
	}
	var members, samples int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&samples)
	if members != 2 || samples != 4 {
		t.Fatalf("details changed membership/history: %d/%d", members, samples)
	}
	t.Log(string(out))
}
