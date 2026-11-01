package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func testPNG(t *testing.T) string {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 256, 192))
	for y := 0; y < 192; y++ {
		for x := 0; x < 256; x++ {
			c := color.RGBA{8, 126, 121, 255}
			if (x/32+y/32)%2 == 0 {
				c = color.RGBA{204, 243, 234, 255}
			}
			img.SetRGBA(x, y, c)
		}
	}
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(b.Bytes())
}

func TestMediaResultFormatsAndOperationWire(t *testing.T) {
	encoded := testPNG(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("media used a different credential")
		}
		if r.URL.Path == "/v1/images/generations" {
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "m" || body["response_format"] != "b64_json" || body["size"] != "1024x1024" {
				t.Error("generation payload mismatch")
			}
		} else {
			if r.ParseMultipartForm(5<<20) != nil {
				t.Error("invalid multipart")
			}
			defer r.MultipartForm.RemoveAll()
			field, wantType := "image", "image/png"
			if r.URL.Path == "/v1/audio/transcriptions" {
				field, wantType = "file", "audio/wav"
			}
			f, header, err := r.FormFile(field)
			if err != nil {
				t.Error("selected file was not wired")
			} else {
				f.Close()
				if header.Header.Get("Content-Type") != wantType || r.FormValue("model") != "m" {
					t.Error("wrong MIME or model in media upload")
				}
			}
		}
		if r.URL.Path == "/v1/audio/transcriptions" {
			json.NewEncoder(w).Encode(map[string]any{"text": "First line\n第二行\t專有名詞", "usage": map[string]int{"input_tokens": 0, "output_tokens": -1}})
		} else {
			json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"b64_json": encoded, "mime": "text/html"}}, "usage": map[string]int{"input_tokens": 0, "output_tokens": 0}})
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	for _, operation := range []string{"IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO"} {
		v.Endpoint = operation
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
		confirmCaps(t, s, v, []string{map[string]string{"IMAGES_GENERATION": "IMAGE_GENERATION", "IMAGES_EDIT": "IMAGE_EDITING", "AUDIO": "AUDIO_INPUT"}[operation]})
		request := MediaRequest{Selection: v, Prompt: "fixture prompt", RequestID: newID()}
		switch operation {
		case "IMAGES_GENERATION":
			request.Size = "1024x1024"
		case "IMAGES_EDIT":
			request.Image = &Attachment{Kind: "image", Filename: "local.png", MIME: "image/png", Data: encoded}
		case "AUDIO":
			request.Prompt = ""
			request.Audio = &Attachment{Kind: "audio", Filename: "local.wav", MIME: "audio/wav", Data: base64.StdEncoding.EncodeToString([]byte("RIFF0000WAVE"))}
		}
		response := req(t, s, "POST", "/api/v1/infer/media", request)
		requireCode(t, response, 200)
		var result MediaResponse
		if json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Outcome != "SUCCESS" || result.RequestID != request.RequestID || scopeKey(result.Selection) != scopeKey(v) {
			t.Fatal("media response lost selected scope/request")
		}
		if result.Details["ttft_ms"] != nil || result.Details["input_tokens"] != float64(0) || result.Details["latency_ms"] == nil {
			t.Fatal("nonstreaming media invented TTFT or lost zero usage")
		}
		if operation == "AUDIO" {
			if result.Text != "First line\n第二行\t專有名詞" || result.Details["output_tokens"] != nil {
				t.Fatal("multiline transcript lost or negative usage accepted")
			}
		} else if len(result.Data) != 1 || result.Data[0].MIME != "image/png" {
			t.Fatal("trusted upstream MIME instead of validated raster type")
		}
	}
	if calls.Load() != 3 {
		t.Fatal("media operation did not use its transport")
	}
	var messages int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages)
	if messages != 0 || strings.Contains(req(t, s, "GET", "/api/v1/telemetry", nil).Body.String(), encoded) || strings.Contains(req(t, s, "GET", "/api/v1/telemetry", nil).Body.String(), "第二行") {
		t.Fatal("raw media result entered history/telemetry")
	}
	var jpegBytes bytes.Buffer
	if err := jpeg.Encode(&jpegBytes, image.NewRGBA(image.Rect(0, 0, 2, 2)), nil); err != nil {
		t.Fatal(err)
	}
	if mime, _, _ := mediaImageType(base64.StdEncoding.EncodeToString(jpegBytes.Bytes()), 4<<20); mime != "image/jpeg" {
		t.Fatal("JPEG preview unavailable")
	}
	if mime, _, _ := mediaImageType("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA", 4<<20); mime != "image/webp" {
		t.Fatal("WebP preview unavailable")
	}
}

func TestMediaRejectsEchoMalformedResultsAndUnconfirmedScope(t *testing.T) {
	encoded := testPNG(t)
	var reply atomic.Value
	reply.Store(`{"data":[{"b64_json":"invalid"}]}`)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		io.Copy(io.Discard, r.Body)
		w.Write([]byte(reply.Load().(string)))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	v.Endpoint = "IMAGES_GENERATION"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	request := MediaRequest{Selection: v, Prompt: "generate"}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", request), 422)
	if calls.Load() != 0 {
		t.Fatal("unconfirmed media reached upstream")
	}
	confirmCaps(t, s, v, []string{"IMAGE_GENERATION"})
	v.CredentialID = "media-key"
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: v.CredentialID, ProviderID: "p", AccountID: "a", Label: "Media", Secret: "top-secret-example-key", AuthFields: []AuthField{{Name: "x-private", Secret: "extra-secret-example"}}}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"IMAGE_GENERATION"})
	request.Selection = v
	for _, data := range []map[string]string{
		{"b64_json": "invalid"},
		{"b64_json": base64.StdEncoding.EncodeToString(mustDecodeImage(t, encoded)[:33])},
		{"b64_json": base64.StdEncoding.EncodeToString([]byte(`<svg xmlns="http://www.w3.org/2000/svg"/>`))},
		{"url": "http://images.example/a.png"},
		{"url": "https://images.example/a.png?key=extra-secret-example"},
		{"url": "https://images.example/top-secret-example-key"},
		{"b64_json": base64.StdEncoding.EncodeToString(append(mustDecodeImage(t, encoded), []byte("extra-secret-example")...))},
	} {
		body, _ := json.Marshal(map[string]any{"data": []any{data}})
		reply.Store(string(body))
		response := req(t, s, "POST", "/api/v1/infer/media", request)
		requireCode(t, response, 502)
		var result MediaResponse
		json.Unmarshal(response.Body.Bytes(), &result)
		if result.Outcome != "PARSER" || len(result.Data) != 0 || strings.Contains(response.Body.String(), "extra-secret-example") {
			t.Fatal("unsafe image or credential echo returned to browser")
		}
	}
	v.Endpoint = "AUDIO"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"AUDIO_INPUT"})
	request = MediaRequest{Selection: v, Audio: &Attachment{Kind: "audio", Filename: "a.wav", MIME: "audio/wav", Data: base64.StdEncoding.EncodeToString([]byte("RIFF0000WAVE"))}}
	for _, text := range []string{"extra-secret-example", "top-secret-example-key", "bad\u0000control", strings.Repeat("x", (256<<10)+1)} {
		body, _ := json.Marshal(map[string]string{"text": text})
		reply.Store(string(body))
		response := req(t, s, "POST", "/api/v1/infer/media", request)
		requireCode(t, response, 502)
		var result MediaResponse
		json.Unmarshal(response.Body.Bytes(), &result)
		if result.Text != "" || result.Outcome != "PARSER" {
			t.Fatal("unsafe transcript returned")
		}
	}
	before := calls.Load()
	confirmCaps(t, s, v, []string{})
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", request), 422)
	if calls.Load() != before {
		t.Fatal("revoked media capability reached HTTP")
	}
}

func mustDecodeImage(t *testing.T, encoded string) []byte {
	t.Helper()
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestMediaFlightBudgetAndExplicitCancel(t *testing.T) {
	entered := make(chan struct{}, 4)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		entered <- struct{}{}
		select {
		case <-r.Context().Done():
		case <-time.After(5 * time.Second):
			t.Error("cancel did not close upstream blocked body")
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	chat := newSession(t, s, v)
	v.Endpoint = "IMAGES_GENERATION"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"IMAGE_GENERATION"})
	responses := make(chan *httptest.ResponseRecorder, 4)
	ids := []string{"image-1", "image-2", "image-3", "image-4"}
	for _, id := range ids {
		go func(id string) {
			responses <- req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: v, Prompt: "wait", RequestID: id})
		}(id)
	}
	for range ids {
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("media did not start")
		}
	}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: v, Prompt: "duplicate", RequestID: ids[0]}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: v, Prompt: "fifth", RequestID: "fifth"}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: chat.ID, Prompt: "same shared budget"}), 409)
	for _, id := range ids {
		requireCode(t, req(t, s, "POST", "/api/v1/infer/cancel", map[string]string{"request_id": id}), 200)
	}
	for range ids {
		select {
		case response := <-responses:
			requireCode(t, response, 409)
			var result MediaResponse
			json.Unmarshal(response.Body.Bytes(), &result)
			if result.Outcome != "CANCELLED" {
				t.Fatal("media cancel became parser failure")
			}
		case <-time.After(3 * time.Second):
			t.Fatal("cancel left media handler blocked")
		}
	}
	var running, canceled int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE outcome='RUNNING'").Scan(&running)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE source='PLAYGROUND_MEDIA' AND outcome='CANCELLED'").Scan(&canceled)
	if running != 0 || canceled != 4 || s.performance(context.Background(), v, 86400000)["success_rate"] != nil {
		t.Fatal("cancel counted as uptime or lost durable telemetry")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/cancel", map[string]string{"request_id": ids[0], "session_id": chat.ID}), 400)
}

func TestMediaHTTPErrorAndInputMismatch(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		io.Copy(io.Discard, r.Body)
		http.Error(w, "private provider error body", 429)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	v.Endpoint = "IMAGES_GENERATION"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	confirmCaps(t, s, v, []string{"IMAGE_GENERATION"})
	request := MediaRequest{Selection: v, Prompt: "draw", Audio: &Attachment{Kind: "audio"}}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", request), 400)
	request.Audio = nil
	request.RequestID = "bad id"
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", request), 400)
	if calls.Load() != 0 {
		t.Fatal("wrong operation/file reached HTTP")
	}
	request.RequestID = "valid-id"
	response := req(t, s, "POST", "/api/v1/infer/media", request)
	requireCode(t, response, 502)
	var result MediaResponse
	json.Unmarshal(response.Body.Bytes(), &result)
	if result.Outcome != "RATE_LIMITED" || result.Status != 429 || strings.Contains(response.Body.String(), "private provider") || calls.Load() != 1 {
		t.Fatal("upstream classification/retry/body secrecy mismatch")
	}
}
