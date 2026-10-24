package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
	"time"
)

// Local allocations and provider-reported measurements are different facts.
// None of these authorization tables belongs to workspace/portable sync.
const researchResourceBudgetSchema = `
CREATE TABLE IF NOT EXISTS research_resource_policy(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,policy_json TEXT NOT NULL,started_at INTEGER NOT NULL);
INSERT INTO research_resource_policy VALUES(1,1,'{"daily_tokens":null,"daily_usd":null,"credential_tokens":null,"credential_usd":null,"reserve_tokens":256,"reserve_usd":"0.01"}',CAST(strftime('%s','now') AS INTEGER)*1000) ON CONFLICT(id) DO NOTHING;
CREATE TABLE IF NOT EXISTS research_resource_limits(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,limits_json TEXT NOT NULL,PRIMARY KEY(provider_id,account_id,credential_id));
CREATE TABLE IF NOT EXISTS research_resource_admissions(request_id TEXT PRIMARY KEY REFERENCES research_inference_admissions(request_id),policy_revision INTEGER NOT NULL,reserve_tokens INTEGER NOT NULL,reserve_usd TEXT NOT NULL,output_limit INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS research_resource_settlements(request_id TEXT PRIMARY KEY REFERENCES research_resource_admissions(request_id),reported_tokens TEXT,reported_usd TEXT,accounted_tokens TEXT NOT NULL,accounted_usd TEXT NOT NULL,outcome TEXT NOT NULL,settled_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS research_resource_history(revision INTEGER PRIMARY KEY,action TEXT NOT NULL,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,value_json TEXT NOT NULL,changed_at INTEGER NOT NULL);
INSERT INTO research_resource_history SELECT revision,'POLICY','','','',policy_json,started_at FROM research_resource_policy WHERE revision=1 ON CONFLICT(revision) DO NOTHING;
`

type researchResourcePolicy struct {
	DailyTokens      *int64  `json:"daily_tokens"`
	DailyUSD         *string `json:"daily_usd"`
	CredentialTokens *int64  `json:"credential_tokens"`
	CredentialUSD    *string `json:"credential_usd"`
	ReserveTokens    int64   `json:"reserve_tokens"`
	ReserveUSD       string  `json:"reserve_usd"`
}

type researchResourceLimits struct {
	Tokens *int64  `json:"tokens"`
	USD    *string `json:"usd"`
}

type researchResourcePolicyRow struct {
	researchResourcePolicy
	Revision  int64 `json:"revision"`
	StartedAt int64 `json:"started_at"`
}

func validResourceUSD(v string, positive bool) bool {
	n := decimal(v)
	return len(v) <= 64 && n != nil && n.Sign() >= 0 && (!positive || n.Sign() > 0) && n.Cmp(big.NewRat(1000000, 1)) <= 0
}

func (p researchResourcePolicy) valid() bool {
	for _, n := range []*int64{p.DailyTokens, p.CredentialTokens} {
		if n != nil && (*n < 0 || *n > 1000000000000) {
			return false
		}
	}
	for _, n := range []*string{p.DailyUSD, p.CredentialUSD} {
		if n != nil && !validResourceUSD(*n, false) {
			return false
		}
	}
	return p.ReserveTokens >= 1 && p.ReserveTokens <= 1000000 && validResourceUSD(p.ReserveUSD, true)
}

func (v researchResourceLimits) valid() bool {
	return (v.Tokens == nil || *v.Tokens >= 0 && *v.Tokens <= 1000000000000) && (v.USD == nil || validResourceUSD(*v.USD, false))
}

func readResearchResourcePolicy(ctx context.Context, q scheduleQuerier) (v researchResourcePolicyRow, err error) {
	var raw string
	err = q.QueryRowContext(ctx, "SELECT revision,policy_json,started_at FROM research_resource_policy WHERE id=1").Scan(&v.Revision, &raw, &v.StartedAt)
	if err == nil && (!resourceFields(raw, "daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd") || json.Unmarshal([]byte(raw), &v.researchResourcePolicy) != nil || v.Revision < 1 || !v.valid()) {
		err = errors.New("invalid resource budget policy")
	}
	return
}

func resourceFields(raw string, names ...string) bool {
	var fields map[string]json.RawMessage
	if json.Unmarshal([]byte(raw), &fields) != nil || len(fields) != len(names) {
		return false
	}
	for _, name := range names {
		if len(fields[name]) == 0 {
			return false
		}
	}
	return true
}

func decodeResourceLimits(raw string) (v researchResourceLimits, err error) {
	if !resourceFields(raw, "tokens", "usd") || json.Unmarshal([]byte(raw), &v) != nil || !v.valid() {
		err = errors.New("invalid resource budget limits")
	}
	return
}

// A finite decimal must not round a tiny positive reported charge down to zero.
// Inputs come from decimal(), so their denominator contains only 2 and 5.
func resourceDecimal(r *big.Rat) string {
	d := new(big.Int).Set(r.Denom())
	scale := 0
	for _, factor := range []int64{2, 5} {
		count := 0
		f, remainder := big.NewInt(factor), new(big.Int)
		for {
			quotient := new(big.Int)
			quotient.QuoRem(d, f, remainder)
			if remainder.Sign() != 0 {
				break
			}
			d = quotient
			count++
		}
		if count > scale {
			scale = count
		}
	}
	if d.Cmp(big.NewInt(1)) != 0 {
		return *exactString(r)
	}
	v := r.FloatString(scale)
	if strings.Contains(v, ".") {
		v = strings.TrimRight(strings.TrimRight(v, "0"), ".")
	}
	if v == "-0" {
		return "0"
	}
	return v
}

// Stored normalized provider amounts may be longer than the bounded wire
// lexeme (for example 1e-4096). They remain finite decimals, never expressions.
func resourceStoredAmount(v string) *big.Rat {
	if len(v) <= 128 {
		return decimal(v)
	}
	if len(v) > 8500 || strings.ContainsAny(v, "eE") || !decimalPattern.MatchString(v) {
		return nil
	}
	n, ok := new(big.Rat).SetString(v)
	if !ok {
		return nil
	}
	return n
}

type researchResourceUsage struct {
	Requests       int      `json:"requests"`
	Pending        int      `json:"pending_requests"`
	UnknownTokens  int      `json:"unknown_token_requests"`
	UnknownUSD     int      `json:"unknown_usd_requests"`
	Overruns       int      `json:"overrun_requests"`
	Tokens         *big.Int `json:"-"`
	USD            *big.Rat `json:"-"`
	ReportedTokens *big.Int `json:"-"`
	ReportedUSD    *big.Rat `json:"-"`
}

func newResourceUsage() *researchResourceUsage {
	return &researchResourceUsage{Tokens: new(big.Int), USD: new(big.Rat), ReportedTokens: new(big.Int), ReportedUSD: new(big.Rat)}
}

func (u *researchResourceUsage) view() map[string]any {
	var tokens, usd any
	if u.UnknownTokens == 0 {
		tokens = u.ReportedTokens.String()
	}
	if u.UnknownUSD == 0 {
		usd = resourceDecimal(u.ReportedUSD)
	}
	return map[string]any{"requests": u.Requests, "pending_requests": u.Pending, "unknown_token_requests": u.UnknownTokens, "unknown_usd_requests": u.UnknownUSD, "overrun_requests": u.Overruns, "accounted_tokens": u.Tokens.String(), "accounted_usd": resourceDecimal(u.USD), "reported_tokens": tokens, "reported_usd": usd, "known_reported_tokens": u.ReportedTokens.String(), "known_reported_usd": resourceDecimal(u.ReportedUSD)}
}

// One indexed day's rows; exact arbitrary-precision sums avoid SQLite SUM or
// browser Number overflow, including hostile but valid int64 usage values.
func readResourceUsage(ctx context.Context, tx *sql.Tx, day string) (*researchResourceUsage, map[researchInferenceScope]*researchResourceUsage, error) {
	return readResourceUsageWhere(ctx, tx, day, "", nil)
}

// predicate is an internal SQL fragment; all scope values remain parameters.
func readResourceUsageWhere(ctx context.Context, tx *sql.Tx, day, predicate string, args []any) (*researchResourceUsage, map[researchInferenceScope]*researchResourceUsage, error) {
	rows, err := tx.QueryContext(ctx, `SELECT a.provider_id,a.account_id,a.credential_id,r.reserve_tokens,r.reserve_usd,f.reported_tokens,f.reported_usd,f.accounted_tokens,f.accounted_usd,f.outcome FROM research_inference_admissions a JOIN research_resource_admissions r ON r.request_id=a.request_id LEFT JOIN research_resource_settlements f ON f.request_id=r.request_id WHERE a.day=?`+predicate, append([]any{day}, args...)...)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	all, scopes := newResourceUsage(), map[researchInferenceScope]*researchResourceUsage{}
	for rows.Next() {
		var scope researchInferenceScope
		var reserve int64
		var reserveUSD string
		var tokens, usd, accountedTokens, accountedUSD, outcome sql.NullString
		if err = rows.Scan(&scope.ProviderID, &scope.AccountID, &scope.CredentialID, &reserve, &reserveUSD, &tokens, &usd, &accountedTokens, &accountedUSD, &outcome); err != nil {
			return nil, nil, err
		}
		amount := resourceStoredAmount(reserveUSD)
		if reserve < 1 || amount == nil || amount.Sign() <= 0 {
			return nil, nil, errors.New("invalid resource budget reservation")
		}
		count := big.NewInt(reserve)
		if accountedTokens.Valid {
			var ok bool
			count, ok = new(big.Int).SetString(accountedTokens.String, 10)
			amount = resourceStoredAmount(accountedUSD.String)
			if !ok || count.Sign() < 0 || amount == nil || amount.Sign() < 0 {
				return nil, nil, errors.New("invalid resource budget settlement")
			}
		}
		if scopes[scope] == nil {
			scopes[scope] = newResourceUsage()
		}
		var measured *big.Int
		if tokens.Valid {
			var ok bool
			measured, ok = new(big.Int).SetString(tokens.String, 10)
			if !ok || measured.Sign() < 0 {
				return nil, nil, errors.New("invalid reported token count")
			}
		}
		var money *big.Rat
		if usd.Valid {
			money = resourceStoredAmount(usd.String)
			if money == nil || money.Sign() < 0 {
				return nil, nil, errors.New("invalid reported USD")
			}
		}
		for _, u := range []*researchResourceUsage{all, scopes[scope]} {
			u.Requests++
			u.Tokens.Add(u.Tokens, count)
			u.USD.Add(u.USD, amount)
			if !outcome.Valid {
				u.Pending++
			}
			if measured == nil || outcome.String != "SUCCESS" {
				u.UnknownTokens++
			}
			if money == nil || outcome.String != "SUCCESS" {
				u.UnknownUSD++
			}
			if measured != nil {
				u.ReportedTokens.Add(u.ReportedTokens, measured)
			}
			if money != nil {
				u.ReportedUSD.Add(u.ReportedUSD, money)
			}
			if count.Cmp(big.NewInt(reserve)) > 0 || amount.Cmp(resourceStoredAmount(reserveUSD)) > 0 {
				u.Overruns++
			}
		}
	}
	return all, scopes, rows.Err()
}

type researchResourceBudgetError struct {
	ScheduleID   string `json:"schedule_id,omitempty"`
	ScheduleName string `json:"schedule_name,omitempty"`
	PoolID       string `json:"pool_id,omitempty"`
	PoolLabel    string `json:"pool_label,omitempty"`
	Metric       string `json:"metric"`
	Scope        string `json:"scope"`
	Used         string `json:"used"`
	Limit        string `json:"limit"`
	Reservation  string `json:"reservation"`
	Day          string `json:"day"`
	ResetAt      int64  `json:"reset_at"`
	researchInferenceScope
}

func (e *researchResourceBudgetError) Error() string {
	return "research resource budget: " + e.Metric + " / " + e.Scope
}

func (s *Server) reserveResearchResources(ctx context.Context, tx *sql.Tx, record requestRecord) (err error) {
	if record.Source != "CRAWLER_VERIFY" {
		return nil
	}
	instant := s.scheduleTime()
	scope := researchInferenceScope{record.Selection.ProviderID, record.Selection.AccountID, record.Selection.CredentialID}
	defer func() {
		if err != nil {
			var limit *researchResourceBudgetError
			if !errors.As(err, &limit) {
				err = &researchResourceBudgetError{Metric: "UNAVAILABLE", Scope: "ENGINE", Day: instant.Format("2006-01-02"), ResetAt: nextBudgetDay(instant), researchInferenceScope: scope}
			}
		}
	}()
	// Use the day already reserved by the daily counter in this transaction.
	// Reading a second wall-clock day here can split one midnight admission.
	var day string
	var admittedAt int64
	if err = tx.QueryRowContext(ctx, "SELECT day,admitted_at FROM research_inference_admissions WHERE request_id=?", record.ID).Scan(&day, &admittedAt); err != nil {
		return err
	}
	instant = time.UnixMilli(admittedAt).UTC()
	if instant.Format("2006-01-02") != day {
		return errors.New("inconsistent resource admission day")
	}
	policy, err := readResearchResourcePolicy(ctx, tx)
	if err != nil {
		return err
	}
	limits := researchResourceLimits{policy.CredentialTokens, policy.CredentialUSD}
	var raw string
	err = tx.QueryRowContext(ctx, "SELECT limits_json FROM research_resource_limits WHERE provider_id=? AND account_id=? AND credential_id=?", scope.ProviderID, scope.AccountID, scope.CredentialID).Scan(&raw)
	if err != nil && err != sql.ErrNoRows {
		return err
	}
	if err == nil {
		limits, err = decodeResourceLimits(raw)
		if err != nil {
			return err
		}
	}
	all, scopes, err := readResourceUsage(ctx, tx, day)
	if err != nil {
		return err
	}
	credential := scopes[scope]
	if credential == nil {
		credential = newResourceUsage()
	}
	issue := &researchResourceBudgetError{Day: day, ResetAt: nextBudgetDay(instant), researchInferenceScope: scope}
	if (policy.DailyTokens != nil || limits.Tokens != nil) && int64(record.MaxOutputTokens) > policy.ReserveTokens {
		issue.Metric, issue.Scope, issue.Used, issue.Limit, issue.Reservation = "OUTPUT_ENVELOPE", "CREDENTIAL", big.NewInt(int64(record.MaxOutputTokens)).String(), big.NewInt(policy.ReserveTokens).String(), big.NewInt(policy.ReserveTokens).String()
		return issue
	}
	for _, check := range []struct {
		scope  string
		usage  *researchResourceUsage
		limits researchResourceLimits
	}{{"ENGINE", all, researchResourceLimits{policy.DailyTokens, policy.DailyUSD}}, {"CREDENTIAL", credential, limits}} {
		if check.limits.Tokens != nil && new(big.Int).Add(check.usage.Tokens, big.NewInt(policy.ReserveTokens)).Cmp(big.NewInt(*check.limits.Tokens)) > 0 {
			issue.Metric, issue.Scope, issue.Used, issue.Limit, issue.Reservation = "TOKENS", check.scope, check.usage.Tokens.String(), big.NewInt(*check.limits.Tokens).String(), big.NewInt(policy.ReserveTokens).String()
			return issue
		}
		if check.limits.USD != nil && new(big.Rat).Add(check.usage.USD, decimal(policy.ReserveUSD)).Cmp(decimal(*check.limits.USD)) > 0 {
			issue.Metric, issue.Scope, issue.Used, issue.Limit, issue.Reservation = "USD", check.scope, resourceDecimal(check.usage.USD), *check.limits.USD, policy.ReserveUSD
			return issue
		}
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO research_resource_admissions VALUES(?,?,?,?,?)", record.ID, policy.Revision, policy.ReserveTokens, policy.ReserveUSD, record.MaxOutputTokens)
	return err
}

func resourceReportedTokens(operation string, input, output *int64, details map[string]any) *big.Int {
	if details["resource_usage_invalid"] == true {
		return nil
	}
	minimum := new(big.Int)
	for _, v := range []*int64{input, output} {
		if v != nil {
			if *v < 0 {
				return nil
			}
			minimum.Add(minimum, big.NewInt(*v))
		}
	}
	if oneOf(operation, "CHAT_COMPLETIONS", "RESPONSES", "GEMINI_NATIVE") {
		if v, ok := details["cache_read_tokens"].(int64); ok && (v < 0 || input != nil && v > *input) {
			return nil
		}
	}
	if oneOf(operation, "CHAT_COMPLETIONS", "RESPONSES") {
		if v, ok := details["reasoning_tokens"].(int64); ok && (v < 0 || output != nil && v > *output) {
			return nil
		}
	}
	if operation == "GEMINI_NATIVE" {
		for _, name := range []string{"reasoning_tokens", "tool_prompt_tokens"} {
			if v, ok := details[name].(int64); ok {
				if v < 0 {
					return nil
				}
				minimum.Add(minimum, big.NewInt(v))
			}
		}
	}
	if operation == "ANTHROPIC_MESSAGES" {
		// Anthropic input_tokens excludes both cache reads and cache creation.
		// Missing components stay unknown; an explicit zero is a measurement.
		for _, name := range []string{"cache_read_tokens", "cache_write_tokens"} {
			v, ok := details[name].(int64)
			if !ok || v < 0 {
				return nil
			}
			minimum.Add(minimum, big.NewInt(v))
		}
	}
	if v, ok := details["total_tokens"].(int64); ok {
		if v < 0 || big.NewInt(v).Cmp(minimum) < 0 {
			return nil
		}
		return big.NewInt(v)
	}
	if input == nil || output == nil || oneOf(operation, "GEMINI_NATIVE", "CUSTOM") {
		return nil
	}
	return minimum
}

// A settlement is append-only and can only consume the saved local request's
// measurements. Missing/failed writes leave the full reservation in place.
func (s *Server) settleResearchResources(ctx context.Context, id, operation, outcome string, input, output *int64, details map[string]any) error {
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var reserve int64
	var reserveUSD string
	if err = tx.QueryRowContext(ctx, "SELECT reserve_tokens,reserve_usd FROM research_resource_admissions WHERE request_id=?", id).Scan(&reserve, &reserveUSD); err != nil {
		return err
	}
	tokens := resourceReportedTokens(operation, input, output, details)
	var money *big.Rat
	if charge, ok := details["provider_charge"].(map[string]any); ok && charge["unit"] == "USD" && details["resource_cost_invalid"] != true {
		money = resourceStoredAmount(str(charge["amount"]))
		if money != nil && money.Sign() < 0 {
			money = nil
		}
	}
	count, amount := big.NewInt(reserve), decimal(reserveUSD)
	if amount == nil || amount.Sign() <= 0 || reserve < 1 {
		return errors.New("invalid resource budget reservation")
	}
	var tokenValue, usdValue any
	if tokens != nil {
		tokenValue = tokens.String()
		if outcome == "SUCCESS" || tokens.Cmp(count) > 0 {
			count = tokens
		}
	}
	if money != nil {
		usdValue = resourceDecimal(money)
		if outcome == "SUCCESS" || money.Cmp(amount) > 0 {
			amount = money
		}
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO research_resource_settlements VALUES(?,?,?,?,?,?,?) ON CONFLICT(request_id) DO NOTHING", id, tokenValue, usdValue, count.String(), resourceDecimal(amount), outcome, s.scheduleTime().UnixMilli())
	if err != nil {
		return err
	}
	return tx.Commit()
}
