package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func TestWorkspaceChunkExplicitResetRetainsOfflineProductAndSecret(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	c := workspaceChunkClientForTest(t, hub, peer, host, true)
	seed(t, peer, "https://provider.example/v1")
	changes, secrets, e := peer.workspaceDirtyRecords(context.Background(), 100)
	if e != nil {
		t.Fatal(e)
	}
	request := WorkspaceExchange{Protocol: 1, Changes: changes, Secrets: secrets}
	v, e := peer.workspaceStageOutgoing(context.Background(), host.URL, c.token, request)
	if e != nil {
		t.Fatal(e)
	}
	body, e := workspaceExchangeBody(request)
	if e != nil {
		t.Fatal(e)
	}
	c.begin(v.ID, body)
	c.upload(v.ID, body, 0)
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/control", map[string]any{"enabled": false}), 200)
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/transfer/discard", map[string]any{"id": v.ID, "confirm": false}), 400)
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/transfer/discard", map[string]any{"id": v.ID, "confirm": true}), 200)
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM workspace_transfer_parts")
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE id=? AND state='ABORTED'", v.ID)
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM workspace_transfers WHERE id=? AND state='ABORTED'", v.ID)
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM providers")
	assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM workspace_dirty WHERE kind='providers'")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM providers")
	var encrypted string
	if e = peer.Store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted); e != nil {
		t.Fatal(e)
	}
	if secret, e := peer.decrypt(encrypted); e != nil || secret != "top-secret-example-key" {
		t.Fatal("transport reset removed a product secret")
	}
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/control", map[string]any{"enabled": true, "resumable": true}), 200)
	workspaceRunForTest(t, peer)
	assertSQLInt(t, hub, 1, "SELECT COUNT(*) FROM providers")
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_dirty")
	assertSQLInt(t, hub, 0, "SELECT COUNT(*) FROM telemetry")
}
func TestWorkspaceChunkPreferredCallerFallsBackOnlyForLegacyCapability(t *testing.T) {
	for _, mode := range []string{"legacy", "invalid-scope", "revoked", "legacy-paused"} {
		t.Run(mode, func(t *testing.T) {
			hub, peer := testServer(t), testServer(t)
			seed(t, hub, "https://provider.example/v1")
			if mode == "legacy-paused" {
				for i := 0; i < 230; i++ {
					id := "retained-" + strconv.Itoa(i)
					if _, e := hub.Store.DB.Exec("INSERT INTO models(provider_id,id,display_name,updated_at) VALUES('p',?,?,?)", id, id, now()); e != nil {
						t.Fatal(e)
					}
				}
			}
			handler := hub.Handler()
			var calls atomic.Int32
			host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/workspace/capabilities" {
					if strings.HasPrefix(mode, "legacy") {
						http.NotFound(w, r)
					} else if mode == "revoked" {
						http.Error(w, "revoked", 401)
					} else {
						jsonOK(w, 200, workspaceChunkCapabilities{Protocol: 1, Workspace: "different", Version: 1, Chunk: workspaceChunkBytes, Record: workspaceChunkRecordBytes, Request: workspaceChunkRequestBytes, Reply: workspaceChunkReplyBytes})
					}
					return
				}
				if r.URL.Path == "/api/workspace/exchange" {
					calls.Add(1)
				}
				handler.ServeHTTP(w, r)
				if mode == "legacy-paused" && r.URL.Path == "/api/workspace/exchange" && calls.Load() == 1 {
					requireCode(t, req(t, peer, "POST", "/api/v1/workspace/control", map[string]any{"enabled": false}), 200)
				}
			}))
			defer host.Close()
			workspacePairForTest(t, hub, peer, host, true)
			requireCode(t, req(t, peer, "POST", "/api/v1/workspace/control", map[string]any{"enabled": true, "resumable": true}), 200)
			w := req(t, peer, "POST", "/api/v1/workspace/sync", map[string]any{})
			if mode == "legacy-paused" {
				requireCode(t, w, 502)
				if calls.Load() != 1 {
					t.Fatal("pause allowed another legacy page")
				}
				assertSQLInt(t, peer, 0, "SELECT enabled FROM workspace_peer")
				assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM providers")
				assertSQLInt(t, hub, 231, "SELECT COUNT(*) FROM models")
			} else if mode == "legacy" {
				requireCode(t, w, 200)
				if calls.Load() == 0 {
					t.Fatal("legacy caller never used real v1 exchange")
				}
				assertSQLInt(t, peer, 1, "SELECT COUNT(*) FROM providers")
			} else {
				requireCode(t, w, 502)
				if calls.Load() != 0 {
					t.Fatal("unsafe capability response silently fell back")
				}
				assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM providers")
			}
		})
	}
}
func TestWorkspaceChunkMigrationPreservesOldPeerVaultAndTakesActualV36Snapshot(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	var before string
	if e := peer.Store.DB.QueryRow("SELECT encrypted_token FROM workspace_peer").Scan(&before); e != nil {
		t.Fatal(e)
	}
	path := peer.Store.path
	if _, e := peer.Store.DB.Exec("DROP TABLE workspace_transfer_parts;DROP TABLE workspace_transfers;ALTER TABLE workspace_peer DROP COLUMN resumable;PRAGMA user_version=36"); e != nil {
		t.Fatal(e)
	}
	peer = reopenScheduleStore(t, peer)
	assertSQLInt(t, peer, 37, "PRAGMA user_version")
	assertSQLInt(t, peer, 0, "SELECT resumable FROM workspace_peer")
	var after string
	if e := peer.Store.DB.QueryRow("SELECT encrypted_token FROM workspace_peer").Scan(&after); e != nil || before != after {
		t.Fatal("migration changed transport credential")
	}
	files, e := filepath.Glob(filepath.Join(filepath.Dir(path), "upgrade-backups", "schema-36-to-37-*.sqlite"))
	if e != nil || len(files) != 1 {
		t.Fatal("missing v36 snapshot", e)
	}
	if e = verifySnapshot(files[0]); e != nil {
		t.Fatal(e)
	}
	for _, kind := range workspaceTables {
		if strings.Contains(kind, "transfer") {
			t.Fatal("runtime spool entered product synchronization registry")
		}
	}
}
func TestWorkspaceChunkLargeConflictPreviewKeepsFullCASAndHistory(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	seed(t, hub, "https://provider.example/v1")
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, true)
	workspaceRunForTest(t, peer)
	local, remote := "local-"+strings.Repeat("x", 8<<20), "remote-"+strings.Repeat("y", 8<<20)
	if _, e := peer.Store.DB.Exec("UPDATE providers SET description=? WHERE id='p'", local); e != nil {
		t.Fatal(e)
	}
	if _, e := hub.Store.DB.Exec("UPDATE providers SET description=? WHERE id='p'", remote); e != nil {
		t.Fatal(e)
	}
	workspaceRunForTest(t, peer)
	var id string
	if e := peer.Store.DB.QueryRow("SELECT id FROM workspace_conflicts WHERE state='OPEN' LIMIT 1").Scan(&id); e != nil {
		t.Fatal(e)
	}
	w := req(t, peer, "GET", "/api/v1/workspace/conflict?preview=1&id="+id, nil)
	requireCode(t, w, 200)
	if w.Body.Len() > 256<<10 {
		t.Fatal("conflict preview exceeded native client response budget")
	}
	var preview struct {
		Hash    string          `json:"local_hash"`
		Limited bool            `json:"preview_limited"`
		Local   WorkspaceRecord `json:"local"`
		Remote  WorkspaceRecord `json:"remote"`
	}
	if json.Unmarshal(w.Body.Bytes(), &preview) != nil || !preview.Limited || !strings.HasPrefix(str(preview.Local.Data["description"]), "local-") || !strings.HasPrefix(str(preview.Remote.Data["description"]), "remote-") {
		t.Fatal("bounded preview lost identity or failed to disclose summary")
	}
	live, _, e := peer.workspaceLocalRecord(context.Background(), "providers", workspaceJSON([]string{"p"}))
	if e != nil || preview.Hash != workspaceHash(workspaceJSON(live)) {
		t.Fatal("preview changed the full live-record CAS digest")
	}
	if _, e = peer.Store.DB.Exec("UPDATE providers SET description=? WHERE id='p'", "new-"+local); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/resolve", map[string]any{"id": id, "choice": "remote", "confirm": true, "local_hash": preview.Hash}), 409)
	w = req(t, peer, "GET", "/api/v1/workspace/conflict?preview=1&id="+id, nil)
	requireCode(t, w, 200)
	if json.Unmarshal(w.Body.Bytes(), &preview) != nil {
		t.Fatal("preview invalid")
	}
	requireCode(t, req(t, peer, "POST", "/api/v1/workspace/resolve", map[string]any{"id": id, "choice": "remote", "confirm": true, "local_hash": preview.Hash}), 200)
	workspaceRunForTest(t, peer)
	var actual string
	if e = peer.Store.DB.QueryRow("SELECT description FROM providers WHERE id='p'").Scan(&actual); e != nil || actual != remote {
		t.Fatal("selected full conflict content was truncated")
	}
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM workspace_conflicts WHERE state='OPEN'")
	var minimum int
	if e = peer.Store.DB.QueryRow("SELECT MIN(length(local_json)) FROM workspace_conflicts").Scan(&minimum); e != nil || minimum < 8<<20 {
		t.Fatal("full original conflict history was removed")
	}
}
