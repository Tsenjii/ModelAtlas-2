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

//go:embed gateway_ui/scene.html
var gatewaySceneHTML string

//go:embed gateway_ui/appearance.css
var gatewayAppearanceCSS string

//go:embed gateway_ui/style.css
var gatewayCSS string

//go:embed gateway_ui/app.js
var gatewayJS string

//go:embed gateway_ui/connection.js
var gatewayConnectionJS string

//go:embed gateway_ui/management.js
var gatewayManagementJS string

//go:embed gateway_ui/console.js
var gatewayConsoleJS string

//go:embed gateway_ui/update.js
var gatewayUpdateJS string

//go:embed gateway_ui/metric-plot.js
var metricPlotJS string

//go:embed gateway_ui/mark.svg
var gatewayMarkSVG string

//go:embed gateway_ui/model-brands.svg
var gatewayModelBrandsSVG string

var gatewayStyleAsset = staticAsset{raw: gatewayAppearanceCSS + gatewayCSS, revalidate: true}
var gatewayScriptAsset = staticAsset{raw: gatewayJS + "\n" + gatewayConsoleJS + "\n" + metricPlotJS + "\n" + gatewayConnectionJS + "\n" + gatewayManagementJS + "\n" + gatewayUpdateJS, revalidate: true}
var gatewayMarkAsset = staticAsset{raw: gatewayMarkSVG, revalidate: true}
var gatewayModelBrandsAsset = staticAsset{raw: gatewayModelBrandsSVG, revalidate: true}

func (s *Server) gatewayPage(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		security(w)
		origin := gatewayBrowserOrigin(r)
		if !s.useGatewaySession(r, origin, time.Now()) {
			if r.Header.Get("Sec-Fetch-Mode") == "navigate" || strings.Contains(r.Header.Get("Accept"), "text/html") {
				http.Redirect(w, r, "/login", http.StatusSeeOther)
				return
			}
			if !s.authorized(r) {
				http.Error(w, "authorization required", http.StatusUnauthorized)
				return
			}
		}
		w.Header().Set("Referrer-Policy", "same-origin")
		next(w, r)
	}
}

func (s *Server) gatewayPrivateAsset(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		security(w)
		if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			http.Error(w, "cross-site request denied", http.StatusForbidden)
			return
		}
		if !s.authorized(r) && !s.useGatewaySession(r, gatewayBrowserOrigin(r), time.Now()) {
			http.Error(w, "authorization required", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func (s *Server) gatewayUIRoutes(root *http.ServeMux, workspace bool) {
	root.HandleFunc("GET /login", s.gatewayLogin)
	root.HandleFunc("POST /login", s.gatewayLogin)
	root.HandleFunc("POST /logout", s.gatewayLogout)
	page := strings.Replace(gatewayHomeHTML, "<!-- atlas-scene -->\n", gatewaySceneHTML, 1)
	if workspace {
		page = strings.NewReplacer(
			">Lite</span>", ">完整版</span>",
			"<!-- workspace-entry -->", `<a class="icon-button link-button workspace-entry" href="/workspace" aria-label="進階工作區" title="進階工作區"><svg aria-hidden="true"><use href="#icon-model"/></svg></a>`,
		).Replace(page)
	}
	root.HandleFunc("GET /{$}", s.gatewayPage(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, page)
	}))
	root.HandleFunc("GET /gateway.css", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		gatewayStyleAsset.serve(w, r, "text/css; charset=utf-8")
	})
	root.HandleFunc("GET /gateway-mark.svg", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		gatewayMarkAsset.serve(w, r, "image/svg+xml")
	})
	root.HandleFunc("GET /gateway-model-brands.svg", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		gatewayModelBrandsAsset.serve(w, r, "image/svg+xml")
	})
	root.HandleFunc("GET /gateway.js", s.gatewayPrivateAsset(func(w http.ResponseWriter, r *http.Request) {
		gatewayScriptAsset.serve(w, r, "text/javascript; charset=utf-8")
	}))
	for asset := range aquaArtworks {
		root.HandleFunc("GET "+asset, s.gatewayPrivateAsset(s.aquaWallpaper))
	}
}
