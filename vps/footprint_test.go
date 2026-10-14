package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"image"
	"image/color"
	"image/png"
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
	"sync/atomic"
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
	// Four megapixels exercise real decode allocations even when compression
	// is efficient. Speech separately exercises the full four-MiB byte cap.
	img := image.NewRGBA(image.Rect(0, 0, 2048, 2048))
	for y := 0; y < 2048; y++ {
		for x := 0; x < 2048; x++ {
			img.SetRGBA(x, y, color.RGBA{uint8(x), uint8(y), uint8((x + y) % 256), 255})
		}
	}
	var encodedImage bytes.Buffer
	if png.Encode(&encodedImage, img) != nil {
		t.Fatal("profile PNG")
	}
	imagePayload, _ := json.Marshal(map[string]any{"data": []any{map[string]any{"b64_json": base64.StdEncoding.EncodeToString(encodedImage.Bytes())}}})
	pcm := make([]byte, maxAttachment-44)
	speechPayload, _ := json.Marshal(speechFixture(pcm, "audio/L16;codec=pcm;rate=24000"))
	var catalogHTTP, mediaHTTP atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("profile wrong credential")
			http.Error(w, "auth", 401)
			return
		}
		if r.URL.Path == "/v1/models" {
			catalogHTTP.Add(1)
			models := []any{map[string]any{"id": "m"}}
			for i := 0; i < 64; i++ {
				models = append(models, map[string]any{"id": fmt.Sprintf("profile-%d", i)})
			}
			json.NewEncoder(w).Encode(map[string]any{"data": models})
			return
		}
		if r.URL.Path == "/v1/images/generations" {
			mediaHTTP.Add(1)
			w.Write(imagePayload)
			return
		}
		if r.URL.Path == "/v1/models/m:generateContent" {
			mediaHTTP.Add(1)
			w.Write(speechPayload)
			return
		}
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
	imageScope, speechScope := v, v
	imageScope.Endpoint = "IMAGES_GENERATION"
	speechScope.Endpoint = "GEMINI_SPEECH"
	for _, scope := range []Selection{imageScope, speechScope} {
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	}
	confirmCaps(t, s, imageScope, []string{"IMAGE_GENERATION"})
	confirmCaps(t, s, speechScope, []string{"AUDIO_OUTPUT"})
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
		if !oneOfVersion(response.StatusCode, 200, 201) {
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
	chatRSS, chatPeak := memory()
	options, _ := json.Marshal(ResearchOptions{Selections: []Selection{v}, Budget: 150, ProbeBudget: 1, MinimalInference: true})
	started, e := do("/research/start", string(options))
	if e != nil {
		t.Fatal(e)
	}
	var run map[string]any
	json.Unmarshal(started, &run)
	runID := str(run["id"])
	if runID == "" {
		t.Fatal("no real research run")
	}
	deadline = time.Now().Add(30 * time.Second)
	for {
		raw, e := do("/research/tasks?run_id="+url.QueryEscape(runID), "")
		if e != nil {
			t.Fatal(e)
		}
		var tasks []ResearchTask
		if json.Unmarshal(raw, &tasks) != nil {
			t.Fatal("research tasks")
		}
		done := len(tasks) > 1
		for _, task := range tasks {
			if !oneOf(task.Status, "SUCCESS", "FAILED", "SKIPPED", "CANCELLED") {
				done = false
			}
		}
		if done {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("real research did not complete")
		}
		time.Sleep(50 * time.Millisecond)
	}
	if catalogHTTP.Load() < 1 {
		t.Fatal("research did not fetch provider")
	}
	researchRSS, researchPeak := memory()
	mediaProfile := func(scope Selection) (int64, int64) {
		errs := make(chan error, 4)
		var wg sync.WaitGroup
		for i := 0; i < 4; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				v := MediaRequest{Selection: scope, Prompt: "profile media", RequestID: newID()}
				if scope.Endpoint == "GEMINI_SPEECH" {
					v.Voice = "Kore"
					v.Format = "wav"
					v.SpeechDialect = "LEGACY"
				}
				body, _ := json.Marshal(v)
				raw, e := do("/infer/media", string(body))
				if e == nil {
					var result MediaResponse
					if json.Unmarshal(raw, &result) != nil || result.Outcome != "SUCCESS" || scopeKey(result.Selection) != scopeKey(scope) || len(result.Data) != 1 {
						e = fmt.Errorf("media not complete")
					}
				}
				errs <- e
			}()
		}
		wg.Wait()
		close(errs)
		for e := range errs {
			if e != nil {
				t.Fatal(e)
			}
		}
		return memory()
	}
	imageRSS, imagePeak := mediaProfile(imageScope)
	speechRSS, speechPeak := mediaProfile(speechScope)
	if mediaHTTP.Load() != 8 {
		t.Fatal("missing actual media work")
	}
	warm, hwm := memory()
	info, _ := os.Stat(binary)
	dbInfo, _ := os.Stat(dbPath)
	report := map[string]any{"profile": "100000 retained samples/full SQL percentiles; 4 TLS chats; durable research/catalog 65 models; 4 simultaneous 2048x2048 image decodes; 4 simultaneous native PCM-to-WAV responses at 4 MiB each", "binary_bytes": info.Size(), "idle_rss_bytes": idle, "warm_rss_bytes": warm, "peak_rss_bytes": hwm, "sqlite_bytes": dbInfo.Size(), "gomemlimit_bytes": 192 << 20, "chat_rss_bytes": chatRSS, "chat_peak_bytes": chatPeak, "research_rss_bytes": researchRSS, "research_peak_bytes": researchPeak, "image_rss_bytes": imageRSS, "image_peak_bytes": imagePeak, "speech_rss_bytes": speechRSS, "speech_peak_bytes": speechPeak, "catalog_http": catalogHTTP.Load(), "media_http": mediaHTTP.Load(), "image_pixels_each": 2048 * 2048, "speech_bytes_each": maxAttachment, "rss_method": "standalone PID /proc VmRSS + cumulative VmHWM; excludes test runner, fixture server and browser"}
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
