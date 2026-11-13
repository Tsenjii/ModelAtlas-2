package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

const gatewayBodyLimit = 2 << 20

type gatewayChatRequest struct {
	model  string
	stream bool
	body   map[string]any
}

type gatewayAttemptResult struct {
	outcome        string
	status         int
	upstreamStatus int
	retry          bool
	body           []byte
	ttfb, ttft     *int64
	lastToken      int64
	input, output  *int64
	latency        int64
	storeErr       error
}

func (s *Server) gatewayChat(w http.ResponseWriter, r *http.Request) {
	security(w)
	token, digest, err := gatewayCredentials(r)
	if err != nil {
		gatewayAuthError(w, err)
		return
	}
	if _, err = gatewayAccess(r.Context(), s.Store.DB, digest); err != nil {
		gatewayAuthError(w, err)
		return
	}
	id := newID()
	if !s.beginFlight(id) {
		w.Header().Set("Retry-After", "1")
		gatewayError(w, 429, "gateway_busy", "ModelAtlas is at its concurrency limit.")
		return
	}
	defer s.endFlight(id)
	contentType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || contentType != "application/json" {
		gatewayError(w, 415, "unsupported_media_type", "Send an application/json request.")
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, gatewayBodyLimit))
	if err != nil {
		var limit *http.MaxBytesError
		if errors.As(err, &limit) {
			gatewayError(w, 413, "request_too_large", "Chat requests are limited to 2 MiB.")
			return
		}
		gatewayError(w, 400, "invalid_request", "Could not read the request.")
		return
	}
	obj, err := gatewayJSONObject(raw)
	model, _ := obj["model"].(string)
	if err != nil || !validID(model) {
		gatewayError(w, 400, "invalid_request", "Provide a valid model ID and messages.")
		return
	}
	messages, _ := obj["messages"].([]any)
	if len(messages) == 0 {
		gatewayError(w, 400, "invalid_request", "Provide a non-empty messages array.")
		return
	}
	stream, streamOK := obj["stream"].(bool)
	if obj["stream"] != nil && !streamOK {
		gatewayError(w, 400, "invalid_request", "stream must be boolean.")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
	defer cancel()
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		gatewayAuthError(w, err)
		return
	}
	secrets = gatewaySecretForms(append(secrets, token))
	if gatewayHasSecret(obj, secrets) {
		gatewayError(w, 400, "invalid_request", "The request contains unsupported or private content.")
		return
	}
	chat := gatewayChatRequest{model: model, stream: stream, body: obj}
	started := time.Now()
	routes, err := s.gatewayAdmit(ctx, digest, id, chat.model, started)
	if err != nil {
		switch {
		case errors.Is(err, errGatewayQuota):
			retry := int(time.UnixMilli(gatewayDayStart(started)).Add(24*time.Hour).Sub(started).Seconds()) + 1
			w.Header().Set("Retry-After", fmt.Sprint(retry))
			gatewayError(w, 429, "daily_limit_reached", "This key has used its daily request limit.")
		case errors.Is(err, errGatewayModel):
			gatewayError(w, 404, "model_not_found", "This model is unavailable for the key.")
		default:
			gatewayAuthError(w, err)
		}
		return
	}
	w.Header().Set("X-Request-ID", id)
	streamStarted := false
	var emit func([]byte) error
	if chat.stream {
		emit = func(frame []byte) error {
			if !streamStarted {
				w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
				w.Header().Set("X-Accel-Buffering", "no")
				w.WriteHeader(200)
				streamStarted = true
			}
			for _, line := range bytes.Split(frame, []byte{10}) {
				if _, err := fmt.Fprintf(w, "data: %s\n", line); err != nil {
					return err
				}
			}
			if _, err := io.WriteString(w, "\n"); err != nil {
				return err
			}
			return http.NewResponseController(w).Flush()
		}
	}
	out := gatewayAttemptResult{outcome: "MODEL_UNAVAILABLE", status: 503}
	ordered, err := s.gatewayRankRoutes(ctx, routes, chat.stream)
	if err != nil {
		out = gatewayAttemptResult{outcome: "TELEMETRY_SAVE", status: 503}
	} else {
		for i, route := range ordered[:min(len(ordered), 2)] {
			out = s.gatewayAttempt(ctx, id, route, chat, secrets, emit)
			if i == 1 || !out.retry || out.storeErr != nil || streamStarted || ctx.Err() != nil {
				break
			}
		}
	}
	if out.storeErr != nil {
		out.outcome, out.status = "TELEMETRY_SAVE", 503
	}
	if ctx.Err() != nil && out.outcome != "SUCCESS" {
		out.setContextOutcome(ctx)
	}
	saved, stop := context.WithTimeout(context.Background(), 5*time.Second)
	_, err = s.Store.DB.ExecContext(saved, "UPDATE gateway_requests SET outcome=?,http_status=?,latency_ms=? WHERE id=?", out.outcome, out.status, time.Since(started).Milliseconds(), id)
	stop()
	if r.Context().Err() != nil {
		return
	}
	if err != nil || out.storeErr != nil {
		out.outcome, out.status = "TELEMETRY_SAVE", 503
	}
	if out.outcome != "SUCCESS" {
		if streamStarted {
			_ = emit([]byte(`{"error":{"type":"server_error","code":"stream_interrupted","message":"The upstream stream was interrupted."}}`))
			return
		}
		if out.status == 429 {
			w.Header().Set("Retry-After", "1")
		}
		gatewayError(w, out.status, strings.ToLower(out.outcome), gatewayOutcomeMessage(out.outcome))
		return
	}
	if chat.stream {
		_ = emit([]byte("[DONE]"))
	} else {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(200)
		_, _ = w.Write(out.body)
	}
}

func gatewayOutcomeMessage(outcome string) string {
	switch outcome {
	case "RATE_LIMITED":
		return "The selected upstream is rate limiting requests."
	case "MODEL_UNAVAILABLE":
		return "No enabled upstream route is available for this model."
	case "TIMEOUT":
		return "The upstream request timed out."
	case "TELEMETRY_SAVE":
		return "ModelAtlas could not save the request record."
	case "REQUEST_HEADERS_UNAVAILABLE":
		return "The selected upstream request headers are unavailable."
	case "SECRET_IN_RESPONSE":
		return "The upstream returned private content."
	case "INVALID_REQUEST":
		return "The upstream rejected the request parameters."
	default:
		return "The upstream could not complete the request."
	}
}

func (v *gatewayAttemptResult) setContextOutcome(ctx context.Context) {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		v.outcome, v.status = "TIMEOUT", 504
	} else if ctx.Err() != nil {
		v.outcome, v.status = "CANCELLED", 499
	}
}

func (s *Server) gatewayRecordAttempt(ctx context.Context, requestID, sample string, scope Selection, p Provider, stream bool, at int64) error {
	source := "GATEWAY"
	if requestID == "" {
		source = "GATEWAY_TEST"
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rec := requestRecord{
		ID: sample, Selection: scope, Operation: "CHAT_COMPLETIONS", Source: source, At: at,
		CredentialVersion: p.CredentialVersion, RequestID: requestID,
		Details: map[string]any{"streaming": stream},
	}
	if err = rec.insert(ctx, tx); err != nil {
		return err
	}
	if requestID != "" {
		if _, err = tx.ExecContext(ctx, "UPDATE gateway_requests SET sample_id=?,attempts=attempts+1 WHERE id=?", sample, requestID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Server) gatewayAttempt(ctx context.Context, requestID string, scope Selection, chat gatewayChatRequest, secrets []string, emit func([]byte) error) (out gatewayAttemptResult) {
	started := time.Now()
	out = gatewayAttemptResult{outcome: "MODEL_UNAVAILABLE", status: 503, retry: true}
	if s.operation(ctx, scope) != "CHAT_COMPLETIONS" || !s.validateSelection(ctx, scope) || !s.groupBound(ctx, scope) {
		return out
	}
	p, key, err := s.endpointAuth(ctx, scope.ProviderID, scope.AccountID, scope.CredentialID)
	if err != nil {
		return out
	}
	cfg := s.config(ctx, p)
	endpoint, err := s.requestEndpoint(ctx, p, cfg, scope.Endpoint, scope.ModelID)
	if err != nil {
		return out
	}
	// The downstream body can choose an alias, but only this configured
	// selection supplies the destination and the upstream model and key.
	chat.body["model"] = scope.ModelID
	extra := []string{key}
	for _, field := range p.AuthFields {
		extra = append(extra, field.Secret)
	}
	payload, err := json.Marshal(chat.body)
	if err != nil {
		out.outcome, out.status, out.retry = "INVALID_REQUEST", 400, false
		return out
	}
	var gotConnection atomic.Bool
	trace := &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) { gotConnection.Store(true) }}
	up, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, trace), "POST", endpoint, bytes.NewReader(payload))
	if err != nil || s.authorizeUpstream(up, p, key, cfg) != nil {
		return out
	}
	up.Header.Set("Content-Type", "application/json")
	up.Header.Set("Accept", "application/json")
	if chat.stream {
		up.Header.Set("Accept", "text/event-stream")
	}
	privateHeaders, _, err := s.applyModelHeaders(up, p, cfg, scope)
	if err != nil {
		out.outcome = "REQUEST_HEADERS_UNAVAILABLE"
		return out
	}
	secrets = append(secrets, gatewaySecretForms(append(extra, privateHeaders...))...)
	if gatewayHasSecret(chat.body, secrets) {
		out.outcome, out.status, out.retry = "INVALID_REQUEST", 400, false
		return out
	}
	sample := newID()
	if err = s.gatewayRecordAttempt(ctx, requestID, sample, scope, p, chat.stream, started.UnixMilli()); err != nil {
		out.outcome, out.status, out.retry, out.storeErr = "TELEMETRY_SAVE", 503, false, err
		return out
	}
	out.retry = false
	var network providerNetwork
	defer func() {
		if out.outcome != "SUCCESS" {
			out.setContextOutcome(ctx)
		}
		out.latency = time.Since(started).Milliseconds()
		details := map[string]any{"streaming": chat.stream, "network": network}
		if out.ttft != nil && out.output != nil && *out.output > 1 && out.lastToken > *out.ttft {
			details["tps"] = float64(*out.output-1) * 1000 / float64(out.lastToken-*out.ttft)
		}
		raw, _ := json.Marshal(details)
		saved, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		var status any
		if out.upstreamStatus > 0 {
			status = out.upstreamStatus
		}
		_, out.storeErr = s.Store.DB.ExecContext(saved,
			"UPDATE telemetry SET outcome=?,status_code=?,ttfb_ms=?,ttft_ms=?,latency_ms=?,input_tokens=?,output_tokens=?,details_json=? WHERE id=?",
			out.outcome, status, out.ttfb, out.ttft, out.latency, out.input, out.output, string(raw), sample)
	}()
	resp, network, err := s.doProviderRequest(up, p, cfg, s.Client)
	if err != nil {
		out.outcome, out.status = "NETWORK", 502
		// Once a connection was assigned, the request might have reached
		// the upstream even if the client reports a write error.
		out.retry = !gotConnection.Load() && ctx.Err() == nil
		return out
	}
	defer resp.Body.Close()
	ttfb := time.Since(started).Milliseconds()
	out.ttfb, out.upstreamStatus = &ttfb, resp.StatusCode
	if resp.StatusCode != 200 {
		out.outcome, out.status = classifyHTTP(resp.StatusCode), 502
		switch resp.StatusCode {
		case 429:
			out.status, out.retry = 429, ctx.Err() == nil
		case 400, 422:
			out.outcome, out.status = "INVALID_REQUEST", 400
		}
		return out
	}
	out.outcome, out.status = "PARSER", 502
	if chat.stream {
		seen := false
		guard := gatewayStreamGuard{secrets: secrets, pending: map[string]gatewayPendingSecret{}, emit: emit}
		err = readSSE(resp.Body, func(frame []byte) error {
			value, err := gatewayJSONObject(frame)
			if err != nil || value["error"] != nil {
				return errors.New("invalid chat stream event")
			}
			if gatewayHasSecret(value, secrets) {
				return errGatewaySecret
			}
			choices, choicesOK := value["choices"].([]any)
			if !choicesOK {
				return errors.New("missing chat choices")
			}
			seen = true
			out.addUsage(value)
			var generated bool
			for i, item := range choices {
				choice, ok := item.(map[string]any)
				if !ok {
					return errors.New("invalid chat choice")
				}
				index := gatewayChoiceIndex(choice, i)
				meaningful, err := guard.delta(index, choice["delta"], 0)
				if err != nil {
					return err
				}
				generated = generated || meaningful
			}
			if generated {
				out.lastToken = time.Since(started).Milliseconds()
				if out.ttft == nil {
					first := out.lastToken
					out.ttft = &first
				}
			}
			var clean []byte
			if emit != nil {
				value["model"] = chat.model
				clean, err = json.Marshal(value)
				if err != nil {
					return err
				}
			}
			return guard.frame(clean)
		})
		if err == nil && seen {
			err = guard.flushBefore(guard.event)
		}
		if errors.Is(err, errGatewaySecret) {
			out.outcome = "SECRET_IN_RESPONSE"
		}
		if err != nil || !seen {
			return out
		}
	} else {
		body, err := readBounded(resp.Body, 8<<20)
		if err != nil {
			return out
		}
		value, err := gatewayJSONObject(body)
		if err != nil || value["error"] != nil {
			return out
		}
		if gatewayHasSecret(value, secrets) {
			out.outcome = "SECRET_IN_RESPONSE"
			return out
		}
		if choices, ok := value["choices"].([]any); !ok || len(choices) == 0 {
			return out
		}
		out.addUsage(value)
		value["model"] = chat.model
		out.body, err = json.Marshal(value)
		if err != nil {
			return out
		}
	}
	out.outcome, out.status = "SUCCESS", 200
	return out
}

func gatewayJSONObject(raw []byte) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var obj map[string]any
	if err := dec.Decode(&obj); err != nil || obj == nil {
		return nil, errors.New("invalid JSON object")
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return nil, errors.New("trailing JSON")
	}
	return obj, nil
}

func (v *gatewayAttemptResult) addUsage(value map[string]any) {
	usage, _ := value["usage"].(map[string]any)
	read := func(names ...string) *int64 {
		for _, name := range names {
			if raw, ok := usage[name].(json.Number); ok {
				if n, err := raw.Int64(); err == nil && n >= 0 {
					return &n
				}
			}
		}
		return nil
	}
	if input := read("prompt_tokens", "input_tokens"); input != nil {
		v.input = input
	}
	if output := read("completion_tokens", "output_tokens"); output != nil {
		v.output = output
	}
}

func gatewaySecretForms(secrets []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, secret := range secrets {
		if secret == "" {
			continue
		}
		for _, form := range []string{secret, url.QueryEscape(secret), base64.StdEncoding.EncodeToString([]byte(secret))} {
			if !seen[form] {
				seen[form] = true
				out = append(out, form)
			}
		}
	}
	return out
}

func gatewayHasSecret(value any, secrets []string) bool {
	switch v := value.(type) {
	case json.Number:
		return gatewayHasSecret(string(v), secrets)
	case string:
		for _, secret := range secrets {
			if strings.Contains(v, secret) {
				return true
			}
		}
	case []any:
		for _, item := range v {
			if gatewayHasSecret(item, secrets) {
				return true
			}
		}
	case map[string]any:
		for name, item := range v {
			if gatewayHasSecret(name, secrets) || gatewayHasSecret(item, secrets) {
				return true
			}
		}
	}
	return false
}

var errGatewaySecret = errors.New("secret in upstream response")

// Most events are forwarded immediately. If a delta ends with a possible
// secret prefix, hold its wire event until that possibility is resolved.
type gatewayStreamGuard struct {
	secrets []string
	pending map[string]gatewayPendingSecret
	frames  [][]byte
	bytes   int
	event   int
	emit    func([]byte) error
}

type gatewayPendingSecret struct {
	suffix string
	event  int
}

func gatewayChoiceIndex(v map[string]any, fallback int) string {
	if n, ok := v["index"].(json.Number); ok {
		if i, err := n.Int64(); err == nil && i >= 0 && i < 1024 {
			return fmt.Sprint(i)
		}
	}
	return fmt.Sprint(fallback)
}

func (g *gatewayStreamGuard) delta(path string, value any, depth int) (bool, error) {
	if depth > 16 {
		return false, errors.New("chat delta nesting limit")
	}
	switch v := value.(type) {
	case string:
		if v == "" {
			return false, nil
		}
		pending, exists := g.pending[path]
		text := pending.suffix + v
		keep := 0
		for _, secret := range g.secrets {
			if strings.Contains(text, secret) {
				return false, errGatewaySecret
			}
			for start := max(0, len(text)-len(secret)+1); start < len(text)-keep; {
				offset := strings.IndexByte(text[start:], secret[0])
				if offset < 0 {
					break
				}
				at := start + offset
				if len(text)-at > keep && strings.HasPrefix(secret, text[at:]) {
					keep = len(text) - at
					break
				}
				start = at + 1
			}
		}
		if keep == 0 {
			delete(g.pending, path)
		} else {
			if !exists && len(g.pending) >= 128 {
				return false, errors.New("too many pending chat deltas")
			}
			first := pending.event
			if keep <= len(v) {
				first = g.event
			}
			g.pending[path] = gatewayPendingSecret{strings.Clone(text[len(text)-keep:]), first}
		}
		return true, nil
	case []any:
		generated := false
		for i, item := range v {
			index := fmt.Sprint(i)
			if object, ok := item.(map[string]any); ok {
				index = gatewayChoiceIndex(object, i)
			}
			part, err := g.delta(path+"["+index+"]", item, depth+1)
			if err != nil {
				return false, err
			}
			generated = generated || part
		}
		return generated, nil
	case map[string]any:
		generated := false
		for name, item := range v {
			if oneOf(name, "role", "id", "type") {
				continue
			}
			part, err := g.delta(path+"/"+url.PathEscape(name), item, depth+1)
			if err != nil {
				return false, err
			}
			generated = generated || part
		}
		return generated, nil
	}
	return false, nil
}

func (g *gatewayStreamGuard) frame(raw []byte) error {
	defer func() { g.event++ }()
	if g.emit == nil {
		return nil
	}
	ready := g.event
	for _, pending := range g.pending {
		ready = min(ready, pending.event)
	}
	if err := g.flushBefore(ready); err != nil {
		return err
	}
	if len(g.pending) == 0 {
		return g.emit(raw)
	}
	g.bytes += len(raw)
	if g.bytes > 1<<20 || len(g.frames) >= 256 {
		return errors.New("pending chat event limit")
	}
	g.frames = append(g.frames, raw)
	return nil
}

func (g *gatewayStreamGuard) flushBefore(event int) error {
	first := g.event - len(g.frames)
	n := max(0, min(event-first, len(g.frames)))
	for _, raw := range g.frames[:n] {
		if err := g.emit(raw); err != nil {
			return err
		}
		g.bytes -= len(raw)
	}
	clear(g.frames[:n])
	g.frames = g.frames[n:]
	return nil
}
