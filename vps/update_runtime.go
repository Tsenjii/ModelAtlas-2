package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

type updateJournal struct {
	Current   *installedUpdate `json:"current,omitempty"`
	Trial     *installedUpdate `json:"trial,omitempty"`
	Preparing bool             `json:"preparing,omitempty"`
	Snapshot  string           `json:"snapshot,omitempty"`
	Result    string           `json:"result,omitempty"`
}

func validUpdateDirectory(path string) bool {
	return filepath.IsAbs(path) && path != "/" && filepath.Clean(path) == path && !strings.ContainsAny(path, "\r\n\x00")
}

func readUpdateJournal(root string) (updateJournal, error) {
	var state updateJournal
	file := filepath.Join(root, "boot.json")
	raw, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return state, nil
	}
	if err != nil {
		return state, err
	}
	if len(raw) > 8192 || json.Unmarshal(raw, &state) != nil ||
		(state.Current != nil && !validInstalledUpdate(*state.Current)) ||
		(state.Trial != nil && !validInstalledUpdate(*state.Trial)) ||
		(state.Preparing && state.Trial == nil) ||
		(state.Snapshot != "" && (!state.Preparing || !filepath.IsLocal(state.Snapshot) || filepath.Base(state.Snapshot) != state.Snapshot)) {
		return updateJournal{}, errors.New("invalid update journal")
	}
	return state, nil
}

func saveUpdateJournal(root string, state updateJournal) error {
	raw, err := json.Marshal(state)
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(root, ".boot-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	_, err = file.Write(raw)
	if err = errors.Join(err, file.Sync(), file.Close()); err != nil {
		return err
	}
	if err = os.Rename(file.Name(), filepath.Join(root, "boot.json")); err != nil {
		return err
	}
	return syncDirectory(root)
}

func updateBinary(root string, version installedUpdate) string {
	return filepath.Join(root, "releases", version.Version.Commit, "bin", updateBundleName())
}

type portableSupervisor struct {
	root, env, fallback string
	args                []string
	config              deploymentConfig
}

type supervisedProcess struct {
	cmd  *exec.Cmd
	done chan struct{}
	err  error
}

func (b portableSupervisor) launch(binary, gate string) (*supervisedProcess, error) {
	cmd := exec.Command(binary, b.args...)
	cmd.Env = append(os.Environ(), "MODELATLAS_SUPERVISED_ROOT="+b.root, "MODELATLAS_TRIAL_GATE="+gate)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	keepalive, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		_ = keepalive.Close()
		return nil, err
	}
	process := &supervisedProcess{cmd: cmd, done: make(chan struct{})}
	go func() {
		process.err = cmd.Wait()
		close(process.done)
	}()
	return process, nil
}

func (p *supervisedProcess) stop() {
	_ = p.cmd.Process.Signal(syscall.SIGTERM)
	timer := time.NewTimer(18 * time.Second)
	defer timer.Stop()
	select {
	case <-p.done:
	case <-timer.C:
		_ = p.cmd.Process.Kill()
		<-p.done
	}
}

func (b portableSupervisor) trialGate() string {
	return filepath.Join(b.root, ".trial-gate")
}

func (b portableSupervisor) waitReady(ctx context.Context, p *supervisedProcess, version binaryVersion) error {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	timer := time.NewTicker(500 * time.Millisecond)
	defer timer.Stop()
	for {
		select {
		case <-p.done:
			return errors.New("trial exited before readiness")
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			if verifyServiceVersion(ctx, b.config, version, false) == nil {
				select {
				case <-p.done:
					return errors.New("trial exited during readiness")
				default:
					return nil
				}
			}
		}
	}
}

func (b portableSupervisor) snapshotPath(state updateJournal) string {
	return filepath.Join(b.root, "recovery", state.Snapshot, "atlas.sqlite")
}

func (b portableSupervisor) activeBinary(state updateJournal) (string, error) {
	if state.Current == nil {
		return b.fallback, nil
	}
	binary := updateBinary(b.root, *state.Current)
	if err := verifyUpdateBundle(filepath.Dir(filepath.Dir(binary)), *state.Current); err != nil {
		return "", fmt.Errorf("active release verification failed: %w", err)
	}
	return binary, nil
}

func (b portableSupervisor) runOperation(ctx context.Context, binary, name string, options ...string) error {
	args := append([]string{"ops", name, "--env-file", b.env}, options...)
	cmd := exec.CommandContext(ctx, binary, args...)
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// No public requests or workers run in a trial. Until promotion is durable, the
// snapshot is the authoritative recovery point and replaying a restore is safe.
func (b portableSupervisor) recoverTrial(state updateJournal) error {
	if state.Snapshot != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		active, err := b.activeBinary(state)
		if err != nil {
			return err
		}
		if err := b.runOperation(ctx, active, "restore", "--snapshot", b.snapshotPath(state)); err != nil {
			return fmt.Errorf("trial recovery requires its original snapshot: %w", err)
		}
	}
	state.Trial, state.Preparing, state.Snapshot, state.Result = nil, false, "", "rolled_back"
	return saveUpdateJournal(b.root, state)
}

func (b portableSupervisor) prepareTrial(ctx context.Context, state *updateJournal) error {
	target := *state.Trial
	bundle := filepath.Join(b.root, "releases", target.Version.Commit)
	if err := verifyUpdateBundle(bundle, target); err != nil {
		return err
	}
	dir, err := os.MkdirTemp(filepath.Join(b.root, "recovery"), "before-")
	if err != nil {
		return err
	}
	// Record the snapshot only after it and the preflight have succeeded. The
	// candidate cannot open the production database before this point.
	trial := *state
	trial.Snapshot = filepath.Base(dir)
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	snapshot := b.snapshotPath(trial)
	active, err := b.activeBinary(*state)
	if err != nil {
		return err
	}
	if err = b.runOperation(ctx, active, "backup", "--output", snapshot); err != nil {
		return err
	}
	if err = syncDirectory(filepath.Dir(dir)); err != nil {
		return err
	}
	scratch, err := os.MkdirTemp(dir, "preflight-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(scratch)
	copyPath := filepath.Join(scratch, "atlas.sqlite")
	if err = b.runOperation(ctx, updateBinary(b.root, target), "preflight", "--database", snapshot, "--output", copyPath); err != nil {
		return errors.New("candidate could not migrate or verify a private copy")
	}
	if err = writeExclusive(b.trialGate(), nil, 0600); err != nil {
		return err
	}
	if err = saveUpdateJournal(b.root, trial); err != nil {
		return err
	}
	*state = trial
	return nil
}

func (b portableSupervisor) clearGate() error {
	if err := os.Remove(b.trialGate()); err != nil && !os.IsNotExist(err) {
		return err
	}
	return syncDirectory(b.root)
}

func (b portableSupervisor) run(ctx context.Context) error {
	for _, dir := range []string{b.root, filepath.Join(b.root, "releases"), filepath.Join(b.root, "recovery")} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			return err
		}
	}
	lock, err := acquireDatabaseLock(filepath.Join(b.root, "supervisor"))
	if err != nil {
		return errors.New("another updater is already supervising this directory")
	}
	defer lock.Close()
	for ctx.Err() == nil {
		state, err := readUpdateJournal(b.root)
		if err != nil {
			return err
		}
		if state.Preparing {
			if err := b.recoverTrial(state); err != nil {
				return err
			}
			continue
		}
		if err = b.clearGate(); err != nil {
			return err
		}
		var child *supervisedProcess
		if state.Trial != nil {
			state.Preparing = true
			if err := saveUpdateJournal(b.root, state); err != nil {
				return err
			}
			if err = b.prepareTrial(ctx, &state); err != nil {
				if ctx.Err() != nil {
					return nil
				}
				log.Printf("ModelAtlas update preflight failed: %v", err)
				// Reload the durable record in case a journal rename reached disk
				// before a directory sync failed.
				record, err := readUpdateJournal(b.root)
				if err != nil {
					return err
				}
				if err := b.recoverTrial(record); err != nil {
					return err
				}
				continue
			}
			child, err = b.launch(updateBinary(b.root, *state.Trial), b.trialGate())
			if err == nil {
				err = b.waitReady(ctx, child, state.Trial.Version)
			}
			if err != nil {
				if child != nil {
					child.stop()
				}
				if ctx.Err() != nil {
					return nil
				}
				log.Printf("ModelAtlas update trial failed: %v", err)
				if err := b.recoverTrial(state); err != nil {
					return err
				}
				continue
			}
			state = updateJournal{Current: state.Trial, Result: "updated"}
			if err = saveUpdateJournal(b.root, state); err == nil {
				err = b.clearGate()
			}
			if err != nil {
				child.stop()
				return err
			}
		} else {
			binary, err := b.activeBinary(state)
			if err != nil {
				return err
			}
			child, err = b.launch(binary, "")
			if err != nil {
				return err
			}
		}
		select {
		case <-ctx.Done():
			child.stop()
			return nil
		case <-child.done:
			next, err := readUpdateJournal(b.root)
			if err != nil {
				return err
			}
			if next.Trial == nil {
				return fmt.Errorf("ModelAtlas service exited: %v", child.err)
			}
		}
	}
	return nil
}

func supervisePortable(root, env string, args []string, config deploymentConfig) error {
	fallback, err := os.Executable()
	if err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	return (portableSupervisor{root: root, env: env, fallback: fallback, args: args, config: config}).run(ctx)
}
