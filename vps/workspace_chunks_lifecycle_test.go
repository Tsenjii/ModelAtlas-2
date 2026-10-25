package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

type workspaceChunkTestClient struct {
	t             *testing.T
	host          *httptest.Server
	token, device string
}

func workspaceChunkClientForTest(t *testing.T, hub, peer *Server, host *httptest.Server, share bool) *workspaceChunkTestClient {
	t.Helper()
	workspacePairForTest(t, hub, peer, host, share)
	c := &workspaceChunkTestClient{t: t, host: host}
	var encrypted string
	if e := peer.Store.DB.QueryRow("SELECT device_id,encrypted_token FROM workspace_peer WHERE id=1").Scan(&c.device, &encrypted); e != nil {
		t.Fatal(e)
	}
	var e error
	c.token, e = peer.decrypt(encrypted)
	if e != nil {
		t.Fatal("test device token unavailable")
	}
	return c
}
func (c *workspaceChunkTestClient) call(path string, in any, code int, out any) {
	c.t.Helper()
	raw, e := json.Marshal(in)
	if e != nil {
		c.t.Fatal(e)
	}
	request, e := http.NewRequest("POST", c.host.URL+"/api/workspace/"+path, bytes.NewReader(raw))
	if e != nil {
		c.t.Fatal(e)
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	request.Header.Set("Content-Type", "application/json")
	response, e := c.host.Client().Do(request)
	if e != nil {
		c.t.Fatal("real TLS transfer failed", e)
	}
	defer response.Body.Close()
	data, e := io.ReadAll(io.LimitReader(response.Body, 512<<10))
	if e != nil {
		c.t.Fatal(e)
	}
	if response.StatusCode != code {
		c.t.Fatalf("%s: want %d got %d: %.200s", path, code, response.StatusCode, data)
	}
	if out != nil && json.Unmarshal(data, out) != nil {
		c.t.Fatalf("%s: invalid bounded response", path)
	}
}
func (c *workspaceChunkTestClient) begin(id string, body *requestJSON) workspaceTransfer {
	c.t.Helper()
	hash, n, e := workspaceReaderHash(body.reader())
	if e != nil {
		c.t.Fatal(e)
	}
	var out workspaceTransfer
	c.call("transfers/begin", map[string]any{"id": id, "bytes": n, "sha256": hash}, 200, &out)
	return out
}
func (c *workspaceChunkTestClient) part(id string, offset int64, data []byte, code int) workspaceTransfer {
	c.t.Helper()
	var out workspaceTransfer
	var target any
	if code == 200 {
		target = &out
	}
	c.call("transfers/part", map[string]any{"id": id, "offset": offset, "sha256": contentHash(data), "data": base64.StdEncoding.EncodeToString(data)}, code, target)
	return out
}
func (c *workspaceChunkTestClient) upload(id string, body *requestJSON, start int64) {
	c.t.Helper()
	reader := body.reader()
	defer reader.Close()
	if _, e := io.CopyN(io.Discard, reader, start); e != nil {
		c.t.Fatal(e)
	}
	buffer := make([]byte, workspaceChunkBytes)
	for offset := start; offset < body.size; {
		n, e := io.ReadFull(reader, buffer[:min(int64(len(buffer)), body.size-offset)])
		if e != nil {
			c.t.Fatal(e)
		}
		v := c.part(id, offset, buffer[:n], 200)
		offset += int64(n)
		if v.Received != offset {
			c.t.Fatal("persisted upload progress diverged")
		}
	}
}
func (c *workspaceChunkTestClient) reply(v workspaceTransfer) WorkspaceReply {
	c.t.Helper()
	var out bytes.Buffer
	for offset := int64(0); offset < v.ReplyBytes; {
		var part struct {
			ID         string
			Offset     int64
			Hash       string `json:"sha256"`
			Data       string
			ReplyHash  string `json:"reply_sha256"`
			ReplyBytes int64  `json:"reply_bytes"`
		}
		c.call("transfers/reply", map[string]any{"id": v.ID, "offset": offset}, 200, &part)
		data, e := base64.StdEncoding.DecodeString(part.Data)
		if e != nil || part.ID != v.ID || part.Offset != offset || part.ReplyHash != v.ReplyHash || part.ReplyBytes != v.ReplyBytes || contentHash(data) != part.Hash || int64(len(data)) != min(int64(workspaceChunkBytes), v.ReplyBytes-offset) {
			c.t.Fatal("reply fragment not bound to receipt")
		}
		out.Write(data)
		offset += int64(len(data))
	}
	if contentHash(out.Bytes()) != v.ReplyHash {
		c.t.Fatal("reply receipt integrity failure")
	}
	var reply WorkspaceReply
	decoder := json.NewDecoder(&out)
	decoder.UseNumber()
	if e := decoder.Decode(&reply); e != nil {
		c.t.Fatal(e)
	}
	return reply
}

func TestWorkspaceChunkLargeRecordResumeRestartAndLostFinishReceipt(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	var current atomic.Pointer[Server]
	current.Store(hub)
	host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { current.Load().Handler().ServeHTTP(w, r) }))
	defer host.Close()
	c := workspaceChunkClientForTest(t, hub, peer, host, false)
	// One retained record exceeds the original 32 MiB page. Multibyte text and a
	// >2^53 integer must survive both fragment boundaries and JSON decoding.
	text := strings.Repeat("大資料界線", ((33<<20)/len("大資料界線"))+1)
	record := WorkspaceRecord{Kind: "android:samples", ID: "large-history", Data: map[string]any{"text": text, "tokens": json.Number("9007199254740993")}}
	body, e := workspaceExchangeBody(WorkspaceExchange{Protocol: 1, Changes: []WorkspaceRecord{record}})
	if e != nil || body.size <= workspacePageBytes {
		t.Fatal("large fixture is not beyond original limit", e)
	}
	first := c.begin("restart-large", body)
	buffer := make([]byte, workspaceChunkBytes)
	if _, e = io.ReadFull(body.reader(), buffer); e != nil {
		t.Fatal(e)
	}
	c.part(first.ID, workspaceChunkBytes, buffer, 409)
	c.part(first.ID, 0, buffer, 200)
	c.part(first.ID, 0, buffer, 200)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_native_archive")
	// Close the actual database, reopen it with the same vault, and rebuild the
	// HTTP server at the same TLS origin. Nothing reconstructs progress in RAM.
	path := hub.Store.path
	if e = hub.Store.Close(); e != nil {
		t.Fatal(e)
	}
	reopened, e := OpenStore(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	hub = &Server{Store: reopened, Token: hub.Token, Key: hub.Key, Client: hub.Client}
	current.Store(hub)
	var resumed workspaceTransfer
	c.call("transfers/status", map[string]any{"id": first.ID}, 200, &resumed)
	if resumed.Received != workspaceChunkBytes || resumed.State != "UPLOADING" {
		t.Fatal("restart lost durable offset")
	}
	c.upload(first.ID, body, resumed.Received)
	c.call("transfers/finish", map[string]any{"id": first.ID}, 200, nil) // response deliberately discarded
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_native_archive WHERE kind='android:samples' AND id='large-history'")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_changes WHERE kind='android:samples' AND id='large-history'")
	var done workspaceTransfer
	c.call("transfers/status", map[string]any{"id": first.ID}, 200, &done)
	if done.State != "DONE" || done.ReplyBytes <= workspacePageBytes {
		t.Fatal("durable large reply receipt missing")
	}
	c.call("transfers/finish", map[string]any{"id": first.ID}, 200, &resumed)
	if resumed.ReplyHash != done.ReplyHash || resumed.ReplyBytes != done.ReplyBytes {
		t.Fatal("replayed finish replaced committed receipt")
	}
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_changes WHERE kind='android:samples' AND id='large-history'")
	reply := c.reply(done)
	if len(reply.Accepted) != 1 || reply.Accepted[0].Data["text"] != text || reply.Accepted[0].Data["tokens"] != json.Number("9007199254740993") {
		t.Fatal("large record was truncated or integer rounded")
	}
	c.call("transfers/abort", map[string]any{"id": done.ID}, 409, nil)
	c.call("transfers/ack", map[string]any{"id": done.ID, "reply_sha256": strings.Repeat("0", 64)}, 409, nil)
	c.call("transfers/ack", map[string]any{"id": done.ID, "reply_sha256": done.ReplyHash}, 200, nil)
	c.call("transfers/ack", map[string]any{"id": done.ID, "reply_sha256": done.ReplyHash}, 200, nil)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE state='ACKED' AND consumed=1")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_native_archive")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestWorkspaceChunkEncryptedSecretScopeAndAtomicRollback(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	seed(t, peer, "https://provider.example/v1")
	c := workspaceChunkClientForTest(t, hub, peer, host, true)
	changes, secrets, e := peer.workspaceDirtyRecords(context.Background(), 100)
	if e != nil {
		t.Fatal(e)
	}
	body, e := workspaceExchangeBody(WorkspaceExchange{Protocol: 1, Changes: changes, Secrets: secrets})
	if e != nil {
		t.Fatal(e)
	}
	v := c.begin("privileged-receipt", body)
	c.upload(v.ID, body, 0)
	var encrypted string
	if e = hub.Store.DB.QueryRow("SELECT encrypted_payload FROM workspace_transfer_parts LIMIT 1").Scan(&encrypted); e != nil || strings.Contains(encrypted, "top-secret-example-key") || strings.Contains(encrypted, c.token) {
		t.Fatal("plaintext transport cache")
	}
	// Immutable reply storage must fail inside the same product transaction.
	if _, e = hub.Store.DB.Exec("CREATE TRIGGER fail_chunk_reply BEFORE INSERT ON workspace_transfer_parts WHEN NEW.side='reply' BEGIN SELECT RAISE(ABORT,'fixture reply storage unavailable'); END"); e != nil {
		t.Fatal(e)
	}
	c.call("transfers/finish", map[string]any{"id": v.ID}, 503, nil)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM providers")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_changes")
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE state='UPLOADED'")
	if _, e = hub.Store.DB.Exec("DROP TRIGGER fail_chunk_reply"); e != nil {
		t.Fatal(e)
	}
	c.call("transfers/finish", map[string]any{"id": v.ID}, 200, &v)
	reply := c.reply(v)
	if len(reply.Secrets) != len(secrets) || len(secrets) != 1 {
		t.Fatal("authorized reply changed credential scopes")
	}
	for key, want := range secrets {
		if reply.Secrets[key].Key != want.Key || want.Key != "top-secret-example-key" {
			t.Fatal("authorized reply lost vault material")
		}
	}
	if e = hub.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted); e != nil {
		t.Fatal(e)
	}
	plain, e := hub.decrypt(encrypted)
	if e != nil || plain != "top-secret-example-key" {
		t.Fatal("destination vault material missing")
	}
	// Transport-token revocation blocks even an already committed receipt.
	requireCode(t, req(t, hub, "POST", "/api/v1/workspace/revoke", map[string]any{"device_id": c.device, "confirm": true}), 200)
	c.call("transfers/status", map[string]any{"id": v.ID}, 401, nil)
	c.call("transfers/reply", map[string]any{"id": v.ID, "offset": 0}, 401, nil)
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM credentials")
}

func TestWorkspaceChunkRejectsCorruptionIdentityAndScopeBeforeApply(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	c := workspaceChunkClientForTest(t, hub, peer, host, false)
	body, e := workspaceExchangeBody(WorkspaceExchange{Protocol: 1, Changes: []WorkspaceRecord{{Kind: "android:samples", ID: "unchanged", Data: map[string]any{"text": "local last good"}}}})
	if e != nil {
		t.Fatal(e)
	}
	v := c.begin("integrity", body)
	c.call("transfers/begin", map[string]any{"id": v.ID, "bytes": body.size, "sha256": strings.Repeat("0", 64)}, 409, nil)
	c.call("transfers/begin", map[string]any{"id": "over-limit", "bytes": workspaceChunkRequestBytes + 1, "sha256": strings.Repeat("0", 64)}, 422, nil)
	c.upload(v.ID, body, 0)
	// Valid per-part digest cannot substitute for the declared complete digest.
	data, e := io.ReadAll(body.reader())
	if e != nil {
		t.Fatal(e)
	}
	data = bytes.Replace(data, []byte("last good"), []byte("last evil"), 1)
	if len(data) != int(body.size) {
		t.Fatal("corruption fixture length changed")
	}
	sealed, hash, e := hub.workspaceSealChunk(workspaceTransfer{Role: "IN", Owner: c.device, ID: v.ID, Context: func() string {
		var x string
		_ = hub.Store.DB.QueryRow("SELECT context_hash FROM workspace_transfers WHERE id=?", v.ID).Scan(&x)
		return x
	}(), RequestHash: v.RequestHash, RequestBytes: v.RequestBytes}, "request", 0, data)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = hub.Store.DB.Exec("UPDATE workspace_transfer_parts SET sha256=?,encrypted_payload=? WHERE transfer_id=?", hash, sealed, v.ID); e != nil {
		t.Fatal(e)
	}
	c.call("transfers/finish", map[string]any{"id": v.ID}, 422, nil)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_native_archive")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_changes")
	c.call("transfers/abort", map[string]any{"id": v.ID}, 200, nil)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
	v = c.begin("metadata-only", body)
	c.upload(v.ID, body, 0)
	// A device may not use an alternate origin or another device's receipt.
	request, _ := http.NewRequest("POST", host.URL+"/api/workspace/transfers/status", strings.NewReader(`{"id":"metadata-only"}`))
	request.Host = "other.example"
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.token)
	response, e := host.Client().Do(request)
	if e != nil {
		t.Fatal(e)
	}
	response.Body.Close()
	if response.StatusCode != 409 {
		t.Fatal("origin change recovered another scope")
	}
	otherPeer := testServer(t)
	other := workspaceChunkClientForTest(t, hub, otherPeer, host, false)
	other.call("transfers/status", map[string]any{"id": v.ID}, 404, nil)
	privileged, e := workspaceExchangeBody(WorkspaceExchange{Protocol: 1, Changes: []WorkspaceRecord{}, Secrets: map[string]WorkspaceSecret{"credentials/not-authorized": {Key: "synthetic-only-key"}}})
	if e != nil {
		t.Fatal(e)
	}
	c.call("transfers/abort", map[string]any{"id": v.ID}, 200, nil)
	v = c.begin("no-secret-escalation", privileged)
	c.upload(v.ID, privileged, 0)
	c.call("transfers/finish", map[string]any{"id": v.ID}, 400, nil)
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM credentials")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_changes")
}
