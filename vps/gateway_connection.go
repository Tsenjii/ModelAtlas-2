package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"
)

// Mounted only on the authenticated browser entry point. The regular
// credential list, workspace APIs and programmatic routes remain metadata-only.
func (s *Server) gatewayCopyCredential(w http.ResponseWriter, r *http.Request) {
	var scope syncRequest
	if !decodeJSON(w, r, &scope) {
		return
	}
	if !validID(scope.ProviderID) || !validID(scope.AccountID) || !validID(scope.CredentialID) {
		http.Error(w, "valid credential scope required", 400)
		return
	}
	var cipher string
	err := s.Store.DB.QueryRowContext(r.Context(), `SELECT encrypted_key FROM credentials
		WHERE provider_id=? AND account_id=? AND id=?`, scope.ProviderID, scope.AccountID, scope.CredentialID).Scan(&cipher)
	if errors.Is(err, sql.ErrNoRows) || err == nil && cipher == "" {
		http.Error(w, "no stored API key", 404)
		return
	}
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	secret, err := s.decrypt(cipher)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]string{"api_key": secret})
}

func (s *Server) gatewayOfferPerformance(w http.ResponseWriter, r *http.Request) {
	scope := selectionQuery(r)
	if !validScope(scope) || !r.URL.Query().Has("group") {
		http.Error(w, "full selected scope required", 400)
		return
	}
	duration := parseWindow(r)
	end := now()
	start := end - duration
	if r.URL.Query().Get("window") == "custom" {
		var first, last error
		start, first = strconv.ParseInt(r.URL.Query().Get("start"), 10, 64)
		end, last = strconv.ParseInt(r.URL.Query().Get("end"), 10, 64)
		if first != nil || last != nil || start < 0 || end <= start || end > now() {
			http.Error(w, "invalid custom time window", 400)
			return
		}
	} else if duration <= 0 {
		http.Error(w, "invalid window", 400)
		return
	}
	jsonOK(w, 200, map[string]any{"selection": scope, "local": s.performanceBetween(r.Context(), scope, start, end)})
}

const gatewayNewAPIPerformance = "/api/perf-metrics"

func (s *Server) gatewayOfferSite(w http.ResponseWriter, r *http.Request) {
	in := struct {
		Selection
		Hours int `json:"hours"`
	}{}
	if r.Method == http.MethodGet {
		in.Selection = selectionQuery(r)
		in.Hours, _ = strconv.Atoi(r.URL.Query().Get("hours"))
		if !r.URL.Query().Has("group") {
			http.Error(w, "full selected scope required", 400)
			return
		}
	} else if !decodeJSON(w, r, &in) {
		return
	}
	if !validScope(in.Selection) || in.Hours < 1 || in.Hours > 720 {
		http.Error(w, "valid model scope and time window required", 400)
		return
	}

	ctx := r.Context()
	var origin string
	err := s.Store.DB.QueryRowContext(ctx, `SELECT COALESCE(o.base_url,p.base_url)
		FROM my_models m JOIN providers p ON p.id=m.provider_id
		LEFT JOIN credential_origins o ON o.provider_id=m.provider_id AND o.account_id=m.account_id AND o.credential_id=m.credential_id
		WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=? AND m.model_id=? AND m.group_id=? AND m.endpoint=?`,
		in.ProviderID, in.AccountID, in.CredentialID, in.ModelID, in.Group, in.Endpoint).Scan(&origin)
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "membership missing", 404)
		return
	}
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if !validateHTTPS(origin) {
		http.Error(w, "upstream connection unavailable", 422)
		return
	}
	q := NewApiQuery{ProviderID: in.ProviderID, ModelID: in.ModelID, Origin: origin, Hours: in.Hours}
	if r.Method == http.MethodGet {
		s.gatewayCachedSite(w, ctx, q)
		return
	}

	// Different keys at the same origin share this public reading. Bound work
	// the same way as a manual quota or catalog refresh.
	flight := "gateway-site:" + q.ProviderID + "/" + q.Origin + "/" + q.ModelID + "/" + strconv.Itoa(q.Hours)
	if !s.beginFlight(flight) {
		http.Error(w, "upstream refresh already running or service busy", 409)
		return
	}
	defer s.endFlight(flight)
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	result, err := s.newApiRead(ctx, q, gatewayNewAPIPerformance)
	if err != nil {
		writeProviderProblem(w, err, gatewayNewAPIPerformance)
		return
	}
	jsonOK(w, 200, result)
}

func (s *Server) gatewayCachedSite(w http.ResponseWriter, ctx context.Context, q NewApiQuery) {
	result := NewApiResult{ProviderID: q.ProviderID, Role: "ANONYMOUS", Endpoint: gatewayNewAPIPerformance, Source: "NEW_API_OFFICIAL"}
	var raw string
	err := s.Store.DB.QueryRowContext(ctx, `SELECT facts_json,provider_version,observed_at FROM newapi_snapshots
		WHERE provider_id=? AND account_id='' AND credential_id='' AND credential_role='ANONYMOUS'
		AND endpoint=? AND source='NEW_API_OFFICIAL' AND json_valid(facts_json)
		AND json_extract(facts_json,'$.origin')=? AND json_extract(facts_json,'$.model_name')=?
		AND json_extract(facts_json,'$.hours')=? ORDER BY observed_at DESC,rowid DESC LIMIT 1`,
		q.ProviderID, gatewayNewAPIPerformance, q.Origin, q.ModelID, q.Hours).Scan(&raw, &result.Version, &result.ObservedAt)
	if errors.Is(err, sql.ErrNoRows) {
		jsonOK(w, 200, nil)
		return
	}
	if err != nil || json.Unmarshal([]byte(raw), &result.Facts) != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, result)
}
