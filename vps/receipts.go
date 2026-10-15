package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type ReceiptFact struct {
	ID         string `json:"id"`
	Selection  `json:"scope"`
	Kind       string         `json:"kind"`
	Source     string         `json:"source"`
	ObservedAt int64          `json:"observed_at"`
	Facts      map[string]any `json:"facts"`
}
type RequestReceipt struct {
	Version           int            `json:"version"`
	CredentialVersion int            `json:"credential_version"`
	Origin            string         `json:"origin"`
	CapturedAt        int64          `json:"captured_at"`
	Price             *ReceiptFact   `json:"pricing_snapshot"`
	PriceState        string         `json:"price_state"`
	NominalPriceState string         `json:"nominal_price_state"`
	QuotaState        string         `json:"quota_state"`
	QuotaBefore       []ReceiptFact  `json:"quota_before"`
	QuotaAfter        []ReceiptFact  `json:"quota_after"`
	ResponseQuota     map[string]any `json:"response_quota"`
	Cost              map[string]any `json:"cost"`
}

// Check the bytes produced by our own encoder, including nested JSON strings.
// Avoid rebuilding the entire conversation once for every historical secret.
func encodedJSONContainsSecret(raw []byte, secrets []string) bool {
	for _, secret := range secrets {
		if secret == "" {
			continue
		}
		value := secret
		for depth := 0; depth < 4; depth++ {
			if bytes.Contains(raw, []byte(value)) {
				return true
			}
			b, _ := json.Marshal(value)
			value = string(b[1 : len(b)-1])
		}
	}
	return false
}
func attachmentContainsSecret(a Attachment, secrets []string) bool {
	raw, e := base64.StdEncoding.DecodeString(a.Data)
	if e != nil {
		return true
	}
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(raw, []byte(secret)) {
			return true
		}
	}
	return false
}

// Freeze only fresh evidence at the material version actually used by HTTP.
// Model/global nominal quotes and resource pools retain their individual scope.
func (s *Server) receiptSnapshot(ctx context.Context, v Selection, p Provider, at int64, secrets []string) (*ReceiptFact, []ReceiptFact, error) {
	op := s.operation(ctx, v)
	nominal := v
	nominal.CredentialID = ""
	nominal.Endpoint = op
	credentialPool := v
	credentialPool.ModelID = ""
	credentialPool.Group = ""
	credentialPool.Endpoint = "UNKNOWN"
	accountPool := credentialPool
	accountPool.CredentialID = ""
	scopes := []string{scopeKey(v), scopeKey(nominal), scopeKey(credentialPool), scopeKey(accountPool)}
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT id,scope_key,kind,source,facts_json,observed_at FROM observations WHERE scope_key IN(?,?,?,?) AND status='SUCCESS' AND observed_at BETWEEN ? AND ? ORDER BY rowid DESC LIMIT 100", scopes[0], scopes[1], scopes[2], scopes[3], at-86400000, at)
	if e != nil {
		return nil, nil, e
	}
	var quote, effectiveQuote *ReceiptFact
	pools := []ReceiptFact{}
	seen := map[string]bool{}
	for rows.Next() {
		var f ReceiptFact
		var scope, raw string
		if e = rows.Scan(&f.ID, &scope, &f.Kind, &f.Source, &raw, &f.ObservedAt); e != nil {
			rows.Close()
			return nil, nil, e
		}
		_ = json.Unmarshal([]byte(scope), &f.Selection)
		d := json.NewDecoder(strings.NewReader(raw))
		d.UseNumber()
		if d.Decode(&f.Facts) != nil {
			continue
		}
		if f.CredentialID != "" {
			epoch := quotaInteger(f.Facts["credential_version"])
			if epoch == nil && p.CredentialVersion != 0 || epoch != nil && *epoch != int64(p.CredentialVersion) {
				continue
			}
		}
		origin := str(f.Facts["origin"])
		if f.Source != "USER" && origin != p.BaseURL {
			continue
		}
		if effectiveQuote == nil && f.CredentialID == v.CredentialID && f.ModelID == v.ModelID && f.Group == v.Group && oneOf(f.Kind, "PRICING", "GROUP_PRICING", "MANUAL_PRICE") && f.Facts["effective_charge"] != nil {
			b, _ := json.Marshal(f.Facts["effective_charge"])
			var legs PriceLegs
			if json.Unmarshal(b, &legs) == nil && oneOf(legs.Unit, "USD/1K tokens", "USD/request") && priceState(legs) != "UNKNOWN" {
				copy := f
				copy.Facts = map[string]any{"effective_charge": legs, "basis": "EFFECTIVE"}
				effectiveQuote = &copy
			}
		}
		if f.ModelID == v.ModelID && f.Group == v.Group && oneOf(f.Kind, "MANUAL_PRICE", "CATALOG", "PRICING", "GROUP_PRICING", "NOMINAL_PRICE") && f.Facts["price"] != nil {
			raw, _ := json.Marshal(f.Facts["price"])
			var legs PriceLegs
			if json.Unmarshal(raw, &legs) != nil || !oneOf(legs.Unit, "USD/1K tokens", "USD/request") {
				continue
			}
			valid := true
			for _, leg := range []*string{legs.Input, legs.Output, legs.Fixed, legs.Cache} {
				if leg != nil && decimal(*leg) == nil {
					valid = false
				}
			}
			if !valid {
				continue
			}
			effective := f.Facts["effective_charge"]
			f.Facts = map[string]any{"price": legs, "basis": "NOMINAL", "pricing_version": safeText(str(f.Facts["pricing_version"]), 256, secrets...), "billing_mode": safeText(str(f.Facts["billing_mode"]), 120, secrets...)}
			if effective != nil && f.CredentialID == v.CredentialID && oneOf(f.Kind, "PRICING", "GROUP_PRICING", "MANUAL_PRICE") {
				b, _ := json.Marshal(effective)
				var charge PriceLegs
				if json.Unmarshal(b, &charge) == nil && oneOf(charge.Unit, "USD/1K tokens", "USD/request") && priceState(charge) != "UNKNOWN" {
					f.Facts["effective_charge"] = charge
				}
			}
			if f.Kind == "MANUAL_PRICE" {
				f.Facts["basis"] = "MANUAL"
			}
			if quote == nil {
				f.Source = redactConversationText(f.Source, secrets)
				quote = &f
			}
		} else if strings.Contains(f.Kind, "QUOTA") || f.Kind == "ACCOUNT_USAGE" {
			dedupe := scope + "/" + f.Kind + "/" + f.Source
			if seen[dedupe] || len(pools) >= 8 {
				continue
			}
			seen[dedupe] = true
			f.Facts = receiptQuotaFacts(f.Facts)
			if len(f.Facts) > 0 {
				pools = append(pools, f)
			}
		}
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return nil, nil, e
	}
	if effectiveQuote != nil {
		if quote == nil {
			copy := *effectiveQuote
			quote = &copy
			quote.Facts = map[string]any{}
		}
		quote.Facts["effective_charge"] = effectiveQuote.Facts["effective_charge"]
		quote.Facts["effective_evidence"] = effectiveQuote
	}

	rows, e = s.Store.DB.QueryContext(ctx, `SELECT n.id,n.credential_id,n.credential_role,n.endpoint,n.facts_json,n.observed_at FROM newapi_snapshots n JOIN credentials c ON c.provider_id=n.provider_id AND c.account_id=n.account_id AND c.id=n.credential_id WHERE n.provider_id=? AND n.account_id=? AND n.provider_version='v1.0.0-rc.25' AND n.source='NEW_API_OFFICIAL' AND n.observed_at BETWEEN ? AND ? AND json_extract(n.facts_json,'$.origin')=? AND ((n.credential_role='INFERENCE' AND n.credential_id=? AND json_extract(n.facts_json,'$.credential_version')=?) OR (n.credential_role='ACCOUNT_PAT' AND c.enabled=1 AND json_extract(n.facts_json,'$.credential_version')=COALESCE((SELECT MAX(version) FROM credential_secret_versions WHERE provider_id=c.provider_id AND account_id=c.account_id AND credential_id=c.id),0))) AND n.endpoint IN('/api/usage/token/','/api/user/self') ORDER BY n.rowid DESC LIMIT 20`, v.ProviderID, v.AccountID, at-86400000, at, p.BaseURL, v.CredentialID, p.CredentialVersion)
	if e != nil {
		return nil, nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var f ReceiptFact
		var credential, role, endpoint, raw string
		if rows.Scan(&f.ID, &credential, &role, &endpoint, &raw, &f.ObservedAt) != nil {
			continue
		}
		key := "NEW_API/" + credential + "/" + endpoint
		if seen[key] || len(pools) >= 8 {
			continue
		}
		seen[key] = true
		var facts map[string]any
		d := json.NewDecoder(strings.NewReader(raw))
		d.UseNumber()
		if d.Decode(&facts) != nil {
			continue
		}
		f.Selection = Selection{ProviderID: v.ProviderID, AccountID: v.AccountID, CredentialID: credential, Endpoint: "UNKNOWN"}
		f.Kind = "RESOURCE_POOL"
		f.Source = "NEW_API_OFFICIAL"
		f.Facts = receiptQuotaFacts(facts)
		f.Facts["unit"] = "QUOTA_CREDITS"
		f.Facts["credential_role"] = role
		f.Facts["scope_kind"] = map[string]string{"INFERENCE": "CREDENTIAL", "ACCOUNT_PAT": "ACCOUNT"}[role]
		pools = append(pools, f)
	}
	if rows.Err() != nil {
		return nil, nil, rows.Err()
	}
	raw, _ := json.Marshal(pools)
	if encodedJSONContainsSecret(raw, secrets) {
		return quote, []ReceiptFact{}, nil
	}
	return quote, pools, nil
}
func receiptQuotaFacts(facts map[string]any) map[string]any {
	out := map[string]any{}
	if values, ok := facts["values"].(map[string]any); ok {
		facts = values
	}
	for _, name := range []string{"remaining", "used", "granted", "limit", "rpm", "rpd", "tpm", "tpd", "quota", "used_quota", "request_count", "total_granted", "total_used", "total_available", "expires_at"} {
		if n := decimal(facts[name]); n != nil && n.Sign() >= 0 {
			out[name] = *exactString(n)
		}
	}
	for _, name := range []string{"scope_kind", "unit", "slice", "quota_state", "reset"} {
		if value := str(facts[name]); value != "" && safeText(value, 120) == value {
			out[name] = value
		}
	}
	for _, name := range []string{"estimated", "unlimited_quota"} {
		if value, ok := facts[name].(bool); ok {
			out[name] = value
		}
	}
	return out
}
func (s *Server) newReceipt(ctx context.Context, v Selection, p Provider, at int64, secrets []string) (*RequestReceipt, error) {
	quote, pools, e := s.receiptSnapshot(ctx, v, p, at, secrets)
	if e != nil {
		quote = nil
		pools = []ReceiptFact{}
	}
	r := &RequestReceipt{Version: 1, CredentialVersion: p.CredentialVersion, Origin: redactConversationText(p.BaseURL, secrets), CapturedAt: at, Price: quote, PriceState: "UNKNOWN", NominalPriceState: "UNKNOWN", QuotaState: "UNKNOWN", QuotaBefore: pools, QuotaAfter: []ReceiptFact{}, ResponseQuota: map[string]any{}, Cost: map[string]any{"actual": nil, "estimated": nil, "nominal_estimated": nil, "currency": "USD", "state": "UNKNOWN", "reason": "REQUEST_NOT_FINISHED"}}
	if e != nil {
		r.Cost["snapshot_before_status"] = "UNAVAILABLE"
	}
	if quote != nil {
		raw, _ := json.Marshal(quote.Facts["price"])
		var legs PriceLegs
		_ = json.Unmarshal(raw, &legs)
		r.NominalPriceState = priceState(legs)
		if effective := quote.Facts["effective_charge"]; effective != nil {
			raw, _ = json.Marshal(effective)
			_ = json.Unmarshal(raw, &legs)
			r.PriceState = priceState(legs)
		}
	}
	return r, nil
}
func (s *Server) completeReceipt(ctx context.Context, r *RequestReceipt, v Selection, p Provider, operation, outcome string, input, output *int64, details map[string]any, secrets []string) {
	_, pools, e := s.receiptSnapshot(ctx, v, p, now(), secrets)
	if e == nil {
		r.QuotaAfter = pools
	} else {
		r.Cost["snapshot_after_status"] = "UNAVAILABLE"
	}
	if headers, ok := details["rate_limit_headers"].(map[string]string); ok {
		for _, name := range []string{"x-ratelimit-remaining-requests", "x-ratelimit-remaining-tokens", "x-ratelimit-limit-requests", "x-ratelimit-limit-tokens"} {
			if n, e := strconv.ParseInt(headers[name], 10, 64); e == nil && n >= 0 {
				r.ResponseQuota[name] = n
			}
		}
		for _, name := range []string{"retry-after", "x-ratelimit-reset-requests", "x-ratelimit-reset-tokens"} {
			if headers[name] != "" {
				r.ResponseQuota[name] = headers[name]
			}
		}
	}
	if len(r.ResponseQuota) > 0 {
		r.QuotaState = "RATE_LIMIT_ONLY"
	}
	r.Cost["reason"] = "NO_PRICING_SNAPSHOT"
	if r.Price != nil && outcome == "SUCCESS" {
		raw, _ := json.Marshal(r.Price.Facts["price"])
		var legs PriceLegs
		_ = json.Unmarshal(raw, &legs)
		r.Cost["nominal_estimated"], r.Cost["reason"] = estimateRequestCost(legs, operation, input, output, details)
		if effective := r.Price.Facts["effective_charge"]; effective != nil {
			raw, _ = json.Marshal(effective)
			_ = json.Unmarshal(raw, &legs)
			r.Cost["estimated"], r.Cost["effective_reason"] = estimateRequestCost(legs, operation, input, output, details)
		}
	} else if outcome != "SUCCESS" {
		r.Cost["reason"] = "REQUEST_NOT_COMPLETED"
	}
	if value, ok := details["provider_charge"].(map[string]any); ok {
		r.Cost["reported_charge"] = value
		if value["unit"] == "USD" {
			r.Cost["actual"] = value["amount"]
			r.Cost["state"] = "PROVIDER_REPORTED"
		}
	}
	details["receipt"] = r
}
func estimateRequestCost(p PriceLegs, op string, input, output *int64, details map[string]any) (*string, string) {
	fixed := big.NewRat(0, 1)
	if p.Fixed != nil {
		fixed = decimal(*p.Fixed)
		if fixed == nil || fixed.Sign() < 0 {
			return nil, "INVALID_PRICE"
		}
	} else if !p.FixedAbsent {
		return nil, "FIXED_CHARGE_UNKNOWN"
	}
	if p.Unit == "USD/request" {
		if p.Fixed == nil && !p.FixedAbsent {
			return nil, "FIXED_CHARGE_UNKNOWN"
		}
		return exactString(fixed), "FIXED_REQUEST_QUOTE"
	}
	if p.Unit != "USD/1K tokens" || !oneOf(op, "CHAT_COMPLETIONS", "RESPONSES", "EMBEDDINGS") {
		return nil, "USAGE_BASIS_UNKNOWN"
	}
	if input == nil || *input < 0 || output == nil && op != "EMBEDDINGS" || output != nil && *output < 0 {
		return nil, "TOKEN_USAGE_UNKNOWN"
	}
	outCount := int64(0)
	if output != nil {
		outCount = *output
	}
	cached := receiptInteger(details["cache_read_tokens"])
	writes := receiptInteger(details["cache_write_tokens"])
	if writes != nil && *writes > 0 {
		return nil, "CACHE_WRITE_PRICE_UNKNOWN"
	}
	if cached != nil && (*cached < 0 || *cached > *input) {
		return nil, "CACHE_USAGE_INVALID"
	}
	inRate, outRate := decimalValue(p.Input), decimalValue(p.Output)
	if *input > 0 && inRate == nil || outCount > 0 && outRate == nil {
		return nil, "TOKEN_PRICE_UNKNOWN"
	}
	if cached == nil && *input > 0 {
		cacheRate := decimalValue(p.Cache)
		if cacheRate == nil || inRate == nil || cacheRate.Cmp(inRate) != 0 {
			return nil, "CACHE_USAGE_UNKNOWN"
		}
	}
	cacheCount := int64(0)
	if cached != nil {
		cacheCount = *cached
	}
	if cacheCount > 0 && p.Cache == nil {
		return nil, "CACHE_READ_PRICE_UNKNOWN"
	}
	total := new(big.Rat).Set(fixed)
	for _, term := range []struct {
		n    int64
		rate *big.Rat
	}{{*input - cacheCount, inRate}, {outCount, outRate}, {cacheCount, decimalValue(p.Cache)}} {
		if term.n == 0 {
			continue
		}
		if term.rate == nil || term.rate.Sign() < 0 {
			return nil, "TOKEN_PRICE_UNKNOWN"
		}
		total.Add(total, new(big.Rat).Mul(term.rate, big.NewRat(term.n, 1000)))
	}
	return exactString(total), "NOMINAL_QUOTE_ESTIMATE"
}
func receiptInteger(v any) *int64 {
	switch x := v.(type) {
	case int64:
		return &x
	case int:
		n := int64(x)
		return &n
	case *int64:
		return x
	default:
		return quotaInteger(v)
	}
}
func decimalValue(p *string) *big.Rat {
	if p == nil {
		return nil
	}
	r := decimal(*p)
	if r == nil || r.Sign() < 0 {
		return nil
	}
	return r
}
func reportedCharge(raw json.RawMessage, p Provider) map[string]any {
	var value any
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	if d.Decode(&value) != nil {
		return nil
	}
	n := decimal(value)
	if n == nil || n.Sign() < 0 {
		return nil
	}
	unit, source := "UNKNOWN", "PROVIDER_REPORTED"
	u, e := url.Parse(p.BaseURL)
	if e == nil && u.Scheme == "https" && u.Hostname() == "openrouter.ai" && (u.Port() == "" || u.Port() == "443") {
		unit = "USD"
		source = "OPENROUTER_USAGE"
	}
	return map[string]any{"amount": *exactString(n), "unit": unit, "source": source}
}
func responseRateHeaders(headers http.Header, secrets []string) map[string]string {
	out := map[string]string{}
	names := []string{}
	for _, secret := range secrets {
		names = append(names, strings.ToLower(secret))
	}
	for name, values := range headers {
		low := strings.ToLower(name)
		if safeText(low, 120, names...) != low {
			continue
		}
		if strings.HasPrefix(low, "x-ratelimit-") || low == "retry-after" {
			if value := safeText(strings.Join(values, ","), 256, secrets...); value != "" {
				out[low] = value
			}
		}
	}
	return out
}

func (s *Server) startRequestRecord(ctx context.Context, id string, v Selection, op, source string, at int64, epoch int, session, request, retry string, messageIDs []string, details map[string]any) error {
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	raw, _ := json.Marshal(details)
	_, e = tx.ExecContext(ctx, "INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at,credential_version,details_json) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,?,?)", id, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, op, source, at, epoch, string(raw))
	if e != nil {
		return e
	}
	var sessionValue any
	if session != "" {
		sessionValue = session
	}
	var retryValue any
	if retry != "" {
		retryValue = retry
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO request_context VALUES(?,?,?,?)", id, sessionValue, request, retryValue); e != nil {
		return e
	}
	for _, message := range messageIDs {
		if _, e = tx.ExecContext(ctx, "INSERT INTO message_requests VALUES(?,?)", message, id); e != nil {
			return e
		}
	}
	return tx.Commit()
}
func (s *Server) telemetryDetail(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if !validID(id) {
		http.Error(w, "request id required", 400)
		return
	}
	rows, e := s.readTelemetry(r.Context(), "t.id=?", []any{id}, 1, 0)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if len(rows) == 0 {
		http.Error(w, "request missing", 404)
		return
	}
	jsonOK(w, 200, rows[0])
}
func (s *Server) retryableRequest(ctx context.Context, session, id string, history []Message) bool {
	if len(history) == 0 || history[len(history)-1].Role == "assistant" {
		return false
	}
	var latest, outcome string
	if s.Store.DB.QueryRowContext(ctx, "SELECT t.id,t.outcome FROM telemetry t JOIN request_context rc ON rc.sample_id=t.id WHERE rc.session_id=? ORDER BY t.rowid DESC LIMIT 1", session).Scan(&latest, &outcome) != nil || latest != id || oneOf(outcome, "SUCCESS", "RUNNING") {
		return false
	}
	var related string
	if s.Store.DB.QueryRowContext(ctx, "SELECT sample_id FROM message_requests WHERE message_id=?", history[len(history)-1].ID).Scan(&related) != nil {
		return false
	}
	if related == id {
		return true
	}
	var count int
	return s.Store.DB.QueryRowContext(ctx, `WITH RECURSIVE lineage(id,parent) AS(SELECT sample_id,retry_of FROM request_context WHERE sample_id=? UNION ALL SELECT rc.sample_id,rc.retry_of FROM request_context rc JOIN lineage l ON rc.sample_id=l.parent) SELECT COUNT(*) FROM lineage WHERE id=?`, id, related).Scan(&count) == nil && count == 1
}
func (s *Server) exportTelemetry(w http.ResponseWriter, r *http.Request) {
	where, args, code := s.telemetryFilters(r)
	if code != 0 {
		http.Error(w, "invalid request filters", code)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	flight := "export:" + newID()
	if !s.beginFlight(flight) {
		http.Error(w, "export flight limit reached", 409)
		return
	}
	defer s.endFlight(flight)
	var watermark int64
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(rowid),0) FROM telemetry").Scan(&watermark) != nil {
		http.Error(w, "database error", 500)
		return
	}
	where += " AND t.rowid<=?"
	args = append(args, watermark)
	// Freeze the selected row set in SQLite temp storage. No long-lived cursor
	// or in-memory ID list blocks inference while a slow client downloads.
	table := "request_export_" + newID()
	if _, e = s.Store.DB.ExecContext(r.Context(), "CREATE TEMP TABLE "+table+"(row_id INTEGER PRIMARY KEY)"); e != nil {
		http.Error(w, "export unavailable", 500)
		return
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = s.Store.DB.ExecContext(ctx, "DROP TABLE IF EXISTS "+table)
	}()
	if _, e = s.Store.DB.ExecContext(r.Context(), "INSERT INTO "+table+" SELECT t.rowid FROM telemetry t LEFT JOIN request_context rc ON rc.sample_id=t.id WHERE "+where, args...); e != nil {
		http.Error(w, "export unavailable", 500)
		return
	}
	where = "t.rowid IN(SELECT row_id FROM " + table + ")"
	args = nil
	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="modelatlas-requests.ndjson"`)
	encoder := json.NewEncoder(w)
	if encoder.Encode(map[string]any{"type": "export_start", "version": 1, "watermark": watermark, "exported_at": now()}) != nil {
		return
	}
	count := 0
	var cursor int64
	hasCursor := false
	for {
		pageWhere := where
		pageArgs := args
		if hasCursor {
			pageWhere += " AND t.rowid<?"
			pageArgs = append(append([]any{}, args...), cursor)
		}
		rows, e := s.readTelemetry(r.Context(), pageWhere, pageArgs, 200, 0)
		if e != nil {
			panic(http.ErrAbortHandler)
		}
		for _, row := range rows {
			row["type"] = "request"
			if encoder.Encode(cleanExportValue(row, secrets)) != nil {
				panic(http.ErrAbortHandler)
			}
			count++
		}
		if len(rows) < 200 {
			break
		}
		if s.Store.DB.QueryRowContext(r.Context(), "SELECT rowid FROM telemetry WHERE id=?", rows[len(rows)-1]["id"]).Scan(&cursor) != nil {
			panic(http.ErrAbortHandler)
		}
		hasCursor = true
	}
	if encoder.Encode(map[string]any{"type": "export_end", "complete": true, "records": count}) != nil {
		panic(http.ErrAbortHandler)
	}
}
func (s *Server) telemetryFilters(r *http.Request) (string, []any, int) {
	q := r.URL.Query()
	where := "1=1"
	args := []any{}
	if id := q.Get("session_id"); id != "" {
		if !s.manualSession(r.Context(), id) {
			return "", nil, 404
		}
		where += " AND rc.session_id=?"
		args = append(args, id)
	}
	if q.Has("provider_id") {
		v := selectionQuery(r)
		if !validScope(v) {
			return "", nil, 400
		}
		where += " AND t.provider_id=? AND t.account_id=? AND t.credential_id=? AND t.model_id=? AND t.group_id=? AND t.endpoint=?"
		args = append(args, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint)
	}
	if outcome := q.Get("outcome"); outcome != "" {
		if len(outcome) > 80 {
			return "", nil, 400
		}
		where += " AND t.outcome=?"
		args = append(args, outcome)
	}
	if text := q.Get("q"); text != "" {
		if len(text) > 256 {
			return "", nil, 400
		}
		where += " AND (instr(lower(t.model_id),lower(?))>0 OR instr(lower(t.provider_id),lower(?))>0 OR instr(lower(t.source),lower(?))>0)"
		args = append(args, text, text, text)
	}
	return where, args, 0
}
func (s *Server) readTelemetry(ctx context.Context, where string, args []any, limit, offset int) ([]map[string]any, error) {
	args = append(args, limit, offset)
	rows, e := s.Store.DB.QueryContext(ctx, `SELECT t.id,t.provider_id,t.account_id,t.credential_id,t.model_id,t.group_id,t.endpoint,t.operation,t.source,t.outcome,t.status_code,t.ttfb_ms,t.ttft_ms,t.latency_ms,t.input_tokens,t.output_tokens,t.created_at,t.details_json,t.credential_version,COALESCE(rc.session_id,''),COALESCE(rc.request_id,''),COALESCE(rc.retry_of,'') FROM telemetry t LEFT JOIN request_context rc ON rc.sample_id=t.id WHERE `+where+` ORDER BY t.rowid DESC LIMIT ? OFFSET ?`, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, p, a, c, m, g, endpoint, op, source, outcome, raw, session, request, retry string
		var status, ttfb, ttft, lat, in, output sql.NullInt64
		var at int64
		var epoch int
		if e = rows.Scan(&id, &p, &a, &c, &m, &g, &endpoint, &op, &source, &outcome, &status, &ttfb, &ttft, &lat, &in, &output, &at, &raw, &epoch, &session, &request, &retry); e != nil {
			return nil, e
		}
		var details map[string]any
		_ = json.Unmarshal([]byte(raw), &details)
		out = append(out, map[string]any{"id": id, "provider_id": p, "account_id": a, "credential_id": c, "model_id": m, "group": g, "endpoint": endpoint, "operation": op, "source": source, "outcome": outcome, "created_at": at, "credential_version": epoch, "http_status": nullable(status), "ttfb_ms": nullable(ttfb), "ttft_ms": nullable(ttft), "latency_ms": nullable(lat), "input_tokens": nullable(in), "output_tokens": nullable(output), "details": details, "session_id": session, "request_id": request, "retry_of": retry})
	}
	return out, rows.Err()
}
