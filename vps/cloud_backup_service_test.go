package main

import (
	"context"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func portableServiceFixture(t *testing.T) (*Server, *cloudBackupService, string) {
	t.Helper()
	binary, err := exec.LookPath("true")
	if err != nil {
		t.Fatal(err)
	}
	_, path, _ := portableDestinationFixture(t, binary, "synthetic-obscured-value")
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	m := newCloudBackupService(context.Background(), path, s.Store.path, s.Key)
	if err = m.available(); err != nil {
		t.Fatal(err)
	}
	s.backups = m
	t.Cleanup(m.Close)
	return s, m, path
}

func portablePolicyBody(m *cloudBackupService, revision int, enabled bool) map[string]any {
	return map[string]any{"revision": revision, "enabled": enabled, "interval_minutes": 15, "keep_local": true, "fingerprint": m.cfg.fingerprint, "confirm": true}
}

func TestPortableCloudPolicyCASAndZeroExitNeverPretendVerifiedBackup(t *testing.T) {
	s, m, _ := portableServiceFixture(t)
	ctx := context.Background()
	p, err := m.policy(ctx)
	if err != nil || p.Enabled || p.BindingValid || p.Revision != 0 {
		t.Fatal("new destination acquired automatic execution authority", err)
	}
	m.tick(ctx)
	var count int
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&count) != nil || count != 0 {
		t.Fatal("disabled destination started work")
	}
	state := req(t, s, "GET", "/api/v1/backup/cloud", nil)
	requireCode(t, state, 200)
	if strings.Contains(state.Body.String(), m.cfg.ConfigFile) || strings.Contains(state.Body.String(), m.cfg.Spool) || strings.Contains(state.Body.String(), "synthetic-obscured-value") {
		t.Fatal("public backup state exposed private host configuration")
	}
	for _, key := range []string{"enabled", "revision", "interval_minutes", "keep_local", "fingerprint", "confirm"} {
		body := portablePolicyBody(m, 0, true)
		delete(body, key)
		requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", body), 400)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 0, true)), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 0, true)), 409)
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_policy_events").Scan(&count) != nil || count != 1 {
		t.Fatal("stale save rewrote immutable policy history")
	}
	m.tick(ctx)
	m.wg.Wait()
	var id string
	if m.db.QueryRow("SELECT id FROM backup_jobs").Scan(&id) != nil {
		t.Fatal("explicitly enabled schedule did not create durable work")
	}
	job, _, err := m.job(ctx, id)
	if err != nil || job.State != "FAILED" || len(job.SHA256) != 64 || job.SnapshotAt == 0 {
		t.Fatal("a zero-exit process with no downloaded data was accepted as verified", job, err)
	}
	directory := filepath.Join(m.cfg.Spool, id)
	if _, err = verifyCloudBackupArchive(ctx, directory, id, job.SHA256, s.Key); err != nil {
		t.Fatal("failed transfer discarded the completed local snapshot", err)
	}
	if _, err = m.start(id, m.cfg.fingerprint, false); err != nil {
		t.Fatal("lost-ack lookup failed", err)
	}
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&count) != nil || count != 1 {
		t.Fatal("read recovery created a second backup")
	}
	// A scheduled retry reuses the exact valid snapshot, not a new filename or a
	// new snapshot that could hide the original failed attempt.
	if _, err = m.db.Exec("UPDATE backup_jobs SET updated_at=?", now()-16*60000); err != nil {
		t.Fatal(err)
	}
	m.tick(ctx)
	m.wg.Wait()
	retried, _, err := m.job(ctx, id)
	if err != nil || retried.State != "FAILED" || retried.SHA256 != job.SHA256 || retried.SnapshotAt != job.SnapshotAt {
		t.Fatal("scheduled retry changed the original backup identity", err)
	}
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_events WHERE state='FAILED'").Scan(&count) != nil || count != 2 {
		t.Fatal("retry erased the original failure history")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 1, false)), 200)
	if _, err = m.db.Exec("UPDATE backup_jobs SET updated_at=?", now()-16*60000); err != nil {
		t.Fatal(err)
	}
	m.tick(ctx)
	m.wg.Wait()
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_events WHERE state='FAILED'").Scan(&count) != nil || count != 2 {
		t.Fatal("disabled schedule continued retrying")
	}
}

func TestPortableCloudHistoryExactRecoveryAndMalformedReadsKeepAuthorityClosed(t *testing.T) {
	s, m, path := portableServiceFixture(t)
	for i := range 61 {
		_, err := m.db.Exec("INSERT INTO backup_jobs(id,binding,destination,state,created_at,updated_at) VALUES(?,?,?,'FAILED',?,?)", "backup-history-"+strconv.Itoa(i), m.cfg.fingerprint, m.cfg.Label, now(), now())
		if err != nil {
			t.Fatal(err)
		}
	}
	var page struct {
		Jobs      []cloudBackupJob `json:"jobs"`
		Next      int64            `json:"next_before"`
		Recovered *cloudBackupJob  `json:"recovered_job"`
	}
	first := req(t, s, "GET", "/api/v1/backup/cloud?id=backup-history-0", nil)
	requireCode(t, first, 200)
	if json.Unmarshal(first.Body.Bytes(), &page) != nil || len(page.Jobs) != 25 || page.Next == 0 || page.Recovered == nil || page.Recovered.ID != "backup-history-0" || page.Recovered.Sequence != 1 {
		t.Fatal("older exact recovery was replaced by the latest history page")
	}
	before := page.Next
	next := req(t, s, "GET", "/api/v1/backup/cloud?before="+strconv.FormatInt(before, 10), nil)
	requireCode(t, next, 200)
	if json.Unmarshal(next.Body.Bytes(), &page) != nil || len(page.Jobs) != 25 || page.Jobs[0].Sequence >= before {
		t.Fatal("backup history pagination repeated or skipped a boundary")
	}
	for _, bad := range []string{"0", "-1", "not-a-number", "9007199254740992"} {
		requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud?before="+bad, nil), 400)
	}
	if _, err := m.db.Exec("UPDATE backup_policy SET interval_minutes=0"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud?before=40", nil), 503)
	if _, err := m.db.Exec("UPDATE backup_policy SET interval_minutes=15; UPDATE backup_jobs SET state='UNKNOWN' WHERE seq=61"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud", nil), 503)
	if _, err := m.db.Exec("UPDATE backup_jobs SET state='FAILED' WHERE seq=61"); err != nil {
		t.Fatal(err)
	}
	old := m.cfg
	changed := old
	changed.Label = "Explicitly changed host destination"
	raw, _ := json.Marshal(changed)
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud", nil), 503)
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 0, true)), 503)
	if p, err := m.policy(context.Background()); err != nil || p.Enabled {
		t.Fatal("changed host configuration silently granted new authority")
	}
}

func TestPortableCloudStateAndExecutionAuthorityDoNotTravelWithDatabaseBackup(t *testing.T) {
	s, m, _ := portableServiceFixture(t)
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 0, true)), 200)
	// A previous process ended during transfer. Recovery records interruption;
	// it must not relabel the old attempt as a verified success.
	if _, err := m.db.Exec("INSERT INTO backup_jobs(id,binding,destination,state,created_at,updated_at) VALUES('backup-interrupted',?,?,'UPLOADING',?,?)", m.cfg.fingerprint, m.cfg.Label, now(), now()); err != nil {
		t.Fatal(err)
	}
	configPath := m.configPath
	m.Close()
	reopened := newCloudBackupService(context.Background(), configPath, s.Store.path, s.Key)
	defer reopened.Close()
	job, _, err := reopened.job(context.Background(), "backup-interrupted")
	if err != nil || job.State != "INTERRUPTED" || job.ErrorCode != "PROCESS_INTERRUPTED" || job.SHA256 != "" {
		t.Fatal("process interruption lost its original uncertainty", job, err)
	}
	s.backups = reopened
	backup := filepath.Join(t.TempDir(), "app.sqlite")
	if err = onlineBackup(context.Background(), s.Store.path, backup, s.Key); err != nil {
		t.Fatal(err)
	}
	binary, _ := exec.LookPath("true")
	_, newPath, _ := portableDestinationFixture(t, binary, "other-host-synthetic-crypt-password")
	other := newCloudBackupService(context.Background(), newPath, backup, s.Key)
	defer other.Close()
	if err = other.available(); err != nil {
		t.Fatal(err)
	}
	p, err := other.policy(context.Background())
	if err != nil || p.Enabled || p.BindingValid || p.Revision != 0 {
		t.Fatal("database backup transferred host execution authority", err)
	}
	other.tick(context.Background())
	var count int
	if other.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&count) != nil || count != 0 {
		t.Fatal("restored app database automatically started a host backup")
	}
	// The original source's private host policy is still intact.
	if p, err = reopened.policy(context.Background()); err != nil || !p.Enabled || !p.BindingValid {
		t.Fatal("creating a backup altered the original host policy", err)
	}
}

func TestPortableCloudCommandsRequirePrivateIdentityAndExplicitKeyRecovery(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "portable")
	if err := portableCommand([]string{"init", "--directory", directory}, io.Discard); err != nil {
		t.Fatal(err)
	}
	_, env := portableTestConfig(t, directory)
	binary, _ := exec.LookPath("true")
	cfg, _, _ := portableDestinationFixture(t, binary, "synthetic-obscured-value")
	destination := filepath.Join(directory, "config", "backup.json")
	args := []string{"configure", "--env-file", env, "--destination-file", destination, "--rclone-binary", binary, "--rclone-config", cfg.ConfigFile, "--remote", cfg.Remote, "--prefix", cfg.Prefix, "--spool-directory", cfg.Spool, "--label", cfg.Label}
	if err := cloudBackupCommand(args, io.Discard); err == nil {
		t.Fatal("missing key recovery confirmation accepted")
	}
	args = append(args, "--confirm-key-recovery")
	if err := cloudBackupCommand(args, io.Discard); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(destination)
	if err := cloudBackupCommand(args, io.Discard); err == nil {
		t.Fatal("configured destination overwritten")
	}
	after, _ := os.ReadFile(destination)
	if string(before) != string(after) {
		t.Fatal("failed reconfiguration changed a confirmed destination")
	}
}
