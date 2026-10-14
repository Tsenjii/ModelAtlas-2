package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
	"time"
)

func TestBrowserProductionWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	encoded := testPNG(t)
	var mediaCalls atomic.Int32
	var blockedMediaStarted atomic.Bool
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25"}}`))
		case "/v1/models":
			w.Write([]byte(`{"data":[{"id":"m"},{"id":"catalog-only"}]}`))
		case "/v1/chat/completions":
			var body struct {
				Messages []any `json:"messages"`
			}
			json.NewDecoder(r.Body).Decode(&body)
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Browser reply\"}}]}\n\n"))
			w.(http.Flusher).Flush()
			time.Sleep(50 * time.Millisecond)
			w.Write([]byte("data: [DONE]\n\n"))
		case "/v1/images/generations":
			mediaCalls.Add(1)
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "m" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("browser media selected the wrong credential/model")
			}
			switch body["prompt"] {
			case "blocked image":
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(`{"data":[`))
				w.(http.Flusher).Flush()
				blockedMediaStarted.Store(true)
				select {
				case <-r.Context().Done():
				case <-time.After(5 * time.Second):
					t.Error("browser stop did not cancel upstream body")
				}
			case "invalid image":
				w.Write([]byte(`{"data":[{"b64_json":"not-an-image"}]}`))
			case "image URL":
				w.Write([]byte(`{"data":[{"url":"https://images.example.invalid/result.png"}]}`))
			case "rate limited":
				http.Error(w, "provider error body", 429)
			default:
				json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"b64_json": encoded}}})
			}
		case "/v1/images/edits", "/v1/audio/transcriptions":
			mediaCalls.Add(1)
			if r.ParseMultipartForm(5<<20) != nil {
				t.Error("browser media did not use multipart")
				return
			}
			defer r.MultipartForm.RemoveAll()
			field, wantType := "image", "image/png"
			if r.URL.Path == "/v1/audio/transcriptions" {
				field, wantType = "file", "audio/wav"
			}
			file, header, err := r.FormFile(field)
			if err != nil {
				t.Error("browser omitted local media")
				return
			}
			file.Close()
			if header.Header.Get("Content-Type") != wantType || r.FormValue("model") != "m" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
				t.Error("browser media scope/MIME mismatch")
			}
			if field == "file" {
				json.NewEncoder(w).Encode(map[string]string{"text": "Browser transcript\n第二行轉寫"})
			} else {
				json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"b64_json": encoded}}})
			}
		default:
			http.Error(w, "unavailable", 404)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Read-only fixture observation: never replaces a production API action.
		if r.URL.Path == "/__fixture/media-state" {
			json.NewEncoder(w).Encode(map[string]bool{"blocked": blockedMediaStarted.Load()})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/browser.cjs", app.URL, up.URL+"/v1")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_TEST_PNG="+encoded)
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("browser workflow failed: %v\n%s", err, output)
	}
	if mediaCalls.Load() != 7 {
		t.Fatalf("browser media flow made %d upstream calls, expected 7", mediaCalls.Load())
	}
	t.Log(string(output))
}
