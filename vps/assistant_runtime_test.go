package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func configureAssistant(t *testing.T, s *Server, v Selection) {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", struct {
		Selection
		Capabilities []string `json:"capabilities"`
		Confirm      bool     `json:"confirm"`
	}{v, []string{"TOOL_CALLING"}, true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/config", AssistantConfig{v}), 200)
}
func assistantReply(w http.ResponseWriter, text string, calls ...ToolCall) {
	message := map[string]any{"content": text, "role": "assistant"}
	if len(calls) > 0 {
		message["tool_calls"] = calls
	}
	json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"index": 0, "message": message, "finish_reason": "stop"}}, "usage": map[string]int{"prompt_tokens": 10, "completion_tokens": 3}})
}
func functionCall(id, name string, value any) ToolCall {
	raw, _ := json.Marshal(value)
	return ToolCall{ID: id, Type: "function", Function: ToolFunction{Name: name, Arguments: string(raw)}}
}
func TestAssistantRealToolsSharedReviewAndSecretBoundary(t *testing.T) {
	s := testServer(t)
	var round atomic.Int32
	var newBase, refID string
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/new/v1/models" {
			if r.Header.Get("Authorization") != "Bearer second-private-key-example" {
				t.Error("shared onboarding used the wrong safe reference")
			}
			w.Write([]byte(`{"data":[{"id":"new-model"}]}`))
			return
		}
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		raw, _ := json.Marshal(body)
		for _, secret := range []string{"top-secret-example-key", "second-private-key-example", s.Token, base64.StdEncoding.EncodeToString(s.Key)} {
			if strings.Contains(string(raw), secret) {
				t.Error("raw secret reached model request")
			}
		}
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("assistant changed its selected credential")
		}
		messages := body["messages"].([]any)
		if messages[0].(map[string]any)["content"] != assistantSystem {
			t.Error("assistant system boundary changed")
		}
		switch round.Add(1) {
		case 1:
			v := Selection{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "added-model", Endpoint: "CHAT_COMPLETIONS"}
			assistantReply(w, "", functionCall("model-add", "add_model", map[string]any{"selection": v, "display_name": "Added"}), functionCall("provider-add", "onboard_provider", map[string]any{"safe_secret_ref": refID, "name": "New Provider", "model_ids": []string{"new-model"}}))
		case 2:
			if messages[len(messages)-1].(map[string]any)["role"] != "tool" {
				t.Error("native tool continuation missing")
			}
			assistantReply(w, "已準備，請在 Review 確認。")
		default:
			assistantReply(w, "完成查詢。")
		}
	}))
	defer up.Close()
	s.Client = up.Client()
	newBase = up.URL + "/new/v1"
	v := seed(t, s, up.URL+"/v1")
	configureAssistant(t, s, v)
	r := req(t, s, "POST", "/api/v1/assistant/secrets", map[string]any{"base_url": newBase, "label": "新 Key", "role": "INFERENCE", "secret": "second-private-key-example"})
	requireCode(t, r, 201)
	var ref SafeSecretRef
	json.Unmarshal(r.Body.Bytes(), &ref)
	refID = ref.ID
	r = req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: "幫我加入模型和 Provider，安全參照 " + refID})
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "event: done") || round.Load() != 2 {
		t.Fatalf("real agent loop failed: %s", r.Body.String())
	}
	for _, secret := range []string{"top-secret-example-key", "second-private-key-example", s.Token} {
		if strings.Contains(r.Body.String(), secret) {
			t.Fatal("secret leaked to UI")
		}
	}
	var members, providers int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM providers").Scan(&providers)
	if members != 1 || providers != 1 {
		t.Fatal("tool bypassed explicit review")
	}
	var actionID, reviewID string
	s.Store.DB.QueryRow("SELECT id FROM action_ledger WHERE action='ADD_MY_MODEL'").Scan(&actionID)
	s.Store.DB.QueryRow("SELECT id FROM onboarding WHERE status='REVIEW'").Scan(&reviewID)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/confirm", map[string]any{"id": actionID, "confirm": true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/onboarding/confirm", map[string]any{"id": reviewID, "confirm": true, "selected_model_ids": []string{"new-model"}}), 201)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if members != 3 {
		t.Fatal("shared review did not create real membership")
	}
	var samples int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE source='ASSISTANT_AGENT' AND outcome='SUCCESS'").Scan(&samples)
	if samples != 2 {
		t.Fatal("assistant bypassed common telemetry")
	}
	rows, e := s.Store.DB.Query("SELECT args_json,result_json FROM assistant_tool_events")
	if e != nil {
		t.Fatal(e)
	}
	defer rows.Close()
	for rows.Next() {
		var a, b string
		rows.Scan(&a, &b)
		if strings.Contains(a+b, "second-private-key-example") {
			t.Fatal("secret persisted in action journal")
		}
	}
}

func TestAssistantControlChangesUndoAndCriticalRefs(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	raw := func(v any) json.RawMessage { b, _ := json.Marshal(v); return b }
	preview := func(v ControlChange) string {
		r := req(t, s, "POST", "/api/v1/assistant/actions/preview", v)
		requireCode(t, r, 201)
		var m map[string]any
		json.Unmarshal(r.Body.Bytes(), &m)
		return m["id"].(string)
	}
	transition := func(id string, undo bool) *httptest.ResponseRecorder {
		path := "confirm"
		if undo {
			path = "undo"
		}
		return req(t, s, "POST", "/api/v1/assistant/actions/"+path, map[string]any{"id": id, "confirm": true})
	}
	id := preview(ControlChange{Kind: "PROVIDER", ProviderID: "p", Patch: map[string]json.RawMessage{"name": raw("Updated")}})
	requireCode(t, transition(id, false), 200)
	requireCode(t, transition(id, true), 200)
	var name string
	s.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&name)
	if name != "P" {
		t.Fatal("undo failed")
	}
	id = preview(ControlChange{Kind: "PROVIDER", ProviderID: "p", Patch: map[string]json.RawMessage{"name": raw("First")}})
	requireCode(t, transition(id, false), 200)
	newer := preview(ControlChange{Kind: "PROVIDER", ProviderID: "p", Patch: map[string]json.RawMessage{"name": raw("Second")}})
	requireCode(t, transition(newer, false), 200)
	requireCode(t, transition(id, true), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/preview", ControlChange{Kind: "PROVIDER", ProviderID: "p", Patch: map[string]json.RawMessage{"base_url": raw("https://other.example/v1")}}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/actions/preview", ControlChange{Kind: "NOTES", Selection: v, Patch: map[string]json.RawMessage{"tags": raw("bad")}}), 422)
	refResponse := req(t, s, "POST", "/api/v1/assistant/secrets", map[string]any{"base_url": "https://provider.example/v1", "label": "Rotated", "role": "INFERENCE", "secret": "new-private-credential"})
	requireCode(t, refResponse, 201)
	var ref SafeSecretRef
	json.Unmarshal(refResponse.Body.Bytes(), &ref)
	id = preview(ControlChange{Kind: "CREDENTIAL_ROTATE", ProviderID: "p", AccountID: "a", CredentialID: "c", Patch: map[string]json.RawMessage{"safe_secret_ref": raw(ref.ID)}})
	_, key, _ := s.endpointAuth(context.Background(), "p", "a", "c")
	if key != "top-secret-example-key" {
		t.Fatal("rotation preview changed key")
	}
	requireCode(t, transition(id, false), 200)
	_, key, _ = s.endpointAuth(context.Background(), "p", "a", "c")
	if key != "new-private-credential" {
		t.Fatal("confirmed rotation missing")
	}
	requireCode(t, transition(id, true), 200)
	_, key, _ = s.endpointAuth(context.Background(), "p", "a", "c")
	if key != "top-secret-example-key" {
		t.Fatal("rotation Undo missing")
	}
	id = preview(ControlChange{Kind: "CREDENTIAL_ADD", ProviderID: "p", AccountID: "a", Patch: map[string]json.RawMessage{"safe_secret_ref": raw(ref.ID), "group": raw("group-b")}})
	requireCode(t, transition(id, false), 200)
	requireCode(t, transition(id, true), 200)
	var enabled int
	s.Store.DB.QueryRow("SELECT enabled FROM credentials WHERE id!='c'").Scan(&enabled)
	if enabled != 0 {
		t.Fatal("add Undo must disable retained credential")
	}
	actions := req(t, s, "GET", "/api/v1/assistant/actions", nil)
	if strings.Contains(actions.Body.String(), "_cipher") || strings.Contains(actions.Body.String(), "_fields") {
		t.Fatal("private snapshots exposed")
	}
}

func TestAssistantInterruptedToolResumeDoesNotReplay(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	configureAssistant(t, s, v)
	thread, e := s.assistantThread(context.Background(), "", v)
	if e != nil {
		t.Fatal(e)
	}
	call := functionCall("persisted-call", "preview_change", map[string]any{"kind": "PROVIDER", "provider_id": "p", "patch": map[string]string{"name": "Updated"}})
	if e = s.saveMessage(context.Background(), thread.SessionID, Message{Role: "assistant", MessageState: MessageState{ToolCalls: []ToolCall{call}}}); e != nil {
		t.Fatal(e)
	}
	run := newID()
	s.Store.DB.Exec("INSERT INTO assistant_runs(id,thread_id,status,user_message,created_at) VALUES(?,?,'INTERRUPTED','earlier',?)", run, thread.ID, now())
	s.Store.DB.Exec("INSERT INTO assistant_tool_events VALUES(?,?,?,?,?,?,'SUCCESS',?)", newID(), run, call.ID, call.Function.Name, call.Function.Arguments, `{"id":"retained-action","state":"PENDING"}`, now())
	results, e := s.assistantResume(context.Background(), thread)
	if e != nil || len(results) != 1 || !strings.Contains(results[0].Content, "retained-action") {
		t.Fatal("resume did not reuse completed journal result")
	}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		messages := body["messages"].([]any)
		found := false
		for _, m := range messages {
			row := m.(map[string]any)
			if row["role"] == "tool" && strings.Contains(str(row["content"]), "retained-action") {
				found = true
			}
		}
		if !found {
			t.Error("resumed wire lost structured result")
		}
		assistantReply(w, "已使用保存結果。")
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	r := req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{ThreadID: thread.ID, Message: "繼續"})
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "event: done") {
		t.Fatal(r.Body.String())
	}
	var n int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM assistant_tool_events").Scan(&n)
	if n != 1 {
		t.Fatal("interrupted tool was replayed")
	}
}

func TestAssistantCancelClosesRealModelAndPreservesStatus(t *testing.T) {
	started := make(chan struct{})
	closed := make(chan struct{})
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		close(started)
		select {
		case <-r.Context().Done():
			close(closed)
		case <-time.After(5 * time.Second):
			t.Error("assistant cancellation did not close upstream")
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	configureAssistant(t, s, v)
	thread, e := s.assistantThread(context.Background(), "", v)
	if e != nil {
		t.Fatal(e)
	}
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		finished <- req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{ThreadID: thread.ID, Message: "查資料"})
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("real model request missing")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/cancel", map[string]any{"thread_id": thread.ID}), 200)
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("upstream remained open")
	}
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("assistant stuck")
	}
	var status string
	s.Store.DB.QueryRow("SELECT status FROM assistant_runs WHERE thread_id=?", thread.ID).Scan(&status)
	if status != "CANCELLED" {
		t.Fatal(status)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: "key=" + s.Token}), 422)
}
