package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net"
	"net/url"
	"strings"
	"time"
)

// Local admission state is deliberately absent from workspaceTables and
// portable backups. Synced research history cannot enable or clear this gate.
const researchOriginSchema = `CREATE TABLE IF NOT EXISTS research_origin_cooldowns(
 origin TEXT PRIMARY KEY,not_before INTEGER NOT NULL,observed_at INTEGER NOT NULL,
 http_status INTEGER NOT NULL,failure_count INTEGER NOT NULL);`

type researchOriginWait struct {
	Origin        string `json:"origin"`
	NextAttemptAt int64  `json:"next_attempt_at"`
	HTTPStatus    int    `json:"http_status"`
}

func (e *researchOriginWait) Error() string { return "public origin cooling down" }

func researchPublicOrigin(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || !publicURL(raw) {
		return ""
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	port := u.Port()
	if port != "" && port != "443" {
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return "https://" + host
}

func (s *Server) checkResearchOrigin(ctx context.Context, tx *sql.Tx, origin string) error {
	if origin == "" {
		return nil
	}
	wait := &researchOriginWait{Origin: origin}
	err := tx.QueryRowContext(ctx, "SELECT not_before,http_status FROM research_origin_cooldowns WHERE origin=?", origin).Scan(&wait.NextAttemptAt, &wait.HTTPStatus)
	if err != nil && err != sql.ErrNoRows {
		return errors.New("public cooldown storage unavailable")
	}
	if err == nil && wait.NextAttemptAt > s.scheduleTime().UnixMilli() {
		return wait
	}
	return nil
}

// Persist a remote failure before the next task can reserve its HTTP budget.
// A late failure can extend a wait, never shorten a newer Retry-After floor.
func (s *Server) recordResearchOriginFailure(origin string, failure *researchHTTPFailure) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return errors.New("public cooldown storage unavailable")
	}
	defer tx.Rollback()
	var failures int
	err = tx.QueryRowContext(ctx, "SELECT failure_count FROM research_origin_cooldowns WHERE origin=?", origin).Scan(&failures)
	if err != nil && err != sql.ErrNoRows {
		return errors.New("public cooldown storage unavailable")
	}
	stamp := s.scheduleTime().UnixMilli()
	jitter := int64([]byte(contentHash([]byte(origin)))[0] % 6)
	delay := int64(30*(1<<min(max(failures, 0), 6))) + jitter
	next := max(failure.NotBefore, stamp+delay*1000)
	_, err = tx.ExecContext(ctx, `INSERT INTO research_origin_cooldowns VALUES(?,?,?,?,1)
 ON CONFLICT(origin) DO UPDATE SET not_before=MAX(not_before,excluded.not_before),observed_at=excluded.observed_at,http_status=excluded.http_status,failure_count=MIN(failure_count+1,64)`, origin, next, stamp, failure.Status)
	if err != nil {
		return errors.New("public cooldown storage unavailable")
	}
	if err = tx.QueryRowContext(ctx, "SELECT not_before FROM research_origin_cooldowns WHERE origin=?", origin).Scan(&failure.NotBefore); err != nil || tx.Commit() != nil {
		return errors.New("public cooldown storage unavailable")
	}
	return nil
}

// A successful request admitted before another request failed cannot clear
// that newer cooldown. No response body, header or credential is retained.
func (s *Server) researchOriginSucceeded(origin string, admittedAt int64) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_origin_cooldowns SET failure_count=0 WHERE origin=? AND not_before<=?", origin, admittedAt)
}

func readResearchOriginWait(raw []byte) *researchOriginWait {
	var envelope struct {
		Wait *researchOriginWait `json:"origin_wait"`
	}
	if json.Unmarshal(raw, &envelope) != nil {
		return nil
	}
	return envelope.Wait
}
