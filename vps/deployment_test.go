package main

import (
	"bytes"
	"context"
	"encoding/base64"
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
	"syscall"
	"testing"
	"time"
)

func TestLocalDeploymentDoesNotInheritPublicPort(t *testing.T) {
	env := map[string]string{"MODELATLAS_ADMIN_TOKEN": "deployment-test-admin-long-token", "PORT": "45000"}
	c, err := readDeploymentConfig(func(k string) string { return env[k] })
	if err != nil || c.addr != "127.0.0.1:8787" || c.db != "./data/modelatlas.db" {
		t.Fatalf("local deployment changed: %v", err)
	}
	env["MODELATLAS_PUBLIC_ORIGIN"] = "https://atlas.example"
	if _, err = readDeploymentConfig(func(k string) string { return env[k] }); err == nil {
		t.Fatal("public origin silently enabled platform mode")
	}
}

// Build and launch the exact commands documented for repository-root deployment.
// Requests pass through a real HTTPS reverse proxy, the normal auth middleware,
// real API mutations, vault, SQLite, SIGTERM, and a second process on the same DB.
func TestPlatformDeploymentProcess(t *testing.T) {
	build := exec.Command("sh", "deploy/build.sh")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("deployment build script: %v\n%s", err, out)
	}
	repo, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	db := filepath.Join(dir, "persistent", "atlas.sqlite")
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	listener.Close()
	backend, _ := url.Parse("http://127.0.0.1:" + port)
	proxy := httputil.NewSingleHostReverseProxy(backend)
	proxy.Director = func(r *http.Request) {
		r.URL.Scheme, r.URL.Host = backend.Scheme, backend.Host
		// The platform must overwrite client-supplied forwarding headers.
		r.Header.Del("Forwarded")
		r.Header.Del("X-Forwarded-Host")
		r.Header.Set("X-Forwarded-Proto", "https")
	}
	ingress := httptest.NewTLSServer(proxy)
	defer ingress.Close()
	publicURL, _ := url.Parse(ingress.URL)
	const token = "deployment-test-admin-long-token"
	const secret = "deployment-test-provider-secret"
	key := bytes.Repeat([]byte{7}, 32)
	env := map[string]string{
		"MODELATLAS_DEPLOYMENT": "platform", "MODELATLAS_PUBLIC_ORIGIN": ingress.URL,
		"MODELATLAS_ADMIN_TOKEN": token, "MODELATLAS_MASTER_KEY": base64.StdEncoding.EncodeToString(key),
		"MODELATLAS_DB": db, "PORT": port,
	}
	cleanEnv := func(values map[string]string) []string {
		var out []string
		for _, value := range os.Environ() {
			name, _, _ := strings.Cut(value, "=")
			if !strings.HasPrefix(name, "MODELATLAS_") && name != "PORT" {
				out = append(out, value)
			}
		}
		for name, value := range values {
			out = append(out, name+"="+value)
		}
		return out
	}
	start := func() func() {
		logFile, err := os.CreateTemp(dir, "process-*.log")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { logFile.Close() })
		cmd := exec.Command("sh", "vps/deploy/run.sh")
		cmd.Dir, cmd.Env = repo, cleanEnv(env)
		cmd.Stdout, cmd.Stderr = logFile, logFile
		if err = cmd.Start(); err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		stopped := false
		stop := func() {
			if stopped {
				return
			}
			stopped = true
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case err := <-done:
				if err != nil {
					t.Errorf("SIGTERM exit: %v", err)
				}
			case <-time.After(20 * time.Second):
				_ = cmd.Process.Kill()
				<-done
				t.Error("deployment process did not stop")
			}
			logs, err := os.ReadFile(logFile.Name())
			if err != nil || bytes.Contains(logs, []byte(token)) || bytes.Contains(logs, []byte(secret)) || bytes.Contains(logs, []byte(env["MODELATLAS_MASTER_KEY"])) {
				t.Error("process log leaked a secret or was unreadable")
			}
		}
		t.Cleanup(stop)
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			r, err := http.Get(backend.String() + "/healthz")
			if err == nil {
				io.Copy(io.Discard, r.Body)
				r.Body.Close()
				if r.StatusCode == 200 {
					return stop
				}
			}
			select {
			case err := <-done:
				stopped = true
				logs, _ := os.ReadFile(logFile.Name())
				t.Fatalf("deployment exited before readiness: %v\n%s", err, logs)
			default:
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatal("deployment did not become healthy")
		return stop
	}
	request := func(client *http.Client, base, method, path, host string, auth bool, headers map[string]string, body any, want int) []byte {
		t.Helper()
		var data []byte
		if body != nil {
			data, err = json.Marshal(body)
			if err != nil {
				t.Fatal(err)
			}
		}
		r, err := http.NewRequest(method, base+path, bytes.NewReader(data))
		if err != nil {
			t.Fatal(err)
		}
		if host != "" {
			r.Host = host
		}
		if auth {
			r.SetBasicAuth("admin", token)
		}
		if body != nil {
			r.Header.Set("Content-Type", "application/json")
		}
		for name, value := range headers {
			r.Header.Set(name, value)
		}
		response, err := client.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		data, err = io.ReadAll(response.Body)
		if err != nil || response.StatusCode != want {
			t.Fatalf("%s %s: want %d got %d: %s (%v)", method, path, want, response.StatusCode, data, err)
		}
		if want == 426 && response.Header.Get("WWW-Authenticate") != "" {
			t.Fatal("plaintext request got a Basic auth challenge")
		}
		if strings.HasPrefix(base, "https:") && want == 200 && response.Header.Get("Strict-Transport-Security") == "" {
			t.Fatal("HTTPS response missing HSTS")
		}
		return data
	}
	stop := start()
	request(http.DefaultClient, backend.String(), "GET", "/api/v1/status", publicURL.Host, false, nil, nil, 426)
	request(http.DefaultClient, backend.String(), "GET", "/healthz/", publicURL.Host, false, nil, nil, 426)
	request(ingress.Client(), ingress.URL, "GET", "/", "", false, nil, nil, 401)
	request(ingress.Client(), ingress.URL, "GET", "/app.js", "", false, nil, nil, 401)
	request(ingress.Client(), ingress.URL, "GET", "/api/v1/status", "evil.example", true, nil, nil, 421)
	request(ingress.Client(), ingress.URL, "POST", "/api/v1/providers", "", true, map[string]string{"Origin": "http://" + publicURL.Host}, Provider{ID: "bad", Name: "Bad", BaseURL: "https://api.example/v1"}, 403)
	request(ingress.Client(), ingress.URL, "GET", "/api/v1/status", "", true, map[string]string{"X-Forwarded-Proto": "http"}, nil, 200)
	request(http.DefaultClient, backend.String(), "GET", "/api/v1/status", publicURL.Host, true, map[string]string{"X-Forwarded-Proto": "https, http"}, nil, 426)
	request(ingress.Client(), ingress.URL, "POST", "/api/v1/providers", "", true, map[string]string{"Origin": ingress.URL}, Provider{ID: "p", Name: "Persistent", BaseURL: "https://api.example/v1", AuthMode: "BEARER"}, 201)
	request(ingress.Client(), ingress.URL, "POST", "/api/v1/accounts", "", true, nil, Account{ID: "a", ProviderID: "p", Label: "Persistent account"}, 201)
	request(ingress.Client(), ingress.URL, "POST", "/api/v1/credentials", "", true, nil, Credential{ID: "c", ProviderID: "p", AccountID: "a", Secret: secret}, 201)
	stop()
	store, err := OpenStore(db)
	if err != nil {
		t.Fatal(err)
	}
	var encrypted string
	if err = store.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE id='c'").Scan(&encrypted); err != nil {
		t.Fatal(err)
	}
	if value, err := (&Server{Key: key}).decrypt(encrypted); err != nil || value != secret || strings.Contains(encrypted, secret) {
		t.Fatal("persistent vault could not be reopened with the fixed master key")
	}
	store.Close()
	stop = start()
	providers := request(ingress.Client(), ingress.URL, "GET", "/api/v1/providers", "", true, nil, nil, 200)
	if !bytes.Contains(providers, []byte("Persistent")) || bytes.Contains(providers, []byte(`"bad"`)) {
		t.Fatal("restart lost provider or cross-origin mutation reached SQLite")
	}
	credentials := request(ingress.Client(), ingress.URL, "GET", "/api/v1/credentials", "", true, nil, nil, 200)
	if !bytes.Contains(credentials, []byte(`"c"`)) || bytes.Contains(credentials, []byte(secret)) {
		t.Fatal("restart lost credential or exposed plaintext")
	}
	stop()
	t.Run("occupied-port-exits-nonzero", func(t *testing.T) {
		occupied, err := net.Listen("tcp", "0.0.0.0:"+port)
		if err != nil {
			t.Fatal(err)
		}
		defer occupied.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, "sh", "vps/deploy/run.sh")
		cmd.Dir, cmd.Env = repo, cleanEnv(env)
		out, err := cmd.CombinedOutput()
		if err == nil || ctx.Err() != nil || !bytes.Contains(out, []byte("HTTP server stopped:")) {
			t.Fatalf("bind failure not reported as process failure: %v", err)
		}
		// The failed process must release its SQLite writer lock as well.
		reopened, err := OpenStore(db)
		if err != nil {
			t.Fatal(err)
		}
		reopened.Close()
	})
	for name, overrides := range map[string]map[string]string{
		"public-without-opt-in": {"MODELATLAS_DEPLOYMENT": "", "MODELATLAS_PUBLIC_ORIGIN": "", "MODELATLAS_LISTEN": "0.0.0.0:" + port},
		"missing-origin":        {"MODELATLAS_PUBLIC_ORIGIN": ""},
		"plaintext-origin":      {"MODELATLAS_PUBLIC_ORIGIN": "http://atlas.example"},
		"origin-with-secret":    {"MODELATLAS_PUBLIC_ORIGIN": "https://private-value@atlas.example"},
		"origin-with-path":      {"MODELATLAS_PUBLIC_ORIGIN": "https://atlas.example/app"},
		"relative-db":           {"MODELATLAS_DB": "./data/not-persistent.sqlite"},
		"missing-master-key":    {"MODELATLAS_MASTER_KEY": ""},
		"short-admin-token":     {"MODELATLAS_ADMIN_TOKEN": "short"},
		"invalid-port":          {"PORT": "70000"},
		"invalid-mode":          {"MODELATLAS_DEPLOYMENT": "platfrom"},
	} {
		t.Run(name, func(t *testing.T) {
			values := make(map[string]string, len(env))
			for k, v := range env {
				values[k] = v
			}
			for k, v := range overrides {
				values[k] = v
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, "sh", "vps/deploy/run.sh")
			cmd.Dir, cmd.Env = repo, cleanEnv(values)
			out, err := cmd.CombinedOutput()
			if err == nil || ctx.Err() != nil {
				t.Fatal("invalid deployment started successfully")
			}
			if bytes.Contains(out, []byte(token)) || bytes.Contains(out, []byte("private-value")) || bytes.Contains(out, []byte(env["MODELATLAS_MASTER_KEY"])) {
				t.Fatal("configuration error leaked a secret")
			}
		})
	}
}
