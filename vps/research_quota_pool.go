package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"time"
)

// Local research authorization only. No pool definition, membership, receipt,
// or policy history is transferred by workspace sync or portable restore.
const researchQuotaPoolSchema = `
CREATE TABLE IF NOT EXISTS research_quota_pool_policy(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,started_at INTEGER NOT NULL);
INSERT INTO research_quota_pool_policy VALUES(1,1,CAST(strftime('%s','now') AS INTEGER)*1000) ON CONFLICT(id) DO NOTHING;
CREATE TABLE IF NOT EXISTS research_quota_pools(id TEXT PRIMARY KEY,definition_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS research_quota_pool_admissions(pool_id TEXT NOT NULL REFERENCES research_quota_pools(id),request_id TEXT NOT NULL REFERENCES research_inference_admissions(request_id),revision INTEGER NOT NULL,definition_json TEXT NOT NULL,basis TEXT NOT NULL CHECK(basis IN ('ADMISSION','EXISTING_USAGE')),PRIMARY KEY(pool_id,request_id));
CREATE INDEX IF NOT EXISTS research_quota_pool_request ON research_quota_pool_admissions(request_id,pool_id);
CREATE TABLE IF NOT EXISTS research_quota_pool_history(revision INTEGER PRIMARY KEY,pool_id TEXT NOT NULL,definition_json TEXT NOT NULL,changed_at INTEGER NOT NULL);
`

type researchQuotaPool struct {
	ID         string                   `json:"id"`
	Label      string                   `json:"label"`
	Kind       string                   `json:"kind"`
	ProviderID string                   `json:"provider_id"`
	AccountID  string                   `json:"account_id"`
	Project    string                   `json:"project"`
	Enabled    bool                     `json:"enabled"`
	Requests   *int                     `json:"requests"`
	Tokens     *int64                   `json:"tokens"`
	USD        *string                  `json:"usd"`
	Members    []researchInferenceScope `json:"members"`
}

func (v researchQuotaPool) valid() bool {
	if !validID(v.ID) || v.Label == "" || safeText(v.Label, 120) != v.Label || !oneOf(v.Kind, "PROVIDER", "ACCOUNT", "PROJECT", "CUSTOM") || v.Members == nil || len(v.Members) > 200 || v.Requests != nil && (*v.Requests < 0 || *v.Requests > 2000) || !(researchResourceLimits{v.Tokens, v.USD}).valid() {
		return false
	}
	switch v.Kind {
	case "PROVIDER":
		if !validID(v.ProviderID) || v.AccountID != "" || v.Project != "" || len(v.Members) != 0 {
			return false
		}
	case "ACCOUNT":
		if !validID(v.ProviderID) || !validID(v.AccountID) || v.Project != "" || len(v.Members) != 0 {
			return false
		}
	case "PROJECT":
		if !validID(v.ProviderID) || v.AccountID != "" || v.Project == "" || safeText(v.Project, 120) != v.Project || len(v.Members) == 0 {
			return false
		}
	case "CUSTOM":
		if v.ProviderID != "" || v.AccountID != "" || v.Project != "" || len(v.Members) == 0 {
			return false
		}
	}
	seen := map[researchInferenceScope]bool{}
	for _, member := range v.Members {
		if !member.valid() || seen[member] || v.Kind == "PROJECT" && member.ProviderID != v.ProviderID {
			return false
		}
		seen[member] = true
	}
	return true
}

func (v researchQuotaPool) matches(scope researchInferenceScope) bool {
	if v.Kind == "PROVIDER" {
		return v.ProviderID == scope.ProviderID
	}
	if v.Kind == "ACCOUNT" {
		return v.ProviderID == scope.ProviderID && v.AccountID == scope.AccountID
	}
	for _, member := range v.Members {
		if member == scope {
			return true
		}
	}
	return false
}

func readResearchQuotaPools(ctx context.Context, tx *sql.Tx) (revision, started int64, pools []researchQuotaPool, err error) {
	err = tx.QueryRowContext(ctx, "SELECT revision,started_at FROM research_quota_pool_policy WHERE id=1").Scan(&revision, &started)
	if err != nil {
		return
	}
	if revision < 1 {
		err = errors.New("invalid pool revision")
		return
	}
	rows, err := tx.QueryContext(ctx, "SELECT id,definition_json FROM research_quota_pools ORDER BY id LIMIT 101")
	if err != nil {
		return
	}
	defer rows.Close()
	pools = []researchQuotaPool{}
	for rows.Next() {
		var id, raw string
		var pool researchQuotaPool
		if err = rows.Scan(&id, &raw); err != nil {
			return
		}
		if !resourceFields(raw, "id", "label", "kind", "provider_id", "account_id", "project", "enabled", "requests", "tokens", "usd", "members") || json.Unmarshal([]byte(raw), &pool) != nil || pool.ID != id || !pool.valid() {
			err = errors.New("invalid pool definition")
			return
		}
		pools = append(pools, pool)
	}
	err = rows.Err()
	if err == nil && len(pools) > 100 {
		err = errors.New("too many pools")
	}
	return
}

// Today's usage is the union of current members (including before attachment)
// and immutable admissions to this pool. Detaching a key cannot erase spending.
// One request matching overlapping rules counts once within each pool.
func quotaPoolPredicate(pool researchQuotaPool, exclude string) (string, []any) {
	predicate := ` AND a.request_id<>? AND (EXISTS(SELECT 1 FROM research_quota_pool_admissions z WHERE z.request_id=a.request_id AND z.pool_id=?) OR (?='PROVIDER' AND a.provider_id=?) OR (?='ACCOUNT' AND a.provider_id=? AND a.account_id=?)`
	args := []any{exclude, pool.ID, pool.Kind, pool.ProviderID, pool.Kind, pool.ProviderID, pool.AccountID}
	// Match the validated definition itself rather than treating a missing member
	// index as permission to skip authorization.
	for _, member := range pool.Members {
		predicate += ` OR (a.provider_id=? AND a.account_id=? AND a.credential_id=?)`
		args = append(args, member.ProviderID, member.AccountID, member.CredentialID)
	}
	predicate += ")"
	return predicate, args
}

func quotaPoolUsage(ctx context.Context, tx *sql.Tx, pool researchQuotaPool, day, exclude string) (*researchResourceUsage, int, int, error) {
	predicate, args := quotaPoolPredicate(pool, exclude)
	u, _, err := readResourceUsageWhere(ctx, tx, day, predicate, args)
	if err != nil {
		return nil, 0, 0, err
	}
	var requests, older int
	err = tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(NOT EXISTS(SELECT 1 FROM research_resource_admissions r WHERE r.request_id=a.request_id)),0) FROM research_inference_admissions a WHERE a.day=?`+predicate, append([]any{day}, args...)...).Scan(&requests, &older)
	return u, requests, older, err
}

// Attaching today's existing usage is not retroactive authorization. Preserve
// that distinct basis so subsequent membership edits cannot refund it.
func snapshotQuotaPoolUse(ctx context.Context, tx *sql.Tx, pool researchQuotaPool, day string, revision int64) error {
	predicate, args := quotaPoolPredicate(pool, "")
	raw, _ := json.Marshal(pool)
	values := []any{pool.ID, revision, string(raw), day}
	_, err := tx.ExecContext(ctx, `INSERT INTO research_quota_pool_admissions SELECT ?,a.request_id,?,?,'EXISTING_USAGE' FROM research_inference_admissions a WHERE a.day=?`+predicate+` ON CONFLICT(pool_id,request_id) DO NOTHING`, append(values, args...)...)
	return err
}

func (s *Server) reserveResearchQuotaPools(ctx context.Context, tx *sql.Tx, record requestRecord) (err error) {
	if record.Source != "CRAWLER_VERIFY" {
		return nil
	}
	scope := researchInferenceScope{record.Selection.ProviderID, record.Selection.AccountID, record.Selection.CredentialID}
	issue := &researchResourceBudgetError{Metric: "UNAVAILABLE", Scope: "POOL", researchInferenceScope: scope}
	defer func() {
		if err != nil {
			var limit *researchResourceBudgetError
			if !errors.As(err, &limit) {
				err = issue
			}
		}
	}()
	var day string
	var admitted int64
	if err = tx.QueryRowContext(ctx, "SELECT day,admitted_at FROM research_inference_admissions WHERE request_id=?", record.ID).Scan(&day, &admitted); err != nil {
		return
	}
	instant := time.UnixMilli(admitted).UTC()
	issue.Day, issue.ResetAt = day, nextBudgetDay(instant)
	if instant.Format("2006-01-02") != day {
		return errors.New("invalid pool admission day")
	}
	revision, _, pools, err := readResearchQuotaPools(ctx, tx)
	if err != nil {
		return err
	}
	policy, err := readResearchResourcePolicy(ctx, tx)
	if err != nil {
		return err
	}
	for _, pool := range pools {
		if !pool.Enabled || !pool.matches(scope) {
			continue
		}
		issue.PoolID, issue.PoolLabel = pool.ID, pool.Label
		u, requests, older, e := quotaPoolUsage(ctx, tx, pool, day, record.ID)
		if e != nil {
			return e
		}
		if pool.Requests != nil && requests >= *pool.Requests {
			issue.Metric, issue.Used, issue.Limit, issue.Reservation = "REQUESTS", big.NewInt(int64(requests)).String(), big.NewInt(int64(*pool.Requests)).String(), "1"
			return issue
		}
		if older > 0 && (pool.Tokens != nil || pool.USD != nil) {
			return issue
		}
		if pool.Tokens != nil && int64(record.MaxOutputTokens) > policy.ReserveTokens {
			issue.Metric, issue.Used, issue.Limit = "OUTPUT_ENVELOPE", big.NewInt(int64(record.MaxOutputTokens)).String(), big.NewInt(policy.ReserveTokens).String()
			return issue
		}
		if pool.Tokens != nil && new(big.Int).Add(u.Tokens, big.NewInt(policy.ReserveTokens)).Cmp(big.NewInt(*pool.Tokens)) > 0 {
			issue.Metric, issue.Used, issue.Limit, issue.Reservation = "TOKENS", u.Tokens.String(), big.NewInt(*pool.Tokens).String(), big.NewInt(policy.ReserveTokens).String()
			return issue
		}
		if pool.USD != nil && new(big.Rat).Add(u.USD, decimal(policy.ReserveUSD)).Cmp(decimal(*pool.USD)) > 0 {
			issue.Metric, issue.Used, issue.Limit, issue.Reservation = "USD", resourceDecimal(u.USD), *pool.USD, policy.ReserveUSD
			return issue
		}
		raw, _ := json.Marshal(pool)
		if _, err = tx.ExecContext(ctx, "INSERT INTO research_quota_pool_admissions VALUES(?,?,?,?,'ADMISSION')", pool.ID, record.ID, revision, string(raw)); err != nil {
			return err
		}
	}
	return nil
}
