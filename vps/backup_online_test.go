package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestPortableOnlineBackupKeepsLiveWriterAndConsistentCompleteHistory(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://never-called.example/v1")
	session, err := s.createTestSession(context.Background(), v)
	if err != nil {
		t.Fatal(err)
	}
	content := strings.Repeat("完整線上快照😀\n", 10000)
	if _, err = s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('online-kept',?,'user',?,1)", session, content); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Store.DB.Exec("CREATE TABLE backup_atomic_probe(epoch INTEGER NOT NULL, leg TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(epoch,leg))"); err != nil {
		t.Fatal(err)
	}
	env := operationsEnvironment(t, s)
	directory := t.TempDir()
	if err = operationCommand([]string{"ops", "backup", "--env-file", env, "--output", filepath.Join(directory, "offline.sqlite")}, io.Discard); err == nil {
		t.Fatal("existing offline writer-lock requirement was weakened")
	}
	started, stop := make(chan struct{}), make(chan struct{})
	var wg sync.WaitGroup
	var writerErr error
	wg.Go(func() {
		for epoch := 1; epoch <= 512; epoch++ {
			tx, e := s.Store.DB.Begin()
			if e == nil {
				_, e = tx.Exec("INSERT INTO backup_atomic_probe VALUES(?,'credit',7),(?,'debit',-7)", epoch, epoch)
				if e == nil {
					e = tx.Commit()
				} else {
					_ = tx.Rollback()
				}
			}
			if e != nil {
				writerErr = e
				if epoch == 1 {
					close(started)
				}
				return
			}
			if epoch == 1 {
				close(started)
			}
			select {
			case <-stop:
				return
			default:
			}
		}
	})
	<-started
	snapshot := filepath.Join(directory, "online.sqlite")
	err = operationCommand([]string{"ops", "online-backup", "--env-file", env, "--output", snapshot}, io.Discard)
	close(stop)
	wg.Wait()
	if writerErr != nil || err != nil {
		t.Fatal("live write or consistent snapshot failed", writerErr, err)
	}
	if err = verifySnapshot(snapshot); err != nil {
		t.Fatal(err)
	}
	read, err := readOnlyBackupDatabase(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	defer read.Close()
	var sum, count, maximum int
	if read.QueryRow("SELECT COALESCE(SUM(value),0),COUNT(*),COALESCE(MAX(epoch),0) FROM backup_atomic_probe").Scan(&sum, &count, &maximum) != nil || maximum == 0 || count != maximum*2 || sum != 0 {
		t.Fatal("backup mixed committed transaction boundaries", count, maximum, sum)
	}
	var kept string
	if read.QueryRow("SELECT content FROM messages WHERE id='online-kept'").Scan(&kept) != nil || kept != content {
		t.Fatal("online backup truncated original history")
	}
	if err = verifyDatabase(context.Background(), read, s.Key); err != nil {
		t.Fatal(err)
	}
	if _, err = read.Exec("DELETE FROM backup_atomic_probe"); err == nil {
		t.Fatal("verification connection can write the snapshot")
	}
	if _, err = s.Store.DB.Exec("INSERT INTO backup_atomic_probe VALUES(1000000000,'still-live',0)"); err != nil {
		t.Fatal("backup took over the application writer", err)
	}
	for _, path := range []string{snapshot, snapshot + ".json"} {
		info, e := os.Stat(path)
		if e != nil || info.Mode().Perm() != 0600 {
			t.Fatal("backup permissions", e)
		}
	}
	raw, _ := os.ReadFile(snapshot + ".json")
	var manifest databaseSnapshot
	if json.Unmarshal(raw, &manifest) != nil || manifest.Schema != schemaVersion || manifest.SHA256 == "" {
		t.Fatal("actual output schema or digest missing")
	}
}

func TestPortableOnlineBackupRejectsWrongKeysAndNeverOverwritesLastGood(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	good := filepath.Join(t.TempDir(), "good.sqlite")
	if err := onlineBackup(context.Background(), s.Store.path, good, s.Key); err != nil {
		t.Fatal(err)
	}
	before, _ := fileDigest(good)
	if err := onlineBackup(context.Background(), s.Store.path, good, s.Key); err == nil {
		t.Fatal("online backup overwrote an existing snapshot")
	}
	after, _ := fileDigest(good)
	if before != after {
		t.Fatal("last-good bytes changed")
	}
	wrong := filepath.Join(t.TempDir(), "wrong.sqlite")
	if err := onlineBackup(context.Background(), s.Store.path, wrong, bytes.Repeat([]byte{9}, 32)); err == nil {
		t.Fatal("wrong-key snapshot published as usable")
	}
	if _, err := os.Stat(wrong + ".json"); !os.IsNotExist(err) {
		t.Fatal("unusable vault received a valid backup manifest")
	}
	missing := filepath.Join(t.TempDir(), "not-existing.sqlite")
	if _, err := readOnlyBackupDatabase(missing); err == nil {
		t.Fatal("read-only snapshot created an empty source")
	}
	if _, err := os.Stat(missing); !os.IsNotExist(err) {
		t.Fatal("source created by verification")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	cancelled := filepath.Join(t.TempDir(), "cancelled.sqlite")
	if err := onlineBackup(ctx, s.Store.path, cancelled, s.Key); err == nil {
		t.Fatal("cancelled backup reported success")
	}
	if _, err := os.Stat(cancelled + ".json"); !os.IsNotExist(err) {
		t.Fatal("cancelled backup published a success manifest")
	}
	if err := verifyDatabase(context.Background(), s.Store.DB, s.Key); err != nil {
		t.Fatal("failed backup damaged the live source", err)
	}
}
