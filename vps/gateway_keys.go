package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

type gatewayKeyInput struct {
	ID        string   `json:"id"`
	Label     string   `json:"label"`
	Models    []string `json:"models"`
	Daily     int      `json:"daily_limit"`
	ExpiresAt *int64   `json:"expires_at"`
}

func (s *Server) gatewayListKeys(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), `SELECT k.id,k.label,k.prefix,k.models_json,k.daily_limit,k.enabled,
		COALESCE(p.paused,0),k.created_at,p.expires_at,
		(SELECT COUNT(*) FROM gateway_requests r WHERE r.key_id=k.id AND r.created_at>=?)
		FROM gateway_keys k LEFT JOIN gateway_key_policies p ON p.key_id=k.id
		ORDER BY k.created_at DESC,k.id`, gatewayDayStart(time.Now()))
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	keys := []gatewayKey{}
	for rows.Next() {
		var k gatewayKey
		var models string
		if rows.Scan(&k.ID, &k.Label, &k.Prefix, &models, &k.DailyLimit, &k.Enabled, &k.Paused, &k.CreatedAt, &k.ExpiresAt, &k.UsedToday) != nil || json.Unmarshal([]byte(models), &k.Models) != nil {
			http.Error(w, "database error", 500)
			return
		}
		keys = append(keys, k)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, keys)
}

func (s *Server) readGatewayKeyInput(w http.ResponseWriter, r *http.Request, editing bool) (gatewayKeyInput, bool) {
	var in gatewayKeyInput
	if !decodeJSON(w, r, &in) {
		return in, false
	}
	in.Label = strings.TrimSpace(in.Label)
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return in, false
	}
	if in.Label == "" || safeText(in.Label, 120, secrets...) != in.Label || len(in.Models) < 1 || len(in.Models) > 100 ||
		in.Daily < 1 || in.Daily > 1_000_000 || editing && !validID(in.ID) {
		http.Error(w, "key label, 1–100 models and a daily limit are required", 400)
		return in, false
	}
	if in.ExpiresAt != nil && (*in.ExpiresAt <= now() || *in.ExpiresAt > 253402300799999) {
		http.Error(w, "expiration must be in the future", 400)
		return in, false
	}
	seen := make(map[string]bool, len(in.Models))
	for _, id := range in.Models {
		if !validID(id) || seen[id] {
			http.Error(w, "invalid or duplicate model", 400)
			return in, false
		}
		seen[id] = true
	}
	return in, true
}

func (s *Server) gatewayIssueKey(w http.ResponseWriter, r *http.Request) {
	in, ok := s.readGatewayKeyInput(w, r, false)
	if !ok {
		return
	}
	var entropy [32]byte
	if _, err := rand.Read(entropy[:]); err != nil {
		http.Error(w, "key generation unavailable", 503)
		return
	}
	token := gatewayKeyPrefix + base64.RawURLEncoding.EncodeToString(entropy[:])
	digest := sha256.Sum256([]byte(token))
	k := gatewayKey{ID: newID(), Label: in.Label, Prefix: token[:12], Models: in.Models,
		DailyLimit: in.Daily, Enabled: true, CreatedAt: now(), ExpiresAt: in.ExpiresAt}
	ctx := r.Context()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	models, _ := json.Marshal(k.Models)
	res, err := tx.ExecContext(ctx, `INSERT INTO gateway_keys(id,label,digest,prefix,models_json,daily_limit,enabled,created_at)
		SELECT ?,?,?,?,?,?,1,? WHERE (SELECT COUNT(*) FROM gateway_models WHERE enabled=1 AND id IN(SELECT value FROM json_each(?)))=?`,
		k.ID, k.Label, digest[:], k.Prefix, string(models), k.DailyLimit, k.CreatedAt, string(models), len(k.Models))
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := res.RowsAffected(); n != 1 {
		http.Error(w, "select published models", 422)
		return
	}
	if k.ExpiresAt != nil {
		_, err = tx.ExecContext(ctx, "INSERT INTO gateway_key_policies(key_id,expires_at) VALUES(?,?)", k.ID, k.ExpiresAt)
	}
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, struct {
		gatewayKey
		Token string `json:"token"`
	}{k, token})
}

func (s *Server) gatewayEditKey(w http.ResponseWriter, r *http.Request) {
	in, ok := s.readGatewayKeyInput(w, r, true)
	if !ok {
		return
	}
	ctx := r.Context()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	models, _ := json.Marshal(in.Models)
	res, err := tx.ExecContext(ctx, `UPDATE gateway_keys AS k SET label=?,models_json=?,daily_limit=?
		WHERE id=? AND enabled=1 AND (SELECT COUNT(*) FROM gateway_models
			WHERE id IN(SELECT value FROM json_each(?)) AND (enabled=1 OR id IN(SELECT value FROM json_each(k.models_json))))=?`,
		in.Label, string(models), in.Daily, in.ID, string(models), len(in.Models))
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := res.RowsAffected(); n != 1 {
		http.Error(w, "key or selected models unavailable", 422)
		return
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO gateway_key_policies(key_id,expires_at) VALUES(?,?)
		ON CONFLICT(key_id) DO UPDATE SET expires_at=excluded.expires_at`, in.ID, in.ExpiresAt)
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]string{"id": in.ID})
}

func (s *Server) gatewayPauseKey(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID     string `json:"id"`
		Paused *bool  `json:"paused"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validID(in.ID) || in.Paused == nil {
		http.Error(w, "valid key and pause state required", 400)
		return
	}
	res, err := s.Store.DB.ExecContext(r.Context(), `INSERT INTO gateway_key_policies(key_id,paused)
		SELECT id,? FROM gateway_keys WHERE id=? AND enabled=1
		ON CONFLICT(key_id) DO UPDATE SET paused=excluded.paused`, *in.Paused, in.ID)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := res.RowsAffected(); n != 1 {
		http.Error(w, "key not found", 404)
		return
	}
	jsonOK(w, 200, map[string]string{"id": in.ID})
}

func (s *Server) gatewayRevokeKey(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	res, err := s.Store.DB.ExecContext(r.Context(), "UPDATE gateway_keys SET enabled=0 WHERE id=?", in.ID)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := res.RowsAffected(); n != 1 {
		http.Error(w, "key not found", 404)
		return
	}
	jsonOK(w, 200, map[string]string{"id": in.ID})
}
