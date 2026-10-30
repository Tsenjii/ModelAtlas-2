package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
)

func TestBrowserPortableCloudBackupRealWorkflowAndReadRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI with actual rclone")
	}
	parallelBrowserFlow(t)
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	cfg, path, _ := actualPortableDestination(t)
	cfg.Label = "我的加密備份"
	raw, _ := json.Marshal(cfg)
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	m := newCloudBackupService(context.Background(), path, s.Store.path, s.Key)
	defer m.Close()
	if err := m.available(); err != nil {
		t.Fatal(err)
	}
	var access sync.RWMutex
	var policies, runs atomic.Int64
	var authenticatedPolicies, authenticatedRuns atomic.Int64
	production := s.Handler()
	control := s.auth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		access.Lock()
		defer access.Unlock()
		switch r.URL.Path {
		case "/fixture/backup/install":
			s.backups = m
		case "/fixture/backup/tick":
			s.backgroundTick(r.Context())
		case "/fixture/backup/history":
			for i := range 61 {
				if _, err := m.db.Exec("INSERT INTO backup_jobs(id,binding,destination,state,created_at,updated_at,error_code) VALUES(?,?,?,'FAILED',?,?,'TRANSFER_FAILED')", "backup-browser-history-"+strconv.Itoa(i), m.cfg.fingerprint, m.cfg.Label, now()-int64(1000+i), now()-int64(1000+i)); err != nil {
					t.Error(err)
					http.Error(w, "fixture history failed", 500)
					return
				}
			}
		default:
			http.NotFound(w, r)
			return
		}
		jsonOK(w, 200, map[string]any{"ok": true})
	}))
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if len(r.URL.Path) >= len("/fixture/") && r.URL.Path[:len("/fixture/")] == "/fixture/" {
			control.ServeHTTP(w, r)
			return
		}
		access.RLock()
		defer access.RUnlock()
		user, token, basic := r.BasicAuth()
		authorized := basic && user == "admin" && token == s.Token || r.Header.Get("Authorization") == "Bearer "+s.Token
		if r.Method == "POST" && r.URL.Path == "/api/v1/backup/cloud/policy" {
			policies.Add(1)
			if authorized {
				authenticatedPolicies.Add(1)
			}
		}
		if r.Method == "POST" && r.URL.Path == "/api/v1/backup/cloud/run" {
			runs.Add(1)
			if authorized {
				authenticatedRuns.Add(1)
			}
		}
		production.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/cloud-backup.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("actual cloud backup browser: %v\n%s", err, out)
	}
	m.wg.Wait()
	var total, verified, changes int
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&total) != nil || total != 63 || m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs WHERE state='SUCCESS'").Scan(&verified) != nil || verified != 2 {
		t.Fatal("actual transfers, read recovery or permanent failed history changed", total, verified)
	}
	// APIRequestContext may first receive a genuine Basic challenge. Count all
	// authenticated write attempts, including conflicts, independently of those
	// unauthenticated requests; durable event counts still prove exact commits.
	t.Logf("HTTP policy/run attempts %d/%d; authenticated attempts %d/%d", policies.Load(), runs.Load(), authenticatedPolicies.Load(), authenticatedRuns.Load())
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_policy_events").Scan(&changes) != nil || changes != 4 || authenticatedPolicies.Load() != 4 || authenticatedRuns.Load() != 1 {
		t.Fatal("UI duplicated an authenticated write after read failure or lost acknowledgement", changes, authenticatedPolicies.Load(), authenticatedRuns.Load())
	}
	p, err := m.policy(context.Background())
	if err != nil || p.Enabled || p.Revision != 4 || p.IntervalMinutes != 45 {
		t.Fatal("stale draft silently rebased or changed the final policy", p, err)
	}
}
