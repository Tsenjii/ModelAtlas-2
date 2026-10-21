package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestBrowserWorkspaceRecoveryWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	s := testServer(t)
	v := seed(t, s, "https://no-inference.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	var mode, reads, active, maximum, writes atomic.Int32
	var blocked, aborted, writeHeld, committed atomic.Bool
	snapshotRelease, writeRelease := make(chan struct{}), make(chan struct{})
	var releaseSnapshot, releaseWrite sync.Once
	production := s.Handler()
	copyResponse := func(w http.ResponseWriter, rec *httptest.ResponseRecorder) {
		for key, values := range rec.Header() {
			w.Header()[key] = values
		}
		w.WriteHeader(rec.Code)
		_, _ = w.Write(rec.Body.Bytes())
	}
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Fault injection belongs only to this test wrapper. Every successful
		// read and committed write still uses the unmodified production handler.
		switch r.URL.Path {
		case "/__recovery_fixture/normal":
			mode.Store(1)
		case "/__recovery_fixture/fail":
			mode.Store(0)
		case "/__recovery_fixture/hold-read":
			blocked.Store(false)
			mode.Store(2)
		case "/__recovery_fixture/block-snapshot":
			blocked.Store(false)
			mode.Store(3)
		case "/__recovery_fixture/release-snapshot":
			releaseSnapshot.Do(func() { close(snapshotRelease) })
		case "/__recovery_fixture/hold-write":
			writeHeld.Store(true)
		case "/__recovery_fixture/release-write":
			releaseWrite.Do(func() { close(writeRelease) })
		case "/__recovery_fixture/state":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"reads": reads.Load(), "active": active.Load(), "maximum": maximum.Load(), "writes": writes.Load(), "blocked": blocked.Load(), "aborted": aborted.Load(), "committed": committed.Load()})
			return
		default:
			if r.Method == "GET" && r.URL.Path == "/api/v1/providers" {
				reads.Add(1)
				n := active.Add(1)
				defer active.Add(-1)
				for previous := maximum.Load(); n > previous; previous = maximum.Load() {
					if maximum.CompareAndSwap(previous, n) {
						break
					}
				}
				switch mode.Load() {
				case 0:
					http.Error(w, "temporary workspace outage", 503)
					return
				case 2:
					w.Header().Set("Content-Type", "application/json")
					_, _ = w.Write([]byte("["))
					w.(http.Flusher).Flush()
					blocked.Store(true)
					select {
					case <-r.Context().Done():
						aborted.Store(true)
					case <-time.After(10 * time.Second):
						t.Error("read deadline did not cancel the partial response")
					}
					return
				case 3:
					mode.Store(1)
					rec := httptest.NewRecorder()
					production.ServeHTTP(rec, r)
					blocked.Store(true)
					select {
					case <-snapshotRelease:
					case <-r.Context().Done():
						return
					}
					copyResponse(w, rec)
					return
				}
			}
			if r.Method == "POST" && r.URL.Path == "/api/v1/sessions" {
				writes.Add(1)
				if writeHeld.CompareAndSwap(true, false) {
					rec := httptest.NewRecorder()
					production.ServeHTTP(rec, r)
					committed.Store(true)
					select {
					case <-writeRelease:
					case <-r.Context().Done():
						t.Error("read deadline cancelled a committed POST")
						return
					case <-time.After(10 * time.Second):
						t.Error("committed write response was never released")
						return
					}
					copyResponse(w, rec)
					return
				}
			}
			production.ServeHTTP(w, r)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer app.Close()
	defer releaseSnapshot.Do(func() { close(snapshotRelease) })
	defer releaseWrite.Do(func() { close(writeRelease) })
	cmd := exec.Command("node", "e2e/workspace-recovery.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("workspace recovery browser: %v\n%s", err, out)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM providers")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM sessions")
	if maximum.Load() != 1 || !aborted.Load() || writes.Load() != 2 {
		t.Fatalf("recovery boundaries: max reads=%d aborted=%t writes=%d", maximum.Load(), aborted.Load(), writes.Load())
	}
	t.Log(string(out))
}
