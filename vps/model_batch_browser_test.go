package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserModelBatchTestWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var calls, mode atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		io.Copy(io.Discard, r.Body)
		switch mode.Load() {
		case 1:
			http.Error(w, `{"error":{"message":"quota"}}`, 429)
		case 2:
			<-r.Context().Done()
		default:
			batchTestReply(w)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	batchTestSelections(t, s, up.URL+"/v1")
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__batch_fixture/state":
			jsonOK(w, 200, map[string]any{"calls": calls.Load()})
			return
		case "/__batch_fixture/limited":
			mode.Store(1)
			jsonOK(w, 200, true)
			return
		case "/__batch_fixture/blocked":
			mode.Store(2)
			jsonOK(w, 200, true)
			return
		case "/__batch_fixture/complete":
			mode.Store(0)
			jsonOK(w, 200, true)
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/model-batch.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("batch browser: %v\n%s", err, out)
	}
	if calls.Load() != 8 {
		t.Fatalf("unexpected extra batch inference: %d", calls.Load())
	}
	t.Log(string(out))
}
