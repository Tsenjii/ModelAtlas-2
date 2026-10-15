package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"sort"
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
	allBytes := []byte{}
	seen := map[string]bool{}
	p := Provider{BaseURL: origin, AuthMode: "API_KEY"}
	cfg := ProviderConfig{AuthMode: "API_KEY", AuthHeader: "x-api-key"}
	for page := 1; page <= 10; page++ {
		if e := s.chargeIntegrationWindow(ctx, "AA", 100); e != nil {
			return snapshot, e
		}
		root, _, e := s.getJSON(context.WithValue(ctx, integrationHeaderKey{}, "AA"), p, cfg, origin+aaPath+fmt.Sprintf("?page=%d", page), key, 1<<20)
		if e != nil {
			return snapshot, e
		}
		var version json.Number
		if json.Unmarshal(root["intelligence_index_version"], &version) != nil || decimal(version.String()) == nil {
			return snapshot, errors.New("AA index version missing")
		}
		if snapshot.IndexVersion != "" && snapshot.IndexVersion != version.String() {
			return snapshot, errors.New("AA versions changed between pages")
		}
		snapshot.IndexVersion = version.String()
		var pagination struct {
			Page       int   `json:"page"`
			TotalPages int   `json:"total_pages"`
			HasMore    *bool `json:"has_more"`
		}
		if json.Unmarshal(root["pagination"], &pagination) != nil || pagination.Page != page || pagination.HasMore == nil || pagination.TotalPages < page || pagination.TotalPages > 10 {
			return snapshot, errors.New("AA incomplete pagination")
		}
		var data []struct {
			ID          string              `json:"id"`
			Slug        string              `json:"slug"`
			Name        string              `json:"name"`
			Evaluations map[string]*float64 `json:"evaluations"`
		}
		if json.Unmarshal(root["data"], &data) != nil || data == nil || len(data) > 200 {
			return snapshot, errors.New("AA invalid model page")
		}
		for _, v := range data {
			score := v.Evaluations["artificial_analysis_intelligence_index"]
			if score == nil {
				continue
			}
			if !validID(v.ID) || !validID(v.Slug) || safeText(v.Name, 200, key) != v.Name || v.Name == "" || *score < 0 || *score > 100 || math.IsNaN(*score) || math.IsInf(*score, 0) || seen[v.ID] {
				return snapshot, errors.New("AA model schema conflict")
			}
			seen[v.ID] = true
			snapshot.Models = append(snapshot.Models, AAModel{ID: v.ID, Slug: v.Slug, Name: v.Name, Score: *score})
		}
		pageRaw, _ := json.Marshal(root)
		allBytes = append(allBytes, []byte(contentHash(pageRaw))...)
		if !*pagination.HasMore {
			if pagination.TotalPages != page {
				return snapshot, errors.New("AA pagination conflict")
			}
			break
		}
		if page == 10 {
			return snapshot, errors.New("AA page budget exhausted")
		}
	}
	if len(snapshot.Models) < 4 {
		return snapshot, errors.New("AA leaderboard has fewer than four scored models")
	}
	sort.Slice(snapshot.Models, func(i, j int) bool {
		if snapshot.Models[i].Score == snapshot.Models[j].Score {
			return snapshot.Models[i].ID < snapshot.Models[j].ID
		}
		return snapshot.Models[i].Score > snapshot.Models[j].Score
	})
	for i := range snapshot.Models {
		snapshot.Models[i].Rank = i + 1
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return snapshot, e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, "INSERT INTO aa_snapshots VALUES(?,?,'VALID',?,?,?,?)", snapshot.ID, snapshot.IndexVersion, snapshot.Source, snapshot.ObservedAt, snapshot.ValidUntil, contentHash(allBytes)); e != nil {
		return snapshot, e
	}
	for _, v := range snapshot.Models {
		if _, e = tx.ExecContext(ctx, "INSERT INTO aa_models VALUES(?,?,?,?,?,?)", snapshot.ID, v.ID, v.Slug, v.Name, v.Rank, v.Score); e != nil {
			return snapshot, e
		}
	}
	return snapshot, tx.Commit()
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
