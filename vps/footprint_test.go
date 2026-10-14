package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// Measures the actual stripped standalone process, never the test runner's heap.
func TestStandaloneProcessFootprint(t *testing.T) {
	if os.Getenv("MODELATLAS_FOOTPRINT_TEST") != "1" {
		t.Skip("opt-in standalone runtime profile")
	}
	dir := t.TempDir()
	binary := filepath.Join(dir, "modelatlas-vps")
	build := exec.Command("go", "build", "-trimpath", "-ldflags=-s -w", "-o", binary, ".")
	build.Env = append(os.Environ(), "CGO_ENABLED=0")
	if out, e := build.CombinedOutput(); e != nil {
		t.Fatalf("build: %v %s", e, out)
	}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/chat/completions" {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"profile reply\"}}]}\n\n")
			w.(http.Flusher).Flush()
			time.Sleep(20 * time.Millisecond)
			fmt.Fprint(w, "data: [DONE]\n\n")
			return
		}
		http.NotFound(w, r)
	}))
	defer up.Close()
	caPath := filepath.Join(dir, "ca.pem")
	if os.WriteFile(caPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: up.Certificate().Raw}), 0600) != nil {
		t.Fatal("fixture CA")
	}
	s := testServer(t)
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	tx, e := s.Store.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	stmt, e := tx.Prepare("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,latency_ms,details_json,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',100,'{\"tps\":40}',?)")
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 100000; i++ {
		if _, e = stmt.Exec(fmt.Sprint(i), now()-600000-int64(i)); e != nil {
			t.Fatal(e)
		}
	}
	stmt.Close()
	if tx.Commit() != nil {
		t.Fatal("seed commit")
	}
	sessionIDs := []string{}
	for i := 0; i < 4; i++ {
		id, e := s.createTestSession(context.Background(), v)
		if e != nil {
			t.Fatal(e)
		}
		sessionIDs = append(sessionIDs, id)
	}
	var dbPath string
	rows, e := s.Store.DB.Query("PRAGMA database_list")
	if e != nil {
		t.Fatal(e)
	}
	var seq int
	var name string
	for rows.Next() {
		rows.Scan(&seq, &name, &dbPath)
	}
	rows.Close()
	s.Store.Close()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	addr := ln.Addr().String()
	ln.Close()
	child := exec.Command(binary)
	child.Env = append(os.Environ(), "MODELATLAS_LISTEN="+addr, "MODELATLAS_ADMIN_TOKEN="+s.Token, "MODELATLAS_MASTER_KEY="+base64.StdEncoding.EncodeToString(s.Key), "MODELATLAS_DB="+dbPath, "SSL_CERT_FILE="+caPath, "GOMEMLIMIT=192MiB")
	if e = child.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { _ = child.Process.Signal(os.Interrupt); _ = child.Wait() }()
	client := &http.Client{Timeout: 30 * time.Second}
	base := "http://" + addr
	do := func(path string, body string) ([]byte, error) {
		method := "GET"
		if body != "" {
			method = "POST"
		}
		r, e := http.NewRequest(method, base+"/api/v1"+path, strings.NewReader(body))
		if e != nil {
			return nil, e
		}
		r.Header.Set("Authorization", "Bearer "+s.Token)
		if body != "" {
			r.Header.Set("Content-Type", "application/json")
		}
		response, e := client.Do(r)
		if e != nil {
			return nil, e
		}
		defer response.Body.Close()
		b, e := io.ReadAll(response.Body)
		if response.StatusCode != 200 {
			return nil, fmt.Errorf("%s status %d", path, response.StatusCode)
		}
		return b, e
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, e = do("/status", ""); e == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("process did not start")
		}
		time.Sleep(30 * time.Millisecond)
	}
	memory := func() (int64, int64) {
		raw, e := os.ReadFile(fmt.Sprintf("/proc/%d/status", child.Process.Pid))
		if e != nil {
			return 0, 0
		}
		var rss, hwm int64
		for _, line := range strings.Split(string(raw), "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				n, _ := strconv.ParseInt(fields[1], 10, 64)
				if fields[0] == "VmRSS:" {
					rss = n * 1024
				}
				if fields[0] == "VmHWM:" {
					hwm = n * 1024
				}
			}
		}
		return rss, hwm
	}
	idle, _ := memory()
	if idle == 0 {
		t.Fatal("no process RSS")
	}
	detail, e := do("/models/detail?"+url.Values{"provider_id": {v.ProviderID}, "account_id": {v.AccountID}, "credential_id": {v.CredentialID}, "model_id": {v.ModelID}, "group": {v.Group}, "endpoint": {v.Endpoint}}.Encode()+"&window=all", "")
	if e != nil {
		t.Fatal(e)
	}
	var data map[string]any
	json.Unmarshal(detail, &data)
	if data["local"].(map[string]any)["samples"] != float64(100000) {
		t.Fatal("profile did not query full history")
	}
	if _, e = do("/my-models?all=true", ""); e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	errs := make(chan error, 4)
	for _, id := range sessionIDs {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			body, _ := json.Marshal(map[string]any{"session_id": id, "prompt": "profile"})
			raw, e := do("/infer", string(body))
			if e == nil && !strings.Contains(string(raw), "event: done") {
				e = fmt.Errorf("actual inference not complete")
			}
			errs <- e
		}(id)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	warm, hwm := memory()
	info, _ := os.Stat(binary)
	dbInfo, _ := os.Stat(dbPath)
	report := map[string]any{"profile": "100000 retained samples, complete SQL percentiles and 4 simultaneous real TLS streaming requests", "binary_bytes": info.Size(), "idle_rss_bytes": idle, "warm_rss_bytes": warm, "peak_rss_bytes": hwm, "sqlite_bytes": dbInfo.Size(), "gomemlimit_bytes": 192 << 20}
	raw, _ := json.MarshalIndent(report, "", "  ")
	t.Log(string(raw))
	if path := os.Getenv("MODELATLAS_FOOTPRINT_OUT"); path != "" {
		if e = os.WriteFile(path, raw, 0600); e != nil {
			t.Fatal(e)
		}
	}
	if hwm <= 0 || hwm > 256<<20 {
		t.Fatalf("standalone workload RSS outside 256 MiB target: %d", hwm)
	}
}
