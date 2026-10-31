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
	"sync/atomic"
	"testing"
)

func TestConversationsSearchArchiveStaleEditAndCompleteExport(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	session := newSession(t, s, v)
	tx, e := s.Store.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 1100; i++ {
		content := fmt.Sprintf("紀錄 %04d", i)
		if i == 0 {
			content += " literal%needle"
		}
		if i == 1099 {
			content += " top-secret-example-key"
		}
		if _, e = tx.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,'user',?,?)", fmt.Sprint(i), session.ID, content, i); e != nil {
			t.Fatal(e)
		}
	}
	if tx.Commit() != nil {
		t.Fatal("commit")
	}
	var found []Session
	response := req(t, s, "GET", "/api/v1/sessions?q=literal%25needle&limit=50", nil)
	requireCode(t, response, 200)
	if json.Unmarshal(response.Body.Bytes(), &found) != nil || len(found) != 1 || found[0].MessageCount != 1100 || strings.Contains(found[0].Preview, "top-secret") {
		t.Fatal("full content search/count/redaction failed", response.Body.String())
	}
	requireCode(t, req(t, s, "GET", "/api/v1/sessions?provider_id=p", nil), 400)
	change := map[string]any{"session_id": session.ID, "revision": 0, "title": "整理後的對話", "pinned": true, "archived": true}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", change), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", change), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/infer", inferRequest{SessionID: session.ID, Prompt: "continue"}), 409)
	if req(t, s, "GET", "/api/v1/sessions", nil).Body.String() != "[]\n" {
		t.Fatal("archived conversation in active list")
	}
	if json.Unmarshal(req(t, s, "GET", "/api/v1/sessions?state=ARCHIVED", nil).Body.Bytes(), &found) != nil || len(found) != 1 || !found[0].Pinned || found[0].Revision != 1 {
		t.Fatal("archive state not durable")
	}
	exported := req(t, s, "GET", "/api/v1/sessions/export?session_id="+session.ID, nil)
	requireCode(t, exported, 200)
	var doc struct {
		Session  Session   `json:"session"`
		Messages []Message `json:"messages"`
	}
	if json.Unmarshal(exported.Body.Bytes(), &doc) != nil || len(doc.Messages) != 1100 || doc.Messages[0].Content != "紀錄 0000 literal%needle" || !strings.Contains(doc.Messages[1099].Content, "[REDACTED]") || !doc.Session.Archived || doc.Session.Title != "整理後的對話" {
		t.Fatal("export truncated or incorrect")
	}
	if strings.Contains(exported.Body.String(), "top-secret-example-key") || !strings.HasPrefix(exported.Header().Get("Content-Disposition"), "attachment;") {
		t.Fatal("unsafe export")
	}
	md := req(t, s, "GET", "/api/v1/sessions/export?session_id="+session.ID+"&format=markdown", nil)
	requireCode(t, md, 200)
	if !strings.Contains(md.Body.String(), "紀錄 1099 [REDACTED]") {
		t.Fatal("markdown export truncated")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", map[string]any{"session_id": session.ID, "revision": 1, "archived": false}), 200)
	if !s.beginFlight(session.ID) {
		t.Fatal("flight")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", map[string]any{"session_id": session.ID, "revision": 2, "title": "locked"}), 409)
	s.endFlight(session.ID)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", map[string]any{"session_id": session.ID, "revision": 2, "title": "top-secret-example-key"}), 422)
	var messages, members, changes int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM session_changes").Scan(&changes)
	if messages != 1100 || members != 1 || changes != 2 {
		t.Fatal("archive/edit altered retained history or membership")
	}
	if _, e = s.Store.DB.Exec("INSERT INTO assistant_threads VALUES('agent',?,'scope',1)", session.ID); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/sessions/export?session_id="+session.ID, nil), 404)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", map[string]any{"session_id": session.ID, "revision": 2, "title": "agent"}), 404)
}

func TestBrowserConversationsWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	ticks := strings.Repeat(string(rune(96)), 3)
	reply := "# 測試回應\n\n**繁體中文**與 `程式碼`\n\n" + ticks + "js\nconsole.log('<img src=x onerror=alert(1)>');\n" + ticks + "\n\n| 模型 | 狀態 |\n| --- | --- |\n| M | 可用 |\n\n[安全連結](https://example.org/docs)\n[危險連結](javascript:alert)\n<img src=https://example.org/tracker onerror=alert(1)>"
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("wrong conversation scope")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "m" {
			t.Error("missing model")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		b, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]string{"content": reply}}}})
		w.Write([]byte("data: " + string(b) + "\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	s.AutoSync = false
	session := newSession(t, s, v)
	for i := 0; i < 105; i++ {
		e := s.saveMessage(context.Background(), session.ID, Message{Role: "user", Content: fmt.Sprintf("保留訊息 %03d", i)})
		if e != nil {
			t.Fatal(e)
		}
	}
	// Fault gates wrap the real handler. Successful reads and every mutation
	// still execute production SQL/CAS; a lost acknowledgement is injected only
	// after the actual handler has committed. No successful API JSON is mocked.
	var libraryReads, updates, abortedReads atomic.Int32
	var failRead, failMore, delayRead, delayWrite, loseAck, failRefresh, writeHeld atomic.Bool
	readRelease, writeRelease := make(chan struct{}, 1), make(chan struct{}, 1)
	handler := s.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__conversation-fixture/control" && r.Method == "POST" {
			var control struct {
				Mode string `json:"mode"`
			}
			if json.NewDecoder(r.Body).Decode(&control) != nil {
				http.Error(w, "invalid fixture control", 400)
				return
			}
			switch control.Mode {
			case "fail-read":
				failRead.Store(true)
			case "fail-more":
				failMore.Store(true)
			case "delay-read":
				delayRead.Store(true)
			case "delay-write":
				delayWrite.Store(true)
			case "release-write":
				writeRelease <- struct{}{}
			case "lose-ack":
				loseAck.Store(true)
			case "fail-refresh":
				failRefresh.Store(true)
			case "seed-pages":
				tx, err := s.Store.DB.Begin()
				if err != nil {
					t.Error(err)
					http.Error(w, "fixture setup failed", 500)
					return
				}
				defer tx.Rollback()
				for i := 0; i < 52; i++ {
					id, title := fmt.Sprintf("conversation-page-%02d", i), fmt.Sprintf("分頁對話 %02d", i)
					if i == 51 {
						title += strings.Repeat(" 很長的研究紀錄", 16)
					}
					if _, err = tx.Exec("INSERT INTO sessions(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at) VALUES(?,?,?,?,?,?,?,?,?)", id, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, title, now()+int64(i)); err == nil {
						_, err = tx.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,'user',?,?)", "page-message-"+id, id, "分頁內容與 <img src=x onerror=alert(1)> 保留為文字", now())
					}
					if err != nil {
						t.Error(err)
						http.Error(w, "fixture setup failed", 500)
						return
					}
				}
				if err = tx.Commit(); err != nil {
					t.Error(err)
					http.Error(w, "fixture setup failed", 500)
					return
				}
			default:
				http.Error(w, "unknown fixture control", 400)
				return
			}
			jsonOK(w, 200, map[string]bool{"ok": true})
			return
		}
		if r.URL.Path == "/__conversation-fixture/state" {
			var title string
			var changes, messages, revision int
			s.Store.DB.QueryRow("SELECT title FROM sessions WHERE id=?", session.ID).Scan(&title)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM session_changes WHERE session_id=?", session.ID).Scan(&changes)
			s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages WHERE session_id=?", session.ID).Scan(&messages)
			s.Store.DB.QueryRow("SELECT revision FROM session_metadata WHERE session_id=?", session.ID).Scan(&revision)
			jsonOK(w, 200, map[string]any{"reads": libraryReads.Load(), "updates": updates.Load(), "aborted_reads": abortedReads.Load(), "write_held": writeHeld.Load(), "title": title, "changes": changes, "messages": messages, "revision": revision})
			return
		}
		if r.URL.Path == "/api/v1/providers" && r.Method == "GET" && failRefresh.Swap(false) {
			http.Error(w, "fixture refresh unavailable", 503)
			return
		}
		if r.URL.Path == "/api/v1/sessions" && r.Method == "GET" && r.URL.Query().Get("limit") == "50" && r.URL.Query().Has("q") {
			libraryReads.Add(1)
			if failRead.Swap(false) || r.URL.Query().Get("offset") == "50" && failMore.Swap(false) {
				http.Error(w, "fixture conversation read unavailable", 503)
				return
			}
			if r.URL.Query().Get("q") == "慢速查詢" && delayRead.Swap(false) {
				select {
				case <-readRelease:
				case <-r.Context().Done():
					abortedReads.Add(1)
					return
				}
			}
		}
		if r.URL.Path == "/api/v1/sessions/update" && r.Method == "POST" {
			updates.Add(1)
			if delayWrite.Swap(false) {
				writeHeld.Store(true)
				select {
				case <-writeRelease:
				case <-r.Context().Done():
					writeHeld.Store(false)
					return
				}
				writeHeld.Store(false)
			}
			if loseAck.Swap(false) {
				result := httptest.NewRecorder()
				handler.ServeHTTP(result, r)
				if result.Code != 200 {
					t.Errorf("lost-ack fixture did not commit: %d", result.Code)
				}
				http.Error(w, "fixture acknowledgement lost after real commit", 502)
				return
			}
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/conversations.cjs", app.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+s.Token, "MODELATLAS_CONVERSATION="+session.ID, "MODELATLAS_REPLY="+reply)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("conversation browser: %v\n%s", e, out)
	}
	if calls.Load() != 1 {
		t.Fatalf("management/history caused %d inference requests", calls.Load())
	}
	t.Log(string(out))
}
