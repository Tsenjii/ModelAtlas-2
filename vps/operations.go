package main

// Administrative operations do not construct a Server worker or contact a model
// provider. Offline backup/preflight/restore keep the application writer lock;
// online-backup explicitly uses a read-only SQLite snapshot. Environment files
// are parsed as data and are never sourced as shell code.
import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

var buildCommit = "development"
var buildTime = "unknown"

type binaryVersion struct {
	Commit  string `json:"commit"`
	BuiltAt string `json:"built_at"`
	OS      string `json:"os"`
	Arch    string `json:"arch"`
	Schema  int    `json:"schema"`
}

func versionInfo() binaryVersion {
	commit := buildCommit
	if commit == "development" {
		if info, ok := debug.ReadBuildInfo(); ok {
			for _, setting := range info.Settings {
				if setting.Key == "vcs.revision" {
					commit = setting.Value
				}
			}
		}
	}
	return binaryVersion{commit, buildTime, runtime.GOOS, runtime.GOARCH, schemaVersion}
}

func operationCommand(args []string, out io.Writer) error {
	if len(args) == 1 && args[0] == "version" {
		return json.NewEncoder(out).Encode(versionInfo())
	}
	if len(args) < 2 || args[0] != "ops" {
		return errors.New("usage: modelatlas-vps version | ops init-env|database|backup|online-backup|preflight|restore|ready [options]")
	}
	f := flag.NewFlagSet("ops", flag.ContinueOnError)
	f.SetOutput(io.Discard) // Never echo an invalid argument that might be a secret.
	envFile := f.String("env-file", "", "private environment file")
	defaultDB := f.String("default-db", "", "database used when env file has no DB setting")
	database := f.String("database", "", "offline input database")
	output := f.String("output", "", "new snapshot / preflight database")
	snapshot := f.String("snapshot", "", "verified snapshot to restore")
	gate := f.String("gate-file", "", "deployment maintenance file")
	directory := f.String("directory", "", "verified release bundle")
	if f.Parse(args[2:]) != nil || f.NArg() != 0 {
		return errors.New("invalid operation options; secrets belong in the private environment file")
	}
	if args[1] == "init-env" {
		return initializeEnvironment(*envFile, *defaultDB, *gate)
	}
	if args[1] == "bundle-check" {
		return verifyReleaseBundle(*directory)
	}
	config, err := operationConfig(*envFile, *defaultDB)
	if err != nil {
		return err
	}
	if *database != "" {
		config.db = *database
	}
	if !filepath.IsAbs(config.db) || filepath.Clean(config.db) != config.db || strings.ContainsAny(config.db, "\r\n?\x00") {
		return errors.New("operations require an absolute local database path")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	switch args[1] {
	case "database":
		if *gate != "" {
			values, e := readPrivateEnvironment(*envFile)
			if e != nil {
				return e
			}
			if configured, exists := values["MODELATLAS_MAINTENANCE_FILE"]; exists && configured != *gate {
				return errors.New("managed update requires the installed maintenance gate path")
			}
		}
		_, err = fmt.Fprintln(out, config.db)
	case "backup":
		err = withOfflineDatabase(config.db, func(db *sql.DB) error {
			if e := verifyDatabase(ctx, db, config.key); e != nil {
				return e
			}
			return snapshotDatabase(ctx, db, *output)
		})
	case "online-backup":
		err = onlineBackup(ctx, config.db, *output, config.key)
	case "preflight":
		// The source snapshot is immutable; only its separate private copy is migrated.
		if *output == "" || filepath.Clean(*output) == filepath.Clean(config.db) {
			return errors.New("preflight requires a separate output path")
		}
		if err = verifySnapshot(config.db); err != nil {
			return err
		}
		if err = copyExclusive(config.db, *output, 0600); err != nil {
			return err
		}
		var store *Store
		store, err = openStore(*output, false)
		if err == nil {
			defer store.Close()
			err = verifyDatabase(ctx, store.DB, config.key)
		}
	case "restore":
		err = restoreSnapshot(ctx, config.db, *snapshot, config.key)
	case "ready":
		err = verifyRunningService(ctx, config, false)
	case "ready-previous":
		err = verifyRunningService(ctx, config, true)
	default:
		return errors.New("unknown operation")
	}
	if err == nil && args[1] != "database" {
		_, err = fmt.Fprintln(out, "OK")
	}
	return err
}

func operationConfig(path, defaultDB string) (deploymentConfig, error) {
	if path == "" {
		return deploymentConfig{}, errors.New("--env-file is required")
	}
	values, err := readPrivateEnvironment(path)
	if err != nil {
		return deploymentConfig{}, err
	}
	if values["MODELATLAS_DB"] == "" {
		values["MODELATLAS_DB"] = defaultDB
	}
	c, err := readDeploymentConfig(func(k string) string { return values[k] })
	if err == nil && len(c.key) != 32 {
		err = errors.New("operations require the existing stable master key")
	}
	return c, err
}

func readPrivateEnvironment(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, errors.New("cannot read private environment file")
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > 65536 {
		return nil, errors.New("environment file must be a private regular file (chmod 600, at most 64 KiB)")
	}
	values := map[string]string{}
	scanner := bufio.NewScanner(io.LimitReader(f, 65537))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		name, value, ok := strings.Cut(line, "=")
		name, value = strings.TrimSpace(name), strings.TrimSpace(value)
		if !ok || name == "" || strings.IndexFunc(name, func(r rune) bool { return !(r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_') }) != -1 {
			return nil, errors.New("invalid environment assignment; use one NAME=value per line")
		}
		// Supports the generated format and single-line systemd quotes. No shell
		// expansion, backticks, command substitution or multiline continuation.
		if strings.HasPrefix(value, "\"") {
			value, err = strconv.Unquote(value)
			if err != nil {
				return nil, errors.New("invalid quoted environment value")
			}
		} else if strings.HasPrefix(value, "'") {
			if len(value) < 2 || !strings.HasSuffix(value, "'") {
				return nil, errors.New("invalid quoted environment value")
			}
			value = value[1 : len(value)-1]
		} else if strings.ContainsAny(value, "'\"\\") {
			return nil, errors.New("unsupported environment escaping; use single-line quoted values")
		}
		if strings.ContainsAny(value, "\r\n\x00") {
			return nil, errors.New("environment values must be single line")
		}
		values[name] = value
	}
	if scanner.Err() != nil {
		return nil, errors.New("cannot parse environment file")
	}
	return values, nil
}

func initializeEnvironment(path, db, gate string) error {
	if !filepath.IsAbs(path) || !filepath.IsAbs(db) || !filepath.IsAbs(gate) || strings.ContainsAny(path+db+gate, "\r\n\x00\"\\") {
		return errors.New("init-env requires absolute safe environment, database and gate paths")
	}
	if _, err := os.Lstat(path); err == nil {
		return errors.New("environment already exists; keeping the existing keys")
	} else if !os.IsNotExist(err) {
		return errors.New("cannot inspect environment file")
	}
	key, token := make([]byte, 32), make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return err
	}
	if _, err := rand.Read(token); err != nil {
		return err
	}
	value := "MODELATLAS_ADMIN_TOKEN=" + base64.RawURLEncoding.EncodeToString(token) + "\nMODELATLAS_MASTER_KEY=" + base64.StdEncoding.EncodeToString(key) + "\nMODELATLAS_DB=\"" + db + "\"\nMODELATLAS_LISTEN=127.0.0.1:8787\nMODELATLAS_MAINTENANCE_FILE=\"" + gate + "\"\n"
	return writeExclusive(path, []byte(value), 0600)
}

func acquireDatabaseLock(path string) (*os.File, error) {
	f, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		f.Close()
		return nil, errors.New("database already in use; stop the service before offline operations")
	}
	return f, nil
}

func withOfflineDatabase(path string, fn func(*sql.DB) error) error {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
		return errors.New("existing nonempty database required")
	}
	lock, err := acquireDatabaseLock(path)
	if err != nil {
		return err
	}
	defer lock.Close()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return err
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	if _, err = db.Exec("PRAGMA busy_timeout=5000"); err != nil {
		return err
	}
	return fn(db)
}

// Read ciphertexts only; failure messages never contain row identifiers,
// ciphertext, raw values or the driver's potentially sensitive SQL diagnostics.
func verifyVault(ctx context.Context, db *sql.DB, key []byte) error {
	s := &Server{Key: key}
	checks := []struct{ table, column, kind string }{
		{"credentials", "encrypted_key", "key"}, {"credential_secret_versions", "encrypted_key", "key"},
		{"integration_credentials", "encrypted_key", "key"}, {"assistant_secret_refs", "encrypted_key", "key"},
		{"credential_auth_fields", "fields_json", "fields"}, {"assistant_secret_refs", "fields_json", "fields"},
		{"workspace_peer", "encrypted_token", "key"}, {"onboarding", "draft_json", "draft"},
	}
	for _, check := range checks {
		var exists int
		if err := db.QueryRowContext(ctx, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", check.table).Scan(&exists); err != nil {
			return errors.New("vault inventory unavailable")
		}
		if exists == 0 {
			continue
		}
		rows, err := db.QueryContext(ctx, "SELECT "+check.column+" FROM "+check.table)
		if err != nil {
			return errors.New("vault inventory unavailable")
		}
		for rows.Next() {
			var raw string
			if err = rows.Scan(&raw); err != nil {
				break
			}
			if raw == "" {
				continue
			}
			switch check.kind {
			case "key":
				_, err = s.decrypt(raw)
			case "fields":
				_, err = s.decryptFields(raw)
			case "draft":
				var draft storedDraft
				if err = json.Unmarshal([]byte(raw), &draft); err == nil {
					for name, value := range draft.Encrypted {
						if value == "" {
							continue
						}
						if strings.HasSuffix(name, ":fields") {
							_, err = s.decryptFields(value)
						} else {
							_, err = s.decrypt(value)
						}
						if err != nil {
							break
						}
					}
				}
			}
			if err != nil {
				break
			}
		}
		if err == nil {
			err = rows.Err()
		}
		rows.Close()
		if err != nil {
			return errors.New("vault verification failed; keep the original master key and database together")
		}
	}
	return nil
}

func verifyDatabase(ctx context.Context, db *sql.DB, key []byte) error {
	var integrity string
	if db.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&integrity) != nil || integrity != "ok" {
		return errors.New("database integrity check failed")
	}
	rows, err := db.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return errors.New("foreign key check unavailable")
	}
	invalid := rows.Next()
	err = rows.Err()
	rows.Close()
	if invalid || err != nil {
		return errors.New("database foreign key check failed")
	}
	return verifyVault(ctx, db, key)
}

type databaseSnapshot struct {
	Format    int    `json:"format"`
	Schema    int    `json:"schema"`
	SHA256    string `json:"sha256"`
	CreatedAt string `json:"created_at"`
}

func snapshotDatabase(ctx context.Context, db *sql.DB, path string) error {
	if !filepath.IsAbs(path) {
		return errors.New("snapshot requires an absolute output path")
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		return errors.New("snapshot output already exists or is inaccessible")
	}
	if _, err := os.Lstat(path + ".json"); !os.IsNotExist(err) {
		return errors.New("snapshot manifest already exists or is inaccessible")
	}
	// Reserve private permissions before SQLite opens the empty output, even if
	// the platform's umask is permissive. SQLite accepts an empty target.
	if err := writeExclusive(path, nil, 0600); err != nil {
		return err
	}
	// VACUUM INTO is a consistent SQLite snapshot, including committed WAL data.
	if _, err := db.ExecContext(ctx, "VACUUM INTO ?", path); err != nil {
		return errors.New("snapshot failed; check free disk space and permissions")
	}
	if err := os.Chmod(path, 0600); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	err = file.Sync()
	file.Close()
	if err != nil {
		return err
	}
	var version int
	if err := db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	digest, err := fileDigest(path)
	if err != nil {
		return err
	}
	manifest, _ := json.Marshal(databaseSnapshot{1, version, digest, time.Now().UTC().Format(time.RFC3339)})
	return writeExclusive(path+".json", manifest, 0600)
}

func verifyReleaseBundle(directory string) error {
	raw, err := os.ReadFile(filepath.Join(directory, "VERSION.json"))
	var version binaryVersion
	if err != nil || len(raw) > 4096 || json.Unmarshal(raw, &version) != nil || version != versionInfo() || version.OS != "linux" || len(version.Commit) != 40 {
		return errors.New("release manifest does not match this binary / CPU architecture")
	}
	if _, err = hex.DecodeString(version.Commit); err != nil {
		return errors.New("invalid release commit")
	}
	f, err := os.Open(filepath.Join(directory, "SHA256SUMS.txt"))
	if err != nil {
		return err
	}
	defer f.Close()
	checks := map[string]bool{}
	scanner := bufio.NewScanner(io.LimitReader(f, 65536))
	for scanner.Scan() {
		line := scanner.Text()
		if len(line) < 67 || line[64:66] != "  " {
			return errors.New("invalid release checksum format")
		}
		name := line[66:]
		if name == "" || filepath.IsAbs(name) || filepath.Clean(name) != name || strings.Contains(name, "..") || strings.ContainsAny(name, "\\\r\n\x00") || checks[name] {
			return errors.New("unsafe release checksum path")
		}
		path := filepath.Join(directory, name)
		info, e := os.Lstat(path)
		if e != nil || !info.Mode().IsRegular() {
			return errors.New("release file missing or not regular")
		}
		digest, e := fileDigest(path)
		if e != nil || digest != line[:64] {
			return errors.New("release checksum mismatch")
		}
		checks[name] = true
	}
	if scanner.Err() != nil {
		return scanner.Err()
	}
	for _, name := range []string{"VERSION.json", "bin/modelatlas-vps-linux-" + version.Arch, "deploy/install.sh", "deploy/update.sh", "deploy/manage.sh", "deploy/modelatlas-vps.service", "UPDATE.md", "README.md", "PLATFORM_DEPLOY.md"} {
		if !checks[name] {
			return errors.New("incomplete release bundle")
		}
	}
	return nil
}

func fileDigest(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func verifySnapshot(path string) error {
	raw, err := os.ReadFile(path + ".json")
	var manifest databaseSnapshot
	if err != nil || len(raw) > 4096 || json.Unmarshal(raw, &manifest) != nil || manifest.Format != 1 {
		return errors.New("snapshot manifest missing or invalid")
	}
	digest, err := fileDigest(path)
	if err != nil || digest != manifest.SHA256 {
		return errors.New("snapshot checksum mismatch")
	}
	return nil
}

func writeExclusive(path string, value []byte, mode os.FileMode) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	_, err = f.Write(value)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = syncDirectory(filepath.Dir(path))
	}
	return err
}

func copyExclusive(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	if err == nil {
		err = out.Sync()
	}
	closeErr := out.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = syncDirectory(filepath.Dir(dst))
	}
	return err
}

func syncDirectory(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func restoreSnapshot(ctx context.Context, path, snapshot string, key []byte) error {
	if !filepath.IsAbs(snapshot) || filepath.Clean(path) == filepath.Clean(snapshot) {
		return errors.New("restore requires a separate absolute snapshot path")
	}
	if err := verifySnapshot(snapshot); err != nil {
		return err
	}
	if err := withOfflineDatabase(snapshot, func(db *sql.DB) error { return verifyDatabase(ctx, db, key) }); err != nil {
		return err
	}
	lock, err := acquireDatabaseLock(path)
	if err != nil {
		return err
	}
	defer lock.Close()
	// Prepare the complete replacement before moving the original; preserve even
	// failed migrations and WAL/SHM beside the original DB instead of deleting them.
	dir, err := os.MkdirTemp(filepath.Dir(path), ".restore-")
	if err != nil {
		return err
	}
	replacement := filepath.Join(dir, "restored.sqlite")
	if err = copyExclusive(snapshot, replacement, 0600); err != nil {
		return err
	}
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if _, e := os.Lstat(path + suffix); os.IsNotExist(e) {
			continue
		} else if e != nil {
			return e
		}
		if err = os.Rename(path+suffix, filepath.Join(dir, "previous.sqlite"+suffix)); err != nil {
			return err
		}
	}
	if err = os.Rename(replacement, path); err != nil {
		return err
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func verifyRunningService(ctx context.Context, c deploymentConfig, previous bool) error {
	host, port, _ := net.SplitHostPort(c.addr)
	if net.ParseIP(host).IsUnspecified() {
		host = "127.0.0.1"
	}
	if !net.ParseIP(host).IsLoopback() {
		return errors.New("ready requires a local listener")
	}
	path := "/api/v1/system"
	if previous {
		path = "/api/v1/status"
	}
	request, err := http.NewRequestWithContext(ctx, "GET", "http://"+net.JoinHostPort(host, port)+path, nil)
	if err != nil {
		return errors.New("invalid readiness URL")
	}
	request.SetBasicAuth("admin", c.token)
	if c.publicOrigin != "" {
		request.Host = strings.TrimPrefix(c.publicOrigin, "https://")
		request.Header.Set("X-Forwarded-Proto", "https")
	}
	client := &http.Client{Timeout: 3 * time.Second, Transport: &http.Transport{Proxy: nil}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer client.CloseIdleConnections()
	response, err := client.Do(request)
	if err != nil {
		return errors.New("service not ready")
	}
	defer response.Body.Close()
	if previous {
		var status struct {
			Persistent bool `json:"persistent"`
		}
		if response.StatusCode != 200 || json.NewDecoder(io.LimitReader(response.Body, 16384)).Decode(&status) != nil || !status.Persistent {
			return errors.New("previous service authentication / storage not ready")
		}
		return nil
	}
	var state systemState
	if response.StatusCode != 200 || json.NewDecoder(io.LimitReader(response.Body, 16384)).Decode(&state) != nil || state.Status != "ok" || state.Version != versionInfo() || !state.VaultVerified {
		return errors.New("service version, authentication, storage or vault readiness mismatch")
	}
	return nil
}
