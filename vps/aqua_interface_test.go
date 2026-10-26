package main

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAquaArtworkAuthenticationCacheAndRange(t *testing.T) {
	s := testServer(t)
	handler := s.Handler()
	call := func(method, token, etag, byteRange, site string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "/assets/aqua-garden-v1.webp", nil)
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		r.Header.Set("If-None-Match", etag)
		r.Header.Set("Range", byteRange)
		r.Header.Set("Sec-Fetch-Site", site)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	r := call("GET", s.Token, "", "", "same-origin")
	requireCode(t, r, 200)
	b := r.Body.Bytes()
	digest := sha256.Sum256(b)
	if len(b) != 257786 || hex.EncodeToString(digest[:]) != "50b2fce90ced99098f173ab6c3a9b7b6e1376a133217a22dd614206c5cbcf6b4" || string(b[:4]) != "RIFF" || string(b[8:12]) != "WEBP" {
		t.Fatal("bundled artwork bytes changed")
	}
	if r.Header().Get("Content-Type") != "image/webp" || r.Header().Get("Cache-Control") != "private, max-age=3600" || r.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("artwork response headers missing")
	}
	classic := req(t, s, "GET", "/app.css", nil)
	requireCode(t, classic, 200)
	if strings.Contains(classic.Body.String(), "aqua-garden") {
		t.Fatal("classic stylesheet must not load or match optional Aqua rules")
	}
	optional := req(t, s, "GET", "/aqua.css", nil)
	requireCode(t, optional, 200)
	if !strings.Contains(optional.Body.String(), "aqua-garden-v1.webp") || optional.Header().Get("Content-Type") != "text/css; charset=utf-8" {
		t.Fatal("optional appearance stylesheet missing")
	}
	unauthorizedStyle := httptest.NewRecorder()
	handler.ServeHTTP(unauthorizedStyle, httptest.NewRequest("GET", "/aqua.css", nil))
	requireCode(t, unauthorizedStyle, 401)
	etag := r.Header().Get("ETag")
	if etag == "" {
		t.Fatal("cache validator missing")
	}
	requireCode(t, call("GET", "", etag, "", "same-origin"), 401)
	requireCode(t, call("GET", s.Token, etag, "", "cross-site"), 403)
	if cached := call("GET", s.Token, etag, "", "same-origin"); cached.Code != 304 || cached.Body.Len() != 0 {
		t.Fatal("authenticated cache validation failed")
	}
	if head := call("HEAD", s.Token, "", "", "same-origin"); head.Code != 200 || head.Body.Len() != 0 || head.Header().Get("Content-Length") != "257786" {
		t.Fatal("HEAD must preserve metadata without an image body")
	}
	if partial := call("GET", s.Token, "", "bytes=0-11", "same-origin"); partial.Code != 206 || partial.Body.String() != string(b[:12]) {
		t.Fatal("image range response failed")
	}
}

func TestBrowserAquaInterfaceRealWorkflowAndMobileRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var inference atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Errorf("unexpected Aqua upstream request: %s", r.URL.Path)
			http.Error(w, "unexpected", 500)
			return
		}
		inference.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Aqua 真實測試回應。\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	selection := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Atlas Aqua 跨裝置工作模型與完整長名稱"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	if _, err := s.Store.DB.Exec("INSERT INTO sessions VALUES('aqua-session','p','a','c','m','','CHAT_COMPLETIONS','花園工作區',1)"); err != nil {
		t.Fatal(err)
	}
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/aqua-interface.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("Aqua interface browser: %v\n%s", err, out)
	} else {
		t.Log(string(out))
	}
	if inference.Load() != 1 {
		t.Fatalf("appearance/navigation made inference requests: %d", inference.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM messages WHERE session_id='aqua-session'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry")
	var content string
	if err := s.Store.DB.QueryRow("SELECT group_concat(content) FROM messages WHERE session_id='aqua-session'").Scan(&content); err != nil || !strings.Contains(content, "Aqua 真實測試回應") {
		t.Fatal("real response was not retained", err)
	}
}
