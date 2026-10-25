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
	Title        string `json:"title"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
	Pinned       bool   `json:"pinned"`
	Archived     bool   `json:"archived"`
	Revision     int    `json:"revision"`
	MessageCount int    `json:"message_count"`
	Preview      string `json:"preview,omitempty"`
}
type Message struct {
	ID             string       `json:"id"`
	SessionID      string       `json:"session_id"`
	Role           string       `json:"role"`
	Content        string       `json:"content"`
	Attachments    []Attachment `json:"attachments,omitempty"`
	CreatedAt      int64        `json:"created_at"`
	SampleID       string       `json:"sample_id,omitempty"`
	RequestOutcome string       `json:"request_outcome,omitempty"`
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
	s.searchSessions(w, r)
}
func (s *Server) addSession(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Session
		ClientRequestID string `json:"client_request_id"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	x := input.Session
	var requestID string
	if input.ClientRequestID != "" {
		var err error
		requestID, err = conversationRequestID(input.ClientRequestID)
		if err != nil {
			http.Error(w, "invalid conversation request id", 400)
			return
		}
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
	if x.Title == "" {
		x.Title = "新對話 · " + x.ModelID
	}
	if !s.safeSessionTitle(r.Context(), x.Title) {
		http.Error(w, "invalid conversation title", 422)
		return
	}
	x.ID = newID()
	if requestID != "" {
		x.ID = requestID
	}
	x.CreatedAt = now()
	x.UpdatedAt = x.CreatedAt
	statement := "INSERT INTO sessions(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at) VALUES(?,?,?,?,?,?,?,?,?)"
	if requestID != "" {
		statement += " ON CONFLICT(id) DO NOTHING"
	}
	result, err := s.Store.DB.ExecContext(r.Context(), statement, x.ID, x.ProviderID, x.AccountID, x.CredentialID, x.ModelID, x.Group, x.Endpoint, x.Title, x.CreatedAt)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if requestID != "" {
		inserted, err := result.RowsAffected()
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		if inserted == 0 {
			var stored Session
			err = s.Store.DB.QueryRowContext(r.Context(), `SELECT s.id,s.provider_id,s.account_id,s.credential_id,s.model_id,s.group_id,s.endpoint,s.title,s.created_at,MAX(s.created_at,COALESCE(sm.updated_at,0),COALESCE((SELECT MAX(created_at) FROM messages WHERE session_id=s.id),0)),COALESCE(sm.pinned,0),COALESCE(sm.archived,0),COALESCE(sm.revision,0),(SELECT COUNT(*) FROM messages WHERE session_id=s.id) FROM sessions s LEFT JOIN session_metadata sm ON sm.session_id=s.id WHERE s.id=?`, requestID).Scan(&stored.ID, &stored.ProviderID, &stored.AccountID, &stored.CredentialID, &stored.ModelID, &stored.Group, &stored.Endpoint, &stored.Title, &stored.CreatedAt, &stored.UpdatedAt, &stored.Pinned, &stored.Archived, &stored.Revision, &stored.MessageCount)
			if err != nil {
				http.Error(w, "database error", 500)
				return
			}
			if stored.Selection != x.Selection || stored.Title != x.Title || stored.Archived {
				http.Error(w, "conversation request id already used", 409)
				return
			}
			x = stored
			w.Header().Set("Idempotent-Replayed", "true")
		}
	}
	jsonOK(w, 201, x)
}

// UUID v4 request identities have their own session-ID namespace. SQLite's
// existing primary key makes transport retries atomic across restarts; this
// applies only to conversation creation, never inference or other writes.
func conversationRequestID(value string) (string, error) {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' || value[14] != '4' || !strings.ContainsRune("89ab", rune(value[19])) || value != strings.ToLower(value) {
		return "", errors.New("invalid conversation request id")
	}
	packed := strings.ReplaceAll(value, "-", "")
	if len(packed) != 32 {
		return "", errors.New("invalid conversation request id")
	}
	if _, err := hex.DecodeString(packed); err != nil {
		return "", errors.New("invalid conversation request id")
	}
	return "r-" + packed, nil
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
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT m.id,m.session_id,m.role,m.content,m.media_metadata,m.created_at,m.structured_json,COALESCE(mr.sample_id,''),COALESCE(t.outcome,'') FROM messages m LEFT JOIN message_requests mr ON mr.message_id=m.id LEFT JOIN telemetry t ON t.id=mr.sample_id WHERE m.session_id=? ORDER BY m.rowid "+order+" LIMIT ? OFFSET ?", id, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var x Message
		var raw, structured string
		if err = rows.Scan(&x.ID, &x.SessionID, &x.Role, &x.Content, &raw, &x.CreatedAt, &structured, &x.SampleID, &x.RequestOutcome); err != nil {
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
	RetryID     string           `json:"retry_id,omitempty"`
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
		Input         *int64          `json:"prompt_tokens"`
		Output        *int64          `json:"completion_tokens"`
		Total         *int64          `json:"total_tokens"`
		ToolPrompt    *int64          `json:"tool_prompt_tokens"`
		Cost          json.RawMessage `json:"cost"`
		PromptDetails struct {
			Cache  *int64 `json:"cached_tokens"`
			Writes *int64 `json:"cache_write_tokens"`
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
	if !s.manualSession(r.Context(), v.SessionID) {
		http.Error(w, "manual conversation missing", 404)
		return
	}
	if s.sessionArchived(r.Context(), v.SessionID) {
		http.Error(w, "conversation is archived; restore to continue", 409)
		return
	}
	options, e := s.sessionSettings(r.Context(), v.SessionID)
	if e != nil {
		http.Error(w, "settings unavailable", 503)
		return
	}
	if v.System == "" {
		v.System = options.System
	}
	if v.MaxTokens == 0 {
		v.MaxTokens = options.MaxTokens
	}
	if v.Streaming == nil {
		v.Streaming = &options.Streaming
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
		if id, ok := result.Details["sample_id"].(string); ok {
			w.Header().Set("X-ModelAtlas-Sample-ID", id)
		}
		if result.Status >= 400 {
			writeProviderProblem(w, &providerReadError{Code: "UPSTREAM_HTTP", UpstreamStatus: result.Status}, "inference")
		} else if result.Outcome == "NETWORK" || result.Outcome == "TIMEOUT" {
			problemCode := "UPSTREAM_NETWORK"
			if result.Outcome == "TIMEOUT" {
				problemCode = "UPSTREAM_TIMEOUT"
			}
			writeProviderProblem(w, &providerReadError{Code: problemCode}, "inference")
		} else if strings.HasPrefix(result.Outcome, "KILO_") {
			writeProviderProblem(w, &providerReadError{Code: result.Outcome}, "inference")
		} else {
			http.Error(w, result.Outcome, code)
		}
		return
	}
	if result.Outcome == "SUCCESS" {
		_ = emit("done", result)
	} else {
		_ = emit("error", map[string]any{"category": result.Outcome, "http_status": result.Status, "sample_id": result.Details["sample_id"]})
	}
}

// Playground, explicit Test and Compare share this engine. No key/provider fallback.
func (s *Server) execute(parent context.Context, v inferRequest, source string, emit func(string, any) error) (result InferenceResult, code int) {
	result = InferenceResult{Outcome: "INVALID_REQUEST", Details: map[string]any{}}
	v.Prompt = strings.TrimSpace(v.Prompt)
	if v.Prompt == "" && len(v.Attachments) > 0 {
		return result, 400
	}
	if !validID(v.SessionID) || (v.Prompt == "" && len(v.ToolResults) == 0 && v.RetryID == "") || len(v.Prompt) > 32000 || len(v.System) > 16000 || len(v.Attachments) > 3 || v.MaxTokens < 0 || v.MaxTokens > 32768 || v.RetryID != "" && (source != "PLAYGROUND" || !validID(v.RetryID) || v.Prompt != "" || len(v.ToolResults) > 0 || len(v.Attachments) > 0) {
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
	if source == "PLAYGROUND" && s.sessionArchived(ctx, v.SessionID) {
		result.Outcome = "CONVERSATION_ARCHIVED"
		return result, 409
	}
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
	if v.RetryID != "" && !s.retryableRequest(ctx, v.SessionID, v.RetryID, history) {
		result.Outcome = "RETRY_STALE_OR_UNAVAILABLE"
		return result, 409
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
	allAttachments := []Attachment{}
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
		allAttachments = append(allAttachments, attachments...)
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
	{
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
	if err = s.guardFactoryKilo(ctx, scope, provider, cfg, endpoint, key); err != nil {
		if ctx.Err() != nil {
			result.Outcome = "CANCELLED"
			return result, 499
		}
		var admission *conversationAdmissionError
		if errors.As(err, &admission) {
			result.Outcome = admission.Outcome
			result.Details["research_preflight_budget"] = admission.Cause
			if errors.Is(err, errResearchProbeScopeChanged) || errors.Is(err, errResearchProbeApproval) {
				result.Outcome = "RESEARCH_PLAN_CHANGED"
				return result, 409
			}
			return result, admission.Status
		}
		var problem *providerReadError
		if errors.As(err, &problem) {
			result.Outcome = problem.Code
		} else {
			result.Outcome = "KILO_CATALOG_UNAVAILABLE"
		}
		return result, 503
	}
	stream := v.Streaming == nil || *v.Streaming
	var custom *CustomProtocol
	var payload map[string]any
	if operation == "CUSTOM" {
		custom, err = s.customProtocol(ctx, scope)
		if err == nil {
			payload, err = customPayload(custom, scope.ModelID, v.System, v.MaxTokens, stream, messages, tools)
		}
	} else {
		payload, err = protocolPayload(operation, scope.ModelID, v.MaxTokens, stream, messages, tools)
	}
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
	body, err := encodeRequestJSON(payload, 32<<20)
	if errors.Is(err, errRequestJSONLimit) {
		result.Outcome = "CONVERSATION_LIMIT_START_NEW_CONVERSATION"
		return result, 413
	}
	if err != nil {
		result.Outcome = "PROTOCOL_INPUT_UNSUPPORTED"
		return result, 422
	}
	upstream, err := http.NewRequestWithContext(ctx, "POST", endpoint, body.reader())
	if err != nil {
		return result, 422
	}
	upstream.ContentLength = body.size
	upstream.GetBody = func() (io.ReadCloser, error) { return body.reader(), nil }
	upstream.Header.Set("Content-Type", "application/json")
	upstream.Header.Set("Accept", "text/event-stream")
	if operation == "ANTHROPIC_MESSAGES" && upstream.Header.Get("anthropic-version") == "" {
		upstream.Header.Set("anthropic-version", "2023-06-01")
	}
	if err = s.authorizeUpstream(upstream, provider, key, cfg); err != nil {
		return result, 422
	}
	userID := newID()
	if body.containsSecret(secrets) {
		result.Outcome = "SECRET_IN_REQUEST_CONTENT"
		return result, 422
	}
	for _, a := range allAttachments {
		if attachmentContainsSecret(a, secrets) {
			result.Outcome = "SECRET_IN_REQUEST_CONTENT"
			return result, 422
		}
	}
	if len(v.Attachments) > 0 && !s.rememberMedia(userID, v.Attachments) {
		result.Outcome = "ATTACHMENT_MEMORY_LIMIT"
		return result, 413
	}
	admitted := false
	defer func() {
		if !admitted && len(v.Attachments) > 0 {
			s.forgetMedia(userID)
		}
	}()
	messageIDs := []string{}
	pendingMessages := []Message{}
	for _, turn := range turns[len(history):] {
		if turn.Role == "user" {
			turn.ID = userID
		}
		if turn.ID == "" {
			turn.ID = newID()
		}
		messageIDs = append(messageIDs, turn.ID)
		pendingMessages = append(pendingMessages, turn)
	}
	conditionHash := authenticityCondition(payload, operation, stream, upstream.Header.Get("anthropic-version"), body.size)
	started := time.Now()
	sampleID := newID()
	receipt, err := s.newReceipt(ctx, scope, provider, started.UnixMilli(), secrets)
	if err != nil {
		result.Outcome = "RECEIPT_SAVE"
		return result, 500
	}
	result.Details["sample_id"] = sampleID
	result.Details["receipt"] = receipt
	var updatedTools []ToolDefinition
	if v.Tools != nil {
		updatedTools = tools
	}
	record := requestRecord{ID: sampleID, Selection: scope, Operation: operation, Source: source, At: started.UnixMilli(), CredentialVersion: provider.CredentialVersion, SessionID: v.SessionID, RetryID: v.RetryID, MessageIDs: messageIDs, Details: result.Details, MaxOutputTokens: v.MaxTokens}
	if err = s.admitConversation(ctx, record, pendingMessages, updatedTools); err != nil {
		delete(result.Details, "sample_id")
		delete(result.Details, "receipt")
		result.Outcome = "TELEMETRY_SAVE"
		if errors.Is(err, errResearchProbeScopeChanged) || errors.Is(err, errResearchProbeApproval) {
			result.Outcome = "RESEARCH_PLAN_CHANGED"
			return result, 409
		}
		var runLimit *researchRunBudgetError
		if errors.As(err, &runLimit) {
			result.Details["research_run_budget"] = runLimit
		}
		var limit *researchInferenceBudgetError
		if errors.As(err, &limit) {
			result.Details["research_inference_budget"] = limit
		}
		var resourceLimit *researchResourceBudgetError
		if errors.As(err, &resourceLimit) {
			result.Details["research_resource_budget"] = resourceLimit
		}
		var admissionError *conversationAdmissionError
		if errors.As(err, &admissionError) {
			result.Outcome = admissionError.Outcome
			return result, admissionError.Status
		}
		return result, 500
	}
	admitted = true

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
		s.completeReceipt(saveCtx, receipt, scope, provider, operation, result.Outcome, inputs, outputs, result.Details, secrets)
		raw, _ = json.Marshal(result.Details)
		_, err := s.Store.DB.ExecContext(saveCtx, "UPDATE telemetry SET outcome=?,status_code=?,ttfb_ms=?,ttft_ms=?,latency_ms=?,input_tokens=?,output_tokens=?,details_json=? WHERE id=?", result.Outcome, status, ttfb, ttft, latency, inputs, outputs, string(raw), sampleID)
		if err != nil {
			result.Outcome = "TELEMETRY_SAVE"
		} else {
			if source == "CRAWLER_VERIFY" {
				if err := s.settleResearchResources(saveCtx, sampleID, operation, result.Outcome, inputs, outputs, result.Details); err != nil {
					result.Details["resource_budget_settlement"] = "UNAVAILABLE_RESERVATION_RETAINED"
				}
			}
			// Missing auxiliary proof leaves the sample unverified. It must never
			// turn a saved answer/history into a failed inference.
			_ = s.captureAuthenticity(saveCtx, sampleID, provider.CredentialStamp, authenticityCapture{ConditionHash: conditionHash, ConfigHash: connectorHash(cfg), EndpointHash: authenticityEndpointHash(endpoint, operation), PayloadBytes: body.size, MaxOutputTokens: v.MaxTokens, ToolsOffered: len(tools), ValidToolCalls: len(result.ToolCalls), TextBytes: text.Len(), ReasoningBytes: reasoning.Len()}, authenticityTraffic{ID: sampleID, Selection: scope, Operation: operation, Source: source, Outcome: result.Outcome, CredentialVersion: provider.CredentialVersion, At: started.UnixMilli(), Input: inputs, Output: outputs, Details: string(raw)})
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
	result.Details["rate_limit_headers"] = responseRateHeaders(resp.Header, secrets)
	if resp.StatusCode != 200 {
		result.Outcome = classifyHTTP(resp.StatusCode)
		return result, 502
	}
	if err = emit("start", map[string]any{"selection": scope, "sample_id": sampleID}); err != nil {
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
		counts := map[string]*int64{"total_tokens": chunk.Usage.Total, "tool_prompt_tokens": chunk.Usage.ToolPrompt, "reasoning_tokens": chunk.Usage.OutputDetails.Reasoning, "cache_read_tokens": chunk.Usage.PromptDetails.Cache, "cache_write_tokens": chunk.Usage.PromptDetails.Writes}
		for _, count := range []*int64{chunk.Usage.Input, chunk.Usage.Output, chunk.Usage.Total, chunk.Usage.ToolPrompt, chunk.Usage.OutputDetails.Reasoning, chunk.Usage.PromptDetails.Cache, chunk.Usage.PromptDetails.Writes} {
			if count != nil && *count < 0 {
				result.Details["resource_usage_invalid"] = true
			}
		}
		if charge := reportedCharge(chunk.Usage.Cost, provider); charge != nil {
			result.Details["provider_charge"] = charge
		} else if len(chunk.Usage.Cost) > 0 && string(chunk.Usage.Cost) != "null" {
			result.Details["resource_cost_invalid"] = true
		}
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
	if operation == "CUSTOM" {
		err = readCustom(resp.Body, custom, stream, consume)
	} else if operation == "CHAT_COMPLETIONS" {
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
	if err = s.saveMessageWithRequest(ctx, v.SessionID, Message{ID: newID(), Role: "assistant", Content: text.String(), MessageState: MessageState{ToolCalls: completedCalls, ProtocolState: protocolState}}, sampleID); err != nil {
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
	if err != nil || group != v.Group {
		return false
	}
	var reported, origin string
	var known bool
	var version sql.NullInt64
	var at int64
	e := s.Store.DB.QueryRowContext(ctx, "SELECT COALESCE(json_extract(facts_json,'$.group'),''),COALESCE(json_extract(facts_json,'$.group_known'),0),json_extract(facts_json,'$.credential_version'),COALESCE(json_extract(facts_json,'$.origin'),''),observed_at FROM newapi_snapshots WHERE provider_id=? AND account_id=? AND credential_id=? AND credential_role='INFERENCE' AND endpoint='/api/usage/token/' AND provider_version='v1.0.0-rc.25' AND source='NEW_API_OFFICIAL' ORDER BY rowid DESC LIMIT 1", v.ProviderID, v.AccountID, v.CredentialID).Scan(&reported, &known, &version, &origin, &at)
	if e == nil && known && version.Valid && at >= now()-86400000 {
		var current int64
		s.Store.DB.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&current)
		p, e := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
		if e == nil && p.BaseURL == origin && version.Int64 == current && reported != v.Group {
			return false
		}
	}
	return true
}
func (s *Server) listTelemetry(w http.ResponseWriter, r *http.Request) {
	where, args, code := s.telemetryFilters(r)
	if code != 0 {
		http.Error(w, "invalid request filters", code)
		return
	}
	limit, offset := pageRange(r, 100)
	out, e := s.readTelemetry(r.Context(), where, args, limit, offset)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, out)
}
