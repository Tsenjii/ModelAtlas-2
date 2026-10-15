package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"strings"
)

func conversationOperation(op string) bool {
	return oneOf(op, "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM")
}
func geminiEndpoint(raw string, stream bool) string {
	if !stream {
		return strings.Replace(raw, ":streamGenerateContent", ":generateContent", 1)
	}
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	q := u.Query()
	q.Set("alt", "sse")
	u.RawQuery = q.Encode()
	return u.String()
}
func protocolPayload(op, model string, max int, stream bool, messages []chatMessage, tools []ToolDefinition) (map[string]any, error) {
	payload := map[string]any{"model": model, "stream": stream, "max_tokens": max}
	if op == "CHAT_COMPLETIONS" {
		clean := []map[string]any{}
		for _, m := range messages {
			row := map[string]any{"role": m.Role, "content": m.Content}
			if len(m.ToolCalls) > 0 {
				row["tool_calls"] = m.ToolCalls
			}
			if m.ToolCallID != "" {
				row["tool_call_id"] = m.ToolCallID
			}
			clean = append(clean, row)
		}
		payload["messages"] = clean
		if stream {
			payload["stream_options"] = map[string]bool{"include_usage": true}
		}
		if len(tools) > 0 {
			payload["tools"] = tools
		}
		return payload, nil
	}
	switch op {
	case "RESPONSES":
		delete(payload, "max_tokens")
		payload["max_output_tokens"] = max
		payload["store"] = false
		input := []map[string]any{}
		for _, m := range messages {
			if m.Role == "tool" {
				input = append(input, map[string]any{"type": "function_call_output", "call_id": m.ToolCallID, "output": m.Content})
				continue
			}
			if len(m.ProtocolState) > 0 {
				var state []map[string]any
				if json.Unmarshal(m.ProtocolState, &state) != nil {
					return nil, errors.New("invalid state")
				}
				input = append(input, state...)
			}
			parts, err := protocolParts(op, m.Content, m.Role)
			if err != nil {
				return nil, err
			}
			if len(parts) > 0 {
				input = append(input, map[string]any{"role": m.Role, "content": parts})
			}
			for _, c := range m.ToolCalls {
				input = append(input, map[string]any{"type": "function_call", "call_id": c.ID, "name": c.Function.Name, "arguments": c.Function.Arguments})
			}
		}
		payload["input"] = input
		if len(tools) > 0 {
			t := []map[string]any{}
			for _, v := range tools {
				t = append(t, map[string]any{"type": "function", "name": v.Function.Name, "description": v.Function.Description, "parameters": v.Function.Parameters})
			}
			payload["tools"] = t
		}
	case "ANTHROPIC_MESSAGES":
		rows := []map[string]any{}
		for _, m := range messages {
			role := m.Role
			parts := []map[string]any{}
			if role == "tool" {
				role = "user"
				parts = append(parts, map[string]any{"type": "tool_result", "tool_use_id": m.ToolCallID, "content": m.Content})
			} else {
				var err error
				parts, err = protocolParts(op, m.Content, role)
				if err != nil {
					return nil, err
				}
				if len(m.ProtocolState) > 0 {
					var state []map[string]any
					if json.Unmarshal(m.ProtocolState, &state) != nil {
						return nil, errors.New("invalid state")
					}
					parts = append(state, parts...)
				}
				for _, c := range m.ToolCalls {
					var args any
					json.Unmarshal([]byte(c.Function.Arguments), &args)
					parts = append(parts, map[string]any{"type": "tool_use", "id": c.ID, "name": c.Function.Name, "input": args})
				}
			}
			if len(rows) > 0 && rows[len(rows)-1]["role"] == role {
				rows[len(rows)-1]["content"] = append(rows[len(rows)-1]["content"].([]map[string]any), parts...)
			} else {
				rows = append(rows, map[string]any{"role": role, "content": parts})
			}
		}
		payload["messages"] = rows
		if len(tools) > 0 {
			t := []map[string]any{}
			for _, v := range tools {
				t = append(t, map[string]any{"name": v.Function.Name, "description": v.Function.Description, "input_schema": v.Function.Parameters})
			}
			payload["tools"] = t
		}
	case "GEMINI_NATIVE":
		payload = map[string]any{"generationConfig": map[string]any{"maxOutputTokens": max}}
		contents := []map[string]any{}
		for _, m := range messages {
			role := "user"
			if m.Role == "assistant" {
				role = "model"
			}
			parts := []map[string]any{}
			if m.Role == "tool" {
				parts = append(parts, map[string]any{"functionResponse": map[string]any{"id": m.ToolCallID, "name": m.ToolName, "response": map[string]any{"output": m.Content}}})
			} else if len(m.ProtocolState) > 0 {
				if json.Unmarshal(m.ProtocolState, &parts) != nil {
					return nil, errors.New("invalid native state")
				}
			} else {
				var err error
				parts, err = protocolParts(op, m.Content, m.Role)
				if err != nil {
					return nil, err
				}
				for _, c := range m.ToolCalls {
					var args any
					json.Unmarshal([]byte(c.Function.Arguments), &args)
					parts = append(parts, map[string]any{"functionCall": map[string]any{"id": c.ID, "name": c.Function.Name, "args": args}})
				}
			}
			if len(contents) > 0 && contents[len(contents)-1]["role"] == role {
				contents[len(contents)-1]["parts"] = append(contents[len(contents)-1]["parts"].([]map[string]any), parts...)
			} else {
				contents = append(contents, map[string]any{"role": role, "parts": parts})
			}
		}
		payload["contents"] = contents
		if len(tools) > 0 {
			decl := []map[string]any{}
			for _, t := range tools {
				decl = append(decl, map[string]any{"name": t.Function.Name, "description": t.Function.Description, "parameters": t.Function.Parameters})
			}
			payload["tools"] = []any{map[string]any{"functionDeclarations": decl}}
		}
	default:
		return nil, errors.New("unsupported protocol")
	}
	return payload, nil
}
func protocolParts(op string, content any, role string) ([]map[string]any, error) {
	parts := []map[string]any{}
	text := func(t string) {
		if t == "" {
			return
		}
		switch op {
		case "RESPONSES":
			kind := "input_text"
			if role == "assistant" {
				kind = "output_text"
			}
			parts = append(parts, map[string]any{"type": kind, "text": t})
		case "ANTHROPIC_MESSAGES":
			parts = append(parts, map[string]any{"type": "text", "text": t})
		case "GEMINI_NATIVE":
			parts = append(parts, map[string]any{"text": t})
		}
	}
	if t, ok := content.(string); ok {
		text(t)
		return parts, nil
	}
	input, ok := content.([]map[string]any)
	if !ok {
		return nil, errors.New("invalid content")
	}
	for _, p := range input {
		switch p["type"] {
		case "text":
			text(p["text"].(string))
		case "image_url":
			raw := p["image_url"].(map[string]string)["url"]
			header, data, ok := strings.Cut(raw, ",")
			if !ok {
				return nil, errors.New("invalid image")
			}
			mime := strings.TrimSuffix(strings.TrimPrefix(header, "data:"), ";base64")
			switch op {
			case "RESPONSES":
				parts = append(parts, map[string]any{"type": "input_image", "image_url": raw})
			case "ANTHROPIC_MESSAGES":
				parts = append(parts, map[string]any{"type": "image", "source": map[string]any{"type": "base64", "media_type": mime, "data": data}})
			case "GEMINI_NATIVE":
				parts = append(parts, map[string]any{"inlineData": map[string]any{"mimeType": mime, "data": data}})
			}
		case "file":
			f := p["file"].(map[string]string)
			_, data, _ := strings.Cut(f["file_data"], ",")
			switch op {
			case "RESPONSES":
				parts = append(parts, map[string]any{"type": "input_file", "filename": f["filename"], "file_data": f["file_data"]})
			case "ANTHROPIC_MESSAGES":
				parts = append(parts, map[string]any{"type": "document", "source": map[string]any{"type": "base64", "media_type": "application/pdf", "data": data}})
			case "GEMINI_NATIVE":
				parts = append(parts, map[string]any{"inlineData": map[string]any{"mimeType": "application/pdf", "data": data}})
			}
		case "input_audio":
			if op != "GEMINI_NATIVE" {
				return nil, errors.New("audio not supported by this protocol")
			}
			a := p["input_audio"].(map[string]string)
			mime := "audio/mpeg"
			if a["format"] == "wav" {
				mime = "audio/wav"
			}
			parts = append(parts, map[string]any{"inlineData": map[string]any{"mimeType": mime, "data": a["data"]}})
		default:
			return nil, errors.New("unsupported content")
		}
	}
	return parts, nil
}

var errStreamDone = errors.New("protocol complete")

func readProtocol(body io.Reader, op string, stream bool, consume func([]byte) error, saveState func(json.RawMessage)) error {
	parser := nativeParser{op: op, stream: stream, consume: consume, calls: map[int]ToolCall{}, state: []map[string]any{}, blocks: map[int]map[string]any{}}
	var err error
	if stream {
		err = readFrames(body, parser.accept)
	} else {
		var raw []byte
		raw, err = readBounded(body, 1<<20)
		if err == nil {
			err = parser.accept(raw)
		}
	}
	if err == errStreamDone {
		err = nil
	}
	if err != nil {
		return err
	}
	if !parser.completed {
		return io.ErrUnexpectedEOF
	}
	if len(parser.state) > 0 {
		raw, _ := json.Marshal(parser.state)
		if len(raw) > 256<<10 {
			return errors.New("native state limit")
		}
		saveState(raw)
	}
	return nil
}
func readFrames(body io.Reader, consume func([]byte) error) error {
	scanner := bufio.NewScanner(io.LimitReader(body, 16<<20))
	scanner.Buffer(make([]byte, 4096), 1<<20)
	data := []string{}
	size := 0
	dispatch := func() error {
		if len(data) == 0 {
			return nil
		}
		raw := strings.Join(data, "\n")
		data = nil
		size = 0
		return consume([]byte(raw))
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if err := dispatch(); err != nil {
				return err
			}
		} else if strings.HasPrefix(line, "data:") {
			v := strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " ")
			size += len(v)
			if size > 1<<20 {
				return errors.New("frame limit")
			}
			data = append(data, v)
		}
	}
	if scanner.Err() != nil {
		return scanner.Err()
	}
	return dispatch()
}

type nativeParser struct {
	op                string
	stream, completed bool
	consume           func([]byte) error
	calls             map[int]ToolCall
	state             []map[string]any
	stateBytes        int
	blocks            map[int]map[string]any
}

func object(v any) map[string]any {
	m, _ := v.(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}
func list(v any) []any { a, _ := v.([]any); return a }
func str(v any) string { s, _ := v.(string); return s }
func num(v any) int    { n, _ := v.(float64); return int(n) }
func (p *nativeParser) send(text, think, model, finish string, usage map[string]any, calls []ToolCall) error {
	msg := map[string]any{"content": text, "reasoning_content": think, "tool_calls": calls}
	choice := map[string]any{"index": 0, "delta": msg}
	if !p.stream {
		choice["message"] = msg
	}
	if finish != "" {
		choice["finish_reason"] = finish
	}
	raw, _ := json.Marshal(map[string]any{"model": model, "choices": []any{choice}, "usage": usage})
	return p.consume(raw)
}
func usageNative(root map[string]any, op string) map[string]any {
	u := object(root["usage"])
	if op == "GEMINI_NATIVE" {
		u = object(root["usageMetadata"])
	}
	out := map[string]any{}
	mapping := map[string]string{"input_tokens": "prompt_tokens", "output_tokens": "completion_tokens", "total_tokens": "total_tokens"}
	if op == "GEMINI_NATIVE" {
		mapping = map[string]string{"promptTokenCount": "prompt_tokens", "candidatesTokenCount": "completion_tokens", "totalTokenCount": "total_tokens"}
	}
	for f, t := range mapping {
		if v, ok := u[f]; ok {
			out[t] = v
		}
	}
	cache := u["cache_read_input_tokens"]
	reason := u["reasoning_tokens"]
	if op == "RESPONSES" {
		cache = object(u["input_tokens_details"])["cached_tokens"]
		reason = object(u["output_tokens_details"])["reasoning_tokens"]
	}
	if op == "GEMINI_NATIVE" {
		cache = u["cachedContentTokenCount"]
		reason = u["thoughtsTokenCount"]
	}
	out["prompt_tokens_details"] = map[string]any{"cached_tokens": cache}
	out["completion_tokens_details"] = map[string]any{"reasoning_tokens": reason}
	return out
}
func (p *nativeParser) accept(raw []byte) error {
	var root map[string]any
	if json.Unmarshal(raw, &root) != nil || root == nil || root["error"] != nil {
		return errors.New("invalid upstream response")
	}
	switch p.op {
	case "RESPONSES":
		if !p.stream {
			return p.response(root, true)
		}
		kind := str(root["type"])
		i := num(root["output_index"])
		switch kind {
		case "response.output_text.delta", "response.refusal.delta":
			return p.send(str(root["delta"]), "", "", "", nil, nil)
		case "response.reasoning_summary_text.delta", "response.reasoning_text.delta":
			return p.send("", str(root["delta"]), "", "", nil, nil)
		case "response.output_item.added":
			m := object(root["item"])
			if m["type"] == "function_call" {
				c := ToolCall{Index: i, ID: str(m["call_id"]), Type: "function", Function: ToolFunction{Name: str(m["name"])}}
				p.calls[i] = c
				return p.send("", "", "", "", nil, []ToolCall{c})
			}
		case "response.function_call_arguments.delta":
			return p.send("", "", "", "", nil, []ToolCall{{Index: i, Function: ToolFunction{Arguments: str(root["delta"])}}})
		case "response.completed":
			if err := p.response(object(root["response"]), false); err != nil {
				return err
			}
			return errStreamDone
		case "response.failed", "response.incomplete", "error":
			return errors.New("response failed or incomplete")
		}
	case "ANTHROPIC_MESSAGES":
		if !p.stream {
			if str(root["stop_reason"]) == "" {
				return io.ErrUnexpectedEOF
			}
			p.completed = true
			for i, b := range list(root["content"]) {
				if err := p.anthropicBlock(i, object(b), false); err != nil {
					return err
				}
			}
			return p.send("", "", str(root["model"]), str(root["stop_reason"]), usageNative(root, p.op), nil)
		}
		switch str(root["type"]) {
		case "message_start":
			m := object(root["message"])
			return p.send("", "", str(m["model"]), "", usageNative(m, p.op), nil)
		case "content_block_start":
			i := num(root["index"])
			b := object(root["content_block"])
			if b["type"] == "thinking" {
				if len(p.blocks) >= 16 {
					return errors.New("block limit")
				}
				p.blocks[i] = b
			}
			return p.anthropicBlock(i, b, true)
		case "content_block_delta":
			d := object(root["delta"])
			i := num(root["index"])
			switch str(d["type"]) {
			case "text_delta":
				return p.send(str(d["text"]), "", "", "", nil, nil)
			case "signature_delta":
				if b := p.blocks[i]; b != nil {
					b["signature"] = str(b["signature"]) + str(d["signature"])
					if len(str(b["signature"])) > 64000 {
						return errors.New("signature limit")
					}
				}
			case "thinking_delta":
				if b := p.blocks[i]; b != nil {
					b["thinking"] = str(b["thinking"]) + str(d["thinking"])
					if len(str(b["thinking"])) > 256<<10 {
						return errors.New("thinking limit")
					}
				}
				return p.send("", str(d["thinking"]), "", "", nil, nil)
			case "input_json_delta":
				return p.send("", "", "", "", nil, []ToolCall{{Index: i, Function: ToolFunction{Arguments: str(d["partial_json"])}}})
			}
		case "content_block_stop":
			i := num(root["index"])
			if b := p.blocks[i]; b != nil {
				if str(b["signature"]) == "" {
					return errors.New("missing thinking signature")
				}
				if err := p.remember(b); err != nil {
					return err
				}
				delete(p.blocks, i)
			}
		case "message_delta":
			return p.send("", "", "", str(object(root["delta"])["stop_reason"]), usageNative(root, p.op), nil)
		case "message_stop":
			p.completed = true
			return errStreamDone
		case "error":
			return errors.New("anthropic error")
		}
	case "GEMINI_NATIVE":
		for _, v := range list(root["candidates"]) {
			c := object(v)
			if num(c["index"]) != 0 {
				continue
			}
			finish := str(c["finishReason"])
			if finish != "" {
				if !oneOf(finish, "STOP", "MAX_TOKENS") {
					return errors.New("generation blocked")
				}
				p.completed = true
			}
			for _, v := range list(object(c["content"])["parts"]) {
				m := object(v)
				if len(p.state) > 4096 {
					return errors.New("native part limit")
				}
				if err := p.remember(m); err != nil {
					return err
				}
				text, think := str(m["text"]), ""
				if m["thought"] == true {
					think = text
					text = ""
				}
				if f, ok := m["functionCall"]; ok {
					fn := object(f)
					args, _ := json.Marshal(fn["args"])
					id := str(fn["id"])
					if id == "" {
						id = "gemini_" + newID()
					}
					call := ToolCall{Index: len(p.calls), ID: id, Type: "function", Function: ToolFunction{Name: str(fn["name"]), Arguments: string(args)}}
					p.calls[call.Index] = call
					if err := p.send("", "", "", "", nil, []ToolCall{call}); err != nil {
						return err
					}
				}
				if err := p.send(text, think, str(root["modelVersion"]), finish, nil, nil); err != nil {
					return err
				}
			}
		}
		return p.send("", "", str(root["modelVersion"]), "", usageNative(root, p.op), nil)
	}
	return nil
}
func (p *nativeParser) response(root map[string]any, content bool) error {
	if root["status"] != "completed" || root["error"] != nil {
		return errors.New("incomplete response")
	}
	p.completed = true
	for i, v := range list(root["output"]) {
		m := object(v)
		switch str(m["type"]) {
		case "message":
			if content {
				for _, v := range list(m["content"]) {
					if err := p.send(str(object(v)["text"]), "", "", "", nil, nil); err != nil {
						return err
					}
				}
			}
		case "function_call":
			if content {
				if err := p.send("", "", "", "", nil, []ToolCall{{Index: i, ID: str(m["call_id"]), Type: "function", Function: ToolFunction{Name: str(m["name"]), Arguments: str(m["arguments"])}}}); err != nil {
					return err
				}
			}
		case "reasoning":
			if err := p.remember(m); err != nil {
				return err
			}
		}
	}
	return p.send("", "", str(root["model"]), "stop", usageNative(root, p.op), nil)
}
func (p *nativeParser) anthropicBlock(i int, b map[string]any, start bool) error {
	switch str(b["type"]) {
	case "text":
		return p.send(str(b["text"]), "", "", "", nil, nil)
	case "thinking":
		if !start {
			if err := p.remember(b); err != nil {
				return err
			}
		}
		return p.send("", str(b["thinking"]), "", "", nil, nil)
	case "tool_use":
		args := ""
		if !start {
			raw, _ := json.Marshal(b["input"])
			args = string(raw)
		}
		return p.send("", "", "", "", nil, []ToolCall{{Index: i, ID: str(b["id"]), Type: "function", Function: ToolFunction{Name: str(b["name"]), Arguments: args}}})
	}
	return nil
}

func (p *nativeParser) remember(m map[string]any) error {
	raw, _ := json.Marshal(m)
	p.stateBytes += len(raw)
	if p.stateBytes > 256<<10 || len(p.state) >= 4096 {
		return errors.New("native state limit")
	}
	p.state = append(p.state, m)
	return nil
}
