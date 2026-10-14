package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	_ "golang.org/x/image/webp"
)

type MediaRequest struct {
	Selection
	Prompt       string      `json:"prompt"`
	Image        *Attachment `json:"image,omitempty"`
	Audio        *Attachment `json:"audio,omitempty"`
	Size         string      `json:"size,omitempty"`
	RequestID    string      `json:"request_id,omitempty"`
	Voice        string      `json:"voice,omitempty"`
	Format       string      `json:"format,omitempty"`
	Instructions string      `json:"instructions,omitempty"`
	Speed        float64     `json:"speed,omitempty"`
	Inputs       []string    `json:"inputs,omitempty"`
	Documents    []string    `json:"documents,omitempty"`
	Dimensions   int         `json:"dimensions,omitempty"`
	TopN         int         `json:"top_n,omitempty"`
}
type mediaWire struct {
	URL    string `json:"url,omitempty"`
	Base64 string `json:"b64_json,omitempty"`
	MIME   string `json:"mime,omitempty"`
	Format string `json:"format,omitempty"`
}
type MediaResponse struct {
	Outcome   string         `json:"outcome"`
	Text      string         `json:"text,omitempty"`
	Data      []mediaWire    `json:"data,omitempty"`
	Selection Selection      `json:"selection"`
	Status    int            `json:"http_status,omitempty"`
	RequestID string         `json:"request_id"`
	Details   map[string]any `json:"details"`
	Vectors   []VectorResult `json:"vectors,omitempty"`
	Rankings  []RerankResult `json:"rankings,omitempty"`
}

// Media requests share the chat flight budget and cancellation registry, while
// their inputs/results stay out of conversation history and telemetry payloads.
func (s *Server) mediaInference(w http.ResponseWriter, r *http.Request) {
	var v MediaRequest
	if !decodeLimit(w, r, &v, 12<<20) {
		return
	}
	operation := s.operation(r.Context(), v.Selection)
	required := map[string]string{"IMAGES_GENERATION": "IMAGE_GENERATION", "IMAGES_EDIT": "IMAGE_EDITING", "AUDIO": "AUDIO_INPUT", "AUDIO_SPEECH": "AUDIO_OUTPUT", "EMBEDDINGS": "EMBEDDING", "RERANK": "RERANK"}[operation]
	if required == "" || !s.validateSelection(r.Context(), v.Selection) || !s.groupBound(r.Context(), v.Selection) || !oneOf(required, s.capabilities(r.Context(), v.Selection)...) {
		http.Error(w, "selected media capability unavailable", 422)
		return
	}
	if len(v.Prompt) > 32000 || !oneOf(operation, "AUDIO", "EMBEDDINGS") && strings.TrimSpace(v.Prompt) == "" || v.Size != "" && !oneOf(v.Size, "256x256", "512x512", "1024x1024", "1024x1536", "1536x1024", "auto") || v.RequestID != "" && !validID(v.RequestID) {
		http.Error(w, "invalid media parameters", 400)
		return
	}
	if operation == "IMAGES_EDIT" && (v.Image == nil || v.Image.Kind != "image" || v.Image.validate() != nil) || operation == "AUDIO" && (v.Audio == nil || v.Audio.Kind != "audio" || v.Audio.validate() != nil) {
		http.Error(w, "invalid bounded local media", 400)
		return
	}
	if operation == "IMAGES_GENERATION" && (v.Image != nil || v.Audio != nil) || operation == "IMAGES_EDIT" && v.Audio != nil || operation == "AUDIO" && v.Image != nil || operation != "IMAGES_GENERATION" && v.Size != "" {
		http.Error(w, "media inputs do not match this operation", 400)
		return
	}
	if operation == "EMBEDDINGS" || operation == "RERANK" {
		if !validVectorRequest(v, operation) {
			http.Error(w, "invalid vector request", 400)
			return
		}
	} else if len(v.Inputs) > 0 || len(v.Documents) > 0 || v.Dimensions != 0 || v.TopN != 0 {
		http.Error(w, "vector parameters require vector endpoint", 400)
		return
	}
	if operation == "AUDIO_SPEECH" {
		if v.Image != nil || v.Audio != nil || len(v.Prompt) > 4096 || !toolNamePattern.MatchString(v.Voice) || !oneOf(v.Format, "mp3", "wav", "opus", "flac", "aac", "pcm") || len(v.Instructions) > 4096 || v.Speed != 0 && (v.Speed < 0.25 || v.Speed > 4) {
			http.Error(w, "invalid speech parameters", 400)
			return
		}
	} else if v.Voice != "" || v.Format != "" || v.Instructions != "" || v.Speed != 0 {
		http.Error(w, "speech parameters require speech endpoint", 400)
		return
	}
	if v.RequestID == "" {
		v.RequestID = newID()
	}
	flightID := "media:" + v.RequestID
	if !s.beginFlight(flightID) {
		http.Error(w, "request in progress or flight limit reached", 409)
		return
	}
	defer s.endFlight(flightID)
	ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
	defer cancel()
	s.flightMu.Lock()
	if s.cancels == nil {
		s.cancels = map[string]context.CancelFunc{}
	}
	s.cancels[flightID] = cancel
	s.flightMu.Unlock()
	p, key, err := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		http.Error(w, "credential unavailable", 422)
		return
	}
	secrets := []string{key}
	for _, field := range p.AuthFields {
		secrets = append(secrets, field.Secret)
	}
	cfg := s.config(ctx, p)
	endpoint, err := s.requestEndpoint(ctx, p, cfg, v.Endpoint, v.ModelID)
	if err != nil {
		http.Error(w, "endpoint unavailable", 422)
		return
	}
	body := &bytes.Buffer{}
	contentType := "application/json"
	if operation == "EMBEDDINGS" {
		payload := map[string]any{"model": v.ModelID, "input": v.Inputs, "encoding_format": "float"}
		if v.Dimensions != 0 {
			payload["dimensions"] = v.Dimensions
		}
		_ = json.NewEncoder(body).Encode(payload)
	} else if operation == "RERANK" {
		payload := map[string]any{"model": v.ModelID, "query": v.Prompt, "documents": v.Documents}
		if v.TopN != 0 {
			payload["top_n"] = v.TopN
		}
		_ = json.NewEncoder(body).Encode(payload)
	} else if operation == "AUDIO_SPEECH" {
		payload := map[string]any{"model": v.ModelID, "input": v.Prompt, "voice": v.Voice, "response_format": v.Format}
		if v.Instructions != "" {
			payload["instructions"] = v.Instructions
		}
		if v.Speed != 0 {
			payload["speed"] = v.Speed
		}
		_ = json.NewEncoder(body).Encode(payload)
	} else if operation == "IMAGES_GENERATION" {
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
	result := MediaResponse{Outcome: "NETWORK", Selection: v.Selection, RequestID: v.RequestID, Details: map[string]any{"streaming": false, "retry_count": 0, "ttft_ms": nil}}
	responseStatus := 502
	started := time.Now()
	sampleID := newID()
	if _, err = s.Store.DB.ExecContext(ctx, "INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at,credential_version) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,?)", sampleID, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, operation, "PLAYGROUND_MEDIA", started.UnixMilli(), p.CredentialVersion); err != nil {
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
				responseStatus = 409
			} else {
				responseStatus = 408
			}
		}
		result.Details["latency_ms"] = time.Since(started).Milliseconds()
		result.Details["ttfb_ms"] = ttfb
		result.Details["input_tokens"] = input
		result.Details["output_tokens"] = output
		details, _ := json.Marshal(result.Details)
		saveCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		var status any
		if result.Status > 0 {
			status = result.Status
		}
		if _, e := s.Store.DB.ExecContext(saveCtx, "UPDATE telemetry SET outcome=?,status_code=?,ttfb_ms=?,latency_ms=?,input_tokens=?,output_tokens=?,details_json=? WHERE id=?", result.Outcome, status, ttfb, result.Details["latency_ms"], input, output, string(details), sampleID); e != nil {
			result.Outcome = "TELEMETRY_SAVE"
			responseStatus = 500
		}
		if result.Outcome != "SUCCESS" {
			result.Text = ""
			result.Data = nil
			result.Vectors = nil
			result.Rankings = nil
		}
		jsonOK(w, responseStatus, result)
	}()
	client := *s.Client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := client.Do(request)
	if err != nil {
		return
	}
	defer response.Body.Close()
	result.Status = response.StatusCode
	ttfb = time.Since(started).Milliseconds()
	if response.StatusCode != 200 {
		result.Outcome = classifyHTTP(response.StatusCode)
		return
	}
	raw, err := readBounded(response.Body, 8<<20)
	if err != nil {
		result.Outcome = "PARSER"
		return
	}
	if operation == "AUDIO_SPEECH" {
		mediaType := generatedAudioType(raw, v.Format, secrets...)
		if mediaType == "" {
			result.Outcome = "PARSER"
			return
		}
		result.Data = []mediaWire{{Base64: base64.StdEncoding.EncodeToString(raw), MIME: mediaType, Format: v.Format}}
		result.Outcome = "SUCCESS"
		responseStatus = 200
		return
	}
	var root map[string]json.RawMessage
	if json.Unmarshal(raw, &root) != nil || root["error"] != nil && string(root["error"]) != "null" {
		result.Outcome = "PARSER"
		return
	}
	var usage map[string]json.RawMessage
	_ = json.Unmarshal(root["usage"], &usage)
	input = intField(usage, "input_tokens")
	output = intField(usage, "output_tokens")
	if input != nil && *input < 0 {
		input = nil
	}
	if output != nil && *output < 0 {
		output = nil
	}
	if operation == "EMBEDDINGS" || operation == "RERANK" {
		if !parseVectors(root, v, operation, &result) {
			result.Outcome = "PARSER"
			return
		}
	} else if operation == "AUDIO" {
		if json.Unmarshal(root["text"], &result.Text) != nil || !validTranscript(result.Text, secrets) {
			result.Outcome = "PARSER"
			return
		}
	} else {
		if json.Unmarshal(root["data"], &result.Data) != nil || len(result.Data) == 0 || len(result.Data) > 4 {
			result.Outcome = "PARSER"
			return
		}
		imageBytes := 0
		pixelsLeft := int64(4 << 20)
		for i := range result.Data {
			data := &result.Data[i]
			if data.URL == "" && data.Base64 == "" || data.URL != "" && (!validateHTTPS(data.URL) || safeText(data.URL, 8192, secrets...) != data.URL) {
				result.Outcome = "PARSER"
				return
			}
			if data.Base64 != "" {
				var size int
				var pixels int64
				data.MIME, size, pixels = mediaImageType(data.Base64, pixelsLeft, secrets...)
				imageBytes += size
				pixelsLeft -= pixels
				if data.MIME == "" || imageBytes > maxAttachment {
					result.Outcome = "PARSER"
					return
				}
			} else {
				data.MIME = ""
			}
		}
	}
	result.Outcome = "SUCCESS"
	responseStatus = 200
}

// Check dimensions before decoding raster pixels. All results in one request
// share a four-megapixel budget, rather than allocating arbitrary provider sizes.
func mediaImageType(encoded string, pixelsLeft int64, secrets ...string) (string, int, int64) {
	if len(encoded) == 0 || len(encoded) > ((maxAttachment+2)/3)*4 {
		return "", 0, 0
	}
	raw, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil || len(raw) == 0 || len(raw) > maxAttachment {
		return "", 0, 0
	}
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(raw, []byte(secret)) {
			return "", 0, 0
		}
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	pixels := int64(cfg.Width) * int64(cfg.Height)
	if err != nil || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 || pixels > pixelsLeft {
		return "", 0, 0
	}
	mime := map[string]string{"png": "image/png", "jpeg": "image/jpeg", "webp": "image/webp"}[format]
	if mime == "" {
		return "", 0, 0
	}
	decoded, decodedFormat, err := image.Decode(bytes.NewReader(raw))
	if err != nil || decodedFormat != format || decoded.Bounds().Dx() != cfg.Width || decoded.Bounds().Dy() != cfg.Height {
		return "", 0, 0
	}
	return mime, len(raw), pixels
}

func validTranscript(text string, secrets []string) bool {
	if strings.TrimSpace(text) == "" || len(text) > 256<<10 || !utf8.ValidString(text) {
		return false
	}
	for _, r := range text {
		if unicode.Is(unicode.Cf, r) || unicode.IsControl(r) && r != '\n' && r != '\r' && r != '\t' {
			return false
		}
	}
	flat := strings.NewReplacer("\n", "", "\r", "", "\t", "").Replace(text)
	return safeText(flat, len(flat), secrets...) == flat
}
