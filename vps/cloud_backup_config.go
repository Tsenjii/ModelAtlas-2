package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

// Credentials remain in a host-owned rclone configuration. Web clients can
// select an already configured destination, never submit commands or secrets.
type cloudBackupDestination struct {
	Format               int    `json:"format"`
	Label                string `json:"label"`
	Executable           string `json:"rclone_binary"`
	ConfigFile           string `json:"rclone_config"`
	Remote               string `json:"remote"`
	Prefix               string `json:"prefix"`
	Spool                string `json:"spool_directory"`
	KeyRecoveryConfirmed bool   `json:"key_recovery_confirmed"`
	fingerprint          string
}

func privateBackupFile(path string, maximum int64) ([]byte, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return nil, errors.New("backup configuration requires an absolute private file")
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > maximum {
		return nil, errors.New("backup configuration must be a private regular file (chmod 600)")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, errors.New("cannot read private backup configuration")
	}
	defer f.Close()
	actual, err := f.Stat()
	if err != nil || !os.SameFile(info, actual) {
		return nil, errors.New("backup configuration changed while opening it")
	}
	raw, err := io.ReadAll(io.LimitReader(f, maximum+1))
	if err != nil || int64(len(raw)) > maximum {
		return nil, errors.New("cannot read bounded backup configuration")
	}
	return raw, nil
}

func backupPathSegment(value string) bool {
	return len(value) >= 1 && len(value) <= 128 && value != "." && value != ".." && strings.IndexFunc(value, func(r rune) bool {
		return !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.')
	}) == -1
}

func readCloudBackupDestination(path string) (cloudBackupDestination, error) {
	var cfg cloudBackupDestination
	raw, err := privateBackupFile(path, 65536)
	if err != nil {
		return cfg, err
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if d.Decode(&cfg) != nil || d.Decode(new(any)) != io.EOF || cfg.Format != 1 || len(cfg.Label) == 0 || len(cfg.Label) > 160 || strings.IndexFunc(cfg.Label, unicode.IsControl) != -1 {
		return cfg, errors.New("invalid backup destination document")
	}
	if !backupPathSegment(cfg.Remote) || strings.Contains(cfg.Remote, ".") || len(cfg.Prefix) == 0 || len(cfg.Prefix) > 512 {
		return cfg, errors.New("invalid configured backup remote or prefix")
	}
	for _, part := range strings.Split(cfg.Prefix, "/") {
		if !backupPathSegment(part) {
			return cfg, errors.New("backup prefix must contain safe relative path segments")
		}
	}
	for _, path := range []string{cfg.Executable, cfg.ConfigFile, cfg.Spool} {
		if !filepath.IsAbs(path) || filepath.Clean(path) != path || strings.ContainsAny(path, "\r\n\x00") {
			return cfg, errors.New("backup tool, credentials and spool require absolute host paths")
		}
	}
	info, err := os.Stat(cfg.Executable)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 || info.Mode().Perm()&0022 != 0 {
		return cfg, errors.New("configured backup executable is unavailable or writable by others")
	}
	secrets, err := privateBackupFile(cfg.ConfigFile, 2<<20)
	if err != nil {
		return cfg, err
	}
	crypt, err := backupCryptRemote(secrets, cfg.Remote)
	if err != nil {
		return cfg, err
	}
	// Bind the selected encryption identity, while ordinary OAuth access-token
	// refreshes in the underlying backend do not disable an approved schedule.
	// Neither private contents nor paths are returned to a web client.
	identity, _ := json.Marshal(crypt)
	cfg.fingerprint = contentHash(append(append([]byte{}, raw...), identity...))
	return cfg, nil
}

func validateBackupCryptRemote(raw []byte, remote string) error {
	_, err := backupCryptRemote(raw, remote)
	return err
}

func backupCryptRemote(raw []byte, remote string) (map[string]string, error) {
	sections := map[string]map[string]string{}
	section := ""
	scan := bufio.NewScanner(bytes.NewReader(raw))
	scan.Buffer(make([]byte, 4096), 2<<20)
	for scan.Scan() {
		line := strings.TrimSpace(scan.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = line[1 : len(line)-1]
			if _, exists := sections[section]; exists {
				return nil, errors.New("duplicate backup credential section")
			}
			sections[section] = map[string]string{}
			continue
		}
		name, value, ok := strings.Cut(line, "=")
		if !ok || section == "" {
			return nil, errors.New("cannot verify the private rclone configuration; use a dedicated readable config")
		}
		name, value = strings.TrimSpace(name), strings.TrimSpace(value)
		if _, exists := sections[section][name]; exists {
			return nil, errors.New("duplicate backup credential field")
		}
		sections[section][name] = value
	}
	if scan.Err() != nil {
		return nil, errors.New("cannot parse bounded backup credentials")
	}
	v := sections[remote]
	if v["type"] != "crypt" || v["remote"] == "" || v["password"] == "" || !oneOf(v["no_data_encryption"], "", "false") || !oneOf(v["filename_encryption"], "", "standard") || !oneOf(v["directory_name_encryption"], "", "true") {
		return nil, errors.New("backup destination requires a configured rclone crypt remote with data and filename encryption")
	}
	return v, nil
}

func prepareBackupSpool(path string) error {
	if err := os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
		return errors.New("cannot create backup spool; create its parent directory first")
	}
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
		return errors.New("backup spool must be a private directory (chmod 700), without a symbolic link")
	}
	return nil
}
