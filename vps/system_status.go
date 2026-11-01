package main

import (
	"context"
	"net/http"
	"os"
	"runtime"
)

type systemState struct {
	Status          string        `json:"status"`
	Version         binaryVersion `json:"version"`
	DatabaseSchema  int           `json:"database_schema"`
	VaultConfigured bool          `json:"vault_configured"`
	VaultVerified   bool          `json:"vault_verified"`
	Maintenance     bool          `json:"maintenance"`
	DatabaseBytes   int64         `json:"database_bytes"`
	MemoryBytes     uint64        `json:"memory_bytes"`
}

func (s *Server) maintenancePending() bool {
	if s.maintenanceFile == "" {
		return false
	}
	_, err := os.Stat(s.maintenanceFile)
	return !os.IsNotExist(err) // Fail closed if the gate cannot be inspected.
}

func (s *Server) maintenanceHandler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.maintenancePending() && !((r.Method == "GET" || r.Method == "HEAD") && (r.URL.Path == "/healthz" || r.URL.Path == "/api/v1/system")) {
			security(w)
			w.Header().Set("Retry-After", "15")
			http.Error(w, "服務更新中，請稍後重新整理。", http.StatusServiceUnavailable)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) systemState(ctx context.Context) systemState {
	state := systemState{Status: "ok", Version: versionInfo(), VaultConfigured: len(s.Key) == 32, VaultVerified: s.vaultVerified, Maintenance: s.maintenancePending()}
	if s.Store.DB.QueryRowContext(ctx, "PRAGMA user_version").Scan(&state.DatabaseSchema) != nil || s.Store.DB.PingContext(ctx) != nil {
		state.Status = "unavailable"
	}
	if info, err := os.Stat(s.Store.path); err == nil {
		state.DatabaseBytes = info.Size()
	}
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	state.MemoryBytes = memory.Sys
	return state
}

func (s *Server) systemStatus(w http.ResponseWriter, r *http.Request) {
	state := s.systemState(r.Context())
	code := http.StatusOK
	if state.Status != "ok" {
		code = http.StatusServiceUnavailable
	}
	jsonOK(w, code, state)
}
