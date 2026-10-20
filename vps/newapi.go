package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type NewApiQuery struct {
	Origin       string `json:"-"`
	ProviderID   string `json:"provider_id"`
	AccountID    string `json:"account_id"`
	CredentialID string `json:"credential_id"`
	ModelID      string `json:"model_id,omitempty"`
	Hours        int    `json:"hours,omitempty"`
}
type NewApiResult struct {
	ProviderID    string         `json:"provider_id"`
	AccountID     string         `json:"account_id"`
	CredentialID  string         `json:"credential_id"`
	Role          string         `json:"credential_role"`
	Version       string         `json:"version"`
	Endpoint      string         `json:"endpoint"`
	Facts         map[string]any `json:"facts"`
	ObservedAt    int64          `json:"observed_at"`
	Source        string         `json:"source"`
	Compatibility string         `json:"compatibility"`
}

func (s *Server) accountPAT(ctx context.Context, q NewApiQuery) (Provider, string, error) {
	var p Provider
	var enc string
	var enabled int
	err := s.Store.DB.QueryRowContext(ctx, "SELECT p.id,p.name,COALESCE(o.base_url,p.base_url),p.description,p.auth_mode,c.encrypted_key,c.enabled FROM providers p JOIN accounts a ON a.provider_id=p.id JOIN credentials c ON c.provider_id=a.provider_id AND c.account_id=a.id LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id WHERE p.id=? AND a.id=? AND c.id=? AND c.role='ACCOUNT_PAT'", q.ProviderID, q.AccountID, q.CredentialID).Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode, &enc, &enabled)
	if err != nil || enabled != 1 || !validateHTTPS(p.BaseURL) {
		return Provider{}, "", sql.ErrNoRows
	}
	key, err := s.decrypt(enc)
	return p, key, err
}
func (s *Server) newApiGet(ctx context.Context, p Provider, path, token string) (map[string]json.RawMessage, error) {
	u, _ := url.Parse(originPath(p, strings.SplitN(path, "?", 2)[0]))
	if parts := strings.SplitN(path, "?", 2); len(parts) == 2 {
		u.RawQuery = parts[1]
	}
	mode := "NONE"
	if token != "" {
		mode = "BEARER"
	} else {
		p.AuthFields = nil
		p.CredentialMode = ""
	}
	root, _, err := s.getJSON(ctx, p, ProviderConfig{AuthMode: mode}, u.String(), token, 4<<20)
	if err != nil {
		return nil, err
	}
	if string(root["success"]) != "true" && string(root["code"]) != "true" {
		return nil, &providerReadError{Code: "UPSTREAM_REJECTED"}
	}
	return root, nil
}
func getNewAPIData(body map[string]json.RawMessage) (map[string]json.RawMessage, error) {
	var data map[string]json.RawMessage
	if json.Unmarshal(body["data"], &data) != nil || data == nil {
		return nil, errors.New("invalid data")
	}
	return data, nil
}
func stringField(obj map[string]json.RawMessage, key string) string {
	var v string
	if json.Unmarshal(obj[key], &v) != nil || safeText(v, 8192) != v {
		return ""
	}
	return v
}
func scalarField(obj map[string]json.RawMessage, key string) any {
	raw := obj[key]
	if len(raw) == 0 {
		return nil
	}
	var v any
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	if dec.Decode(&v) != nil {
		return nil
	}
	switch v.(type) {
	case json.Number, bool:
		return v
	}
	return nil
}
func intField(obj map[string]json.RawMessage, key string) *int64 {
	v, ok := scalarField(obj, key).(json.Number)
	if !ok {
		return nil
	}
	n, err := v.Int64()
	if err != nil {
		return nil
	}
	return &n
}
func safeStrings(raw json.RawMessage, key string, max int) []string {
	var a []string
	if json.Unmarshal(raw, &a) != nil || len(a) > 100 {
		return []string{}
	}
	out := []string{}
	for _, v := range a {
		if v != "" && safeText(v, max, key) == v {
			out = append(out, v)
		}
	}
	return out
}
func (s *Server) newApiStatus(ctx context.Context, p Provider) (map[string]any, string, error) {
	root, err := s.newApiGet(ctx, p, "/api/status", "")
	if err != nil {
		return nil, "UNKNOWN", err
	}
	data, err := getNewAPIData(root)
	if err != nil {
		return nil, "UNKNOWN", err
	}
	version := safeText(stringField(data, "version"), 64)
	if version == "" {
		version = "UNKNOWN"
	}
	facts := map[string]any{"version": version}
	for _, field := range []string{"system_name", "docs_link", "quota_display_type", "custom_currency_symbol"} {
		if text := stringField(data, field); text != "" {
			facts[field] = text
		}
	}
	for _, field := range []string{"quota_per_unit", "checkin_enabled"} {
		if v := scalarField(data, field); v != nil {
			facts[field] = v
		}
	}
	var modules map[string]map[string]json.RawMessage
	raw := stringField(data, "HeaderNavModules")
	if len(raw) <= 65536 && json.Unmarshal([]byte(raw), &modules) == nil {
		facts["pricing_module_enabled"] = scalarField(modules["pricing"], "enabled")
		facts["pricing_requires_auth"] = scalarField(modules["pricing"], "requireAuth")
	}
	return facts, version, nil
}
func (s *Server) newApiSnapshot(ctx context.Context, q NewApiQuery, endpoint, role, version string, facts map[string]any) error {
	raw, err := json.Marshal(facts)
	if err != nil || len(raw) > 4<<20 {
		return errors.New("facts too large")
	}
	_, err = s.Store.DB.ExecContext(ctx, "INSERT INTO newapi_snapshots(id,provider_id,account_id,credential_id,credential_role,endpoint,facts_json,provider_version,source,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?)", newID(), q.ProviderID, q.AccountID, q.CredentialID, role, endpoint, string(raw), version, "NEW_API_OFFICIAL", now())
	return err
}
func (s *Server) newApiRead(ctx context.Context, q NewApiQuery, path string) (NewApiResult, error) {
	p, err := s.provider(ctx, q.ProviderID)
	if err != nil {
		return NewApiResult{}, &providerReadError{Code: "PROVIDER_NOT_FOUND"}
	}
	token, role := "", "ANONYMOUS"
	if path == "/api/usage/token/" {
		p, token, err = s.endpointAuth(ctx, q.ProviderID, q.AccountID, q.CredentialID)
		role = "INFERENCE"
	} else if path == "/api/user/self" {
		p, token, err = s.accountPAT(ctx, q)
		role = "ACCOUNT_PAT"
	} else if path == "/api/pricing" && q.CredentialID != "" {
		// Public queries must not fail because the user selected an inference
		// key. Only an explicitly selected account PAT authenticates pricing.
		var selectedRole string
		if e := s.Store.DB.QueryRowContext(ctx, "SELECT role FROM credentials WHERE provider_id=? AND account_id=? AND id=? AND enabled=1", q.ProviderID, q.AccountID, q.CredentialID).Scan(&selectedRole); e != nil {
			err = e
		} else if selectedRole == "ACCOUNT_PAT" {
			p, token, err = s.accountPAT(ctx, q)
			role = "ACCOUNT_PAT"
		}
	}
	if err != nil {
		return NewApiResult{}, &providerReadError{Code: "CREDENTIAL_ROLE"}
	}
	if q.Origin != "" {
		if !validateHTTPS(q.Origin) || token != "" && p.BaseURL != q.Origin {
			return NewApiResult{}, &providerReadError{Code: "CREDENTIAL_ORIGIN"}
		}
		p.BaseURL = q.Origin
	}
	if path == "/api/user/self" && role != "ACCOUNT_PAT" {
		return NewApiResult{}, &providerReadError{Code: "CREDENTIAL_ROLE"}
	}
	q.Origin = p.BaseURL
	facts, version, err := s.newApiStatus(ctx, p)
	if err != nil {
		return NewApiResult{}, err
	}
	certified := version == "v1.0.0-rc.25"
	if role != "ANONYMOUS" && !certified {
		return NewApiResult{}, &providerReadError{Code: "UNSUPPORTED_PRIVATE_VERSION"}
	}
	statusFacts := facts
	if path != "/api/status" {
		// Reading a public endpoint anonymously is safe even when an optional
		// HeaderNavModules setting is absent. 401/403 remains a scoped failure.
		actualPath := path
		if strings.HasPrefix(path, "/api/perf-metrics") {
			if q.Hours == 0 {
				q.Hours = 168
			}
			if q.Hours < 1 || q.Hours > 720 {
				return NewApiResult{}, &providerReadError{Code: "INVALID_WINDOW"}
			}
			query := url.Values{"hours": {jsonNumber(q.Hours)}}
			if path == "/api/perf-metrics" {
				if !validID(q.ModelID) {
					return NewApiResult{}, &providerReadError{Code: "MODEL_REQUIRED"}
				}
				query.Set("model", q.ModelID)
			}
			actualPath += "?" + query.Encode()
		}
		root, e := s.newApiGet(ctx, p, actualPath, token)
		if e != nil {
			return NewApiResult{}, e
		}
		facts = map[string]any{}
		switch path {
		case "/api/pricing":
			facts, err = parseNewApiPricing(root, token)
			if err == nil && certified {
				// Retain the caller's account projection for its nominal quote.
				// The public snapshot below remains anonymous; this is never an
				// inference about that account's effective charge or access.
				err = s.mapNewApiPrices(ctx, q, facts, statusFacts)
			}
			if err == nil {
				facts["price_mapping_available"] = certified
			}
		case "/api/notice":
			var notice string
			if json.Unmarshal(root["data"], &notice) != nil {
				return NewApiResult{}, errors.New("invalid notice")
			}
			text := safeMultilineText(notice, 65536, token)
			if text != notice {
				return NewApiResult{}, errors.New("invalid notice")
			}
			facts["present"] = text != ""
			facts["text"] = text
		case "/api/user/self", "/api/usage/token/":
			var data map[string]json.RawMessage
			data, err = getNewAPIData(root)
			if err != nil {
				break
			}
			fields := []string{"quota", "used_quota", "request_count", "role", "status"}
			if path == "/api/usage/token/" {
				fields = []string{"total_granted", "total_used", "total_available", "unlimited_quota", "model_limits_enabled", "expires_at"}
			}
			for _, field := range fields {
				if n := scalarField(data, field); n != nil {
					facts[field] = n
				}
			}
			facts["group"] = nil
			facts["group_known"] = false
			if raw, ok := data["group"]; ok {
				var actual string
				if json.Unmarshal(raw, &actual) == nil && safeText(actual, 120, token) == actual {
					facts["group"] = actual
					facts["group_known"] = true
				}
			}
			if path == "/api/usage/token/" {
				facts["credential_version"] = p.CredentialVersion
				facts["origin"] = p.BaseURL
			}
		case "/api/perf-metrics/summary", "/api/perf-metrics":
			facts, err = parseNewApiPerformance(root, token, path == "/api/perf-metrics")
			if err == nil {
				if path == "/api/perf-metrics" && facts["model_name"] != q.ModelID {
					return NewApiResult{}, errors.New("performance model mismatch")
				}
				facts["hours"] = q.Hours
				facts["source"] = "PROVIDER_REPORTED"
			}
		default:
			return NewApiResult{}, errors.New("endpoint not certified")
		}
		if err != nil {
			return NewApiResult{}, err
		}
	}
	facts["origin"] = p.BaseURL
	saved := q
	if role == "ANONYMOUS" {
		saved.AccountID = ""
		saved.CredentialID = ""
	}
	if err = s.newApiSnapshot(ctx, saved, path, role, version, facts); err != nil {
		return NewApiResult{}, err
	}
	compatibility := "SCHEMA_VALIDATED"
	if certified {
		compatibility = "VERSION_CERTIFIED"
	}
	return NewApiResult{ProviderID: saved.ProviderID, AccountID: saved.AccountID, CredentialID: saved.CredentialID, Role: role, Version: version, Endpoint: path, Facts: facts, ObservedAt: now(), Source: "NEW_API_OFFICIAL", Compatibility: compatibility}, nil
}
func parseNewApiPricing(root map[string]json.RawMessage, key string) (map[string]any, error) {
	var entries []map[string]json.RawMessage
	if json.Unmarshal(root["data"], &entries) != nil || entries == nil {
		return nil, errors.New("malformed pricing")
	}
	items := []map[string]any{}
	for _, row := range entries {
		name := safeText(stringField(row, "model_name"), 256, key)
		if name == "" {
			continue
		}
		item := map[string]any{"model_name": name}
		for _, field := range []string{"quota_type", "model_ratio", "model_price", "completion_ratio", "cache_ratio", "create_cache_ratio", "image_ratio", "audio_ratio", "audio_completion_ratio", "vendor_id"} {
			if n := scalarField(row, field); n != nil {
				item[field] = n
			}
		}
		for _, field := range []string{"billing_mode", "billing_expr", "pricing_version"} {
			raw := stringField(row, field)
			if v := safeText(raw, 8192, key); v != "" {
				item[field] = v
			}
		}
		item["enable_groups"] = safeStrings(row["enable_groups"], key, 120)
		item["supported_endpoint_types"] = safeStrings(row["supported_endpoint_types"], key, 64)
		items = append(items, item)
	}
	ratios := map[string]any{}
	var rawRatios map[string]json.RawMessage
	_ = json.Unmarshal(root["group_ratio"], &rawRatios)
	for group := range rawRatios {
		if safeText(group, 120, key) == group {
			if n := scalarField(rawRatios, group); n != nil {
				ratios[group] = n
			} else {
				ratios[group] = nil
			}
		}
	}
	return map[string]any{"items": items, "pricing_items_count": len(items), "skipped_items": len(entries) - len(items), "group_ratio": ratios, "usable_groups": safeStrings(root["usable_group"], key, 120), "auto_groups": safeStrings(root["auto_groups"], key, 120), "pricing_version": safeText(stringField(root, "pricing_version"), 128, key)}, nil
}

var newApiOperations = map[string]string{"openai": "CHAT_COMPLETIONS", "openai-response": "RESPONSES", "openai-response-compact": "RESPONSES", "anthropic": "ANTHROPIC_MESSAGES", "gemini": "GEMINI_NATIVE", "image-generation": "IMAGES_GENERATION", "embedding": "EMBEDDINGS", "rerank": "RERANK"}

func (s *Server) mapNewApiPrices(ctx context.Context, q NewApiQuery, facts, status map[string]any) error {
	items, _ := facts["items"].([]map[string]any)
	ratios, _ := facts["group_ratio"].(map[string]any)
	usable, _ := facts["usable_groups"].([]string)
	unit := decimal(status["quota_per_unit"])
	for _, item := range items {
		groups, _ := item["enable_groups"].([]string)
		if oneOf("all", groups...) {
			groups = usable
		}
		if len(groups) == 0 {
			groups = []string{""}
		}
		ops, _ := item["supported_endpoint_types"].([]string)
		if len(ops) == 0 {
			ops = []string{"UNKNOWN"}
		}
		for _, group := range groups {
			ratio := decimal(ratios[group])
			for _, op := range ops {
				operation := newApiOperations[op]
				if operation == "" {
					operation = "UNKNOWN"
				}
				scope := Selection{ProviderID: q.ProviderID, AccountID: q.AccountID, ModelID: item["model_name"].(string), Group: group, Endpoint: operation}
				legs := PriceLegs{Unit: "USD/1K tokens"}
				quotaType, _ := item["quota_type"].(json.Number)
				if item["billing_mode"] != "tiered_expr" && ratio != nil {
					if string(quotaType) == "0" && unit != nil && unit.Sign() > 0 {
						model := decimal(item["model_ratio"])
						if model != nil {
							in := new(big.Rat).Quo(new(big.Rat).Mul(new(big.Rat).Mul(model, ratio), big.NewRat(1000, 1)), unit)
							legs.Input = exactString(in)
							if out := decimal(item["completion_ratio"]); out != nil {
								legs.Output = exactString(new(big.Rat).Mul(in, out))
							}
							if cache := decimal(item["cache_ratio"]); cache != nil {
								legs.Cache = exactString(new(big.Rat).Mul(in, cache))
							}
							legs.FixedAbsent = true
						}
					}
					if string(quotaType) == "1" {
						if fixed := decimal(item["model_price"]); fixed != nil {
							legs.Fixed = exactString(new(big.Rat).Mul(fixed, ratio))
							zero := "0"
							legs.Input = &zero
							legs.Output = &zero
							legs.Unit = "USD/request"
						}
					}
				}
				nominalState := "UNKNOWN"
				if operation == "CHAT_COMPLETIONS" {
					nominalState = priceState(legs)
				}
				if err := s.observe(ctx, scope, "NOMINAL_PRICE", "NEW_API_OFFICIAL", "SUCCESS", map[string]any{"origin": q.Origin, "price": legs, "nominal_price_state": nominalState, "billing_mode": item["billing_mode"], "billing_expr": item["billing_expr"], "effective_charge": nil, "pricing_version": facts["pricing_version"]}); err != nil {
					return err
				}
			}
		}
	}
	return nil
}
func parseNewApiPerformance(root map[string]json.RawMessage, key string, detail bool) (map[string]any, error) {
	data, err := getNewAPIData(root)
	if err != nil {
		return nil, err
	}
	field := "models"
	if detail {
		field = "groups"
	}
	var rows []map[string]json.RawMessage
	if json.Unmarshal(data[field], &rows) != nil || rows == nil {
		return nil, errors.New("malformed perf")
	}
	out := []map[string]any{}
	for _, row := range rows {
		nameKey := "model_name"
		if detail {
			nameKey = "group"
		}
		name := safeText(stringField(row, nameKey), 256, key)
		if name == "" {
			return nil, errors.New("perf identity missing")
		}
		entry := map[string]any{nameKey: name}
		for _, n := range []string{"avg_latency_ms", "success_rate", "avg_tps"} {
			value := scalarField(row, n)
			_, numeric := value.(json.Number)
			if !numeric || !validNewApiMetric(n, value) {
				return nil, errors.New("perf metric missing")
			}
			entry[n] = value
		}
		if detail {
			if value := intField(row, "avg_ttft_ms"); value != nil {
				if *value < 0 {
					return nil, errors.New("invalid TTFT")
				}
				entry["avg_ttft_ms"] = value
			} else {
				return nil, errors.New("TTFT missing")
			}
		}
		if n := scalarField(row, "request_count"); n != nil {
			entry["request_count"] = n
		}
		if detail {
			var series []map[string]json.RawMessage
			if json.Unmarshal(row["series"], &series) != nil {
				return nil, errors.New("series missing")
			}
			points := []map[string]any{}
			for _, p := range series {
				point := map[string]any{}
				for _, name := range []string{"ts", "avg_ttft_ms", "avg_latency_ms", "success_rate", "avg_tps"} {
					v := scalarField(p, name)
					if v == nil {
						return nil, errors.New("malformed series")
					}
					if !validNewApiMetric(name, v) {
						return nil, errors.New("invalid series metric")
					}
					point[name] = v
				}
				points = append(points, point)
			}
			entry["series"] = points
		} else {
			var rates []json.Number
			if json.Unmarshal(row["recent_success_rates"], &rates) == nil {
				entry["recent_success_rates"] = rates
			}
			if raw := row["recent_success_series"]; len(raw) > 0 {
				points, e := parseNewApiSeries(raw, []string{"ts", "success_rate"})
				if e != nil {
					return nil, e
				}
				entry["recent_success_series"] = points
			}
		}
		out = append(out, entry)
	}
	facts := map[string]any{field: out, "parser_schema": "new-api-performance-v1", "timestamp_unit": "UNIX_SECONDS", "success_rate_unit": "PERCENT"}
	for _, name := range []string{"window_start", "window_end"} {
		if raw := data[name]; len(raw) > 0 {
			value := intField(data, name)
			if value == nil || *value < 0 {
				return nil, errors.New("invalid performance window")
			}
			facts[name] = *value
		}
	}
	if start, ok := facts["window_start"].(int64); ok {
		if end, ok := facts["window_end"].(int64); ok && end < start {
			return nil, errors.New("reversed performance window")
		}
	}
	if raw := data["summary"]; len(raw) > 0 {
		var summary map[string]json.RawMessage
		if json.Unmarshal(raw, &summary) != nil || summary == nil {
			return nil, errors.New("invalid summary")
		}
		clean := map[string]any{}
		for _, name := range []string{"avg_ttft_ms", "avg_latency_ms", "avg_tps", "success_rate", "request_count"} {
			if v := scalarField(summary, name); v != nil {
				if !validNewApiMetric(name, v) {
					return nil, errors.New("invalid summary metric")
				}
				clean[name] = v
			}
		}
		facts["summary"] = clean
	}
	if detail {
		model := safeText(stringField(data, "model_name"), 256, key)
		schema := safeText(stringField(data, "series_schema"), 64, key)
		if model == "" || schema == "" {
			return nil, errors.New("perf header missing")
		}
		facts["model_name"] = model
		facts["series_schema"] = schema
		if raw := data["series"]; len(raw) > 0 {
			points, e := parseNewApiSeries(raw, []string{"ts", "avg_ttft_ms", "avg_latency_ms", "success_rate", "avg_tps"})
			if e != nil {
				return nil, e
			}
			facts["series"] = points
		}
	}
	return facts, nil
}
func validNewApiMetric(name string, value any) bool {
	n, ok := value.(json.Number)
	if !ok {
		return false
	}
	r := decimal(n)
	if r == nil || r.Sign() < 0 {
		return false
	}
	return name != "success_rate" || r.Cmp(big.NewRat(100, 1)) <= 0
}
func parseNewApiSeries(raw json.RawMessage, fields []string) ([]map[string]any, error) {
	var rows []map[string]json.RawMessage
	if json.Unmarshal(raw, &rows) != nil || rows == nil || len(rows) > 10000 {
		return nil, errors.New("invalid performance series")
	}
	points := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		point := map[string]any{}
		for _, field := range fields {
			v := scalarField(row, field)
			if !validNewApiMetric(field, v) {
				return nil, errors.New("invalid performance series metric")
			}
			point[field] = v
		}
		points = append(points, point)
	}
	return points, nil
}
func (s *Server) newApiQuery(w http.ResponseWriter, r *http.Request, path string) {
	var q NewApiQuery
	if !decodeJSON(w, r, &q) {
		return
	}
	if !validID(q.ProviderID) {
		http.Error(w, "provider required", 400)
		return
	}
	result, err := s.newApiRead(r.Context(), q, path)
	if err != nil {
		writeProviderProblem(w, err, path)
		return
	}
	jsonOK(w, 200, result)
}
func (s *Server) listNewApiSnapshots(w http.ResponseWriter, r *http.Request) {
	provider := r.URL.Query().Get("provider_id")
	if provider != "" && !validID(provider) {
		http.Error(w, "invalid provider", 400)
		return
	}
	// Public UI reads one exact source before applying the historical limit.
	// Frequent performance refreshes must never evict a notice's last-good read.
	public, endpoint, origin, model, hours := 0, "", "", "", 0
	if value := r.URL.Query().Get("public"); value != "" {
		if value != "1" || !validID(provider) {
			http.Error(w, "invalid public snapshot scope", 400)
			return
		}
		public = 1
		operation := r.URL.Query().Get("operation")
		endpoint = map[string]string{"status": "/api/status", "notice": "/api/notice", "pricing": "/api/pricing", "summary": "/api/perf-metrics/summary", "performance": "/api/perf-metrics"}[operation]
		origin = r.URL.Query().Get("origin")
		if endpoint == "" || !validateHTTPS(origin) {
			http.Error(w, "invalid public snapshot origin or operation", 400)
			return
		}
		if operation == "performance" {
			model = r.URL.Query().Get("model_id")
			if !validID(model) {
				http.Error(w, "model required", 400)
				return
			}
		}
		if operation == "performance" || operation == "summary" {
			hours, _ = strconv.Atoi(r.URL.Query().Get("hours"))
			if hours < 1 || hours > 720 {
				http.Error(w, "invalid snapshot window", 400)
				return
			}
		}
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,account_id,credential_id,credential_role,endpoint,facts_json,provider_version,observed_at FROM newapi_snapshots WHERE (?='' OR provider_id=?) AND (?=0 OR (credential_role='ANONYMOUS' AND account_id='' AND credential_id='' AND endpoint=? AND json_extract(facts_json,'$.origin')=? AND (?='' OR json_extract(facts_json,'$.model_name')=?) AND (?=0 OR json_extract(facts_json,'$.hours')=?))) ORDER BY rowid DESC LIMIT 200", provider, provider, public, endpoint, origin, model, model, hours, hours)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var p, a, c, role, endpoint, raw, version string
		var at int64
		if rows.Scan(&p, &a, &c, &role, &endpoint, &raw, &version, &at) != nil {
			continue
		}
		var facts map[string]any
		if json.Unmarshal([]byte(raw), &facts) != nil {
			continue
		}
		out = append(out, map[string]any{"provider_id": p, "account_id": a, "credential_id": c, "credential_role": role, "endpoint": endpoint, "facts": facts, "version": version, "observed_at": at, "source": "NEW_API_OFFICIAL"})
	}
	jsonOK(w, 200, out)
}
func (s *Server) newApiStatusHTTP(w http.ResponseWriter, r *http.Request) {
	s.newApiQuery(w, r, "/api/status")
}
func (s *Server) newApiPricingHTTP(w http.ResponseWriter, r *http.Request) {
	s.newApiQuery(w, r, "/api/pricing")
}
func (s *Server) newApiAccountHTTP(w http.ResponseWriter, r *http.Request) {
	s.newApiQuery(w, r, "/api/user/self")
}
func (s *Server) newApiKeyHTTP(w http.ResponseWriter, r *http.Request) {
	s.newApiQuery(w, r, "/api/usage/token/")
}
func enrichNewAPI(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	// Resolve one explicit account PAT; ambiguous roles are not picked arbitrarily.
	var pat string
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT c.id FROM credentials c JOIN providers p ON p.id=c.provider_id LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id WHERE c.provider_id=? AND c.account_id=? AND c.role='ACCOUNT_PAT' AND c.enabled=1 AND COALESCE(o.base_url,p.base_url)=?", v.ProviderID, v.AccountID, p.BaseURL)
	if err != nil {
		return err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	if len(ids) == 1 {
		pat = ids[0]
	}
	q := NewApiQuery{ProviderID: v.ProviderID, AccountID: v.AccountID, CredentialID: pat, ModelID: v.ModelID, Hours: 168, Origin: p.BaseURL}
	partial := false
	for _, path := range []string{"/api/pricing", "/api/perf-metrics/summary", "/api/perf-metrics", "/api/notice"} {
		result, err := s.newApiRead(ctx, q, path)
		if err != nil {
			partial = true
			continue
		}
		scope := v
		if path != "/api/pricing" {
			scope.CredentialID = ""
		}
		source := "NEW_API_OFFICIAL"
		if strings.Contains(path, "perf") {
			source = "PROVIDER_REPORTED"
		}
		if s.observe(ctx, scope, path, source, "SUCCESS", result.Facts) != nil {
			partial = true
		}
	}
	if pat != "" {
		if _, err := s.newApiRead(ctx, q, "/api/user/self"); err != nil {
			partial = true
		}
	}
	q.CredentialID = v.CredentialID
	if _, err := s.newApiRead(ctx, q, "/api/usage/token/"); err != nil {
		partial = true
	}
	if partial {
		return errors.New("some capabilities unavailable")
	}
	return nil
}
