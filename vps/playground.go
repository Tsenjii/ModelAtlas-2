package main

import (
 "bufio"
 "bytes"
 "context"
 "database/sql"
 "encoding/json"
 "errors"
 "fmt"
 "io"
 "net/http"
 "strings"
 "time"
 "crypto/rand"
 "encoding/hex"
)

type Session struct {ID string `json:"id"`; Selection; Title string `json:"title"`; CreatedAt int64 `json:"created_at"` }
type Message struct {ID string `json:"id"`; SessionID string `json:"session_id"`; Role string `json:"role"`; Content string `json:"content"`; CreatedAt int64 `json:"created_at"` }
func newID()string {var v [16]byte;_,_=rand.Read(v[:]);return hex.EncodeToString(v[:])}
func (s *Server) listSessions(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at FROM sessions ORDER BY created_at DESC LIMIT 100");if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 out:=[]Session{};for rows.Next(){var x Session;if rows.Scan(&x.ID,&x.ProviderID,&x.AccountID,&x.CredentialID,&x.ModelID,&x.Group,&x.Endpoint,&x.Title,&x.CreatedAt)==nil{out=append(out,x)}};jsonOK(w,200,out)
}
func (s *Server) addSession(w http.ResponseWriter,r *http.Request){
 var x Session;if !decodeJSON(w,r,&x){return}
 if x.Endpoint==""{x.Endpoint="CHAT_COMPLETIONS"}
 if !s.validateSelection(r.Context(),x.Selection){http.Error(w,"model/credential/scope not explicitly selected",422);return}
 if len(x.Title)>200{http.Error(w,"title too long",400);return}
 x.ID=newID();x.CreatedAt=now()
 _,err:=s.Store.DB.ExecContext(r.Context(),"INSERT INTO sessions(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at) VALUES(?,?,?,?,?,?,?,?,?)",x.ID,x.ProviderID,x.AccountID,x.CredentialID,x.ModelID,x.Group,x.Endpoint,x.Title,x.CreatedAt)
 if err!=nil{http.Error(w,"database error",500);return};jsonOK(w,201,x)
}
func (s *Server) listMessages(w http.ResponseWriter,r *http.Request){
 id:=r.URL.Query().Get("session_id");if !validID(id){http.Error(w,"session_id required",400);return}
 var n int;if s.Store.DB.QueryRowContext(r.Context(),"SELECT 1 FROM sessions WHERE id=?",id).Scan(&n)!=nil {http.Error(w,"session not found",404);return}
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT id,session_id,role,content,created_at FROM messages WHERE session_id=? ORDER BY created_at,id LIMIT 250",id);if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 out:=[]Message{};for rows.Next(){var x Message;if rows.Scan(&x.ID,&x.SessionID,&x.Role,&x.Content,&x.CreatedAt)==nil{out=append(out,x)}};jsonOK(w,200,out)
}
type inferRequest struct {SessionID string `json:"session_id"`; Prompt string `json:"prompt"` }
type chatMessage struct {Role string `json:"role"`; Content string `json:"content"` }
type chatResponse struct {
 Choices []struct{ Delta struct{Content string `json:"content"`} `json:"delta"`; Message struct{Content string `json:"content"`} `json:"message"` } `json:"choices"`
 Usage struct{PromptTokens int64 `json:"prompt_tokens"`; CompletionTokens int64 `json:"completion_tokens"`} `json:"usage"`
}
func (s *Server) infer(w http.ResponseWriter,r *http.Request){
 var v inferRequest;if !decodeJSON(w,r,&v){return}
 v.Prompt=strings.TrimSpace(v.Prompt)
 if !validID(v.SessionID)||v.Prompt==""||len(v.Prompt)>32000{http.Error(w,"invalid prompt or session",400);return}
 var scope Selection
 err:=s.Store.DB.QueryRowContext(r.Context(),"SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM sessions WHERE id=?",v.SessionID).Scan(&scope.ProviderID,&scope.AccountID,&scope.CredentialID,&scope.ModelID,&scope.Group,&scope.Endpoint)
 if err!=nil{http.Error(w,"session not found",404);return}
 if !s.validateSelection(r.Context(),scope){http.Error(w,"selected model or credential was removed",422);return}
 provider,key,err:=s.endpointAuth(r.Context(),scope.ProviderID,scope.AccountID,scope.CredentialID)
 if err!=nil{http.Error(w,"credential invalid",422);return}
 // We do not map group to an invented upstream header. Model group is an exact local scope.
 // A provider-specific group adapter must be explicitly implemented before it can send a grouped request.
 if scope.Group!="" {http.Error(w,"group adapter unavailable; refusing to send outside selected group",422);return}
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT role,content FROM messages WHERE session_id=? ORDER BY created_at,id LIMIT 100",v.SessionID)
 if err!=nil{http.Error(w,"database error",500);return}
 messages:=[]chatMessage{};for rows.Next(){var x chatMessage;if rows.Scan(&x.Role,&x.Content)==nil {if x.Role=="user"||x.Role=="assistant"{messages=append(messages,x)}}};rows.Close()
 messages=append(messages,chatMessage{"user",v.Prompt})
 payload:=map[string]any{"model":scope.ModelID,"stream":true,"stream_options":map[string]bool{"include_usage":true},"messages":messages}
 body,_:=json.Marshal(payload)
 endpoint:=strings.TrimRight(provider.BaseURL,"/")
 if !strings.HasSuffix(endpoint,"/chat/completions"){endpoint+="/chat/completions"}
 upstream,err:=http.NewRequestWithContext(r.Context(),http.MethodPost,endpoint,bytes.NewReader(body))
 if err!=nil{http.Error(w,"endpoint invalid",422);return}
 upstream.Header.Set("Content-Type","application/json");upstream.Header.Set("Accept","text/event-stream")
 if key!=""{upstream.Header.Set("Authorization","Bearer "+key)}
 // Retain the human turn even if upstream fails; no silent loss of conversation evidence.
 if _,err=s.Store.DB.ExecContext(r.Context(),"INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",newID(),v.SessionID,"user",v.Prompt,now());err!=nil{http.Error(w,"database error",500);return}
 started:=time.Now();outcome:="NETWORK";status:=0
 var ttfb,ttft,inputs,outputs any
 var text strings.Builder
 defer func(){
  _,_=s.Store.DB.ExecContext(context.Background(),"INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,status_code,ttfb_ms,ttft_ms,latency_ms,input_tokens,output_tokens,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",newID(),scope.ProviderID,scope.AccountID,scope.CredentialID,scope.ModelID,scope.Group,scope.Endpoint,scope.Endpoint,"PLAYGROUND",outcome,status,ttfb,ttft,time.Since(started).Milliseconds(),inputs,outputs,now())
 }()
 resp,err:=s.Client.Do(upstream)
 if err!=nil{if errors.Is(r.Context().Err(),context.Canceled){outcome="CANCELLED"};http.Error(w,"inference network failure",502);return}
 defer resp.Body.Close();status=resp.StatusCode;ttfb=time.Since(started).Milliseconds()
 if status!=200 {outcome="HTTP_ERROR";http.Error(w,"upstream unavailable",502);return}
 flusher,ok:=w.(http.Flusher);if !ok{outcome="PARSER";http.Error(w,"streaming unsupported",500);return}
 w.Header().Set("Content-Type","text/event-stream; charset=utf-8")
 w.Header().Set("Cache-Control","no-store");w.Header().Set("X-Accel-Buffering","no")
 w.WriteHeader(200)
 reader:=bufio.NewScanner(io.LimitReader(resp.Body,16<<20));reader.Buffer(make([]byte,4096),1<<20)
 done:=false
 for reader.Scan() {
  if r.Context().Err()!=nil{outcome="CANCELLED";return}
  line:=strings.TrimSpace(reader.Text())
  if !strings.HasPrefix(line,"data:"){continue}
  raw:=strings.TrimSpace(strings.TrimPrefix(line,"data:"))
  if raw=="[DONE]"{done=true;break}
  var event chatResponse
  if json.Unmarshal([]byte(raw),&event)!=nil{outcome="PARSER";sendSSE(w,flusher,"error",map[string]string{"category":"PARSER"});return}
  for _,choice:=range event.Choices {
   part:=choice.Delta.Content
   if part=="" {continue}
   if ttft==nil{ttft=time.Since(started).Milliseconds()}
   if text.Len()+len(part)>256<<10 {outcome="PARSER";sendSSE(w,flusher,"error",map[string]string{"category":"RESPONSE_LIMIT"});return}
   text.WriteString(part);sendSSE(w,flusher,"delta",map[string]string{"text":part})
  }
  if event.Usage.PromptTokens>0 {inputs=event.Usage.PromptTokens}
  if event.Usage.CompletionTokens>0{outputs=event.Usage.CompletionTokens}
 }
 if reader.Err()!=nil||!done{outcome="PARSER";sendSSE(w,flusher,"error",map[string]string{"category":"TRUNCATED_STREAM"});return}
 if _,err=s.Store.DB.ExecContext(r.Context(),"INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",newID(),v.SessionID,"assistant",text.String(),now());err!=nil{outcome="DB_ERROR";sendSSE(w,flusher,"error",map[string]string{"category":"HISTORY_SAVE"});return}
 outcome="SUCCESS";sendSSE(w,flusher,"done",map[string]any{"ok":true})
}
func sendSSE(w http.ResponseWriter,f http.Flusher,name string,data any) {
 b,_:=json.Marshal(data)
 _,_=fmt.Fprintf(w,"event: %s\ndata: %s\n\n",name,b);f.Flush()
}
func (s *Server) listTelemetry(w http.ResponseWriter,r *http.Request){
 rows,err:=s.Store.DB.QueryContext(r.Context(),"SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,status_code,ttfb_ms,ttft_ms,latency_ms,input_tokens,output_tokens,created_at FROM telemetry ORDER BY created_at DESC LIMIT 200")
 if err!=nil{http.Error(w,"database error",500);return};defer rows.Close()
 out:=[]map[string]any{}
 for rows.Next(){var p,a,c,m,g,e,o,src,result string;var status,ttfb,ttft,lat,in,outTokens sql.NullInt64;var at int64
  if rows.Scan(&p,&a,&c,&m,&g,&e,&o,&src,&result,&status,&ttfb,&ttft,&lat,&in,&outTokens,&at)==nil{
   obj:=map[string]any{"provider_id":p,"account_id":a,"credential_id":c,"model_id":m,"group":g,"endpoint":e,"operation":o,"source":src,"outcome":result,"created_at":at}
   if status.Valid{obj["http_status"]=status.Int64};if ttfb.Valid{obj["ttfb_ms"]=ttfb.Int64};if ttft.Valid{obj["ttft_ms"]=ttft.Int64};if lat.Valid{obj["latency_ms"]=lat.Int64};if in.Valid{obj["input_tokens"]=in.Int64};if outTokens.Valid{obj["output_tokens"]=outTokens.Int64}
   out=append(out,obj)
  }
 };jsonOK(w,200,out)
}
