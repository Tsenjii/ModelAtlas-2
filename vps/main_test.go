package main

import (
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "testing"
)

func TestHealth(t *testing.T) {
 r:=httptest.NewRecorder()
 newHandler().ServeHTTP(r,httptest.NewRequest(http.MethodGet,"/healthz",nil))
 if r.Code!=http.StatusOK {t.Fatalf("status %d",r.Code)}
 var s status
 if err:=json.Unmarshal(r.Body.Bytes(),&s);err!=nil {t.Fatal(err)}
 if s.Status!="ok" || s.Service!="modelatlas-vps" {t.Fatalf("unexpected response: %+v",s)}
 if r.Header().Get("Cache-Control")!="no-store" {t.Fatal("health must not be cached")}
}
func TestHomepage(t *testing.T) {
 r:=httptest.NewRecorder()
 newHandler().ServeHTTP(r,httptest.NewRequest(http.MethodGet,"/",nil))
 if r.Code!=http.StatusOK {t.Fatalf("status %d",r.Code)}
 if r.Header().Get("X-Content-Type-Options")!="nosniff" {t.Fatal("missing nosniff")}
}
