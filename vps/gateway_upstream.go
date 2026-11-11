package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

type gatewayUpstream struct {
	syncRequest
	Name       string `json:"name"`
	BaseURL    string `json:"base_url"`
	AuthMode   string `json:"auth_mode"`
	Account    string `json:"account"`
	Credential string `json:"credential"`
	Group      string `json:"group"`
	Enabled    bool   `json:"enabled"`
}

func (s *Server) gatewayListUpstreams(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), `SELECT p.id,a.id,c.id,p.name,COALESCE(o.base_url,p.base_url),
		COALESCE(NULLIF(o.auth_mode,''),NULLIF(json_extract(pc.config_json,'$.auth_mode'),''),p.auth_mode),
		a.label,c.label,COALESCE(g.group_id,''),c.enabled
		FROM credentials c JOIN providers p ON p.id=c.provider_id JOIN accounts a ON a.provider_id=c.provider_id AND a.id=c.account_id
		LEFT JOIN provider_config pc ON pc.provider_id=p.id
		LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id
		LEFT JOIN credential_context g ON g.provider_id=c.provider_id AND g.account_id=c.account_id AND g.credential_id=c.id
		WHERE c.role='INFERENCE' ORDER BY p.name,a.label,c.label,p.id,a.id,c.id`)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []gatewayUpstream{}
	for rows.Next() {
		var v gatewayUpstream
		if rows.Scan(&v.ProviderID, &v.AccountID, &v.CredentialID, &v.Name, &v.BaseURL, &v.AuthMode, &v.Account, &v.Credential, &v.Group, &v.Enabled) != nil {
			http.Error(w, "database error", 500)
			return
		}
		out = append(out, v)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, out)
}

func (s *Server) gatewayAddUpstream(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name        string `json:"name"`
		BaseURL     string `json:"base_url"`
		APIKey      string `json:"api_key"`
		Description string `json:"description"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	in.Name = strings.TrimSpace(in.Name)
	in.BaseURL = strings.TrimRight(strings.TrimSpace(in.BaseURL), "/")
	in.APIKey = strings.TrimSpace(in.APIKey)
	in.Description = strings.TrimSpace(in.Description)
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if in.Name == "" || safeText(in.Name, 120, append(secrets, in.APIKey)...) != in.Name ||
		safeMultilineText(in.Description, 1000, append(secrets, in.APIKey)...) != in.Description ||
		!validateHTTPS(in.BaseURL) || safeText(in.BaseURL, 4096, append(secrets, in.APIKey)...) != in.BaseURL {
		http.Error(w, "valid name and HTTPS base URL required", 400)
		return
	}
	mode, label := "BEARER", "主要 Key"
	if in.APIKey == "" {
		mode, label = "NONE", "匿名"
	}
	draft, err := s.prepareDraft(OnboardingInput{
		Mode: "ADVANCED", Provider: Provider{Name: in.Name, BaseURL: in.BaseURL, AuthMode: mode, Description: in.Description},
		Account: Account{Label: "主要連線"}, Credentials: []Credential{{Label: label, Secret: in.APIKey}},
		Endpoint: "CHAT_COMPLETIONS",
	})
	if err != nil {
		http.Error(w, "invalid upstream credential", 400)
		return
	}
	p, a, c := draft.Input.Provider, draft.Input.Account, draft.Input.Credentials[0]
	config, _ := json.Marshal(p.Config)
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	for _, write := range []struct {
		sql  string
		args []any
	}{
		{"INSERT INTO providers(id,name,base_url,description,auth_mode,created_at) VALUES(?,?,?,?,?,?)", []any{p.ID, p.Name, p.BaseURL, p.Description, p.AuthMode, now()}},
		{"INSERT INTO provider_config VALUES(?,?)", []any{p.ID, string(config)}},
		{"INSERT INTO accounts VALUES(?,?,?)", []any{a.ID, p.ID, a.Label}},
		{"INSERT INTO credentials(id,provider_id,account_id,label,encrypted_key,role,enabled,created_at) VALUES(?,?,?,?,?,'INFERENCE',1,?)", []any{c.ID, p.ID, a.ID, c.Label, draft.Encrypted[c.ID], now()}},
	} {
		if _, err = tx.ExecContext(ctx, write.sql, write.args...); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if insertCredentialOrigin(ctx, tx, p.ID, a.ID, c.ID, p.BaseURL, "") != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, gatewayUpstream{
		syncRequest: syncRequest{p.ID, a.ID, c.ID}, Name: p.Name, BaseURL: p.BaseURL, AuthMode: p.AuthMode, Account: a.Label, Credential: c.Label, Enabled: true,
	})
}

func (s *Server) gatewaySync(w http.ResponseWriter, r *http.Request) {
	var selection syncRequest
	if !decodeJSON(w, r, &selection) {
		return
	}
	id := "gateway-catalog:" + selection.ProviderID + "/" + selection.AccountID + "/" + selection.CredentialID
	if !s.beginFlight(id) {
		http.Error(w, "upstream synchronization already running or service busy", 409)
		return
	}
	defer s.endFlight(id)
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	p, key, err := s.endpointAuth(ctx, selection.ProviderID, selection.AccountID, selection.CredentialID)
	if err != nil {
		http.Error(w, "upstream connection unavailable", 422)
		return
	}
	cfg := s.config(ctx, p)
	entries, err := openAIModels(s, ctx, p, cfg, key)
	if err != nil {
		writeProviderProblem(w, err, "catalog")
		return
	}
	s.gatewayApplyCatalog(w, ctx, selection, p, cfg, entries, "PROVIDER_CATALOG", "CHAT_COMPLETIONS")
}

func (s *Server) gatewayAddUpstreamModel(w http.ResponseWriter, r *http.Request) {
	var in struct {
		syncRequest
		Model    string `json:"model_id"`
		Endpoint string `json:"endpoint"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	in.Model = strings.TrimSpace(in.Model)
	if !validID(in.Model) {
		http.Error(w, "valid model ID required", 400)
		return
	}
	if in.Endpoint == "" {
		in.Endpoint = "CHAT_COMPLETIONS"
	}
	if !oneOf(in.Endpoint, "CHAT_COMPLETIONS", "IMAGES_GENERATION") {
		http.Error(w, "invalid manual model endpoint", 400)
		return
	}
	ctx := r.Context()
	p, _, err := s.endpointAuth(ctx, in.ProviderID, in.AccountID, in.CredentialID)
	if err != nil {
		http.Error(w, "upstream connection unavailable", 422)
		return
	}
	s.gatewayApplyCatalog(w, ctx, in.syncRequest, p, s.config(ctx, p), []CatalogEntry{{ID: in.Model, Name: in.Model}}, "MANUAL", in.Endpoint)
}

func (s *Server) gatewayApplyCatalog(w http.ResponseWriter, ctx context.Context, connection syncRequest, p Provider, cfg ProviderConfig, entries []CatalogEntry, source, endpoint string) {
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	primary, err := s.provider(ctx, p.ID)
	if err != nil {
		http.Error(w, "upstream connection unavailable", 422)
		return
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	scope := Selection{ProviderID: connection.ProviderID, AccountID: connection.AccountID, CredentialID: connection.CredentialID, Endpoint: endpoint}
	var cipher, fields string
	err = tx.QueryRowContext(ctx, `SELECT c.encrypted_key,COALESCE(f.fields_json,''),COALESCE(g.group_id,'') FROM credentials c
		LEFT JOIN credential_auth_fields f ON f.provider_id=c.provider_id AND f.account_id=c.account_id AND f.credential_id=c.id
		LEFT JOIN credential_context g ON g.provider_id=c.provider_id AND g.account_id=c.account_id AND g.credential_id=c.id
		WHERE c.provider_id=? AND c.account_id=? AND c.id=? AND c.enabled=1 AND c.role='INFERENCE'`, scope.ProviderID, scope.AccountID, scope.CredentialID).Scan(&cipher, &fields, &scope.Group)
	if err != nil || credentialMaterialStamp(cipher, fields) != p.CredentialStamp {
		http.Error(w, "upstream changed during discovery; retry", 409)
		return
	}
	if len(entries) > 0 && bindSelection(ctx, tx, primary, cfg, scope) != nil {
		if endpoint == "IMAGES_GENERATION" {
			http.Error(w, "a matching image generation endpoint is required", 422)
		} else {
			http.Error(w, "a matching Chat Completions endpoint is required", 422)
		}
		return
	}
	seen := make(map[string]bool, len(entries))
	added := 0
	for _, entry := range entries {
		if seen[entry.ID] || !validID(entry.ID) || safeText(entry.ID, 256, secrets...) != entry.ID {
			continue
		}
		seen[entry.ID] = true
		name := safeText(entry.Name, 200, secrets...)
		if name == "" {
			name = entry.ID
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO models VALUES(?,?,?,'UNKNOWN','UNKNOWN',?,?) ON CONFLICT(provider_id,id) DO NOTHING", p.ID, entry.ID, name, source, now()); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		scope.ModelID = entry.ID
		if source == "PROVIDER_CATALOG" && knownTermPrice(entry.Price) != nil {
			evidence := withCredentialObservation(ctx, scope, p)
			if err = writeObservation(evidence, tx, scope, "CATALOG", cfg.Adapter+"_OFFICIAL", "SUCCESS", map[string]any{"price": entry.Price}); err != nil {
				http.Error(w, "database error", 500)
				return
			}
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO my_models
			SELECT ?,?,?,?,?,?,? WHERE ?='MANUAL' OR NOT EXISTS(SELECT 1 FROM membership_state WHERE scope_key=? AND state='REMOVED')
			ON CONFLICT DO NOTHING`, scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint, now(), source, scopeKey(scope))
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		n, err := res.RowsAffected()
		if err != nil || n == 1 && bumpMembership(tx, scope) != nil {
			http.Error(w, "database error", 500)
			return
		}
		if source == "MANUAL" {
			if _, err = tx.ExecContext(ctx, "UPDATE membership_state SET state='ACTIVE' WHERE scope_key=? AND state='REMOVED'", scopeKey(scope)); err != nil {
				http.Error(w, "database error", 500)
				return
			}
			if endpoint == "IMAGES_GENERATION" {
				if err = confirmImageConnection(ctx, tx, scope); err != nil {
					http.Error(w, "database error", 500)
					return
				}
			}
		}
		added += int(n)
	}
	if source == "MANUAL" && len(seen) != 1 {
		http.Error(w, "invalid model ID", 400)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]int{"models_seen": len(seen), "added": added})
}

type gatewayPerformance struct {
	Samples   int      `json:"samples"`
	Successes int      `json:"successes"`
	TTFT      *float64 `json:"ttft_ms"`
	TTFB      *float64 `json:"ttfb_ms"`
	Latency   *float64 `json:"latency_ms"`
	TPS       *float64 `json:"tps"`
}

const gatewayOfferScopeSQL = "provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?"

const gatewayPerformanceSQL = `SELECT json_object(
	'samples',COUNT(*),'successes',COALESCE(SUM(outcome='SUCCESS'),0),
	'ttft_ms',AVG(CASE WHEN outcome='SUCCESS' THEN ttft_ms END),
	'ttfb_ms',AVG(CASE WHEN outcome='SUCCESS' THEN ttfb_ms END),
	'latency_ms',AVG(CASE WHEN outcome='SUCCESS' THEN latency_ms END),
	'tps',AVG(CASE WHEN outcome='SUCCESS' THEN ` + numericTPS + ` END))
	FROM (SELECT outcome,ttft_ms,ttfb_ms,latency_ms,details_json FROM telemetry
	WHERE %s AND created_at>=? AND outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED')
	ORDER BY created_at DESC,id DESC LIMIT 100)`

func (s *Server) gatewayPerformance(ctx context.Context, v Selection) (gatewayPerformance, error) {
	return s.gatewayReadPerformance(ctx, gatewayOfferScopeSQL,
		v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, now()-7*86400000)
}

func (s *Server) gatewayReadPerformance(ctx context.Context, scope string, args ...any) (gatewayPerformance, error) {
	var p gatewayPerformance
	var raw []byte
	query := fmt.Sprintf(gatewayPerformanceSQL, scope)
	err := s.Store.DB.QueryRowContext(ctx, query, args...).Scan(&raw)
	if err != nil {
		return p, err
	}
	err = json.Unmarshal(raw, &p)
	return p, err
}

type gatewayOffering struct {
	Selection
	Provider    string             `json:"provider"`
	Account     string             `json:"account"`
	Credential  string             `json:"credential"`
	BaseURL     string             `json:"base_url"`
	HasAPIKey   bool               `json:"has_api_key"`
	DisplayName string             `json:"display_name"`
	Canonical   string             `json:"canonical_id"`
	Operation   string             `json:"operation"`
	Enabled     bool               `json:"enabled"`
	Performance gatewayPerformance `json:"performance"`
	Prices      map[string]any     `json:"prices,omitempty"`
	Terms       gatewayTerms       `json:"terms"`
}

func (s *Server) gatewayOfferings(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	limit, offset := pageRange(r, 50)
	q := r.URL.Query()
	var order string
	switch q.Get("sort") {
	case "ttft":
		order = "json_extract(perf,'$.ttft_ms') ASC NULLS LAST, "
	case "tps":
		order = "json_extract(perf,'$.tps') DESC NULLS LAST, "
	case "success":
		order = "1.0 * json_extract(perf,'$.successes') / NULLIF(json_extract(perf,'$.samples'),0) DESC NULLS LAST, "
	case "latency":
		order = "json_extract(perf,'$.latency_ms') ASC NULLS LAST, "
	}
	perf := "NULL"
	var args []any
	if order != "" {
		perf = "(" + fmt.Sprintf(gatewayPerformanceSQL, "provider_id=m.provider_id AND account_id=m.account_id AND credential_id=m.credential_id AND model_id=m.model_id AND group_id=m.group_id AND endpoint=m.endpoint") + ")"
		args = append(args, now()-7*86400000)
	}
	id := q.Get("canonical_id")
	args = append(args, id, id, q.Get("provider_id"), q.Get("provider_id"), q.Get("account_id"), q.Get("account_id"), q.Get("credential_id"), q.Get("credential_id"), q.Get("q"), limit+1, offset)
	rows, err := s.Store.DB.QueryContext(ctx, gatewayCatalogSQL+` SELECT m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint,
		m.provider,m.account,m.credential,m.base_url,m.has_api_key,m.display_name,m.canonical_id,m.operation,m.enabled,`+perf+` AS perf,`+gatewayTermColumns+`
		FROM scopes m WHERE (?='' OR m.canonical_id=?)
		AND (?='' OR m.provider_id=?) AND (?='' OR m.account_id=?) AND (?='' OR m.credential_id=?)
		AND instr(lower(m.model_id||' '||m.display_name||' '||m.provider),lower(?))>0
		ORDER BY `+order+`m.model_id,m.provider,m.provider_id,m.account_id,m.credential_id,m.group_id,m.endpoint LIMIT ? OFFSET ?`, args...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	out := []gatewayOffering{}
	for rows.Next() {
		var v gatewayOffering
		var raw []byte
		var facts gatewayTermRow
		if err = rows.Scan(append([]any{&v.ProviderID, &v.AccountID, &v.CredentialID, &v.ModelID, &v.Group, &v.Endpoint, &v.Provider, &v.Account, &v.Credential, &v.BaseURL, &v.HasAPIKey, &v.DisplayName, &v.Canonical, &v.Operation, &v.Enabled, &raw}, facts.scan()...)...); err != nil ||
			raw != nil && json.Unmarshal(raw, &v.Performance) != nil {
			rows.Close()
			http.Error(w, "database error", 500)
			return
		}
		v.Terms = facts.terms()
		out = append(out, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	for i := range out {
		v := &out[i]
		v.Enabled = v.Enabled && s.selectionUsable(ctx, v.Selection)
		if q.Get("prices") == "1" {
			offer := s.offerSummary(ctx, v.Selection)
			v.Prices = map[string]any{"nominal_price": offer["nominal_price"], "price": offer["price"], "effective_price": offer["effective_price"],
				"published_groups": s.gatewayPublishedGroupPrices(ctx, *v)}
		}
		if order == "" {
			v.Performance, err = s.gatewayPerformance(ctx, v.Selection)
			if err != nil {
				http.Error(w, "database error", 500)
				return
			}
		}
	}
	jsonOK(w, 200, struct {
		Offerings []gatewayOffering `json:"offerings"`
		More      bool              `json:"more"`
	}{out, more})
}

type gatewayOfferSample struct {
	At           int64    `json:"at"`
	Source       string   `json:"source"`
	Outcome      string   `json:"outcome"`
	HTTPStatus   *int64   `json:"http_status"`
	TTFB         *int64   `json:"ttfb_ms"`
	TTFT         *int64   `json:"ttft_ms"`
	Latency      *int64   `json:"latency_ms"`
	TPS          *float64 `json:"tps"`
	InputTokens  *int64   `json:"input_tokens"`
	OutputTokens *int64   `json:"output_tokens"`
}

func (s *Server) gatewayOfferHistory(w http.ResponseWriter, r *http.Request) {
	v := selectionQuery(r)
	if !validScope(v) || !r.URL.Query().Has("group") {
		http.Error(w, "full selected scope required", 400)
		return
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), `SELECT created_at,source,outcome,status_code,ttfb_ms,ttft_ms,
		latency_ms,`+numericTPS+`,input_tokens,output_tokens FROM telemetry WHERE `+gatewayOfferScopeSQL+`
		ORDER BY created_at DESC,id DESC LIMIT 20`, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	samples := make([]gatewayOfferSample, 0, 20)
	for rows.Next() {
		var sample gatewayOfferSample
		if err := rows.Scan(&sample.At, &sample.Source, &sample.Outcome, &sample.HTTPStatus, &sample.TTFB,
			&sample.TTFT, &sample.Latency, &sample.TPS, &sample.InputTokens, &sample.OutputTokens); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		samples = append(samples, sample)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, samples)
}

func (p gatewayPerformance) health() int {
	if p.Samples == 0 {
		return 1
	}
	if p.Samples >= 3 && p.Successes*100 >= p.Samples*95 {
		return 2
	}
	if p.Samples < 3 && p.Successes == p.Samples {
		return 1
	}
	return 0
}

func (s *Server) gatewayRankRoutes(ctx context.Context, routes []Selection, stream bool) ([]Selection, error) {
	type ranked struct {
		scope  Selection
		health int
		speed  *float64
	}
	candidates := make([]ranked, 0, len(routes))
	for _, v := range routes {
		if s.operation(ctx, v) != "CHAT_COMPLETIONS" || !s.selectionUsable(ctx, v) {
			continue
		}
		p, err := s.gatewayPerformance(ctx, v)
		if err != nil {
			return nil, err
		}
		health := p.health()
		var speed *float64
		if health == 2 {
			speed = p.Latency
			if stream {
				speed = p.TTFT
			}
		}
		candidates = append(candidates, ranked{v, health, speed})
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		a, b := candidates[i], candidates[j]
		if a.health != b.health {
			return a.health > b.health
		}
		if a.speed == nil || b.speed == nil {
			return a.speed != nil && b.speed == nil
		}
		return *a.speed < *b.speed
	})
	out := make([]Selection, 0, len(candidates))
	for _, candidate := range candidates {
		out = append(out, candidate.scope)
	}
	return out, nil
}

func (s *Server) gatewayProbe(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	if s.operation(r.Context(), v) != "CHAT_COMPLETIONS" || !s.selectionUsable(r.Context(), v) {
		http.Error(w, "upstream connection unavailable", 422)
		return
	}
	id := "gateway-test:" + scopeKey(v)
	if !s.beginFlight(id) {
		http.Error(w, "test already running or service busy", 409)
		return
	}
	defer s.endFlight(id)
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	req := gatewayChatRequest{stream: true, body: map[string]any{
		"stream": true, "stream_options": map[string]bool{"include_usage": true},
		"messages":   []any{map[string]any{"role": "user", "content": "Write the numbers 1 through 12 separated by commas. Output only the list."}},
		"max_tokens": 64,
	}}
	attempt := s.gatewayAttempt(ctx, "", v, req, secrets, nil)
	if attempt.storeErr != nil {
		http.Error(w, "test record unavailable", 503)
		return
	}
	performance, err := s.gatewayPerformance(ctx, v)
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"outcome": attempt.outcome, "http_status": attempt.upstreamStatus, "ttft_ms": attempt.ttft, "latency_ms": attempt.latency, "performance": performance})
}
