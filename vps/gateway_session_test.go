package main

import (
	"crypto/sha256"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func checkGatewayBrowserLogin(t *testing.T, s *Server) {
	t.Helper()
	const origin = "https://atlas.example"
	handler := (deploymentConfig{publicOrigin: origin, distribution: true}).handler(s.Handler())
	newRequest := func(method, path string, form url.Values) *http.Request {
		// Exercise a plaintext backend request accepted by the actual HTTPS
		// proxy guard rather than by trusting a forwarded header in the UI.
		r := httptest.NewRequest(method, "http://atlas.example"+path, strings.NewReader(form.Encode()))
		r.Header.Set("X-Forwarded-Proto", "https")
		if method == http.MethodPost {
			r.Header.Set("Origin", origin)
			r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		return r
	}
	call := func(r *http.Request, want int) *httptest.ResponseRecorder {
		t.Helper()
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		requireCode(t, w, want)
		return w
	}
	form := url.Values{"username": {"admin"}, "password": {s.Token}}
	local := httptest.NewRequest("POST", "http://127.0.0.1:8787/login", strings.NewReader(form.Encode()))
	local.Header.Set("Origin", "http://127.0.0.1:8787")
	local.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	localResult := httptest.NewRecorder()
	s.Handler().ServeHTTP(localResult, local)
	requireCode(t, localResult, 303)
	localCookies := localResult.Result().Cookies()
	if len(localCookies) != 1 || localCookies[0].Name != "modelatlas-session" || localCookies[0].Secure || !localCookies[0].HttpOnly {
		t.Fatal("loopback sign-in did not issue a usable local cookie")
	}
	s.gatewaySessions.close(sha256.Sum256([]byte(localCookies[0].Value)))
	request := newRequest("GET", "/", nil)
	request.Header.Set("Sec-Fetch-Mode", "navigate")
	request.SetBasicAuth("admin", s.Token)
	if w := call(request, 303); w.Header().Get("Location") != "/login" || w.Header().Get("WWW-Authenticate") != "" {
		t.Fatal("browser home did not redirect from cached Basic credentials to login")
	}
	call(newRequest("GET", "/register", nil), 404)
	call(newRequest("GET", "/logout", nil), 405)
	call(newRequest("GET", "/gateway.css", nil), 200)
	call(newRequest("GET", "/gateway.js", nil), 401)
	request = newRequest("POST", "/login", form)
	request.Header.Del("X-Forwarded-Proto")
	call(request, 426)
	request = newRequest("POST", "/login", form)
	request.Header.Del("Origin")
	call(request, 403)
	request = newRequest("POST", "/login", form)
	request.Header.Set("Sec-Fetch-Site", "same-site")
	call(request, 403)
	issued := func(previous *http.Cookie) *http.Cookie {
		t.Helper()
		r := newRequest("POST", "/login", form)
		if previous != nil {
			r.AddCookie(previous)
		}
		w := call(r, 303)
		cookies := w.Result().Cookies()
		if len(cookies) != 1 || cookies[0].Name != "__Host-modelatlas-session" || cookies[0].Path != "/" ||
			cookies[0].Domain != "" || !cookies[0].Secure || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteLaxMode ||
			strings.Contains(w.Header().Get("Set-Cookie"), s.Token) {
			t.Fatal("unexpected session cookie")
		}
		return cookies[0]
	}
	cookie := issued(nil)
	request = newRequest("GET", "/ui/api/status", nil)
	request.AddCookie(cookie)
	call(request, 200)
	request = newRequest("GET", "/api/v1/status", nil)
	request.AddCookie(cookie)
	call(request, 401)
	request.SetBasicAuth("admin", s.Token)
	call(request, 200)
	request = newRequest("POST", "/ui/api/gateway/probe", nil)
	request.Header.Del("Origin")
	request.AddCookie(cookie)
	call(request, 403)
	request.Header.Set("Origin", origin)
	call(request, 415)
	rotated := issued(cookie)
	request = newRequest("GET", "/ui/api/status", nil)
	request.AddCookie(cookie)
	if w := call(request, 401); w.Header().Get("WWW-Authenticate") != "" || !strings.Contains(w.Body.String(), "LOGIN_REQUIRED") {
		t.Fatal("replaced session was reusable or triggered a Basic challenge")
	}
	request = newRequest("POST", "/logout", nil)
	request.AddCookie(rotated)
	request.Header.Set("Origin", "https://foreign.example")
	call(request, 403)
	request = newRequest("GET", "/ui/api/status", nil)
	request.AddCookie(rotated)
	call(request, 200)
	request = newRequest("POST", "/logout", nil)
	request.AddCookie(rotated)
	call(request, 303)
	request = newRequest("GET", "/ui/api/status", nil)
	request.AddCookie(rotated)
	call(request, 401)

	wrong := url.Values{"username": {"admin"}, "password": {"fixture-wrong-password"}}
	if w := call(newRequest("POST", "/login", wrong), 401); w.Header().Get("WWW-Authenticate") != "" || w.Header().Get("Set-Cookie") != "" {
		t.Fatal("invalid credentials triggered a challenge or issued a session")
	}

	var sessions gatewayBrowserSessions
	now := time.Now()
	idle, err := sessions.open(now, [32]byte{})
	if err != nil || sessions.use(sha256.Sum256([]byte(idle)), now.Add(gatewaySessionIdle)) {
		t.Fatal("inactive session did not expire")
	}
	active, err := sessions.open(now, [32]byte{})
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256([]byte(active))
	for elapsed := gatewaySessionIdle / 2; elapsed < gatewaySessionLifetime; elapsed += gatewaySessionIdle / 2 {
		if !sessions.use(digest, now.Add(elapsed)) {
			t.Fatal("active session expired early")
		}
	}
	if sessions.use(digest, now.Add(gatewaySessionLifetime)) {
		t.Fatal("active session exceeded its absolute lifetime")
	}
}

func TestGatewayRememberBrowser(t *testing.T) {
	const origin = "https://atlas.example"
	path := filepath.Join(t.TempDir(), "modelatlas.sqlite")
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if store != nil {
			_ = store.Close()
		}
	}()
	token := "fixture-remember-browser-token"
	s := &Server{Store: store, Token: token, distribution: true}
	call := func(server *Server, target, method, route string, form url.Values, cookie *http.Cookie, want int) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(method, target+route, strings.NewReader(form.Encode()))
		if method == "POST" {
			r.Header.Set("Origin", target)
			r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		if cookie != nil {
			r.AddCookie(cookie)
		}
		w := httptest.NewRecorder()
		server.Handler().ServeHTTP(w, r)
		requireCode(t, w, want)
		return w
	}
	form := url.Values{"username": {"admin"}, "password": {token}, "remember_browser": {"1"}}
	login := call(s, origin, "POST", "/login", form, nil, http.StatusSeeOther)
	cookies := login.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatal("login did not issue a session cookie")
	}
	cookie := cookies[0]
	if cookie.MaxAge != int(gatewayRememberLifetime/time.Second) || cookie.Expires.IsZero() || !cookie.Secure || !cookie.HttpOnly || cookie.SameSite != http.SameSiteLaxMode {
		t.Fatal("unexpected remembered browser cookie")
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	s = &Server{Store: store, Token: token, distribution: true}
	call(s, origin, "GET", "/gateway.js", nil, cookie, http.StatusOK)
	call(s, origin, "GET", "/ui/api/status", nil, cookie, http.StatusOK)
	call(s, "https://other.example", "GET", "/ui/api/status", nil, cookie, http.StatusUnauthorized)
	rotated := &Server{Store: store, Token: "new-fixture-admin-token", distribution: true}
	call(rotated, origin, "GET", "/ui/api/status", nil, cookie, http.StatusUnauthorized)

	r := httptest.NewRequest("GET", origin+"/", nil)
	r.AddCookie(cookie)
	if !s.useGatewaySession(r, origin, cookie.Expires.Add(-time.Second)) || s.useGatewaySession(r, origin, cookie.Expires) {
		t.Fatal("remembered browser session did not honor its deadline")
	}
	call(s, origin, "POST", "/logout", nil, cookie, http.StatusSeeOther)
	call(s, origin, "GET", "/ui/api/status", nil, cookie, http.StatusUnauthorized)
}
