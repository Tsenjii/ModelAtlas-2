package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func operationsEnvironment(t *testing.T, s *Server) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "private.env")
	value := "MODELATLAS_ADMIN_TOKEN=" + s.Token + "\nMODELATLAS_MASTER_KEY=" + base64.StdEncoding.EncodeToString(s.Key) + "\nMODELATLAS_DB=\"" + s.Store.path + "\"\n"
	if err := os.WriteFile(path, []byte(value), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestOperationsSnapshotPreflightAndRestorePreserveVaultAndHistory(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://never-called.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	session, err := s.createTestSession(context.Background(), v)
	if err != nil {
		t.Fatal(err)
	}
	content := strings.Repeat("永久對話😀\n", 100000)
	if _, err = s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('kept',?,'user',?,1)", session, content); err != nil {
		t.Fatal(err)
	}
	archiveInsert(t, s, v, "original-price", "MANUAL_PRICE", "USER", "SUCCESS", `{"price":{"input":"0.000000000123456789"}}`, 1000)
	// Exercise both encrypted custom fields and historical rotated secrets.
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "rotated-provider-secret", "confirm": true}), 200)
	fields, err := s.encryptFields([]AuthField{{Name: "X-Private", Secret: "private-header-value"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.Store.DB.Exec("INSERT OR REPLACE INTO credential_auth_fields VALUES('p','a','c',?)", fields); err != nil {
		t.Fatal(err)
	}
	env := operationsEnvironment(t, s)
	snapshot := filepath.Join(t.TempDir(), "backup.sqlite")
	backupArgs := []string{"ops", "backup", "--env-file", env, "--output", snapshot}
	if err = operationCommand(backupArgs, io.Discard); err == nil {
		t.Fatal("offline backup ignored the live writer lock")
	}
	if err = s.Store.Close(); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err = operationCommand(backupArgs, &output); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{snapshot, snapshot + ".json"} {
		info, e := os.Stat(path)
		if e != nil || info.Mode().Perm() != 0600 {
			t.Fatal("snapshot permissions", e)
		}
	}
	digest, err := fileDigest(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	validation := filepath.Join(t.TempDir(), "candidate.sqlite")
	if err = operationCommand([]string{"ops", "preflight", "--env-file", env, "--database", snapshot, "--output", validation}, &output); err != nil {
		t.Fatal(err)
	}
	if after, _ := fileDigest(snapshot); after != digest {
		t.Fatal("preflight rewrote the permanent backup")
	}
	if err = operationCommand(backupArgs, &output); err == nil {
		t.Fatal("backup overwrote an existing snapshot")
	}
	if err = withOfflineDatabase(s.Store.path, func(db *sql.DB) error { _, e := db.Exec("UPDATE providers SET name='failed candidate'"); return e }); err != nil {
		t.Fatal(err)
	}
	if err = operationCommand([]string{"ops", "restore", "--env-file", env, "--snapshot", snapshot}, &output); err != nil {
		t.Fatal(err)
	}
	restored, err := OpenStore(s.Store.path)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	if err = verifyDatabase(context.Background(), restored.DB, s.Key); err != nil {
		t.Fatal(err)
	}
	var kept, encrypted string
	if err = restored.DB.QueryRow("SELECT content FROM messages WHERE id='kept'").Scan(&kept); err != nil || kept != content {
		t.Fatal("full original Unicode context lost", err)
	}
	restored.DB.QueryRow("SELECT facts_json FROM observations WHERE id='original-price'").Scan(&kept)
	if kept != `{"price":{"input":"0.000000000123456789"}}` {
		t.Fatal("precise price history changed")
	}
	restored.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&kept)
	if kept != "P" {
		t.Fatal("rollback did not restore original row")
	}
	restored.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted)
	if plain, e := s.decrypt(encrypted); e != nil || plain != "rotated-provider-secret" {
		t.Fatal("restored vault not usable")
	}
	if strings.Contains(output.String(), "secret") || strings.Contains(output.String(), "private-header") || strings.Contains(output.String(), base64.StdEncoding.EncodeToString(s.Key)) {
		t.Fatal("operation output leaked secrets")
	}
	failed, e := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), ".restore-*", "previous.sqlite"))
	if e != nil || len(failed) != 1 {
		t.Fatal("failed candidate database was deleted")
	}
}

func TestOperationsWrongMasterKeyCorruptionAndEnvironmentAreSafe(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	env := operationsEnvironment(t, s)
	if err := s.Store.Close(); err != nil {
		t.Fatal(err)
	}
	before, _ := fileDigest(s.Store.path)
	raw, err := os.ReadFile(env)
	if err != nil {
		t.Fatal(err)
	}
	wrong := bytes.Replace(raw, []byte(base64.StdEncoding.EncodeToString(s.Key)), []byte(base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{9}, 32))), 1)
	if err = os.WriteFile(env, wrong, 0600); err != nil {
		t.Fatal(err)
	}
	err = operationCommand([]string{"ops", "backup", "--env-file", env, "--output", filepath.Join(t.TempDir(), "not-created.sqlite")}, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "vault verification failed") {
		t.Fatal("wrong master key was accepted", err)
	}
	after, _ := fileDigest(s.Store.path)
	if before != after {
		t.Fatal("failed preflight changed the original database")
	}
	if err = os.WriteFile(env, raw, 0600); err != nil {
		t.Fatal(err)
	}
	snapshot := filepath.Join(t.TempDir(), "backup.sqlite")
	if err = operationCommand([]string{"ops", "backup", "--env-file", env, "--output", snapshot}, io.Discard); err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(snapshot, os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteAt([]byte("changed"), 120)
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	if err = operationCommand([]string{"ops", "restore", "--env-file", env, "--snapshot", snapshot}, io.Discard); err == nil {
		t.Fatal("tampered snapshot accepted")
	}
	if err = os.Chmod(env, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err = readPrivateEnvironment(env); err == nil {
		t.Fatal("public environment file accepted")
	}
	generated := filepath.Join(t.TempDir(), "new.env")
	if err = initializeEnvironment(generated, s.Store.path, filepath.Join(t.TempDir(), "gate")); err != nil {
		t.Fatal(err)
	}
	first, _ := os.ReadFile(generated)
	if err = initializeEnvironment(generated, s.Store.path, filepath.Join(t.TempDir(), "gate")); err == nil {
		t.Fatal("existing keys replaced")
	}
	second, _ := os.ReadFile(generated)
	if !bytes.Equal(first, second) {
		t.Fatal("environment changed on rejected initialization")
	}
	marker := filepath.Join(t.TempDir(), "not-executed")
	if err = os.WriteFile(generated, []byte("EXAMPLE='$(touch "+marker+")'\n"), 0600); err != nil {
		t.Fatal(err)
	}
	values, err := readPrivateEnvironment(generated)
	if err != nil || !strings.HasPrefix(values["EXAMPLE"], "$(touch ") {
		t.Fatal("literal systemd value was expanded", err)
	}
	if _, err = os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("environment file executed shell commands")
	}
}

func TestAutomaticMigrationSnapshotAndFutureSchemaGuard(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	// Reconstruct v17, before any workspace tables/triggers existed.
	rows, err := s.Store.DB.Query("SELECT type,name FROM sqlite_master WHERE name LIKE 'workspace_%' AND type IN ('trigger','table') ORDER BY CASE type WHEN 'trigger' THEN 0 ELSE 1 END")
	if err != nil {
		t.Fatal(err)
	}
	var drops []string
	for rows.Next() {
		var kind, name string
		if err = rows.Scan(&kind, &name); err != nil {
			t.Fatal(err)
		}
		drops = append(drops, "DROP "+strings.ToUpper(kind)+" "+name)
	}
	rows.Close()
	for _, q := range drops {
		if _, err = s.Store.DB.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	dropScheduleSchemaFixture(t, s.Store.DB)
	if _, err = s.Store.DB.Exec("PRAGMA user_version=17"); err != nil {
		t.Fatal(err)
	}
	s.Store.Close()
	beforeWrongKey, _ := fileDigest(s.Store.path)
	if unexpected, e := openStore(s.Store.path, true, bytes.Repeat([]byte{12}, 32)); e == nil {
		unexpected.Close()
		t.Fatal("startup accepted a different master key")
	}
	if after, _ := fileDigest(s.Store.path); after != beforeWrongKey {
		t.Fatal("wrong-key startup migrated the original database")
	}
	backupDir := filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups")
	if err = os.WriteFile(backupDir, []byte("blocked backup directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if unexpected, e := OpenStore(s.Store.path); e == nil {
		unexpected.Close()
		t.Fatal("migration continued without a durable backup")
	}
	if after, _ := fileDigest(s.Store.path); after != beforeWrongKey {
		t.Fatal("failed snapshot changed the original schema / data")
	}
	if err = os.Remove(backupDir); err != nil {
		t.Fatal(err)
	}
	migrated, err := OpenStore(s.Store.path)
	if err != nil {
		t.Fatal(err)
	}
	if err = verifyDatabase(context.Background(), migrated.DB, s.Key); err != nil {
		t.Fatal(err)
	}
	migrated.Close()
	backups, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", "*.sqlite"))
	if err != nil || len(backups) != 1 {
		t.Fatal("missing unique pre-migration snapshot", err)
	}
	if err = verifySnapshot(backups[0]); err != nil {
		t.Fatal(err)
	}
	if err = withOfflineDatabase(backups[0], func(db *sql.DB) error {
		var v int
		db.QueryRow("PRAGMA user_version").Scan(&v)
		if v != 17 {
			t.Fatal("backup was made after migration")
		}
		return verifyDatabase(context.Background(), db, s.Key)
	}); err != nil {
		t.Fatal(err)
	}
	if err = withOfflineDatabase(s.Store.path, func(db *sql.DB) error { _, e := db.Exec("PRAGMA user_version=999"); return e }); err != nil {
		t.Fatal(err)
	}
	before, _ := fileDigest(s.Store.path)
	if unexpected, e := OpenStore(s.Store.path); e == nil {
		unexpected.Close()
		t.Fatal("old binary opened a future database")
	}
	after, _ := fileDigest(s.Store.path)
	if before != after {
		t.Fatal("rejected future database was changed")
	}
}

func TestMaintenanceGateBlocksWritesSyncAndBackground(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Write([]byte(`{"data":[{"id":"m"}]}`)) }))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	s.maintenanceFile = filepath.Join(t.TempDir(), "gate")
	if err := os.WriteFile(s.maintenanceFile, nil, 0600); err != nil {
		t.Fatal(err)
	}
	for _, target := range []struct{ method, path string }{{"GET", "/"}, {"GET", "/api/v1/providers"}, {"POST", "/api/v1/providers"}, {"POST", "/api/workspace/pair"}, {"POST", "/api/workspace/exchange"}} {
		r := req(t, s, target.method, target.path, map[string]any{})
		requireCode(t, r, 503)
		if r.Header().Get("Retry-After") == "" {
			t.Fatal("missing retry guidance")
		}
	}
	requireCode(t, req(t, s, "GET", "/healthz", nil), 200)
	state := req(t, s, "GET", "/api/v1/system", nil)
	requireCode(t, state, 200)
	var status systemState
	if err := json.Unmarshal(state.Body.Bytes(), &status); err != nil || !status.Maintenance {
		t.Fatal("maintenance status not exposed", err)
	}
	anonymous := httptest.NewRecorder()
	s.Handler().ServeHTTP(anonymous, httptest.NewRequest("GET", "/api/v1/system", nil))
	requireCode(t, anonymous, 401)
	s.workerTick(context.Background())
	var counts int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history WHERE status='PENDING'").Scan(&counts)
	if counts != 1 || calls.Load() != 0 {
		t.Fatal("maintenance ran background work")
	}
	if err := os.Remove(s.maintenanceFile); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/providers", nil), 200)
	s.workerTick(context.Background())
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history WHERE status='PENDING'").Scan(&counts)
	if counts != 0 || calls.Load() == 0 {
		t.Fatal("background caller did not resume after committed update")
	}
	if strings.Contains(state.Body.String(), s.Store.path) || strings.Contains(state.Body.String(), s.Token) {
		t.Fatal("system status leaked private deployment details")
	}
}
