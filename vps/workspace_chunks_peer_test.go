package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestWorkspaceChunkFormalCallerLargeHistoryAndLostAcknowledgement(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	scope := seed(t, peer, "https://provider.example/v1")
	requireCode(t, req(t, peer, "POST", "/api/v1/my-models", scope), 201)
	var ack atomic.Bool
	var finishes atomic.Int32
	handler := hub.Handler()
	host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/workspace/transfers/finish" {
			finishes.Add(1)
		}
		if r.URL.Path == "/api/workspace/transfers/ack" && !ack.Swap(true) {
			recorder := httptest.NewRecorder()
			handler.ServeHTTP(recorder, r)
			if recorder.Code != 200 {
				t.Error("real remote ACK failed")
			}
			http.Error(w, "response lost after commit", 503)
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer) // original small-page caller and vault first
	content := strings.Repeat("retained-history-", ((33<<20)/len("retained-history-"))+1)
	if _, e := peer.Store.DB.Exec("INSERT INTO sessions VALUES('large-session','p','a','c','m','','CHAT_COMPLETIONS','完整大資料歷史',1); INSERT INTO messages(id,session_id,role,content,created_at) VALUES('large-message','large-session','user',?,2)", content); e != nil {
		t.Fatal(e)
	}
	w := req(t, peer, "POST", "/api/v1/workspace/sync", map[string]any{})
	requireCode(t, w, 502) // remote ACK committed, local CONSUMED receipt remains
	var id string
	if e := peer.Store.DB.QueryRow("SELECT id FROM workspace_transfers WHERE role='OUT' AND consumed=1 AND state='CONSUMED'").Scan(&id); e != nil {
		t.Fatal("destination data did not commit with its receipt", e)
	}
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM messages WHERE id='large-message'")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_changes WHERE kind='messages' AND id='[\"large-message\"]'")
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_dirty WHERE kind='messages' AND id='[\"large-message\"]'")
	// Recreate the formal caller after the lost ACK. It must read its persisted
	// CONSUMED marker and acknowledge the old ID, never consume the reply twice.
	fresh := &Server{Store: peer.Store, Token: peer.Token, Key: peer.Key, Client: peer.Client}
	before := finishes.Load()
	workspaceRunForTest(t, fresh)
	if finishes.Load() != before {
		t.Fatal("already consumed request was finished again")
	}
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE role='OUT' AND id=? AND consumed=1 AND state='ACKED'", id)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_transfer_parts WHERE role='OUT' AND transfer_id=?", id)
	var actual string
	if e := hub.Store.DB.QueryRow("SELECT content FROM messages WHERE id='large-message'").Scan(&actual); e != nil || actual != content {
		t.Fatal("formal caller truncated permanent message history")
	}
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestWorkspaceChunkCallerCancellationPauseAndConcurrentEdit(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	seed(t, peer, "https://provider.example/v1")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var interrupt atomic.Bool
	handler := hub.Handler()
	host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/workspace/transfers/part" && !interrupt.Swap(true) {
			recorder := httptest.NewRecorder()
			handler.ServeHTTP(recorder, r)
			if recorder.Code != 200 {
				t.Error("real part failed")
			}
			cancel()
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	changes, _, e := peer.workspaceDirtyRecords(context.Background(), 100)
	if e != nil {
		t.Fatal(e)
	}
	v, e := peer.workspaceStageOutgoing(context.Background(), host.URL, func() string {
		var encrypted string
		_ = peer.Store.DB.QueryRow("SELECT encrypted_token FROM workspace_peer").Scan(&encrypted)
		token, _ := peer.decrypt(encrypted)
		return token
	}(), WorkspaceExchange{Protocol: 1, Changes: changes})
	if e != nil {
		t.Fatal(e)
	}
	var encrypted string
	_ = peer.Store.DB.QueryRow("SELECT encrypted_token FROM workspace_peer").Scan(&encrypted)
	token, _ := peer.decrypt(encrypted)
	if _, e = peer.workspaceResumeOutgoing(ctx, host.URL, token, v); e == nil {
		t.Fatal("cancelled caller reported completion")
	}
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM providers")
	if _, e = peer.Store.DB.Exec("UPDATE providers SET name='離線最新修改' WHERE id='p'; UPDATE workspace_peer SET enabled=0"); e != nil {
		t.Fatal(e)
	}
	if _, e = peer.workspaceResumeOutgoing(context.Background(), host.URL, token, v); e == nil {
		t.Fatal("paused peer sent a pending request")
	}
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM providers")
	if _, e = peer.Store.DB.Exec("UPDATE workspace_peer SET enabled=1"); e != nil {
		t.Fatal(e)
	}
	result, e := peer.workspaceResumeOutgoing(context.Background(), host.URL, token, v)
	if e != nil || result.Sent != len(changes) {
		t.Fatal("pending request did not resume", e)
	}
	var local, remote string
	_ = peer.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&local)
	_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&remote)
	if local != "離線最新修改" || remote == "離線最新修改" {
		t.Fatal("old receipt overwrote a newer local edit")
	}
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM workspace_dirty WHERE kind='providers' AND id='[\"p\"]'")
	workspaceRunForTest(t, peer)
	_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&remote)
	if remote != local {
		t.Fatal("concurrent local edit was not retained in outbox")
	}
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM credentials WHERE enabled=1")
}

func TestWorkspaceChunkBoundedEncodingAndLegacyCanonicalBytes(t *testing.T) {
	record := WorkspaceRecord{Kind: "android:samples", ID: "边界", Revision: 9007199254740993, Data: map[string]any{"text": "<>&\u2028\n\"界", "array": []any{json.Number("9007199254740993"), nil, true}}}
	encoded, e := workspaceRecordJSON(record)
	if e != nil || encoded != workspaceJSON(record) {
		t.Fatal("large-record encoder changed existing conflict hash representation", e)
	}
	if !workspaceDataEqual(record.Data, map[string]any{"array": []any{json.Number("9007199254740993"), nil, true}, "text": "<>&\u2028\n\"界"}) {
		t.Fatal("canonical compare changed existing equality")
	}
	baseline, e := workspaceRecordBody(record, workspaceChunkRecordBytes)
	if e != nil {
		t.Fatal(e)
	}
	record.Data = map[string]any{"text": strings.Repeat("x", workspaceChunkRecordBytes)}
	if _, e = workspaceRecordBody(record, workspaceChunkRecordBytes); !errors.Is(e, errRequestJSONLimit) {
		t.Fatal("serialized record boundary is unbounded")
	}
	if baseline.size >= workspaceChunkRecordBytes {
		t.Fatal("small fixture invalid")
	}
}
