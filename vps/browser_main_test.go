package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var browserFlowSlots = make(chan struct{}, 2)
var browserClipboard sync.Mutex

// Each flow owns its server, database, context, pages and screenshots. Bound
// genuine browser work to two flows; clipboard readers share an OS resource.
// Called only after the opt-in guard, so ordinary unit tests remain unchanged.
func parallelBrowserFlow(t *testing.T) {
	t.Helper()
	t.Parallel()
	if oneOf(t.Name(), "TestBrowserChatExperienceWorkflow", "TestBrowserConversationsWorkflow") {
		browserClipboard.Lock()
		t.Cleanup(browserClipboard.Unlock)
	}
	browserFlowSlots <- struct{}{}
	t.Cleanup(func() { <-browserFlowSlots })
}

// Share only the actual browser process, never contexts, pages or app fixtures.
// Preserve every original flow, screenshot, assertion, request and time limit.
func TestMain(m *testing.M) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		os.Exit(m.Run())
	}
	realModule := os.Getenv("PLAYWRIGHT_MODULE")
	if realModule == "" {
		realModule = "playwright"
	}
	cmd := exec.Command("node", "e2e/browser-server.cjs")
	cmd.Env = append(os.Environ(), "MODELATLAS_PLAYWRIGHT_REAL_MODULE="+realModule)
	var diagnostics bytes.Buffer
	cmd.Stderr = &diagnostics
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err = cmd.Start(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	ready := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			ready <- scanner.Text()
		} else {
			ready <- ""
		}
	}()
	var line string
	select {
	case line = <-ready:
	case <-time.After(15 * time.Second):
		cmd.Process.Kill()
		cmd.Wait()
		fmt.Fprintln(os.Stderr, "real Chromium suite startup timed out")
		os.Exit(1)
	}
	var receipt struct {
		Endpoint string `json:"endpoint"`
	}
	if json.Unmarshal([]byte(line), &receipt) != nil || !strings.HasPrefix(receipt.Endpoint, "ws://127.0.0.1:") {
		cmd.Process.Kill()
		cmd.Wait()
		fmt.Fprintln(os.Stderr, "real Chromium suite startup failed:", diagnostics.String())
		os.Exit(1)
	}
	wrapper, err := filepath.Abs("e2e/browser-driver.cjs")
	if err != nil {
		cmd.Process.Kill()
		cmd.Wait()
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	os.Setenv("MODELATLAS_PLAYWRIGHT_REAL_MODULE", realModule)
	os.Setenv("MODELATLAS_BROWSER_ENDPOINT", receipt.Endpoint)
	os.Setenv("PLAYWRIGHT_MODULE", wrapper)
	isolation := exec.Command("node", "e2e/browser-isolation.cjs")
	if out, e := isolation.CombinedOutput(); e != nil {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()
		fmt.Fprintln(os.Stderr, "real browser context isolation failed:", e, string(out))
		os.Exit(1)
	}
	code := m.Run()
	stdin.Close()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err = <-done:
		if err != nil && code == 0 {
			fmt.Fprintln(os.Stderr, "Chromium suite shutdown failed:", err)
			code = 1
		}
	case <-time.After(10 * time.Second):
		cmd.Process.Kill()
		<-done
		if code == 0 {
			code = 1
		}
		fmt.Fprintln(os.Stderr, "Chromium suite did not stop")
	}
	os.Exit(code)
}
