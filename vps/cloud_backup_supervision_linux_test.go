//go:build linux

package main

import (
	"context"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestPortableCloudParentCrashStopsOwnedTransferAndRecoversUncertainty(t *testing.T) {
	if os.Getenv("MODELATLAS_CLOUD_BACKUP_TEST") != "1" {
		t.Skip("opt in on Linux for actual process-death and encrypted transfer supervision")
	}
	binary := filepath.Join(t.TempDir(), "modelatlas-vps")
	if out, err := exec.Command("sh", "deploy/build.sh", binary).CombinedOutput(); err != nil {
		t.Fatalf("supervision binary: %v %s", err, out)
	}
	arrived, stopped := make(chan struct{}, 8), make(chan struct{}, 8)
	release := make(chan struct{})
	storage := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case arrived <- struct{}{}:
		default:
		}
		select {
		case <-r.Context().Done():
			select {
			case stopped <- struct{}{}:
			default:
			}
		case <-release:
			http.Error(w, "storage fixture released", 503)
		}
	}))
	defer storage.Close()
	defer close(release)
	ca := filepath.Join(t.TempDir(), "storage-ca.pem")
	if err := os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: storage.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, path, _ := actualPortableDestination(t)
	raw, _ := os.ReadFile(cfg.ConfigFile)
	crypt, err := backupCryptRemote(raw, cfg.Remote)
	if err != nil {
		t.Fatal(err)
	}
	ini := "[encrypted]\ntype = crypt\nremote = blocked:backups\npassword = " + crypt["password"] + "\n[blocked]\ntype = webdav\nurl = " + storage.URL + "\nvendor = other\n"
	if err = os.WriteFile(cfg.ConfigFile, []byte(ini), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, err = readCloudBackupDestination(path)
	if err != nil {
		t.Fatal(err)
	}
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	env := operationsEnvironment(t, s)
	id := "backup-crash-" + newID()
	log, err := os.CreateTemp(t.TempDir(), "supervisor-*.log")
	if err != nil {
		t.Fatal(err)
	}
	defer log.Close()
	cmd := exec.Command(binary, "backup", "run", "--env-file", env, "--destination-file", path, "--id", id)
	cmd.Env = append(os.Environ(), "SSL_CERT_FILE="+ca)
	cmd.Stdout, cmd.Stderr = log, log
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() { _ = cmd.Process.Kill() })
	select {
	case <-arrived:
	case <-time.After(15 * time.Second):
		t.Fatal("owned actual TLS transfer did not start")
	}
	// Linux records children on the particular OS thread that performed fork.
	// Go's exec may use any runtime thread, rather than the thread-group leader.
	// Inspect every thread and still require exactly one actual owned process.
	threads, err := filepath.Glob("/proc/" + strconv.Itoa(cmd.Process.Pid) + "/task/*/children")
	if err != nil || len(threads) == 0 {
		t.Fatal("cannot inspect actual supervisor threads", err)
	}
	children := map[string]bool{}
	for _, path := range threads {
		raw, e := os.ReadFile(path)
		if e != nil && !os.IsNotExist(e) {
			t.Fatal("cannot inspect owned transfer processes", e)
		}
		for _, pid := range strings.Fields(string(raw)) {
			children[pid] = true
		}
	}
	if len(children) != 1 {
		t.Fatal("expected exactly one actual owned transfer process", len(children))
	}
	childID := ""
	for pid := range children {
		childID = pid
	}
	child, err := strconv.Atoi(childID)
	if err != nil {
		t.Fatal(err)
	}
	owned, _ := os.FindProcess(child)
	t.Cleanup(func() { _ = owned.Kill() })
	if err = cmd.Process.Signal(syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-done:
		if err == nil {
			t.Fatal("SIGKILL supervisor unexpectedly returned success")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("supervisor did not terminate")
	}
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("parent crash left an active storage request")
	}
	deadline := time.Now().Add(5 * time.Second)
	dead := false
	for time.Now().Before(deadline) {
		stat, e := os.ReadFile("/proc/" + strconv.Itoa(child) + "/stat")
		if os.IsNotExist(e) || e == nil && strings.Contains(string(stat), ") Z ") {
			dead = true
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !dead {
		t.Fatal("owned transfer continued executing after its parent died")
	}
	m := newCloudBackupService(context.Background(), path, s.Store.path, s.Key)
	defer m.Close()
	job, _, err := m.job(context.Background(), id)
	if err != nil || job.State != "INTERRUPTED" || job.ErrorCode != "PROCESS_INTERRUPTED" || !validBackupDigest(job.SHA256) {
		t.Fatal("crash recovery invented success or lost original snapshot identity", job, err)
	}
	if _, err = verifyCloudBackupArchive(context.Background(), filepath.Join(cfg.Spool, id), id, job.SHA256, s.Key); err != nil {
		t.Fatal("crash removed the original complete private snapshot", err)
	}
}
