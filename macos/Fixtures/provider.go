package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Local-only synthetic provider. No production credentials or external HTTP.
func main() {
	dir := os.Args[1]
	if err := os.MkdirAll(dir, 0700); err != nil {
		panic(err)
	}
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	root := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "ModelAtlas ephemeral CI root " + serial.String()}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	rootDER, _ := x509.CreateCertificate(rand.Reader, root, root, &key.PublicKey, key)
	rootCert, _ := x509.ParseCertificate(rootDER)
	leafKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "localhost"}, DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, NotBefore: root.NotBefore, NotAfter: root.NotAfter, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leaf, rootCert, &leafKey.PublicKey, key)
	privateDER, _ := x509.MarshalECPrivateKey(leafKey)
	cert, _ := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER}), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: privateDER}))
	if err := os.WriteFile(filepath.Join(dir, "ca.pem"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: rootDER}), 0600); err != nil {
		panic(err)
	}
	var cancelled, redirected atomic.Int64
	spy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1); io.WriteString(w, `{}`) }))
	defer spy.Close()
	richReply := "## 原生 Mac，也能舒服地寫程式\n\n清楚的排版讓**重點**、程式碼與比較資料更容易閱讀。這些內容來自隔離的 TLS 合成測試。\n\n```swift\nstruct Workspace {\n    let name = \"ModelAtlas\"\n    let local = true\n\n    func greeting() -> String {\n        \"你好，\\(name)\"\n    }\n}\n```\n\n### 工作區比較\n\n| 工作區 | 用途 | 資料來源 |\n| --- | --- | --- |\n| Playground | 對話與寫程式 | 本機請求 |\n| Providers | 管理連線 | 使用者設定 |\n| Models | 搜尋與比較 | 各自保留來源 |\n\n> 上捲閱讀時，新的回應會繼續接收，畫面留在你正在看的位置。\n\n- 程式碼可直接複製。\n- 表格可水平捲動。\n- 附件可以拖入輸入區。\n\n" + strings.Repeat("這是較長的原生閱讀測試段落。串流持續接收，歷史仍完整保存；向上閱讀不會被新文字拉回底部。\n\n", 18)
	mux := http.NewServeMux()
	// Disposable native integration only: the already trusted TLS listener can
	// expose a second real packaged engine's public sync routes. Admin APIs are
	// never proxied, and a target must be an uncredentialed loopback HTTP origin.
	var workspaceTarget atomic.Pointer[url.URL]
	var transferMu sync.Mutex
	var transferArmed, transferHeld bool
	var transferRelease chan struct{}
	mux.HandleFunc("/fixture/workspace-transfer", func(w http.ResponseWriter, r *http.Request) {
		transferMu.Lock()
		defer transferMu.Unlock()
		if r.Method == http.MethodPost {
			var value struct {
				Action string `json:"action"`
			}
			if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&value) != nil {
				http.Error(w, "invalid control", 400)
				return
			}
			switch value.Action {
			case "arm":
				if transferRelease != nil {
					http.Error(w, "already armed", 409)
					return
				}
				transferArmed = true
				transferHeld = false
				transferRelease = make(chan struct{})
			case "release":
				if transferRelease != nil {
					close(transferRelease)
					transferRelease = nil
				}
				transferArmed = false
			default:
				http.Error(w, "unknown control", 400)
				return
			}
		} else if r.Method != http.MethodGet {
			http.Error(w, "method required", 405)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]bool{"armed": transferArmed, "held": transferHeld})
	})
	mux.HandleFunc("/fixture/workspace-target", func(w http.ResponseWriter, r *http.Request) {
		var value struct {
			URL string `json:"url"`
		}
		if r.Method != http.MethodPost || json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&value) != nil {
			http.Error(w, "invalid fixture target", 400)
			return
		}
		target, err := url.Parse(value.URL)
		if err != nil || target.Scheme != "http" || target.Hostname() != "127.0.0.1" || target.Port() == "" || target.User != nil || (target.Path != "" && target.Path != "/") || target.RawQuery != "" || target.Fragment != "" {
			http.Error(w, "loopback fixture target required", 400)
			return
		}
		workspaceTarget.Store(target)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/workspace/", func(w http.ResponseWriter, r *http.Request) {
		target := workspaceTarget.Load()
		if target == nil {
			http.Error(w, "workspace fixture not ready", 503)
			return
		}
		proxy := httputil.NewSingleHostReverseProxy(target)
		// Hold only the real center's successful first part response, after its
		// SQLite commit. No request, receipt or progress value is fabricated.
		proxy.ModifyResponse = func(response *http.Response) error {
			transferMu.Lock()
			var release chan struct{}
			if transferArmed && r.URL.Path == "/api/workspace/transfers/part" && response.StatusCode == 200 {
				transferArmed = false
				transferHeld = true
				release = transferRelease
			}
			transferMu.Unlock()
			if release != nil {
				select {
				case <-release:
				case <-r.Context().Done():
					return r.Context().Err()
				}
			}
			return nil
		}
		proxy.ServeHTTP(w, r)
	})
	installParityRoutes(mux)
	richContinue := make(chan struct{})
	var releaseRich sync.Once
	var richPaused atomic.Bool
	var richSent atomic.Int64
	var richFinished atomic.Bool
	mux.HandleFunc("/fixture/rich-state", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"paused": richPaused.Load(), "sent_characters": richSent.Load(), "finished": richFinished.Load(), "expected_characters": len([]rune(richReply)), "sha256": fmt.Sprintf("%x", sha256.Sum256([]byte(richReply)))})
	})
	mux.HandleFunc("/fixture/release-rich", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method required", 405)
			return
		}
		releaseRich.Do(func() { close(richContinue) })
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer native-fixture-provider-key" {
			http.Error(w, "fixture auth required", 401)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"data":[{"id":"native-fixture-chat"},{"id":"native-fixture-code"},{"id":"native-fixture-slow"}]}`)
	})
	mux.HandleFunc("/v1/chat/completions", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer native-fixture-provider-key" {
			http.Error(w, "fixture auth required", 401)
			return
		}
		var body struct {
			Model  string `json:"model"`
			Stream bool   `json:"stream"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			http.Error(w, "bad fixture body", 400)
			return
		}
		reply := "這是原生 Mac 工作區。你的模型、對話和研究資料都保存在本機，並保留清楚的連線與來源。"
		if body.Model == "native-fixture-code" {
			reply = richReply
		}
		if !body.Stream {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]any{"model": body.Model, "choices": []any{map[string]any{"message": map[string]string{"content": reply}, "finish_reason": "stop"}}, "usage": map[string]int{"prompt_tokens": 12, "completion_tokens": 48, "total_tokens": 60}})
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		f := w.(http.Flusher)
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
		f.Flush()
		if body.Model == "native-fixture-slow" {
			<-r.Context().Done()
			cancelled.Add(1)
			return
		}
		for index, rune := range []rune(reply) {
			if body.Model == "native-fixture-code" && index == 900 {
				richPaused.Store(true)
				select {
				case <-richContinue:
					richPaused.Store(false)
				case <-r.Context().Done():
					return
				}
			}
			chunk, _ := json.Marshal(map[string]any{"model": body.Model, "choices": []any{map[string]any{"delta": map[string]string{"content": string(rune)}}}})
			fmt.Fprintf(w, "data: %s\n\n", chunk)
			f.Flush()
			if body.Model == "native-fixture-code" {
				richSent.Store(int64(index + 1))
			}
			if body.Model == "native-fixture-code" {
				// Small, evenly paced bursts avoid per-rune timer granularity
				// deciding when the two architectures reach the handshake.
				if index%8 == 7 {
					time.Sleep(20 * time.Millisecond)
				}
			} else {
				time.Sleep(3 * time.Millisecond)
			}
		}
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":48,\"total_tokens\":60}}\n\ndata: [DONE]\n\n")
		f.Flush()
		if body.Model == "native-fixture-code" {
			richFinished.Store(true)
		}
	})
	secure := httptest.NewUnstartedServer(mux)
	secure.TLS = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
	secure.StartTLS()
	defer secure.Close()
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/redirect" {
			http.Redirect(w, r, spy.URL, http.StatusFound)
			return
		}
		if r.URL.Path == "/api/v1/truncated" {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "event: delta\ndata: {\"text\":\"partial\"}\n\n")
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"cancelled": cancelled.Load(), "redirected": redirected.Load()})
	}))
	defer plain.Close()
	data, _ := json.Marshal(map[string]string{"url": secure.URL, "plain": plain.URL})
	if err := os.WriteFile(filepath.Join(dir, "fixture.json"), data, 0600); err != nil {
		panic(err)
	}
	select {}
}

// The parity harness asserts real wire options without retaining prompts or keys.
func installParityRoutes(mux *http.ServeMux) {
	installPublicSiteRoutes(mux)
	var counts sync.Map
	record := func(name string) {
		value, _ := counts.LoadOrStore(name, new(atomic.Int64))
		value.(*atomic.Int64).Add(1)
	}
	wav := make([]byte, 52)
	copy(wav, "RIFF")
	binary.LittleEndian.PutUint32(wav[4:], 44)
	copy(wav[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:], 16)
	binary.LittleEndian.PutUint16(wav[20:], 1)
	binary.LittleEndian.PutUint16(wav[22:], 1)
	binary.LittleEndian.PutUint32(wav[24:], 24000)
	binary.LittleEndian.PutUint32(wav[28:], 48000)
	binary.LittleEndian.PutUint16(wav[32:], 2)
	binary.LittleEndian.PutUint16(wav[34:], 16)
	copy(wav[36:], "data")
	binary.LittleEndian.PutUint32(wav[40:], 8)
	bitmap := image.NewRGBA(image.Rect(0, 0, 160, 120))
	for y := 0; y < 120; y++ {
		for x := 0; x < 160; x++ {
			bitmap.SetRGBA(x, y, color.RGBA{uint8(x), 130, uint8(y + 50), 255})
		}
	}
	var raster bytes.Buffer
	_ = png.Encode(&raster, bitmap)
	mux.HandleFunc("/fixture/parity-state", func(w http.ResponseWriter, r *http.Request) {
		out := map[string]int64{}
		counts.Range(func(k, v any) bool { out[k.(string)] = v.(*atomic.Int64).Load(); return true })
		json.NewEncoder(w).Encode(out)
	})
	mux.HandleFunc("/fixture/parity.wav", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/wav")
		w.Write(wav)
	})
	mux.HandleFunc("/fixture/parity.png", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Write(raster.Bytes())
	})
	auth := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Authorization") != "Bearer native-fixture-provider-key" {
			record("invalid")
			http.Error(w, "fixture auth required", 401)
			return false
		}
		return true
	}
	object := func(v any) map[string]any { m, _ := v.(map[string]any); return m }
	for _, path := range []string{"/v1/audio/speech", "/v1/embeddings", "/v1/rerank", "/v1/images/generations"} {
		path := path
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if !auth(w, r) {
				return
			}
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				record("invalid")
				http.Error(w, "invalid fixture body", 400)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			switch path {
			case "/v1/audio/speech":
				if body["voice"] != "alloy" || body["speed"] != 1.75 || body["response_format"] != "wav" || body["instructions"] != "親切" {
					record("invalid")
					http.Error(w, "speech option mismatch", 400)
					return
				}
				record("speech")
				w.Header().Set("Content-Type", "audio/wav")
				w.Write(wav)
			case "/v1/embeddings":
				inputs, _ := body["input"].([]any)
				if len(inputs) != 2 || body["dimensions"] != float64(3) {
					record("invalid")
					http.Error(w, "vector option mismatch", 400)
					return
				}
				record("embeddings")
				json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"index": 0, "embedding": []float64{0, 0.5, 1}}, map[string]any{"index": 1, "embedding": []float64{1, 0.5, 0}}}, "usage": map[string]int{"prompt_tokens": 0, "total_tokens": 0}})
			case "/v1/rerank":
				documents, _ := body["documents"].([]any)
				if len(documents) != 2 || body["top_n"] != float64(1) {
					record("invalid")
					http.Error(w, "rerank option mismatch", 400)
					return
				}
				record("rerank")
				io.WriteString(w, `{"results":[{"index":1,"relevance_score":0.9}]}`)
			case "/v1/images/generations":
				if body["size"] != "1024x1024" {
					record("invalid")
					http.Error(w, "image size mismatch", 400)
					return
				}
				record("images")
				json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"b64_json": base64.StdEncoding.EncodeToString(raster.Bytes())}}})
			}
		})
	}
	for _, path := range []string{"/v1/audio/transcriptions", "/v1/images/edits"} {
		path := path
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if !auth(w, r) {
				return
			}
			if r.ParseMultipartForm(8<<20) != nil {
				record("invalid")
				http.Error(w, "multipart required", 400)
				return
			}
			defer r.MultipartForm.RemoveAll()
			field := "file"
			if path == "/v1/images/edits" {
				field = "image"
			}
			file, _, err := r.FormFile(field)
			if err != nil {
				record("invalid")
				http.Error(w, "file required", 400)
				return
			}
			defer file.Close()
			raw, err := io.ReadAll(io.LimitReader(file, 4<<20))
			if err != nil || len(raw) == 0 {
				record("invalid")
				http.Error(w, "empty file", 400)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			if path == "/v1/audio/transcriptions" {
				if r.FormValue("prompt") != "" {
					record("invalid")
					http.Error(w, "transcription must allow empty prompt", 400)
					return
				}
				record("transcription")
				io.WriteString(w, `{"text":"這是只含音訊附件的真實轉寫測試。"}`)
			} else {
				record("image-edit")
				json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"b64_json": base64.StdEncoding.EncodeToString(raster.Bytes())}}})
			}
		})
	}
	mux.HandleFunc("/v1/models/", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var body map[string]any
		if !strings.HasSuffix(r.URL.Path, ":generateContent") || json.NewDecoder(r.Body).Decode(&body) != nil {
			record("invalid")
			http.Error(w, "native speech required", 400)
			return
		}
		config := object(body["generationConfig"])
		speech := object(config["speechConfig"])
		label := "native-current"
		if multi := object(speech["multiSpeakerVoiceConfig"]); len(multi) > 0 {
			voices, _ := multi["speakerVoiceConfigs"].([]any)
			contents, _ := body["contents"].([]any)
			if len(voices) != 2 || len(contents) != 1 {
				record("invalid")
				http.Error(w, "dual speakers missing", 400)
				return
			}
			parts, _ := object(contents[0])["parts"].([]any)
			if len(parts) != 2 || object(object(parts[0])["speech_metadata"])["speaker"] != "Joe" || object(object(parts[1])["speech_metadata"])["speaker"] != "Jane" {
				record("invalid")
				http.Error(w, "dual turns mismatch", 400)
				return
			}
			label = "native-dual"
		} else if voice := object(speech["voiceConfig"]); voice["prebuiltVoiceConfig"] != nil {
			label = "native-legacy"
		} else if voice["voice"] != "Kore" {
			record("invalid")
			http.Error(w, "native voice mismatch", 400)
			return
		}
		record(label)
		json.NewEncoder(w).Encode(map[string]any{"candidates": []any{map[string]any{"finishReason": "STOP", "content": map[string]any{"parts": []any{map[string]any{"inlineData": map[string]any{"mimeType": "audio/L16;codec=pcm;rate=24000", "data": base64.StdEncoding.EncodeToString(wav[44:])}}}}}}, "usageMetadata": map[string]int{"promptTokenCount": 0, "candidatesTokenCount": 0, "totalTokenCount": 0}})
	})
	mux.HandleFunc("/v1/quota/", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		record("connector")
		json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"model": strings.TrimPrefix(r.URL.Path, "/v1/quota/"), "group": "", "limits": map[string]int{"rpd": 120, "tpd": 2000000}}}})
	})
}

// Same response shapes as the anonymously inspected New API v0 deployment.
// Values/text are synthetic; no real provider credentials or traffic are used.
func installPublicSiteRoutes(mux *http.ServeMux) {
	var failNotice atomic.Bool
	var reads, authLeaks atomic.Int64
	mux.HandleFunc("/fixture/site-state", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]int64{"public_reads": reads.Load(), "auth_leaks": authLeaks.Load()})
	})
	mux.HandleFunc("/fixture/site-failure", func(w http.ResponseWriter, r *http.Request) {
		failNotice.Store(r.URL.Query().Get("enabled") == "true")
		json.NewEncoder(w).Encode(map[string]bool{"enabled": failNotice.Load()})
	})
	for _, path := range []string{"/api/status", "/api/notice", "/api/pricing", "/api/perf-metrics/summary", "/api/perf-metrics"} {
		path := path
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			reads.Add(1)
			if r.Header.Get("Authorization") != "" {
				authLeaks.Add(1)
				http.Error(w, "anonymous only", 401)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			switch path {
			case "/api/status":
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]any{"version": "v0.0.0", "system_name": "原生功能驗收 · 合成站點", "quota_per_unit": 500000, "checkin_enabled": true, "HeaderNavModules": "", "quota_display_type": "CUSTOM", "custom_currency_symbol": "￥"}})
			case "/api/notice":
				if failNotice.Load() {
					http.Error(w, "native-fixture-provider-key", 502)
					return
				}
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": "歡迎使用模型工作區。\n公告與模型資料會從站點直接讀取，並保留更新時間。\n這是隔離驗收資料。"})
			case "/api/pricing":
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": []any{map[string]any{"model_name": "native-fixture-chat", "quota_type": 0, "model_ratio": 1, "completion_ratio": 2, "enable_groups": []string{"default"}, "supported_endpoint_types": []string{"openai"}}}, "group_ratio": map[string]int{"default": 1}, "pricing_version": "synthetic-public-v0"})
			case "/api/perf-metrics/summary":
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]any{"summary": map[string]any{"success_rate": 98.5, "avg_tps": 62.4, "avg_latency_ms": 1240}, "window_start": 1791705600, "window_end": 1792309328, "models": []any{map[string]any{"model_name": "native-fixture-chat", "success_rate": 98.5, "avg_tps": 62.4, "avg_latency_ms": 1240}}}})
			case "/api/perf-metrics":
				hours := int64(168)
				if _, err := fmt.Sscan(r.URL.Query().Get("hours"), &hours); err != nil || hours < 1 || hours > 720 {
					http.Error(w, "invalid window", 400)
					return
				}
				windowEnd := int64(1792309328)
				points := []any{map[string]any{"ts": 1791705600, "avg_ttft_ms": 220, "avg_latency_ms": 1250, "success_rate": 99, "avg_tps": 55.2}, map[string]any{"ts": 1791792000, "avg_ttft_ms": 180, "avg_latency_ms": 1100, "success_rate": 100, "avg_tps": 68.1}, map[string]any{"ts": 1791964800, "avg_ttft_ms": 320, "avg_latency_ms": 1410, "success_rate": 96.5, "avg_tps": 52.7}, map[string]any{"ts": 1792309200, "avg_ttft_ms": 195, "avg_latency_ms": 1170, "success_rate": 98.5, "avg_tps": 62.4}}
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]any{"model_name": r.URL.Query().Get("model"), "series_schema": "dbcd0a3c01b55203", "summary": map[string]any{"success_rate": 98.5, "avg_tps": 62.4, "avg_latency_ms": 1240}, "series": points, "window_start": windowEnd - hours*3600, "window_end": windowEnd, "groups": []any{map[string]any{"group": "default", "avg_ttft_ms": 220, "avg_latency_ms": 1240, "success_rate": 98.5, "avg_tps": 62.4, "series": points}}}})
			}
		})
	}
}
