package main

import (
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

func TestRealInferenceHTTPAndHistory(t *testing.T){
 upstream:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  if r.URL.Path!="/v1/chat/completions"{t.Errorf("wrong HTTP route %s",r.URL.Path);http.Error(w,"bad route",404);return}
  if r.Header.Get("Authorization")!="Bearer top-secret-example-key"{t.Error("selected credential not forwarded")}
  var body struct{Model string `json:"model"`;Messages []struct{Role string `json:"role"`;Content string `json:"content"`} `json:"messages"` }
  if json.NewDecoder(r.Body).Decode(&body)!=nil||body.Model!="m"||len(body.Messages)!=1||body.Messages[0].Content!="Hello"{t.Error("inference body mismatch")}
  w.Header().Set("Content-Type","text/event-stream")
  _,_=w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}\n\n"))
  _,_=w.Write([]byte("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}\n\n"))
  _,_=w.Write([]byte("data: [DONE]\n\n"))
 }))
 defer upstream.Close()
 s:=testServer(t);s.Client=upstream.Client()
 v:=seed(t,s,upstream.URL+"/v1")
 requireCode(t,req(t,s,"POST","/api/v1/my-models",v),201)
 session:=Session{Selection:v,Title:"Integration"}
 res:=req(t,s,"POST","/api/v1/sessions",session);requireCode(t,res,201)
 var saved Session;if json.Unmarshal(res.Body.Bytes(),&saved)!=nil{t.Fatal("invalid session")}
 result:=req(t,s,"POST","/api/v1/infer",map[string]any{"session_id":saved.ID,"prompt":"Hello"})
 requireCode(t,result,200)
 if !strings.Contains(result.Body.String(),"event: delta")||!strings.Contains(result.Body.String(),"reply")||!strings.Contains(result.Body.String(),"event: done"){t.Fatalf("missing SSE: %s",result.Body.String())}
 var history []Message
 if json.Unmarshal(req(t,s,"GET","/api/v1/messages?session_id="+saved.ID,nil).Body.Bytes(),&history)!=nil||len(history)!=2||history[1].Content!="reply"{t.Fatalf("unexpected history: %+v",history)}
 var outcome string;var input,output int
 if err:=s.Store.DB.QueryRow("SELECT outcome,input_tokens,output_tokens FROM telemetry ORDER BY created_at DESC LIMIT 1").Scan(&outcome,&input,&output);err!=nil||outcome!="SUCCESS"||input!=5||output!=3{t.Fatalf("telemetry mismatch: %s %d %d %v",outcome,input,output,err)}
}
func TestTruncatedInferenceNeverCountsSuccess(t *testing.T){
 upstream:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  _,_=w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
 }))
 defer upstream.Close()
 s:=testServer(t);s.Client=upstream.Client()
 v:=seed(t,s,upstream.URL+"/v1")
 requireCode(t,req(t,s,"POST","/api/v1/my-models",v),201)
 created:=req(t,s,"POST","/api/v1/sessions",Session{Selection:v,Title:"Truncated"});requireCode(t,created,201)
 var session Session;_ = json.Unmarshal(created.Body.Bytes(),&session)
 r:=req(t,s,"POST","/api/v1/infer",map[string]string{"session_id":session.ID,"prompt":"hello"})
 requireCode(t,r,200)
 if !strings.Contains(r.Body.String(),"TRUNCATED_STREAM"){t.Fatal("missing error event")}
 var outcome string;_ = s.Store.DB.QueryRow("SELECT outcome FROM telemetry ORDER BY created_at DESC LIMIT 1").Scan(&outcome)
 if outcome!="PARSER"{t.Fatalf("truncated stream recorded as %s",outcome)}
 var count int;_ = s.Store.DB.QueryRow("SELECT count(*) FROM messages WHERE session_id=? AND role='assistant'",session.ID).Scan(&count)
 if count!=0{t.Fatal("partial assistant turn was committed as full response")}
}
func TestAssistantConfirmationUndo(t *testing.T){
 s:=testServer(t);v:=seed(t,s,"https://provider.example/v1")
 preview:=req(t,s,"POST","/api/v1/assistant/preview",AssistantAction{Action:"ADD_MY_MODEL",Selection:v})
 requireCode(t,preview,201);var a AssistantAction;_ = json.Unmarshal(preview.Body.Bytes(),&a)
 if s.validateSelection(httptest.NewRequest("GET","/",nil).Context(),v){t.Fatal("preview executed unconfirmed action")}
 requireCode(t,req(t,s,"POST","/api/v1/assistant/confirm",map[string]any{"id":a.ID,"confirm":false}),400)
 requireCode(t,req(t,s,"POST","/api/v1/assistant/confirm",map[string]any{"id":a.ID,"confirm":true}),200)
 if !s.validateSelection(httptest.NewRequest("GET","/",nil).Context(),v){t.Fatal("confirmed membership missing")}
 requireCode(t,req(t,s,"POST","/api/v1/assistant/undo",map[string]any{"id":a.ID,"confirm":true}),200)
 if s.validateSelection(httptest.NewRequest("GET","/",nil).Context(),v){t.Fatal("undo did not revert membership")}
 requireCode(t,req(t,s,"POST","/api/v1/assistant/undo",map[string]any{"id":a.ID,"confirm":true}),409)
}
func TestRecipeVersionedRollback(t *testing.T){
 s:=testServer(t);seed(t,s,"https://provider.example/v1")
 v:=map[string]any{"provider_id":"p","settings":map[string]any{"catalog_path":"/v1/models"},"reason":"manual correction"}
 requireCode(t,req(t,s,"POST","/api/v1/recipes",v),201)
 v["settings"]=map[string]any{"catalog_path":"/models"}
 requireCode(t,req(t,s,"POST","/api/v1/recipes",v),201)
 requireCode(t,req(t,s,"POST","/api/v1/recipes/rollback",map[string]any{"provider_id":"p","version":1,"confirm":false}),400)
 requireCode(t,req(t,s,"POST","/api/v1/recipes/rollback",map[string]any{"provider_id":"p","version":1,"confirm":true}),200)
 var active int;_ = s.Store.DB.QueryRow("SELECT version FROM recipe_active WHERE provider_id='p'").Scan(&active)
 if active!=1{t.Fatalf("rollback active version = %d",active)}
 requireCode(t,req(t,s,"POST","/api/v1/recipes",map[string]any{"provider_id":"p","settings":map[string]any{"api_key":"secret"},"reason":"bad"}),400)
}
