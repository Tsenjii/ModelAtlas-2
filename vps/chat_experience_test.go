package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestBrowserChatExperienceWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	first, second := strings.Builder{}, strings.Builder{}
	first.WriteString("# 串流優化回應\n\n")
	for i := 0; i < 2000; i++ {
		fmt.Fprintf(&first, "字%04d，", i)
	}
	// The production secret guard retains a short suffix until the next chunk.
	// Padding makes each marker visible before its fixture barrier is released.
	first.WriteString("[第一階段完成]" + strings.Repeat("。", 96))
	for i := 0; i < 1000; i++ {
		fmt.Fprintf(&second, "段%04d，", i)
	}
	second.WriteString("[第二階段完成]" + strings.Repeat("。", 96))
	ticks := strings.Repeat(string(rune(96)), 3)
	tail := "\n\n" + ticks + "js\nconsole.log('完整回應');\n" + ticks
	reply := first.String() + second.String() + tail
	phaseOne, phaseTwo, cancelled := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var releaseOne, releaseTwo, finishCancel sync.Once
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := calls.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("stream scope changed")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "m" {
			t.Error("stream model changed")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		emit := func(value string) {
			b, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]string{"content": value}}}})
			fmt.Fprintf(w, "data: %s\n\n", b)
		}
		if call == 2 {
			emit(strings.Repeat("可停止的回應", 20))
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			finishCancel.Do(func() { close(cancelled) })
			return
		}
		if call != 1 {
			t.Errorf("unexpected extra inference %d", call)
			return
		}
		emit("# 串流優化回應\n\n")
		for i := 0; i < 2000; i++ {
			emit(fmt.Sprintf("字%04d，", i))
			if i%80 == 79 {
				w.(http.Flusher).Flush()
			}
		}
		emit("[第一階段完成]" + strings.Repeat("。", 96))
		w.(http.Flusher).Flush()
		select {
		case <-phaseOne:
		case <-r.Context().Done():
			return
		}
		for i := 0; i < 1000; i++ {
			emit(fmt.Sprintf("段%04d，", i))
		}
		emit("[第二階段完成]" + strings.Repeat("。", 96))
		w.(http.Flusher).Flush()
		select {
		case <-phaseTwo:
		case <-r.Context().Done():
			return
		}
		emit(tail)
		fmt.Fprint(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":3000}}\n\ndata: [DONE]\n\n")
	}))
	defer up.Close()
	s := testServer(t)
	s.Client, s.AutoSync = up.Client(), false
	v := seed(t, s, up.URL+"/v1")
	session := newSession(t, s, v)
	for i := 0; i < 105; i++ {
		if e := s.saveMessage(context.Background(), session.ID, Message{ID: fmt.Sprintf("stream-history-%03d", i), Role: "user", Content: fmt.Sprintf("閱讀位置 %03d", i)}); e != nil {
			t.Fatal(e)
		}
	}
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/fixture/release-one":
			releaseOne.Do(func() { close(phaseOne) })
			w.WriteHeader(204)
		case "/fixture/release-two":
			releaseTwo.Do(func() { close(phaseTwo) })
			w.WriteHeader(204)
		default:
			handler.ServeHTTP(w, r)
		}
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/chat-experience.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_STREAM_SESSION="+session.ID, "MODELATLAS_STREAM_REPLY="+reply)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("chat experience browser: %v\n%s", e, out)
	}
	if calls.Load() != 2 {
		t.Fatalf("shortcuts/reading/refresh caused %d inference requests", calls.Load())
	}
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("Escape did not close the actual upstream request")
	}
	var count int
	if s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE outcome='CANCELLED'").Scan(&count) != nil || count != 1 {
		t.Fatal("cancelled request was not retained")
	}
	t.Log(string(out))
}
