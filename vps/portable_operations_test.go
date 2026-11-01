package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

func portableTestConfig(t *testing.T, directory string) (deploymentConfig, string) {
	t.Helper()
	path := filepath.Join(directory, "config", "modelatlas.env")
	c, err := serveConfig([]string{"--env-file", path}, func(string) string { return "" })
	if err != nil {
		t.Fatal(err)
	}
	return c, path
}

func TestPortableConfigurationOwnsIdentityWithoutExecutingOrInheritingSecrets(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "portable")
	if err := portableCommand([]string{"init", "--directory", directory}, io.Discard); err != nil {
		t.Fatal(err)
	}
	want, path := portableTestConfig(t, directory)
	got, err := serveConfig([]string{"--env-file", path}, func(string) string { return "inherited-other-service-secret" })
	if err != nil || got.token != want.token || !bytes.Equal(got.key, want.key) || got.db != want.db || got.addr != "127.0.0.1:8787" || got.publicOrigin != "" {
		t.Fatal("private identity or origin borrowed from another service", err)
	}
	raw, _ := os.ReadFile(path)
	marker := filepath.Join(t.TempDir(), "must-not-exist")
	literal := "$(touch " + marker + ")-literal-admin-secret"
	values, _ := readPrivateEnvironment(path)
	modified := bytes.Replace(raw, []byte(values["MODELATLAS_ADMIN_TOKEN"]), []byte(literal), 1)
	if err = os.WriteFile(path, modified, 0600); err != nil {
		t.Fatal(err)
	}
	got, err = serveConfig([]string{"--env-file", path}, func(string) string { return "" })
	if err != nil || got.token != literal {
		t.Fatal("literal private value was changed", err)
	}
	if _, e := os.Stat(marker); !os.IsNotExist(e) {
		t.Fatal("private configuration executed shell syntax")
	}
	if _, err = serveConfig([]string{"--admin-token", literal}, func(string) string { return "" }); err == nil || strings.Contains(err.Error(), literal) {
		t.Fatal("invalid flags accepted or echoed a secret")
	}
	if err = os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err = serveConfig([]string{"--env-file", path}, func(string) string { return "" }); err == nil {
		t.Fatal("shared secret file accepted")
	}
}

func TestPortableInitializationPreservesKeysAndRefusesLostOrSharedState(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "state with spaces")
	if err := portableCommand([]string{"init", "--directory", directory, "--public-origin", "https://atlas.example"}, io.Discard); err != nil {
		t.Fatal(err)
	}
	c, env := portableTestConfig(t, directory)
	before, _ := os.ReadFile(env)
	if c.publicOrigin != "https://atlas.example" || c.addr != "127.0.0.1:8787" {
		t.Fatal("portable ingress lost its safe listener")
	}
	if err := portableCommand([]string{"init", "--directory", directory}, io.Discard); err != nil {
		t.Fatal(err)
	}
	after, _ := os.ReadFile(env)
	if !bytes.Equal(before, after) {
		t.Fatal("second initialization replaced credentials")
	}
	for _, path := range []string{directory, filepath.Join(directory, "data"), filepath.Join(directory, "config"), filepath.Join(directory, "backups")} {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != 0700 {
			t.Fatal("portable directory permissions", err)
		}
	}
	if err := os.WriteFile(c.db, []byte("existing-data-must-not-be-replaced"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(env); err != nil {
		t.Fatal(err)
	}
	if err := portableCommand([]string{"init", "--directory", directory}, io.Discard); err == nil {
		t.Fatal("lost key silently replaced")
	}
	if _, err := os.Stat(env); !os.IsNotExist(err) {
		t.Fatal("replacement identity was created")
	}
	other := filepath.Join(t.TempDir(), "shared")
	if err := os.Mkdir(other, 0755); err != nil {
		t.Fatal(err)
	}
	if err := portableCommand([]string{"init", "--directory", other}, io.Discard); err == nil {
		t.Fatal("shared deployment directory accepted")
	}
}

func TestPortableConcurrentInitializationCannotReplaceConfirmedIdentity(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "concurrent")
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() { _ = portableCommand([]string{"init", "--directory", directory}, io.Discard) })
	}
	wg.Wait()
	_, env := portableTestConfig(t, directory)
	before, _ := os.ReadFile(env)
	if err := portableCommand([]string{"init", "--directory", directory}, io.Discard); err != nil {
		t.Fatal(err)
	}
	after, _ := os.ReadFile(env)
	if !bytes.Equal(before, after) {
		t.Fatal("concurrent initialization changed the confirmed identity")
	}
}

func TestPortableForegroundUsesRealHTTPSAPIAndKeepsVaultAcrossRestart(t *testing.T) {
	binary := filepath.Join(t.TempDir(), "modelatlas-vps")
	if output, err := exec.Command("sh", "deploy/build.sh", binary).CombinedOutput(); err != nil {
		t.Fatalf("portable binary build: %v\n%s", err, output)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	listener.Close()
	backend, _ := url.Parse("http://" + address)
	proxy := httputil.NewSingleHostReverseProxy(backend)
	proxy.Director = func(r *http.Request) {
		r.URL.Scheme, r.URL.Host = backend.Scheme, backend.Host
		r.Header.Set("X-Forwarded-Proto", "https")
	}
	ingress := httptest.NewTLSServer(proxy)
	defer ingress.Close()
	directory := filepath.Join(t.TempDir(), "private state")
	initialize := exec.Command(binary, "portable", "init", "--directory", directory, "--public-origin", ingress.URL, "--listen", address)
	if out, e := initialize.CombinedOutput(); e != nil {
		t.Fatalf("portable init: %v %s", e, out)
	}
	c, env := portableTestConfig(t, directory)
	store, err := openStore(c.db, true, c.key)
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{Store: store, Key: c.key, Token: c.token}
	v := seed(t, s, "https://never-called.example/v1")
	session, err := s.createTestSession(context.Background(), v)
	if err != nil {
		t.Fatal(err)
	}
	content := strings.Repeat("portable 完整歷史😀\n", 2048)
	if _, err = store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('portable-history',?,'user',?,1)", session, content); err != nil {
		t.Fatal(err)
	}
	store.Close()
	const rotated = "portable-live-rotated-key"
	request := func(method, path string, body any, authorized bool) (int, []byte) {
		t.Helper()
		raw, _ := json.Marshal(body)
		r, e := http.NewRequest(method, ingress.URL+path, bytes.NewReader(raw))
		if e != nil {
			t.Fatal(e)
		}
		r.Header.Set("Content-Type", "application/json")
		if authorized {
			r.SetBasicAuth("admin", c.token)
		}
		resp, e := ingress.Client().Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer resp.Body.Close()
		data, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, data
	}
	start := func() func() {
		t.Helper()
		log, e := os.CreateTemp(t.TempDir(), "process-*.log")
		if e != nil {
			t.Fatal(e)
		}
		cmd := exec.Command(binary, "portable", "run", "--directory", directory)
		for _, value := range os.Environ() {
			if !strings.HasPrefix(value, "MODELATLAS_") {
				cmd.Env = append(cmd.Env, value)
			}
		}
		cmd.Env = append(cmd.Env, "MODELATLAS_FACTORY_DEFAULTS=0")
		cmd.Stdout, cmd.Stderr = log, log
		if e = cmd.Start(); e != nil {
			t.Fatal(e)
		}
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		stopped := false
		stop := func() {
			t.Helper()
			if stopped {
				return
			}
			stopped = true
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case e := <-done:
				if e != nil {
					t.Error("portable process did not exit normally", e)
				}
			case <-time.After(20 * time.Second):
				_ = cmd.Process.Kill()
				<-done
				t.Error("portable process did not stop")
			}
			log.Close()
			raw, _ := os.ReadFile(log.Name())
			if bytes.Contains(raw, []byte(c.token)) || bytes.Contains(raw, []byte(rotated)) || bytes.Contains(raw, []byte("top-secret-example-key")) {
				t.Error("process log leaked credentials")
			}
		}
		t.Cleanup(stop)
		deadline := time.Now().Add(15 * time.Second)
		for time.Now().Before(deadline) {
			ready := exec.Command(binary, "ops", "ready", "--env-file", env)
			ready.Stdout, ready.Stderr = io.Discard, io.Discard
			if ready.Run() == nil {
				return stop
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatal("portable authenticated readiness failed")
		return stop
	}
	stop := start()
	if code, _ := request("GET", "/api/v1/providers", nil, false); code != 401 {
		t.Fatal("anonymous public API access", code)
	}
	if code, _ := request("POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": rotated, "confirm": true}, true); code != 200 {
		t.Fatal("real credential mutation failed", code)
	}
	stop()
	stop = start()
	if code, raw := request("GET", "/api/v1/credentials", nil, true); code != 200 || bytes.Contains(raw, []byte(rotated)) {
		t.Fatal("vault metadata or secret boundary lost after restart", code)
	}
	stop()
	restored, err := openStore(c.db, false)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	var kept, cipher string
	if restored.DB.QueryRow("SELECT content FROM messages WHERE id='portable-history'").Scan(&kept) != nil || kept != content {
		t.Fatal("full original history changed")
	}
	if restored.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&cipher) != nil {
		t.Fatal("vault row missing")
	}
	if plain, e := s.decrypt(cipher); e != nil || plain != rotated {
		t.Fatal("same master key cannot decrypt restored vault", e)
	}
	after, _ := os.ReadFile(env)
	if !bytes.Contains(after, []byte(c.token)) {
		t.Fatal("portable restart replaced the original admin identity")
	}
}
