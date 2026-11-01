package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func portableDestinationFixture(t *testing.T, binary, cryptPassword string) (cloudBackupDestination, string, string) {
	t.Helper()
	root := t.TempDir()
	remote := filepath.Join(root, "encrypted-objects")
	if err := os.Mkdir(remote, 0700); err != nil {
		t.Fatal(err)
	}
	credentials := filepath.Join(root, "rclone.conf")
	config := "[encrypted]\ntype = crypt\nremote = " + remote + "\npassword = " + cryptPassword + "\nfilename_encryption = standard\ndirectory_name_encryption = true\n"
	if err := os.WriteFile(credentials, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	cfg := cloudBackupDestination{Format: 1, Label: "Test encrypted destination", Executable: binary, ConfigFile: credentials, Remote: "encrypted", Prefix: "ModelAtlas", Spool: filepath.Join(root, "spool"), KeyRecoveryConfirmed: true}
	raw, _ := json.Marshal(cfg)
	path := filepath.Join(root, "destination.json")
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	return cfg, path, remote
}

func TestPortableCloudDestinationRejectsPlaintextOverridesAndUnsafeScopes(t *testing.T) {
	binary, err := exec.LookPath("true")
	if err != nil {
		t.Fatal(err)
	}
	cfg, path, _ := portableDestinationFixture(t, binary, "synthetic-obscured-value")
	good, err := readCloudBackupDestination(path)
	if err != nil || good.fingerprint == "" {
		t.Fatal("valid private destination unreadable", err)
	}
	before := good.fingerprint
	bad := []struct{ name, content string }{
		{"plaintext-backend", "[encrypted]\ntype = drive\nremote = private\npassword = synthetic-value\n"},
		{"unencrypted-data", "[encrypted]\ntype = crypt\nremote = private\npassword = synthetic-value\nno_data_encryption = true\n"},
		{"unencrypted-filenames", "[encrypted]\ntype = crypt\nremote = private\npassword = synthetic-value\nfilename_encryption = off\n"},
		{"duplicate-type", "[encrypted]\ntype = crypt\ntype = drive\nremote = private\npassword = synthetic-value\n"},
		{"duplicate-remote", "[encrypted]\ntype = crypt\nremote = private\npassword = synthetic-value\n[encrypted]\ntype = drive\n"},
		{"unreadable-encrypted-config", "RCLONE_ENCRYPT_V0:\nsynthetic-encrypted-config\n"},
	}
	for _, tc := range bad {
		t.Run(tc.name, func(t *testing.T) {
			if err := os.WriteFile(cfg.ConfigFile, []byte(tc.content), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := readCloudBackupDestination(path); err == nil || strings.Contains(err.Error(), "synthetic-value") {
				t.Fatal("unsafe destination accepted or private value exposed")
			}
		})
	}
	if err := os.WriteFile(cfg.ConfigFile, []byte("[encrypted]\ntype = crypt\nremote = private\npassword = synthetic-rotated-encryption-identity\n"), 0600); err != nil {
		t.Fatal(err)
	}
	changed, err := readCloudBackupDestination(path)
	if err != nil || changed.fingerprint == before {
		t.Fatal("encryption identity changed without invalidating the binding", err)
	}
	backend := "[encrypted]\ntype = crypt\nremote = cloud:backups\npassword = synthetic-encryption-identity\n[cloud]\ntype = drive\nroot_folder_id = original-folder\ntoken = {\"access_token\":\"expiring-first\",\"refresh_token\":\"original-account\",\"expiry\":\"first\"}\n"
	fingerprint := func(content string) string {
		t.Helper()
		if err := os.WriteFile(cfg.ConfigFile, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		value, err := readCloudBackupDestination(path)
		if err != nil {
			t.Fatal(err)
		}
		return value.fingerprint
	}
	identity := fingerprint(backend)
	if fingerprint(strings.ReplaceAll(strings.ReplaceAll(backend, "expiring-first", "expiring-refreshed"), `"expiry":"first"`, `"expiry":"later"`)) != identity {
		t.Fatal("ordinary OAuth access token refresh disabled the same destination")
	}
	for _, changed := range []string{strings.ReplaceAll(backend, "original-folder", "another-folder"), strings.ReplaceAll(backend, "original-account", "another-account")} {
		if fingerprint(changed) == identity {
			t.Fatal("underlying cloud destination changed without invalidating authority")
		}
	}
	for _, scope := range []string{"../other", "/absolute", "x//y", "remote:folder", "x/../y", "x\ny"} {
		if backupPathSegment(scope) {
			t.Fatal("unsafe backup identifier accepted")
		}
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := readCloudBackupDestination(path); err == nil {
		t.Fatal("shared destination configuration accepted")
	}
}

func TestPortableCloudBackupWithActualCryptAndRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_CLOUD_BACKUP_TEST") != "1" {
		t.Skip("opt in with an installed rclone to exercise actual crypt transfers")
	}
	binary, err := exec.LookPath("rclone")
	if err != nil {
		t.Fatal("opt-in cloud backup test requires actual rclone")
	}
	version, err := exec.Command(binary, "version").Output()
	if err != nil || !bytes.HasPrefix(version, []byte("rclone v")) {
		t.Fatal("actual rclone version unavailable", err)
	}
	t.Log(strings.Split(string(version), "\n")[0])
	password, err := exec.Command(binary, "obscure", "synthetic-test-only-crypt-password").Output()
	if err != nil {
		t.Fatal("cannot prepare synthetic crypt configuration", err)
	}
	_, path, encrypted := portableDestinationFixture(t, binary, strings.TrimSpace(string(password)))
	cfg, err := readCloudBackupDestination(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = prepareBackupSpool(cfg.Spool); err != nil {
		t.Fatal(err)
	}
	s := testServer(t)
	v := seed(t, s, "https://never-called.example/v1")
	session, err := s.createTestSession(context.Background(), v)
	if err != nil {
		t.Fatal(err)
	}
	content := strings.Repeat("雲端真加密完整歷史😀\n", 10000)
	if _, err = s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('cloud-kept',?,'user',?,1)", session, content); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	id := "backup-actual-" + newID()
	directory := filepath.Join(cfg.Spool, id)
	archive, err := createCloudBackupArchive(ctx, directory, id, s.Store.path, s.Key)
	if err != nil {
		t.Fatal(err)
	}
	// An inherited override must not turn the crypt backend into plaintext.
	t.Setenv("RCLONE_CONFIG_ENCRYPTED_TYPE", "local")
	t.Setenv("RCLONE_CRYPT_NO_DATA_ENCRYPTION", "true")
	if err = uploadAndVerifyCloudBackup(ctx, cfg, directory, archive, s.Key); err != nil {
		t.Fatal("real encrypted transfer and readback failed", err)
	}
	var objects []string
	if err = filepath.WalkDir(encrypted, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		objects = append(objects, path)
		if oneOf(entry.Name(), "atlas.sqlite", "atlas.sqlite.json", "backup.json") {
			t.Error("remote filename was not encrypted")
		}
		raw, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		if bytes.Contains(raw, []byte("SQLite format 3")) || bytes.Contains(raw, []byte("雲端真加密完整歷史")) || bytes.Contains(raw, []byte(archive.ID)) {
			t.Error("remote contains unencrypted backup content")
		}
		return nil
	}); err != nil || len(objects) != 3 {
		t.Fatal("unexpected encrypted object set", len(objects), err)
	}
	download := filepath.Join(t.TempDir(), "downloaded")
	if err = os.Mkdir(download, 0700); err != nil {
		t.Fatal(err)
	}
	remote := cfg.Remote + ":" + cfg.Prefix + "/" + id
	if err = copyCloudBackup(ctx, cfg, remote, download); err != nil {
		t.Fatal(err)
	}
	if _, err = verifyCloudBackupArchive(ctx, download, id, archive.SHA256, bytes.Repeat([]byte{9}, 32)); err == nil {
		t.Fatal("wrong master key falsely verified downloaded vault")
	}
	if _, err = verifyCloudBackupArchive(ctx, download, id, archive.SHA256, s.Key); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "restored.sqlite")
	if err = restoreSnapshot(ctx, target, filepath.Join(download, "atlas.sqlite"), s.Key); err != nil {
		t.Fatal("downloaded backup could not restore to a new deployment", err)
	}
	restored, err := readOnlyBackupDatabase(target)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	var kept string
	if restored.QueryRow("SELECT content FROM messages WHERE id='cloud-kept'").Scan(&kept) != nil || kept != content || verifyDatabase(ctx, restored, s.Key) != nil {
		t.Fatal("downloaded restore lost history or the usable vault")
	}
	bad := cfg
	bad.Remote = "missing-configured-remote"
	if err = uploadAndVerifyCloudBackup(ctx, bad, directory, archive, s.Key); err == nil {
		t.Fatal("actual rclone failure reported success")
	}
	if _, err = verifyCloudBackupArchive(ctx, directory, id, archive.SHA256, s.Key); err != nil {
		t.Fatal("failed transfer destroyed the original local snapshot", err)
	}
	// Corruption must fail readback rather than overwrite or delete old objects.
	f, err := os.OpenFile(objects[0], os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	one := make([]byte, 1)
	_, err = f.ReadAt(one, 40)
	if err == nil || err == io.EOF {
		one[0] ^= 0x40
		_, err = f.WriteAt(one, 40)
	}
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	if err = uploadAndVerifyCloudBackup(ctx, cfg, directory, archive, s.Key); err == nil {
		t.Fatal("corrupt remote object falsely verified")
	}
	for _, path := range objects {
		if _, err = os.Stat(path); err != nil {
			t.Fatal("failed verification deleted an existing remote backup", err)
		}
	}
}
