package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

// Only linked into the opt-in test executable. Each Android test gets a fresh
// real Go hub so a large history never changes the older fixture's workload.
func workspaceAndroidChunkControl(t *testing.T, certificate tls.Certificate, admin string) (string, func()) {
	t.Helper()
	type child struct {
		server *http.Server
		store  *Store
	}
	var mu sync.Mutex
	children := map[string]child{}
	authorized := func(r *http.Request) bool {
		if r.Header.Get("Authorization") == "Bearer "+admin {
			return true
		}
		user, password, ok := r.BasicAuth()
		return ok && user == "admin" && password == admin
	}
	controller := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r) {
			http.Error(w, "fixture authorization required", 401)
			return
		}
		if r.Method == "POST" && r.URL.Path == "/new" {
			hub := testServer(t)
			scope := seed(t, hub, "https://provider.example/v1")
			requireCode(t, req(t, hub, "POST", "/api/v1/my-models", scope), 201)
			actual := hub.Handler()
			fixture := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if !strings.HasPrefix(r.URL.Path, "/__chunks/") {
					actual.ServeHTTP(w, r)
					return
				}
				if !authorized(r) {
					http.Error(w, "fixture authorization required", 401)
					return
				}
				switch r.URL.Path {
				case "/__chunks/seed":
					if r.Method != "POST" {
						http.Error(w, "POST required", 405)
						return
					}
					var in struct {
						ID    string `json:"id"`
						Bytes int    `json:"bytes"`
					}
					if !decodeLimit(w, r, &in, 4096) {
						return
					}
					if !validID(in.ID) || len(in.ID) > 64 || in.Bytes < 1024 || in.Bytes > workspaceChunkRecordBytes-4096 {
						http.Error(w, "bounded owned fixture required", 422)
						return
					}
					head, tail := "source/"+in.ID+"/", "/尾界"
					content := head + strings.Repeat("x", in.Bytes-len(head)-len(tail)) + tail
					session, message := "chunk-session-"+in.ID, "chunk-message-"+in.ID
					tx, e := hub.Store.DB.Begin()
					if e != nil {
						http.Error(w, "fixture database", 500)
						return
					}
					defer tx.Rollback()
					if _, e = tx.Exec("INSERT INTO sessions VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','保留的大資料歷史',?)", session, now()); e == nil {
						_, e = tx.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,'user',?,?)", message, session, content, now())
					}
					if e == nil {
						e = tx.Commit()
					}
					if e != nil {
						http.Error(w, "fixture insertion failed", 409)
						return
					}
					body, e := encodeRequestJSON(map[string]any{"content": content}, workspaceChunkRecordBytes)
					if e != nil {
						http.Error(w, "fixture encoding", 500)
						return
					}
					hash := sha256.New()
					hash.Write([]byte{0})
					_, e = io.Copy(hash, body.reader())
					if e != nil {
						http.Error(w, "fixture fingerprint", 500)
						return
					}
					jsonOK(w, 200, map[string]any{"message": message, "id": workspaceJSON([]string{message}), "content_bytes": len(content), "data_fingerprint": hex.EncodeToString(hash.Sum(nil))})
				case "/__chunks/state":
					result := map[string]any{}
					for _, table := range []string{"my_models", "messages", "telemetry", "workspace_changes", "workspace_transfer_parts"} {
						var n int
						_ = hub.Store.DB.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&n)
						result[table] = n
					}
					var acked, done int
					_ = hub.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_transfers WHERE role='IN' AND state='ACKED'").Scan(&acked)
					_ = hub.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_transfers WHERE role='IN' AND state='DONE'").Scan(&done)
					result["acked"] = acked
					result["done"] = done
					if id := r.URL.Query().Get("observation"); id != "" {
						var raw string
						var deleted int
						if hub.Store.DB.QueryRow("SELECT data_json,deleted FROM workspace_native_archive WHERE kind='android:external_observations' AND id=?", workspaceJSON([]string{id})).Scan(&raw, &deleted) == nil {
							var data map[string]any
							decoder := json.NewDecoder(strings.NewReader(raw))
							decoder.UseNumber()
							if decoder.Decode(&data) == nil {
								body, e := encodeRequestJSON(data, workspaceChunkRecordBytes)
								if e == nil {
									h := sha256.New()
									h.Write([]byte{byte(deleted)})
									_, _ = io.Copy(h, body.reader())
									result["observation_fingerprint"] = hex.EncodeToString(h.Sum(nil))
									result["observation_bytes"] = len(raw)
								}
							}
						}
					}
					jsonOK(w, 200, result)
				default:
					http.NotFound(w, r)
				}
			})
			listener, e := net.Listen("tcp", "0.0.0.0:0")
			if e != nil {
				http.Error(w, "fixture listener", 500)
				return
			}
			server := &http.Server{Handler: fixture, ReadHeaderTimeout: 10 * time.Second}
			id := newID()
			mu.Lock()
			children[id] = child{server, hub.Store}
			mu.Unlock()
			go server.Serve(tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS12}))
			_, port, _ := net.SplitHostPort(listener.Addr().String())
			jsonOK(w, 200, map[string]any{"id": id, "url": "https://10.0.2.2:" + port})
		} else if r.Method == "POST" && r.URL.Path == "/close" {
			var in struct {
				ID string `json:"id"`
			}
			if !decodeLimit(w, r, &in, 4096) {
				return
			}
			mu.Lock()
			owned, ok := children[in.ID]
			if ok {
				delete(children, in.ID)
			}
			mu.Unlock()
			if !ok {
				http.NotFound(w, r)
				return
			}
			_ = owned.server.Close()
			_ = owned.store.Close()
			jsonOK(w, 200, map[string]any{"closed": true})
		} else {
			http.NotFound(w, r)
		}
	})
	listener, e := net.Listen("tcp", "0.0.0.0:17889")
	if e != nil {
		t.Fatal(e)
	}
	server := &http.Server{Handler: controller, ReadHeaderTimeout: 10 * time.Second}
	go server.Serve(tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS12}))
	return "https://10.0.2.2:17889", func() {
		_ = server.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, owned := range children {
			_ = owned.server.Close()
			_ = owned.store.Close()
		}
	}
}
