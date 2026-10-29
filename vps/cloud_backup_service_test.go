package main

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
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
	for range 61 {
		if _, err := m.db.Exec("INSERT INTO backup_events(job_id,state,at,error_code) VALUES('backup-history-0','FAILED',?,'TRANSFER_FAILED')", now()); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := m.db.Exec("INSERT INTO backup_events(job_id,state,at,error_code) VALUES('backup-history-1','FAILED',?,'TRANSFER_FAILED')", now()); err != nil {
		t.Fatal(err)
	}
	var history struct {
		Job    cloudBackupJob     `json:"job"`
		Events []cloudBackupEvent `json:"events"`
		Next   int64              `json:"next_before"`
	}
	events := req(t, s, "GET", "/api/v1/backup/cloud/events?id=backup-history-0", nil)
	requireCode(t, events, 200)
	if json.Unmarshal(events.Body.Bytes(), &history) != nil || history.Job.ID != "backup-history-0" || len(history.Events) != 25 || history.Next == 0 {
		t.Fatal("exact per-backup event page missing")
	}
	for _, event := range history.Events {
		if event.JobID != history.Job.ID {
			t.Fatal("borrowed another backup's process history")
		}
	}
	older := req(t, s, "GET", "/api/v1/backup/cloud/events?id=backup-history-0&before="+strconv.FormatInt(history.Next, 10), nil)
	requireCode(t, older, 200)
	if json.Unmarshal(older.Body.Bytes(), &history) != nil || len(history.Events) != 25 || history.Events[0].Sequence >= 37 {
		t.Fatal("event history page repeated its boundary")
	}
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud/events?id=backup-missing", nil), 404)
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud/events?id=backup-history-0&before=0", nil), 400)
	if _, err := m.db.Exec("UPDATE backup_events SET state='UNKNOWN' WHERE seq=61"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/backup/cloud/events?id=backup-history-0", nil), 503)
	if _, err := m.db.Exec("UPDATE backup_events SET state='FAILED' WHERE seq=61"); err != nil {
		t.Fatal(err)
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
	sharedDestination := newCloudBackupService(context.Background(), configPath, backup, s.Key)
	defer sharedDestination.Close()
	p, err = sharedDestination.policy(context.Background())
	if err != nil || !p.Enabled || p.BindingValid {
		t.Fatal("a different source borrowed execution authority from a shared host destination", err)
	}
	sharedDestination.tick(context.Background())
	if sharedDestination.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&count) != nil || count != 1 {
		t.Fatal("shared destination automatically backed up a different source")
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

func actualPortableDestination(t *testing.T) (cloudBackupDestination, string, string) {
	t.Helper()
	binary, err := exec.LookPath("rclone")
	if err != nil {
		t.Fatal("opt-in contract requires actual rclone")
	}
	password, err := exec.Command(binary, "obscure", "synthetic-service-crypt-password").Output()
	if err != nil {
		t.Fatal(err)
	}
	_, path, remote := portableDestinationFixture(t, binary, strings.TrimSpace(string(password)))
	cfg, err := readCloudBackupDestination(path)
	if err != nil {
		t.Fatal(err)
	}
	return cfg, path, remote
}

func TestPortableCloudActualScheduleIdempotenceFetchAndCancellation(t *testing.T) {
	if os.Getenv("MODELATLAS_CLOUD_BACKUP_TEST") != "1" {
		t.Skip("opt in to actual rclone crypt, scheduled transfers and cancellation")
	}
	s := testServer(t)
	seed(t, s, "https://never-called.example/v1")
	cfg, path, encrypted := actualPortableDestination(t)
	m := newCloudBackupService(context.Background(), path, s.Store.path, s.Key)
	defer m.Close()
	s.backups = m
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", portablePolicyBody(m, 0, true)), 200)
	s.backgroundTick(context.Background())
	m.wg.Wait()
	var id string
	if m.db.QueryRow("SELECT id FROM backup_jobs").Scan(&id) != nil {
		t.Fatal("real enabled schedule did not create a backup")
	}
	job, _, err := m.job(context.Background(), id)
	if err != nil || job.State != "SUCCESS" || !validBackupDigest(job.SHA256) {
		t.Fatal("actual scheduled crypt transfer did not verify", job, err)
	}
	before, err := os.ReadDir(filepath.Join(cfg.Spool, id))
	if err != nil || len(before) != 3 {
		t.Fatal("explicitly retained local snapshot missing", err)
	}
	ack := req(t, s, "POST", "/api/v1/backup/cloud/run", map[string]any{"id": id, "fingerprint": m.cfg.fingerprint, "retry": false, "confirm": true})
	requireCode(t, ack, 202)
	var recovered cloudBackupJob
	if json.Unmarshal(ack.Body.Bytes(), &recovered) != nil || recovered.Sequence != job.Sequence || recovered.SHA256 != job.SHA256 {
		t.Fatal("lost acknowledgement recovery replaced the original backup")
	}
	var count int
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs").Scan(&count) != nil || count != 1 {
		t.Fatal("idempotent retry created another transfer")
	}
	env := operationsEnvironment(t, s)
	download := filepath.Join(t.TempDir(), "recovery")
	if err = cloudBackupCommand([]string{"fetch", "--env-file", env, "--destination-file", path, "--id", id, "--output", download}, io.Discard); err != nil {
		t.Fatal("formal recovery command failed", err)
	}
	if _, err = verifyCloudBackupArchive(context.Background(), download, id, job.SHA256, s.Key); err != nil {
		t.Fatal(err)
	}
	policy := portablePolicyBody(m, 1, true)
	policy["keep_local"] = false
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/policy", policy), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "newer", Name: "Newer snapshot content", BaseURL: "https://never-called.example/v1", AuthMode: "NONE"}), 201)
	if _, err = m.db.Exec("UPDATE backup_jobs SET updated_at=?", now()-16*60000); err != nil {
		t.Fatal(err)
	}
	s.backgroundTick(context.Background())
	m.wg.Wait()
	if m.db.QueryRow("SELECT COUNT(*) FROM backup_jobs WHERE state='SUCCESS'").Scan(&count) != nil || count != 2 {
		t.Fatal("next due schedule did not create a distinct verified snapshot")
	}
	var newerID string
	if m.db.QueryRow("SELECT id FROM backup_jobs ORDER BY seq DESC LIMIT 1").Scan(&newerID) != nil || newerID == id {
		t.Fatal("new due schedule reused a completed identity")
	}
	if _, err = os.Stat(filepath.Join(cfg.Spool, newerID)); !os.IsNotExist(err) {
		t.Fatal("verified temporary snapshot was retained against the saved policy")
	}
	if err = cloudBackupCommand([]string{"run", "--env-file", env, "--destination-file", path, "--id", newerID}, io.Discard); err != nil {
		t.Fatal("formal idempotent run command could not recover its original verified receipt", err)
	}
	objects := 0
	if err = filepath.WalkDir(encrypted, func(_ string, entry os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if !entry.IsDir() {
			objects++
		}
		return nil
	}); err != nil || objects != 6 {
		t.Fatal("new scheduled backup removed an older encrypted version", objects, err)
	}
	copyPath := filepath.Join(t.TempDir(), "different-source.sqlite")
	if err = onlineBackup(context.Background(), s.Store.path, copyPath, s.Key); err != nil {
		t.Fatal(err)
	}
	otherSource := newCloudBackupService(context.Background(), path, copyPath, s.Key)
	defer otherSource.Close()
	s.backups = otherSource
	state := req(t, s, "GET", "/api/v1/backup/cloud", nil)
	requireCode(t, state, 200)
	var scoped struct {
		LastSuccess int64             `json:"last_success_at"`
		Policy      cloudBackupPolicy `json:"policy"`
		Jobs        []cloudBackupJob  `json:"jobs"`
	}
	if json.Unmarshal(state.Body.Bytes(), &scoped) != nil || scoped.LastSuccess != 0 || scoped.Policy.BindingValid || len(scoped.Jobs) != 2 || scoped.Jobs[0].CurrentSource || scoped.Jobs[1].CurrentSource {
		t.Fatal("another source borrowed the original source's verified success")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/run", map[string]any{"id": id, "fingerprint": otherSource.cfg.fingerprint, "retry": true, "confirm": true}), 409)
	otherSource.tick(context.Background())
	otherSource.wg.Wait()
	s.backups = m
	// A real TLS storage request is held in flight. Cancelling the owning job
	// must kill the actual transfer process, leave its local snapshot intact and
	// never label a successful child exit or partial upload as a verified backup.
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
			http.Error(w, "test storage released", 503)
		}
	}))
	defer storage.Close()
	defer close(release)
	ca := filepath.Join(t.TempDir(), "storage-ca.pem")
	if err = os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: storage.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SSL_CERT_FILE", ca)
	blocked, blockedPath, _ := actualPortableDestination(t)
	old, _ := os.ReadFile(blocked.ConfigFile)
	// Preserve the synthetic crypt password while selecting an explicit fixture
	// backend. No production TLS option is weakened or certificate bypass used.
	parts, err := backupCryptRemote(old, blocked.Remote)
	if err != nil {
		t.Fatal(err)
	}
	content := "[encrypted]\ntype = crypt\nremote = blocked:backups\npassword = " + parts["password"] + "\n[blocked]\ntype = webdav\nurl = " + storage.URL + "\nvendor = other\n"
	if err = os.WriteFile(blocked.ConfigFile, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	blocked, err = readCloudBackupDestination(blockedPath)
	if err != nil {
		t.Fatal(err)
	}
	transfer := newCloudBackupService(context.Background(), blockedPath, s.Store.path, s.Key)
	defer transfer.Close()
	s.backups = transfer
	cancelID := "backup-cancel-" + newID()
	if _, err = transfer.start(cancelID, transfer.cfg.fingerprint, false); err != nil {
		t.Fatal(err)
	}
	select {
	case <-arrived:
	case <-time.After(10 * time.Second):
		t.Fatal("actual TLS storage transfer did not start")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/backup/cloud/cancel", map[string]any{"id": cancelID}), 202)
	done := make(chan struct{})
	go func() { transfer.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("cancelled transfer process did not exit")
	}
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("cancelled process left an active TLS request")
	}
	interrupted, _, err := transfer.job(context.Background(), cancelID)
	if err != nil || interrupted.State != "CANCELLED" || interrupted.SHA256 == "" {
		t.Fatal("cancelled transfer lost snapshot identity or invented success", err)
	}
	if _, err = verifyCloudBackupArchive(context.Background(), filepath.Join(blocked.Spool, cancelID), cancelID, interrupted.SHA256, s.Key); err != nil {
		t.Fatal("cancelled transfer discarded its original complete local snapshot", err)
	}
}
