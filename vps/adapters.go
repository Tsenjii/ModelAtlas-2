package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type CatalogEntry struct {
	ID    string
	Name  string
	Meta  ModelMetadata
	Facts map[string]any
	Price *PriceLegs
}
type ProviderAdapter struct {
	Discover func(*Server, context.Context, Provider, ProviderConfig, string) ([]CatalogEntry, error)
	Enrich   func(*Server, context.Context, Selection, Provider, ProviderConfig, string) error
}

var adapterRegistry = map[string]ProviderAdapter{
	"GENERIC": {Discover: openAIModels}, "NEW_API": {Discover: openAIModels, Enrich: enrichNewAPI}, "ANTHROPIC": {Discover: openAIModels},
	"OPENROUTER_FREE": {Discover: openAIModels, Enrich: enrichOpenRouter}, "AMD_SHARED": {Discover: openAIModels, Enrich: enrichAMD}, "AMD_DEDICATED": {Discover: openAIModels},
	"NVIDIA_NIM_HOSTED": {Discover: openAIModels, Enrich: enrichNvidiaHosted}, "NVIDIA_NIM_SELF_HOSTED": {Discover: openAIModels, Enrich: enrichNvidiaMetrics},
	"GEMINI_FREE": {Discover: geminiModels, Enrich: enrichGemini}, "GROQ_FREE": {Discover: openAIModels, Enrich: enrichGroq},
}

func openAIModels(s *Server, ctx context.Context, p Provider, c ProviderConfig, key string) ([]CatalogEntry, error) {
	rawURL, err := endpointURL(p, c, "MODELS", "")
	if err != nil {
		return nil, err
	}
	root, _, err := s.getJSON(ctx, p, c, rawURL, key, 4<<20)
	if err != nil {
		return nil, err
	}
	var data []map[string]json.RawMessage
	if json.Unmarshal(root["data"], &data) != nil || data == nil || len(data) > 10000 {
		return nil, errors.New("malformed catalog")
	}
	out := []CatalogEntry{}
	for _, row := range data {
		b, _ := json.Marshal(row)
		if safeText(string(b), 1<<18, key) == "" {
			continue
		}
		id := stringField(row, "id")
		if !validID(id) {
			continue
		}
		name := safeText(stringField(row, "name"), 200, key)
		if name == "" {
			name = id
		}
		meta := ModelMetadata{ProviderID: p.ID, ModelID: id, CanonicalID: canonicalID(id), Category: "OTHER", Source: c.Adapter + "_CATALOG", Vendor: safeText(stringField(row, "owned_by"), 120, key), Capabilities: []string{}, Modalities: []string{}, Endpoints: []string{"CHAT_COMPLETIONS"}, Aliases: []string{}}
		if count := intField(row, "context_length"); count != nil && *count > 0 && *count <= 16777216 {
			meta.ContextTokens = count
		}
		var supported []string
		_ = json.Unmarshal(row["supported_parameters"], &supported)
		if oneOf("tools", supported...) {
			meta.Capabilities = append(meta.Capabilities, "TOOL_CALLING")
		}
		entry := CatalogEntry{ID: id, Name: name, Meta: meta, Facts: map[string]any{"availability": "CATALOG_LISTED"}}
		if oneOf(c.Adapter, "OPENROUTER_FREE", "AMD_SHARED") {
			var price map[string]json.RawMessage
			_ = json.Unmarshal(row["pricing"], &price)
			legs := PriceLegs{Unit: "USD/1K tokens"}
			legs.Input = scalePrice(price["prompt"], 1000)
			legs.Output = scalePrice(price["completion"], 1000)
			legs.Cache = scalePrice(price["input_cache_read"], 1000)
			if v, ok := price["request"]; ok {
				legs.Fixed = scalePrice(v, 1)
			} else {
				legs.FixedAbsent = true
			}
			entry.Price = &legs
			entry.Facts["nominal_price_state"] = priceState(legs)
		}
		out = append(out, entry)
	}
	return out, nil
}
func scalePrice(raw json.RawMessage, multiplier int64) *string {
	var v any
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	if dec.Decode(&v) != nil {
		return nil
	}
	r := decimal(v)
	if r == nil {
		return nil
	}
	return exactString(r.Mul(r, big.NewRat(multiplier, 1)))
}
func geminiModels(s *Server, ctx context.Context, p Provider, c ProviderConfig, key string) ([]CatalogEntry, error) {
	rawURL, err := endpointURL(p, c, "MODELS", "")
	if err != nil {
		return nil, err
	}
	root, _, err := s.getJSON(ctx, p, c, rawURL, key, 4<<20)
	if err != nil {
		return nil, err
	}
	var rows []map[string]json.RawMessage
	if json.Unmarshal(root["models"], &rows) != nil || rows == nil {
		return nil, errors.New("invalid Gemini models")
	}
	out := []CatalogEntry{}
	for _, row := range rows {
		id := stringField(row, "name")
		if !validID(id) || safeText(id, 256, key) == "" {
			continue
		}
		meta := ModelMetadata{ProviderID: p.ID, ModelID: id, CanonicalID: canonicalID(id), Category: "CHAT_REASONING", Source: "GEMINI_CATALOG", Vendor: "Google", Endpoints: []string{"GEMINI_NATIVE"}, Capabilities: []string{}, Modalities: []string{}, Aliases: []string{}}
		meta.ContextTokens = intField(row, "inputTokenLimit")
		name := safeText(stringField(row, "displayName"), 200, key)
		if name == "" {
			name = id
		}
		out = append(out, CatalogEntry{ID: id, Name: name, Meta: meta, Facts: map[string]any{"availability": "CATALOG_LISTED"}})
	}
	return out, nil
}
func (s *Server) discover(ctx context.Context, v syncRequest) (int, error) {
	p, key, err := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		return 0, err
	}
	c := s.config(ctx, p)
	adapter, ok := adapterRegistry[c.Adapter]
	if !ok || adapter.Discover == nil {
		return 0, errors.New("unsupported discovery")
	}
	entries, custom, err := s.connectorCatalog(ctx, v)
	if !custom {
		entries, err = adapter.Discover(s, ctx, p, c, key)
	}
	if err != nil {
		return 0, err
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	for _, entry := range entries {
		if _, err = tx.ExecContext(ctx, "INSERT INTO models(provider_id,id,display_name,price_state,quota_state,provenance,updated_at) VALUES(?,?,?,'UNKNOWN','UNKNOWN','PROVIDER_CATALOG',?) ON CONFLICT(provider_id,id) DO UPDATE SET display_name=excluded.display_name,provenance=excluded.provenance,updated_at=excluded.updated_at", p.ID, entry.ID, entry.Name, now()); err != nil {
			return 0, err
		}
		raw, _ := json.Marshal(entry.Meta)
		if _, err = tx.ExecContext(ctx, "INSERT INTO model_metadata VALUES(?,?,?,?) ON CONFLICT(provider_id,model_id) DO UPDATE SET canonical_id=excluded.canonical_id,metadata_json=excluded.metadata_json WHERE json_extract(model_metadata.metadata_json,'$.source')!='MANUAL'", p.ID, entry.ID, entry.Meta.CanonicalID, string(raw)); err != nil {
			return 0, err
		}
	}
	if err = tx.Commit(); err != nil {
		return 0, err
	}
	var group string
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT group_id FROM credential_context WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&group)
	for _, entry := range entries {
		scope := Selection{ProviderID: p.ID, AccountID: v.AccountID, CredentialID: v.CredentialID, ModelID: entry.ID, Group: group, Endpoint: "CHAT_COMPLETIONS"}
		facts := entry.Facts
		if entry.Price != nil {
			facts["price"] = entry.Price
		}
		source := c.Adapter + "_OFFICIAL"
		if override := str(facts["source_override"]); strings.HasPrefix(override, "DECLARATIVE_PROVIDER_META:") {
			source = override
		}
		if err = s.observe(ctx, scope, "CATALOG", source, "SUCCESS", facts); err != nil {
			return 0, err
		}
	}
	return len(entries), nil
}
func originPath(p Provider, path string) string {
	u, _ := url.Parse(p.BaseURL)
	u.Path = path
	u.RawPath = ""
	u.RawQuery = ""
	return u.String()
}
func enrichOpenRouter(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	root, _, err := s.getJSON(ctx, p, c, originPath(p, "/api/v1/key"), key, 256<<10)
	if err != nil {
		return err
	}
	data, err := getNewAPIData(root)
	if err != nil {
		return err
	}
	var daily map[string]json.RawMessage
	_ = json.Unmarshal(data["free_model_daily_requests"], &daily)
	facts := map[string]any{"scope_kind": "PER_KEY", "estimated": true, "is_free_tier": scalarField(data, "is_free_tier")}
	for _, field := range []string{"used", "limit", "remaining", "exempt"} {
		if n := scalarField(daily, field); n != nil {
			facts[field] = n
		}
	}
	// These are account policy facts, not per-model ceilings or recurring qualification.
	scope := v
	scope.ModelID = ""
	scope.Group = ""
	scope.Endpoint = "UNKNOWN"
	return s.observe(ctx, scope, "TOKEN_QUOTA", "OPENROUTER_OFFICIAL", "SUCCESS", facts)
}
func enrichAMD(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	root, _, err := s.getJSON(ctx, p, c, originPath(p, "/api/profile/model-usage"), key, 1<<20)
	if err != nil {
		return err
	}
	facts := numericTree(root, key, 0)
	scope := v
	scope.ModelID = ""
	scope.Endpoint = "UNKNOWN"
	return s.observe(ctx, scope, "ACCOUNT_USAGE", "AMD_OFFICIAL", "SUCCESS", facts)
}
func enrichNvidiaHosted(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	scope := Selection{ProviderID: v.ProviderID, AccountID: v.AccountID, Endpoint: "UNKNOWN"}
	return s.observe(ctx, scope, "QUOTA_LIMITS", "NVIDIA_HOSTED_DOC_CONTRACT", "SUCCESS", map[string]any{"scope_kind": "UNKNOWN", "rpm": nil, "rpd": nil, "tpd": nil, "pricing_mode": "NO_PER_TOKEN_BILLING", "account_limit_requires_login": true, "contract_origin": "Android NvidiaHostedAdapter; numeric current limit not re-verified"})
}
func enrichNvidiaMetrics(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	rawURL, err := endpointURL(p, ProviderConfig{Endpoints: map[string]string{"MODELS": "/metrics"}}, "MODELS", "")
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, "GET", rawURL, nil)
	if err != nil {
		return err
	}
	if s.authorizeUpstream(req, p, key, c) != nil {
		return errors.New("invalid credential")
	}
	client := *s.Client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return errors.New("metrics unavailable")
	}
	raw, err := readBounded(resp.Body, 1<<20)
	if err != nil {
		return err
	}
	facts := map[string]any{}
	scan := bufio.NewScanner(strings.NewReader(string(raw)))
	for scan.Scan() {
		line := scan.Text()
		if strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		name := strings.SplitN(fields[0], "{", 2)[0]
		if !oneOf(name, "vllm:num_requests_running", "vllm:num_requests_waiting", "vllm:gpu_cache_usage_perc", "vllm:prompt_tokens_total", "vllm:generation_tokens_total", "num_requests_running", "num_requests_waiting", "gpu_cache_usage_perc") {
			continue
		}
		n, err := strconv.ParseFloat(fields[1], 64)
		if err == nil {
			facts[name] = n
		}
	}
	if len(facts) == 0 {
		return errors.New("no supported metrics")
	}
	scope := v
	scope.CredentialID = ""
	scope.ModelID = ""
	scope.Group = ""
	scope.Endpoint = "METRICS"
	return s.observe(ctx, scope, "SERVER_METRICS", "NVIDIA_SELF_HOSTED_OFFICIAL", "SUCCESS", facts)
}
func enrichGemini(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	if c.ProjectID == "" {
		return errors.New("Gemini project required for quota scope")
	}
	scope := v
	scope.Group = c.ProjectID
	scope.CredentialID = ""
	return s.observe(ctx, scope, "QUOTA_LIMITS", "GEMINI_DOC_CONTRACT", "SUCCESS", map[string]any{"scope_kind": "PER_PROJECT_MODEL", "project_id": c.ProjectID, "model_id": v.ModelID, "slice": "STANDARD_INTERACTIVE", "rpm_state": "UNKNOWN_REQUIRES_LOGIN", "rpd_state": "UNKNOWN_REQUIRES_LOGIN", "tpm_state": "UNKNOWN_REQUIRES_LOGIN", "tpd_state": "UNKNOWN", "reset": "PACIFIC_MIDNIGHT"})
}
func enrichGroq(s *Server, ctx context.Context, v Selection, p Provider, c ProviderConfig, key string) error {
	return s.observe(ctx, v, "QUOTA_LIMITS", "GROQ_DOC_CONTRACT", "SUCCESS", map[string]any{"scope_kind": "PER_ORG_MODEL", "rpm_state": "UNKNOWN_REQUIRES_LOGIN", "rpd_state": "UNKNOWN_REQUIRES_LOGIN", "tpm_state": "UNKNOWN_REQUIRES_LOGIN", "tpd_state": "UNKNOWN_REQUIRES_LOGIN"})
}

// Only approved numeric usage keys are copied; unknown textual fields cannot leak identities.
func numericTree(root map[string]json.RawMessage, key string, depth int) map[string]any {
	out := map[string]any{}
	if depth > 3 {
		return out
	}
	for _, name := range []string{"data", "models", "by_model", "usage", "quota", "remaining", "used", "limit", "total", "requests", "request_count", "rpm", "rpd", "tpm", "tpd", "daily_limit_usd", "daily_used_usd", "daily_remaining_usd", "reset_at", "input_tokens", "output_tokens", "total_tokens"} {
		raw, ok := root[name]
		if !ok {
			continue
		}
		if v := scalarField(root, name); v != nil {
			out[name] = v
			continue
		}
		var nested map[string]json.RawMessage
		if json.Unmarshal(raw, &nested) == nil {
			out[name] = numericTree(nested, key, depth+1)
		}
	}
	return out
}
func (s *Server) scheduleEnrichment(ctx context.Context, v Selection) {
	raw := "{}"
	_, _ = s.Store.DB.ExecContext(ctx, "INSERT INTO sync_history VALUES(?,?,'PENDING',?,?)", newID(), scopeKey(v), raw, now())
	s.flightMu.Lock()
	wake := s.wake
	s.flightMu.Unlock()
	if wake != nil {
		select {
		case wake <- struct{}{}:
		default:
		}
	}
}
func (s *Server) runPending(ctx context.Context) {
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT id,scope_key FROM sync_history WHERE status='PENDING' ORDER BY rowid LIMIT 2")
	if err != nil {
		return
	}
	type job struct{ id, raw string }
	jobs := []job{}
	for rows.Next() {
		var j job
		if rows.Scan(&j.id, &j.raw) == nil {
			jobs = append(jobs, j)
		}
	}
	rows.Close()
	for _, j := range jobs {
		if ctx.Err() != nil {
			return
		}
		var v Selection
		if json.Unmarshal([]byte(j.raw), &v) != nil {
			continue
		}
		runCtx, cancel := context.WithTimeout(ctx, 45*time.Second)
		facts := s.enrich(runCtx, v)
		cancel()
		raw, _ := json.Marshal(facts)
		status := "SUCCESS"
		for _, v := range facts {
			if v != "SUCCESS" && v != "UNSUPPORTED" {
				status = "PARTIAL"
			}
		}
		_, _ = s.Store.DB.ExecContext(ctx, "UPDATE sync_history SET status=?,summary_json=?,observed_at=? WHERE id=?", status, string(raw), now(), j.id)
	}
}
func (s *Server) worker(ctx context.Context) {
	s.flightMu.Lock()
	s.wake = make(chan struct{}, 1)
	wake := s.wake
	s.flightMu.Unlock()
	timer := time.NewTicker(30 * time.Second)
	defer timer.Stop()
	s.runPending(ctx)
	s.runResearchPending(ctx)
	s.expireResearchFacts(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-wake:
			s.runPending(ctx)
			s.runResearchPending(ctx)
			s.expireResearchFacts(ctx)
		case <-timer.C:
			s.runPending(ctx)
			s.runResearchPending(ctx)
			s.expireResearchFacts(ctx)
		}
	}
}
func (s *Server) enrich(ctx context.Context, v Selection) map[string]string {
	out := map[string]string{}
	p, key, err := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		out["scope"] = "UNAVAILABLE"
		return out
	}
	cfg := s.config(ctx, p)
	count, err := s.discover(ctx, syncRequest{ProviderID: v.ProviderID, AccountID: v.AccountID, CredentialID: v.CredentialID})
	_ = count
	if err != nil {
		out["catalog"] = "FAILED"
		_ = s.observe(ctx, v, "CATALOG", cfg.Adapter+"_OFFICIAL", "FAILED", map[string]any{})
	} else {
		out["catalog"] = "SUCCESS"
	}
	adapter := adapterRegistry[cfg.Adapter]
	if adapter.Enrich != nil {
		if err = adapter.Enrich(s, ctx, v, p, cfg, key); err != nil {
			out["intelligence"] = "PARTIAL"
			_ = s.observe(ctx, v, "SYNC", cfg.Adapter+"_OFFICIAL", "FAILED", map[string]any{})
		} else {
			out["intelligence"] = "SUCCESS"
		}
	} else {
		out["intelligence"] = "UNSUPPORTED"
	}
	for k, status := range s.enrichConnectors(ctx, v) {
		out[k] = status
	}
	return out
}
func (s *Server) syncIntelligence(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.validateSelection(r.Context(), v) {
		http.Error(w, "selected scope missing", 422)
		return
	}
	jsonOK(w, 200, s.enrich(r.Context(), v))
}
func (s *Server) syncHistory(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,scope_key,status,summary_json,observed_at FROM sync_history ORDER BY rowid DESC LIMIT 100")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, key, status, raw string
		var at int64
		if rows.Scan(&id, &key, &status, &raw, &at) == nil {
			var scope Selection
			var summary any
			_ = json.Unmarshal([]byte(key), &scope)
			_ = json.Unmarshal([]byte(raw), &summary)
			out = append(out, map[string]any{"id": id, "selection": scope, "status": status, "summary": summary, "at": at})
		}
	}
	jsonOK(w, 200, out)
}
