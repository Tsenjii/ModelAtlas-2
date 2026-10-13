package main

import (
 "bytes"
 "crypto/rand"
 "encoding/base64"
 "encoding/json"
 "io"
 "net/http"
 "net/http/httptest"
 "path/filepath"
 "strings"
 "testing"
)

func testServer(t *testing.T)*Server{
 t.Helper();db,err:=OpenStore(filepath.Join(t.TempDir(),"modelatlas.sqlite"))
 if err!=nil{t.Fatal(err)}
 t.Cleanup(func(){_ = db.Close()})
 key:=make([]byte,32);if _,err=rand.Read(key);err!=nil{t.Fatal(err)}
 return &Server{Store:db,Token:"local-testing-long-admin-token",Key:key,Client:http.DefaultClient}
}
func req(t *testing.T,s *Server,method,path string,body any) *httptest.ResponseRecorder {
 t.Helper();var input io.Reader
 if body!=nil{b,err:=json.Marshal(body);if err!=nil{t.Fatal(err)};input=bytes.NewReader(b)}
 r:=httptest.NewRequest(method,path,input);r.Header.Set("Authorization","Bearer "+s.Token)
 if body!=nil{r.Header.Set("Content-Type","application/json")}
 w:=httptest.NewRecorder();s.Handler().ServeHTTP(w,r);return w
}
func requireCode(t *testing.T,r *httptest.ResponseRecorder,code int){t.Helper();if r.Code!=code{t.Fatalf("expected %d got %d: %s",code,r.Code,r.Body.String())}}
func seed(t *testing.T,s *Server,providerURL string)Selection{
 t.Helper()
 requireCode(t,req(t,s,"POST","/api/v1/providers",Provider{ID:"p",Name:"P",BaseURL:providerURL,AuthMode:"BEARER"}),201)
 requireCode(t,req(t,s,"POST","/api/v1/accounts",Account{ID:"a",ProviderID:"p",Label:"A"}),201)
 requireCode(t,req(t,s,"POST","/api/v1/credentials",Credential{ID:"c",ProviderID:"p",AccountID:"a",Label:"C",Secret:"top-secret-example-key"}),201)
 requireCode(t,req(t,s,"POST","/api/v1/models",Model{ProviderID:"p",ID:"m",DisplayName:"M"}),201)
 return Selection{ProviderID:"p",AccountID:"a",CredentialID:"c",ModelID:"m",Group:"",Endpoint:"CHAT_COMPLETIONS"}
}
func TestAuthorizationAndSecretEncryption(t *testing.T){
 s:=testServer(t)
 w:=httptest.NewRecorder();s.Handler().ServeHTTP(w,httptest.NewRequest("GET","/api/v1/providers",nil))
 requireCode(t,w,401)
 seed(t,s,"https://provider.example/v1")
 r:=req(t,s,"GET","/api/v1/credentials",nil);requireCode(t,r,200)
 if strings.Contains(r.Body.String(),"top-secret"){t.Fatal("plaintext leaked from credential list")}
 var encrypted string
 if err:=s.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted);err!=nil{t.Fatal(err)}
 if strings.Contains(encrypted,"top-secret")||encrypted=="top-secret-example-key"{t.Fatal("plaintext secret in SQLite")}
 key,err:=s.decrypt(encrypted);if err!=nil||key!="top-secret-example-key"{t.Fatal("vault roundtrip failed")}
 if _,err=base64.StdEncoding.DecodeString(encrypted);err!=nil{t.Fatal("vault ciphertext not encoded")}
}
func TestScopedOptInAndHistoryPreservation(t *testing.T){
 s:=testServer(t);v:=seed(t,s,"https://provider.example/v1")
 r:=req(t,s,"POST","/api/v1/sessions",Session{Selection:v,Title:"Should fail"})
 requireCode(t,r,422)
 requireCode(t,req(t,s,"POST","/api/v1/my-models",v),201)
 requireCode(t,req(t,s,"POST","/api/v1/sessions",Session{Selection:v,Title:"Hello"}),201)
 var created Session
 _=json.Unmarshal(req(t,s,"GET","/api/v1/sessions",nil).Body.Bytes(),&[]Session{})
 sessions:=[]Session{}
 if json.Unmarshal(req(t,s,"GET","/api/v1/sessions",nil).Body.Bytes(),&sessions)!=nil||len(sessions)!=1{t.Fatal("session missing")}
 created=sessions[0]
 // Changing the group must NOT find the existing selected membership.
 other:=v;other.Group="other"
 requireCode(t,req(t,s,"POST","/api/v1/sessions",Session{Selection:other,Title:"Wrong group"}),422)
 requireCode(t,req(t,s,"DELETE","/api/v1/my-models",v),200)
 if req(t,s,"GET","/api/v1/messages?session_id="+created.ID,nil).Code!=200{t.Fatal("history removed with membership")}
}
func TestSQLiteDurability(t *testing.T){
 path:=filepath.Join(t.TempDir(),"atlas.sqlite");s,err:=OpenStore(path);if err!=nil{t.Fatal(err)}
 if _,err=s.DB.Exec("INSERT INTO providers(id,name,base_url,auth_mode,created_at) VALUES('p','Test','https://provider.example/v1','NONE',1)");err!=nil{t.Fatal(err)}
 _=s.Close()
 reopened,err:=OpenStore(path);if err!=nil{t.Fatal(err)};defer reopened.Close()
 var name string;if err=reopened.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&name);err!=nil||name!="Test"{t.Fatalf("missing durable provider: %v",err)}
}
func TestUnknownNeverBecomesFree(t *testing.T){
 c:=Candidate{ID:"candidate",Taiwan:"YES",Strength:"STRONG",Recurring:"DAILY_RESET"}
 a,b:=int64(100),int64(1000000);c.RPD=&a;c.TPD=&b
 if EvaluateCandidate(c,false,false).Classification=="ELIGIBLE"{t.Fatal("unverified quota must not qualify")}
 if EvaluateCandidate(c,true,false).Classification!="ELIGIBLE"{t.Fatal("inclusive threshold should qualify")}
 a=99;if EvaluateCandidate(c,true,false).Classification=="ELIGIBLE"{t.Fatal("RPD below threshold qualified")}
 if EvaluateCandidate(c,true,true).Classification!="ELIGIBLE"{t.Fatal("verified AA exception should qualify")}
 c.Taiwan="UNKNOWN";if EvaluateCandidate(c,true,true).Classification=="ELIGIBLE"{t.Fatal("unknown Taiwan access qualified")}
}
