package main

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

const gatewaySchema = `
CREATE TABLE IF NOT EXISTS gateway_models (
	id TEXT PRIMARY KEY, routes_json TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN(0,1)), created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS gateway_keys (
	id TEXT PRIMARY KEY, label TEXT NOT NULL, digest BLOB NOT NULL UNIQUE, prefix TEXT NOT NULL, models_json TEXT NOT NULL,
	daily_limit INTEGER NOT NULL CHECK(daily_limit BETWEEN 1 AND 1000000), enabled INTEGER NOT NULL CHECK(enabled IN(0,1)), created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS gateway_requests (
	id TEXT PRIMARY KEY, key_id TEXT NOT NULL REFERENCES gateway_keys(id), model_id TEXT NOT NULL REFERENCES gateway_models(id),
	created_at INTEGER NOT NULL, outcome TEXT NOT NULL, http_status INTEGER, latency_ms INTEGER, attempts INTEGER NOT NULL DEFAULT 0,
	sample_id TEXT REFERENCES telemetry(id)
);
CREATE INDEX IF NOT EXISTS gateway_requests_day ON gateway_requests(key_id,created_at);
CREATE INDEX IF NOT EXISTS gateway_requests_recent ON gateway_requests(created_at DESC,id);
CREATE INDEX IF NOT EXISTS gateway_provider_telemetry ON telemetry(provider_id,created_at);
`

const gatewayMaxRoutes = 8
const gatewayKeyPrefix = "sk-ma-"

const gatewayConsoleSchema = `
CREATE TABLE IF NOT EXISTS gateway_key_policies (
	key_id TEXT PRIMARY KEY REFERENCES gateway_keys(id), expires_at INTEGER,
	paused INTEGER NOT NULL DEFAULT 0 CHECK(paused IN(0,1))
);
CREATE INDEX IF NOT EXISTS gateway_manual_tests_recent ON telemetry(created_at DESC,id)
	WHERE source IN ('GATEWAY_TEST','MANUAL_TEST');
`

var (
	errGatewayKey   = errors.New("invalid gateway key")
	errGatewayModel = errors.New("gateway model unavailable")
	errGatewayQuota = errors.New("daily request limit reached")
)

type gatewayModel struct {
	ID        string      `json:"id"`
	Routes    []Selection `json:"routes"`
	Enabled   bool        `json:"enabled"`
	CreatedAt int64       `json:"created_at"`
}

type gatewayKey struct {
	ID         string   `json:"id"`
	Label      string   `json:"label"`
	Prefix     string   `json:"prefix"`
	Models     []string `json:"models"`
	DailyLimit int      `json:"daily_limit"`
	UsedToday  int      `json:"used_today"`
	Enabled    bool     `json:"enabled"`
	Paused     bool     `json:"paused"`
	CreatedAt  int64    `json:"created_at"`
	ExpiresAt  *int64   `json:"expires_at"`
}

func (s *Server) gatewayAdminRoutes() *http.ServeMux {
	admin := http.NewServeMux()
	admin.HandleFunc("GET /status", s.status)
	admin.HandleFunc("GET /system", s.systemStatus)
	admin.HandleFunc("GET /gateway/providers", s.gatewayProviders)
	admin.HandleFunc("POST /gateway/remove", s.gatewayRemove)
	admin.HandleFunc("POST /gateway/provider", s.gatewaySaveProvider)
	admin.HandleFunc("POST /gateway/quota", s.gatewaySaveQuota)
	admin.HandleFunc("POST /gateway/quota/refresh", s.gatewayRefreshQuota)
	admin.HandleFunc("GET /gateway/upstreams", s.gatewayListUpstreams)
	admin.HandleFunc("POST /gateway/upstreams", s.gatewayAddUpstream)
	admin.HandleFunc("POST /gateway/sync", s.gatewaySync)
	admin.HandleFunc("POST /gateway/upstream-model", s.gatewayAddUpstreamModel)
	admin.HandleFunc("GET /gateway/offerings", s.gatewayOfferings)
	admin.HandleFunc("GET /gateway/offering-history", s.gatewayOfferHistory)
	admin.HandleFunc("GET /gateway/offering-performance", s.gatewayOfferPerformance)
	admin.HandleFunc("GET /gateway/offering-site", s.gatewayOfferSite)
	admin.HandleFunc("POST /gateway/offering-site", s.gatewayOfferSite)
	admin.HandleFunc("GET /gateway/model-groups", s.gatewayModelGroups)
	admin.HandleFunc("POST /gateway/model-group", s.gatewayAlignModel)
	admin.HandleFunc("POST /gateway/probe", s.gatewayProbe)
	admin.HandleFunc("POST /models/test", s.testModelHTTP)
	admin.HandleFunc("GET /accounts", s.listAccounts)
	admin.HandleFunc("POST /accounts", s.addAccount)
	admin.HandleFunc("GET /credentials", s.listCredentials)
	admin.HandleFunc("POST /credentials", s.gatewayAddCredential)
	admin.HandleFunc("POST /credentials/toggle", s.toggleCredentials)
	admin.HandleFunc("POST /credentials/rotate", s.rotateCredential)
	admin.HandleFunc("GET /checkins/accounts", s.accountCheckinsHTTP)
	admin.HandleFunc("POST /checkins/accounts", s.completeAccountCheckin)
	admin.HandleFunc("POST /newapi/status", s.newApiStatusHTTP)
	admin.HandleFunc("GET /gateway/models", s.gatewayAdminModels)
	admin.HandleFunc("POST /gateway/models", s.gatewaySaveModel)
	admin.HandleFunc("GET /gateway/keys", s.gatewayListKeys)
	admin.HandleFunc("POST /gateway/keys", s.gatewayIssueKey)
	admin.HandleFunc("POST /gateway/keys/edit", s.gatewayEditKey)
	admin.HandleFunc("POST /gateway/keys/pause", s.gatewayPauseKey)
	admin.HandleFunc("POST /gateway/keys/revoke", s.gatewayRevokeKey)
	admin.HandleFunc("GET /gateway/usage", s.gatewayUsage)
	s.updateRoutes(admin)
	return admin
}

func (s *Server) gatewayHandler() http.Handler {
	admin := s.gatewayAdminRoutes()
	root := http.NewServeMux()
	root.HandleFunc("GET /healthz", s.health)
	root.Handle("/api/v1/", s.auth(http.StripPrefix("/api/v1", admin)))
	s.gatewayWebRoutes(root, admin, false)
	return s.maintenanceHandler(root)
}

func (s *Server) gatewayWebRoutes(root *http.ServeMux, admin *http.ServeMux, workspace bool) {
	root.HandleFunc("GET /v1/models", s.gatewayPublicModels)
	root.HandleFunc("POST /v1/chat/completions", s.gatewayChat)
	// Web sign-in and programmatic credentials authorize separate entry points.
	root.Handle("POST /ui/api/gateway/credential/copy", s.gatewayBrowserAPI(http.HandlerFunc(s.gatewayCopyCredential)))
	root.Handle("/ui/api/", s.gatewayBrowserAPI(http.StripPrefix("/ui/api", admin)))
	s.gatewayUIRoutes(root, workspace)
}

func gatewayError(w http.ResponseWriter, status int, code, message string) {
	kind := "invalid_request_error"
	if status == http.StatusTooManyRequests {
		kind = "rate_limit_error"
	} else if status >= 500 {
		kind = "server_error"
	}
	jsonOK(w, status, map[string]any{"error": map[string]string{"type": kind, "code": code, "message": message}})
}

func gatewayCredentials(r *http.Request) (string, [32]byte, error) {
	raw, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "+gatewayKeyPrefix)
	if !ok || len(r.Header.Values("Authorization")) != 1 || len(raw) != 43 {
		return "", [32]byte{}, errGatewayKey
	}
	data, err := base64.RawURLEncoding.Strict().DecodeString(raw)
	if err != nil || len(data) != 32 {
		return "", [32]byte{}, errGatewayKey
	}
	key := gatewayKeyPrefix + raw
	return key, sha256.Sum256([]byte(key)), nil
}

func gatewayAccess(ctx context.Context, db queryRows, digest [32]byte) (gatewayKey, error) {
	var key gatewayKey
	var models string
	err := db.QueryRowContext(ctx, `SELECT k.id,k.models_json,k.daily_limit FROM gateway_keys k
		LEFT JOIN gateway_key_policies p ON p.key_id=k.id
		WHERE k.digest=? AND k.enabled=1 AND COALESCE(p.paused,0)=0 AND (p.expires_at IS NULL OR p.expires_at>?)`,
		digest[:], now()).Scan(&key.ID, &models, &key.DailyLimit)
	if errors.Is(err, sql.ErrNoRows) {
		return key, errGatewayKey
	}
	if err != nil {
		return key, err
	}
	return key, json.Unmarshal([]byte(models), &key.Models)
}

func gatewayAuthError(w http.ResponseWriter, err error) {
	if errors.Is(err, errGatewayKey) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		gatewayError(w, 401, "invalid_api_key", "A valid ModelAtlas distribution key is required.")
		return
	}
	gatewayError(w, 503, "service_unavailable", "ModelAtlas could not read access settings.")
}

func (s *Server) gatewayPublicModels(w http.ResponseWriter, r *http.Request) {
	security(w)
	_, digest, err := gatewayCredentials(r)
	if err != nil {
		gatewayAuthError(w, err)
		return
	}
	key, err := gatewayAccess(r.Context(), s.Store.DB, digest)
	if err != nil {
		gatewayAuthError(w, err)
		return
	}
	allowed, _ := json.Marshal(key.Models)
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,created_at FROM gateway_models WHERE enabled=1 AND id IN(SELECT value FROM json_each(?)) ORDER BY id", string(allowed))
	if err != nil {
		gatewayAuthError(w, err)
		return
	}
	defer rows.Close()
	models := []map[string]any{}
	for rows.Next() {
		var id string
		var at int64
		if err = rows.Scan(&id, &at); err != nil {
			gatewayAuthError(w, err)
			return
		}
		models = append(models, map[string]any{"id": id, "object": "model", "created": at / 1000, "owned_by": "modelatlas"})
	}
	if rows.Err() != nil {
		gatewayAuthError(w, rows.Err())
		return
	}
	jsonOK(w, 200, map[string]any{"object": "list", "data": models})
}

func (s *Server) gatewayAdminModels(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,routes_json,enabled,created_at FROM gateway_models WHERE "+gatewayLivePublicModel+" ORDER BY id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	models := []gatewayModel{}
	for rows.Next() {
		var v gatewayModel
		var routes string
		if rows.Scan(&v.ID, &routes, &v.Enabled, &v.CreatedAt) != nil || json.Unmarshal([]byte(routes), &v.Routes) != nil {
			http.Error(w, "database error", 500)
			return
		}
		models = append(models, v)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, models)
}

func (s *Server) gatewaySaveModel(w http.ResponseWriter, r *http.Request) {
	var v gatewayModel
	if !decodeJSON(w, r, &v) {
		return
	}
	ctx := r.Context()
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if !validID(v.ID) || safeText(v.ID, 256, secrets...) != v.ID || len(v.Routes) < 1 || len(v.Routes) > gatewayMaxRoutes {
		http.Error(w, "choose a model ID and 1–8 routes", 400)
		return
	}
	seen := make(map[Selection]bool, len(v.Routes))
	for _, route := range v.Routes {
		if !validScope(route) || seen[route] {
			http.Error(w, "invalid or duplicate route", 400)
			return
		}
		seen[route] = true
		if v.Enabled && (s.operation(ctx, route) != "CHAT_COMPLETIONS" || !s.selectionUsable(ctx, route)) {
			http.Error(w, "route is not an enabled Chat Completions connection", 422)
			return
		}
	}
	raw, _ := json.Marshal(v.Routes)
	result, err := s.Store.DB.ExecContext(ctx, `INSERT INTO gateway_models(id,routes_json,enabled,created_at)
		SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM gateway_removals
			WHERE kind='PUBLIC_MODEL' AND provider_id='' AND account_id='' AND item_id=?)
		AND NOT EXISTS(SELECT 1 FROM json_each(?) route WHERE `+gatewayRemovedRoute+`)
		ON CONFLICT(id) DO UPDATE SET routes_json=excluded.routes_json,enabled=excluded.enabled`, v.ID, string(raw), v.Enabled, now(), v.ID, string(raw))
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := result.RowsAffected(); n != 1 {
		http.Error(w, "public ID or upstream route removed", 409)
		return
	}
	jsonOK(w, 200, map[string]string{"id": v.ID})
}

func gatewayDayStart(at time.Time) int64 {
	return at.UTC().Truncate(24 * time.Hour).UnixMilli()
}

// The single SQLite writer serializes revocation, authorization, the quota
// check and the durable admission. One admitted request counts once.
func (s *Server) gatewayAdmit(ctx context.Context, digest [32]byte, id, model string, at time.Time) ([]Selection, error) {
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	key, err := gatewayAccess(ctx, tx, digest)
	if err != nil {
		return nil, err
	}
	if !oneOf(model, key.Models...) {
		return nil, errGatewayModel
	}
	var raw string
	err = tx.QueryRowContext(ctx, "SELECT routes_json FROM gateway_models WHERE id=? AND enabled=1", model).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, errGatewayModel
	}
	if err != nil {
		return nil, err
	}
	var routes []Selection
	if err = json.Unmarshal([]byte(raw), &routes); err != nil {
		return nil, err
	}
	var used int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM gateway_requests WHERE key_id=? AND created_at>=?", key.ID, gatewayDayStart(at)).Scan(&used); err != nil {
		return nil, err
	}
	if used >= key.DailyLimit {
		return nil, errGatewayQuota
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO gateway_requests(id,key_id,model_id,created_at,outcome) VALUES(?,?,?,?,'RUNNING')", id, key.ID, model, at.UnixMilli()); err != nil {
		return nil, err
	}
	return routes, tx.Commit()
}
