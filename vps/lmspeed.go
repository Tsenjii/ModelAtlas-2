package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

const lmspeedOrigin = "https://lmspeed.net"

func (s *Server) saveLmspeedKey(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Secret string `json:"secret"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if len(v.Secret) < 8 || len(v.Secret) > 8192 || strings.ContainsAny(v.Secret, "\r\n\x00") {
		http.Error(w, "invalid key", 400)
		return
	}
	enc, err := s.encrypt(v.Secret)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if _, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO integration_credentials VALUES('LMSPEED',?) ON CONFLICT(name) DO UPDATE SET encrypted_key=excluded.encrypted_key", enc); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]bool{"configured": true})
}
func (s *Server) lmspeedImport(ctx context.Context, base, key, locale string) (int, error) {
	// Test fixture origin is only provided by tests, never by the HTTP API or a recipe.
	if !oneOf(locale, "en", "zh", "ru") {
		return 0, errors.New("invalid locale")
	}
	day := time.Now().UTC().Format("2006-01-02")
	res, err := s.Store.DB.ExecContext(ctx, "INSERT INTO request_budgets VALUES('LMSPEED',?,1) ON CONFLICT(name,date) DO UPDATE SET used=used+1 WHERE used<1000", day)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return 0, errors.New("daily budget exhausted")
	}
	p := Provider{ID: "lmspeed", BaseURL: base, AuthMode: "API_KEY"}
	cfg := ProviderConfig{AuthMode: "API_KEY", AuthHeader: "x-api-key"}
	rawURL := strings.TrimRight(base, "/") + "/api/v1/models?page=1&page_size=50&sort=popular&locale=" + locale + "&include=capabilities"
	root, _, err := s.getJSON(ctx, p, cfg, rawURL, key, 1<<20)
	if err != nil {
		return 0, err
	}
	data, err := getNewAPIData(root)
	if err != nil {
		return 0, err
	}
	var models []map[string]json.RawMessage
	if json.Unmarshal(data["models"], &models) != nil || models == nil {
		return 0, errors.New("invalid LMSpeed models")
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	count := 0
	for _, row := range models {
		if count >= 1000 {
			break
		}
		slug := safeText(stringField(row, "slug"), 256, key)
		if slug == "" {
			continue
		}
		facts := map[string]any{"slug": slug, "attribution": lmspeedOrigin, "source": "LMSPEED", "units": "EXTERNAL_UNVERIFIED", "fresh_until": now() + int64(24*time.Hour/time.Millisecond)}
		for _, field := range []string{"name", "developer", "description", "lmspeed_url"} {
			v := safeText(stringField(row, field), 4096, key)
			if v != "" {
				facts[field] = v
			}
		}
		for _, field := range []string{"context_window_tokens", "max_output_tokens"} {
			if v := intField(row, field); v != nil && *v > 0 {
				facts[field] = *v
			}
		}
		facts["capabilities"] = safeStrings(row["capabilities"], key, 64)
		var stats, perf map[string]json.RawMessage
		_ = json.Unmarshal(row["stats"], &stats)
		_ = json.Unmarshal(row["performance_summary"], &perf)
		for _, field := range []string{"average_tokens_per_second", "average_first_token_latency"} {
			v := scalarField(stats, field)
			if v == nil {
				v = scalarField(perf, field)
			}
			if v != nil {
				facts[field] = v
			}
		}
		// Opaque references remain external hints. They never become official price/free facts.
		for _, field := range []string{"pricing_summary", "benchmark_summary"} {
			raw := string(row[field])
			if raw != "" && safeText(raw, 4096, key) == raw {
				facts[field+"_ref"] = raw
			}
		}
		model := stringField(row, "name")
		if model == "" {
			model = slug
		}
		raw, _ := json.Marshal(facts)
		if _, err = tx.ExecContext(ctx, "INSERT INTO external_models VALUES(?,?,'LMSPEED',?,?)", newID(), canonicalID(model), string(raw), now()); err != nil {
			return 0, err
		}
		count++
	}
	if tx.Commit() != nil {
		return 0, errors.New("persistence failed")
	}
	return count, nil
}
func (s *Server) syncLmspeed(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Locale string `json:"locale"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.Locale == "" {
		v.Locale = "zh"
	}
	var enc string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT encrypted_key FROM integration_credentials WHERE name='LMSPEED'").Scan(&enc) != nil {
		http.Error(w, "configure LMSpeed key first", 422)
		return
	}
	key, err := s.decrypt(enc)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	count, err := s.lmspeedImport(r.Context(), lmspeedOrigin, key, v.Locale)
	if err != nil {
		http.Error(w, "LMSpeed sync unavailable; previous observations preserved", 502)
		return
	}
	jsonOK(w, 200, map[string]any{"models_seen": count, "source": "LMSPEED", "attribution": lmspeedOrigin})
}
func (s *Server) externalFor(ctx context.Context, canonical string) []map[string]any {
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT facts_json,observed_at FROM external_models WHERE canonical_id=? ORDER BY rowid DESC LIMIT 20", canonical)
	if err != nil {
		return []map[string]any{}
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var raw string
		var at int64
		if rows.Scan(&raw, &at) == nil {
			var facts map[string]any
			if json.Unmarshal([]byte(raw), &facts) == nil {
				facts["observed_at"] = at
				out = append(out, facts)
			}
		}
	}
	return out
}
func (s *Server) listExternal(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT canonical_id,facts_json,observed_at FROM external_models ORDER BY rowid DESC LIMIT 200")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var canonical, raw string
		var at int64
		if rows.Scan(&canonical, &raw, &at) == nil {
			var facts map[string]any
			if json.Unmarshal([]byte(raw), &facts) == nil {
				out = append(out, map[string]any{"canonical_id": canonical, "facts": facts, "observed_at": at})
			}
		}
	}
	jsonOK(w, 200, out)
}
