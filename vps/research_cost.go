package main

import (
	"context"
	"errors"
	"math/big"
)

const researchCostPolicy = "local-research-cost-v1"

// Public HTTP has no billing receipt. Zero model calls never proves that a
// search or retrieval was free. Snapshots bind one task/run or an explicit sum.
type researchCostEvidence struct {
	Policy          string  `json:"policy"`
	HTTP            int     `json:"admitted_http_requests"`
	Inference       int     `json:"inference_requests"`
	PublicHTTP      int     `json:"unpriced_public_http_requests"`
	Unmeasured      int     `json:"unmeasured_samples"`
	Missing         int     `json:"missing_resource_receipts"`
	Pending         int     `json:"pending_requests"`
	UnknownTokens   int     `json:"unknown_token_requests"`
	UnknownUSD      int     `json:"unknown_inference_usd_requests"`
	AccountedTokens string  `json:"accounted_tokens"`
	AccountedUSD    string  `json:"accounted_usd"`
	KnownTokens     string  `json:"known_reported_inference_tokens"`
	KnownUSD        string  `json:"known_reported_inference_usd"`
	Tokens          *string `json:"reported_inference_tokens"`
	InferenceUSD    *string `json:"reported_inference_usd"`
	TotalUSD        *string `json:"total_usd"`
}

func emptyResearchCost() *researchCostEvidence {
	c := &researchCostEvidence{Policy: researchCostPolicy, AccountedTokens: "0", AccountedUSD: "0", KnownTokens: "0", KnownUSD: "0"}
	c.complete()
	return c
}

func (c *researchCostEvidence) complete() {
	c.Tokens, c.InferenceUSD, c.TotalUSD = nil, nil, nil
	if c.UnknownTokens == 0 && c.Unmeasured == 0 {
		v := c.KnownTokens
		c.Tokens = &v
	}
	if c.UnknownUSD == 0 && c.Unmeasured == 0 {
		v := c.KnownUSD
		c.InferenceUSD = &v
		if c.PublicHTTP == 0 {
			c.TotalUSD = &v
		}
	}
}

func readResearchCost(ctx context.Context, q researchQueryer, run, task string, http, inference int) (*researchCostEvidence, error) {
	if http < 0 || inference < 0 || inference > http {
		return nil, errors.New("inconsistent research cost admission")
	}
	if inference == 0 {
		c := emptyResearchCost()
		c.HTTP, c.PublicHTTP = http, http
		c.complete()
		return c, nil
	}
	predicate, args := " WHERE a.run_id=?", []any{run}
	if task != "" {
		predicate += " AND a.task_id=?"
		args = append(args, task)
	}
	u, _, e := readResourceUsageQuery(ctx, q, predicate, args)
	if e != nil {
		return nil, e
	}
	if u.Requests > inference {
		return nil, errors.New("resource receipts exceed research admissions")
	}
	c := emptyResearchCost()
	c.HTTP, c.Inference, c.PublicHTTP = http, inference, http-inference
	c.Missing = inference - u.Requests
	c.Pending = u.Pending
	c.UnknownTokens, c.UnknownUSD = u.UnknownTokens+c.Missing, u.UnknownUSD+c.Missing
	c.AccountedTokens, c.AccountedUSD = u.Tokens.String(), resourceDecimal(u.USD)
	c.KnownTokens, c.KnownUSD = u.ReportedTokens.String(), resourceDecimal(u.ReportedUSD)
	c.complete()
	return c, nil
}

func addCostInteger(a, b string) string {
	x, ok := new(big.Int).SetString(a, 10)
	y, other := new(big.Int).SetString(b, 10)
	if !ok || !other || x.Sign() < 0 || y.Sign() < 0 {
		return ""
	}
	return new(big.Int).Add(x, y).String()
}

func addCostUSD(a, b string) string {
	x, y := resourceStoredAmount(a), resourceStoredAmount(b)
	if x == nil || y == nil || x.Sign() < 0 || y.Sign() < 0 {
		return ""
	}
	return resourceDecimal(new(big.Rat).Add(x, y))
}

// Legacy observations retain their original quality meaning, while missing
// cost evidence cannot acquire a zero-cost interpretation.
func addResearchCost(a, b *researchCostEvidence, http, inference int) *researchCostEvidence {
	if a == nil {
		a = emptyResearchCost()
	}
	v := *a
	if b == nil || b.Policy != researchCostPolicy || b.HTTP != http || b.Inference != inference || b.PublicHTTP != http-inference || b.Unmeasured < 0 || b.Missing < 0 || b.Missing > inference || b.Pending < 0 || b.Pending > inference || b.UnknownTokens < 0 || b.UnknownTokens > inference || b.UnknownUSD < 0 || b.UnknownUSD > inference || addCostInteger("0", b.AccountedTokens) == "" || addCostInteger("0", b.KnownTokens) == "" || addCostUSD("0", b.AccountedUSD) == "" || addCostUSD("0", b.KnownUSD) == "" {
		b = emptyResearchCost()
		b.HTTP, b.Inference, b.PublicHTTP, b.Unmeasured = http, inference, max(0, http-inference), 1
		b.UnknownTokens, b.UnknownUSD = inference, inference
	}
	v.HTTP += b.HTTP
	v.Inference += b.Inference
	v.PublicHTTP += b.PublicHTTP
	v.Unmeasured += b.Unmeasured
	v.Missing += b.Missing
	v.Pending += b.Pending
	v.UnknownTokens += b.UnknownTokens
	v.UnknownUSD += b.UnknownUSD
	v.AccountedTokens = addCostInteger(v.AccountedTokens, b.AccountedTokens)
	v.AccountedUSD = addCostUSD(v.AccountedUSD, b.AccountedUSD)
	v.KnownTokens = addCostInteger(v.KnownTokens, b.KnownTokens)
	v.KnownUSD = addCostUSD(v.KnownUSD, b.KnownUSD)
	v.complete()
	return &v
}

type researchCostComparison struct {
	Basis    string                `json:"basis"`
	Baseline *researchCostEvidence `json:"baseline"`
	Proposed *researchCostEvidence `json:"proposed"`
	Tokens   *string               `json:"inference_token_change"`
	USD      *string               `json:"total_usd_change"`
	Status   string                `json:"monetary_status"`
	Note     string                `json:"note"`
}

func compareResearchCost(basis string, b, p *researchCostEvidence) *researchCostComparison {
	c := &researchCostComparison{Basis: basis, Baseline: b, Proposed: p, Status: "UNKNOWN", Note: "只比較所列樣本。Token與USD用精確字串；本機預留／已核算額度、站方回報小計及完整費用分開。未知費用不能判為節省；排序重播不是額外實測或帳單。排除、失敗與取消的工作仍保留於原研究紀錄，不計為成功樣本。"}
	if b == nil || p == nil {
		return c
	}
	if b.Tokens != nil && p.Tokens != nil {
		x, ok := new(big.Int).SetString(*b.Tokens, 10)
		y, other := new(big.Int).SetString(*p.Tokens, 10)
		if ok && other {
			v := new(big.Int).Sub(y, x).String()
			c.Tokens = &v
		}
	}
	if b.TotalUSD != nil && p.TotalUSD != nil {
		x, y := resourceStoredAmount(*b.TotalUSD), resourceStoredAmount(*p.TotalUSD)
		if x != nil && y != nil {
			delta := new(big.Rat).Sub(y, x)
			v := resourceDecimal(delta)
			c.USD, c.Status = &v, "EQUAL"
			if delta.Sign() < 0 {
				c.Status = "LOWER"
			} else if delta.Sign() > 0 {
				c.Status = "HIGHER"
			}
		}
	}
	return c
}
