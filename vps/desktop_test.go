package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDesktopDynamicLoopbackAndParentPipeLifetime(t *testing.T) {
	dir := t.TempDir()
	binary := filepath.Join(dir, "engine")
	build := exec.Command("go", "build", "-o", binary, ".")
	build.Env = append(os.Environ(), "CGO_ENABLED=0")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v %s", err, out)
	}
	ready := filepath.Join(dir, "ready.json")
	db := filepath.Join(dir, "atlas.sqlite")
	cmd := exec.Command(binary)
	for _, value := range os.Environ() {
		name, _, _ := strings.Cut(value, "=")
		if !strings.HasPrefix(name, "MODELATLAS_") && name != "PORT" {
			cmd.Env = append(cmd.Env, value)
		}
	}
	cmd.Env = append(cmd.Env, "MODELATLAS_DEPLOYMENT=desktop", "MODELATLAS_DESKTOP_READY="+ready,
		"MODELATLAS_DB="+db, "MODELATLAS_ADMIN_TOKEN=desktop-testing-admin-token-long", "MODELATLAS_MASTER_KEY="+base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{5}, 32)), "PORT=12345")
	pipe, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer cmd.Process.Kill()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	var record struct {
		URL string `json:"url"`
		PID int    `json:"pid"`
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if data, e := os.ReadFile(ready); e == nil && json.Unmarshal(data, &record) == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !strings.HasPrefix(record.URL, "http://127.0.0.1:") || strings.HasSuffix(record.URL, ":0") || strings.HasSuffix(record.URL, ":12345") || record.PID != cmd.Process.Pid {
		t.Fatal("readiness was not the actual owned loopback listener")
	}
	info, err := os.Stat(ready)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("readiness permissions")
	}
	client := &http.Client{Timeout: 5 * time.Second}
	response, err := client.Get(record.URL + "/api/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != 401 {
		t.Fatal("desktop mode bypassed admin auth")
	}
	pipe.Close()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("parent pipe exit: %v", err)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("orphan desktop engine after parent EOF")
	}
	if _, err = os.Stat(ready); !os.IsNotExist(err) {
		t.Fatal("readiness not removed at exit")
	}
	store, err := OpenStore(db)
	if err != nil {
		t.Fatal("writer lock not released: ", err)
	}
	store.Close()
}
