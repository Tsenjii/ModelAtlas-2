package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserProviderFormsWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var providerCalls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		providerCalls.Add(1)
		http.Error(w, "provider editing must not infer or sync", 500)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ProviderID: "p", ID: "b", Label: "B"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: "p", AccountID: "b", ID: "c", Label: "C", Secret: "synthetic-b-original", Group: "研發"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "q", Name: "Q", BaseURL: up.URL + "/v2", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	if _, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,input_tokens,output_tokens,details_json,created_at) VALUES('retained','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',0,1,'{}',?)", now()); err != nil {
		t.Fatal(err)
	}
	var originalA, originalB string
	if err := s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='a' AND id='c'").Scan(&originalA); err != nil {
		t.Fatal(err)
	}
	if err := s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='b' AND id='c'").Scan(&originalB); err != nil {
		t.Fatal(err)
	}
	paths := []string{"accounts", "credentials", "endpoints", "providers/config", "credentials/rotate", "credentials/toggle"}
	writes := make(map[string]*atomic.Int32)
	for _, path := range paths {
		writes[path] = new(atomic.Int32)
	}
	var failAccount, failKey, holdAccount, holdConfig, accountReady, configReady atomic.Bool
	failAccount.Store(true)
	failKey.Store(true)
	holdAccount.Store(true)
	holdConfig.Store(true)
	accountRelease, configRelease := make(chan struct{}), make(chan struct{})
	var releaseAccount, releaseConfig sync.Once
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__provider_forms_fixture/state":
			state := map[string]any{"account_ready": accountReady.Load(), "config_ready": configReady.Load(), "provider_calls": providerCalls.Load()}
			for path, count := range writes {
				state[path] = count.Load()
			}
			jsonOK(w, 200, state)
			return
		case "/__provider_forms_fixture/release-account":
			releaseAccount.Do(func() { close(accountRelease) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		case "/__provider_forms_fixture/release-config":
			releaseConfig.Do(func() { close(configRelease) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		}
		var release <-chan struct{}
		if r.Method == "POST" {
			if counter := writes[strings.TrimPrefix(r.URL.Path, "/api/v1/")]; counter != nil {
				counter.Add(1)
			}
			if r.URL.Path == "/api/v1/accounts" {
				if failAccount.Swap(false) {
					http.Error(w, "synthetic account interruption", 503)
					return
				}
				if holdAccount.Swap(false) {
					release = accountRelease
				}
			} else if r.URL.Path == "/api/v1/credentials" && failKey.Swap(false) {
				http.Error(w, "synthetic key interruption", 503)
				return
			} else if r.URL.Path == "/api/v1/providers/config" && holdConfig.Swap(false) {
				release = configRelease
			}
		}
		if release == nil {
			handler.ServeHTTP(w, r)
			return
		}
		// Delay the actual Handler response only after its SQLite commit.
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, r)
		if release == accountRelease {
			accountReady.Store(true)
		} else {
			configReady.Store(true)
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
		releaseAccount.Do(func() { close(accountRelease) })
		releaseConfig.Do(func() { close(configRelease) })
		app.Close()
	}()
	cmd := exec.Command("node", "e2e/provider-forms.cjs", app.URL, up.URL+"/v1/chat/completions")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("provider forms browser: %v\n%s", err, out)
	}
	for path, expected := range map[string]int32{"accounts": 2, "credentials": 2, "endpoints": 1, "providers/config": 1, "credentials/rotate": 1, "credentials/toggle": 2} {
		if got := writes[path].Load(); got != expected {
			t.Fatalf("%s writes=%d want=%d", path, got, expected)
		}
	}
	for query, expected := range map[string]int{
		"SELECT COUNT(*) FROM accounts":                      3,
		"SELECT COUNT(*) FROM credentials":                   3,
		"SELECT COUNT(*) FROM my_models":                     1,
		"SELECT COUNT(*) FROM telemetry WHERE id='retained'": 1,
		"SELECT COUNT(*) FROM credential_secret_versions WHERE provider_id='p' AND account_id='b' AND credential_id='c'": 2,
		"SELECT COUNT(*) FROM credential_secret_versions WHERE provider_id='p' AND account_id='a' AND credential_id='c'": 0,
	} {
		var got int
		if err := s.Store.DB.QueryRow(query).Scan(&got); err != nil || got != expected {
			t.Fatalf("invariant %s = %d want=%d err=%v", query, got, expected, err)
		}
	}
	var afterA, afterB string
	s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='a' AND id='c'").Scan(&afterA)
	s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='b' AND id='c'").Scan(&afterB)
	rotated, err := s.decrypt(afterB)
	if afterA != originalA || afterB == originalB || rotated != "synthetic-b-rotated" || err != nil || providerCalls.Load() != 0 {
		t.Fatal("rotation crossed account scope, wrong vault value, or unexpected provider HTTP")
	}
	t.Log(string(out))
}
