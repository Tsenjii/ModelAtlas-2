package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"time"
)

type MediaRequest struct {
	Selection
	Prompt string      `json:"prompt"`
	Image  *Attachment `json:"image,omitempty"`
	Audio  *Attachment `json:"audio,omitempty"`
	Size   string      `json:"size,omitempty"`
}
type mediaWire struct {
	URL    string `json:"url,omitempty"`
	Base64 string `json:"b64_json,omitempty"`
}
type MediaResponse struct {
	Outcome   string      `json:"outcome"`
	Text      string      `json:"text,omitempty"`
	Data      []mediaWire `json:"data,omitempty"`
	Selection Selection   `json:"selection"`
	Status    int         `json:"http_status,omitempty"`
}

// Mirrors Android's registered media transports. Result UI remains a separate PLAY-004 slice.
func (s *Server) mediaInference(w http.ResponseWriter, r *http.Request) {
	var v MediaRequest
	if !decodeLimit(w, r, &v, 12<<20) {
		return
	}
	operation := s.operation(r.Context(), v.Selection)
	required := map[string]string{"IMAGES_GENERATION": "IMAGE_GENERATION", "IMAGES_EDIT": "IMAGE_EDITING", "AUDIO": "AUDIO_INPUT"}[operation]
	if required == "" || !s.validateSelection(r.Context(), v.Selection) || !s.groupBound(r.Context(), v.Selection) || !oneOf(required, s.capabilities(r.Context(), v.Selection)...) {
		http.Error(w, "selected media capability unavailable", 422)
		return
	}
	if len(v.Prompt) > 32000 || operation != "AUDIO" && strings.TrimSpace(v.Prompt) == "" || v.Size != "" && !oneOf(v.Size, "256x256", "512x512", "1024x1024", "1024x1536", "1536x1024", "auto") {
		http.Error(w, "invalid media parameters", 400)
		return
	}
	if operation == "IMAGES_EDIT" && (v.Image == nil || v.Image.Kind != "image" || v.Image.validate() != nil) || operation == "AUDIO" && (v.Audio == nil || v.Audio.Kind != "audio" || v.Audio.validate() != nil) {
		http.Error(w, "invalid bounded local media", 400)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
	defer cancel()
	p, key, err := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		http.Error(w, "credential unavailable", 422)
		return
	}
	cfg := s.config(ctx, p)
	endpoint, err := s.requestEndpoint(ctx, p, cfg, v.Endpoint, v.ModelID)
	if err != nil {
		http.Error(w, "endpoint unavailable", 422)
		return
	}
	body := &bytes.Buffer{}
	contentType := "application/json"
	if operation == "IMAGES_GENERATION" {
		payload := map[string]any{"model": v.ModelID, "prompt": v.Prompt, "n": 1, "response_format": "b64_json"}
		if v.Size != "" {
			payload["size"] = v.Size
		}
		_ = json.NewEncoder(body).Encode(payload)
	} else {
		writer := multipart.NewWriter(body)
		_ = writer.WriteField("model", v.ModelID)
		if v.Prompt != "" {
			_ = writer.WriteField("prompt", v.Prompt)
		}
		a := v.Image
		field := "image"
		if operation == "AUDIO" {
			a = v.Audio
			field = "file"
			_ = writer.WriteField("response_format", "json")
		}
		headers := textproto.MIMEHeader{}
		headers.Set("Content-Disposition", mime.FormatMediaType("form-data", map[string]string{"name": field, "filename": a.Filename}))
		headers.Set("Content-Type", a.MIME)
		part, e := writer.CreatePart(headers)
		if e != nil {
			http.Error(w, "invalid media", 400)
			return
		}
		b, _ := base64.StdEncoding.DecodeString(a.Data)
		_, _ = part.Write(b)
		_ = writer.Close()
		contentType = writer.FormDataContentType()
	}
	request, err := http.NewRequestWithContext(ctx, "POST", endpoint, body)
	if err != nil {
		http.Error(w, "endpoint unavailable", 422)
		return
	}
	request.Header.Set("Content-Type", contentType)
	if s.authorizeUpstream(request, p, key, cfg) != nil {
		http.Error(w, "credential invalid", 422)
		return
	}
	result := MediaResponse{Outcome: "NETWORK", Selection: v.Selection}
	started := time.Now()
	sampleID := newID()
	if _, err = s.Store.DB.ExecContext(ctx, "INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?)", sampleID, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, operation, "PLAYGROUND_MEDIA", started.UnixMilli()); err != nil {
		http.Error(w, "telemetry unavailable", 500)
		return
	}
	var ttfb any
	var input, output *int64
	defer func() {
		if ctx.Err() != nil {
			result.Outcome = "TIMEOUT"
			if ctx.Err() == context.Canceled {
				result.Outcome = "CANCELLED"
			}
		}
		details, _ := json.Marshal(map[string]any{"streaming": false, "retry_count": 0, "ttft_ms": nil})
		saveCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		var status any
		if result.Status > 0 {
			status = result.Status
		}
		_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE telemetry SET outcome=?,status_code=?,ttfb_ms=?,latency_ms=?,input_tokens=?,output_tokens=?,details_json=? WHERE id=?", result.Outcome, status, ttfb, time.Since(started).Milliseconds(), input, output, string(details), sampleID)
	}()
	client := *s.Client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := client.Do(request)
	if err != nil {
		http.Error(w, "media request failed", 502)
		return
	}
	defer response.Body.Close()
	result.Status = response.StatusCode
	ttfb = time.Since(started).Milliseconds()
	if response.StatusCode != 200 {
		result.Outcome = classifyHTTP(response.StatusCode)
		http.Error(w, "media upstream unavailable", 502)
		return
	}
	raw, err := readBounded(response.Body, 16<<20)
	if err != nil {
		result.Outcome = "PARSER"
		http.Error(w, "media response too large", 502)
		return
	}
	var root map[string]json.RawMessage
	if json.Unmarshal(raw, &root) != nil || root["error"] != nil && string(root["error"]) != "null" {
		result.Outcome = "PARSER"
		http.Error(w, "invalid media response", 502)
		return
	}
	var usage map[string]json.RawMessage
	_ = json.Unmarshal(root["usage"], &usage)
	input = intField(usage, "input_tokens")
	output = intField(usage, "output_tokens")
	if operation == "AUDIO" {
		result.Text = safeText(stringField(root, "text"), 256<<10, key)
		if result.Text == "" {
			result.Outcome = "PARSER"
			http.Error(w, "missing transcript", 502)
			return
		}
	} else {
		if json.Unmarshal(root["data"], &result.Data) != nil || len(result.Data) == 0 || len(result.Data) > 4 {
			result.Outcome = "PARSER"
			http.Error(w, "invalid image result", 502)
			return
		}
		for _, data := range result.Data {
			if data.URL == "" && data.Base64 == "" || data.URL != "" && (!validateHTTPS(data.URL) || safeText(data.URL, 8192, key) != data.URL) || len(data.Base64) > 12<<20 {
				result.Outcome = "PARSER"
				http.Error(w, "unsafe image result", 502)
				return
			}
		}
	}
	result.Outcome = "SUCCESS"
	jsonOK(w, 200, result)
}
