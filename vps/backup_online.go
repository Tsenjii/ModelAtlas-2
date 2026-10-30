package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

func readOnlyBackupDatabase(path string) (*sql.DB, error) {
	info, err := os.Stat(path)
	if !filepath.IsAbs(path) || err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
		return nil, errors.New("backup requires an existing local database")
	}
	u := url.URL{Scheme: "file", Path: filepath.ToSlash(path)}
	q := url.Values{"mode": {"ro"}, "_pragma": {"busy_timeout(5000)", "cache_size(-1024)", "temp_store(FILE)"}}
	u.RawQuery = q.Encode()
	db, err := sql.Open("sqlite", u.String())
	if err != nil {
		return nil, errors.New("cannot open database for a read-only snapshot")
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

// VACUUM INTO reads one consistent committed SQLite snapshot. It does not open
// an application Store, take over its writer lock, migrate it or start workers.
// The existing offline backup and restore operations retain their writer lock.
func onlineBackup(ctx context.Context, source, output string, key []byte) error {
	if len(key) != 32 || !filepath.IsAbs(output) || filepath.Clean(source) == filepath.Clean(output) {
		return errors.New("online backup requires a separate absolute output and the original master key")
	}
	for _, path := range []string{output, output + ".json"} {
		if _, err := os.Lstat(path); !os.IsNotExist(err) {
			return errors.New("backup output already exists or is inaccessible")
		}
	}
	db, err := readOnlyBackupDatabase(source)
	if err != nil {
		return err
	}
	defer db.Close()
	if err = writeExclusive(output, nil, 0600); err != nil {
		return errors.New("cannot reserve private backup output")
	}
	if _, err = db.ExecContext(ctx, "VACUUM INTO ?", output); err != nil {
		return errors.New("consistent snapshot failed; check storage and retry with a new output")
	}
	// Verify the actual output, including its schema and every vault row. Reading
	// user_version from the live source after the copy could mislabel a concurrent
	// migration. No manifest is published for an incomplete or unusable snapshot.
	snapshot, err := readOnlyBackupDatabase(output)
	if err != nil {
		return err
	}
	defer snapshot.Close()
	if err = verifyDatabase(ctx, snapshot, key); err != nil {
		return err
	}
	var version int
	if snapshot.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version) != nil || version < 1 || version > schemaVersion {
		return errors.New("snapshot schema is not supported by this binary")
	}
	file, err := os.OpenFile(output, os.O_RDWR, 0600)
	if err != nil {
		return errors.New("cannot sync private snapshot")
	}
	err = file.Sync()
	file.Close()
	if err != nil {
		return errors.New("cannot sync private snapshot")
	}
	digest, err := fileDigest(output)
	if err != nil {
		return errors.New("cannot hash snapshot")
	}
	raw, _ := json.Marshal(databaseSnapshot{Format: 1, Schema: version, SHA256: digest, CreatedAt: time.Now().UTC().Format(time.RFC3339)})
	return writeExclusive(output+".json", raw, 0600)
}
