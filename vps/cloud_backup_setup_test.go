package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPortableManagedCloudBackupConfigurationAndRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_CLOUD_BACKUP_TEST") != "1" {
		t.Skip("requires actual rclone for destination setup and recovery")
	}
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	s.distribution = true
	ctx := context.Background()
	s.managedBackups = newManagedCloudBackup(ctx, s.Store.path, s.Key)
	defer s.managedBackups.Close()
	path := managedBackupPath(s.Store.path)
	const setup = "/api/v1/backup/cloud/destination"
	const status = "/api/v1/backup/cloud"
	read := func() map[string]any {
		response := req(t, s, "GET", status, nil)
		requireCode(t, response, 200)
		var state map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &state); err != nil {
			t.Fatal(err)
		}
		return state
	}
	if state := read(); state["configured"] != false || state["setup_revision"] != "" {
		t.Fatal("missing initial setup")
	}
	r2 := cloudBackupSetup{
		Provider: "r2", Endpoint: "https://0123456789abcdef0123456789abcdef.eu.r2.cloudflarestorage.com",
		Bucket: "atlas-backups", Prefix: "ModelAtlas", AccessKeyID: "fixture-r2-id", SecretAccessKey: "fixture-r2-secret",
	}
	requireCode(t, req(t, s, "POST", setup, r2), 400)
	r2.KeyRecoveryConfirmed = true
	bad := r2
	bad.Endpoint = "https://127.0.0.1"
	requireCode(t, req(t, s, "POST", setup, bad), 400)
	bad = r2
	bad.SecretAccessKey += "\n[encrypted]"
	requireCode(t, req(t, s, "POST", setup, bad), 400)
	if _, err := os.Stat(filepath.Dir(path)); !os.IsNotExist(err) {
		t.Fatal("invalid or missing setup created private backup state")
	}
	requireCode(t, req(t, s, "POST", setup, r2), 200)
	requireCode(t, req(t, s, "POST", setup, r2), 409)
	state := read()
	m := s.managedBackups.service
	if state["configured"] != true || state["destination"] != "Cloudflare R2 · atlas-backups" || state["setup_revision"] == "" {
		t.Fatal("R2 settings did not become available")
	}
	raw, err := privateBackupFile(m.cfg.ConfigFile, 65536)
	if err != nil {
		t.Fatal(err)
	}
	sections, err := backupRemoteSections(raw)
	if err != nil || sections["storage"]["provider"] != "Cloudflare" || sections["storage"]["region"] != "auto" || sections["encrypted"]["remote"] != "storage:atlas-backups" {
		t.Fatal("unexpected R2 configuration")
	}
	for _, value := range []string{r2.AccessKeyID, r2.SecretAccessKey, sections["encrypted"]["password"], m.cfg.ConfigFile} {
		if strings.Contains(req(t, s, "GET", status, nil).Body.String(), value) {
			t.Fatal("backup status exposed private configuration")
		}
	}
	s.workerTick(ctx)
	if jobs, ok := read()["jobs"].([]any); !ok || len(jobs) != 0 {
		t.Fatal("setup unexpectedly started a backup")
	}
	requireCode(t, req(t, s, "POST", status+"/policy", portablePolicyBody(m, 0, true)), 200)
	b2 := r2
	b2.Provider, b2.Endpoint = "b2", "https://s3.us-west-004.backblazeb2.com"
	b2.Revision = state["setup_revision"].(string)
	lease, err := acquireDatabaseLock(filepath.Join(m.cfg.Spool, ".transfer"))
	if err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "POST", setup, b2), 409)
	lease.Close()
	requireCode(t, req(t, s, "POST", setup, b2), 200)
	if _, err = os.Stat(m.cfg.ConfigFile); !os.IsNotExist(err) {
		t.Fatal("superseded managed credentials were retained")
	}
	m = s.managedBackups.service
	raw, err = privateBackupFile(m.cfg.ConfigFile, 65536)
	if err != nil {
		t.Fatal(err)
	}
	sections, err = backupRemoteSections(raw)
	policy, policyErr := m.policy(ctx)
	if err != nil || sections["storage"]["provider"] != "Other" || sections["storage"]["region"] != "us-west-004" || policyErr != nil || policy.BindingValid {
		t.Fatal("B2 configuration or schedule isolation changed")
	}
	s.managedBackups.Close()
	s.managedBackups = newManagedCloudBackup(ctx, s.Store.path, s.Key)
	defer s.managedBackups.Close()
	if read()["destination"] != "Backblaze B2 · atlas-backups" {
		t.Fatal("destination did not survive reopening the service")
	}
	wrongKey := newManagedCloudBackup(ctx, s.Store.path, bytes.Repeat([]byte{9}, 32))
	defer wrongKey.Close()
	if wrongKey.service == nil || wrongKey.service.available() == nil {
		t.Fatal("managed destination accepted an unrelated master key")
	}

	// Use the generated crypt identity with a disposable local storage backend.
	// The recovery side independently derives its crypt key from the old master.
	m = s.managedBackups.service
	remote := t.TempDir()
	writeCryptConfig := func(password string) string {
		t.Helper()
		path := filepath.Join(t.TempDir(), "rclone.conf")
		raw := "[encrypted]\ntype = crypt\nremote = " + remote + "\npassword = " + password + "\n"
		if err := writeExclusive(path, []byte(raw), 0600); err != nil {
			t.Fatal(err)
		}
		return path
	}
	snapshotConfig := m.cfg
	snapshotConfig.ConfigFile = writeCryptConfig(sections["encrypted"]["password"])
	id := "backup-managed-recovery"
	directory := filepath.Join(m.cfg.Spool, id)
	archive, err := createCloudBackupArchive(ctx, directory, id, s.Store.path, s.Key)
	if err != nil || uploadAndVerifyCloudBackup(ctx, snapshotConfig, directory, archive, s.Key) != nil {
		t.Fatal("could not create and verify encrypted backup", err)
	}
	recovery := snapshotConfig
	password, err := obscureManagedBackupKey(ctx, recovery.Executable, s.Key)
	if err != nil {
		t.Fatal(err)
	}
	recovery.ConfigFile = writeCryptConfig(password)
	recoveryDB := filepath.Join(t.TempDir(), "restored.sqlite")
	recoveryPath := managedBackupPath(recoveryDB)
	if err := prepareBackupSpool(filepath.Dir(recoveryPath)); err != nil {
		t.Fatal(err)
	}
	raw, _ = json.Marshal(recovery)
	if err := writeExclusive(recoveryPath, raw, 0600); err != nil {
		t.Fatal(err)
	}
	env := filepath.Join(t.TempDir(), "modelatlas.env")
	environment := "MODELATLAS_DB=" + recoveryDB + "\nMODELATLAS_ADMIN_TOKEN=" + s.Token + "\nMODELATLAS_MASTER_KEY=" + base64.StdEncoding.EncodeToString(s.Key) + "\n"
	if err := writeExclusive(env, []byte(environment), 0600); err != nil {
		t.Fatal(err)
	}
	var result bytes.Buffer
	if err := cloudBackupCommand([]string{"list", "--env-file", env}, &result); err != nil || !strings.Contains(result.String(), id) {
		t.Fatal("independent recovery did not list encrypted backup", err)
	}
	output := filepath.Join(t.TempDir(), "downloaded")
	if err := cloudBackupCommand([]string{"fetch", "--env-file", env, "--id", id, "--output", output}, &result); err != nil {
		t.Fatal("independent recovery could not fetch backup", err)
	}
	if err := restoreSnapshot(ctx, recoveryDB, filepath.Join(output, "atlas.sqlite"), s.Key); err != nil {
		t.Fatal(err)
	}
	db, err := readOnlyBackupDatabase(recoveryDB)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var url string
	if err := db.QueryRow("SELECT base_url FROM providers WHERE id='p'").Scan(&url); err != nil || url != "https://never-called.example/v1" || verifyDatabase(ctx, db, s.Key) != nil {
		t.Fatal("restored database lost provider or usable vault")
	}
}
