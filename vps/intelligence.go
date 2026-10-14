package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"math/big"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

var decimalPattern = regexp.MustCompile(`^[+-]?(?:[0-9]+(?:[.][0-9]*)?|[.][0-9]+)(?:[eE][+-]?[0-9]{1,4})?$`)
var normalizeSeparators = regexp.MustCompile(`[_./:@\s-]+`)

func canonicalID(raw string) string {
	return strings.TrimSuffix(strings.Trim(normalizeSeparators.ReplaceAllString(strings.ToLower(strings.TrimSpace(raw)), "-"), "-"), "-latest")
}

type ModelMetadata struct {
	ProviderID    string   `json:"provider_id"`
	ModelID       string   `json:"model_id"`
	CanonicalID   string   `json:"canonical_id"`
	Aliases       []string `json:"aliases"`
	Category      string   `json:"primary_category"`
	Capabilities  []string `json:"capabilities"`
	Modalities    []string `json:"modalities"`
	Endpoints     []string `json:"endpoint_types"`
	Vendor        string   `json:"vendor"`
	Description   string   `json:"description"`
	ContextTokens *int64   `json:"context_tokens,omitempty"`
	Source        string   `json:"source"`
	AliasSource   string   `json:"alias_rule_source,omitempty"`
}

var capabilityNames = []string{"REASONING", "CODING", "TOOL_CALLING", "FUNCTION_CALLING", "STRUCTURED_OUTPUT", "JSON_MODE", "VISION", "FILES", "IMAGE_GENERATION", "IMAGE_EDITING", "AUDIO_INPUT", "AUDIO_OUTPUT", "WEB_SEARCH", "COMPUTER_USE", "LONG_CONTEXT", "EMBEDDING", "RERANK", "SAFETY", "OTHER"}

func validStrings(a []string, allowed []string, max int) bool {
	if len(a) > max {
		return false
	}
	for _, v := range a {
		if !oneOf(v, allowed...) {
			return false
		}
	}
	return true
}
func (s *Server) metadata(ctx context.Context, p, m string) ModelMetadata {
	out := ModelMetadata{ProviderID: p, ModelID: m, CanonicalID: canonicalID(m), Category: "OTHER", Source: "NORMALIZATION", Capabilities: []string{}, Modalities: []string{}, Endpoints: []string{}, Aliases: []string{}}
	var raw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT metadata_json FROM model_metadata WHERE provider_id=? AND model_id=?", p, m).Scan(&raw) == nil {
		_ = json.Unmarshal([]byte(raw), &out)
	}
	if out.Source != "MANUAL" {
		if rule := s.activeKnowledge(ctx, "MODEL_ALIAS_RULE", p+"/"+m); validID(str(rule["canonical_id"])) {
			out.CanonicalID = str(rule["canonical_id"])
			out.AliasSource = "USER_APPROVED_ALIAS_RULE"
		}
	}
	return out
}
func (s *Server) setMetadata(w http.ResponseWriter, r *http.Request) {
	var m ModelMetadata
	if !decodeJSON(w, r, &m) {
		return
	}
	if !validID(m.ProviderID) || !validID(m.ModelID) || !validStrings(m.Capabilities, capabilityNames, 24) || !validStrings(m.Modalities, []string{"TEXT_INPUT", "TEXT_OUTPUT", "IMAGE_INPUT", "IMAGE_OUTPUT", "AUDIO_INPUT", "AUDIO_OUTPUT", "FILE_INPUT"}, 7) || !validStrings(m.Endpoints, []string{"CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO", "EMBEDDINGS", "RERANK", "CUSTOM", "AUDIO_SPEECH", "GEMINI_SPEECH"}, 12) || !oneOf(m.Category, "CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "SAFETY", "OTHER") || len(m.Aliases) > 20 || len(m.Description) > 4096 || len(m.Vendor) > 120 || m.ContextTokens != nil && (*m.ContextTokens <= 0 || *m.ContextTokens > 16777216) {
		http.Error(w, "invalid model metadata", 400)
		return
	}
	for _, alias := range m.Aliases {
		if safeText(alias, 200) != alias || strings.TrimSpace(alias) == "" {
			http.Error(w, "invalid alias", 400)
			return
		}
	}
	if m.CanonicalID == "" {
		m.CanonicalID = canonicalID(m.ModelID)
	}
	if !validID(m.CanonicalID) {
		http.Error(w, "invalid canonical ID", 400)
		return
	}
	m.Source = "MANUAL"
	raw, _ := json.Marshal(m)
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO model_metadata VALUES(?,?,?,?) ON CONFLICT(provider_id,model_id) DO UPDATE SET canonical_id=excluded.canonical_id,metadata_json=excluded.metadata_json", m.ProviderID, m.ModelID, m.CanonicalID, string(raw)); err != nil {
		http.Error(w, "model missing", 422)
		return
	}
	for _, alias := range append(m.Aliases, m.ModelID) {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO aliases VALUES(?,?,?,'MANUAL') ON CONFLICT(provider_id,raw_id) DO UPDATE SET canonical_id=excluded.canonical_id,source=excluded.source", m.ProviderID, alias, m.CanonicalID); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, m)
}
func (s *Server) confirmCapabilities(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Selection
		Capabilities []string `json:"capabilities"`
		Confirm      bool     `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !s.validateSelection(r.Context(), v.Selection) || !validStrings(v.Capabilities, capabilityNames, 24) {
		http.Error(w, "explicit selected-scope capability confirmation required", 422)
		return
	}
	raw, _ := json.Marshal(v.Capabilities)
	if _, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO capability_confirmations VALUES(?,?,'MANUAL_CONFIRMATION',?) ON CONFLICT(scope_key) DO UPDATE SET capabilities_json=excluded.capabilities_json,observed_at=excluded.observed_at", scopeKey(v.Selection), string(raw), now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"capabilities": v.Capabilities, "source": "MANUAL_CONFIRMATION"})
}
func (s *Server) capabilities(ctx context.Context, v Selection) []string {
	var raw string
	a := []string{}
	if s.Store.DB.QueryRowContext(ctx, "SELECT capabilities_json FROM capability_confirmations WHERE scope_key=?", scopeKey(v)).Scan(&raw) == nil {
		_ = json.Unmarshal([]byte(raw), &a)
	}
	return a
}

type Observation struct {
	ID string `json:"id"`
	Selection
	Kind       string         `json:"kind"`
	Source     string         `json:"source"`
	Status     string         `json:"status"`
	Facts      map[string]any `json:"facts"`
	ObservedAt int64          `json:"observed_at"`
}

func (s *Server) observe(ctx context.Context, v Selection, kind, source, status string, facts map[string]any) error {
	if snapshot, ok := ctx.Value(credentialObservationKey{}).(credentialObservation); ok && snapshot.Provider == v.ProviderID && snapshot.Account == v.AccountID && validateHTTPS(snapshot.Origin) {
		copy := map[string]any{}
		for k, x := range facts {
			copy[k] = x
		}
		copy["origin"] = snapshot.Origin
		facts = copy
	}

	if v.CredentialID != "" {
		version := 0
		if snapshot, ok := ctx.Value(credentialObservationKey{}).(credentialObservation); ok && snapshot.Provider == v.ProviderID && snapshot.Account == v.AccountID && snapshot.Credential == v.CredentialID {
			version = snapshot.Version
		} else {
			s.Store.DB.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&version)
		}
		copied := map[string]any{}
		for k, x := range facts {
			copied[k] = x
		}
		copied["credential_version"] = version
		facts = copied
	}
	raw, err := json.Marshal(facts)
	if err != nil || len(raw) > 512<<10 {
		return sql.ErrNoRows
	}
	_, err = s.Store.DB.ExecContext(ctx, "INSERT INTO observations VALUES(?,?,?,?,?,?,?)", newID(), scopeKey(v), kind, source, status, string(raw), now())
	return err
}
func (s *Server) observations(ctx context.Context, v Selection) []Observation {
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT id,kind,source,status,facts_json,observed_at FROM observations WHERE scope_key=? ORDER BY rowid DESC LIMIT 200", scopeKey(v))
	if err != nil {
		return []Observation{}
	}
	defer rows.Close()
	out := []Observation{}
	for rows.Next() {
		var x Observation
		var raw string
		x.Selection = v
		if rows.Scan(&x.ID, &x.Kind, &x.Source, &x.Status, &raw, &x.ObservedAt) == nil && json.Unmarshal([]byte(raw), &x.Facts) == nil {
			out = append(out, x)
		}
	}
	return out
}
func selectionQuery(r *http.Request) Selection {
	q := r.URL.Query()
	return Selection{ProviderID: q.Get("provider_id"), AccountID: q.Get("account_id"), CredentialID: q.Get("credential_id"), ModelID: q.Get("model_id"), Group: q.Get("group"), Endpoint: q.Get("endpoint")}
}
func validScope(v Selection) bool {
	return validID(v.ProviderID) && validID(v.AccountID) && validID(v.CredentialID) && validID(v.ModelID) && safeText(v.Group, 120) == v.Group && validID(v.Endpoint) && v.Endpoint != "MODELS"
}
func (s *Server) modelDetail(w http.ResponseWriter, r *http.Request) {
	v := selectionQuery(r)
	if !validScope(v) {
		http.Error(w, "full selected scope required", 400)
		return
	}
	meta := s.metadata(r.Context(), v.ProviderID, v.ModelID)
	// Last-good is reported separately from the latest attempt, in each source namespace.
	obs := s.observations(r.Context(), v)
	latest, lastGood := map[string]Observation{}, map[string]Observation{}
	for _, o := range obs {
		k := o.Kind + ":" + o.Source
		if _, ok := latest[k]; !ok {
			latest[k] = o
		}
		if _, ok := lastGood[k]; !ok && o.Status == "SUCCESS" {
			lastGood[k] = o
		}
	}
	goodRows, goodErr := s.Store.DB.QueryContext(r.Context(), "SELECT id,kind,source,facts_json,observed_at FROM observations o WHERE scope_key=? AND status='SUCCESS' AND NOT EXISTS(SELECT 1 FROM observations newer WHERE newer.scope_key=o.scope_key AND newer.kind=o.kind AND newer.source=o.source AND newer.status='SUCCESS' AND newer.rowid>o.rowid)", scopeKey(v))
	if goodErr == nil {
		for goodRows.Next() {
			var o Observation
			var raw string
			o.Selection = v
			o.Status = "SUCCESS"
			if goodRows.Scan(&o.ID, &o.Kind, &o.Source, &raw, &o.ObservedAt) == nil && json.Unmarshal([]byte(raw), &o.Facts) == nil {
				lastGood[o.Kind+":"+o.Source] = o
			}
		}
		goodRows.Close()
	}
	window := parseWindow(r)
	if window <= 0 {
		http.Error(w, "invalid window", 400)
		return
	}
	external := s.externalFor(r.Context(), meta.CanonicalID)
	accountScope := v
	accountScope.CredentialID = ""
	accountFacts := s.observations(r.Context(), accountScope)
	local := s.performance(r.Context(), v, window)
	if r.URL.Query().Get("window") == "custom" {
		start, e1 := strconv.ParseInt(r.URL.Query().Get("start"), 10, 64)
		end, e2 := strconv.ParseInt(r.URL.Query().Get("end"), 10, 64)
		if e1 != nil || e2 != nil || start < 0 || end <= start || end > now() {
			http.Error(w, "invalid custom time window", 400)
			return
		}
		local = s.performanceBetween(r.Context(), v, start, end)
	}
	providerScope := v
	providerScope.AccountID = ""
	providerScope.CredentialID = ""
	providerScope.Group = ""
	jsonOK(w, 200, map[string]any{"selection": v, "metadata": meta, "capabilities": s.capabilities(r.Context(), v), "latest": latest, "last_good": lastGood, "history": obs, "local": local, "external": external, "account_facts": accountFacts, "provider_facts": s.observations(r.Context(), providerScope), "resource_pools": s.resourcePools(r.Context(), v)})
}
func parseWindow(r *http.Request) int64 {
	raw := r.URL.Query().Get("window")
	switch raw {
	case "custom", "", "7d":
		return int64(7 * 24 * time.Hour / time.Millisecond)
	case "1h":
		return int64(time.Hour / time.Millisecond)
	case "24h":
		return int64(24 * time.Hour / time.Millisecond)
	case "30d":
		return int64(30 * 24 * time.Hour / time.Millisecond)
	case "all":
		return now()
	}
	return 0
}
func (s *Server) performance(ctx context.Context, v Selection, window int64) map[string]any {
	end := now()
	return s.performanceBetween(ctx, v, end-window, end)
}
func (s *Server) performanceBetween(ctx context.Context, v Selection, start, end int64) map[string]any {
	return s.performanceSQL(ctx, v, start, end, true)
}
func nullable(n sql.NullInt64) any {
	if n.Valid {
		return n.Int64
	}
	return nil
}
func distribution(a []float64) any {
	if len(a) == 0 {
		return nil
	}
	sort.Float64s(a)
	var sum float64
	for _, v := range a {
		sum += v
	}
	return map[string]float64{"mean": sum / float64(len(a)), "p50": quantile(a, .5), "p90": quantile(a, .9), "p95": quantile(a, .95)}
}

func quantile(a []float64, q float64) float64 {
	position := float64(len(a)-1) * q
	low := int(position)
	if low >= len(a)-1 {
		return a[len(a)-1]
	}
	return a[low] + (a[low+1]-a[low])*(position-float64(low))
}

type PriceLegs struct {
	Input       *string `json:"input"`
	Output      *string `json:"output"`
	Fixed       *string `json:"fixed"`
	FixedAbsent bool    `json:"fixed_absent"`
	Cache       *string `json:"cache"`
	Unit        string  `json:"unit"`
}

func decimal(v any) *big.Rat {
	var raw string
	switch n := v.(type) {
	case json.Number:
		raw = string(n)
	case string:
		raw = n
	default:
		return nil
	}
	if len(raw) > 128 || !decimalPattern.MatchString(raw) {
		return nil
	}
	if at := strings.IndexAny(raw, "eE"); at >= 0 {
		exponent, err := strconv.Atoi(raw[at+1:])
		if err != nil || exponent > 4096 || exponent < -4096 {
			return nil
		}
	}
	r, ok := new(big.Rat).SetString(raw)
	if !ok {
		return nil
	}
	return r
}
func exactString(r *big.Rat) *string {
	if r == nil {
		return nil
	}
	scale := 34
	exponent := len(new(big.Int).Abs(r.Num()).String()) - len(r.Denom().String())
	if exponent < 0 {
		scale += -exponent
	}
	if scale > 4130 {
		scale = 4130
	}
	raw := strings.TrimRight(strings.TrimRight(r.FloatString(scale), "0"), ".")
	if raw == "" || raw == "-0" {
		raw = "0"
	}
	return &raw
}
func priceState(p PriceLegs) string {
	if p.Unit == "USD/request" {
		if p.Fixed == nil {
			if p.FixedAbsent {
				return "FREE_NOW"
			}
			return "UNKNOWN"
		}
		n := decimal(*p.Fixed)
		if n == nil || n.Sign() < 0 {
			return "UNKNOWN"
		}
		if n.Sign() == 0 {
			return "FREE_NOW"
		}
		return "PAID"
	}
	if p.Input == nil || p.Output == nil || p.Fixed == nil && !p.FixedAbsent {
		return "UNKNOWN"
	}
	in, out := decimal(*p.Input), decimal(*p.Output)
	fixed := big.NewRat(0, 1)
	if p.Fixed != nil {
		fixed = decimal(*p.Fixed)
	}
	if in == nil || out == nil || fixed == nil || in.Sign() < 0 || out.Sign() < 0 || fixed.Sign() < 0 {
		return "UNKNOWN"
	}
	if in.Sign() == 0 && out.Sign() == 0 && fixed.Sign() == 0 {
		return "FREE_NOW"
	}
	if in.Sign() == 0 || out.Sign() == 0 {
		return "PARTIALLY_FREE"
	}
	return "PAID"
}
func (s *Server) lifecycle(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Selection
		State     string `json:"state"`
		Favorite  bool   `json:"favorite"`
		Watchlist bool   `json:"watchlist"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validScope(v.Selection) || !oneOf(v.State, "ACTIVE", "HIDDEN", "ARCHIVED") {
		http.Error(w, "invalid lifecycle", 400)
		return
	}
	var n int
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT 1 FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint).Scan(&n) != nil {
		http.Error(w, "membership missing", 404)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(r.Context(), "INSERT INTO membership_state VALUES(?,?,?,?) ON CONFLICT(scope_key) DO UPDATE SET state=excluded.state,favorite=excluded.favorite,watchlist=excluded.watchlist", scopeKey(v.Selection), v.State, v.Favorite, v.Watchlist)
	if err != nil || bumpMembership(tx, v.Selection) != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, v)
}

// Resource pools are displayed at their own scope. They never replace model-local uptime or price.
func (s *Server) resourcePools(ctx context.Context, v Selection) []map[string]any {
	rows, err := s.Store.DB.QueryContext(ctx, "SELECT id,scope_key,kind,source,status,facts_json,observed_at FROM observations WHERE json_extract(scope_key,'$.provider_id')=? AND json_extract(scope_key,'$.account_id')=? AND json_extract(scope_key,'$.model_id')='' AND (json_extract(scope_key,'$.credential_id')=? OR json_extract(scope_key,'$.credential_id')='') ORDER BY rowid DESC LIMIT 100", v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		return []map[string]any{}
	}
	out := []map[string]any{}
	for rows.Next() {
		var id, key, kind, source, status, raw string
		var at int64
		if rows.Scan(&id, &key, &kind, &source, &status, &raw, &at) == nil {
			var scope Selection
			var facts any
			_ = json.Unmarshal([]byte(key), &scope)
			_ = json.Unmarshal([]byte(raw), &facts)
			out = append(out, map[string]any{"id": id, "scope": scope, "kind": kind, "source": source, "status": status, "facts": facts, "observed_at": at})
		}
	}
	rows.Close()
	rows, err = s.Store.DB.QueryContext(ctx, "SELECT id,credential_id,credential_role,endpoint,facts_json,observed_at FROM newapi_snapshots WHERE provider_id=? AND account_id=? AND ((credential_role='INFERENCE' AND credential_id=?) OR credential_role='ACCOUNT_PAT') ORDER BY rowid DESC LIMIT 100", v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id, credential, role, endpoint, raw string
		var at int64
		if rows.Scan(&id, &credential, &role, &endpoint, &raw, &at) != nil {
			continue
		}
		var facts map[string]any
		if json.Unmarshal([]byte(raw), &facts) != nil {
			continue
		}
		if model, ok := facts["model_name"].(string); ok && model != v.ModelID {
			continue
		}
		out = append(out, map[string]any{"id": id, "provider_id": v.ProviderID, "account_id": v.AccountID, "credential_id": credential, "role": role, "endpoint": endpoint, "facts": facts, "observed_at": at, "source": "NEW_API_OFFICIAL"})
	}
	return out
}
