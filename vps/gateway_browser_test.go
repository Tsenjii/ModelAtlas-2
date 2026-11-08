package main

import (
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserDistributionWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s := testServer(t)
	s.distribution = true
	f := newGatewayFixture(t, s, [2]string{"deepseek-v4.1-flash", "DeepSeek-V4_1-Flash"})
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	cmd := exec.Command("node", "e2e/distribution.cjs", app.URL, f.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_TEST_UPSTREAM_A="+gatewayTestKeyA,
		"MODELATLAS_TEST_UPSTREAM_B="+gatewayTestKeyB, "MODELATLAS_TEST_UPSTREAM_PAT="+gatewayTestPAT)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("distribution browser: %v\n%s", err, out)
	}
	t.Log(string(out))
	if f.a.Load() != 2 || f.b.Load() != 2 {
		t.Fatalf("expected one probe per upstream and one failover: %d / %d", f.a.Load(), f.b.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM gateway_requests WHERE outcome='SUCCESS' AND attempts=2")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM gateway_keys WHERE enabled=0")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM account_checkins WHERE source='MANUAL_COMPLETION'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE kind='MANUAL_ACCOUNT_QUOTA'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM newapi_snapshots WHERE credential_role='ACCOUNT_PAT'")
}
