package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Opt-in on a disposable Linux CI host with a genuine systemd. No fake
// systemctl, mocked deployment controller, reduced original test or live key.
func TestSystemdDeploymentLifecycle(t *testing.T) {
	if os.Getenv("MODELATLAS_SYSTEMD_TEST") != "1" {
		t.Skip("requires the isolated genuine systemd CI step")
	}
	if os.Geteuid() != 0 {
		t.Fatal("systemd acceptance must run as root on a disposable host")
	}
	if out, err := exec.Command("/bin/systemctl", "show-environment").CombinedOutput(); err != nil {
		t.Fatalf("genuine systemd unavailable: %v %s", err, out)
	}
	bundle := os.Getenv("MODELATLAS_SYSTEMD_BUNDLE")
	if bundle == "" {
		t.Fatal("prepared release bundle required")
	}
	// PrivateTmp isolates /tmp and /var/tmp inside the actual hardened unit;
	// keep its installation and TLS trust fixture on a visible private tree.
	root, err := os.MkdirTemp("/opt", "modelatlas-accept-")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chmod(root, 0755); err != nil {
		t.Fatal(err)
	}
	unit := fmt.Sprintf("modelatlas-accept-%d", os.Getpid())
	service := "/etc/systemd/system/" + unit + ".service"
	t.Cleanup(func() {
		exec.Command("/bin/systemctl", "disable", "--now", unit+".service").Run()
		os.Remove(service)
		exec.Command("/bin/systemctl", "daemon-reload").Run()
		os.RemoveAll(root)
	})
	data, env := filepath.Join(root, "var/lib/modelatlas"), filepath.Join(root, "etc/modelatlas/modelatlas.env")
	for _, path := range []string{filepath.Dir(data), filepath.Dir(filepath.Dir(env))} {
		if err = os.MkdirAll(path, 0755); err != nil {
			t.Fatal(err)
		}
	}
	if err = os.MkdirAll(filepath.Dir(env), 0700); err != nil {
		t.Fatal(err)
	}
	db := filepath.Join(data, "atlas.sqlite")
	gate := filepath.Join(data, ".update-pending")
	if err = initializeEnvironment(env, db, gate); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := listener.Addr().String()
	listener.Close()
	const secret = "fixture-provider-credential-for-systemd"
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
			return
		}
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+secret {
			t.Error("installed vault not usable for a real provider request")
			http.Error(w, "auth", 401)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"installed reply\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
	}))
	defer provider.Close()
	ca := filepath.Join(root, "fixture-ca.pem")
	if err = os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: provider.TLS.Certificates[0].Certificate[0]}), 0644); err != nil {
		t.Fatal(err)
	}
	initial, err := os.ReadFile(env)
	if err != nil {
		t.Fatal(err)
	}
	initial = bytes.Replace(initial, []byte("127.0.0.1:8787"), []byte(addr), 1)
	initial = append(initial, []byte("SSL_CERT_FILE="+ca+"\n")...)
	if err = os.WriteFile(env, initial, 0600); err != nil {
		t.Fatal(err)
	}
	values, err := readPrivateEnvironment(env)
	if err != nil {
		t.Fatal(err)
	}
	token := values["MODELATLAS_ADMIN_TOKEN"]
	key, err := base64.StdEncoding.DecodeString(values["MODELATLAS_MASTER_KEY"])
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	cleanOutput := func(out []byte) {
		t.Helper()
		logs.Write(out)
		if bytes.Contains(out, []byte(token)) || bytes.Contains(out, []byte(secret)) || bytes.Contains(out, []byte(values["MODELATLAS_MASTER_KEY"])) {
			t.Fatal("deployment output leaked a secret")
		}
	}
	command := func(release, mode string) *exec.Cmd {
		cmd := exec.Command("sh", filepath.Join(release, "deploy/manage.sh"), mode, release)
		cmd.Env = append(os.Environ(), "MODELATLAS_DEPLOY_ROOT="+root, "MODELATLAS_SERVICE="+unit, "PATH=/usr/sbin:/usr/bin:/sbin:/bin")
		return cmd
	}
	run := func(release, mode string, wantSuccess bool) {
		t.Helper()
		out, e := command(release, mode).CombinedOutput()
		cleanOutput(out)
		if (e == nil) != wantSuccess {
			t.Fatalf("systemd %s success=%t wanted=%t: %s", mode, e == nil, wantSuccess, out)
		}
	}
	run(bundle, "install", true)
	client := &http.Client{Timeout: 15 * time.Second}
	request := func(method, path string, body any, want int) []byte {
		t.Helper()
		var raw []byte
		if body != nil {
			raw, _ = json.Marshal(body)
		}
		r, e := http.NewRequest(method, "http://"+addr+path, bytes.NewReader(raw))
		if e != nil {
			t.Fatal(e)
		}
		r.SetBasicAuth("admin", token)
		if body != nil {
			r.Header.Set("Content-Type", "application/json")
		}
		response, e := client.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer response.Body.Close()
		out, e := io.ReadAll(response.Body)
		if e != nil || response.StatusCode != want {
			t.Fatalf("installed %s %s status=%d want=%d read=%v", method, path, response.StatusCode, want, e)
		}
		return out
	}
	request("POST", "/api/v1/providers", Provider{ID: "p", Name: "Permanent provider", BaseURL: provider.URL + "/v1", AuthMode: "BEARER"}, 201)
	request("POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "p", Label: "Permanent account"}, 201)
	request("POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "a", Secret: secret}, 201)
	request("POST", "/api/v1/models", Model{ProviderID: "p", ID: "m", DisplayName: "Permanent model"}, 201)
	v := Selection{ProviderID: "p", AccountID: "a", CredentialID: "c", ModelID: "m", Endpoint: "CHAT_COMPLETIONS"}
	request("POST", "/api/v1/my-models", v, 201)
	var session Session
	if json.Unmarshal(request("POST", "/api/v1/sessions", Session{Selection: v, Title: "Permanent history"}, 201), &session) != nil {
		t.Fatal("session response")
	}
	if !bytes.Contains(request("POST", "/api/v1/infer", map[string]string{"session_id": session.ID, "prompt": "永久完整對話😀"}, 200), []byte("installed reply")) {
		t.Fatal("installed inference did not run")
	}
	assertHistory := func() {
		t.Helper()
		out := request("GET", "/api/v1/messages?session_id="+session.ID, nil, 200)
		if !bytes.Contains(out, []byte("永久完整對話😀")) || !bytes.Contains(out, []byte("installed reply")) {
			t.Fatal("update lost original conversation")
		}
		if !bytes.Contains(request("GET", "/api/v1/telemetry", nil, 200), []byte("SUCCESS")) {
			t.Fatal("update lost request receipt")
		}
		current, _ := os.ReadFile(env)
		if !bytes.Equal(initial, current) {
			t.Fatal("deployment replaced the existing administrator / master key")
		}
	}
	assertHistory()
	second := filepath.Join(root, "candidate")
	if out, e := exec.Command("cp", "-a", bundle, second).CombinedOutput(); e != nil {
		t.Fatalf("copy release %v %s", e, out)
	}
	var version binaryVersion
	raw, err := os.ReadFile(filepath.Join(second, "VERSION.json"))
	if err != nil || json.Unmarshal(raw, &version) != nil {
		t.Fatal("release manifest")
	}
	built, err := time.Parse(time.RFC3339, version.BuiltAt)
	if err != nil {
		t.Fatal(err)
	}
	version.BuiltAt = built.Add(time.Second).Format(time.RFC3339)
	build := exec.Command("go", "build", "-mod=readonly", "-buildvcs=false", "-trimpath", "-ldflags", "-s -w -X main.buildCommit="+version.Commit+" -X main.buildTime="+version.BuiltAt, "-o", filepath.Join(second, "bin/modelatlas-vps-linux-amd64"), ".")
	build.Env = append(os.Environ(), "CGO_ENABLED=0")
	if out, e := build.CombinedOutput(); e != nil {
		t.Fatalf("second real binary %v %s", e, out)
	}
	raw, _ = json.Marshal(version)
	if err = os.WriteFile(filepath.Join(second, "VERSION.json"), raw, 0644); err != nil {
		t.Fatal(err)
	}
	rehashBundle(t, second)
	run(second, "update", true)
	assertHistory()
	var state systemState
	if json.Unmarshal(request("GET", "/api/v1/system", nil, 200), &state) != nil || state.Version != version || !state.VaultVerified || state.Maintenance {
		t.Fatal("installed version / readiness mismatch")
	}
	// Correctly checksummed but unstartable service: real activation fails and
	// the updater must restore the previous binary, DB, unit and active state.
	bad := filepath.Join(root, "unstartable")
	if out, e := exec.Command("cp", "-a", second, bad).CombinedOutput(); e != nil {
		t.Fatalf("copy bad release %v %s", e, out)
	}
	unitFile := filepath.Join(bad, "deploy/modelatlas-vps.service")
	raw, err = os.ReadFile(unitFile)
	if err != nil {
		t.Fatal(err)
	}
	raw = bytes.Replace(raw, []byte("ExecStart=/opt/modelatlas/modelatlas-vps"), []byte("ExecStart=/opt/modelatlas/does-not-exist"), 1)
	if err = os.WriteFile(unitFile, raw, 0644); err != nil {
		t.Fatal(err)
	}
	rehashBundle(t, bad)
	run(bad, "update", false)
	assertHistory()
	// A SIGKILL bypasses shell traps. Its durable pending journal is recovered
	// by a later invocation instead of trusting an incomplete installation.
	crash := command(bad, "update")
	crashLog, err := os.CreateTemp(root, "interrupted-*.log")
	if err != nil {
		t.Fatal(err)
	}
	defer crashLog.Close()
	crash.Stdout, crash.Stderr = crashLog, crashLog
	if err = crash.Start(); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(20 * time.Second)
	interrupted := false
	for time.Now().Before(deadline) {
		current, e := os.ReadFile(service)
		if e == nil && bytes.Contains(current, []byte("does-not-exist")) {
			crash.Process.Signal(syscall.SIGKILL)
			interrupted = true
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !interrupted {
		crash.Process.Kill()
		crash.Wait()
		t.Fatal("did not reach real activation checkpoint")
	}
	crash.Wait()
	crashedOut, _ := os.ReadFile(crashLog.Name())
	cleanOutput(crashedOut)
	if _, err = os.Stat(filepath.Join(root, "etc/modelatlas/update-pending")); err != nil {
		t.Fatal("interrupted update lost its durable recovery journal")
	}
	run(second, "recover", true)
	assertHistory()
	// Tampering must be rejected before stopping or mutating the running app.
	if err = os.WriteFile(filepath.Join(bad, "UPDATE.md"), []byte("tampered"), 0644); err != nil {
		t.Fatal(err)
	}
	run(bad, "update", false)
	assertHistory()
	if err = exec.Command("/bin/systemctl", "stop", unit+".service").Run(); err != nil {
		t.Fatal(err)
	}
	if err = withOfflineDatabase(db, func(dbStore *sql.DB) error { return verifyDatabase(context.Background(), dbStore, key) }); err != nil {
		t.Fatal("installed / restored vault unusable", err)
	}
	backups, err := filepath.Glob(filepath.Join(root, "var/backups/modelatlas", "upgrade-*", "database.sqlite.json"))
	if err != nil || len(backups) < 3 {
		t.Fatal("per-update full backups missing", err)
	}
	for _, path := range []string{env, db} {
		info, e := os.Stat(path)
		if e != nil || info.Mode().Perm() != 0600 {
			t.Fatal("private file permissions", e)
		}
	}
	t.Log("PASS: genuine systemd install, TLS inference, versioned upgrade, full-history/key preservation, failed activation rollback, SIGKILL recovery, tampered bundle rejection")
}

func rehashBundle(t *testing.T, path string) {
	t.Helper()
	file := filepath.Join(path, "SHA256SUMS.txt")
	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var result strings.Builder
	for _, line := range strings.Split(strings.TrimSpace(string(raw)), "\n") {
		if len(line) < 67 {
			t.Fatal("invalid fixture manifest")
		}
		name := line[66:]
		digest, e := fileDigest(filepath.Join(path, name))
		if e != nil {
			t.Fatal(e)
		}
		fmt.Fprintf(&result, "%s  %s\n", digest, name)
	}
	if err = os.WriteFile(file, []byte(result.String()), 0644); err != nil {
		t.Fatal(err)
	}
}
