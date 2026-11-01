package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserScopedModelPickerWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var inferenceCalls atomic.Int32
	var initialReady atomic.Bool
	var holdInitial atomic.Bool
	holdInitial.Store(true)
	initialRelease := make(chan struct{})
	var releaseInitial sync.Once
	release := make(chan struct{})
	var releaseOnce sync.Once
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		inferenceCalls.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer picker-b-key" {
			t.Errorf("model picker selected the wrong endpoint or credential")
		}
		var body struct {
			Model string `json:"model"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || body.Model != "m" {
			t.Error("model picker selected the wrong model")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Scoped picker reply" + strings.Repeat(" ", 128) + "\"}}]}\n\n"))
		w.(http.Flusher).Flush()
		select {
		case <-release:
			w.Write([]byte("data: [DONE]\n\n"))
		case <-r.Context().Done():
		}
	}))
	defer up.Close()
	defer releaseOnce.Do(func() { close(release) })
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	if _, err := s.Store.DB.Exec("UPDATE providers SET name=?,description=? WHERE id=?", "Café Lab", "private-description-not-searchable", "p"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas Coder"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{ProviderID: "p", ModelID: "m", CanonicalID: "m", Category: "CODING", Aliases: []string{"程式模型"}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "研究帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "同名 Key", Secret: "picker-b-key", Group: "research-team"}), 201)
	otherAccount := v
	otherAccount.AccountID, otherAccount.Group = "b", "research-team"
	requireCode(t, req(t, s, "POST", "/api/v1/endpoints", EndpointProfile{ProviderID: "p", ID: "secondary-chat", Operation: "CHAT_COMPLETIONS", URL: up.URL + "/v1/chat/completions"}), 201)
	otherEndpoint := v
	otherEndpoint.Endpoint = "secondary-chat"
	for _, selection := range []Selection{v, otherAccount, otherEndpoint} {
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "group": "", "endpoint": "secondary-chat", "state": "ACTIVE", "favorite": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "endpoint": "CHAT_COMPLETIONS", "capabilities": []string{"FILES"}, "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "q", Name: "Café Lab", BaseURL: up.URL + "/v1", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "q", Label: "Q 帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "q", AccountID: "a", Label: "同名 Key", Secret: "picker-q-key"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "q", ID: "m", DisplayName: "Atlas Coder Q"}), 201)
	otherProvider := v
	otherProvider.ProviderID = "q"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", otherProvider), 201)
	for _, id := range []string{"catalog-only", "hidden"} {
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: id, DisplayName: id}), 201)
	}
	hidden := v
	hidden.ModelID = "hidden"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", hidden), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/lifecycle", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "hidden", "endpoint": "CHAT_COMPLETIONS", "state": "HIDDEN"}), 200)
	// More than one page remains reachable; no unbounded result DOM or silent cap.
	for i := 0; i < 110; i++ {
		id := "bulk-" + string(rune('A'+i/26)) + string(rune('A'+i%26))
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: id, DisplayName: id}), 201)
		selection := v
		selection.ModelID = id
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	}
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/picker-fixture/initial-state":
			jsonOK(w, 200, map[string]bool{"ready": initialReady.Load()})
		case "/picker-fixture/release-models":
			releaseInitial.Do(func() { close(initialRelease) })
			w.WriteHeader(204)
		case "/picker-fixture/state":
			jsonOK(w, 200, map[string]int32{"calls": inferenceCalls.Load()})
		case "/picker-fixture/release":
			releaseOnce.Do(func() { close(release) })
			w.WriteHeader(204)
		default:
			if r.URL.Path == "/api/v1/my-models" && holdInitial.Swap(false) {
				recorder := httptest.NewRecorder()
				handler.ServeHTTP(recorder, r)
				initialReady.Store(true)
				select {
				case <-initialRelease:
				case <-r.Context().Done():
					return
				}
				for k, vs := range recorder.Header() {
					w.Header()[k] = vs
				}
				w.WriteHeader(recorder.Code)
				w.Write(recorder.Body.Bytes())
				return
			}
			handler.ServeHTTP(w, r)
		}
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/model-picker.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	releaseInitial.Do(func() { close(initialRelease) })
	if err != nil {
		t.Fatalf("scoped model picker Browser: %v\n%s", err, out)
	}
	if inferenceCalls.Load() != 1 {
		t.Fatalf("picker browsing or keyboard navigation made extra inference calls: %d", inferenceCalls.Load())
	}
	var samples, memberships int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE provider_id='p' AND account_id='b' AND credential_id='c' AND model_id='m' AND group_id='research-team' AND endpoint='CHAT_COMPLETIONS' AND outcome='SUCCESS'").Scan(&samples)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&memberships)
	if samples != 1 || memberships != 115 {
		t.Fatalf("picker lost exact scope or changed membership: samples=%d members=%d", samples, memberships)
	}
	t.Log(string(out))
}
