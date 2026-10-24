package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func workspacePairForTest(t *testing.T, hub, peer *Server, host *httptest.Server, share bool) {
	t.Helper()
	w := req(t, hub, "POST", "/api/v1/workspace/pair", map[string]any{"share_secrets": share})
	requireCode(t, w, 200)
	var pair map[string]any
	if json.Unmarshal(w.Body.Bytes(), &pair) != nil {
		t.Fatal("invalid pairing")
	}
	peer.Client = host.Client()
	w = req(t, peer, "POST", "/api/v1/workspace/connect", map[string]any{"url": host.URL, "code": pair["code"], "label": "測試 Mac", "platform": "macOS"})
	requireCode(t, w, 200)
}
func workspaceRunForTest(t *testing.T, s *Server) {
	t.Helper()
	w := req(t, s, "POST", "/api/v1/workspace/sync", map[string]any{})
	requireCode(t, w, 200)
}
func TestWorkspaceRealTLSBidirectionalSecretsHistoryAndTombstone(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	selection := seed(t, hub, "https://provider.example/v1")
	requireCode(t, req(t, hub, "POST", "/api/v1/my-models", selection), 201)
	if _, e := hub.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES('sample','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','MANUAL_TEST','SUCCESS',?)", now()); e != nil {
		t.Fatal(e)
	}
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer)
	var key string
	if e := peer.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&key); e != nil {
		t.Fatal(e)
	}
	if plain, e := peer.decrypt(key); e != nil || plain != "top-secret-example-key" {
		t.Fatal("secret was not reencrypted in destination vault")
	}
	for _, table := range []string{"workspace_rows", "workspace_changes", "workspace_conflicts"} {
		rows, e := peer.Store.DB.Query("SELECT * FROM " + table)
		if e != nil {
			t.Fatal(e)
		}
		cols, _ := rows.Columns()
		for rows.Next() {
			v := make([]any, len(cols))
			p := make([]any, len(cols))
			for i := range v {
				p[i] = &v[i]
			}
			if e = rows.Scan(p...); e != nil {
				t.Fatal(e)
			}
			if strings.Contains(workspaceJSON(v), "top-secret-example-key") {
				t.Fatal("plaintext secret in sync metadata")
			}
		}
		rows.Close()
	}
	if _, e := peer.Store.DB.Exec("UPDATE providers SET name='從 Mac 修改' WHERE id='p'"); e != nil {
		t.Fatal(e)
	}
	workspaceRunForTest(t, peer)
	var name string
	_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&name)
	if name != "從 Mac 修改" {
		t.Fatalf("outbound edit lost: %q", name)
	}
	requireCode(t, req(t, peer, "DELETE", "/api/v1/my-models", selection), 200)
	workspaceRunForTest(t, peer)
	var count int
	_ = hub.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&count)
	if count != 0 {
		t.Fatal("tombstone did not propagate")
	}
	for _, s := range []*Server{hub, peer} {
		_ = s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE id='sample'").Scan(&count)
		if count != 1 {
			t.Fatal("history lost")
		}
	}
}
func TestWorkspaceOfflineConflictKeepsBothAndExplicitLocalWins(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	seed(t, hub, "https://provider.example/v1")
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer)
	_, _ = peer.Store.DB.Exec("UPDATE providers SET name='離線修改' WHERE id='p'")
	_, _ = hub.Store.DB.Exec("UPDATE providers SET name='中心修改' WHERE id='p'")
	workspaceRunForTest(t, peer)
	var a, b, id string
	_ = peer.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&a)
	_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&b)
	if a != "離線修改" || b != "中心修改" {
		t.Fatal("conflict silently overwrote a version")
	}
	if e := peer.Store.DB.QueryRow("SELECT id FROM workspace_conflicts WHERE kind='providers' AND state='OPEN' LIMIT 1").Scan(&id); e != nil {
		t.Fatal("conflict missing", e)
	}
	detail := req(t, peer, "GET", "/api/v1/workspace/conflict?id="+id, nil)
	requireCode(t, detail, 200)
	var preview map[string]any
	_ = json.Unmarshal(detail.Body.Bytes(), &preview)
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/resolve", map[string]any{"id": id, "choice": "local", "confirm": true, "local_hash": preview["local_hash"]}), 200)
	workspaceRunForTest(t, peer)
	_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&b)
	if b != "離線修改" {
		t.Fatal("explicit resolution was not applied")
	}
	var preserved int
	_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_conflicts WHERE state='LOCAL'").Scan(&preserved)
	if preserved < 1 {
		t.Fatal("conflict history removed")
	}
}
func TestWorkspacePairingSingleUseExpiryAndDeviceRevocation(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	var id, encrypted string
	_ = peer.Store.DB.QueryRow("SELECT device_id,encrypted_token FROM workspace_peer").Scan(&id, &encrypted)
	token, e := peer.decrypt(encrypted)
	if e != nil {
		t.Fatal(e)
	}
	call := func() int {
		r, e := http.NewRequestWithContext(context.Background(), "POST", host.URL+"/api/workspace/exchange", strings.NewReader(workspaceJSON(WorkspaceExchange{Protocol: 1, Changes: []WorkspaceRecord{}})))
		if e != nil {
			t.Fatal(e)
		}
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+token)
		resp, e := host.Client().Do(r)
		if e != nil {
			t.Fatal(e)
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return resp.StatusCode
	}
	if call() != 200 {
		t.Fatal("paired device rejected")
	}
	requireCode(t, req(t, hub, "POST", "/api/v1/workspace/revoke", map[string]any{"device_id": id, "confirm": true}), 200)
	if call() != 401 {
		t.Fatal("revoked token still accepted")
	}
	code := workspaceRandom()
	_, e = hub.Store.DB.Exec("INSERT INTO workspace_pairings VALUES(?,0,?,NULL)", workspaceHash(code), now()-1)
	if e != nil {
		t.Fatal(e)
	}
	r := httptest.NewRequest("POST", "/api/workspace/pair", strings.NewReader(workspaceJSON(map[string]any{"code": code, "label": "Android", "platform": "Android"})))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	hub.Handler().ServeHTTP(w, r)
	requireCode(t, w, 403)
}

func TestWorkspaceMetadataOnlyNeverTransfersSecretAndDoesNotLoop(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	seed(t, peer, "https://provider.example/v1")
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	var raw string
	var enabled, pending int
	if e := hub.Store.DB.QueryRow("SELECT encrypted_key,enabled FROM credentials WHERE id='c'").Scan(&raw, &enabled); e != nil {
		t.Fatal(e)
	}
	if raw != "" || enabled != 0 {
		t.Fatal("metadata-only pairing transferred or enabled a key")
	}
	_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_dirty").Scan(&pending)
	if pending != 0 {
		t.Fatalf("acknowledged metadata loops forever: %d", pending)
	}
	_ = peer.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&raw)
	plain, e := peer.decrypt(raw)
	if e != nil || plain != "top-secret-example-key" {
		t.Fatal("metadata-only pairing erased local secret")
	}
}
func TestWorkspaceRotationLargeIntegersAndRemoteConflictChoice(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	seed(t, hub, "https://provider.example/v1")
	_, e := hub.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,output_tokens,created_at) VALUES('large','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','MANUAL_TEST','SUCCESS',9007199254740993,1)")
	if e != nil {
		t.Fatal(e)
	}
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer)
	var tokens int64
	_ = peer.Store.DB.QueryRow("SELECT output_tokens FROM telemetry WHERE id='large'").Scan(&tokens)
	if tokens != 9007199254740993 {
		t.Fatalf("sync rounded an integer: %d", tokens)
	}
	requireCode(t, req(t, hub, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "synthetic-rotated-material", "confirm": true}), 200)
	workspaceRunForTest(t, peer)
	var encrypted string
	_ = peer.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted)
	key, e := peer.decrypt(encrypted)
	if e != nil || key != "synthetic-rotated-material" {
		t.Fatal("rotation did not transfer correctly")
	}
	var version int
	_ = peer.Store.DB.QueryRow("SELECT MAX(version) FROM credential_secret_versions WHERE credential_id='c'").Scan(&version)
	if version != 2 {
		t.Fatalf("material epoch lost: %d", version)
	}
	_, _ = peer.Store.DB.Exec("UPDATE providers SET name='離線' WHERE id='p'")
	_, _ = hub.Store.DB.Exec("UPDATE providers SET name='中心最新版' WHERE id='p'")
	workspaceRunForTest(t, peer)
	var id string
	_ = peer.Store.DB.QueryRow("SELECT id FROM workspace_conflicts WHERE state='OPEN' LIMIT 1").Scan(&id)
	detail := req(t, peer, "GET", "/api/v1/workspace/conflict?id="+id, nil)
	requireCode(t, detail, 200)
	var preview map[string]any
	_ = json.Unmarshal(detail.Body.Bytes(), &preview)
	_, _ = peer.Store.DB.Exec("UPDATE providers SET name='預覽之後的新稿' WHERE id='p'")
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/resolve", map[string]any{"id": id, "choice": "remote", "confirm": true, "local_hash": preview["local_hash"]}), 409)
	detail = req(t, peer, "GET", "/api/v1/workspace/conflict?id="+id, nil)
	_ = json.Unmarshal(detail.Body.Bytes(), &preview)
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/resolve", map[string]any{"id": id, "choice": "remote", "confirm": true, "local_hash": preview["local_hash"]}), 200)
	workspaceRunForTest(t, peer)
	var name string
	_ = peer.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&name)
	if name != "中心最新版" {
		t.Fatalf("remote conflict choice failed: %s", name)
	}
}
