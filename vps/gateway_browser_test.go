package main

import (
	"fmt"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestBrowserDistributionWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	s := testServer(t)
	s.distribution = true
	f := newGatewayFixture(t, s, [2]string{"deepseek-v4.1-flash", "DeepSeek-V4_1-Flash"})
	// A dated archive fixture exercises the same stored measurement and
	// aggregation path as the two live model probes below.
	for day, count := range []int{0, 8, 4, 11, 7, 3, 5} {
		for i := range count {
			outcome := "SUCCESS"
			if i == 2 {
				outcome = "RATE_LIMITED"
			}
			models := []string{"deepseek-v4.1-flash", "qwen-3.8-max", "glm-5.3"}
			if _, err := s.Store.DB.Exec(`INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,
				source,outcome,ttft_ms,latency_ms,input_tokens,output_tokens,created_at)
				VALUES(?,'archive-site','archive-account','archive-key',?,'','CHAT_COMPLETIONS','CHAT_COMPLETIONS',
				'MANUAL_TEST',?,?,?,?,?,?)`, fmt.Sprintf("archive-%d-%d", day, i), models[i%len(models)], outcome,
				115+i*7, 450+i*17, 80+i*12, 130+i*23, time.Now().AddDate(0, 0, -day).UnixMilli()); err != nil {
				t.Fatal(err)
			}
		}
	}
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
