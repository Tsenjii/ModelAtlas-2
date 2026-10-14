package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Session struct {
	ID string `json:"id"`
	Selection
	Title     string `json:"title"`
	CreatedAt int64  `json:"created_at"`
}
type Message struct {
	ID          string       `json:"id"`
	SessionID   string       `json:"session_id"`
	Role        string       `json:"role"`
	Content     string       `json:"content"`
	Attachments []Attachment `json:"attachments,omitempty"`
	CreatedAt   int64        `json:"created_at"`
	MessageState
}

func newID() string {
	var v [16]byte
	if _, err := rand.Read(v[:]); err != nil {
		panic("OS randomness unavailable")
	}
	return hex.EncodeToString(v[:])
}
func (s *Server) listSessions(w http.ResponseWriter, r *http.Request) {
	limit, offset := pageRange(r, 200)
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at FROM sessions s WHERE NOT EXISTS(SELECT 1 FROM assistant_threads t WHERE t.session_id=s.id) ORDER BY s.rowid DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Session{}
	for rows.Next() {
		var x Session
		if rows.Scan(&x.ID, &x.ProviderID, &x.AccountID, &x.CredentialID, &x.ModelID, &x.Group, &x.Endpoint, &x.Title, &x.CreatedAt) == nil {
			out = append(out, x)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) addSession(w http.ResponseWriter, r *http.Request) {
	var x Session
	if !decodeJSON(w, r, &x) {
		return
	}
	if x.Endpoint == "" {
		x.Endpoint = "CHAT_COMPLETIONS"
	}
	if !s.validateSelection(r.Context(), x.Selection) {
		http.Error(w, "model/credential/scope not explicitly selected", 422)
		return
	}
	if len(x.Title) > 200 {
		http.Error(w, "title too long", 400)
		return
	}
	x.ID = newID()
	x.CreatedAt = now()
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO sessions(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at) VALUES(?,?,?,?,?,?,?,?,?)", x.ID, x.ProviderID, x.AccountID, x.CredentialID, x.ModelID, x.Group, x.Endpoint, x.Title, x.CreatedAt)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, x)
}
func (s *Server) history(ctx context.Context, id string) ([]Message, error) {
	var count int
	var size int64
	if e := s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(length(CAST(content AS BLOB))+length(media_metadata)+length(structured_json)),0) FROM messages WHERE session_id=?", id).Scan(&count, &size); e != nil {
		return nil, e
	}
	if count > 1024 || size > 32<<20 {
		return nil, errConversationLimit
	}
	return s.historyPage(ctx, id, 1024, 0, false)
}
func (s *Server) historyPage(ctx context.Context, id string, limit, offset int, reverse bool) ([]Message, error) {
	order := "ASC"
	if reverse {
		order = "DESC"
	}
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT id,session_id,role,content,media_metadata,created_at,structured_json FROM messages WHERE session_id=? ORDER BY rowid "+order+" LIMIT ? OFFSET ?", id, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var x Message
		var raw, structured string
		if err = rows.Scan(&x.ID, &x.SessionID, &x.Role, &x.Content, &raw, &x.CreatedAt, &structured); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(raw), &x.Attachments) != nil || json.Unmarshal([]byte(structured), &x.MessageState) != nil {
			return nil, errors.New("invalid history")
		}
		out = append(out, x)
	}
	if reverse {
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	}
	return out, rows.Err()
}
func (s *Server) listMessages(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("session_id")
	if !validID(id) {
		http.Error(w, "session_id required", 400)
		return
	}
	var n int
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT 1 FROM sessions WHERE id=?", id).Scan(&n) != nil {
		http.Error(w, "session not found", 404)
		return
	}
	limit, offset := pageRange(r, 100)
	out, err := s.historyPage(r.Context(), id, limit, offset, true)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, out)
}

type inferRequest struct {
	SessionID   string           `json:"session_id"`
	Prompt      string           `json:"prompt"`
	Attachments []Attachment     `json:"attachments,omitempty"`
	MaxTokens   int              `json:"max_tokens,omitempty"`
	Streaming   *bool            `json:"streaming,omitempty"`
	Tools       []ToolDefinition `json:"tools,omitempty"`
	ToolResults []ToolResult     `json:"tool_results,omitempty"`
	System      string           `json:"system,omitempty"`
}
type chatMessage struct {
	Role    string `json:"role"`
	Content any    `json:"content"`
	MessageState
}
type InferenceResult struct {
	Outcome   string         `json:"outcome"`
	Text      string         `json:"text"`
	Reasoning string         `json:"reasoning,omitempty"`
	ToolCalls []ToolCall     `json:"tool_calls,omitempty"`
	Scope     Selection      `json:"selection"`
	Status    int            `json:"http_status,omitempty"`
	Details   map[string]any `json:"details"`
}
type wireChunk struct {
	Model   string          `json:"model"`
	Error   json.RawMessage `json:"error"`
	Choices []struct {
		Index int `json:"index"`
		Delta struct {
			Content   string     `json:"content"`
			Reasoning string     `json:"reasoning_content"`
			ToolCalls []ToolCall `json:"tool_calls,omitempty"`
		} `json:"delta"`
		Message struct {
			Content   string     `json:"content"`
			Reasoning string     `json:"reasoning_content"`
			ToolCalls []ToolCall `json:"tool_calls,omitempty"`
		} `json:"message"`
		FinishReason *string `json:"finish_reason"`
	} `json:"choices"`
	Usage struct {
		Input         *int64 `json:"prompt_tokens"`
		Output        *int64 `json:"completion_tokens"`
		Total         *int64 `json:"total_tokens"`
		PromptDetails struct {
			Cache *int64 `json:"cached_tokens"`
		} `json:"prompt_tokens_details"`
		OutputDetails struct {
			Reasoning *int64 `json:"reasoning_tokens"`
		} `json:"completion_tokens_details"`
	} `json:"usage"`
}

func (s *Server) cancelInference(w http.ResponseWriter, r *http.Request) {
	var v struct {
		SessionID string `json:"session_id"`
		RequestID string `json:"request_id"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if (v.SessionID == "") == (v.RequestID == "") || v.RequestID != "" && !validID(v.RequestID) {
		http.Error(w, "select one active session or media request", 400)
		return
	}
	id := v.SessionID
	if v.RequestID != "" {
		id = "media:" + v.RequestID
	}
	s.flightMu.Lock()
	cancel := s.cancels[id]
	s.flightMu.Unlock()
	if cancel == nil {
		http.Error(w, "no active inference", 404)
		return
	}
	cancel()
	jsonOK(w, 200, map[string]bool{"cancelled": true})
}
func (s *Server) infer(w http.ResponseWriter, r *http.Request) {
	var v inferRequest
	if !decodeLimit(w, r, &v, 17<<20) {
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", 500)
		return
	}
	began := false
	emit := func(name string, data any) error {
		if !began {
			w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
			w.Header().Set("X-Accel-Buffering", "no")
			w.WriteHeader(200)
			began = true
		}
		return sendSSE(w, flusher, name, data)
	}
	result, code := s.execute(r.Context(), v, "PLAYGROUND", emit)
	if code != 0 && !began {
		http.Error(w, result.Outcome, code)
		return
	}
	if result.Outcome == "SUCCESS" {
		_ = emit("done", result)
	} else {
		_ = emit("error", map[string]string{"category": result.Outcome})
	}
}

// Playground, explicit Test and Compare share this engine. No key/provider fallback.
func (s *Server) execute(parent context.Context, v inferRequest, source string, emit func(string, any) error) (result InferenceResult, code int) {
	result = InferenceResult{Outcome: "INVALID_REQUEST", Details: map[string]any{}}
	v.Prompt = strings.TrimSpace(v.Prompt)
	if v.Prompt == "" && len(v.Attachments) > 0 {
		return result, 400
	}
	if !validID(v.SessionID) || (v.Prompt == "" && len(v.ToolResults) == 0) || len(v.Prompt) > 32000 || len(v.System) > 16000 || len(v.Attachments) > 3 || v.MaxTokens < 0 || v.MaxTokens > 32768 {
		return result, 400
	}
	seen := map[string]bool{}
	for _, a := range v.Attachments {
		if seen[a.Kind] || a.validate() != nil {
			return result, 400
		}
		seen[a.Kind] = true
	}
	if v.MaxTokens == 0 {
		v.MaxTokens = 512
	}
	ctx, cancel := context.WithTimeout(parent, 120*time.Second)
	defer cancel()
	var scope Selection
	err := s.Store.DB.QueryRowContext(ctx, "SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM sessions WHERE id=?", v.SessionID).Scan(&scope.ProviderID, &scope.AccountID, &scope.CredentialID, &scope.ModelID, &scope.Group, &scope.Endpoint)
	if err != nil {
		result.Outcome = "SESSION_MISSING"
		return result, 404
	}
	result.Scope = scope
	if !s.validateSelection(ctx, scope) {
		result.Outcome = "SELECTION_UNAVAILABLE"
		return result, 422
	}
	operation := s.operation(ctx, scope)
	if !conversationOperation(operation) {
		result.Outcome = "TRANSPORT_UNSUPPORTED"
		return result, 422
	}
	if !s.beginFlight(v.SessionID) {
		result.Outcome = "REQUEST_IN_PROGRESS"
		return result, 409
	}
	defer s.endFlight(v.SessionID)
	s.flightMu.Lock()
	if s.cancels == nil {
		s.cancels = map[string]context.CancelFunc{}
	}
	s.cancels[v.SessionID] = cancel
	s.flightMu.Unlock()
	history, err := s.history(ctx, v.SessionID)
	if err != nil {
		result.Outcome = "HISTORY_READ"
		if errors.Is(err, errConversationLimit) {
			result.Outcome = "CONVERSATION_LIMIT_START_NEW_CONVERSATION"
			return result, 413
		}
		return result, 500
	}
	caps := s.capabilities(ctx, scope)
	tools, err := s.sessionTools(ctx, v.SessionID, v.Tools)
	if err != nil {
		result.Outcome = "INVALID_TOOLS"
		return result, 400
	}
	turns, err := toolContinuation(history, v)
	if err != nil {
		result.Outcome = "TOOL_RESULTS_REQUIRED_OR_INVALID"
		return result, 422
	}
	needsTools := len(tools) > 0
	for _, turn := range turns {
		needsTools = needsTools || len(turn.ToolCalls) > 0 || turn.Role == "tool"
	}
	if needsTools && !oneOf("TOOL_CALLING", caps...) && !oneOf("FUNCTION_CALLING", caps...) {
		result.Outcome = "CAPABILITY_REQUIRED_TOOL_CALLING"
		return result, 422
	}
	messages := []chatMessage{}
	total := 0
	for _, turn := range turns {
		attachments := turn.Attachments
		if len(attachments) > 0 && turn.ID != "" {
			attachments = s.mediaFor(turn.ID)
			if len(attachments) != len(turn.Attachments) {
				result.Outcome = "ATTACHMENT_HISTORY_UNAVAILABLE_START_NEW_CONVERSATION"
				return result, 422
			}
		}
		for _, a := range attachments {
			if !oneOf(a.required(), caps...) {
				result.Outcome = "CAPABILITY_REQUIRED_" + a.required()
				return result, 422
			}
			if a.validate() != nil {
				return result, 422
			}
			total += len(a.Data)
		}
		stateBytes, _ := json.Marshal(turn.MessageState)
		total += len(turn.Content) + len(stateBytes)
		if total > 32<<20 {
			result.Outcome = "CONVERSATION_LIMIT_START_NEW_CONVERSATION"
			return result, 413
		}
		var content any = turn.Content
		if len(attachments) > 0 {
			parts := []map[string]any{{"type": "text", "text": turn.Content}}
			for _, a := range attachments {
				parts = append(parts, a.wire())
			}
			content = parts
		}
		messages = append(messages, chatMessage{Role: turn.Role, Content: content, MessageState: turn.MessageState})
	}
	provider, key, err := s.endpointAuth(ctx, scope.ProviderID, scope.AccountID, scope.CredentialID)
	if err != nil {
		result.Outcome = "CREDENTIAL_UNAVAILABLE"
		return result, 422
	}
	if !s.groupBound(ctx, scope) {
		result.Outcome = "GROUP_NOT_BOUND_TO_SELECTED_CREDENTIAL"
		return result, 422
	}
	secrets := []string{key}
	for _, f := range provider.AuthFields {
		secrets = append(secrets, f.Secret)
	}
	if source == "ASSISTANT_AGENT" {
		all, e := s.knownSecrets(ctx)
		if e != nil {
			result.Outcome = "VAULT_UNAVAILABLE"
			return result, 503
		}
		secrets = append(secrets, all...)
	}
	cfg := s.config(ctx, provider)
	endpoint, err := s.requestEndpoint(ctx, provider, cfg, scope.Endpoint, scope.ModelID)
	if err != nil {
		return result, 422
	}
	stream := v.Streaming == nil || *v.Streaming
	payload, err := protocolPayload(operation, scope.ModelID, v.MaxTokens, stream, messages, tools)
	if err != nil {
		result.Outcome = "PROTOCOL_INPUT_UNSUPPORTED"
		return result, 422
	}
	if v.System != "" {
		switch operation {
		case "CHAT_COMPLETIONS":
			payload["messages"] = append([]map[string]any{{"role": "system", "content": v.System}}, payload["messages"].([]map[string]any)...)
		case "RESPONSES":
			payload["instructions"] = v.System
		case "ANTHROPIC_MESSAGES":
			payload["system"] = v.System
		case "GEMINI_NATIVE":
			payload["systemInstruction"] = map[string]any{"parts": []any{map[string]any{"text": v.System}}}
		}
	}
	if operation == "GEMINI_NATIVE" {
		endpoint = geminiEndpoint(endpoint, stream)
	}
	body, _ := json.Marshal(payload)
	if len(body) > 32<<20 {
		result.Outcome = "CONVERSATION_LIMIT_START_NEW_CONVERSATION"
		return result, 413
	}
	upstream, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(body))
	if err != nil {
		return result, 422
	}
	upstream.Header.Set("Content-Type", "application/json")
	upstream.Header.Set("Accept", "text/event-stream")
	if operation == "ANTHROPIC_MESSAGES" && upstream.Header.Get("anthropic-version") == "" {
		upstream.Header.Set("anthropic-version", "2023-06-01")
	}
	if err = s.authorizeUpstream(upstream, provider, key, cfg); err != nil {
		return result, 422
	}
	if err = s.chargeNetwork(ctx, source == "CRAWLER_VERIFY"); err != nil {
		result.Outcome = "RESEARCH_BUDGET_EXHAUSTED"
		return result, 429
	}
	userID := newID()
	if len(v.Attachments) > 0 && !s.rememberMedia(userID, v.Attachments) {
		result.Outcome = "ATTACHMENT_MEMORY_LIMIT"
		return result, 413
	}
	for _, secret := range secrets {
		if secret != "" && jsonContainsSecret(payload, secret) {
			result.Outcome = "SECRET_IN_REQUEST_CONTENT"
			return result, 422
		}
	}
	if v.Tools != nil {
		if err = s.saveSessionTools(ctx, v.SessionID, tools); err != nil {
			return result, 500
		}
	}
	for _, turn := range turns[len(history):] {
		if turn.Role == "user" {
			turn.ID = userID
		}
		if err = s.saveMessage(ctx, v.SessionID, turn); err != nil {
			result.Outcome = "HISTORY_SAVE"
			return result, 500
		}
	}
	started := time.Now()
	sampleID := newID()
	if _, err = s.Store.DB.ExecContext(ctx, "INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at,credential_version) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,?)", sampleID, scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint, operation, source, started.UnixMilli(), provider.CredentialVersion); err != nil {
		result.Outcome = "TELEMETRY_SAVE"
		return result, 500
	}

	result.Outcome = "NETWORK"
	var ttfb, ttft, lastGenerated *int64
	var inputs, outputs *int64
	var text, reasoning strings.Builder
	calls := map[int]*ToolCall{}
	var protocolState json.RawMessage
	defer func() {
		if ctx.Err() != nil {
			if parent.Err() == context.Canceled || ctx.Err() == context.Canceled {
				result.Outcome = "CANCELLED"
			} else {
				result.Outcome = "TIMEOUT"
			}
		}
		result.Text = text.String()
		for _, secret := range secrets {
			if secret != "" && (strings.Contains(text.String()+reasoning.String(), secret)) {
				result.Text = ""
				result.Reasoning = ""
				result.Outcome = "SECRET_IN_RESPONSE"
				text.Reset()
				reasoning.Reset()
				calls = map[int]*ToolCall{}
			}
		}
		result.Reasoning = reasoning.String()
		result.ToolCalls = nil
		if result.Outcome == "SUCCESS" {
			result.ToolCalls = orderedCalls(calls)
		}
		latency := time.Since(started).Milliseconds()
		result.Details["latency_ms"] = latency
		result.Details["retry_count"] = 0
		result.Details["streaming"] = stream
		if ttfb != nil {
			result.Details["ttfb_ms"] = *ttfb
		}
		if ttft != nil {
			result.Details["ttft_ms"] = *ttft
		}
		if inputs != nil {
			result.Details["input_tokens"] = *inputs
		}
		if outputs != nil {
			result.Details["output_tokens"] = *outputs
		}
		if stream && ttft != nil && lastGenerated != nil && outputs != nil && *outputs > 1 && *lastGenerated > *ttft {
			result.Details["tps"] = float64(*outputs-1) * 1000 / float64(*lastGenerated-*ttft)
		}
		raw, _ := json.Marshal(result.Details)
		var status any
		if result.Status > 0 {
			status = result.Status
		}
		saveCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		_, err := s.Store.DB.ExecContext(saveCtx, "UPDATE telemetry SET outcome=?,status_code=?,ttfb_ms=?,ttft_ms=?,latency_ms=?,input_tokens=?,output_tokens=?,details_json=? WHERE id=?", result.Outcome, status, ttfb, ttft, latency, inputs, outputs, string(raw), sampleID)
		if err != nil {
			result.Outcome = "TELEMETRY_SAVE"
		}
	}()
	client := *s.Client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(upstream)
	if err != nil {
		return result, 502
	}
	defer resp.Body.Close()
	result.Status = resp.StatusCode
	elapsed := time.Since(started).Milliseconds()
	ttfb = &elapsed
	rates := map[string]string{}
	for name, values := range resp.Header {
		low := strings.ToLower(name)
		if strings.HasPrefix(low, "x-ratelimit-") || low == "retry-after" {
			value := safeText(strings.Join(values, ","), 256, secrets...)
			if value != "" {
				rates[low] = value
			}
		}
	}
	result.Details["rate_limit_headers"] = rates
	if resp.StatusCode != 200 {
		result.Outcome = classifyHTTP(resp.StatusCode)
		return result, 502
	}
	if err = emit("start", map[string]any{"selection": scope}); err != nil {
		cancel()
		return result, 0
	}
	textGuard := streamGuard{secrets: secrets, emit: func(v string) error { return emit("delta", map[string]string{"text": v}) }}
	thinkGuard := streamGuard{secrets: secrets, emit: func(v string) error { return emit("reasoning", map[string]string{"text": v}) }}
	consume := func(raw []byte) error {
		var chunk wireChunk
		if json.Unmarshal(raw, &chunk) != nil || len(chunk.Error) > 0 && string(chunk.Error) != "null" {
			return errors.New("invalid stream")
		}
		if chunk.Model != "" {
			result.Details["response_model_id"] = safeText(chunk.Model, 128, secrets...)
		}
		for _, choice := range chunk.Choices {
			if choice.Index != 0 {
				continue
			}
			content, think := choice.Delta.Content, choice.Delta.Reasoning
			deltas := choice.Delta.ToolCalls
			if !stream {
				deltas = choice.Message.ToolCalls
			}
			for position, delta := range deltas {
				// Non-streaming OpenAI tool calls have no delta index. Their
				// array positions identify distinct calls; treating absent indices
				// as zero would concatenate unrelated names and arguments.
				if !stream {
					delta.Index = position
				}
				call := calls[delta.Index]
				if call == nil {
					if len(calls) >= 16 || delta.Index < 0 || delta.Index > 63 {
						return errors.New("tool limit")
					}
					call = &ToolCall{Index: delta.Index, Type: "function"}
					calls[delta.Index] = call
				}
				if delta.Function.Arguments != "" && stream {
					n := time.Since(started).Milliseconds()
					lastGenerated = &n
					if ttft == nil {
						ttft = &n
					}
				}
				if delta.ID != "" {
					call.ID = delta.ID
				}
				call.Function.Name += delta.Function.Name
				call.Function.Arguments += delta.Function.Arguments
				if len(call.Function.Arguments) > 32000 || len(call.Function.Name) > 64 {
					return errors.New("tool arguments limit")
				}
			}
			if !stream {
				content, think = choice.Message.Content, choice.Message.Reasoning
			}
			if text.Len()+reasoning.Len()+len(content)+len(think) > 256<<10 {
				return errors.New("response limit")
			}
			if content != "" || think != "" {
				n := time.Since(started).Milliseconds()
				lastGenerated = &n
				if stream && ttft == nil {
					ttft = &n
				}
			}
			if content != "" {
				text.WriteString(content)
				if err := textGuard.write(content); err != nil {
					return err
				}
			}
			if think != "" {
				reasoning.WriteString(think)
				if err := thinkGuard.write(think); err != nil {
					return err
				}
			}
			if choice.FinishReason != nil {
				result.Details["finish_reason"] = safeText(*choice.FinishReason, 64, secrets...)
			}
		}
		counts := map[string]*int64{"total_tokens": chunk.Usage.Total, "reasoning_tokens": chunk.Usage.OutputDetails.Reasoning, "cache_read_tokens": chunk.Usage.PromptDetails.Cache}
		for name, count := range counts {
			if count != nil && *count >= 0 {
				result.Details[name] = *count
			}
		}
		if chunk.Usage.Input != nil && *chunk.Usage.Input >= 0 {
			inputs = chunk.Usage.Input
		}
		if chunk.Usage.Output != nil && *chunk.Usage.Output >= 0 {
			outputs = chunk.Usage.Output
		}
		return nil
	}
	if operation == "CHAT_COMPLETIONS" {
		if !stream {
			raw, e := readBounded(resp.Body, 1<<20)
			if e != nil {
				err = e
			} else {
				err = consume(raw)
			}
		} else {
			err = readSSE(resp.Body, consume)
		}
	} else {
		err = readProtocol(resp.Body, operation, stream, consume, func(raw json.RawMessage) { protocolState = raw })
	}
	if err != nil {
		result.Outcome = "PARSER"
		if err == io.ErrUnexpectedEOF {
			_ = emit("error", map[string]string{"category": "TRUNCATED_STREAM"})
		}
		return result, 0
	}
	completedCalls := orderedCalls(calls)
	if !validCalls(completedCalls, tools) {
		result.Outcome = "INVALID_TOOL_CALL"
		return result, 0
	}
	generated, _ := json.Marshal(Message{Content: text.String() + reasoning.String(), MessageState: MessageState{ToolCalls: completedCalls, ProtocolState: protocolState}})
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(generated, []byte(secret)) {
			result.Outcome = "SECRET_IN_RESPONSE"
			return result, 0
		}
	}
	if err = textGuard.flush(); err != nil {
		cancel()
		return result, 0
	}
	if err = thinkGuard.flush(); err != nil {
		cancel()
		return result, 0
	}
	if ctx.Err() != nil {
		return result, 0
	}
	if err = s.saveMessage(ctx, v.SessionID, Message{Role: "assistant", Content: text.String(), MessageState: MessageState{ToolCalls: completedCalls, ProtocolState: protocolState}}); err != nil {
		result.Outcome = "HISTORY_SAVE"
		return result, 0
	}
	if len(completedCalls) > 0 {
		if err = emit("tool_calls", completedCalls); err != nil {
			cancel()
			return result, 0
		}
	}
	result.Outcome = "SUCCESS"
	return result, 0
}
func classifyHTTP(status int) string {
	switch status {
	case 401:
		return "AUTH_INVALID"
	case 402:
		return "PAYMENT_REQUIRED"
	case 403:
		return "INFERENCE_DENIED"
	case 404:
		return "MODEL_UNAVAILABLE"
	case 410:
		return "GONE"
	case 429:
		return "RATE_LIMITED"
	}
	if status >= 500 {
		return "UPSTREAM_5XX"
	}
	return "HTTP_ERROR"
}

// SSE uses complete data frames, accepts CRLF/comments, and requires an explicit terminator.
func readSSE(body io.Reader, consume func([]byte) error) error {
	scanner := bufio.NewScanner(io.LimitReader(body, 16<<20))
	scanner.Buffer(make([]byte, 4096), 1<<20)
	var data []string
	frameBytes := 0
	dispatch := func() (bool, error) {
		if len(data) == 0 {
			return false, nil
		}
		raw := strings.Join(data, "\n")
		data = nil
		frameBytes = 0
		if strings.TrimSpace(raw) == "[DONE]" {
			return true, nil
		}
		return false, consume([]byte(raw))
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			done, err := dispatch()
			if err != nil {
				return err
			}
			if done {
				return nil
			}
			continue
		}
		if strings.HasPrefix(line, "data:") {
			value := strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " ")
			frameBytes += len(value)
			if frameBytes > 1<<20 {
				return errors.New("SSE frame limit")
			}
			data = append(data, value)
		}
	}
	if scanner.Err() != nil {
		return scanner.Err()
	}
	done, err := dispatch()
	if err != nil {
		return err
	}
	if done {
		return nil
	}
	return io.ErrUnexpectedEOF
}
func sendSSE(w http.ResponseWriter, f http.Flusher, name string, data any) error {
	b, err := json.Marshal(data)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(w, "event: %s\ndata: %s\n\n", name, b)
	f.Flush()
	return err
}
func (s *Server) groupBound(ctx context.Context, v Selection) bool {
	var group string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT group_id FROM credential_context WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&group)
	if err == sql.ErrNoRows {
		return v.Group == ""
	}
	return err == nil && group == v.Group
}
func (s *Server) listTelemetry(w http.ResponseWriter, r *http.Request) {
	limit, offset := pageRange(r, 100)
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,status_code,ttfb_ms,ttft_ms,latency_ms,input_tokens,output_tokens,created_at,details_json FROM telemetry ORDER BY rowid DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var p, a, c, m, g, e, o, src, result, raw string
		var status, ttfb, ttft, lat, in, outTokens sql.NullInt64
		var at int64
		if rows.Scan(&p, &a, &c, &m, &g, &e, &o, &src, &result, &status, &ttfb, &ttft, &lat, &in, &outTokens, &at, &raw) != nil {
			continue
		}
		obj := map[string]any{"provider_id": p, "account_id": a, "credential_id": c, "model_id": m, "group": g, "endpoint": e, "operation": o, "source": src, "outcome": result, "created_at": at, "http_status": nullable(status), "ttfb_ms": nullable(ttfb), "ttft_ms": nullable(ttft), "latency_ms": nullable(lat), "input_tokens": nullable(in), "output_tokens": nullable(outTokens)}
		var details map[string]any
		_ = json.Unmarshal([]byte(raw), &details)
		obj["details"] = details
		out = append(out, obj)
	}
	jsonOK(w, 200, out)
}
