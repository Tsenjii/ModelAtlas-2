package main

import (
	_ "embed"
	"io"
	"net/http"
)

//go:embed gateway_ui/index.html
var gatewayHomeHTML string

//go:embed gateway_ui/style.css
var gatewayCSS string

//go:embed gateway_ui/app.js
var gatewayJS string

//go:embed gateway_ui/management.js
var gatewayManagementJS string

var gatewayStyleAsset = staticAsset{raw: gatewayCSS, revalidate: true}
var gatewayScriptAsset = staticAsset{raw: gatewayJS + "\n" + gatewayManagementJS, revalidate: true}

func (s *Server) gatewayUIRoutes(root *http.ServeMux) {
	root.Handle("GET /{$}", s.auth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, gatewayHomeHTML)
	})))
	root.Handle("GET /gateway.css", s.auth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gatewayStyleAsset.serve(w, r, "text/css; charset=utf-8")
	})))
	root.Handle("GET /gateway.js", s.auth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gatewayScriptAsset.serve(w, r, "text/javascript; charset=utf-8")
	})))
	root.Handle("GET /aqua.css", s.auth(http.HandlerFunc(s.aquaStylesheet)))
	for asset := range aquaArtworks {
		root.Handle("GET "+asset, s.auth(http.HandlerFunc(s.aquaWallpaper)))
	}
}
