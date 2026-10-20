package main

import (
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserSystemStatusWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	s := testServer(t)
	s.vaultVerified = true
	seed(t, s, "https://no-inference.example/v1")
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/system-status.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("system status browser: %v\n%s", err, out)
	}
	t.Log(string(out))
}
