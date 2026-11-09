package main

import (
	"crypto/rand"
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"errors"
	"html/template"
	"mime"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

//go:embed gateway_ui/login.html
var gatewayLoginHTML string

var gatewayLoginTemplate = template.Must(template.New("login").Parse(gatewayLoginHTML))

const (
	gatewaySessionIdle     = time.Hour
	gatewaySessionLifetime = 12 * time.Hour
	gatewaySessionLimit    = 64
)

type gatewayBrowserSession struct {
	expires, idleUntil time.Time
}

func (s gatewayBrowserSession) expired(now time.Time) bool {
	return !now.Before(s.expires) || !now.Before(s.idleUntil)
}

type gatewayBrowserSessions struct {
	mu     sync.Mutex
	active map[[32]byte]gatewayBrowserSession
}

func (s *gatewayBrowserSessions) open(now time.Time, previous [32]byte) (string, error) {
	var secret [32]byte
	if _, err := rand.Read(secret[:]); err != nil {
		return "", err
	}
	value := base64.RawURLEncoding.EncodeToString(secret[:])
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.active == nil {
		s.active = make(map[[32]byte]gatewayBrowserSession)
	}
	delete(s.active, previous)
	var oldest [32]byte
	var leastRecent time.Time
	for id, session := range s.active {
		if session.expired(now) {
			delete(s.active, id)
		} else if leastRecent.IsZero() || session.idleUntil.Before(leastRecent) {
			oldest, leastRecent = id, session.idleUntil
		}
	}
	if len(s.active) >= gatewaySessionLimit {
		delete(s.active, oldest)
	}
	s.active[sha256.Sum256([]byte(value))] = gatewayBrowserSession{now.Add(gatewaySessionLifetime), now.Add(gatewaySessionIdle)}
	return value, nil
}

func (s *gatewayBrowserSessions) use(id [32]byte, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	session, ok := s.active[id]
	if !ok {
		return false
	}
	if session.expired(now) {
		delete(s.active, id)
		return false
	}
	session.idleUntil = now.Add(gatewaySessionIdle)
	s.active[id] = session
	return true
}

func (s *gatewayBrowserSessions) close(id [32]byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.active, id)
}

func gatewayBrowserOrigin(r *http.Request) string {
	if origin, ok := r.Context().Value(verifiedPublicOrigin{}).(string); ok {
		return origin
	}
	if r.TLS != nil {
		return "https://" + canonicalDeploymentHost(r.Host)
	}
	// The documented SSH tunnel and local deployments use a loopback HTTP URL.
	host := strings.ToLower(r.Host)
	hostname := strings.Trim(host, "[]")
	if name, port, err := net.SplitHostPort(host); err == nil {
		hostname = name
		if port == "80" {
			host = strings.TrimSuffix(host, ":80")
		}
	}
	if hostname == "localhost" || net.ParseIP(hostname).IsLoopback() {
		return "http://" + host
	}
	return ""
}

func gatewaySameOrigin(r *http.Request, expected string) bool {
	site := r.Header.Get("Sec-Fetch-Site")
	if expected == "" || site == "cross-site" || site == "same-site" {
		return false
	}
	origins := r.Header.Values("Origin")
	if len(origins) == 1 && origins[0] != "null" {
		return origins[0] == expected
	}
	return len(origins) <= 1 && site == "same-origin"
}

func gatewaySessionCookie(origin string) http.Cookie {
	cookie := http.Cookie{Name: "modelatlas-session", Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode}
	if strings.HasPrefix(origin, "https://") {
		cookie.Name = "__Host-modelatlas-session"
		cookie.Secure = true
	}
	return cookie
}

func gatewaySessionID(r *http.Request, origin string) [32]byte {
	if origin != "" {
		cookie, err := r.Cookie(gatewaySessionCookie(origin).Name)
		if err == nil && len(cookie.Value) == base64.RawURLEncoding.EncodedLen(32) {
			return sha256.Sum256([]byte(cookie.Value))
		}
	}
	return [32]byte{}
}

func renderGatewayLogin(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Referrer-Policy", "same-origin")
	w.WriteHeader(status)
	_ = gatewayLoginTemplate.Execute(w, message)
}

func (s *Server) gatewayLogin(w http.ResponseWriter, r *http.Request) {
	security(w)
	origin := gatewayBrowserOrigin(r)
	if origin == "" {
		http.Error(w, "請使用已設定的 HTTPS 入口或本機網址登入。", http.StatusUpgradeRequired)
		return
	}
	id := gatewaySessionID(r, origin)
	now := time.Now()
	if r.Method != http.MethodPost {
		if s.gatewaySessions.use(id, now) {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		renderGatewayLogin(w, http.StatusOK, "")
		return
	}
	if !gatewaySameOrigin(r, origin) {
		http.Error(w, "origin mismatch", http.StatusForbidden)
		return
	}
	mediaType, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if mediaType != "application/x-www-form-urlencoded" {
		http.Error(w, "form content type required", http.StatusUnsupportedMediaType)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 256<<10)
	if err := r.ParseForm(); err != nil {
		var oversized *http.MaxBytesError
		if errors.As(err, &oversized) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "invalid login form", http.StatusBadRequest)
		return
	}
	if !s.matchesAdminToken(r.PostForm.Get("password")) || r.PostForm.Get("username") != "admin" {
		renderGatewayLogin(w, http.StatusUnauthorized, "帳號或密碼不正確。")
		return
	}
	value, err := s.gatewaySessions.open(time.Now(), id)
	if err != nil {
		renderGatewayLogin(w, http.StatusServiceUnavailable, "暫時無法登入，請稍後再試。")
		return
	}
	cookie := gatewaySessionCookie(origin)
	cookie.Value = value
	http.SetCookie(w, &cookie)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) gatewayLogout(w http.ResponseWriter, r *http.Request) {
	security(w)
	origin := gatewayBrowserOrigin(r)
	if !gatewaySameOrigin(r, origin) {
		http.Error(w, "origin mismatch", http.StatusForbidden)
		return
	}
	s.gatewaySessions.close(gatewaySessionID(r, origin))
	cookie := gatewaySessionCookie(origin)
	cookie.MaxAge = -1
	http.SetCookie(w, &cookie)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (s *Server) gatewayBrowserAPI(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		security(w)
		origin := gatewayBrowserOrigin(r)
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if !gatewaySameOrigin(r, origin) {
				http.Error(w, "origin mismatch", http.StatusForbidden)
				return
			}
			mediaType, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if mediaType != "application/json" {
				http.Error(w, "JSON content type required", http.StatusUnsupportedMediaType)
				return
			}
		} else if site := r.Header.Get("Sec-Fetch-Site"); site == "cross-site" || site == "same-site" {
			http.Error(w, "cross-site request denied", http.StatusForbidden)
			return
		}
		if !s.gatewaySessions.use(gatewaySessionID(r, origin), time.Now()) {
			jsonOK(w, http.StatusUnauthorized, map[string]string{"code": "LOGIN_REQUIRED"})
			return
		}
		next.ServeHTTP(w, r)
	})
}
