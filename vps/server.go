package main

import (
 "crypto/aes"
 "crypto/cipher"
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "encoding/json"
 "errors"
 "io"
 "log"
 "net"
 "net/http"
 "os"
 "strings"
 "time"
 "sync"
)

type Server struct {
 Store *Store
 Token string
 Key []byte
 Client *http.Client
 flightMu sync.Mutex
 flights map[string]bool
}
func (s *Server) beginFlight(id string)bool{
 s.flightMu.Lock();defer s.flightMu.Unlock()
 if s.flights==nil{s.flights=make(map[string]bool)}
 if s.flights[id]{return false}
 s.flights[id]=true
 return true
}
func (s *Server) endFlight(id string){
 s.flightMu.Lock();delete(s.flights,id);s.flightMu.Unlock()
}
func (s *Server) authorized(r *http.Request) bool {
 raw:=strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer ")
 if strings.HasPrefix(r.Header.Get("Authorization"),"Basic ") {
  b,e:=base64.StdEncoding.DecodeString(strings.TrimPrefix(r.Header.Get("Authorization"),"Basic "))
  if e==nil {parts:=strings.SplitN(string(b),":",2);if len(parts)==2&&parts[0]=="admin" {raw=parts[1]}}
 }
 actual,expected:=sha256.Sum256([]byte(raw)),sha256.Sum256([]byte(s.Token))
 return s.Token!=""&&subtle.ConstantTimeCompare(actual[:],expected[:])==1
}
func (s *Server) auth(h http.Handler)http.Handler {
 return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  security(w)
  if !s.authorized(r) {
   w.Header().Set("WWW-Authenticate",`Basic realm="ModelAtlas VPS", charset="UTF-8"`)
   http.Error(w,"authorization required",http.StatusUnauthorized)
   return
  }
  h.ServeHTTP(w,r)
 })
}
func security(w http.ResponseWriter) {
 w.Header().Set("X-Content-Type-Options","nosniff")
 w.Header().Set("X-Frame-Options","DENY")
 w.Header().Set("Referrer-Policy","no-referrer")
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Security-Policy","default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'")
}
func jsonOK(w http.ResponseWriter,status int,v any) {
 w.Header().Set("Content-Type","application/json; charset=utf-8");w.Header().Set("Cache-Control","no-store")
 w.WriteHeader(status);_ = json.NewEncoder(w).Encode(v)
}
func decodeJSON(w http.ResponseWriter,r *http.Request,v any) bool {
 if r.ContentLength>128<<10 {http.Error(w,"request too large",413);return false}
 d:=json.NewDecoder(http.MaxBytesReader(w,r.Body,128<<10))
 d.DisallowUnknownFields()
 if err:=d.Decode(v);err!=nil {http.Error(w,"invalid JSON",400);return false}
 var extra any
 if d.Decode(&extra)!=io.EOF {http.Error(w,"trailing JSON",400);return false}
 return true
}
func validateHTTPS(raw string) bool {
 u,err:=http.NewRequest("GET",raw,nil)
 if err!=nil{return false}
 return u.URL.Scheme=="https"&&u.URL.Host!=""&&u.URL.User==nil&&u.URL.RawQuery==""&&u.URL.Fragment==""&&u.URL.Opaque==""
}
func (s *Server) encrypt(raw string)(string,error) {
 if len(s.Key)!=32{return "",errors.New("encryption key not configured")}
 block,err:=aes.NewCipher(s.Key);if err!=nil{return "",err}
 aead,err:=cipher.NewGCM(block);if err!=nil{return "",err}
 nonce:=make([]byte,aead.NonceSize());if _,err=rand.Read(nonce);err!=nil{return "",err}
 encrypted:=aead.Seal(nonce,nonce,[]byte(raw),nil)
 return base64.StdEncoding.EncodeToString(encrypted),nil
}
func (s *Server) decrypt(enc string)(string,error) {
 if len(s.Key)!=32{return "",errors.New("encryption key not configured")}
 data,err:=base64.StdEncoding.DecodeString(enc);if err!=nil{return "",err}
 block,err:=aes.NewCipher(s.Key);if err!=nil{return "",err}
 aead,err:=cipher.NewGCM(block);if err!=nil{return "",err}
 if len(data)<aead.NonceSize(){return "",errors.New("short encrypted data")}
 plain,err:=aead.Open(nil,data[:aead.NonceSize()],data[aead.NonceSize():],nil)
 if err!=nil{return "",err};return string(plain),nil
}
func (s *Server) Handler()http.Handler{
 api:=http.NewServeMux()
 api.HandleFunc("GET /status",s.status)
 api.HandleFunc("GET /providers",s.listProviders)
 api.HandleFunc("POST /providers",s.addProvider)
 api.HandleFunc("GET /accounts",s.listAccounts)
 api.HandleFunc("POST /accounts",s.addAccount)
 api.HandleFunc("GET /credentials",s.listCredentials)
 api.HandleFunc("POST /credentials",s.addCredential)
 api.HandleFunc("GET /models",s.listModels)
 api.HandleFunc("POST /models",s.addModel)
 api.HandleFunc("POST /newapi/status",s.newApiStatusHTTP)
 api.HandleFunc("POST /newapi/pricing",s.newApiPricingHTTP)
 api.HandleFunc("POST /newapi/account",s.newApiAccountHTTP)
 api.HandleFunc("POST /newapi/token",s.newApiKeyHTTP)
 api.HandleFunc("GET /newapi/snapshots",s.listNewApiSnapshots)
 api.HandleFunc("GET /my-models",s.listMyModels)
 api.HandleFunc("POST /my-models",s.addMyModel)
 api.HandleFunc("DELETE /my-models",s.removeMyModel)
 api.HandleFunc("POST /models/sync",s.syncModels)
 api.HandleFunc("GET /sessions",s.listSessions)
 api.HandleFunc("POST /sessions",s.addSession)
 api.HandleFunc("GET /messages",s.listMessages)
 api.HandleFunc("POST /infer",s.infer)
 api.HandleFunc("GET /telemetry",s.listTelemetry)
 api.HandleFunc("GET /research/candidates",s.listCandidates)
 api.HandleFunc("POST /research/candidates",s.addCandidate)
 api.HandleFunc("GET /research/evidence",s.listEvidence)
 api.HandleFunc("POST /research/evidence",s.addEvidence)
 api.HandleFunc("GET /research/eligibility",s.eligibility)
 api.HandleFunc("POST /research/probe",s.probeCandidate)
 api.HandleFunc("GET /research/runs",s.listResearchRuns)
 api.HandleFunc("POST /research/runs",s.addResearchRun)
 api.HandleFunc("GET /recipes",s.listRecipes)
 api.HandleFunc("POST /recipes",s.addRecipe)
 api.HandleFunc("POST /recipes/rollback",s.rollbackRecipe)
 api.HandleFunc("POST /assistant/preview",s.assistantPreview)
 api.HandleFunc("POST /assistant/confirm",s.assistantConfirm)
 api.HandleFunc("POST /assistant/undo",s.assistantUndo)
 root:=http.NewServeMux()
 root.HandleFunc("GET /healthz",func(w http.ResponseWriter,r *http.Request){
  security(w);if err:=s.Store.DB.PingContext(r.Context());err!=nil{http.Error(w,"unavailable",503);return}
  jsonOK(w,200,map[string]any{"service":"modelatlas-vps","status":"ok"})
 })
 root.Handle("GET /{$}",s.auth(http.HandlerFunc(s.home)))
 root.Handle("GET /app.css",s.auth(http.HandlerFunc(s.stylesheet)))
 root.Handle("GET /app.js",s.auth(http.HandlerFunc(s.script)))
 root.Handle("/api/v1/",s.auth(http.StripPrefix("/api/v1",api)))
 return root
}
func (s *Server) status(w http.ResponseWriter,r *http.Request){
 var providers,models,sessions,samples int
 _=s.Store.DB.QueryRowContext(r.Context(),"SELECT COUNT(*) FROM providers").Scan(&providers)
 _=s.Store.DB.QueryRowContext(r.Context(),"SELECT COUNT(*) FROM models").Scan(&models)
 _=s.Store.DB.QueryRowContext(r.Context(),"SELECT COUNT(*) FROM sessions").Scan(&sessions)
 _=s.Store.DB.QueryRowContext(r.Context(),"SELECT COUNT(*) FROM telemetry").Scan(&samples)
 jsonOK(w,200,map[string]any{"providers":providers,"models":models,"sessions":sessions,"telemetry":samples,"persistent":true})
}
func main() {
 token:=os.Getenv("MODELATLAS_ADMIN_TOKEN")
 if len(token)<24 {log.Fatal("MODELATLAS_ADMIN_TOKEN must be at least 24 characters")}
 var key []byte
 if v:=os.Getenv("MODELATLAS_MASTER_KEY");v!="" {
  var err error;key,err=base64.StdEncoding.DecodeString(v)
  if err!=nil||len(key)!=32{log.Fatal("MODELATLAS_MASTER_KEY must be base64 of exactly 32 bytes")}
 }
 addr:=os.Getenv("MODELATLAS_LISTEN");if addr==""{addr="127.0.0.1:8787"}
 host,_,err:=net.SplitHostPort(addr);if err!=nil {log.Fatal("invalid MODELATLAS_LISTEN")}
 ip:=net.ParseIP(host);if ip==nil||!ip.IsLoopback(){log.Fatal("refuse public listener: place a TLS reverse proxy in front of loopback")}
 path:=os.Getenv("MODELATLAS_DB");if path==""{path="./data/modelatlas.db"}
 store,err:=OpenStore(path);if err!=nil{log.Fatal("database initialization failed: ",err)}
 defer store.Close()
 client:=&http.Client{Timeout:120*time.Second,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}
 srv:=&Server{Store:store,Token:token,Key:key,Client:client}
 server:=&http.Server{Addr:addr,Handler:srv.Handler(),ReadHeaderTimeout:5*time.Second,ReadTimeout:130*time.Second,IdleTimeout:30*time.Second,MaxHeaderBytes:1<<16}
 log.Printf("ModelAtlas VPS listening on %s",addr)
 log.Fatal(server.ListenAndServe())
}
