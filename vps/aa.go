package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const aaOrigin = "https://artificialanalysis.ai"
const aaPath = "/api/v2/language/models/free"
const braveOrigin = "https://api.search.brave.com"

func (s *Server) saveResearchIntegration(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Name   string `json:"name"`
		Secret string `json:"secret"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !oneOf(v.Name, "AA", "BRAVE_SEARCH") || len(v.Secret) < 8 || len(v.Secret) > 8192 || strings.ContainsAny(v.Secret, "\x00\r\n") {
		http.Error(w, "invalid integration key", 400)
		return
	}
	encrypted, e := s.encrypt(v.Secret)
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if _, e = s.Store.DB.ExecContext(r.Context(), "INSERT INTO integration_credentials VALUES(?,?) ON CONFLICT(name) DO UPDATE SET encrypted_key=excluded.encrypted_key", v.Name, encrypted); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"name": v.Name, "configured": true})
}
func (s *Server) integrationKey(ctx context.Context, name string) (string, error) {
	var encrypted string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT encrypted_key FROM integration_credentials WHERE name=?", name).Scan(&encrypted)
	if e != nil {
		return "", errors.New("integration key not configured")
	}
	return s.decrypt(encrypted)
}
func (s *Server) chargeDaily(ctx context.Context, name string, limit int) error {
	day := time.Now().UTC().Format("2006-01-02")
	result, e := s.Store.DB.ExecContext(ctx, "INSERT INTO request_budgets VALUES(?,?,1) ON CONFLICT(name,date) DO UPDATE SET used=used+1 WHERE used<?", name, day, limit)
	if e != nil {
		return e
	}
	n, _ := result.RowsAffected()
	if n != 1 {
		return errors.New("daily request budget exhausted")
	}
	return nil
}

type AAModel struct {
	ID    string  `json:"id"`
	Slug  string  `json:"slug"`
	Name  string  `json:"name"`
	Rank  int     `json:"rank"`
	Score float64 `json:"score"`
}
type AASnapshot struct {
	ID           string    `json:"id"`
	IndexVersion string    `json:"index_version"`
	Source       string    `json:"source"`
	ObservedAt   int64     `json:"observed_at"`
	ValidUntil   int64     `json:"valid_until"`
	Models       []AAModel `json:"models"`
}

func (s *Server) fetchAA(ctx context.Context, origin, key string) (snapshot AASnapshot, failure error) {
	snapshot = AASnapshot{ID: newID(), Source: aaOrigin + aaPath, ObservedAt: now(), ValidUntil: now() + int64(24*time.Hour/time.Millisecond), Models: []AAModel{}}
	defer func() {
		if failure != nil {
			save, stop := context.WithTimeout(context.Background(), 2*time.Second)
			defer stop()
			version := snapshot.IndexVersion
			if version == "" {
				version = "UNKNOWN"
			}
			_, _ = s.Store.DB.ExecContext(save, "INSERT OR IGNORE INTO aa_snapshots VALUES(?,?,'FAILED',?,?,0,'')", snapshot.ID, version, snapshot.Source, snapshot.ObservedAt)
		}
	}()
	checkpoint := &aaResearchCheckpoint{Snapshot: snapshot, NextPage: 1}
	for checkpoint.TotalPages == 0 || checkpoint.NextPage <= checkpoint.TotalPages {
		p := Provider{BaseURL: origin, AuthMode: "API_KEY"}
		cfg := ProviderConfig{AuthMode: "API_KEY", AuthHeader: "x-api-key"}
		root, _, e := s.getJSON(context.WithValue(ctx, integrationHeaderKey{}, "AA"), p, cfg, origin+aaPath+fmt.Sprintf("?page=%d", checkpoint.NextPage), key, 1<<20)
		if e != nil {
			return checkpoint.Snapshot, e
		}
		if e = checkpoint.acceptPage(root, key); e != nil {
			return checkpoint.Snapshot, e
		}
	}
	snapshot = checkpoint.Snapshot
	return snapshot, s.commitAARanking(ctx, &snapshot, checkpoint.Hashes, "", nil)
}

func (s *Server) syncAA(w http.ResponseWriter, r *http.Request) {
	if !s.beginFlight("AA_SYNC") {
		http.Error(w, "AA sync busy", 409)
		return
	}
	defer s.endFlight("AA_SYNC")
	key, e := s.integrationKey(r.Context(), "AA")
	if e != nil {
		http.Error(w, "AA key required", 422)
		return
	}
	snapshot, e := s.fetchAA(r.Context(), aaOrigin, key)
	if e != nil {
		http.Error(w, "AA sync incomplete; previous evidence retained", 502)
		return
	}
	s.reconcileAllCandidates(r.Context())
	jsonOK(w, 200, snapshot)
}
func (s *Server) aaSnapshot(ctx context.Context) (AASnapshot, error) {
	var v AASnapshot
	e := s.Store.DB.QueryRowContext(ctx, "SELECT id,index_version,source_url,observed_at,valid_until FROM aa_snapshots WHERE status='VALID' ORDER BY rowid DESC LIMIT 1").Scan(&v.ID, &v.IndexVersion, &v.Source, &v.ObservedAt, &v.ValidUntil)
	if e != nil {
		return v, e
	}
	v.Models = []AAModel{}
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT aa_id,slug,name,rank,score FROM aa_models WHERE snapshot_id=? ORDER BY rank", v.ID)
	if e != nil {
		return v, e
	}
	defer rows.Close()
	for rows.Next() {
		var m AAModel
		if rows.Scan(&m.ID, &m.Slug, &m.Name, &m.Rank, &m.Score) == nil {
			v.Models = append(v.Models, m)
		}
	}
	return v, rows.Err()
}
func (s *Server) listAA(w http.ResponseWriter, r *http.Request) {
	v, e := s.aaSnapshot(r.Context())
	if e != nil {
		jsonOK(w, 200, map[string]any{"status": "UNKNOWN", "models": []AAModel{}, "attribution": aaOrigin})
		return
	}
	jsonOK(w, 200, map[string]any{"status": map[bool]string{true: "FRESH", false: "STALE"}[v.ValidUntil > now()], "snapshot": v, "attribution": aaOrigin})
}

func (s *Server) aaModelScore(ctx context.Context, c Candidate) (float64, int, bool) {
	// Search/grouping aliases cannot lend benchmark identity or the top-four exception.
	canonical := strings.Trim(normalizeSeparators.ReplaceAllString(strings.ToLower(strings.TrimSpace(c.ModelID)), "-"), "-")
	var score float64
	var rank int
	err := s.Store.DB.QueryRowContext(ctx, `SELECT m.score,m.rank FROM aa_models m JOIN aa_snapshots a ON a.id=m.snapshot_id WHERE a.id=(SELECT id FROM aa_snapshots WHERE status='VALID' ORDER BY rowid DESC LIMIT 1) AND a.valid_until>? AND (lower(m.slug)=lower(?) OR lower(m.name)=lower(?) OR lower(m.aa_id)=lower(?) OR lower(m.slug)=lower(?)) ORDER BY m.rank LIMIT 1`, now(), c.ModelID, c.ModelID, c.ModelID, canonical).Scan(&score, &rank)
	return score, rank, err == nil
}
func (s *Server) aaTop4(ctx context.Context, c Candidate) bool {
	_, rank, valid := s.aaModelScore(ctx, c)
	return valid && rank <= 4
}
