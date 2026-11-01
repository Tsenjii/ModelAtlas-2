package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
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
	call := func(method, path, token, etag, byteRange, site string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, nil)
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
	for _, v := range []struct {
		path, mime, sha string
		size            int
	}{
		{"/assets/aqua-kotoha-v1.jpg", "image/jpeg", "e606900526c42aa479d930eec612cb829a4828d4e51c3ededf28fc7c3ddde282", 135772},
		{"/assets/aqua-elaina-v1.jpg", "image/jpeg", "7897163cc6f609ffc29161e95b2fefc6d56619b6bda2daafdef808e2bc8f07c6", 422004},
		{"/assets/aqua-kotoha-chibi-v1.webp", "image/webp", "81953319738d7b14009fc06768e968fe1321ad6d9322d49f3717ab4626bbe373", 1560556},
		{"/assets/aqua-elaina-chibi-v1.webp", "image/webp", "3f46afcba742572cf80b9cffae8af6d4d66648dd3bce35843d1a621d02aa81bc", 1528248},
	} {
		r := call("GET", v.path, s.Token, "", "", "same-origin")
		requireCode(t, r, 200)
		b := r.Body.Bytes()
		sum := sha256.Sum256(b)
		if len(b) != v.size || hex.EncodeToString(sum[:]) != v.sha {
			t.Fatal("user artwork bytes changed", v.path)
		}
		if v.mime == "image/webp" && (string(b[:4]) != "RIFF" || string(b[8:12]) != "WEBP") || v.mime == "image/jpeg" && (b[0] != 255 || b[1] != 216) {
			t.Fatal("artwork format changed", v.path)
		}
		if r.Header().Get("Content-Type") != v.mime || r.Header().Get("Cache-Control") != "private, max-age=3600" || r.Header().Get("X-Content-Type-Options") != "nosniff" {
			t.Fatal("artwork response headers missing", v.path)
		}
		etag := r.Header().Get("ETag")
		if etag == "" {
			t.Fatal("cache validator missing")
		}
		requireCode(t, call("GET", v.path, "", etag, "", "same-origin"), 401)
		requireCode(t, call("GET", v.path, s.Token, etag, "", "cross-site"), 403)
		if cached := call("GET", v.path, s.Token, etag, "", "same-origin"); cached.Code != 304 || cached.Body.Len() != 0 {
			t.Fatal("authenticated cache validation failed", v.path)
		}
		if head := call("HEAD", v.path, s.Token, "", "", "same-origin"); head.Code != 200 || head.Body.Len() != 0 || head.Header().Get("Content-Length") != fmt.Sprint(v.size) {
			t.Fatal("HEAD metadata changed", v.path)
		}
		if partial := call("GET", v.path, s.Token, "", "bytes=0-11", "same-origin"); partial.Code != 206 || partial.Body.String() != string(b[:12]) {
			t.Fatal("range response changed", v.path)
		}
	}
	requireCode(t, call("GET", "/assets/aqua-garden-v1.webp", s.Token, "", "", "same-origin"), 404)
	classic := req(t, s, "GET", "/app.css", nil)
	requireCode(t, classic, 200)
	if strings.Contains(classic.Body.String(), "aqua-wallpaper") {
		t.Fatal("classic stylesheet contains optional art rules")
	}
	optional := req(t, s, "GET", "/aqua.css", nil)
	requireCode(t, optional, 200)
	if !strings.Contains(optional.Body.String(), "--aqua-wallpaper") || strings.Contains(optional.Body.String(), "aqua-garden") || optional.Header().Get("Content-Type") != "text/css; charset=utf-8" {
		t.Fatal("optional appearance stylesheet missing")
	}
	requireCode(t, call("GET", "/aqua.css", "", "", "", "same-origin"), 401)
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
