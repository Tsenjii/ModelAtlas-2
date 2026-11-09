package main

import (
	_ "embed"
	"io"
	"net/http"
	"strings"
	"time"
)

//go:embed gateway_ui/index.html
var gatewayHomeHTML string

//go:embed gateway_ui/style.css
var gatewayCSS string

//go:embed gateway_ui/app.js
var gatewayJS string

//go:embed gateway_ui/management.js
var gatewayManagementJS string

//go:embed gateway_ui/console.js
var gatewayConsoleJS string

//go:embed gateway_ui/mark.svg
var gatewayMarkSVG string

var gatewayStyleAsset = staticAsset{raw: gatewayCSS, revalidate: true}
var gatewayScriptAsset = staticAsset{raw: gatewayJS + "\n" + gatewayConsoleJS + "\n" + gatewayManagementJS, revalidate: true}
var gatewayMarkAsset = staticAsset{raw: gatewayMarkSVG, revalidate: true}

func (s *Server) gatewayUIRoutes(root *http.ServeMux) {
	root.HandleFunc("GET /login", s.gatewayLogin)
	root.HandleFunc("POST /login", s.gatewayLogin)
	root.HandleFunc("POST /logout", s.gatewayLogout)
	root.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		origin := gatewayBrowserOrigin(r)
		navigation := r.Header.Get("Sec-Fetch-Mode") == "navigate" || strings.Contains(r.Header.Get("Accept"), "text/html")
		if !s.gatewaySessions.use(gatewaySessionID(r, origin), time.Now()) && (navigation || !s.authorized(r)) {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Referrer-Policy", "same-origin")
		_, _ = io.WriteString(w, gatewayHomeHTML)
	})
	root.HandleFunc("GET /gateway.css", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		gatewayStyleAsset.serve(w, r, "text/css; charset=utf-8")
	})
	root.HandleFunc("GET /gateway-mark.svg", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		gatewayMarkAsset.serve(w, r, "image/svg+xml")
	})
	privateAsset := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			security(w)
			if !s.authorized(r) && !s.gatewaySessions.use(gatewaySessionID(r, gatewayBrowserOrigin(r)), time.Now()) {
				http.Error(w, "authorization required", http.StatusUnauthorized)
				return
			}
			if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
				http.Error(w, "cross-site request denied", http.StatusForbidden)
				return
			}
			next(w, r)
		}
	}
	root.HandleFunc("GET /gateway.js", privateAsset(func(w http.ResponseWriter, r *http.Request) {
		gatewayScriptAsset.serve(w, r, "text/javascript; charset=utf-8")
	}))
	for asset := range aquaArtworks {
		root.HandleFunc("GET "+asset, privateAsset(s.aquaWallpaper))
	}
}
