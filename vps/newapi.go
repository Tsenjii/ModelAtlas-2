package main

import (
 "context"
 "database/sql"
 "encoding/json"
 "errors"
 "io"
 "net/http"
 "strings"
)

// Read-only New API v1.0.0-rc.25 adapter follows Android NewApiAdapter:
// /api/status is anonymous, /api/{pricing,user/self} needs Account PAT,
// /api/usage/token/ needs inference credential. Roles are never exchanged.
type NewApiQuery struct {ProviderID string `json:"provider_id"`;AccountID string `json:"account_id"`;CredentialID string `json:"credential_id"`}
type NewApiResult struct {Version string `json:"version"`;Endpoint string `json:"endpoint"`;Facts map[string]any `json:"facts"`;ObservedAt int64 `json:"observed_at"`;Source string `json:"source"`}
func (s *Server) accountPAT(ctx context.Context,query NewApiQuery)(Provider,string,error){
 var p Provider;var encrypted string;var enabled int
 err:=s.Store.DB.QueryRowContext(ctx,"SELECT p.id,p.name,p.base_url,p.description,p.auth_mode,c.encrypted_key,c.enabled FROM providers p JOIN accounts a ON a.provider_id=p.id JOIN credentials c ON c.provider_id=a.provider_id AND c.account_id=a.id WHERE p.id=? AND a.id=? AND c.id=? AND c.role='ACCOUNT_PAT'",query.ProviderID,query.AccountID,query.CredentialID).Scan(&p.ID,&p.Name,&p.BaseURL,&p.Description,&p.AuthMode,&encrypted,&enabled)
 if err!=nil||enabled!=1||!validateHTTPS(p.BaseURL){return Provider{},"",sql.ErrNoRows}
 key,err:=s.decrypt(encrypted);if err!=nil{return Provider{},"",err}
 return p,key,nil
}
func (s *Server) newApiGet(ctx context.Context,p Provider,path,token string)(map[string]json.RawMessage,error) {
 base:=strings.TrimSuffix(strings.TrimRight(p.BaseURL,"/"),"/v1")
 // Reject configured non-standard URL path; do not send PAT to a different origin.
 req,err:=http.NewRequestWithContext(ctx,"GET",base+path,nil)
 if err!=nil{return nil,err}
 if token!=""{req.Header.Set("Authorization","Bearer "+token)}
 resp,err:=s.Client.Do(req);if err!=nil{return nil,err}
 defer resp.Body.Close()
 if resp.StatusCode!=200{return nil,errors.New("non-200 New API response")}
 raw,err:=io.ReadAll(io.LimitReader(resp.Body,2<<20));if err!=nil{return nil,err}
 if len(raw)>=2<<20{return nil,errors.New("New API response too large")}
 var data map[string]json.RawMessage
 if json.Unmarshal(raw,&data)!=nil{return nil,errors.New("malformed New API envelope")}
 if string(data["success"])!="true"{return nil,errors.New("upstream success=false")}
 return data,nil
}
func getNewAPIData(body map[string]json.RawMessage)(map[string]json.RawMessage,error){
 var data map[string]json.RawMessage
 if err:=json.Unmarshal(body["data"],&data);err!=nil||data==nil{return nil,errors.New("invalid New API data")}
 return data,nil
}
func stringField(obj map[string]json.RawMessage,key string)string{
 var v string
 if json.Unmarshal(obj[key],&v)!=nil||len(v)>256{return ""}
 return v
}
func scalarField(obj map[string]json.RawMessage,key string)any{
 raw:=obj[key];if len(raw)==0{return nil}
 var v any
 dec:=json.NewDecoder(strings.NewReader(string(raw)));dec.UseNumber()
 if dec.Decode(&v)!=nil{return nil}
 switch v.(type){case json.Number,bool:return v;default:return nil}
}
func (s *Server) newApiStatus(ctx context.Context,p Provider)(map[string]any,string,error){
 body,err:=s.newApiGet(ctx,p,"/api/status","");if err!=nil{return nil,"UNKNOWN",err}
 data,err:=getNewAPIData(body);if err!=nil{return nil,"UNKNOWN",err}
 version:=stringField(data,"version");if version==""{version="UNKNOWN"}
 facts:=map[string]any{"version":version}
 for _,field:=range []string{"quota_per_unit","checkin_enabled"}{if v:=scalarField(data,field);v!=nil{facts[field]=v}}
 return facts,version,nil
}
func (s *Server) newApiSnapshot(ctx context.Context,q NewApiQuery,endpoint,role,version string,facts map[string]any)error{
 raw,err:=json.Marshal(facts);if err!=nil{return err}
 if len(raw)>128<<10{return errors.New("facts too large")}
 _,err=s.Store.DB.ExecContext(ctx,"INSERT INTO newapi_snapshots(id,provider_id,account_id,credential_id,credential_role,endpoint,facts_json,provider_version,source,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",newID(),q.ProviderID,q.AccountID,q.CredentialID,role,endpoint,string(raw),version,"NEW_API_OFFICIAL",now())
 return err
}
func (s *Server) newApiQuery(w http.ResponseWriter,r *http.Request,path string){
 var q NewApiQuery;if !decodeJSON(w,r,&q){return}
 if !validID(q.ProviderID){http.Error(w,"provider ID required",400);return}
 var p Provider
 var token,role string
 if path=="/api/status" {
  if s.Store.DB.QueryRowContext(r.Context(),"SELECT id,name,base_url,description,auth_mode FROM providers WHERE id=?",q.ProviderID).Scan(&p.ID,&p.Name,&p.BaseURL,&p.Description,&p.AuthMode)!=nil{http.Error(w,"provider not found",404);return}
  role="ANONYMOUS";q.AccountID="";q.CredentialID=""
 } else {
  if !validID(q.AccountID)||!validID(q.CredentialID){http.Error(w,"explicit account/credential required",400);return}
  var err error
  if path=="/api/usage/token/" {p,token,err=s.endpointAuth(r.Context(),q.ProviderID,q.AccountID,q.CredentialID);role="INFERENCE"}else{p,token,err=s.accountPAT(r.Context(),q);role="ACCOUNT_PAT"}
  if err!=nil{http.Error(w,"credential role/scope unavailable",422);return}
 }
 facts,version,err:=s.newApiStatus(r.Context(),p)
 if err!=nil{http.Error(w,"New API status unavailable",502);return}
 if path!="/api/status" {
  if version!="v1.0.0-rc.25"{http.Error(w,"New API version not certified",422);return}
  root,err:=s.newApiGet(r.Context(),p,path,token)
  if err!=nil{http.Error(w,"New API endpoint failed",502);return}
  facts=map[string]any{}
  if path=="/api/pricing"{
   var entries []json.RawMessage
   if json.Unmarshal(root["data"],&entries)!=nil{http.Error(w,"malformed pricing",502);return}
   facts["pricing_items_count"]=len(entries)
   if version:=stringField(root,"pricing_version");version!=""{facts["pricing_version"]=version}
  } else {
   data,err:=getNewAPIData(root);if err!=nil{http.Error(w,"malformed quota",502);return}
   fields:=[]string{"quota","used_quota","request_count"}
   if path=="/api/usage/token/"{fields=[]string{"total_granted","total_used","total_available","unlimited_quota","model_limits_enabled","expires_at"}}
   for _,field:=range fields{if v:=scalarField(data,field);v!=nil{facts[field]=v}}
  }
 }
 if err=s.newApiSnapshot(r.Context(),q,path,role,version,facts);err!=nil{http.Error(w,"snapshot persistence failed",500);return}
 jsonOK(w,200,NewApiResult{Version:version,Endpoint:path,Facts:facts,ObservedAt:now(),Source:"NEW_API_OFFICIAL"})
}
func (s *Server) listNewApiSnapshots(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT provider_id,account_id,credential_id,credential_role,endpoint,facts_json,provider_version,observed_at FROM newapi_snapshots ORDER BY observed_at DESC LIMIT 100")
 if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 out:=[]map[string]any{}
 for rows.Next(){var p,a,c,role,endpoint,raw,version string;var at int64
  if rows.Scan(&p,&a,&c,&role,&endpoint,&raw,&version,&at)!=nil{continue}
  var facts map[string]any;if json.Unmarshal([]byte(raw),&facts)!=nil{continue}
  out=append(out,map[string]any{"provider_id":p,"account_id":a,"credential_id":c,"credential_role":role,"endpoint":endpoint,"facts":facts,"version":version,"observed_at":at,"source":"NEW_API_OFFICIAL"})
 }
 jsonOK(w,200,out)
}
func (s *Server) newApiStatusHTTP(w http.ResponseWriter,r *http.Request){s.newApiQuery(w,r,"/api/status")}
func (s *Server) newApiPricingHTTP(w http.ResponseWriter,r *http.Request){s.newApiQuery(w,r,"/api/pricing")}
func (s *Server) newApiAccountHTTP(w http.ResponseWriter,r *http.Request){s.newApiQuery(w,r,"/api/user/self")}
func (s *Server) newApiKeyHTTP(w http.ResponseWriter,r *http.Request){s.newApiQuery(w,r,"/api/usage/token/")}
