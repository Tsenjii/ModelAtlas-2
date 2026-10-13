package main

import (
 "encoding/json"
 "errors"
 "io"
 "net/http"
 "net/url"
 "strings"
 "sync"
 "time"
)

// API contracts are intentionally explicit: unknown is never silently free.
// This lightweight initial backend is in-memory; durable SQLite parity remains pending.
type Provider struct { ID string `json:"id"`; Name string `json:"name"`; BaseURL string `json:"base_url"` }
type Model struct { ID string `json:"id"`; ProviderID string `json:"provider_id"`; DisplayName string `json:"display_name"`; PriceState string `json:"price_state"`; RecurringFree bool `json:"recurring_free"` }
type Registry struct { mu sync.RWMutex; providers map[string]Provider; models map[string]Model }
func NewRegistry() *Registry { return &Registry{providers:make(map[string]Provider),models:make(map[string]Model)} }
func (db *Registry) Handler() http.Handler {
 mux:=http.NewServeMux()
 mux.HandleFunc("GET /api/v1/providers",func(w http.ResponseWriter,r *http.Request){
  db.mu.RLock(); defer db.mu.RUnlock()
  result:=make([]Provider,0,len(db.providers));for _,p:=range db.providers {result=append(result,p)};respond(w,http.StatusOK,result)
 })
 mux.HandleFunc("POST /api/v1/providers",func(w http.ResponseWriter,r *http.Request){
  var p Provider;if err:=decode(r,&p);err!=nil {http.Error(w,"invalid JSON",400);return}
  if !validID(p.ID)||strings.TrimSpace(p.Name)=="" {http.Error(w,"invalid provider",400);return}
  u,err:=url.Parse(p.BaseURL);if err!=nil||u.Scheme!="https"||u.Host==""||u.User!=nil {http.Error(w,"HTTPS provider URL required",400);return}
  db.mu.Lock();defer db.mu.Unlock()
  if _,exists:=db.providers[p.ID];exists {http.Error(w,"provider exists",409);return}
  db.providers[p.ID]=p;respond(w,http.StatusCreated,p)
 })
 mux.HandleFunc("GET /api/v1/models",func(w http.ResponseWriter,r *http.Request){
  db.mu.RLock();defer db.mu.RUnlock()
  result:=make([]Model,0,len(db.models));for _,m:=range db.models {result=append(result,m)};respond(w,http.StatusOK,result)
 })
 mux.HandleFunc("POST /api/v1/models",func(w http.ResponseWriter,r *http.Request){
  var m Model;if err:=decode(r,&m);err!=nil {http.Error(w,"invalid JSON",400);return}
  if !validID(m.ID)||!validID(m.ProviderID)||strings.TrimSpace(m.DisplayName)=="" {http.Error(w,"invalid model",400);return}
  if m.PriceState=="" {m.PriceState="UNKNOWN"}
  if m.PriceState!="UNKNOWN"&&m.PriceState!="FREE_NOW"&&m.PriceState!="PAID" {http.Error(w,"invalid price state",400);return}
  // Free now is not recurring free; recurring claims require future evidence verification.
  if m.RecurringFree {http.Error(w,"recurring free requires verified evidence",422);return}
  db.mu.Lock();defer db.mu.Unlock()
  if _,ok:=db.providers[m.ProviderID];!ok {http.Error(w,"provider missing",422);return}
  key:=m.ProviderID+"\x00"+m.ID
  if _,exists:=db.models[key];exists {http.Error(w,"model exists",409);return}
  db.models[key]=m;respond(w,http.StatusCreated,m)
 })
 return mux
}
func validID(s string) bool {if len(s)==0||len(s)>128{return false};for _,c:=range s {if !(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-'||c=='_'||c=='.'||c=='/'||c==':'){return false}};return true}
func decode(r *http.Request,v any) error {
 if r.ContentLength>65536 {return errors.New("oversize")}
 d:=json.NewDecoder(io.LimitReader(r.Body,65537));d.DisallowUnknownFields()
 if err:=d.Decode(v);err!=nil{return err}
 var extra any;if err:=d.Decode(&extra);err!=io.EOF{return errors.New("trailing JSON")}
 return nil
}
func respond(w http.ResponseWriter,code int,v any){w.Header().Set("Content-Type","application/json");w.Header().Set("Cache-Control","no-store");w.WriteHeader(code);_ = json.NewEncoder(w).Encode(v)}
func secureServer(addr string,h http.Handler)*http.Server{return &http.Server{Addr:addr,Handler:h,ReadHeaderTimeout:5*time.Second,ReadTimeout:15*time.Second,WriteTimeout:30*time.Second,IdleTimeout:30*time.Second,MaxHeaderBytes:1<<16}}
