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

func TestBrowserConversationCreationWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	var inference, posts, commits, reads, metadataReads atomic.Int32
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		inference.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"對話恢復後的真實回應"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}`))
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client = upstream.Client()
	v := seed(t, s, upstream.URL)
	original := newSession(t, s, v)
	_, err := s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('original-message',?,'user','原對話的完整歷史',1)", original.ID)
	if err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "另一個帳戶"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "另一把 Key", Secret: "synthetic-other-account-key", Group: "other"}), 201)
	other := v
	other.AccountID, other.Group = "b", "other"
	_ = newSession(t, s, other)
	var failReads, failAfterCreate, rejectNext, dropNext, truncateNext, holdNext, blocked atomic.Bool
	var holdRead atomic.Int32
	writeRelease := make(chan struct{})
	readRelease := []chan struct{}{make(chan struct{}), make(chan struct{})}
	var releaseWrite sync.Once
	var releaseRead [2]sync.Once
	var idsMu sync.Mutex
	var createdIDs []string
	production := s.Handler()
	copyResponse := func(w http.ResponseWriter, rec *httptest.ResponseRecorder) {
		for key, value := range rec.Header() {
			w.Header()[key] = value
		}
		w.WriteHeader(rec.Code)
		_, _ = w.Write(rec.Body.Bytes())
	}
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Only the fixture wrapper controls failures and response barriers.
		// Actual persistence, validation, history and inference use production.
		switch r.URL.Path {
		case "/__creation_fixture/state":
			idsMu.Lock()
			ids := append([]string{}, createdIDs...)
			idsMu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"posts": posts.Load(), "commits": commits.Load(), "reads": reads.Load(), "metadata_reads": metadataReads.Load(), "inference": inference.Load(), "created": ids, "original": original.ID, "blocked": blocked.Load()})
			return
		case "/__creation_fixture/normal":
			failReads.Store(false)
		case "/__creation_fixture/fail-after-create":
			failAfterCreate.Store(true)
		case "/__creation_fixture/reject-next":
			rejectNext.Store(true)
		case "/__creation_fixture/drop-next":
			dropNext.Store(true)
		case "/__creation_fixture/truncate-next":
			truncateNext.Store(true)
		case "/__creation_fixture/hold-next":
			blocked.Store(false)
			holdNext.Store(true)
		case "/__creation_fixture/release-write":
			releaseWrite.Do(func() { close(writeRelease) })
		case "/__creation_fixture/hold-read-1":
			failReads.Store(false)
			blocked.Store(false)
			holdRead.Store(1)
		case "/__creation_fixture/hold-read-2":
			failReads.Store(false)
			blocked.Store(false)
			holdRead.Store(2)
		case "/__creation_fixture/release-read-1":
			releaseRead[0].Do(func() { close(readRelease[0]) })
		case "/__creation_fixture/release-read-2":
			releaseRead[1].Do(func() { close(readRelease[1]) })
		default:
			if r.Method == "GET" && r.URL.Path == "/api/v1/providers" {
				metadataReads.Add(1)
			}
			if r.Method == "GET" && r.URL.Path == "/api/v1/sessions" {
				reads.Add(1)
				if failReads.Load() {
					http.Error(w, "temporary session list failure", 503)
					return
				}
				if n := holdRead.Swap(0); n > 0 {
					rec := httptest.NewRecorder()
					production.ServeHTTP(rec, r)
					blocked.Store(true)
					select {
					case <-readRelease[n-1]:
					case <-r.Context().Done():
						return
					}
					copyResponse(w, rec)
					return
				}
			}
			if r.Method == "POST" && r.URL.Path == "/api/v1/sessions" {
				posts.Add(1)
				if rejectNext.Swap(false) {
					http.Error(w, "model/credential/scope not explicitly selected", 422)
					return
				}
				rec := httptest.NewRecorder()
				production.ServeHTTP(rec, r)
				if rec.Code == 201 {
					var created Session
					_ = json.Unmarshal(rec.Body.Bytes(), &created)
					idsMu.Lock()
					found := false
					for _, id := range createdIDs {
						if id == created.ID {
							found = true
						}
					}
					if !found {
						createdIDs = append(createdIDs, created.ID)
						commits.Add(1)
					}
					idsMu.Unlock()
				}
				if failAfterCreate.Swap(false) {
					failReads.Store(true)
				}
				if dropNext.Swap(false) {
					connection, _, e := w.(http.Hijacker).Hijack()
					if e != nil {
						t.Error(e)
						return
					}
					_ = connection.Close()
					return
				}
				if truncateNext.Swap(false) {
					w.Header().Set("Content-Type", "application/json")
					w.WriteHeader(rec.Code)
					_, _ = w.Write(rec.Body.Bytes()[:rec.Body.Len()/2])
					w.(http.Flusher).Flush()
					connection, _, e := w.(http.Hijacker).Hijack()
					if e != nil {
						t.Error(e)
						return
					}
					_ = connection.Close()
					return
				}
				if holdNext.Swap(false) {
					blocked.Store(true)
					select {
					case <-writeRelease:
					case <-r.Context().Done():
						return
					case <-time.After(15 * time.Second):
						t.Error("committed creation was not released")
						return
					}
				}
				copyResponse(w, rec)
				return
			}
			production.ServeHTTP(w, r)
			return
		}
		w.WriteHeader(204)
	}))
	defer app.Close()
	defer releaseWrite.Do(func() { close(writeRelease) })
	for i := range releaseRead {
		defer releaseRead[i].Do(func() { close(readRelease[i]) })
	}
	cmd := exec.Command("node", "e2e/conversation-creation.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("conversation creation browser: %v\n%s", err, out)
	}
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM sessions")
	assertSQLInt(t, s, 5, "SELECT COUNT(*) FROM sessions WHERE account_id='a'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM sessions WHERE account_id='b'")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM messages WHERE id='original-message' AND content='原對話的完整歷史'")
	if commits.Load() != 4 || inference.Load() != 1 {
		t.Fatalf("creation side effects: commits=%d inference=%d", commits.Load(), inference.Load())
	}
	t.Log(string(out))
}
