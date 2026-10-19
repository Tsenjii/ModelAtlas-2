package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

func publishDesktopReady(path, addr string) error {
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		return errors.New("desktop readiness path already exists or is inaccessible")
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".ready-*")
	if err != nil {
		return errors.New("desktop readiness directory is unavailable")
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(0600); err == nil {
		err = json.NewEncoder(f).Encode(map[string]any{"url": "http://" + addr, "pid": os.Getpid()})
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return errors.New("desktop readiness write failed")
	}
	if os.Rename(f.Name(), path) != nil {
		return errors.New("desktop readiness publish failed")
	}
	return nil
}
