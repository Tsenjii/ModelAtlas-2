package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

const publicStatusFixture = `{"success":true,"data":{"version":"v0.0.0","system_name":"公開相容站點","quota_per_unit":500000,"checkin_enabled":true,"HeaderNavModules":"","quota_display_type":"CUSTOM","custom_currency_symbol":"￥"}}`
const publicPerformanceFixture = `{"success":true,"data":{"model_name":"m","series_schema":"dbcd0a3c01b55203","summary":{"success_rate":0.5,"avg_tps":25,"avg_latency_ms":1000},"window_start":1791705600,"window_end":1792309328,"series":[{"ts":1791705600,"success_rate":0,"avg_tps":0,"avg_latency_ms":1000,"avg_ttft_ms":0},{"ts":1791712800,"success_rate":100,"avg_tps":50,"avg_latency_ms":500,"avg_ttft_ms":100}],"groups":[{"group":"default","success_rate":0.5,"avg_tps":25,"avg_latency_ms":1000,"avg_ttft_ms":100,"series":[{"ts":1791705600,"success_rate":0,"avg_tps":0,"avg_latency_ms":1000,"avg_ttft_ms":0}]}]}}`

func TestNewApiPublicVersionZeroUsesSchemaWithoutSendingInferenceKey(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Error("public query carried a credential")
		}
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(publicStatusFixture))
		case "/api/notice":
			w.Write([]byte(`{"success":true,"data":"第一行公告\n第二行內容\n"}`))
		case "/api/pricing":
			w.Write([]byte(`{"success":true,"data":[{"model_name":"m","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["default"]}],"group_ratio":{"default":1}}`))
		case "/api/perf-metrics":
			if r.URL.Query().Get("model") != "m" || r.URL.Query().Get("hours") != "168" {
				t.Error("model/window not sent")
			}
			w.Write([]byte(publicPerformanceFixture))
		default:
			http.Error(w, "not found", 404)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	seed(t, s, up.URL+"/v1")
	q := NewApiQuery{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "m", Hours: 168}
	for _, operation := range []string{"status", "notice", "pricing", "performance"} {
		r := req(t, s, "POST", "/api/v1/newapi/"+operation, q)
		requireCode(t, r, 200)
		var result NewApiResult
		if json.Unmarshal(r.Body.Bytes(), &result) != nil || result.Role != "ANONYMOUS" || result.AccountID != "" || result.CredentialID != "" || result.Compatibility != "SCHEMA_VALIDATED" {
			t.Fatalf("incorrect public scope: %s", r.Body.String())
		}
		if operation == "notice" && result.Facts["text"] != "第一行公告\n第二行內容\n" {
			t.Fatal("multiline notice discarded")
		}
		if operation == "pricing" && result.Facts["price_mapping_available"] != false {
			t.Fatal("unverified currency mapped to money")
		}
		if operation == "performance" {
			if result.Facts["success_rate_unit"] != "PERCENT" || result.Facts["timestamp_unit"] != "UNIX_SECONDS" || len(result.Facts["series"].([]any)) != 2 {
				t.Fatal("provider series/units missing")
			}
			if result.Facts["summary"].(map[string]any)["success_rate"] != 0.5 {
				t.Fatal("percentage below one was converted to a ratio")
			}
		}
	}
	var count int
	if e := s.Store.DB.QueryRow("SELECT COUNT(*) FROM observations WHERE kind='NOMINAL_PRICE'").Scan(&count); e != nil {
		t.Fatal(e)
	}
	if count != 0 {
		t.Fatal("unknown currency became a nominal USD price")
	}
}

func TestNewApiFailureRetainsLastGoodAndReportsActualUpstreamStatus(t *testing.T) {
	var fail atomic.Bool
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/status" {
			w.Write([]byte(publicStatusFixture))
			return
		}
		if fail.Load() {
			http.Error(w, "top-secret-example-key upstream diagnostic", 502)
			return
		}
		w.Write([]byte(publicPerformanceFixture))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	seed(t, s, up.URL+"/v1")
	q := NewApiQuery{ProviderID: "p", ModelID: "m", Hours: 168}
	requireCode(t, req(t, s, "POST", "/api/v1/newapi/performance", q), 200)
	fail.Store(true)
	r := req(t, s, "POST", "/api/v1/newapi/performance", q)
	requireCode(t, r, 502)
	if !strings.Contains(r.Body.String(), `"upstream_status":502`) || !strings.Contains(r.Body.String(), `"code":"UPSTREAM_HTTP"`) || strings.Contains(r.Body.String(), "top-secret") {
		t.Fatalf("unsafe/unhelpful diagnostic: %s", r.Body.String())
	}
	var count int
	if e := s.Store.DB.QueryRow("SELECT COUNT(*) FROM newapi_snapshots WHERE endpoint='/api/perf-metrics'").Scan(&count); e != nil {
		t.Fatal(e)
	}
	if count != 1 {
		t.Fatal("failed refresh replaced last good snapshot")
	}
	r = req(t, s, "GET", "/api/v1/newapi/snapshots?provider_id=p", nil)
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "dbcd0a3c01b55203") {
		t.Fatal("last good no longer readable")
	}
	other := req(t, s, "GET", "/api/v1/newapi/snapshots?provider_id=other", nil)
	requireCode(t, other, 200)
	if strings.TrimSpace(other.Body.String()) != "[]" {
		t.Fatal("snapshot filter borrowed another provider")
	}
}

func TestNewApiPublicRejectsForeignModelAndMalformedMetrics(t *testing.T) {
	for _, replacement := range []string{strings.Replace(publicPerformanceFixture, `"model_name":"m"`, `"model_name":"other"`, 1), strings.Replace(publicPerformanceFixture, `"success_rate":0.5`, `"success_rate":101`, 1), strings.Replace(publicPerformanceFixture, `"avg_ttft_ms":100`, `"avg_ttft_ms":-1`, 1)} {
		up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/api/status" {
				w.Write([]byte(publicStatusFixture))
			} else {
				w.Write([]byte(replacement))
			}
		}))
		s := testServer(t)
		s.Client = up.Client()
		seed(t, s, up.URL+"/v1")
		requireCode(t, req(t, s, "POST", "/api/v1/newapi/performance", NewApiQuery{ProviderID: "p", ModelID: "m"}), 502)
		var count int
		if e := s.Store.DB.QueryRow("SELECT COUNT(*) FROM newapi_snapshots").Scan(&count); e != nil {
			t.Fatal(e)
		}
		if count != 0 {
			t.Fatal("malformed performance persisted")
		}
		up.Close()
	}
}

func TestMultilinePublicContentStillRedactsCredentialEchoes(t *testing.T) {
	value := map[string]any{"notice": "line one\nline two", "secret": "line one\ntop-secret-example-key", "control": "bad\x00value"}
	clean := cleanSecretEcho(value, []string{"top-secret-example-key"}, 0).(map[string]any)
	if clean["notice"] != "line one\nline two" || clean["secret"] != nil || clean["control"] != nil {
		t.Fatal("multiline content/secret boundaries failed")
	}
}

func TestInferenceGatewayErrorKeepsUpstreamHTTPStatusAndReceipt(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "top-secret-example-key", 503) }))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	r := req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "hello"})
	requireCode(t, r, 502)
	if !strings.Contains(r.Body.String(), `"upstream_status":503`) || strings.Contains(r.Body.String(), "top-secret") || r.Header().Get("X-ModelAtlas-Sample-ID") == "" {
		t.Fatalf("inference lost real upstream failure: %s", r.Body.String())
	}
	var outcome string
	var status int
	if e := s.Store.DB.QueryRow("SELECT outcome,status_code FROM telemetry WHERE id=?", r.Header().Get("X-ModelAtlas-Sample-ID")).Scan(&outcome, &status); e != nil {
		t.Fatal(e)
	}
	if outcome != "UPSTREAM_5XX" || status != 503 {
		t.Fatalf("receipt changed: %s %d", outcome, status)
	}
}

// Optional local replay of independently retrieved public responses. It never
// calls an external provider or requires a real credential; normal CI uses the
// deterministic fixtures above. The retrieval receipt retains URL/time/hash.
func TestNewApiCapturedPublicResponses(t *testing.T) {
	dir := os.Getenv("MODELATLAS_PUBLIC_RESPONSES")
	if dir == "" {
		t.Skip("no captured public responses supplied")
	}
	names := map[string]string{"/api/status": "status", "/api/notice": "notice", "/api/pricing": "pricing", "/api/perf-metrics/summary": "summary", "/api/perf-metrics": "performance"}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Error("captured public request had auth")
		}
		body, e := os.ReadFile(filepath.Join(dir, names[r.URL.Path]+".json"))
		if e != nil {
			http.Error(w, "missing response", 500)
			return
		}
		w.Write(body)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	seed(t, s, up.URL+"/v1")
	for _, operation := range []string{"status", "notice", "pricing", "summary", "performance"} {
		requireCode(t, req(t, s, "POST", "/api/v1/newapi/"+operation, NewApiQuery{ProviderID: "p", ModelID: "glm-5.3-flash", Hours: 168}), 200)
	}
}
