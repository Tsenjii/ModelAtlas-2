package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
)

func TestBrowserKiloFactoryAnonymousChatPriceGuardAndExistingWorkspaceRecovery(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	var catalogs, chats, installs atomic.Int32
	var paid atomic.Bool
	fresh, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("factory defaults borrowed authentication")
		}
		switch r.URL.Path {
		case "/api/v1/models":
			catalogs.Add(1)
			price := "0"
			if paid.Load() {
				price = "1"
			}
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"id": "nvidia/nemotron-3.5-lightning:free", "isFree": true, "pricing": map[string]string{"prompt": "0", "completion": price}}}})
		case "/api/v1/chat/completions":
			chats.Add(1)
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil || body["model"] != "nvidia/nemotron-3.5-lightning:free" {
				t.Error("factory browser sent a different model")
			}
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"KILO_BROWSER_OK\"}}]}\n\ndata: [DONE]\n\n"))
		default:
			t.Error("unexpected factory endpoint", r.URL.Path)
			w.WriteHeader(404)
		}
	})
	if added, e := fresh.installKiloFactory(context.Background(), true); e != nil || !added {
		t.Fatal(added, e)
	}
	legacy := testServer(t)
	seed(t, legacy, "https://existing.example/v1")
	if added, e := legacy.installKiloFactory(context.Background(), true); e != nil || added {
		t.Fatal("existing workspace changed automatically", e)
	}
	start := func(s *Server) *httptest.Server {
		production := s.Handler()
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/test/kilo/paid":
				paid.Store(true)
				w.WriteHeader(204)
				return
			case "/test/kilo/state":
				var samples, messages, members int
				s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&samples)
				s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages)
				s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
				json.NewEncoder(w).Encode(map[string]any{"catalogs": catalogs.Load(), "chats": chats.Load(), "installs": installs.Load(), "samples": samples, "messages": messages, "members": members})
				return
			}
			if r.Method == "POST" && r.URL.Path == "/api/v1/defaults/kilo" {
				// Playwright's route.fetch may first receive a Basic Auth
				// challenge. Count authenticated mutations, not the 401 probe.
				if _, password, ok := r.BasicAuth(); ok && password == s.Token {
					installs.Add(1)
				}
			}
			production.ServeHTTP(w, r)
		}))
	}
	app, oldApp := start(fresh), start(legacy)
	defer app.Close()
	defer oldApp.Close()
	cmd := exec.Command("node", "e2e/factory-kilo.cjs", app.URL, oldApp.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+fresh.Token, "MODELATLAS_LEGACY_TEST_TOKEN="+legacy.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Kilo factory Browser: %v\n%s", e, out)
	}
	if catalogs.Load() != 2 || chats.Load() != 1 || installs.Load() != 1 {
		t.Fatalf("factory workload changed: catalogs=%d chats=%d installs=%d", catalogs.Load(), chats.Load(), installs.Load())
	}
	assertSQLInt(t, fresh, 1, "SELECT COUNT(*) FROM telemetry WHERE outcome='SUCCESS'")
	assertSQLInt(t, fresh, 2, "SELECT COUNT(*) FROM messages")
	assertSQLInt(t, fresh, 14, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, legacy, 15, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM credentials WHERE provider_id='p' AND encrypted_key!=''")
	assertSQLInt(t, legacy, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM action_ledger WHERE action='INSTALL_FACTORY_DEFAULTS'")
	t.Log(string(out))
}
