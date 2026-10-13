package main

import (
 "context"
 "database/sql"
 "encoding/json"
 "io"
 "net/http"
 "strings"
 "time"
)

type Provider struct { ID string `json:"id"`; Name string `json:"name"`; BaseURL string `json:"base_url"`; Description string `json:"description"`; AuthMode string `json:"auth_mode"` }
type Account struct { ID string `json:"id"`; ProviderID string `json:"provider_id"`; Label string `json:"label"` }
type Credential struct { ID string `json:"id"`; ProviderID string `json:"provider_id"`; AccountID string `json:"account_id"`; Label string `json:"label"`; Secret string `json:"secret,omitempty"`; Enabled bool `json:"enabled"` }
type Model struct { ProviderID string `json:"provider_id"`; ID string `json:"id"`; DisplayName string `json:"display_name"`; PriceState string `json:"price_state"`; QuotaState string `json:"quota_state"`; Provenance string `json:"provenance"` }
type Selection struct { ProviderID string `json:"provider_id"`; AccountID string `json:"account_id"`; CredentialID string `json:"credential_id"`; ModelID string `json:"model_id"`; Group string `json:"group"`; Endpoint string `json:"endpoint"` }

func (s *Server) listProviders(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT id,name,base_url,description,auth_mode FROM providers ORDER BY name,id");if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 a:=[]Provider{};for rows.Next(){var p Provider;if rows.Scan(&p.ID,&p.Name,&p.BaseURL,&p.Description,&p.AuthMode)==nil{a=append(a,p)}};jsonOK(w,200,a)
}
func (s *Server) addProvider(w http.ResponseWriter,r *http.Request){
 var p Provider;if !decodeJSON(w,r,&p){return}
 if !validID(p.ID)||len(p.Name)<1||len(p.Name)>120||!validateHTTPS(p.BaseURL)||len(p.Description)>1000 {http.Error(w,"invalid provider fields",400);return}
 if p.AuthMode=="" {p.AuthMode="BEARER"}
 if p.AuthMode!="BEARER"&&p.AuthMode!="NONE" {http.Error(w,"unsupported auth mode",400);return}
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO providers(id,name,base_url,description,auth_mode,created_at) VALUES(?,?,?,?,?,?)",p.ID,p.Name,p.BaseURL,p.Description,p.AuthMode,now())
 if err!=nil{http.Error(w,"provider conflict or database error",409);return};jsonOK(w,201,p)
}
func (s *Server) listAccounts(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT id,provider_id,label FROM accounts ORDER BY provider_id,id");if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 a:=[]Account{};for rows.Next(){var v Account;if rows.Scan(&v.ID,&v.ProviderID,&v.Label)==nil{a=append(a,v)}};jsonOK(w,200,a)
}
func (s *Server) addAccount(w http.ResponseWriter,r *http.Request){
 var a Account;if !decodeJSON(w,r,&a){return}
 if !validID(a.ID)||!validID(a.ProviderID)||len(a.Label)>120 {http.Error(w,"invalid account",400);return}
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO accounts(id,provider_id,label) VALUES(?,?,?)",a.ID,a.ProviderID,a.Label)
 if err!=nil {http.Error(w,"missing provider or duplicate account",409);return};jsonOK(w,201,a)
}
func (s *Server) listCredentials(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT id,provider_id,account_id,label,enabled FROM credentials ORDER BY provider_id,account_id,id");if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 a:=[]Credential{};for rows.Next(){var v Credential;var enabled int;if rows.Scan(&v.ID,&v.ProviderID,&v.AccountID,&v.Label,&enabled)==nil{v.Enabled=enabled==1;a=append(a,v)}};jsonOK(w,200,a)
}
func (s *Server) addCredential(w http.ResponseWriter,r *http.Request){
 var v Credential;if !decodeJSON(w,r,&v){return}
 if !validID(v.ID)||!validID(v.ProviderID)||!validID(v.AccountID)||len(v.Label)>120||len(v.Secret)>8192 {http.Error(w,"invalid credential",400);return}
 var mode string
 if s.Store.DB.QueryRowContext(r.Context(),"SELECT p.auth_mode FROM providers p JOIN accounts a ON a.provider_id=p.id WHERE a.provider_id=? AND a.id=?",v.ProviderID,v.AccountID).Scan(&mode)!=nil {http.Error(w,"missing account",422);return}
 enc:=""
 if mode=="BEARER" {
  if v.Secret=="" {http.Error(w,"credential secret required",400);return}
  var err error;enc,err=s.encrypt(v.Secret);if err!=nil{http.Error(w,"master encryption key required",503);return}
 } else if v.Secret!="" {http.Error(w,"anonymous provider cannot receive secret",400);return}
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO credentials(id,provider_id,account_id,label,encrypted_key,enabled,created_at) VALUES(?,?,?,?,?,1,?)",v.ID,v.ProviderID,v.AccountID,v.Label,enc,now())
 if err!=nil{http.Error(w,"duplicate credential or invalid scope",409);return}
 v.Secret="";v.Enabled=true;jsonOK(w,201,v)
}
func (s *Server) listModels(w http.ResponseWriter,r *http.Request){
 filter:=r.URL.Query().Get("provider_id")
 q:="SELECT provider_id,id,display_name,price_state,quota_state,provenance FROM models"
 var args []any
 if filter!="" {q+=" WHERE provider_id=?";args=append(args,filter)}
 q+=" ORDER BY provider_id,id"
 rows,err:=s.Store.DB.QueryContext(r.Context(),q,args...);if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 a:=[]Model{};for rows.Next(){var m Model;if rows.Scan(&m.ProviderID,&m.ID,&m.DisplayName,&m.PriceState,&m.QuotaState,&m.Provenance)==nil{a=append(a,m)}};jsonOK(w,200,a)
}
func (s *Server) addModel(w http.ResponseWriter,r *http.Request){
 var m Model;if !decodeJSON(w,r,&m){return}
 if !validID(m.ProviderID)||!validID(m.ID)||len(m.DisplayName)<1||len(m.DisplayName)>200{http.Error(w,"invalid model",400);return}
 if m.PriceState==""{m.PriceState="UNKNOWN"}
 if m.QuotaState==""{m.QuotaState="UNKNOWN"}
 if m.PriceState!="UNKNOWN"||m.QuotaState!="UNKNOWN" {http.Error(w,"manual price/quota claims need verified evidence",422);return}
 m.Provenance="MANUAL"
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO models(provider_id,id,display_name,price_state,quota_state,provenance,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(provider_id,id) DO UPDATE SET display_name=excluded.display_name,updated_at=excluded.updated_at WHERE models.provenance='MANUAL'",m.ProviderID,m.ID,m.DisplayName,m.PriceState,m.QuotaState,m.Provenance,now())
 if err!=nil{http.Error(w,"missing provider or database error",409);return};jsonOK(w,201,m)
}
func (s *Server) listMyModels(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM my_models ORDER BY created_at DESC");if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 a:=[]Selection{};for rows.Next(){var v Selection;if rows.Scan(&v.ProviderID,&v.AccountID,&v.CredentialID,&v.ModelID,&v.Group,&v.Endpoint)==nil{a=append(a,v)}};jsonOK(w,200,a)
}
func (s *Server) validateSelection(ctx context.Context,v Selection) bool {
 if !validID(v.ProviderID)||!validID(v.AccountID)||!validID(v.CredentialID)||!validID(v.ModelID)||len(v.Group)>120||v.Endpoint!="CHAT_COMPLETIONS" {return false}
 var n int
 err:=s.Store.DB.QueryRowContext(ctx,"SELECT 1 FROM my_models m JOIN credentials c ON c.provider_id=m.provider_id AND c.account_id=m.account_id AND c.id=m.credential_id AND c.enabled=1 WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=? AND m.model_id=? AND m.group_id=? AND m.endpoint=?",v.ProviderID,v.AccountID,v.CredentialID,v.ModelID,v.Group,v.Endpoint).Scan(&n)
 return err==nil&&n==1
}
func (s *Server) addMyModel(w http.ResponseWriter,r *http.Request){
 var v Selection;if !decodeJSON(w,r,&v){return}
 if !validID(v.ProviderID)||!validID(v.AccountID)||!validID(v.CredentialID)||!validID(v.ModelID)||len(v.Group)>120 {http.Error(w,"invalid selection",400);return}
 if v.Endpoint==""{v.Endpoint="CHAT_COMPLETIONS"}
 if v.Endpoint!="CHAT_COMPLETIONS" {http.Error(w,"endpoint unsupported",422);return}
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO my_models(provider_id,account_id,credential_id,model_id,group_id,endpoint,created_at) VALUES(?,?,?,?,?,?,?)",v.ProviderID,v.AccountID,v.CredentialID,v.ModelID,v.Group,v.Endpoint,now())
 if err!=nil{http.Error(w,"scope missing or membership exists",409);return};jsonOK(w,201,v)
}
func (s *Server) removeMyModel(w http.ResponseWriter,r *http.Request){
 var v Selection;if !decodeJSON(w,r,&v){return}
 result,err:=s.Store.DB.ExecContext(r.Context(),"DELETE FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?",v.ProviderID,v.AccountID,v.CredentialID,v.ModelID,v.Group,v.Endpoint)
 if err!=nil{http.Error(w,"database error",500);return}
 n,_:=result.RowsAffected();if n==0{http.Error(w,"missing membership",404);return}
 jsonOK(w,200,map[string]any{"removed":true,"history_preserved":true})
}
type syncRequest struct { ProviderID string `json:"provider_id"`; AccountID string `json:"account_id"`; CredentialID string `json:"credential_id"` }
type upstreamModels struct { Data []struct {ID string `json:"id"`} `json:"data"` }
func (s *Server) syncModels(w http.ResponseWriter,r *http.Request){
 var v syncRequest;if !decodeJSON(w,r,&v){return}
 p,key,err:=s.endpointAuth(r.Context(),v.ProviderID,v.AccountID,v.CredentialID)
 if err!=nil{http.Error(w,"invalid provider/account/credential scope",422);return}
 url:=strings.TrimRight(p.BaseURL,"/")+"/models"
 req,err:=http.NewRequestWithContext(r.Context(),"GET",url,nil);if err!=nil{http.Error(w,"invalid endpoint",422);return}
 if key!=""{req.Header.Set("Authorization","Bearer "+key)}
 resp,err:=s.Client.Do(req)
 if err!=nil{http.Error(w,"provider sync failed",502);return}
 defer resp.Body.Close()
 if resp.StatusCode!=200 {http.Error(w,"provider sync failed",502);return}
 body,err:=io.ReadAll(io.LimitReader(resp.Body,2<<20));if err!=nil{http.Error(w,"provider sync failed",502);return}
 var result upstreamModels
 if json.Unmarshal(body,&result)!=nil||result.Data==nil {http.Error(w,"invalid provider catalog",502);return}
 tx,err:=s.Store.DB.BeginTx(r.Context(),nil);if err!=nil{http.Error(w,"database error",500);return};defer tx.Rollback()
 count:=0
 for _,model:=range result.Data {
  if !validID(model.ID){continue}
  if _,err=tx.ExecContext(r.Context(),"INSERT INTO models(provider_id,id,display_name,price_state,quota_state,provenance,updated_at) VALUES(?,?,?,'UNKNOWN','UNKNOWN','PROVIDER_CATALOG',?) ON CONFLICT(provider_id,id) DO UPDATE SET display_name=excluded.display_name,provenance=excluded.provenance,updated_at=excluded.updated_at",v.ProviderID,model.ID,model.ID,now());err!=nil{http.Error(w,"database error",500);return};count++
 }
 if err=tx.Commit();err!=nil{http.Error(w,"database error",500);return}
 jsonOK(w,200,map[string]any{"models_seen":count,"my_models_unchanged":true})
}
func (s *Server) endpointAuth(ctx context.Context,provider,account,credential string)(Provider,string,error){
 var p Provider;var enc string;var enabled int
 err:=s.Store.DB.QueryRowContext(ctx,"SELECT p.id,p.name,p.base_url,p.description,p.auth_mode,c.encrypted_key,c.enabled FROM providers p JOIN accounts a ON a.provider_id=p.id JOIN credentials c ON c.provider_id=a.provider_id AND c.account_id=a.id WHERE p.id=? AND a.id=? AND c.id=?",provider,account,credential).Scan(&p.ID,&p.Name,&p.BaseURL,&p.Description,&p.AuthMode,&enc,&enabled)
 if err!=nil||enabled!=1 {return Provider{},"",sql.ErrNoRows}
 if !validateHTTPS(p.BaseURL){return Provider{},"",sql.ErrNoRows}
 if p.AuthMode=="NONE" {return p,"",nil}
 key,err:=s.decrypt(enc);if err!=nil{return Provider{},"",err}
 return p,key,nil
}
var _ = time.Second
