package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
)

var errQueryObservationExcluded = errors.New("incomplete or changed query observation")

type queryPairMeasurement struct {
	ComparisonID string             `json:"comparison_id"`
	Budget       int                `json:"budget_per_arm"`
	Baseline     queryComparisonArm `json:"baseline"`
	Proposed     queryComparisonArm `json:"proposed"`
}
type queryPairedEvaluation struct {
	Pairs        []queryPairMeasurement `json:"pairs"`
	BaselineHTTP int                    `json:"baseline_full_http_requests"`
	ProposedHTTP int                    `json:"proposed_full_http_requests"`
	Inference    int                    `json:"model_inference_requests"`
	Cost         *string                `json:"total_monetary_cost"`
	Note         string                 `json:"note"`
}

func measuredQueryArm(ctx context.Context, tx *sql.Tx, c queryComparison, a queryComparisonArm) (queryComparisonArm, error) {
	if a.Status != "COMPLETED" || a.Inference != 0 || a.Budget != c.Budget || a.HTTP < 1 || a.HTTP > c.Budget || a.Quality == nil {
		return a, errQueryObservationExcluded
	}
	q := a.Quality
	if !usableResearchQuality(*q) || q.Status != "SUCCESS" || q.RunID != a.RunID || q.Subject != c.Language || q.MetricKey != knowledgeMetricKey("RESEARCH_STRATEGY", c.Language) || q.Kind != "RESEARCH_STRATEGY" || q.Inference != 0 {
		return a, errQueryObservationExcluded
	}
	_, arm, hash, e := queryComparisonMembership(ctx, tx, a.RunID)
	if e != nil {
		return a, e
	}
	options, _ := comparisonOptions(c, arm)
	var raw, payload, kind, status string
	var http, probes int
	if e = tx.QueryRowContext(ctx, "SELECT config_json FROM research_run_options WHERE run_id=?", a.RunID).Scan(&raw); e != nil {
		return a, e
	}
	var saved ResearchOptions
	if json.Unmarshal([]byte(raw), &saved) != nil || connectorHash(saved) != hash || hash != connectorHash(options) {
		return a, errQueryObservationExcluded
	}
	if e = tx.QueryRowContext(ctx, "SELECT t.payload_json,t.kind,t.status,COALESCE(u.http_requests,0),COALESCE(u.inference_requests,0) FROM research_tasks t LEFT JOIN research_task_usage u ON u.task_id=t.id WHERE t.id=? AND t.run_id=?", q.TaskID, a.RunID).Scan(&payload, &kind, &status, &http, &probes); e != nil {
		return a, e
	}
	var in taskInput
	if json.Unmarshal([]byte(payload), &in) != nil || kind != "SEARCH" || status != "SUCCESS" || q.HTTP != http || probes != 0 || q.ScopeHash != researchInputScope("SEARCH", in) || in.Query != options.Queries[c.Language] || !researchStrategyContextMatches(KnowledgeVersion{Subject: c.Language, Data: map[string]any{"search_country": c.Country, "search_pages": c.Pages}}, in) {
		return a, errQueryObservationExcluded
	}
	bindings, e := researchBindings(ctx, tx, a.RunID)
	if e != nil {
		return a, e
	}
	if b, ok := bindings[q.MetricKey]; !ok || b.ScopeHash != q.ScopeHash {
		return a, errQueryObservationExcluded
	}
	cp, e := loadBraveCheckpoint(ctx, tx, q.TaskID)
	if e != nil {
		return a, e
	}
	if cp == nil || !cp.Complete || cp.ScopeHash != q.ScopeHash || cp.CredentialStamp != c.CredentialStamp || cp.PageLimit != c.Pages || cp.Country != c.Country {
		return a, errQueryObservationExcluded
	}
	return a, nil
}

func computePairedQueryEvaluation(ctx context.Context, tx *sql.Tx, v KnowledgeVersion) (knowledgeEvaluation, error) {
	out := knowledgeEvaluation{VersionID: v.ID, Policy: pairedQueryPolicy, Status: "INSUFFICIENT", ProposalHash: snapshotHash(v.Data), Diff: map[string]any{}, RunIDs: []string{}, At: now(), Limitations: []string{
		"只採最近30日最多32組同語言／地區／頁數／目前搜尋Key、相同每臂上限且兩臂均完成的本機實測。同步歷史、未確認或部分結果不提供執行／評估權限。",
		"此為實際成對查詢觀測，基線與提案各自發送HTTP；不是歷史排序重播，也不證明因果或未來改善。網站、時序與重複候選可能影響結果。",
		"搜尋品質分母只含搜尋任務HTTP；完整兩臂HTTP另列，包含公開頁面檢查。未驗證新線索僅0.5分；recurring結構化主張仍須原資格與真實性驗證。",
		"模型推論為0次；搜尋／公開來源計費與完整貨幣成本Unknown，不推定免費。預留或站方回報小計不冒充總帳單。",
	}}
	proof, e := readQueryGeneration(ctx, tx, v)
	if e != nil || proof == nil || v.Status == "IMPORTED" {
		return out, errors.New("local generated query proof required")
	}
	in, e := queryContext(v)
	if e != nil {
		return out, e
	}
	out.ScopeHash = researchInputScope("SEARCH", in)
	baseID, baseQuery, e := resolveQueryBaseline(ctx, tx, in)
	if e != nil {
		return out, e
	}
	stamp, e := currentQueryCredential(ctx, tx)
	if e != nil {
		return out, e
	}
	expected := queryComparison{VersionID: v.ID, VersionHash: out.ProposalHash, BaselineID: baseID, BaselineQuery: baseQuery, ProposedQuery: in.Query, Language: in.Language, Country: in.SearchCountry, Pages: in.SearchPages, CredentialStamp: stamp}
	out.BaselineID = baseID
	out.PlanHash = comparisonPlanHash(expected)
	out.Diff["query"] = map[string]any{"before": baseQuery, "after": in.Query}
	paired := &queryPairedEvaluation{Pairs: []queryPairMeasurement{}, Note: "品質效益使用搜尋任務的本機觀測；完整HTTP包含搜尋及公開檢查。完整貨幣成本Unknown。"}
	out.Paired = paired
	rows, e := tx.QueryContext(ctx, "SELECT id FROM research_query_comparisons WHERE version_id=? ORDER BY rowid DESC LIMIT 128", v.ID)
	if e != nil {
		return out, e
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if e = rows.Scan(&id); e != nil {
			rows.Close()
			return out, e
		}
		ids = append(ids, id)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return out, e
	}
	budget := 0
	var baselineCost, proposedCost *researchCostEvidence
	for _, id := range ids {
		c, err := scanQueryComparison(tx.QueryRowContext(ctx, "SELECT "+comparisonColumns+" FROM research_query_comparisons WHERE id=?", id))
		if err != nil {
			return out, err
		}
		if len(paired.Pairs) >= 32 || comparisonPlanHash(c) != out.PlanHash || c.At > now() || now()-c.At > 30*24*60*60000 {
			out.Excluded++
			continue
		}
		if e = fillQueryComparison(ctx, tx, &c); e != nil {
			return out, e
		}
		if c.Changed || budget != 0 && c.Budget != budget {
			out.Excluded++
			continue
		}
		b, p := c.Arms[0], c.Arms[1]
		if b.Arm != "BASELINE" || p.Arm != "PROPOSED" {
			return out, errors.New("invalid arm ordering")
		}
		b, err = measuredQueryArm(ctx, tx, c, b)
		if err != nil {
			if !errors.Is(err, errQueryObservationExcluded) && !errors.Is(err, sql.ErrNoRows) {
				return out, err
			}
			out.Excluded++
			continue
		}
		p, err = measuredQueryArm(ctx, tx, c, p)
		if err != nil {
			if !errors.Is(err, errQueryObservationExcluded) && !errors.Is(err, sql.ErrNoRows) {
				return out, err
			}
			out.Excluded++
			continue
		}
		budget = c.Budget
		paired.Pairs = append(paired.Pairs, queryPairMeasurement{ComparisonID: c.ID, Budget: c.Budget, Baseline: b, Proposed: p})
		paired.BaselineHTTP += b.HTTP
		paired.ProposedHTTP += p.HTTP
		baselineCost = addResearchCost(baselineCost, b.CostEvidence, b.HTTP, b.Inference)
		proposedCost = addResearchCost(proposedCost, p.CostEvidence, p.HTTP, p.Inference)
		out.Baseline.add(*b.Quality)
		out.Proposed.add(*p.Quality)
		out.Target.add(*p.Quality)
		out.RunIDs = append(out.RunIDs, b.RunID, p.RunID)
	}
	sort.Slice(paired.Pairs, func(i, j int) bool { return paired.Pairs[i].ComparisonID < paired.Pairs[j].ComparisonID })
	sort.Strings(out.RunIDs)
	out.DatasetHash = snapshotHash(map[string]any{"pairs": paired.Pairs})
	out.ComparableRuns = len(paired.Pairs)
	out.Cost = compareResearchCost("PAIRED_FULL_RUNS", baselineCost, proposedCost)
	out.Reason = "至少明確執行3組兩臂完整且相同範圍／每臂上限的實際查詢對照，再評估觀測效益與完整HTTP。"
	if out.ComparableRuns >= 3 {
		out.Status = "NO_OBSERVED_GAIN"
		out.Reason = "實測未觀察到品質增加或完整HTTP下降；保留結果，不能以此確認改善試用。"
		if out.Proposed.Utility+1e-9 < out.Baseline.Utility || paired.ProposedHTTP > paired.BaselineHTTP {
			out.Status = "REGRESSION"
			out.Reason = "實測提案品質下降或完整HTTP增加；請修訂查詢，不能使用這份評估啟用。"
		} else if out.Proposed.Utility > out.Baseline.Utility+1e-9 || paired.ProposedHTTP < paired.BaselineHTTP {
			out.Status = "READY_FOR_TRIAL"
			out.Reason = "至少3組實測觀察到品質增加或完整HTTP下降，且另一項未退步；可Review後確認試用，再監測下一輪真實研究。"
		}
	}
	return out, nil
}

// Monitor only local completed search receipts made with the currently selected
// search credential. Epoch and exact query/context checks remain in the common
// monitor; a key change cannot reuse an old trial's apparent success.
func pairedQueryMonitorHistory(ctx context.Context, tx *sql.Tx, v KnowledgeVersion, history []researchQuality) ([]researchQuality, error) {
	stamp, e := currentQueryCredential(ctx, tx)
	if e != nil {
		return nil, e
	}
	out := []researchQuality{}
	for _, q := range history {
		if q.Kind != "RESEARCH_STRATEGY" || q.VersionID != v.ID || q.EpochID != v.ActiveEpoch {
			continue
		}
		cp, err := loadBraveCheckpoint(ctx, tx, q.TaskID)
		if err != nil {
			return nil, err
		}
		if cp != nil && cp.Complete && cp.ScopeHash == q.ScopeHash && cp.CredentialStamp == stamp {
			out = append(out, q)
		}
	}
	return out, nil
}
