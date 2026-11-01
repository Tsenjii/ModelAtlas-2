package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestWorkspaceManualSyncJoinsRealBackgroundSyncAndCanceledWaiterDoesNotConsumeLock(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	scope := seed(t, hub, "https://provider.example/v1")
	requireCode(t, req(t, hub, "POST", "/api/v1/my-models", scope), 201)
	blocked, release := make(chan struct{}), make(chan struct{})
	var blockOnce, releaseOnce sync.Once
	base := hub.Handler()
	host := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/workspace/exchange" {
			blockOnce.Do(func() { close(blocked); <-release })
		}
		base.ServeHTTP(w, r)
	}))
	defer host.Close()
	defer releaseOnce.Do(func() { close(release) })
	workspacePairForTest(t, hub, peer, host, true)
	workerDone := make(chan struct{})
	go func() { peer.workspaceTick(context.Background()); close(workerDone) }()
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		t.Fatal("Real background exchange never started")
	}
	call := func(ctx context.Context) (*httptest.ResponseRecorder, <-chan struct{}) {
		r := httptest.NewRequest("POST", "/api/v1/workspace/sync", strings.NewReader(`{}`)).WithContext(ctx)
		r.Header.Set("Authorization", "Bearer "+peer.Token)
		r.Header.Set("Content-Type", "application/json")
		w, done := httptest.NewRecorder(), make(chan struct{})
		go func() { peer.Handler().ServeHTTP(w, r); close(done) }()
		return w, done
	}
	w, done := call(context.Background())
	select {
	case <-done:
		t.Fatalf("Manual sync returned %d while its real background transfer was still running", w.Code)
	case <-time.After(100 * time.Millisecond):
	}
	cancelCtx, cancel := context.WithCancel(context.Background())
	canceled, canceledDone := call(cancelCtx)
	cancel()
	select {
	case <-canceledDone:
	case <-time.After(2 * time.Second):
		t.Fatal("Canceled waiter did not stop")
	}
	requireCode(t, canceled, http.StatusRequestTimeout)
	releaseOnce.Do(func() { close(release) })
	select {
	case <-workerDone:
	case <-time.After(3 * time.Second):
		t.Fatal("Background transfer did not finish")
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Manual request did not resume")
	}
	requireCode(t, w, 200)
	var count int
	if err := peer.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&count); err != nil || count != 1 {
		t.Fatal("Manual completion did not observe imported membership")
	}
	workspaceRunForTest(t, peer)
}
