package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func customForTest() *CustomProtocol {
	return &CustomProtocol{Request: map[string]any{"target": "$MODEL", "history": "$MESSAGES", "instruction": "$SYSTEM", "functions": "$TOOLS", "limit": "$MAX_TOKENS", "streaming": "$STREAM"}, Fields: map[string]string{"text": "/result/text", "reasoning": "/result/think", "model": "/model", "finish": "/state", "tool_calls": "/result/calls", "input_tokens": "/usage/in", "output_tokens": "/usage/out"}, Streaming: true, Done: "[DONE]", FinishValues: []string{"stop", "tool_calls", "length"}}
}

func TestCustomProtocolRealTLSAndNativeTools(t *testing.T) {
	for _, stream := range []bool{false, true} {
		t.Run(fmt.Sprint(stream), func(t *testing.T) {
			calls := 0
			up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				var body map[string]any
				json.NewDecoder(r.Body).Decode(&body)
				if r.URL.Path != "/mapped" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" || body["target"] != "m" || body["instruction"] != "用繁體中文" || body["limit"] != float64(777) || body["streaming"] != stream || body["functions"] == nil {
					t.Error("selected custom wire/settings not applied")
				}
				result := map[string]any{"text": "接續完成", "think": "", "calls": []any{}}
				state := "stop"
				if calls == 1 {
					result["text"] = ""
					result["calls"] = []ToolCall{{Index: 0, ID: "custom-call", Type: "function", Function: ToolFunction{Name: "weather", Arguments: `{"city":"Taipei"}`}}}
					state = "tool_calls"
				} else {
					raw, _ := json.Marshal(body["history"])
					if !strings.Contains(string(raw), "Taipei result") || !strings.Contains(string(raw), "custom-call") {
						t.Error("custom tool history lost")
					}
				}
				raw, _ := json.Marshal(map[string]any{"result": result, "state": state, "model": "fixture-model", "usage": map[string]int{"in": 0, "out": 0}})
				if stream {
					w.Header().Set("Content-Type", "text/event-stream")
					fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
				} else {
					w.Write(raw)
				}
			}))
			defer up.Close()
			s := testServer(t)
			s.Client = up.Client()
			scope := seed(t, s, up.URL+"/v1")
			requireCode(t, req(t, s, "POST", "/api/v1/endpoints", EndpointProfile{ProviderID: "p", ID: "mapped", Operation: "CUSTOM", URL: up.URL + "/mapped", Custom: customForTest()}), 201)
			scope.Endpoint = "mapped"
			id := sessionForTest(t, s, scope)
			confirmCaps(t, s, scope, []string{"TOOL_CALLING"})
			requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", map[string]any{"session_id": id, "system": "用繁體中文", "max_tokens": 777, "streaming": stream}), 200)
			r := req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: id, Prompt: "查詢天氣", Tools: toolsForTest()})
			requireCode(t, r, 200)
			if !strings.Contains(r.Body.String(), "event: tool_calls") || !strings.Contains(r.Body.String(), "event: done") {
				t.Fatal(r.Body.String())
			}
			r = req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: id, ToolResults: []ToolResult{{CallID: "custom-call", Content: "Taipei result"}}})
			requireCode(t, r, 200)
			if !strings.Contains(r.Body.String(), "接續完成") || !strings.Contains(r.Body.String(), "event: done") {
				t.Fatal(r.Body.String())
			}
			history, e := s.history(context.Background(), id)
			if e != nil || len(history) != 4 || history[2].Role != "tool" {
				t.Fatal("custom conversation not retained")
			}
			backup := req(t, s, "GET", "/api/v1/backup", nil)
			requireCode(t, backup, 200)
			if !strings.Contains(backup.Body.String(), "custom_protocols") || strings.Contains(backup.Body.String(), "top-secret-example-key") {
				t.Fatal("custom export unsafe/missing")
			}
			other := testServer(t)
			var document BackupDocument
			if json.Unmarshal(backup.Body.Bytes(), &document) != nil {
				t.Fatal("backup parse")
			}
			requireCode(t, req(t, other, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
			c, e := other.customProtocol(context.Background(), scope)
			if e != nil || c.Fields["text"] != "/result/text" {
				t.Fatal("custom restore lost protocol")
			}
		})
	}
}

func TestCustomRejectsIncompleteAndUnsafeDefinitions(t *testing.T) {
	for _, change := range []func(*CustomProtocol){func(c *CustomProtocol) { c.Request["authorization"] = "$MODEL" }, func(c *CustomProtocol) { c.Request["key"] = "secret" }, func(c *CustomProtocol) { c.Request["target"] = "$KEY" }, func(c *CustomProtocol) { delete(c.Request, "history") }, func(c *CustomProtocol) { c.Fields["finish"] = "" }, func(c *CustomProtocol) { c.Done = "bad\n" }} {
		c := customForTest()
		change(c)
		if validCustom(c) {
			t.Fatal("unsafe custom definition accepted")
		}
	}
	c := customForTest()
	for _, body := range []string{`data: {"result":{"text":"partial"}}` + "\n\n", `data: {"result":{"text":"blocked"},"state":"SAFETY"}` + "\n\n", `data: {"result":{"text":3},"state":"stop"}` + "\n\n", `data: {"result":{"text":"hello"},"state":"stop"} {}` + "\n\n"} {
		if readCustom(strings.NewReader(body), c, true, func([]byte) error { return nil }) == nil {
			t.Fatal("truncated/blocked/malformed custom success")
		}
	}
	delete(c.Request, "instruction")
	if _, e := customPayload(c, "m", "mandatory system", 512, true, nil, nil); e == nil {
		t.Fatal("system silently omitted")
	}
	delete(c.Request, "functions")
	if _, e := customPayload(c, "m", "", 512, true, nil, toolsForTest()); e == nil {
		t.Fatal("tools silently omitted")
	}
}

func TestManualConversationSettingsPersistAndProtectAgent(t *testing.T) {
	s := testServer(t)
	scope := seed(t, s, "https://provider.example/v1")
	id := sessionForTest(t, s, scope)
	settings := map[string]any{"session_id": id, "system": "只回答繁體中文", "max_tokens": 1024, "streaming": false}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", settings), 200)
	v, e := s.sessionSettings(context.Background(), id)
	if e != nil || v.System != "只回答繁體中文" || v.MaxTokens != 1024 || v.Streaming {
		t.Fatal("settings not retained")
	}
	settings["system"] = "top-secret-example-key"
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", settings), 422)
	settings["system"] = "safe"
	settings["max_tokens"] = 0
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", settings), 400)
	settings["max_tokens"] = 512
	s.beginFlight(id)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", settings), 409)
	s.endFlight(id)
	if _, e = s.Store.DB.Exec("INSERT INTO assistant_threads VALUES('thread',?,?,?)", id, scopeKey(scope), now()); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/settings", settings), 404)
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: id, Prompt: "override", System: "different"}), 404)
}
