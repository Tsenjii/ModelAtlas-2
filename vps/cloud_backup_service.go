package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type cloudBackupPolicy struct {
	Revision        int64 `json:"revision"`
	Enabled         bool  `json:"enabled"`
	IntervalMinutes int   `json:"interval_minutes"`
	KeepLocal       bool  `json:"keep_local"`
	BindingValid    bool  `json:"binding_valid"`
}

type cloudBackupJob struct {
	Sequence    int64  `json:"sequence"`
	ID          string `json:"id"`
	State       string `json:"state"`
	CreatedAt   int64  `json:"created_at"`
	UpdatedAt   int64  `json:"updated_at"`
	SnapshotAt  int64  `json:"snapshot_at"`
	SHA256      string `json:"sha256"`
	ErrorCode   string `json:"error_code"`
	Destination string `json:"destination"`
}

type cloudBackupService struct {
	configPath, source, problem string
	key                         []byte
	cfg                         cloudBackupDestination
	db                          *sql.DB
	ctx                         context.Context
	close                       context.CancelFunc
	mu                          sync.Mutex
	wg                          sync.WaitGroup
	active                      string
	cancel                      context.CancelFunc
}

const cloudBackupLedgerSchema = `
CREATE TABLE IF NOT EXISTS backup_policy(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,enabled INTEGER NOT NULL,interval_minutes INTEGER NOT NULL,keep_local INTEGER NOT NULL,binding TEXT NOT NULL);
INSERT OR IGNORE INTO backup_policy VALUES(1,0,0,1440,0,'');
CREATE TABLE IF NOT EXISTS backup_jobs(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,binding TEXT NOT NULL,destination TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,snapshot_at INTEGER NOT NULL DEFAULT 0,sha256 TEXT NOT NULL DEFAULT '',error_code TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS backup_policy_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,revision INTEGER NOT NULL,enabled INTEGER NOT NULL,interval_minutes INTEGER NOT NULL,keep_local INTEGER NOT NULL,binding TEXT NOT NULL,at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS backup_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,job_id TEXT NOT NULL,state TEXT NOT NULL,at INTEGER NOT NULL,error_code TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS backup_job_state ON backup_jobs(state,updated_at);
CREATE INDEX IF NOT EXISTS backup_event_job ON backup_events(job_id,seq);
`

func newCloudBackupService(parent context.Context, configPath, source string, key []byte) *cloudBackupService {
	ctx, cancel := context.WithCancel(parent)
	m := &cloudBackupService{configPath: configPath, source: source, key: key, ctx: ctx, close: cancel}
	cfg, err := readCloudBackupDestination(configPath)
	if err != nil {
		m.problem = "主機備份目的地設定無法驗證，尚未傳送資料。"
		return m
	}
	m.cfg = cfg
	relative, err := filepath.Rel(cfg.Spool, source)
	if len(key) != 32 || err != nil || relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		m.problem = "備份需要固定 master key，以及與正式資料庫分開的暫存目錄。"
		return m
	}
	if prepareBackupSpool(cfg.Spool) != nil {
		m.problem = "無法準備私密備份暫存目錄。"
		return m
	}
	path := filepath.Join(cfg.Spool, "backup-state.sqlite")
	if _, err = os.Lstat(path); os.IsNotExist(err) {
		err = writeExclusive(path, nil, 0600)
	}
	info, statErr := os.Lstat(path)
	if err != nil || statErr != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		m.problem = "無法開啟私密備份狀態資料。"
		return m
	}
	db, err := sql.Open("sqlite", path)
	if err == nil {
		db.SetMaxOpenConns(1)
		_, err = db.Exec("PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA cache_size=-256;" + cloudBackupLedgerSchema)
	}
	if err != nil {
		if db != nil {
			db.Close()
		}
		m.problem = "無法讀取備份狀態，保留既有檔案。"
		return m
	}
	m.db = db
	// Recover only when no actual transfer holds the cross-process lease. The
	// application database and its runtime authority are never imported here.
	if lease, e := acquireDatabaseLock(filepath.Join(cfg.Spool, ".transfer")); e == nil {
		_, _ = db.Exec("INSERT INTO backup_events(job_id,state,at,error_code) SELECT id,'INTERRUPTED',?,'PROCESS_INTERRUPTED' FROM backup_jobs WHERE state IN ('PENDING','SNAPSHOT','UPLOADING','VERIFYING')", now())
		_, _ = db.Exec("UPDATE backup_jobs SET state='INTERRUPTED',updated_at=?,error_code='PROCESS_INTERRUPTED' WHERE state IN ('PENDING','SNAPSHOT','UPLOADING','VERIFYING')", now())
		lease.Close()
	}
	return m
}

func (m *cloudBackupService) Close() {
	m.close()
	m.wg.Wait()
	if m.db != nil {
		m.db.Close()
	}
}

func (m *cloudBackupService) available() error {
	if m.db == nil || m.problem != "" {
		return errors.New("backup service configuration unavailable")
	}
	current, err := readCloudBackupDestination(m.configPath)
	if err != nil || current.fingerprint != m.cfg.fingerprint {
		return errors.New("host backup configuration changed; restart to apply and confirm the new destination")
	}
	return nil
}

func (m *cloudBackupService) policy(ctx context.Context) (cloudBackupPolicy, error) {
	var p cloudBackupPolicy
	var binding string
	err := m.db.QueryRowContext(ctx, "SELECT revision,enabled,interval_minutes,keep_local,binding FROM backup_policy WHERE id=1").Scan(&p.Revision, &p.Enabled, &p.IntervalMinutes, &p.KeepLocal, &binding)
	p.BindingValid = binding == m.cfg.fingerprint
	if err != nil || p.Revision < 0 || p.IntervalMinutes < 15 || p.IntervalMinutes > 10080 {
		return p, errors.New("saved backup policy unavailable")
	}
	return p, nil
}

func (m *cloudBackupService) job(ctx context.Context, id string) (cloudBackupJob, string, error) {
	var j cloudBackupJob
	var binding string
	err := m.db.QueryRowContext(ctx, "SELECT seq,id,state,created_at,updated_at,snapshot_at,sha256,error_code,destination,binding FROM backup_jobs WHERE id=?", id).Scan(&j.Sequence, &j.ID, &j.State, &j.CreatedAt, &j.UpdatedAt, &j.SnapshotAt, &j.SHA256, &j.ErrorCode, &j.Destination, &binding)
	return j, binding, err
}

func (m *cloudBackupService) transition(id, state, code, digest string, snapshotAt int64) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	tx, err := m.db.BeginTx(ctx, nil)
	if err != nil {
		return errors.New("cannot save backup progress")
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "UPDATE backup_jobs SET state=?,updated_at=?,error_code=?,sha256=CASE WHEN ?='' THEN sha256 ELSE ? END,snapshot_at=CASE WHEN ?=0 THEN snapshot_at ELSE ? END WHERE id=?", state, now(), code, digest, digest, snapshotAt, snapshotAt, id); err != nil {
		return errors.New("cannot save backup progress")
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO backup_events(job_id,state,at,error_code) VALUES(?,?,?,?)", id, state, now(), code); err != nil {
		return errors.New("cannot save backup history")
	}
	return tx.Commit()
}

func (m *cloudBackupService) start(id, fingerprint string, retry bool) (cloudBackupJob, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := m.available(); err != nil || !m.cfg.KeyRecoveryConfirmed || fingerprint != m.cfg.fingerprint || !strings.HasPrefix(id, "backup-") || !backupPathSegment(id) {
		return cloudBackupJob{}, errors.New("backup destination or separate key recovery is not confirmed")
	}
	if m.ctx.Err() != nil {
		return cloudBackupJob{}, errors.New("backup service is stopping")
	}
	existing, binding, err := m.job(m.ctx, id)
	if err == nil && !retry {
		if binding != fingerprint {
			return existing, errors.New("backup identifier belongs to another destination")
		}
		return existing, nil // a lost acknowledgement never creates a new archive
	}
	if m.active != "" {
		return existing, errors.New("another backup is already running")
	}
	if retry && (err != nil || binding != fingerprint || !oneOf(existing.State, "FAILED", "INTERRUPTED", "CANCELLED")) {
		return existing, errors.New("this backup cannot be retried for the selected destination")
	}
	if !retry && err != sql.ErrNoRows {
		return existing, errors.New("cannot read backup identity")
	}
	lease, err := acquireDatabaseLock(filepath.Join(m.cfg.Spool, ".transfer"))
	if err != nil {
		return existing, errors.New("another backup process is already running")
	}
	if !retry {
		_, err = m.db.ExecContext(m.ctx, "INSERT INTO backup_jobs(id,binding,destination,state,created_at,updated_at) VALUES(?,?,?,'PENDING',?,?)", id, fingerprint, m.cfg.Label, now(), now())
	}
	if err == nil {
		err = m.transition(id, "PENDING", "", "", 0)
	}
	if err != nil {
		lease.Close()
		return existing, errors.New("cannot durably admit backup")
	}
	p, err := m.policy(m.ctx)
	if err != nil {
		lease.Close()
		_ = m.transition(id, "FAILED", "POLICY_UNAVAILABLE", "", 0)
		return existing, err
	}
	ctx, cancel := context.WithTimeout(m.ctx, 10*time.Minute)
	m.active, m.cancel = id, cancel
	m.wg.Go(func() {
		defer lease.Close()
		defer cancel()
		defer func() { m.mu.Lock(); m.active, m.cancel = "", nil; m.mu.Unlock() }()
		m.execute(ctx, id, existing, p.KeepLocal)
	})
	returnJob, _, err := m.job(m.ctx, id)
	return returnJob, err
}

func (m *cloudBackupService) execute(ctx context.Context, id string, existing cloudBackupJob, keepLocal bool) {
	directory := filepath.Join(m.cfg.Spool, id)
	var archive cloudBackupArchive
	var err error
	if existing.SHA256 != "" {
		archive, err = verifyCloudBackupArchive(ctx, directory, id, existing.SHA256, m.key)
	} else {
		if err = m.transition(id, "SNAPSHOT", "", "", 0); err == nil {
			archive, err = createCloudBackupArchive(ctx, directory, id, m.source, m.key)
		}
	}
	if err == nil {
		err = m.transition(id, "UPLOADING", "", archive.SHA256, archive.CreatedAt)
	}
	if err == nil {
		err = transferCloudBackup(ctx, m.cfg, directory, archive, m.key, func() error { return m.transition(id, "VERIFYING", "", "", 0) })
	}
	if err != nil {
		state, code := "FAILED", "SNAPSHOT_OR_TRANSFER_FAILED"
		if ctx.Err() != nil {
			state, code = "CANCELLED", "CANCELLED_OR_TIMED_OUT"
		}
		_ = m.transition(id, state, code, "", 0)
		return // the original snapshot is retained for an explicit safe retry
	}
	if m.transition(id, "SUCCESS", "", "", 0) == nil && !keepLocal {
		// Only the verified transfer's private working copy is temporary. The
		// live DB, durable job/event history and remote versions are never pruned.
		_ = os.RemoveAll(directory)
	}
}

func (m *cloudBackupService) tick(ctx context.Context) {
	if m.available() != nil {
		return
	}
	p, err := m.policy(ctx)
	if err != nil || !p.Enabled || !p.BindingValid {
		return
	}
	var last int64
	if m.db.QueryRowContext(ctx, "SELECT COALESCE(MAX(updated_at),0) FROM backup_jobs").Scan(&last) != nil || last != 0 && now() < last+int64(p.IntervalMinutes)*60000 {
		return
	}
	var retry string
	_ = m.db.QueryRowContext(ctx, "SELECT id FROM backup_jobs WHERE state='FAILED' AND binding=? AND sha256<>'' ORDER BY seq DESC LIMIT 1", m.cfg.fingerprint).Scan(&retry)
	if retry != "" {
		_, _ = m.start(retry, m.cfg.fingerprint, true)
	} else {
		_, _ = m.start("backup-"+newID(), m.cfg.fingerprint, false)
	}
}

func (s *Server) getCloudBackup(w http.ResponseWriter, r *http.Request) {
	m := s.backups
	if m == nil {
		jsonOK(w, 200, map[string]any{"configured": false, "available": false, "reason": "尚未設定主機的加密備份目的地。"})
		return
	}
	if m.available() != nil {
		http.Error(w, "主機備份設定暫時無法驗證，沒有啟用新的傳輸。", 503)
		return
	}
	p, policyErr := m.policy(r.Context())
	var err error
	before := int64(1<<53 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		before, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || before <= 0 || before > 1<<53-1 {
			http.Error(w, "invalid backup history cursor", 400)
			return
		}
	}
	if policyErr != nil {
		http.Error(w, "備份政策暫時無法讀取，未變更設定。", 503)
		return
	}
	rows, err := m.db.QueryContext(r.Context(), "SELECT seq,id,state,created_at,updated_at,snapshot_at,sha256,error_code,destination FROM backup_jobs WHERE seq<? ORDER BY seq DESC LIMIT 26", before)
	if err != nil {
		http.Error(w, "備份歷史暫時無法讀取。", 503)
		return
	}
	jobs := []cloudBackupJob{}
	for rows.Next() {
		var j cloudBackupJob
		if rows.Scan(&j.Sequence, &j.ID, &j.State, &j.CreatedAt, &j.UpdatedAt, &j.SnapshotAt, &j.SHA256, &j.ErrorCode, &j.Destination) != nil || !validCloudBackupJob(j) {
			err = errors.New("unreadable backup history")
			break
		}
		jobs = append(jobs, j)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		http.Error(w, "備份歷史暫時無法讀取。", 503)
		return
	}
	next := int64(0)
	if len(jobs) > 25 {
		jobs = jobs[:25]
		next = jobs[len(jobs)-1].Sequence
	}
	var successful, snapshotAt int64
	if m.db.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(updated_at),0),COALESCE(MAX(snapshot_at),0) FROM backup_jobs WHERE state='SUCCESS'").Scan(&successful, &snapshotAt) != nil {
		http.Error(w, "最後成功備份暫時無法讀取，保留上次結果。", 503)
		return
	}
	var recovered *cloudBackupJob
	if id := r.URL.Query().Get("id"); id != "" {
		job, _, e := m.job(r.Context(), id)
		if e != nil || !validCloudBackupJob(job) {
			http.Error(w, "找不到指定的備份，沒有重新建立或傳送。", 404)
			return
		}
		recovered = &job
	}
	m.mu.Lock()
	active := m.active
	m.mu.Unlock()
	jsonOK(w, 200, map[string]any{"configured": true, "available": true, "destination": m.cfg.Label, "fingerprint": m.cfg.fingerprint, "key_recovery_confirmed": m.cfg.KeyRecoveryConfirmed, "policy": p, "active_id": active, "last_success_at": successful, "last_snapshot_at": snapshotAt, "jobs": jobs, "next_before": next, "recovered_job": recovered})
}

func validCloudBackupJob(j cloudBackupJob) bool {
	return j.Sequence > 0 && j.Sequence < 1<<53 && backupPathSegment(j.ID) && j.CreatedAt > 0 && j.UpdatedAt > 0 && j.SnapshotAt >= 0 && oneOf(j.State, "PENDING", "SNAPSHOT", "UPLOADING", "VERIFYING", "SUCCESS", "FAILED", "INTERRUPTED", "CANCELLED") && (j.SHA256 == "" || len(j.SHA256) == 64) && (j.State != "SUCCESS" || j.SnapshotAt > 0 && len(j.SHA256) == 64)
}

func (s *Server) saveCloudBackupPolicy(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Revision        *int64 `json:"revision"`
		Enabled         *bool  `json:"enabled"`
		IntervalMinutes *int   `json:"interval_minutes"`
		KeepLocal       *bool  `json:"keep_local"`
		Fingerprint     string `json:"fingerprint"`
		Confirm         bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	m := s.backups
	if m == nil || m.available() != nil {
		http.Error(w, "主機尚無可用的加密備份目的地。", 503)
		return
	}
	if input.Revision == nil || *input.Revision < 0 || *input.Revision >= 1<<53-1 || input.Enabled == nil || input.IntervalMinutes == nil || *input.IntervalMinutes < 15 || *input.IntervalMinutes > 10080 || input.KeepLocal == nil || input.Fingerprint != m.cfg.fingerprint || !input.Confirm || *input.Enabled && !m.cfg.KeyRecoveryConfirmed {
		http.Error(w, "請確認完整的備份設定與另行保存的 master key。", 400)
		return
	}
	tx, err := m.db.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "無法保存備份設定。", 500)
		return
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(r.Context(), "UPDATE backup_policy SET revision=revision+1,enabled=?,interval_minutes=?,keep_local=?,binding=? WHERE id=1 AND revision=?", *input.Enabled, *input.IntervalMinutes, *input.KeepLocal, input.Fingerprint, *input.Revision)
	if err != nil {
		http.Error(w, "無法保存備份設定，請先重新讀取狀態。", 500)
		return
	}
	if n, _ := result.RowsAffected(); n != 1 {
		http.Error(w, "備份設定已變更，請重新讀取並確認。", 409)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO backup_policy_events(revision,enabled,interval_minutes,keep_local,binding,at) VALUES(?,?,?,?,?,?)", *input.Revision+1, *input.Enabled, *input.IntervalMinutes, *input.KeepLocal, input.Fingerprint, now()); err != nil || tx.Commit() != nil {
		http.Error(w, "設定保存結果尚待重新讀取，請勿重複提交。", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"revision": *input.Revision + 1})
}

func (s *Server) startCloudBackup(w http.ResponseWriter, r *http.Request) {
	var input struct {
		ID          string `json:"id"`
		Fingerprint string `json:"fingerprint"`
		Retry       bool   `json:"retry"`
		Confirm     bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	if s.backups == nil || !input.Confirm {
		http.Error(w, "請先設定並確認加密備份目的地。", 400)
		return
	}
	job, err := s.backups.start(input.ID, input.Fingerprint, input.Retry)
	if err != nil {
		http.Error(w, "備份尚未核准或已有工作執行；請重新讀取狀態，保留原備份識別碼。", 409)
		return
	}
	jsonOK(w, 202, job)
}

func (s *Server) cancelCloudBackup(w http.ResponseWriter, r *http.Request) {
	var input struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	m := s.backups
	if m == nil {
		http.Error(w, "no backup service", 404)
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if input.ID == "" || m.active != input.ID || m.cancel == nil {
		http.Error(w, "backup is no longer active", 409)
		return
	}
	m.cancel()
	jsonOK(w, 202, map[string]any{"id": input.ID, "cancelling": true})
}
