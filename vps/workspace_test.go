package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserWorkspaceWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Error(w, "workspace browsing must not contact a provider", 500)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "lab", Name: "開發實驗室", BaseURL: "https://lab.example/v1", AuthMode: "NONE"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "media", Name: "多模態工作區", BaseURL: "https://media.example/v1", AuthMode: "NONE"}), 201)
	for _, model := range []struct{ id, name, category string }{{"m", "日常對話", "CHAT_REASONING"}, {"code", "程式協作", "CODING"}, {"vision", "影像理解", "CHAT_REASONING"}, {"catalog-only", "研究候選（未加入）", "OTHER"}} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: model.id, DisplayName: model.name}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: "p", ModelID: model.id, Category: model.category}), 200)
		if model.id != "catalog-only" {
			selection := v
			selection.ModelID = model.id
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
			if model.id == "code" {
				requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": model.id, "endpoint": "CHAT_COMPLETIONS", "state": "ACTIVE", "favorite": true}), 200)
			}
		}
	}
	zero := "0"
	if e := s.observe(context.Background(), v, "PRICING", "MANUAL", "SUCCESS", map[string]any{"price": PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true, Unit: "USD/1K tokens"}, "effective_charge": PriceLegs{Input: &zero, Output: &zero, FixedAbsent: true, Unit: "USD/1K tokens"}}); e != nil {
		t.Fatal(e)
	}
	for _, sample := range []struct{ id, model, outcome, metrics string }{{"ui-1", "m", "SUCCESS", `{"tps":72.5,"ttft_ms":180,"latency_ms":1450}`}, {"ui-2", "m", "SUCCESS", `{"tps":68,"ttft_ms":210,"latency_ms":1680}`}, {"ui-3", "code", "SUCCESS", `{"tps":92,"ttft_ms":250,"latency_ms":2130}`}} {
		if _, e := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,details_json,created_at) VALUES(?,'p','a','c',?,'','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND',?,?,?)", sample.id, sample.model, sample.outcome, sample.metrics, now()); e != nil {
			t.Fatal(e)
		}
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	empty := testServer(t)
	blank := httptest.NewServer(empty.Handler())
	defer blank.Close()
	cmd := exec.Command("node", "e2e/workspace.cjs", app.URL, blank.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("workspace browser: %v\n%s", e, out)
	}
	if calls.Load() != 0 {
		t.Fatalf("workspace navigation or preferences made %d provider requests", calls.Load())
	}
	var members int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if members != 3 {
		t.Fatalf("workspace navigation changed membership: %d", members)
	}
	t.Log(string(out))
}
