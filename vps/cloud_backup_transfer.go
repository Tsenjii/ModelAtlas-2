package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type cloudBackupArchive struct {
	Format               int           `json:"format"`
	ID                   string        `json:"id"`
	CreatedAt            int64         `json:"created_at"`
	Version              binaryVersion `json:"version"`
	SHA256               string        `json:"sha256"`
	MasterKeyIncluded    bool          `json:"master_key_included"`
	KeyRecoveryConfirmed bool          `json:"key_recovery_confirmed"`
}

func createCloudBackupArchive(ctx context.Context, directory, id, source string, key []byte) (cloudBackupArchive, error) {
	archive := cloudBackupArchive{Format: 1, ID: id, CreatedAt: now(), Version: versionInfo(), KeyRecoveryConfirmed: true}
	if !backupPathSegment(id) {
		return archive, errors.New("invalid backup identifier")
	}
	if err := os.Mkdir(directory, 0700); err != nil {
		return archive, errors.New("backup directory already exists or cannot be created")
	}
	snapshot := filepath.Join(directory, "atlas.sqlite")
	if err := onlineBackup(ctx, source, snapshot, key); err != nil {
		return archive, err
	}
	digest, err := fileDigest(snapshot)
	if err != nil {
		return archive, errors.New("cannot hash the completed backup")
	}
	archive.SHA256 = digest
	raw, _ := json.Marshal(archive)
	return archive, writeExclusive(filepath.Join(directory, "backup.json"), raw, 0600)
}

func verifyCloudBackupArchive(ctx context.Context, directory, id, expectedHash string, key []byte) (cloudBackupArchive, error) {
	var archive cloudBackupArchive
	entries, err := os.ReadDir(directory)
	if err != nil || len(entries) != 3 {
		return archive, errors.New("downloaded backup has an unexpected file set")
	}
	for _, entry := range entries {
		if !oneOf(entry.Name(), "atlas.sqlite", "atlas.sqlite.json", "backup.json") || !entry.Type().IsRegular() {
			return archive, errors.New("downloaded backup contains an unsupported entry")
		}
		if err = os.Chmod(filepath.Join(directory, entry.Name()), 0600); err != nil {
			return archive, errors.New("cannot protect downloaded backup")
		}
	}
	raw, err := privateBackupFile(filepath.Join(directory, "backup.json"), 16384)
	if err != nil || json.Unmarshal(raw, &archive) != nil || archive.Format != 1 || archive.ID != id || archive.CreatedAt <= 0 || archive.MasterKeyIncluded || !archive.KeyRecoveryConfirmed || len(archive.SHA256) != 64 || expectedHash != "" && archive.SHA256 != expectedHash {
		return archive, errors.New("downloaded backup identity or manifest does not match")
	}
	snapshot := filepath.Join(directory, "atlas.sqlite")
	if err = verifySnapshot(snapshot); err != nil {
		return archive, errors.New("downloaded backup failed its snapshot manifest verification")
	}
	actual, err := fileDigest(snapshot)
	if err != nil || actual != archive.SHA256 {
		return archive, errors.New("downloaded backup checksum differs from the completed snapshot")
	}
	db, err := readOnlyBackupDatabase(snapshot)
	if err != nil {
		return archive, err
	}
	defer db.Close()
	if err = verifyDatabase(ctx, db, key); err != nil {
		return archive, err
	}
	return archive, nil
}

// Use an explicit executable and argv, never a shell. Credentials are not
// emitted, and inherited RCLONE_* cannot downgrade the configured crypt remote.
func copyCloudBackup(ctx context.Context, cfg cloudBackupDestination, source, destination string) error {
	cmd := exec.CommandContext(ctx, cfg.Executable,
		"copy", "--config", cfg.ConfigFile, "--immutable", "--transfers", "1", "--checkers", "1",
		"--retries", "1", "--low-level-retries", "1", "--contimeout", "15s", "--timeout", "60s",
		"--", source, destination)
	for _, value := range os.Environ() {
		if !strings.HasPrefix(value, "RCLONE_") && !strings.HasPrefix(value, "MODELATLAS_") {
			cmd.Env = append(cmd.Env, value)
		}
	}
	cmd.Stdout, cmd.Stderr = io.Discard, io.Discard
	cmd.WaitDelay = 3 * time.Second
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return errors.New("backup transfer cancelled or timed out")
		}
		return errors.New("encrypted backup transfer failed; existing local and remote backups were retained")
	}
	return nil
}

func uploadAndVerifyCloudBackup(ctx context.Context, cfg cloudBackupDestination, directory string, archive cloudBackupArchive, key []byte) error {
	if !cfg.KeyRecoveryConfirmed {
		return errors.New("confirm a separately stored master key before cloud backup")
	}
	if _, err := verifyCloudBackupArchive(ctx, directory, archive.ID, archive.SHA256, key); err != nil {
		return err
	}
	remote := cfg.Remote + ":" + cfg.Prefix + "/" + archive.ID
	if err := copyCloudBackup(ctx, cfg, directory, remote); err != nil {
		return err
	}
	verify, err := os.MkdirTemp(cfg.Spool, ".verify-")
	if err != nil {
		return errors.New("cannot prepare private verification directory")
	}
	defer os.RemoveAll(verify)
	if err = copyCloudBackup(ctx, cfg, remote, verify); err != nil {
		return err
	}
	_, err = verifyCloudBackupArchive(ctx, verify, archive.ID, archive.SHA256, key)
	return err
}
