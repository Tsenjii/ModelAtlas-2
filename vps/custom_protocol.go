package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"strings"
)

// A data-only request template and JSON pointers. No scripts, URLs, auth
// interpolation, or executable expressions are accepted here.
type CustomProtocol struct {
	Request      map[string]any    `json:"request"`
	Fields       map[string]string `json:"response_fields"`
	Streaming    bool              `json:"streaming"`
	Done         string            `json:"done_sentinel,omitempty"`
	FinishValues []string          `json:"finish_values"`
}

func customTemplate(value any, values map[string]any, seen map[string]int, depth int) (any, error) {
	if depth > 8 {
		return nil, errors.New("template nesting limit")
	}
	switch v := value.(type) {
	case string:
		if strings.HasPrefix(v, "$") {
			if !oneOf(v, "$MODEL", "$MESSAGES", "$SYSTEM", "$TOOLS", "$MAX_TOKENS", "$STREAM") {
				return nil, errors.New("unknown placeholder")
			}
			seen[v]++
			if values != nil {
				return values[v], nil
			}
		}
		if len(v) > 4096 || rawSecretPattern.MatchString(v) {
			return nil, errors.New("unsafe template literal")
		}
		return v, nil
	case map[string]any:
		if len(v) > 64 {
			return nil, errors.New("template object limit")
		}
		out := map[string]any{}
		for k, x := range v {
			if len(k) > 64 || oneOf(strings.ToLower(k), "authorization", "cookie", "api_key", "key", "secret", "access_token", "credentials", "pat", "headers", "url") {
				return nil, errors.New("auth or URL in template")
			}
			var e error
			out[k], e = customTemplate(x, values, seen, depth+1)
			if e != nil {
				return nil, e
			}
		}
		return out, nil
	case []any:
		if len(v) > 64 {
			return nil, errors.New("template array limit")
		}
		out := []any{}
		for _, x := range v {
			r, e := customTemplate(x, values, seen, depth+1)
			if e != nil {
				return nil, e
			}
			out = append(out, r)
		}
		return out, nil
	case nil, bool, float64, json.Number:
		return v, nil
	default:
		return nil, errors.New("unsupported template value")
	}
}
func validCustom(c *CustomProtocol) bool {
	if c == nil || c.Request == nil || len(c.Fields) < 2 || len(c.Fields) > 11 || c.Fields["finish"] == "" || c.Fields["text"] == "" && c.Fields["tool_calls"] == "" || len(c.FinishValues) == 0 || len(c.FinishValues) > 8 || len(c.Done) > 64 || strings.ContainsAny(c.Done, "\r\n\x00") || !c.Streaming && c.Done != "" {
		return false
	}
	raw, e := json.Marshal(c)
	if e != nil || len(raw) > 16384 {
		return false
	}
	seen := map[string]int{}
	if _, e = customTemplate(c.Request, nil, seen, 0); e != nil || seen["$MESSAGES"] != 1 || seen["$MODEL"] != 1 || seen["$MAX_TOKENS"] != 1 || seen["$SYSTEM"] > 1 || seen["$TOOLS"] > 1 || seen["$STREAM"] > 1 || c.Streaming && seen["$STREAM"] != 1 {
		return false
	}
	for name, path := range c.Fields {
		if !oneOf(name, "text", "reasoning", "model", "finish", "input_tokens", "output_tokens", "total_tokens", "cache_read_tokens", "reasoning_tokens", "tool_calls", "error") || path == "" || !validPointer(path) {
			return false
		}
	}
	for _, finish := range c.FinishValues {
		if finish == "" || len(finish) > 64 || safeText(finish, 64) != finish {
			return false
		}
	}
	return true
}

func (s *Server) customProtocol(ctx context.Context, scope Selection) (*CustomProtocol, error) {
	var raw string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT config_json FROM custom_protocols WHERE provider_id=? AND endpoint_id=?", scope.ProviderID, scope.Endpoint).Scan(&raw)
	var c CustomProtocol
	if err != nil || json.Unmarshal([]byte(raw), &c) != nil || !validCustom(&c) {
		return nil, errors.New("custom endpoint definition missing or invalid")
	}
	return &c, nil
}
func saveCustom(ctx context.Context, tx *sql.Tx, pid, id string, c *CustomProtocol) error {
	if !validCustom(c) {
		return errors.New("invalid custom protocol")
	}
	raw, _ := json.Marshal(c)
	_, e := tx.ExecContext(ctx, "INSERT INTO custom_protocols VALUES(?,?,?)", pid, id, string(raw))
	return e
}
func customPayload(c *CustomProtocol, model, system string, max int, stream bool, messages []chatMessage, tools []ToolDefinition) (map[string]any, error) {
	if stream && !c.Streaming {
		return nil, errors.New("custom streaming not declared")
	}
	standard, e := protocolPayload("CHAT_COMPLETIONS", model, max, stream, messages, tools)
	if e != nil {
		return nil, e
	}
	seen := map[string]int{}
	result, e := customTemplate(c.Request, map[string]any{"$MODEL": model, "$SYSTEM": system, "$MESSAGES": standard["messages"], "$MAX_TOKENS": max, "$STREAM": stream, "$TOOLS": tools}, seen, 0)
	if e != nil || system != "" && seen["$SYSTEM"] != 1 || len(tools) > 0 && seen["$TOOLS"] != 1 {
		return nil, errors.New("custom template cannot carry selected system or tools")
	}
	return object(result), nil
}

func readCustom(body io.Reader, c *CustomProtocol, stream bool, consume func([]byte) error) error {
	completed := false
	accept := func(raw []byte) error {
		if stream && c.Done != "" && string(raw) == c.Done {
			completed = true
			return errStreamDone
		}
		var root map[string]any
		d := json.NewDecoder(bytes.NewReader(raw))
		d.UseNumber()
		if d.Decode(&root) != nil || d.Decode(new(any)) != io.EOF || root["error"] != nil {
			return errors.New("invalid custom response")
		}
		field := func(name string) any {
			if c.Fields[name] == "" {
				return nil
			}
			return pointer(root, c.Fields[name])
		}
		if field("error") != nil {
			return errors.New("custom upstream error")
		}
		readString := func(name string) (string, error) {
			x := field(name)
			if x == nil {
				return "", nil
			}
			s, ok := x.(string)
			if !ok {
				return "", errors.New("custom field must be text")
			}
			return s, nil
		}
		text, e := readString("text")
		if e != nil {
			return e
		}
		think, e := readString("reasoning")
		if e != nil {
			return e
		}
		model, e := readString("model")
		if e != nil {
			return e
		}
		finish, e := readString("finish")
		if e != nil {
			return e
		}
		if finish != "" {
			if !oneOf(finish, c.FinishValues...) {
				return errors.New("custom generation incomplete or blocked")
			}
			completed = true
		}
		var calls []ToolCall
		if x := field("tool_calls"); x != nil {
			raw, e := json.Marshal(x)
			if e != nil || json.Unmarshal(raw, &calls) != nil || len(calls) > 16 {
				return errors.New("invalid custom tools")
			}
		}
		usage := map[string]any{}
		for k, n := range map[string]string{"input_tokens": "prompt_tokens", "output_tokens": "completion_tokens", "total_tokens": "total_tokens"} {
			if x := field(k); x != nil {
				number, ok := x.(json.Number)
				if !ok {
					return errors.New("invalid custom usage")
				}
				count, e := number.Int64()
				if e != nil || count < 0 {
					return errors.New("invalid custom usage")
				}
				usage[n] = count
			}
		}
		for k, n := range map[string]string{"cache_read_tokens": "cached_tokens", "reasoning_tokens": "reasoning_tokens"} {
			if x := field(k); x != nil {
				number, ok := x.(json.Number)
				if !ok {
					return errors.New("invalid custom token details")
				}
				count, e := number.Int64()
				if e != nil || count < 0 {
					return errors.New("invalid custom token details")
				}
				parent := "prompt_tokens_details"
				if k == "reasoning_tokens" {
					parent = "completion_tokens_details"
				}
				usage[parent] = map[string]any{n: count}
			}
		}
		p := nativeParser{stream: stream, consume: consume}
		return p.send(text, think, model, finish, usage, calls)
	}
	var e error
	if stream {
		e = readFrames(body, accept)
	} else {
		var raw []byte
		raw, e = readBounded(body, 1<<20)
		if e == nil {
			e = accept(raw)
		}
	}
	if e == errStreamDone {
		e = nil
	}
	if e != nil {
		return e
	}
	if !completed {
		return io.ErrUnexpectedEOF
	}
	return nil
}
