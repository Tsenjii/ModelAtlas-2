package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestBrowserProductionWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
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
		default:
			http.Error(w, "unavailable", 404)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/browser.cjs", app.URL, up.URL+"/v1")
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("browser workflow failed: %v\n%s", err, output)
	}
	t.Log(string(output))
}
