package main

import (
	"context"
	"net/http"
	"sync"
	"time"
)

// The zero value is usable. A channel lets HTTP callers wait for the same
// background transfer without leaking a goroutine when the request is canceled.
type workspaceMutex struct {
	once      sync.Once
	available chan struct{}
}

func (m *workspaceMutex) initialize() {
	m.once.Do(func() { m.available = make(chan struct{}, 1); m.available <- struct{}{} })
}
func (m *workspaceMutex) Lock() { m.initialize(); <-m.available }
func (m *workspaceMutex) TryLock() bool {
	m.initialize()
	select {
	case <-m.available:
		return true
	default:
		return false
	}
}
func (m *workspaceMutex) lockContext(ctx context.Context) bool {
	m.initialize()
	if ctx.Err() != nil {
		return false
	}
	select {
	case <-m.available:
		return true
	case <-ctx.Done():
		return false
	}
}
func (m *workspaceMutex) Unlock() {
	m.initialize()
	select {
	case m.available <- struct{}{}:
	default:
		panic("unlock of unlocked workspace mutex")
	}
}
func (s *Server) workspaceWait(w http.ResponseWriter, r *http.Request) bool {
	ctx, cancel := context.WithTimeout(r.Context(), 35*time.Second)
	defer cancel()
	if s.workspaceMu.lockContext(ctx) {
		return true
	}
	if r.Context().Err() != nil {
		http.Error(w, "sync request canceled", http.StatusRequestTimeout)
	} else {
		http.Error(w, "sync still running; retry shortly", http.StatusConflict)
	}
	return false
}
