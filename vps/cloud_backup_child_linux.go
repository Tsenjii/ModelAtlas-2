//go:build linux

package main

import (
	"os/exec"
	"syscall"
)

func configureBackupChild(cmd *exec.Cmd) {
	// A killed server must not leave an unowned transfer writing in the cloud.
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
}
