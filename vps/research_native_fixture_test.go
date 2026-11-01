package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
)

// Disposable cross-runtime integration only. Production Handler, SQLite and
// AA runner are unchanged; only the outbound client maps AA to real local TLS.
// Neither these control routes nor the synthetic key exist in shipped builds.
func TestResearchMacNativeFixture(t *testing.T) {
	path := os.Getenv("MODELATLAS_RESEARCH_FIXTURE_OUT")
	if path == "" {
		t.Skip("native Mac research fixture is started separately by CI")
	}
	var first, second, starts, controls, resumes, direct atomic.Int32
	var failReads atomic.Int32
	var failAfterControl, loseAck, held atomic.Bool
	var holdMu sync.Mutex
	var release chan struct{}
	s, clock := aaResearchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-api-key") != "aa-fixture-key" || r.URL.Path != aaPath {
			t.Error("native AA request escaped its exact TLS endpoint")
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page == 1 {
			first.Add(1)
		} else if second.Add(1) == 1 {
			w.Header().Set("Retry-After", "120")
			http.Error(w, "synthetic-private-upstream-body", 429)
			return
		}
		aaFixturePage(w, page, 2)
	})
	seedAALastGood(t, s)
	for rank := 1; rank <= 4; rank++ {
		if _, err := s.Store.DB.Exec("INSERT INTO aa_models VALUES('aa-last-good',?,?,?,?,70)", "old-"+strconv.Itoa(rank), "old-"+strconv.Itoa(rank), "上一份模型 "+strconv.Itoa(rank), rank); err != nil {
			t.Fatal(err)
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	done := make(chan struct{})
	go func() { defer close(done); s.worker(ctx) }()
	defer func() { stop(); <-done }()
	production := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+s.Token {
			http.Error(w, "synthetic fixture authorization required", 401)
			return
		}
		if r.URL.Path == "/api/v1/research-fixture" {
			if r.Method == "POST" {
				var request struct {
					Action string `json:"action"`
				}
				if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&request) != nil {
					http.Error(w, "invalid fixture action", 400)
					return
				}
				switch request.Action {
				case "FAIL_READ_AFTER_CONTROL":
					failAfterControl.Store(true)
				case "LOSE_CONTROL_ACK":
					loseAck.Store(true)
				case "HOLD_CONTROL":
					holdMu.Lock()
					if release == nil {
						release = make(chan struct{})
					}
					holdMu.Unlock()
				case "RELEASE_CONTROL":
					holdMu.Lock()
					if release != nil {
						close(release)
						release = nil
					}
					holdMu.Unlock()
				case "ADVANCE_CLOCK":
					clock.Add(120000)
					s.wakeWorker()
				default:
					http.Error(w, "invalid fixture action", 400)
					return
				}
			}
			var used, runs, telemetry int
			if err := s.Store.DB.QueryRow("SELECT COALESCE(SUM(used),0),COUNT(*) FROM research_run_options").Scan(&used, &runs); err != nil {
				t.Error(err)
			}
			if err := s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&telemetry); err != nil {
				t.Error(err)
			}
			jsonOK(w, 200, map[string]any{"first": first.Load(), "second": second.Load(), "starts": starts.Load(), "controls": controls.Load(), "resumes": resumes.Load(), "direct_sync": direct.Load(), "held": held.Load(), "used": used, "runs": runs, "telemetry": telemetry})
			return
		}
		if r.URL.Path == "/api/v1/research/runs" && failReads.CompareAndSwap(1, 0) {
			http.Error(w, "synthetic read failure after real commit", 503)
			return
		}
		if r.URL.Path == "/api/v1/research/start" && r.Method == "POST" {
			starts.Add(1)
		}
		if r.URL.Path == "/api/v1/research/aa/sync" {
			direct.Add(1)
		}
		if r.URL.Path == "/api/v1/research/control" && r.Method == "POST" {
			controls.Add(1)
			raw, err := io.ReadAll(io.LimitReader(r.Body, 4096))
			if err != nil {
				t.Error(err)
				http.Error(w, "invalid request", 400)
				return
			}
			var body struct {
				Action string `json:"action"`
			}
			if json.Unmarshal(raw, &body) != nil {
				http.Error(w, "invalid request", 400)
				return
			}
			if body.Action == "RESUME" {
				resumes.Add(1)
			}
			r.Body = io.NopCloser(bytes.NewReader(raw))
			receipt := httptest.NewRecorder()
			production.ServeHTTP(receipt, r)
			if receipt.Code >= 200 && receipt.Code < 300 {
				holdMu.Lock()
				gate := release
				holdMu.Unlock()
				if gate != nil {
					held.Store(true)
					select {
					case <-gate:
					case <-r.Context().Done():
					}
					held.Store(false)
				}
				if failAfterControl.Swap(false) {
					failReads.Store(1)
				}
				if loseAck.Swap(false) {
					http.Error(w, "synthetic lost ACK after real commit", 503)
					return
				}
			}
			for key, values := range receipt.Header() {
				w.Header()[key] = values
			}
			w.WriteHeader(receipt.Code)
			_, _ = w.Write(receipt.Body.Bytes())
			return
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	encoded, err := json.Marshal(map[string]any{"url": app.URL, "token": s.Token, "pid": os.Getpid()})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, encoded, 0600); err != nil {
		t.Fatal(err)
	}
	defer os.Remove(path)
	<-ctx.Done()
}
