package main

import (
 "encoding/json"
 "log"
 "net/http"
 "os"
 "time"
)

type status struct {
 Service string `json:"service"`
 Version string `json:"version"`
 Status string `json:"status"`
}

func newHandler() http.Handler {
 mux := http.NewServeMux()
 mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
  w.Header().Set("Content-Type", "application/json")
  w.Header().Set("Cache-Control", "no-store")
  json.NewEncoder(w).Encode(status{Service:"modelatlas-vps",Version:"0.1.0",Status:"ok"})
 })
 mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
  w.Header().Set("Content-Type", "text/html; charset=utf-8")
  w.Header().Set("X-Content-Type-Options", "nosniff")
  w.Write([]byte("<!doctype html><html lang='zh-Hant'><meta name='viewport' content='width=device-width,initial-scale=1'><title>ModelAtlas VPS</title><body><main><h1>ModelAtlas VPS</h1><p>服務已啟動。Playground、Providers、Models 尚待實作；不會假裝已有 Android 功能。</p></main></body></html>"))
 })
 return mux
}

func main() {
 addr := os.Getenv("MODELATLAS_LISTEN")
 if addr == "" { addr = "127.0.0.1:8787" }
 srv := &http.Server{Addr:addr, Handler:newHandler(), ReadHeaderTimeout:5*time.Second, IdleTimeout:30*time.Second}
 log.Printf("ModelAtlas VPS listening on %s", addr)
 log.Fatal(srv.ListenAndServe())
}
