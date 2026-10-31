package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func cloudBackupCommand(args []string, out io.Writer) error {
	if len(args) == 0 || !oneOf(args[0], "configure", "run", "fetch") {
		return errors.New("usage: backup configure|run|fetch --env-file PRIVATE_ENV --destination-file PRIVATE_DESTINATION [options]")
	}
	f := flag.NewFlagSet("backup", flag.ContinueOnError)
	f.SetOutput(io.Discard)
	env := f.String("env-file", "", "existing private deployment environment")
	destination := f.String("destination-file", "", "private host destination document")
	binary := f.String("rclone-binary", "", "absolute installed rclone executable")
	credentials := f.String("rclone-config", "", "private dedicated rclone configuration")
	remote := f.String("remote", "", "already configured crypt remote")
	prefix := f.String("prefix", "ModelAtlas", "relative encrypted backup prefix")
	spool := f.String("spool-directory", "", "private local working directory")
	label := f.String("label", "", "destination label")
	confirmed := f.Bool("confirm-key-recovery", false, "master key is separately stored outside this host")
	id := f.String("id", "", "persistent backup identifier")
	retry := f.Bool("retry", false, "explicitly retry the same failed backup")
	output := f.String("output", "", "new private download directory")
	if f.Parse(args[1:]) != nil || f.NArg() != 0 || !filepath.IsAbs(*destination) || filepath.Clean(*destination) != *destination {
		return errors.New("invalid backup options; credentials belong in private files")
	}
	config, err := operationConfig(*env, "")
	if err != nil {
		return err
	}
	if args[0] == "configure" {
		if !*confirmed {
			return errors.New("store the master key separately, then explicitly confirm key recovery")
		}
		if *binary == "" {
			*binary, err = exec.LookPath("rclone")
			if err != nil {
				return errors.New("install rclone and configure a crypt remote before continuing")
			}
		}
		cfg := cloudBackupDestination{Format: 1, Label: *label, Executable: *binary, ConfigFile: *credentials, Remote: *remote, Prefix: *prefix, Spool: *spool, KeyRecoveryConfirmed: true}
		parent := filepath.Dir(*destination)
		info, err := os.Lstat(parent)
		if err != nil || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
			return errors.New("destination document requires an existing private parent directory")
		}
		temporary, err := os.MkdirTemp(parent, ".backup-config-")
		if err != nil {
			return errors.New("cannot prepare private backup destination")
		}
		defer os.RemoveAll(temporary)
		prepared := filepath.Join(temporary, "destination.json")
		raw, _ := json.MarshalIndent(cfg, "", "  ")
		if err = writeExclusive(prepared, raw, 0600); err != nil {
			return err
		}
		if _, err = readCloudBackupDestination(prepared); err != nil {
			return err
		}
		if err = prepareBackupSpool(cfg.Spool); err != nil {
			return err
		}
		if err = writeExclusive(*destination, raw, 0600); err != nil {
			return errors.New("destination already exists or cannot be created; existing credentials were kept")
		}
		return json.NewEncoder(out).Encode(map[string]any{"configured": true, "enabled": false, "key_recovery": "user_confirmed_separate_storage"})
	}
	cfg, err := readCloudBackupDestination(*destination)
	if err != nil {
		return err
	}
	parent, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithTimeout(parent, 10*time.Minute)
	defer cancel()
	if args[0] == "fetch" {
		if !validCloudBackupID(*id) || !filepath.IsAbs(*output) || filepath.Clean(*output) != *output {
			return errors.New("fetch requires a backup id and a new absolute private output directory")
		}
		if err = os.Mkdir(*output, 0700); err != nil {
			return errors.New("fetch output already exists or cannot be created")
		}
		if err = copyCloudBackup(ctx, cfg, cfg.Remote+":"+cfg.Prefix+"/"+*id, *output); err != nil {
			return err
		}
		archive, err := verifyCloudBackupArchive(ctx, *output, *id, "", config.key)
		if err != nil {
			return err
		}
		return json.NewEncoder(out).Encode(map[string]any{"id": archive.ID, "verified": true, "sha256": archive.SHA256, "database_replaced": false})
	}
	m := newCloudBackupService(ctx, *destination, config.db, config.key)
	defer m.Close()
	if *id == "" {
		*id = "backup-" + newID()
	}
	if _, err = m.start(*id, m.cfg.fingerprint, *retry); err != nil {
		return err
	}
	m.wg.Wait()
	job, _, err := m.job(ctx, *id)
	if err != nil {
		return errors.New("cannot read final backup state; recover by its original identifier")
	}
	if err = json.NewEncoder(out).Encode(job); err != nil {
		return err
	}
	if job.State != "SUCCESS" {
		return errors.New("backup did not complete and verify; original local and remote data were retained")
	}
	return nil
}
