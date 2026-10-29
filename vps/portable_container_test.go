//go:build linux

package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Run on native Linux Docker hosts, once per supported release architecture.
// This is an actual image, named volume, Caddy/TLS boundary and rclone transfer;
// no positive response or process is replaced with a test double.
func TestPortableContainerHTTPSVolumeVaultSSEAndCloudRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_CONTAINER_TEST") != "1" {
		t.Skip("opt in on a native Linux Docker host for the complete container lifecycle")
	}
	if _, err := exec.LookPath("docker"); err != nil {
		t.Fatal("container opt-in requires actual Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 7*time.Minute)
	defer cancel()
	docker := func(args ...string) []byte {
		t.Helper()
		cmd := exec.CommandContext(ctx, "docker", args...)
		raw, err := cmd.CombinedOutput()
		if err != nil {
			// Command output can include a copied private file; never echo it.
			t.Fatalf("actual Docker %s failed: %v", args[0], err)
		}
		return raw
	}
	root, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	revision, err := exec.Command("git", "rev-parse", "HEAD").Output()
	if err != nil {
		t.Fatal(err)
	}
	id := "atlas-test-" + newID()
	image, volume, network := id+":local", id+"-data", id+"-net"
	app, proxy := id+"-app", id+"-https"
	cleanup := func(args ...string) { _ = exec.Command("docker", args...).Run() }
	t.Cleanup(func() {
		cleanup("rm", "-f", app, proxy)
		cleanup("volume", "rm", volume)
		cleanup("network", "rm", network)
		cleanup("image", "rm", image)
	})
	build := exec.CommandContext(ctx, "docker", "build", "-f", filepath.Join(root, "vps/deploy/Dockerfile"), "--build-arg", "SOURCE_COMMIT="+strings.TrimSpace(string(revision)), "-t", image, root)
	buildLog := filepath.Join(t.TempDir(), "build.log")
	log, err := os.Create(buildLog)
	if err != nil {
		t.Fatal(err)
	}
	build.Stdout, build.Stderr = log, log
	err = build.Run()
	log.Close()
	if err != nil {
		raw, _ := os.ReadFile(buildLog)
		t.Fatalf("actual container build failed: %v\n%s", err, raw)
	}
	composeBase := []string{"compose", "--project-name", id + "-compose", "--file", filepath.Join(root, "vps/deploy/compose.yaml")}
	compose := func(args ...string) []byte {
		t.Helper()
		cmd := exec.CommandContext(ctx, "docker", append(append([]string{}, composeBase...), args...)...)
		cmd.Env = append(os.Environ(), "MODELATLAS_IMAGE="+image, "MODELATLAS_PUBLIC_ORIGIN=https://atlas.test")
		raw, e := cmd.CombinedOutput()
		if e != nil {
			t.Fatalf("actual Compose %s failed: %v", args[0], e)
		}
		return raw
	}
	t.Cleanup(func() {
		cmd := exec.Command("docker", append(append([]string{}, composeBase...), "down", "--volumes")...)
		cmd.Env = append(os.Environ(), "MODELATLAS_IMAGE="+image, "MODELATLAS_PUBLIC_ORIGIN=https://atlas.test")
		_ = cmd.Run()
	})
	var composition struct {
		Services map[string]struct{ Ports []any }
	}
	if json.Unmarshal(compose("config", "--format", "json"), &composition) != nil || len(composition.Services) != 2 || len(composition.Services["app"].Ports) != 0 {
		t.Fatal("Compose exposes backend HTTP or lost private initialization")
	}
	compose("up", "--no-build", "--wait", "--wait-timeout", "30", "app")
	identity := compose("exec", "-T", "app", "cat", "/data/state/config/modelatlas.env")
	compose("down")
	compose("up", "--no-build", "--wait", "--wait-timeout", "30", "app")
	if !bytes.Equal(identity, compose("exec", "-T", "app", "cat", "/data/state/config/modelatlas.env")) {
		t.Fatal("Compose recreation replaced persistent identity")
	}
	compose("down")
	docker("volume", "create", volume)
	docker("network", "create", network)
	base := []string{"run", "--rm", "--volume", volume + ":/data", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges"}
	cli := func(args ...string) []byte {
		return docker(append(append(append([]string{}, base...), image), args...)...)
	}
	const origin = "https://atlas.test"
	initialize := func() {
		cli("portable", "init", "--directory", "/data/state", "--public-origin", origin, "--listen", "0.0.0.0:8787")
	}
	initialize()
	var binary binaryVersion
	if json.Unmarshal(cli("version"), &binary) != nil || binary.Commit != strings.TrimSpace(string(revision)) || binary.Arch != runtime.GOARCH || binary.OS != "linux" {
		t.Fatal("container source/architecture mismatch")
	}
	environment := cliFile(t, docker, base, image, "/data/state/config/modelatlas.env")
	private := filepath.Join(t.TempDir(), "identity.env")
	if err = os.WriteFile(private, environment, 0600); err != nil {
		t.Fatal(err)
	}
	config, err := serveConfig([]string{"--env-file", private}, func(string) string { return "" })
	if err != nil {
		t.Fatal(err)
	}
	initialize()
	if !bytes.Equal(environment, cliFile(t, docker, base, image, "/data/state/config/modelatlas.env")) {
		t.Fatal("repeated container initialization replaced the original identity")
	}
	certs, pool, pair := portableContainerTLS(t)
	var calls atomic.Int32
	finish := make(chan struct{})
	upstream := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer container-synthetic-provider-secret" {
			t.Error("container inference did not retain the exact endpoint and vault scope")
			http.Error(w, "unexpected test request", 422)
			return
		}
		calls.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"容器串流😀\"}}]}\n\n")
		w.(http.Flusher).Flush()
		select {
		case <-finish:
		case <-r.Context().Done():
			return
		}
		_, _ = io.WriteString(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n")
	}))
	upstream.Listener.Close()
	upstream.Listener, err = net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	upstream.TLS = &tls.Config{Certificates: []tls.Certificate{pair}, MinVersion: tls.VersionTLS12}
	upstream.StartTLS()
	defer upstream.Close()
	_, upstreamPort, _ := net.SplitHostPort(upstream.Listener.Addr().String())
	start := func() {
		docker("run", "-d", "--name", app, "--network", network, "--network-alias", "app", "--add-host", "host.docker.internal:host-gateway", "--volume", volume+":/data", "--mount", "type=bind,src="+certs+",dst=/test-certs,readonly", "--env", "SSL_CERT_FILE=/test-certs/cert.pem", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", image, "portable", "run", "--directory", "/data/state", "--backup-destination", "/data/state/config/backup.json")
		deadline := time.Now().Add(30 * time.Second)
		for time.Now().Before(deadline) {
			if exec.Command("docker", "exec", app, "modelatlas-vps", "ops", "ready", "--env-file", "/data/state/config/modelatlas.env").Run() == nil {
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
		t.Fatal("container failed authenticated readiness")
	}
	start()
	fixture := filepath.Join(t.TempDir(), "Caddyfile")
	caddy := origin + " {\n tls /test-certs/cert.pem /test-certs/key.pem\n import /etc/caddy/modelatlas-proxy.caddy\n}\n"
	if err = os.WriteFile(fixture, []byte(caddy), 0644); err != nil {
		t.Fatal(err)
	}
	docker("run", "-d", "--name", proxy, "--network", network, "--publish", "127.0.0.1::443", "--mount", "type=bind,src="+fixture+",dst=/etc/caddy/Caddyfile,readonly", "--mount", "type=bind,src="+filepath.Join(root, "vps/deploy/modelatlas-proxy.caddy")+",dst=/etc/caddy/modelatlas-proxy.caddy,readonly", "--mount", "type=bind,src="+certs+",dst=/test-certs,readonly", "caddy:2-alpine")
	address := strings.TrimSpace(string(docker("port", proxy, "443/tcp")))
	transport := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}, DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", address)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 20 * time.Second}
	request := func(method, path string, body any, auth bool, headers map[string]string, want int) []byte {
		t.Helper()
		r := portableContainerRequest(t, method, origin+path, body, config.token, auth)
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		response, e := client.Do(r)
		if e != nil {
			t.Fatal("container TLS request failed", e)
		}
		defer response.Body.Close()
		raw, e := io.ReadAll(response.Body)
		if e != nil || response.StatusCode != want {
			t.Fatalf("container %s %s: want %d got %d (%v)", method, path, want, response.StatusCode, e)
		}
		if want == 200 && response.Header.Get("Strict-Transport-Security") == "" {
			t.Fatal("HTTPS proxy lost HSTS")
		}
		return raw
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		r, e := client.Get(origin + "/healthz")
		if e == nil {
			r.Body.Close()
			if r.StatusCode == 200 {
				break
			}
		}
		if time.Now().After(deadline) {
			t.Fatal("actual Caddy HTTPS did not become ready")
		}
		time.Sleep(50 * time.Millisecond)
	}
	request("GET", "/api/v1/status", nil, false, nil, 401)
	request("GET", "/app.js", nil, false, nil, 401)
	request("GET", "/api/v1/status", nil, true, map[string]string{"X-Forwarded-Proto": "http,https"}, 200)
	request("POST", "/api/v1/providers", Provider{ID: "p", Name: "Container persistent", BaseURL: "https://host.docker.internal:" + upstreamPort + "/v1", AuthMode: "BEARER"}, true, map[string]string{"Origin": "https://other.example"}, 403)
	request("POST", "/api/v1/providers", Provider{ID: "p", Name: "Container persistent", BaseURL: "https://host.docker.internal:" + upstreamPort + "/v1", AuthMode: "BEARER"}, true, nil, 201)
	request("POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "p", Label: "Persistent account"}, true, nil, 201)
	request("POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "a", Secret: "container-synthetic-provider-secret"}, true, nil, 201)
	request("POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Container model"}, true, nil, 201)
	selection := Selection{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "m", Endpoint: "CHAT_COMPLETIONS"}
	request("POST", "/api/v1/my-models", selection, true, nil, 201)
	var session Session
	if json.Unmarshal(request("POST", "/api/v1/sessions", Session{Selection: selection, Title: "Complete container history"}, true, nil, 201), &session) != nil || session.ID == "" {
		t.Fatal("container session missing")
	}
	stream, err := client.Do(portableContainerRequest(t, "POST", origin+"/api/v1/infer", map[string]any{"session_id": session.ID, "prompt": "永久歷史😀", "streaming": true}, config.token, true))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(stream.Body)
	var received strings.Builder
	for !strings.Contains(received.String(), "event: delta") {
		line, e := reader.ReadString('\n')
		received.WriteString(line)
		if e != nil {
			t.Fatal("SSE buffered until provider completion", e)
		}
	}
	// The provider is still blocked here. Seeing the delta proves that the real
	// image and Caddy route stream data before a response has completed.
	close(finish)
	tail, err := io.ReadAll(reader)
	stream.Body.Close()
	received.Write(tail)
	if err != nil || !strings.Contains(received.String(), "容器串流😀") || !strings.Contains(received.String(), "event: done") || calls.Load() != 1 {
		t.Fatal("actual container stream incomplete", err)
	}
	before := request("GET", "/api/v1/messages?session_id="+session.ID, nil, true, nil, 200)
	if !bytes.Contains(before, []byte("永久歷史😀")) || !bytes.Contains(before, []byte("容器串流😀")) {
		t.Fatal("container lost complete Unicode history")
	}
	docker("stop", "--time", "30", app)
	inspect := docker("inspect", app)
	if bytes.Contains(inspect, []byte(config.token)) || bytes.Contains(inspect, []byte("container-synthetic-provider-secret")) {
		t.Fatal("container inspection leaked credentials")
	}
	var state []struct {
		State  struct{ ExitCode int }
		Config struct{ User string }
	}
	if json.Unmarshal(inspect, &state) != nil || len(state) != 1 || state[0].State.ExitCode != 0 || state[0].Config.User != "10001:10001" {
		t.Fatal("container was root or did not stop normally")
	}
	docker("rm", app)
	initialize()
	start()
	if after := request("GET", "/api/v1/messages?session_id="+session.ID, nil, true, nil, 200); !bytes.Equal(before, after) {
		t.Fatal("recreated container changed permanent history")
	}
	if !bytes.Equal(environment, cliFile(t, docker, base, image, "/data/state/config/modelatlas.env")) {
		t.Fatal("recreation replaced master key")
	}
	// Configure a real crypt remote inside the same persistent state, with its
	// ciphertext stored separately from the live DB. This validates the generic
	// cloud adapter without claiming a user's real cloud account was contacted.
	password := strings.TrimSpace(string(docker("exec", app, "rclone", "obscure", "container-synthetic-backup-password")))
	remote := "[encrypted]\ntype = crypt\nremote = /data/encrypted-objects\npassword = " + password + "\n"
	rcloneFile := filepath.Join(t.TempDir(), "rclone.conf")
	if err = os.WriteFile(rcloneFile, []byte(remote), 0600); err != nil {
		t.Fatal(err)
	}
	credentials := exec.CommandContext(ctx, "docker", "exec", "-i", app, "sh", "-c", "umask 077; cat > /data/state/config/rclone.conf")
	credentials.Stdin = strings.NewReader(remote)
	if err = credentials.Run(); err != nil {
		t.Fatal("cannot install private fixture credentials as the unprivileged runtime user", err)
	}
	docker("exec", app, "modelatlas-vps", "backup", "configure", "--env-file", "/data/state/config/modelatlas.env", "--destination-file", "/data/state/config/backup.json", "--rclone-config", "/data/state/config/rclone.conf", "--remote", "encrypted", "--spool-directory", "/data/state/backups", "--label", "Container encrypted backup", "--confirm-key-recovery")
	backupID := "backup-" + newID()
	var backup cloudBackupJob
	raw := docker("exec", app, "modelatlas-vps", "backup", "run", "--env-file", "/data/state/config/modelatlas.env", "--destination-file", "/data/state/config/backup.json", "--id", backupID)
	if json.Unmarshal(raw, &backup) != nil || backup.State != "SUCCESS" || !validBackupDigest(backup.SHA256) {
		t.Fatal("container crypt transfer was not readback-verified")
	}
	docker("exec", app, "modelatlas-vps", "backup", "fetch", "--env-file", "/data/state/config/modelatlas.env", "--destination-file", "/data/state/config/backup.json", "--id", backupID, "--output", "/data/recovered")
	docker("stop", "--time", "30", app)
	docker("rm", app)
	cli("ops", "restore", "--env-file", "/data/state/config/modelatlas.env", "--snapshot", "/data/recovered/atlas.sqlite")
	start()
	if after := request("GET", "/api/v1/messages?session_id="+session.ID, nil, true, nil, 200); !bytes.Equal(before, after) {
		t.Fatal("cloud recovery changed complete history")
	}
	status := request("GET", "/api/v1/backup/cloud", nil, true, nil, 200)
	if !bytes.Contains(status, []byte(backupID)) || !bytes.Contains(status, []byte(`"enabled":false`)) {
		t.Fatal("container lost backup history or implicitly enabled schedule")
	}
	logs := docker("logs", app)
	if bytes.Contains(logs, []byte(config.token)) || bytes.Contains(logs, []byte("container-synthetic-provider-secret")) || bytes.Contains(logs, []byte(password)) {
		t.Fatal("container log leaked secrets")
	}
	if output := os.Getenv("MODELATLAS_CONTAINER_REPORT"); output != "" {
		report := map[string]any{"result": "PASS", "architecture": runtime.GOARCH, "source": strings.TrimSpace(string(revision)), "native": true, "nonroot": true, "read_only_root": true, "compose_recreation": true, "backend_not_published": true, "volume_recreation": true, "stable_identity": true, "vault": true, "https": true, "real_caddy": true, "sse_before_completion": true, "inference_calls": calls.Load(), "full_history": true, "actual_crypt_readback": true, "schedule_default_off": true, "image": json.RawMessage(docker("image", "inspect", image)), "proxy_image": json.RawMessage(docker("image", "inspect", "caddy:2-alpine"))}
		data, e := json.MarshalIndent(report, "", "  ")
		if e != nil || os.WriteFile(output, data, 0600) != nil {
			t.Fatal("cannot preserve actual container receipt", e)
		}
	}
}

func cliFile(t *testing.T, docker func(...string) []byte, base []string, image, path string) []byte {
	t.Helper()
	args := append(append([]string{}, base...), "--entrypoint", "cat", image, path)
	return docker(args...)
}

func portableContainerRequest(t *testing.T, method, url string, body any, token string, auth bool) *http.Request {
	t.Helper()
	var data []byte
	if body != nil {
		var err error
		data, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
	}
	r, err := http.NewRequest(method, url, bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	if auth {
		r.SetBasicAuth("admin", token)
	}
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	return r
}

func portableContainerTLS(t *testing.T) (string, *x509.CertPool, tls.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Disposable container test CA"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, DNSNames: []string{"atlas.test", "host.docker.internal"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	private, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pemCert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	pemKey := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: private})
	dir := t.TempDir()
	if os.Chmod(dir, 0755) != nil || os.WriteFile(filepath.Join(dir, "cert.pem"), pemCert, 0644) != nil || os.WriteFile(filepath.Join(dir, "key.pem"), pemKey, 0600) != nil {
		t.Fatal("cannot prepare disposable TLS fixture")
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemCert) {
		t.Fatal("invalid test CA")
	}
	pair, err := tls.X509KeyPair(pemCert, pemKey)
	if err != nil {
		t.Fatal(err)
	}
	return dir, pool, pair
}
