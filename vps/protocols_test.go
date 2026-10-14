package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func toolsForTest() []ToolDefinition {
	return []ToolDefinition{{Type: "function", Function: ToolFunction{Name: "weather", Parameters: map[string]any{"type": "object", "properties": map[string]any{"city": map[string]any{"type": "string"}}}}}}
}
func sessionForTest(t *testing.T, s *Server, scope Selection) string {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	r := req(t, s, "POST", "/api/v1/sessions", Session{Selection: scope})
	requireCode(t, r, 201)
	var v Session
	json.Unmarshal(r.Body.Bytes(), &v)
	return v.ID
}
func TestNativeProtocolsAndToolContinuation(t *testing.T) {
	for _, op := range []string{"CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE"} {
		for _, stream := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/%v", op, stream), func(t *testing.T) {
				calls := 0
				up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					calls++
					var payload map[string]any
					if json.NewDecoder(r.Body).Decode(&payload) != nil {
						t.Error("invalid request")
					}
					if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
						t.Error("missing exact credential")
					}
					if op == "ANTHROPIC_MESSAGES" && r.Header.Get("anthropic-version") != "2023-06-01" {
						t.Error("missing version")
					}
					if op == "GEMINI_NATIVE" {
						if !strings.Contains(r.URL.Path, "/models/m:") {
							t.Errorf("native model path %s", r.URL.Path)
						}
						if stream && r.URL.Query().Get("alt") != "sse" {
							t.Error("not SSE")
						}
						if payload["contents"] == nil || payload["messages"] != nil {
							t.Error("gemini wire")
						}
					}
					if op == "RESPONSES" {
						if payload["input"] == nil || payload["max_tokens"] != nil || payload["store"] != false {
							t.Error("responses wire")
						}
					}
					if calls == 2 {
						b, _ := json.Marshal(payload)
						if !bytes.Contains(b, []byte("Taipei result")) || !bytes.Contains(b, []byte("weather")) {
							t.Error("tool history absent")
						}
					}
					send := func(v any) {
						b, _ := json.Marshal(v)
						if stream {
							fmt.Fprintf(w, "data: %s\n\n", b)
						} else {
							w.Write(b)
						}
					}
					function := map[string]any{"name": "weather", "arguments": "{\"city\":\"Taipei\"}"}
					switch op {
					case "CHAT_COMPLETIONS":
						msg := map[string]any{"content": ""}
						if calls == 1 {
							msg["tool_calls"] = []any{map[string]any{"index": 0, "id": "call1", "type": "function", "function": function}}
						} else {
							msg["content"] = "Done"
						}
						choice := map[string]any{"index": 0, "finish_reason": "stop"}
						if stream {
							choice["delta"] = msg
						} else {
							choice["message"] = msg
						}
						send(map[string]any{"model": "m", "choices": []any{choice}, "usage": map[string]any{"prompt_tokens": 0, "completion_tokens": 0}})
						if stream {
							fmt.Fprint(w, "data: [DONE]\n\n")
						}
					case "RESPONSES":
						output := []any{}
						if calls == 1 {
							output = append(output, map[string]any{"type": "function_call", "call_id": "call1", "name": "weather", "arguments": function["arguments"]})
						} else {
							output = append(output, map[string]any{"type": "message", "content": []any{map[string]any{"type": "output_text", "text": "Done"}}})
						}
						response := map[string]any{"status": "completed", "model": "m", "output": output, "usage": map[string]any{"input_tokens": 0, "output_tokens": 0}}
						if stream {
							if calls == 1 {
								send(map[string]any{"type": "response.output_item.added", "output_index": 0, "item": map[string]any{"type": "function_call", "call_id": "call1", "name": "weather"}})
								send(map[string]any{"type": "response.function_call_arguments.delta", "output_index": 0, "delta": function["arguments"]})
							} else {
								send(map[string]any{"type": "response.output_text.delta", "delta": "Done"})
							}
							send(map[string]any{"type": "response.completed", "response": response})
						} else {
							send(response)
						}
					case "ANTHROPIC_MESSAGES":
						block := map[string]any{"type": "text", "text": "Done"}
						if calls == 1 {
							block = map[string]any{"type": "tool_use", "id": "call1", "name": "weather", "input": map[string]any{"city": "Taipei"}}
						}
						if stream {
							send(map[string]any{"type": "message_start", "message": map[string]any{"model": "m", "usage": map[string]any{"input_tokens": 0}}})
							start := block
							if calls == 1 {
								start = map[string]any{"type": "tool_use", "id": "call1", "name": "weather", "input": map[string]any{}}
							}
							send(map[string]any{"type": "content_block_start", "index": 0, "content_block": start})
							if calls == 1 {
								send(map[string]any{"type": "content_block_delta", "index": 0, "delta": map[string]any{"type": "input_json_delta", "partial_json": function["arguments"]}})
							}
							send(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "end_turn"}, "usage": map[string]any{"output_tokens": 0}})
							send(map[string]any{"type": "message_stop"})
						} else {
							send(map[string]any{"model": "m", "content": []any{block}, "stop_reason": "end_turn", "usage": map[string]any{"input_tokens": 0, "output_tokens": 0}})
						}
					case "GEMINI_NATIVE":
						part := map[string]any{"text": "Done"}
						if calls == 1 {
							part = map[string]any{"functionCall": map[string]any{"id": "call1", "name": "weather", "args": map[string]any{"city": "Taipei"}}, "thoughtSignature": "opaque-fixture-signature"}
						}
						send(map[string]any{"modelVersion": "m", "candidates": []any{map[string]any{"index": 0, "content": map[string]any{"parts": []any{part}}, "finishReason": "STOP"}}, "usageMetadata": map[string]any{"promptTokenCount": 0, "candidatesTokenCount": 0}})
					}
				}))
				defer up.Close()
				s := testServer(t)
				s.Client = up.Client()
				scope := seed(t, s, up.URL+"/v1")
				scope.Endpoint = op
				id := sessionForTest(t, s, scope)
				requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "endpoint": op, "capabilities": []string{"TOOL_CALLING"}, "confirm": true}), 200)
				result, code := s.execute(context.Background(), inferRequest{SessionID: id, Prompt: "Weather", Streaming: &stream, Tools: toolsForTest()}, "MANUAL_TEST", func(string, any) error { return nil })
				if code != 0 || result.Outcome != "SUCCESS" || len(result.ToolCalls) != 1 {
					t.Fatalf("first: %#v code%d", result, code)
				}
				reject, _ := s.execute(context.Background(), inferRequest{SessionID: id, Prompt: "skip", Streaming: &stream}, "MANUAL_TEST", func(string, any) error { return nil })
				if reject.Outcome != "TOOL_RESULTS_REQUIRED_OR_INVALID" || calls != 1 {
					t.Fatal("pending calls bypassed")
				}
				result, code = s.execute(context.Background(), inferRequest{SessionID: id, ToolResults: []ToolResult{{CallID: result.ToolCalls[0].ID, Content: "Taipei result"}}, Streaming: &stream}, "MANUAL_TEST", func(string, any) error { return nil })
				if code != 0 || result.Outcome != "SUCCESS" || result.Text != "Done" || calls != 2 {
					t.Fatalf("continued: %#v code%d calls%d", result, code, calls)
				}
				if !stream {
					if _, ok := result.Details["ttft_ms"]; ok {
						t.Fatal("nonstream TTFT")
					}
				}
				if result.Details["input_tokens"] != int64(0) {
					t.Fatal("known zero lost")
				}
				h, e := s.history(context.Background(), id)
				if e != nil || len(h) != 4 || h[1].ToolCalls[0].ID != "call1" || h[2].Role != "tool" {
					t.Fatalf("durable tools: %#v %v", h, e)
				}
			})
		}
	}
}
func TestNativeTruncationAndUnsupportedInput(t *testing.T) {
	for _, op := range []string{"RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE"} {
		if readProtocol(strings.NewReader("data: {}\n\n"), op, true, func([]byte) error { return nil }, func(json.RawMessage) {}) == nil {
			t.Errorf("%s accepted EOF", op)
		}
	}
	m := chatMessage{Role: "user", Content: []map[string]any{{"type": "input_audio", "input_audio": map[string]string{"data": "AAAA", "format": "wav"}}}}
	for _, op := range []string{"RESPONSES", "ANTHROPIC_MESSAGES"} {
		if _, err := protocolPayload(op, "m", 32, true, []chatMessage{m}, nil); err == nil {
			t.Fatal("audio silently dropped")
		}
	}
}
func validTestWAV() []byte {
	raw := make([]byte, 44+48000)
	copy(raw, "RIFF")
	binary.LittleEndian.PutUint32(raw[4:], uint32(len(raw)-8))
	copy(raw[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(raw[16:], 16)
	binary.LittleEndian.PutUint16(raw[20:], 1)
	binary.LittleEndian.PutUint16(raw[22:], 1)
	binary.LittleEndian.PutUint32(raw[24:], 24000)
	binary.LittleEndian.PutUint32(raw[28:], 48000)
	binary.LittleEndian.PutUint16(raw[32:], 2)
	binary.LittleEndian.PutUint16(raw[34:], 16)
	copy(raw[36:], "data")
	binary.LittleEndian.PutUint32(raw[40:], 48000)
	return raw
}
func TestSpeechVectorsThroughRealHTTP(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var v map[string]any
		json.NewDecoder(r.Body).Decode(&v)
		switch r.URL.Path {
		case "/v1/audio/speech":
			if v["input"] != "Hello" || v["voice"] != "alloy" || v["response_format"] != "wav" {
				t.Error("speech wire")
			}
			w.Header().Set("Content-Type", "audio/wav")
			w.Write(validTestWAV())
		case "/v1/embeddings":
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"index": 0, "embedding": []float64{0.1, -0.2}}}})
		case "/v1/rerank":
			json.NewEncoder(w).Encode(map[string]any{"results": []any{map[string]any{"index": 1, "relevance_score": 0.8}}})
		default:
			t.Errorf("unexpected %s", r.URL.Path)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	base := seed(t, s, up.URL+"/v1")
	for _, op := range []string{"AUDIO_SPEECH", "EMBEDDINGS", "RERANK"} {
		v := base
		v.Endpoint = op
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
		cap := map[string]string{"AUDIO_SPEECH": "AUDIO_OUTPUT", "EMBEDDINGS": "EMBEDDING", "RERANK": "RERANK"}[op]
		requireCode(t, req(t, s, "POST", "/api/v1/models/capabilities", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "model_id": "m", "endpoint": op, "capabilities": []string{cap}, "confirm": true}), 200)
		request := MediaRequest{Selection: v, Prompt: "Hello", Voice: "alloy", Format: "wav"}
		if op == "EMBEDDINGS" {
			request = MediaRequest{Selection: v, Inputs: []string{"text"}}
		}
		if op == "RERANK" {
			request = MediaRequest{Selection: v, Prompt: "query", Documents: []string{"a", "b"}}
		}
		r := req(t, s, "POST", "/api/v1/infer/media", request)
		requireCode(t, r, 200)
		var result MediaResponse
		json.Unmarshal(r.Body.Bytes(), &result)
		if result.Outcome != "SUCCESS" || result.Details["ttft_ms"] != nil {
			t.Fatalf("%s: %s", op, r.Body.String())
		}
		if op == "AUDIO_SPEECH" && result.Data[0].MIME != "audio/wav" {
			t.Fatal("speech absent")
		}
		if op == "EMBEDDINGS" && result.Vectors[0].Embedding[1] != -0.2 {
			t.Fatal("vectors changed")
		}
		if op == "RERANK" && result.Rankings[0].Index != 1 {
			t.Fatal("ranking changed")
		}
	}
}
func TestAudioGuardsAndStreamingSecret(t *testing.T) {
	if generatedAudioType(validTestWAV(), "wav") != "audio/wav" || generatedAudioType([]byte("RIFF0000WAVE"), "wav") != "" {
		t.Fatal("WAV guard")
	}
	raw := validTestWAV()
	raw = append(raw, []byte("secret")...)
	if generatedAudioType(raw, "pcm", "secret") != "" {
		t.Fatal("secret audio")
	}
	var out strings.Builder
	g := streamGuard{secrets: []string{"top-secret-key"}, emit: func(s string) error { out.WriteString(s); return nil }}
	if g.write("Hello top-se") != nil {
		t.Fatal("premature reject")
	}
	if g.write("cret-key") == nil || strings.Contains(out.String(), "top-secret-key") {
		t.Fatal("split secret leaked")
	}
}
