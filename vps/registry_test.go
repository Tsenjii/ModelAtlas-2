package main

import (
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

func request(h http.Handler, method,path,body string) int {
 w:=httptest.NewRecorder()
 h.ServeHTTP(w,httptest.NewRequest(method,path,strings.NewReader(body)))
 return w.Code
}
func TestRegistryScopedModels(t *testing.T){
 h:=newHandler()
 if code:=request(h,"POST","/api/v1/providers",`{"id":"p1","name":"One","base_url":"https://example.org/v1"}`);code!=201 {t.Fatalf("provider %d",code)}
 if code:=request(h,"POST","/api/v1/providers",`{"id":"p2","name":"Two","base_url":"https://example.net/v1"}`);code!=201 {t.Fatalf("provider2 %d",code)}
 for _,p:=range []string{"p1","p2"} {
  if code:=request(h,"POST","/api/v1/models",`{"id":"same","provider_id":"`+p+`","display_name":"Same"}`);code!=201 {t.Fatalf("model %s %d",p,code)}
 }
 if code:=request(h,"POST","/api/v1/models",`{"id":"same","provider_id":"p1","display_name":"Duplicate"}`);code!=409 {t.Fatalf("duplicate %d",code)}
 if code:=request(h,"POST","/api/v1/models",`{"id":"m","provider_id":"missing","display_name":"Missing"}`);code!=422 {t.Fatalf("unknown provider %d",code)}
}
func TestNoUnverifiedFree(t *testing.T){
 h:=newHandler()
 request(h,"POST","/api/v1/providers",`{"id":"p","name":"One","base_url":"https://example.org"}`)
 if code:=request(h,"POST","/api/v1/models",`{"id":"m","provider_id":"p","display_name":"Free","recurring_free":true}`);code!=422 {t.Fatalf("unverified free %d",code)}
 if code:=request(h,"POST","/api/v1/models",`{"id":"m","provider_id":"p","display_name":"Unknown"}`);code!=201 {t.Fatalf("unknown model %d",code)}
}
func TestRejectInsecureProviderAndMalformedJSON(t *testing.T){
 h:=newHandler()
 for _,body:=range []string{
  `{"id":"p","name":"Bad","base_url":"http://example.org"}`,
  `{"id":"p","name":"Bad","base_url":"https://user:pass@example.org"}`,
  `{"id":"p","name":"Bad","base_url":"https://example.org","raw_key":"secret"}`,
 } {
  if code:=request(h,"POST","/api/v1/providers",body);code!=400 {t.Fatalf("expected 400, got %d",code)}
 }
}
