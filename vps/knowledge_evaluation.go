package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
)

const knowledgeEvaluationPolicy = "research-priority-replay-v1"

type researchYield struct {
	Runs            int     `json:"runs"`
	HTTP            int     `json:"http_requests"`
	Candidates      int     `json:"candidates"`
	NewCandidates   int     `json:"new_candidates"`
	RecurringClaims int     `json:"recurring_claims"`
	NegativeClaims  int     `json:"negative_claims"`
	Unresolved      int     `json:"unresolved"`
	Failures        int     `json:"failures"`
	Utility         float64 `json:"utility"`
	PerHTTP         float64 `json:"utility_per_http"`
}

func (y *researchYield) add(q researchQuality) {
	y.Runs++
	y.HTTP += q.HTTP
	y.Candidates += q.Candidates
	y.NewCandidates += q.NewCandidates
	y.RecurringClaims += q.RecurringClaims
	y.NegativeClaims += q.NegativeClaims
	y.Unresolved += q.Unresolved
	if q.Status == "FAILED" {
		y.Failures++
	}
	y.Utility += q.Utility
	if y.HTTP > 0 {
		y.PerHTTP = y.Utility / float64(y.HTTP)
	}
}

func (y *researchYield) merge(other researchYield) {
	y.HTTP += other.HTTP
	y.Candidates += other.Candidates
	y.NewCandidates += other.NewCandidates
	y.RecurringClaims += other.RecurringClaims
	y.NegativeClaims += other.NegativeClaims
	y.Unresolved += other.Unresolved
	y.Failures += other.Failures
	y.Utility += other.Utility
}

type knowledgeEvaluation struct {
	ID             string         `json:"id"`
	VersionID      string         `json:"version_id"`
	BaselineID     string         `json:"baseline_id"`
	Policy         string         `json:"policy"`
	Status         string         `json:"status"`
	ScopeHash      string         `json:"scope_hash"`
	ProposalHash   string         `json:"proposal_hash"`
	DatasetHash    string         `json:"dataset_hash"`
	PlanHash       string         `json:"plan_hash"`
	Target         researchYield  `json:"target_observations"`
	Baseline       researchYield  `json:"baseline_replay"`
	Proposed       researchYield  `json:"proposed_replay"`
	ComparableRuns int            `json:"comparable_runs"`
	Excluded       int            `json:"excluded_observations"`
	Diff           map[string]any `json:"diff"`
	RunIDs         []string       `json:"run_ids"`
	Reason         string         `json:"reason"`
	Limitations    []string       `json:"limitations"`
	At             int64          `json:"at"`
}

func readKnowledgeVersion(ctx context.Context, q researchQueryer, id string) (KnowledgeVersion, error) {
	var v KnowledgeVersion
	var raw, evidence string
	err := q.QueryRowContext(ctx, "SELECT id,kind,subject,data_json,evidence_json,status FROM knowledge_versions WHERE id=?", id).Scan(&v.ID, &v.Kind, &v.Subject, &raw, &evidence, &v.Status)
	if err != nil {
		return v, err
	}
	if json.Unmarshal([]byte(raw), &v.Data) != nil || json.Unmarshal([]byte(evidence), &v.EvidenceIDs) != nil {
		return v, errors.New("invalid knowledge version")
	}
	return v, nil
}

func currentResearchScope(ctx context.Context, q researchQueryer, v KnowledgeVersion) (string, error) {
	if v.Kind == "RESEARCH_STRATEGY" {
		if !researchLanguage(v.Subject) || str(v.Data["language"]) != v.Subject || str(v.Data["query"]) == "" {
			return "", errors.New("query must bind the exact language")
		}
		return researchInputScope("SEARCH", taskInput{Language: v.Subject, Query: str(v.Data["query"])}), nil
	}
	var src ResearchSource
	var raw string
	if err := q.QueryRowContext(ctx, "SELECT id,name,kind,url,language,enabled,config_json FROM research_sources WHERE id=?", v.Subject).Scan(&src.ID, &src.Name, &src.Kind, &src.URL, &src.Language, &src.Enabled, &raw); err != nil {
		return "", err
	}
	if json.Unmarshal([]byte(raw), &src.Config) != nil {
		return "", errors.New("invalid source definition")
	}
	if str(v.Data["source_id"]) != v.Subject {
		return "", errors.New("priority must bind the exact source")
	}
	return researchInputScope("SOURCE", taskInput{SourceSnapshot: &src}), nil
}

func currentResearchPlan(ctx context.Context, q researchQueryer) (map[string]KnowledgeVersion, error) {
	rows, err := q.QueryContext(ctx, `SELECT v.id,v.kind,v.subject,v.data_json,COALESCE(e.epoch_id,'') FROM knowledge_active a JOIN knowledge_versions v ON v.id=a.version_id LEFT JOIN knowledge_activation_epochs e ON e.kind=a.kind AND e.subject=a.subject WHERE a.kind IN ('SOURCE_RELIABILITY','RESEARCH_STRATEGY') AND v.status!='IMPORTED' ORDER BY a.kind,a.subject LIMIT 1000`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]KnowledgeVersion{}
	for rows.Next() {
		var v KnowledgeVersion
		var raw string
		if err = rows.Scan(&v.ID, &v.Kind, &v.Subject, &raw, &v.ActiveEpoch); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(raw), &v.Data) != nil {
			return nil, errors.New("invalid active knowledge")
		}
		out[knowledgeMetricKey(v.Kind, v.Subject)] = v
	}
	return out, rows.Err()
}

func priorityWeight(v KnowledgeVersion, scope string) float64 {
	if hash := str(v.Data["scope_hash"]); hash != "" && hash != scope {
		return 1
	}
	weight, priority := 1.0, 1.0
	if n := decimal(json.Number(numberText(v.Data["weight"]))); n != nil {
		weight, _ = n.Float64()
	}
	if n := quotaInteger(json.Number(numberText(v.Data["priority"]))); n != nil {
		priority = float64(*n)
	}
	return weight * priority
}

// Replay is a declared model of the recorded, completed atomic retrievals under
// half of their admitted HTTP cost. It predicts ordering only, never unseen
// network responses or an improvement to provider truth/identity.
func replayResearchRound(observations []researchQuality, plan map[string]KnowledgeVersion, proposal *KnowledgeVersion) researchYield {
	items := append([]researchQuality(nil), observations...)
	weight := func(q researchQuality) float64 {
		v := plan[q.MetricKey]
		if proposal != nil && q.MetricKey == knowledgeMetricKey(proposal.Kind, proposal.Subject) {
			v = *proposal
		}
		return priorityWeight(v, q.ScopeHash)
	}
	sort.SliceStable(items, func(i, j int) bool {
		a, b := weight(items[i]), weight(items[j])
		if a != b {
			return a > b
		}
		if items[i].Order != items[j].Order {
			return items[i].Order < items[j].Order
		}
		return items[i].MetricKey < items[j].MetricKey
	})
	budget := 0
	for _, q := range items {
		budget += q.HTTP
	}
	budget = max(1, budget/2)
	y := researchYield{}
	for _, q := range items {
		if q.HTTP > budget {
			continue
		}
		budget -= q.HTTP
		y.add(q)
	}
	return y
}

func computeKnowledgeEvaluation(ctx context.Context, tx *sql.Tx, v KnowledgeVersion) (knowledgeEvaluation, error) {
	out := knowledgeEvaluation{VersionID: v.ID, Policy: knowledgeEvaluationPolicy, Status: "INSUFFICIENT", ProposalHash: snapshotHash(v.Data), Diff: map[string]any{}, RunIDs: []string{}, Limitations: []string{
		"僅比較最近30日最多32輪同一來源版本或精確語言／查詢的本機觀測；取消、未完成、零HTTP及同步歷史不作獨立樣本。",
		"重播只模擬已完成擷取在半數HTTP預算內的排序；不預測未觀測回應，也不證明因果改善。",
		"recurring_claims是有來源的結構化主張，仍須原eligibility與身份驗證；沒有改動Free／資格判定。",
		"貨幣、token及搜尋計費未完整量測，保持Unknown；READY_FOR_TRIAL僅允許人工確認試用。",
	}, At: now()}
	if !oneOf(v.Kind, "SOURCE_RELIABILITY", "RESEARCH_STRATEGY") || v.Status == "IMPORTED" {
		return out, errors.New("only local research-priority proposals can be evaluated")
	}
	scope, err := currentResearchScope(ctx, tx, v)
	if err != nil {
		return out, err
	}
	out.ScopeHash = scope
	if old := str(v.Data["scope_hash"]); old != "" && old != scope {
		out.Status = "SOURCE_CHANGED"
		out.Reason = "來源／查詢版本已變，舊資料不能支持此提案；請重新研究。"
		return out, nil
	}
	plan, err := currentResearchPlan(ctx, tx)
	if err != nil {
		return out, err
	}
	key := knowledgeMetricKey(v.Kind, v.Subject)
	baseline := plan[key]
	out.BaselineID = baseline.ID
	out.PlanHash = snapshotHash(map[string]any{"active": plan})
	for _, field := range []string{"weight", "priority", "query"} {
		before := baseline.Data[field]
		if before == nil && field == "weight" {
			before = 1
		}
		if before == nil && field == "priority" {
			before = 1
		}
		if field == "query" && before == nil {
			before = searchQueries[v.Subject]
		}
		if after, ok := v.Data[field]; ok {
			out.Diff[field] = map[string]any{"before": before, "after": after}
		}
	}
	history, err := loadResearchQuality(ctx, tx)
	if err != nil {
		return out, err
	}
	selected := map[string]bool{}
	for _, q := range history {
		if q.MetricKey != key || q.ScopeHash != scope {
			continue
		}
		if !usableResearchQuality(q) {
			out.Excluded++
			continue
		}
		if len(selected) == 32 {
			break
		}
		selected[q.RunID] = true
		out.Target.add(q)
		out.RunIDs = append(out.RunIDs, q.RunID)
	}
	groups := map[string][]researchQuality{}
	dataset := []researchQuality{}
	for _, q := range history {
		if !selected[q.RunID] {
			continue
		}
		if !usableResearchQuality(q) {
			out.Excluded++
			continue
		}
		groups[q.RunID] = append(groups[q.RunID], q)
		dataset = append(dataset, q)
	}
	sort.Slice(dataset, func(i, j int) bool {
		if dataset[i].RunID != dataset[j].RunID {
			return dataset[i].RunID < dataset[j].RunID
		}
		return dataset[i].MetricKey < dataset[j].MetricKey
	})
	out.DatasetHash = snapshotHash(map[string]any{"quality": dataset})
	for _, run := range out.RunIDs {
		if len(groups[run]) < 2 {
			continue
		}
		out.ComparableRuns++
		b, p := replayResearchRound(groups[run], plan, nil), replayResearchRound(groups[run], plan, &v)
		out.Baseline.merge(b)
		out.Proposed.merge(p)
	}
	out.Baseline.Runs = out.ComparableRuns
	out.Proposed.Runs = out.ComparableRuns
	if out.Baseline.HTTP > 0 {
		out.Baseline.PerHTTP = out.Baseline.Utility / float64(out.Baseline.HTTP)
	}
	if out.Proposed.HTTP > 0 {
		out.Proposed.PerHTTP = out.Proposed.Utility / float64(out.Proposed.HTTP)
	}
	out.Reason = "至少需要3輪同範圍且包含其他來源／查詢的完整研究，才能比較排序。"
	if out.Target.Runs >= 3 && out.Target.HTTP >= 3 && out.ComparableRuns >= 3 {
		out.Status = "READY_FOR_TRIAL"
		out.Reason = "歷史排序重播沒有降低觀測研究效益；可Review後確認試用，仍須監測下一輪實際結果。"
		if out.Proposed.Utility+1e-9 < out.Baseline.Utility || out.Proposed.HTTP > out.Baseline.HTTP {
			out.Status = "REGRESSION"
			out.Reason = "本次重播的觀測效益下降或HTTP成本增加，不能以此評估啟用；請修訂提案。"
		}
	}
	return out, nil
}

func (s *Server) evaluateKnowledge(w http.ResponseWriter, r *http.Request) {
	var input struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	v, err := readKnowledgeVersion(r.Context(), tx, input.ID)
	if err != nil {
		http.Error(w, "version missing", 404)
		return
	}
	report, err := computeKnowledgeEvaluation(r.Context(), tx, v)
	if err != nil {
		http.Error(w, "a local proposal with an exact saved source/query is required", 422)
		return
	}
	report.ID = newID()
	raw, _ := json.Marshal(report)
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO knowledge_evaluations VALUES(?,?,?,?,?)", report.ID, v.ID, report.BaselineID, string(raw), report.At); err != nil {
		http.Error(w, "evaluation persistence failed", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, report)
}

func checkKnowledgeEvaluation(ctx context.Context, tx *sql.Tx, v KnowledgeVersion, id string) error {
	var raw string
	if tx.QueryRowContext(ctx, "SELECT report_json FROM knowledge_evaluations WHERE id=? AND version_id=?", id, v.ID).Scan(&raw) != nil {
		return errors.New("evaluate this exact version before activation")
	}
	var saved knowledgeEvaluation
	if json.Unmarshal([]byte(raw), &saved) != nil {
		return errors.New("invalid evaluation")
	}
	fresh, err := computeKnowledgeEvaluation(ctx, tx, v)
	if err != nil || saved.Status != "READY_FOR_TRIAL" || fresh.Status != "READY_FOR_TRIAL" || saved.Policy != fresh.Policy || saved.ProposalHash != fresh.ProposalHash || saved.ScopeHash != fresh.ScopeHash || saved.BaselineID != fresh.BaselineID || saved.PlanHash != fresh.PlanHash || saved.DatasetHash != fresh.DatasetHash {
		return errors.New("evaluation is insufficient, regressed or stale; refresh and evaluate again")
	}
	return nil
}

type knowledgeMonitor struct {
	Status   string        `json:"status"`
	Baseline researchYield `json:"baseline"`
	Observed researchYield `json:"observed"`
	Reason   string        `json:"reason"`
}

func monitorKnowledge(v KnowledgeVersion, evaluation *knowledgeEvaluation, history []researchQuality) *knowledgeMonitor {
	if !v.Active || !oneOf(v.Kind, "SOURCE_RELIABILITY", "RESEARCH_STRATEGY") {
		return nil
	}
	m := &knowledgeMonitor{Status: "INSUFFICIENT", Reason: "等待至少3輪實際使用此版本的同範圍完整研究；舊佇列與其他版本不混入。"}
	if evaluation != nil {
		m.Baseline = evaluation.Target
	}
	for _, q := range history {
		if q.VersionID != v.ID || q.EpochID != v.ActiveEpoch || q.MetricKey != knowledgeMetricKey(v.Kind, v.Subject) || !usableResearchQuality(q) || evaluation != nil && q.ScopeHash != evaluation.ScopeHash {
			continue
		}
		m.Observed.add(q)
		if m.Observed.Runs == 32 {
			break
		}
	}
	if m.Observed.Runs >= 3 {
		m.Status = "OBSERVED"
		m.Reason = "已保存此版本的實際研究用量與結果；網站變動與重複候選可能影響產出，不推論因果。"
		if evaluation == nil || m.Baseline.HTTP == 0 {
			m.Status = "NO_BASELINE"
			m.Reason = "此版本為手動試用，沒有已保存的可比較基線。"
		} else if m.Baseline.PerHTTP > 0 && m.Observed.PerHTTP < m.Baseline.PerHTTP*.5 {
			m.Status = "DEGRADED"
			m.Reason = "最近最多32輪同範圍研究的每HTTP效益低於試用前50%；請Review，可回復啟用前版本。"
		}
	}
	return m
}

func (s *Server) enrichKnowledge(ctx context.Context, versions []KnowledgeVersion) error {
	tx, err := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT e.version_id,e.report_json FROM knowledge_evaluations e WHERE e.rowid=(SELECT MAX(n.rowid) FROM knowledge_evaluations n WHERE n.version_id=e.version_id) ORDER BY e.rowid DESC LIMIT 200`)
	if err != nil {
		return err
	}
	evaluations := map[string]*knowledgeEvaluation{}
	for rows.Next() {
		var id, raw string
		if err = rows.Scan(&id, &raw); err != nil {
			rows.Close()
			return err
		}
		var e knowledgeEvaluation
		if json.Unmarshal([]byte(raw), &e) == nil {
			evaluations[id] = &e
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	rows, err = tx.QueryContext(ctx, `SELECT d.after_id,d.action_id,COALESCE(e.report_json,'') FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id JOIN knowledge_active k ON k.kind=d.kind AND k.subject=d.subject AND k.version_id=d.after_id LEFT JOIN knowledge_activation_evaluations t ON t.action_id=d.action_id LEFT JOIN knowledge_evaluations e ON e.id=t.evaluation_id WHERE d.family='KNOWLEDGE' AND a.state='CONFIRMED' ORDER BY a.rowid DESC LIMIT 1000`)
	if err != nil {
		return err
	}
	actions := map[string]string{}
	baselines := map[string]*knowledgeEvaluation{}
	for rows.Next() {
		var version, id, raw string
		if err = rows.Scan(&version, &id, &raw); err != nil {
			rows.Close()
			return err
		}
		if _, ok := actions[version]; ok {
			continue
		}
		actions[version] = id
		var e knowledgeEvaluation
		if json.Unmarshal([]byte(raw), &e) == nil {
			baselines[version] = &e
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	history, err := loadResearchQuality(ctx, tx)
	if err != nil {
		return err
	}
	for i := range versions {
		v := &versions[i]
		v.Evaluation = evaluations[v.ID]
		v.ActivationActionID = actions[v.ID]
		v.Monitor = monitorKnowledge(*v, baselines[v.ID], history)
		if v.Monitor != nil {
			if scope, err := currentResearchScope(ctx, tx, *v); err != nil || str(v.Data["scope_hash"]) != "" && str(v.Data["scope_hash"]) != scope {
				v.Monitor.Status = "SOURCE_CHANGED"
				v.Monitor.Reason = "來源或查詢設定已變，下一輪不沿用此版本的優先序；請重新研究及評估。"
			}
		}
	}
	return tx.Commit()
}
