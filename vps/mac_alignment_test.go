package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserMacAlignmentWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var inference, publicCalls, rotations, accountWrites, keyWrites atomic.Int32
	var armSavedRead, failProviderRead, armKey, keyReady atomic.Bool
	var failNotice, windowReady, toggleReady, holdToggle atomic.Bool
	windowRelease, toggleRelease, keyRelease := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var releaseWindow, releaseToggle, releaseKey sync.Once
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		publicCalls.Add(1)
		if r.Header.Get("Authorization") != "" {
			t.Error("anonymous site browsing disclosed a credential")
		}
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(publicStatusFixture))
		case "/api/notice":
			if failNotice.Load() {
				http.Error(w, "synthetic-upstream-secret-never-display", 503)
				return
			}
			w.Write([]byte(`{"success":true,"data":"服務公告\n週日維護，資料保留。"}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[{"model_name":"m","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["default"]}],"group_ratio":{"default":1}}`))
		case "/api/perf-metrics/summary":
			w.Write([]byte(`{"success":true,"data":{"models":[{"model_name":"m","avg_latency_ms":1000,"avg_tps":25,"success_rate":0.5}],"summary":{"success_rate":0.5,"avg_tps":25,"avg_latency_ms":1000}}}`))
		case "/api/perf-metrics":
			if r.URL.Query().Get("model") != "m" {
				t.Error("wrong public performance model")
			}
			if r.URL.Query().Get("hours") == "24" {
				windowReady.Store(true)
				select {
				case <-windowRelease:
				case <-r.Context().Done():
					return
				}
			}
			w.Write([]byte(publicPerformanceFixture))
		default:
			inference.Add(1)
			http.Error(w, "unexpected inference", 500)
		}
	}))
	defer up.Close()
	defer releaseWindow.Do(func() { close(windowRelease) })
	defer releaseToggle.Do(func() { close(toggleRelease) })
	defer releaseKey.Do(func() { close(keyRelease) })
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": "p", "config": ProviderConfig{Adapter: "NEW_API", AuthMode: "BEARER", RequiresCheckin: true, CheckinURL: up.URL + "/checkin"}}), 200)
	s.Store.DB.Exec("UPDATE providers SET name='Atlas Lab' WHERE id='p'")
	s.Store.DB.Exec("UPDATE accounts SET label='個人帳戶' WHERE provider_id='p' AND id='a'")
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "研究帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "研究 Key", Secret: "synthetic-research-original", Group: "research"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas Reasoner"}), 201)
	v.Group = "unreported"
	s.Store.DB.Exec("UPDATE credential_context SET group_id='unreported' WHERE provider_id='p' AND account_id='a' AND credential_id='c'")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/checkins", map[string]any{"provider_id": "p", "timezone": "Asia/Taipei"}), 200)
	for i := 0; i < 237; i++ {
		archiveInsert(t, s, v, "history-"+strconv.Itoa(i), "MANUAL_PRICE", "MANUAL", "SUCCESS", `{"price":{"input":"0.000000000123456789","output":"0","unit":"USD/1K tokens"}}`, now()-int64(i)*1000)
	}
	foreign := v
	foreign.AccountID = "b"
	archiveInsert(t, s, foreign, "foreign-account", "MANUAL_PRICE", "MANUAL", "SUCCESS", `{"price":{"input":"999","unit":"USD/1K tokens"}}`, now())
	if _, err := s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,ttft_ms,latency_ms,input_tokens,output_tokens,details_json,created_at) VALUES('aligned-sample','p','a','c','m','unreported','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',0,1500,0,10,'{\"tps\":0}',?)", now()); err != nil {
		t.Fatal(err)
	}
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__mac_alignment_fixture/state":
			jsonOK(w, 200, map[string]any{"public_calls": publicCalls.Load(), "inference": inference.Load(), "window_ready": windowReady.Load(), "toggle_ready": toggleReady.Load(), "rotations": rotations.Load(), "account_writes": accountWrites.Load(), "key_writes": keyWrites.Load(), "key_ready": keyReady.Load()})
			return
		case "/__mac_alignment_fixture/arm-key":
			armKey.Store(true)
		case "/__mac_alignment_fixture/release-key":
			releaseKey.Do(func() { close(keyRelease) })
		case "/__mac_alignment_fixture/arm-saved-read":
			armSavedRead.Store(true)
		case "/__mac_alignment_fixture/fail-notice":
			failNotice.Store(true)
		case "/__mac_alignment_fixture/restore-notice":
			failNotice.Store(false)
		case "/__mac_alignment_fixture/release-window":
			releaseWindow.Do(func() { close(windowRelease) })
		case "/__mac_alignment_fixture/arm-toggle":
			holdToggle.Store(true)
		case "/__mac_alignment_fixture/release-toggle":
			releaseToggle.Do(func() { close(toggleRelease) })
		case "/__mac_alignment_fixture/append":
			archiveInsert(t, s, v, "new-after-snapshot", "MANUAL_PRICE", "MANUAL", "SUCCESS", `{"price":{"input":"0","output":"0","unit":"USD/1K tokens"}}`, now())
		default:
			if r.URL.Path == "/api/v1/credentials" && r.Method == "POST" {
				keyWrites.Add(1)
				recorder := httptest.NewRecorder()
				handler.ServeHTTP(recorder, r)
				if armKey.Swap(false) {
					keyReady.Store(true)
					select {
					case <-keyRelease:
					case <-r.Context().Done():
						return
					}
				}
				for k, vs := range recorder.Header() {
					w.Header()[k] = vs
				}
				w.WriteHeader(recorder.Code)
				w.Write(recorder.Body.Bytes())
				return
			}
			if r.URL.Path == "/api/v1/providers" && r.Method == "GET" && failProviderRead.Swap(false) {
				http.Error(w, "synthetic saved read interruption", 503)
				return
			}
			if r.URL.Path == "/api/v1/accounts" && r.Method == "POST" {
				accountWrites.Add(1)
				handler.ServeHTTP(w, r)
				if armSavedRead.Swap(false) {
					failProviderRead.Store(true)
				}
				return
			}
			if r.URL.Path == "/api/v1/credentials/rotate" {
				rotations.Add(1)
			}
			if r.URL.Path == "/api/v1/credentials/toggle" && holdToggle.Swap(false) {
				recorder := httptest.NewRecorder()
				handler.ServeHTTP(recorder, r)
				toggleReady.Store(true)
				select {
				case <-toggleRelease:
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
			return
		}
		jsonOK(w, 200, map[string]bool{"done": true})
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/mac-alignment.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	releaseKey.Do(func() { close(keyRelease) })
	releaseWindow.Do(func() { close(windowRelease) })
	releaseToggle.Do(func() { close(toggleRelease) })
	if err != nil {
		t.Fatalf("Mac alignment Browser: %v\n%s", err, out)
	}
	var checkins, observations, memberships int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM account_checkins").Scan(&checkins)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM observations").Scan(&observations)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&memberships)
	if checkins != 1 || observations != 239 || memberships != 1 || inference.Load() != 0 || rotations.Load() != 2 || accountWrites.Load() != 1 || keyWrites.Load() != 1 {
		t.Fatalf("persisted counts checkins=%d observations=%d membership=%d inference=%d rotations=%d", checkins, observations, memberships, inference.Load(), rotations.Load())
	}
	var enc string
	s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='b' AND id='c'").Scan(&enc)
	secret, err := s.decrypt(enc)
	if err != nil || secret != "synthetic-research-original" {
		t.Fatal("restore did not restore this account's original credential")
	}
	var otherEnc string
	s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='a' AND id='c'").Scan(&otherEnc)
	otherSecret, err := s.decrypt(otherEnc)
	if err != nil || otherSecret != "top-secret-example-key" {
		t.Fatal("other account's same-ID Key was changed")
	}
	t.Log(string(out))
}
