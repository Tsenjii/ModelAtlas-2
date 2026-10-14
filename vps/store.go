package main

import (
	"database/sql"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type Store struct {
	DB   *sql.DB
	lock *os.File
}

func OpenStore(path string) (*Store, error) {
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
		lock, err = os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
		if err != nil {
			return nil, err
		}
		if err = unix.Flock(int(lock.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
			lock.Close()
			return nil, errors.New("database already in use")
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
	for _, q := range []string{"PRAGMA foreign_keys=ON", "PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL"} {
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
	success = true
	return &Store{DB: db, lock: lock}, nil
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
