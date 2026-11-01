package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"testing"
)

func TestBrowserWorkspaceSyncWorkflow(t *testing.T) {
	if os.Getenv("MODELATLAS_BROWSER_TEST") != "1" {
		t.Skip("enable in browser CI job")
	}
	parallelBrowserFlow(t)
	hub, peer := testServer(t), testServer(t)
	v := seed(t, hub, "https://no-inference.example/v1")
	requireCode(t, req(t, hub, "POST", "/api/v1/my-models", v), 201)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	peer.Client = host.Client()
	handler := peer.Handler()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/__workspace_fixture/conflict" {
			_, a := hub.Store.DB.Exec("UPDATE providers SET name='中心新名稱' WHERE id='p'")
			_, b := peer.Store.DB.Exec("UPDATE providers SET name='手機離線名稱' WHERE id='p'")
			if a != nil || b != nil {
				t.Error("fixture edit failed")
			}
			jsonOK(w, 200, map[string]any{"ok": true})
			return
		}
		if r.URL.Path == "/__workspace_fixture/state" {
			var local, remote string
			var models, samples, conflicts int
			_ = peer.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&local)
			_ = hub.Store.DB.QueryRow("SELECT name FROM providers WHERE id='p'").Scan(&remote)
			_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&models)
			_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&samples)
			_ = peer.Store.DB.QueryRow("SELECT COUNT(*) FROM workspace_conflicts WHERE state='OPEN'").Scan(&conflicts)
			jsonOK(w, 200, map[string]any{"local": local, "remote": remote, "models": models, "samples": samples, "conflicts": conflicts})
			return
		}
		handler.ServeHTTP(w, r)
	}))
	defer app.Close()
	cmd := exec.Command("node", "e2e/workspace-sync.cjs", app.URL, host.URL)
	cmd.Env = append(os.Environ(), "MODELATLAS_TEST_TOKEN="+peer.Token)
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("workspace browser: %v\n%s", e, out)
	}
	t.Log(string(out))
}
