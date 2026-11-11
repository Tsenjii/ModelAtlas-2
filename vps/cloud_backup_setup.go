package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

type cloudBackupSetup struct {
	Revision             string `json:"revision"`
	Provider             string `json:"provider"`
	Endpoint             string `json:"endpoint"`
	Bucket               string `json:"bucket"`
	Prefix               string `json:"prefix"`
	AccessKeyID          string `json:"access_key_id"`
	SecretAccessKey      string `json:"secret_access_key"`
	KeyRecoveryConfirmed bool   `json:"key_recovery_confirmed"`
}

var (
	r2BackupHost = regexp.MustCompile(`^[0-9a-f]{32}(\.(eu|fedramp|us))?\.r2\.cloudflarestorage\.com$`)
	b2BackupHost = regexp.MustCompile(`^s3\.([a-z0-9]+(-[a-z0-9]+)+)\.backblazeb2\.com$`)
	backupBucket = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$`)
	backupS3Key  = regexp.MustCompile(`^[A-Za-z0-9_/+=-]{1,512}$`)
)

func (input cloudBackupSetup) backend() (provider, region, label string, err error) {
	if !input.KeyRecoveryConfirmed {
		return "", "", "", fmt.Errorf("請先在主機之外保存 ModelAtlas 主金鑰。")
	}
	if !backupBucket.MatchString(input.Bucket) || !validCloudBackupPrefix(input.Prefix) || !backupS3Key.MatchString(input.AccessKeyID) || !backupS3Key.MatchString(input.SecretAccessKey) {
		return "", "", "", fmt.Errorf("請檢查 Bucket 名稱、備份資料夾與 S3 存取金鑰。")
	}
	endpoint, parseErr := url.Parse(input.Endpoint)
	if parseErr == nil && endpoint.Scheme == "https" && input.Endpoint == "https://"+endpoint.Host {
		switch input.Provider {
		case "r2":
			if r2BackupHost.MatchString(endpoint.Host) {
				return "Cloudflare", "auto", "Cloudflare R2 · " + input.Bucket, nil
			}
		case "b2":
			if match := b2BackupHost.FindStringSubmatch(endpoint.Host); match != nil {
				// The generic S3 backend also supports B2 on older rclone releases.
				return "Other", match[1], "Backblaze B2 · " + input.Bucket, nil
			}
		}
	}
	return "", "", "", fmt.Errorf("請填入這個 Bucket 在雲端控制台顯示的 HTTPS S3 端點。")
}

func (input cloudBackupSetup) rcloneConfig(provider, region, obscured string) []byte {
	return fmt.Appendf(nil, `[storage]
type = s3
provider = %s
env_auth = false
endpoint = %s
region = %s
access_key_id = %s
secret_access_key = %s
force_path_style = true
no_check_bucket = true
upload_concurrency = 1

[encrypted]
type = crypt
remote = storage:%s
filename_encryption = standard
directory_name_encryption = true
password = %s
`, provider, input.Endpoint, region, input.AccessKeyID, input.SecretAccessKey, input.Bucket, obscured)
}

func obscureManagedBackupKey(ctx context.Context, executable string, key []byte) (string, error) {
	// Stable across hosts with the same original master key, separated from the
	// application vault and from any user-supplied cloud credentials.
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte("modelatlas/cloud-backup/rclone-crypt/v1"))
	cmd := backupRcloneCommand(ctx, executable, "obscure", "-")
	cmd.Stdout = nil
	cmd.Stdin = strings.NewReader(base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) + "\n")
	output, err := cmd.Output()
	obscured := strings.TrimSpace(string(output))
	if err != nil || !backupS3Key.MatchString(obscured) {
		return "", fmt.Errorf("主機的 rclone 無法建立加密備份設定。")
	}
	return obscured, nil
}

type managedCloudBackup struct {
	ctx     context.Context
	source  string
	key     []byte
	mu      sync.RWMutex
	service *cloudBackupService
}

func managedBackupPath(source string) string {
	return filepath.Join(source+".cloud-backup", "destination.json")
}

func managedBackupRevision(path string) string {
	if _, err := os.Lstat(path); os.IsNotExist(err) {
		return ""
	}
	if raw, err := privateBackupFile(path, 65536); err == nil {
		return contentHash(raw)
	}
	return "unavailable"
}

func newManagedCloudBackup(ctx context.Context, source string, key []byte) *managedCloudBackup {
	m := &managedCloudBackup{ctx: ctx, source: source, key: key}
	path := managedBackupPath(source)
	if info, err := os.Lstat(filepath.Dir(path)); err == nil && info.IsDir() && info.Mode().Perm()&0077 == 0 && managedBackupRevision(path) != "" {
		m.service = newCloudBackupService(ctx, path, source, key)
	}
	return m
}

func (m *managedCloudBackup) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.service != nil {
		m.service.Close()
		m.service = nil
	}
}

func (s *Server) useCloudBackup() (*cloudBackupService, func()) {
	if s.managedBackups == nil {
		return s.backups, func() {}
	}
	m := s.managedBackups
	m.mu.RLock()
	return m.service, m.mu.RUnlock
}

func (s *Server) tickCloudBackup(ctx context.Context) {
	m, release := s.useCloudBackup()
	defer release()
	if m != nil {
		m.tick(ctx)
	}
}

func (s *Server) saveCloudBackupDestination(w http.ResponseWriter, r *http.Request) {
	m := s.managedBackups
	if m == nil {
		http.Error(w, "這個部署的備份目的地由主機管理。", http.StatusConflict)
		return
	}
	var input cloudBackupSetup
	if !decodeLimit(w, r, &input, 8192) {
		return
	}
	input.Endpoint = strings.TrimSuffix(strings.TrimSpace(input.Endpoint), "/")
	input.Bucket = strings.TrimSpace(input.Bucket)
	input.Endpoint = strings.TrimSuffix(input.Endpoint, "/"+input.Bucket)
	input.Prefix = strings.TrimSpace(input.Prefix)
	input.AccessKeyID = strings.TrimSpace(input.AccessKeyID)
	input.SecretAccessKey = strings.TrimSpace(input.SecretAccessKey)
	provider, region, label, err := input.backend()
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	executable, err := exec.LookPath("rclone")
	if err != nil || !backupExecutableAvailable(executable) {
		http.Error(w, "主機需要先安裝 rclone，容器版已內建。", http.StatusServiceUnavailable)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	obscured, err := obscureManagedBackupKey(ctx, executable, m.key)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	if code, err := m.save(input, executable, label, input.rcloneConfig(provider, region, obscured)); err != nil {
		http.Error(w, err.Error(), code)
		return
	}
	jsonOK(w, http.StatusOK, map[string]any{"configured": true})
}

func (m *managedCloudBackup) save(input cloudBackupSetup, executable, label string, credentials []byte) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.ctx.Err() != nil {
		return 503, fmt.Errorf("服務正在停止，請稍後再儲存。")
	}
	path := managedBackupPath(m.source)
	root := filepath.Dir(path)
	spool := filepath.Join(root, "spool")
	if prepareBackupSpool(root) != nil || prepareBackupSpool(spool) != nil {
		return 503, fmt.Errorf("主機無法準備私密備份目錄。")
	}
	if input.Revision != managedBackupRevision(path) {
		return 409, fmt.Errorf("目的地設定已變更，請重新讀取後再儲存。")
	}
	lease, err := acquireDatabaseLock(filepath.Join(spool, ".transfer"))
	if err != nil {
		return 409, fmt.Errorf("備份進行中，請等工作結束後再修改目的地。")
	}
	defer lease.Close()
	configFile := filepath.Join(root, "rclone-"+newID()+".conf")
	if err = writeExclusive(configFile, credentials, 0600); err != nil {
		return 503, fmt.Errorf("主機無法儲存私密的雲端憑證。")
	}
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(configFile)
		}
	}()
	staging, err := os.MkdirTemp(root, ".destination-")
	if err != nil {
		return 503, fmt.Errorf("主機無法準備備份目的地。")
	}
	defer os.RemoveAll(staging)
	prepared := filepath.Join(staging, "destination.json")
	cfg := cloudBackupDestination{
		Format: 1, Label: label, Executable: executable, ConfigFile: configFile,
		Remote: "encrypted", Prefix: input.Prefix, Spool: spool,
		KeyRecoveryConfirmed: true, MasterKeyBinding: cloudBackupMasterKeyBinding(m.key),
	}
	raw, _ := json.Marshal(cfg)
	if writeExclusive(prepared, raw, 0600) != nil {
		return 503, fmt.Errorf("主機無法儲存備份目的地。")
	}
	next := newCloudBackupService(m.ctx, prepared, m.source, m.key)
	if next.available() != nil || next.recoverInterrupted() != nil || os.Rename(prepared, path) != nil {
		next.Close()
		return 503, fmt.Errorf("主機無法套用備份目的地，請重新讀取狀態。")
	}
	old := m.service
	next.configPath, m.service, committed = path, next, true
	syncErr := syncDirectory(root)
	if old != nil {
		old.Close()
		oldConfig := old.cfg.ConfigFile
		if syncErr == nil && filepath.Dir(oldConfig) == root && strings.HasPrefix(filepath.Base(oldConfig), "rclone-") && strings.HasSuffix(oldConfig, ".conf") {
			_ = os.Remove(oldConfig)
		}
	}
	if syncErr != nil {
		return 503, fmt.Errorf("儲存結果需要重新讀取確認。")
	}
	return 200, nil
}
