package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"strconv"
	"time"
)

type integrationHeaderKey struct{}

// AA V2 uses a fixed 24h window anchored at the first request, not UTC midnight.
// Local counts and provider-reported remaining are distinct. Unknown remaining
// stays NULL; rotating a key does not reset the shared integration budget.
func (s *Server) chargeIntegrationWindow(ctx context.Context, name string, defaultLimit int) error {
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if e = s.chargeIntegrationWindowTx(ctx, tx, name, defaultLimit); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Server) chargeIntegrationWindowTx(ctx context.Context, tx *sql.Tx, name string, defaultLimit int) error {
	stamp := s.scheduleTime().UnixMilli()
	var start, reset int64
	var used, limit int
	var remaining sql.NullInt64
	e := tx.QueryRowContext(ctx, "SELECT window_start,reset_at,used,request_limit,provider_remaining FROM integration_windows WHERE name=?", name).Scan(&start, &reset, &used, &limit, &remaining)
	if e != nil && e != sql.ErrNoRows {
		return e
	}
	if e == sql.ErrNoRows || reset <= stamp {
		start = stamp
		reset = stamp + int64(24*time.Hour/time.Millisecond)
		used = 0
		limit = defaultLimit
		remaining = sql.NullInt64{}
	}
	if used >= limit || remaining.Valid && remaining.Int64 == 0 {
		return errors.New("integration fixed-window budget exhausted")
	}
	used++
	if remaining.Valid {
		remaining.Int64--
	}
	var value any
	if remaining.Valid {
		value = remaining.Int64
	}
	_, e = tx.ExecContext(ctx, "INSERT INTO integration_windows VALUES(?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET window_start=excluded.window_start,reset_at=excluded.reset_at,used=excluded.used,request_limit=excluded.request_limit,provider_remaining=excluded.provider_remaining", name, start, reset, used, limit, value)
	if e != nil {
		return e
	}
	return nil
}
func (s *Server) observeIntegrationHeaders(ctx context.Context, name string, h http.Header) {
	limit, e1 := strconv.ParseInt(h.Get("X-RateLimit-Limit"), 10, 64)
	remaining, e2 := strconv.ParseInt(h.Get("X-RateLimit-Remaining"), 10, 64)
	reset, e3 := strconv.ParseInt(h.Get("X-RateLimit-Reset"), 10, 64)
	if e1 != nil || e2 != nil || e3 != nil || limit < 1 || limit > 100000 || remaining < 0 || remaining > limit || reset <= time.Now().Unix() || reset > time.Now().Add(48*time.Hour).Unix() {
		return
	}
	_, _ = s.Store.DB.ExecContext(ctx, "UPDATE integration_windows SET reset_at=?,window_start=?,request_limit=?,provider_remaining=? WHERE name=?", reset*1000, reset*1000-int64(24*time.Hour/time.Millisecond), limit, remaining, name)
}
