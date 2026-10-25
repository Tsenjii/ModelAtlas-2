package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserWorkspaceChunksResumePauseAndRetainPermanentHistory(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	hub, peer := testServer(t), testServer(t)
	scope := seed(t, hub, "https://no-inference.example/v1")
	requireCode(t, req(t, hub, "POST", "/api/v1/my-models", scope), 201)
	var block atomic.Bool
	var held atomic.Bool
	var finishCount atomic.Int32
	release := make(chan struct{})
	var releaseOnce sync.Once
	actual := hub.Handler()
	host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/workspace/transfers/finish" {
			finishCount.Add(1)
		}
		if r.URL.Path == "/api/workspace/transfers/part" && block.CompareAndSwap(true, false) {
			recorder := httptest.NewRecorder()
			actual.ServeHTTP(recorder, r)
			held.Store(true)
			select {
			case <-release:
			case <-r.Context().Done():
				return
			}
			for key, values := range recorder.Header() {
				w.Header()[key] = values
			}
			w.WriteHeader(recorder.Code)
			_, _ = w.Write(recorder.Body.Bytes())
			return
		}
		actual.ServeHTTP(w, r)
	}))
	defer host.Close()
	defer releaseOnce.Do(func() { close(release) })
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer)
	content := "retained/" + strings.Repeat("x", 1<<20) + "/尾界"
	if _, e := peer.Store.DB.Exec("INSERT INTO sessions VALUES('chunk-browser-session','p','a','c','m','','CHAT_COMPLETIONS','斷線後保留的歷史',1);INSERT INTO messages(id,session_id,role,content,created_at) VALUES('chunk-browser-message','chunk-browser-session','user',?,2)", content); e != nil {
		t.Fatal(e)
	}
	changes, secrets, e := peer.workspaceDirtyRecords(context.Background(), 100)
	if e != nil {
		t.Fatal(e)
	}
	var encrypted string
	_ = peer.Store.DB.QueryRow("SELECT encrypted_token FROM workspace_peer").Scan(&encrypted)
	token, e := peer.decrypt(encrypted)
	if e != nil {
		t.Fatal(e)
	}
	transfer, e := peer.workspaceStageOutgoing(context.Background(), host.URL, token, WorkspaceExchange{Protocol: 1, Cursor: func() int64 {
		var n int64
		_ = peer.Store.DB.QueryRow("SELECT cursor FROM workspace_peer").Scan(&n)
		return n
	}(), Changes: changes, Secrets: secrets})
	if e != nil {
		t.Fatal(e)
	}
	block.Store(true)
	handler := peer.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__chunks/release":
			releaseOnce.Do(func() { close(release) })
			jsonOK(w, 200, map[string]bool{"released": true})
			return
		case "/__chunks/state":
			var local, remote string
			var models, samples, events int
			_ = peer.Store.DB.QueryRow("SELECT content FROM messages WHERE id='chunk-browser-message'").Scan(&local)
			_ = hub.Store.DB.QueryRow("SELECT content FROM messages WHERE id='chunk-browser-message'").Scan(&remote)
			_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&models)
			_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&samples)
			_ = hub.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_changes WHERE kind='messages' AND id='[\"chunk-browser-message\"]'").Scan(&events)
			jsonOK(w, 200, map[string]any{"held": held.Load(), "models": models, "samples": samples, "events": events, "finish": finishCount.Load(), "local_hash": workspaceHash(local), "remote_hash": workspaceHash(remote), "expected_hash": workspaceHash(content), "id": transfer.ID})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	command := exec.Command("node", "e2e/workspace-chunks.cjs", app.URL)
	command.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+peer.Token)
	out, e := command.CombinedOutput()
	if e != nil {
		t.Fatalf("chunk recovery browser: %v\n%s", e, out)
	}
	t.Log(string(out))
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE id=? AND state='ACKED'", transfer.ID)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
}
