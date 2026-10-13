package main

import (
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

func TestNewApiCredentialRolesAndProvenance(t *testing.T) {
 seen:=[]string{}
 upstream:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  seen=append(seen,r.URL.Path+"@"+r.Header.Get("Authorization"))
  w.Header().Set("Content-Type","application/json")
  switch r.URL.Path {
  case "/api/status": _,_=w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"checkin_enabled":true}}`))
  case "/api/user/self": _,_=w.Write([]byte(`{"success":true,"data":{"quota":500000,"used_quota":20000,"request_count":7,"email":"should-not-escape"}}`))
  case "/api/usage/token/": _,_=w.Write([]byte(`{"code":true,"data":{"total_available":12345,"unlimited_quota":false}}`))
  case "/api/pricing": _,_=w.Write([]byte(`{"success":true,"data":[{"model_name":"m","model_price":0.3}],"pricing_version":"2026-10-14"}`))
  default:http.Error(w,"not found",404)
  }
 }))
 defer upstream.Close()
 s:=testServer(t);s.Client=upstream.Client()
 seed(t,s,upstream.URL+"/v1")
 pat:=Credential{ID:"pat",ProviderID:"p",AccountID:"a",Label:"Account",Secret:"account-pat-secret",Role:"ACCOUNT_PAT"}
 requireCode(t,req(t,s,"POST","/api/v1/credentials",pat),201)
 q:=NewApiQuery{ProviderID:"p",AccountID:"a",CredentialID:"pat"}
 requireCode(t,req(t,s,"POST","/api/v1/newapi/status",q),200)
 requireCode(t,req(t,s,"POST","/api/v1/newapi/account",q),200)
 requireCode(t,req(t,s,"POST","/api/v1/newapi/pricing",q),200)
 // The account PAT must never become an inference token.
 requireCode(t,req(t,s,"POST","/api/v1/newapi/token",q),422)
 q.CredentialID="c"
 requireCode(t,req(t,s,"POST","/api/v1/newapi/token",q),502) // fixture deliberately uses wrong envelope? see rc25 code parsing
 requireCode(t,req(t,s,"POST","/api/v1/newapi/account",q),422)
 result:=req(t,s,"GET","/api/v1/newapi/snapshots",nil)
 requireCode(t,result,200)
 if strings.Contains(result.Body.String(),"account-pat-secret")||strings.Contains(result.Body.String(),"should-not-escape"){t.Fatal("snapshot leaked credential or identity")}
 for _,entry:=range seen{
  if strings.HasPrefix(entry,"/api/user/self@")&&!strings.HasSuffix(entry,"Bearer account-pat-secret"){t.Fatalf("PAT scope violation: %s",entry)}
  if strings.HasPrefix(entry,"/api/status@")&&!strings.HasSuffix(entry,"@"){t.Fatalf("status must be anonymous: %s",entry)}
 }
 var snaps []map[string]any
 if json.Unmarshal(result.Body.Bytes(),&snaps)!=nil||len(snaps)<3{t.Fatal("snapshots not stored")}
}
func TestNewApiUnknownVersionBlocksPrivilegedEndpoints(t *testing.T){
 count:=0
 upstream:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  count++
  if r.URL.Path=="/api/status" {_,_=w.Write([]byte(`{"success":true,"data":{"version":"v2.0.0"}}`));return}
  t.Error("noncertified version accessed protected path");http.Error(w,"unexpected",500)
 }))
 defer upstream.Close()
 s:=testServer(t);s.Client=upstream.Client();seed(t,s,upstream.URL+"/v1")
 pat:=Credential{ID:"pat",ProviderID:"p",AccountID:"a",Secret:"secret",Role:"ACCOUNT_PAT"}
 requireCode(t,req(t,s,"POST","/api/v1/credentials",pat),201)
 requireCode(t,req(t,s,"POST","/api/v1/newapi/account",NewApiQuery{ProviderID:"p",AccountID:"a",CredentialID:"pat"}),422)
 if count!=1 {t.Fatalf("unknown version called %d upstream paths",count)}
}
