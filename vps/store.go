package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type Store struct {
	DB   *sql.DB
	lock *os.File
	path string
}

func OpenStore(path string) (*Store, error) {
	return openStore(path, true)
}

func openStore(path string, autoSnapshot bool, vaultKey ...[]byte) (*Store, error) {
	if path == "" {
		return nil, errors.New("empty database path")
	}
	if path != ":memory:" {
		if strings.ContainsAny(path, "?\x00") || strings.HasPrefix(path, "file:") {
			return nil, errors.New("database must be a local path")
		}
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
		f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
		if err != nil {
			return nil, err
		}
		f.Close()
		if err = os.Chmod(path, 0600); err != nil {
			return nil, err
		}
	}
	var lock *os.File
	if path != ":memory:" {
		var err error
		lock, err = acquireDatabaseLock(path)
		if err != nil {
			return nil, err
		}
	}
	success := false
	defer func() {
		if !success && lock != nil {
			lock.Close()
		}
	}()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	var previousVersion int
	if err = db.QueryRow("PRAGMA user_version").Scan(&previousVersion); err != nil {
		db.Close()
		return nil, err
	}
	if previousVersion > schemaVersion {
		db.Close()
		return nil, fmt.Errorf("database version %d is newer than this binary", previousVersion)
	}
	if len(vaultKey) > 0 {
		if err = verifyVault(context.Background(), db, vaultKey[0]); err != nil {
			db.Close()
			return nil, err
		}
	}
	if autoSnapshot && path != ":memory:" && previousVersion < schemaVersion {
		var tables int
		if err = db.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").Scan(&tables); err != nil {
			db.Close()
			return nil, err
		}
		if tables > 0 {
			dir := filepath.Join(filepath.Dir(path), "upgrade-backups")
			if err = os.MkdirAll(dir, 0700); err == nil {
				err = syncDirectory(filepath.Dir(dir))
			}
			if err == nil {
				name := fmt.Sprintf("schema-%d-to-%d-%d.sqlite", previousVersion, schemaVersion, time.Now().UTC().UnixNano())
				err = snapshotDatabase(context.Background(), db, filepath.Join(dir, name))
			}
			if err != nil {
				db.Close()
				return nil, fmt.Errorf("pre-migration backup failed: %w", err)
			}
		}
	}
	for _, q := range []string{"PRAGMA foreign_keys=ON", "PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL", "PRAGMA cache_size=-2048", "PRAGMA temp_store=FILE", "PRAGMA mmap_size=0"} {
		if _, err = db.Exec(q); err != nil {
			db.Close()
			return nil, fmt.Errorf("pragma: %w", err)
		}
	}
	if _, err = db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	if err = migrate(db); err != nil {
		db.Close()
		return nil, fmt.Errorf("migration: %w", err)
	}
	if err = backfillEndpointBindings(db); err != nil {
		db.Close()
		return nil, err
	}
	if _, err = db.Exec("UPDATE telemetry SET outcome='INTERRUPTED' WHERE outcome='RUNNING'"); err != nil {
		db.Close()
		return nil, err
	}
	if _, err = db.Exec("UPDATE research_runs SET status='PAUSED' WHERE status IN ('RUNNING','QUEUED'); UPDATE research_tasks SET status='INTERRUPTED' WHERE status='RUNNING'"); err != nil {
		db.Close()
		return nil, err
	}
	if _, err = db.Exec("UPDATE assistant_runs SET status='INTERRUPTED',finished_at=? WHERE status='RUNNING'", now()); err != nil {
		db.Close()
		return nil, err
	}
	if _, err = db.Exec(`UPDATE extraction_comparisons SET status='INTERRUPTED',report_json=json_set(report_json,'$.status','INTERRUPTED','$.http_requests',NULL,'$.reason','Engine interrupted; in-flight HTTP is unknown and never automatically replayed.') WHERE status='RUNNING'; UPDATE extraction_observations SET status='INTERRUPTED',report_json=json_set(report_json,'$.status','INTERRUPTED','$.http_requests',NULL) WHERE status='RUNNING'`); err != nil {
		db.Close()
		return nil, err
	}
	success = true
	return &Store{DB: db, lock: lock, path: path}, nil
}
func (s *Store) Close() error {
	err := s.DB.Close()
	if s.lock != nil {
		s.lock.Close()
	}
	return err
}
func now() int64 { return time.Now().UTC().UnixMilli() }
func validID(v string) bool {
	if len(v) < 1 || len(v) > 256 {
		return false
	}
	for _, r := range v {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || strings.ContainsRune("._-/:", r) {
			continue
		}
		return false
	}
	return true
}
func isConstraint(err error) bool {
	return err != nil && (strings.Contains(err.Error(), "constraint") || strings.Contains(err.Error(), "UNIQUE"))
}
