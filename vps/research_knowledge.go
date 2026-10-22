package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
)

// Execution/evaluation authority stays local. Synchronized reports and portable
// definitions cannot supply admitted costs, a plan receipt, or a trial approval.
const researchEvaluationSchema = `
 CREATE TABLE IF NOT EXISTS research_plan_bindings(run_id TEXT NOT NULL REFERENCES research_runs(id),metric_key TEXT NOT NULL,binding_json TEXT NOT NULL,PRIMARY KEY(run_id,metric_key));
 CREATE TABLE IF NOT EXISTS research_task_usage(task_id TEXT PRIMARY KEY REFERENCES research_tasks(id),http_requests INTEGER NOT NULL,inference_requests INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS research_local_evidence(evidence_id TEXT PRIMARY KEY REFERENCES research_evidence(id),run_id TEXT NOT NULL REFERENCES research_runs(id),task_id TEXT NOT NULL REFERENCES research_tasks(id),record_json TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS research_local_evidence_run ON research_local_evidence(run_id);
 CREATE TABLE IF NOT EXISTS research_quality_observations(id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES research_runs(id),reflection_id TEXT NOT NULL REFERENCES research_reflections(id),metric_key TEXT NOT NULL,observation_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(reflection_id,metric_key));
 CREATE INDEX IF NOT EXISTS research_quality_history ON research_quality_observations(metric_key,created_at);
 CREATE INDEX IF NOT EXISTS research_quality_run ON research_quality_observations(run_id,metric_key);
 CREATE TABLE IF NOT EXISTS knowledge_evaluations(id TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES knowledge_versions(id),baseline_id TEXT NOT NULL,report_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS knowledge_evaluation_history ON knowledge_evaluations(version_id,created_at);
 CREATE TABLE IF NOT EXISTS knowledge_activation_evaluations(action_id TEXT PRIMARY KEY REFERENCES action_ledger(id),evaluation_id TEXT NOT NULL REFERENCES knowledge_evaluations(id));`

type researchTaskBudgetKey struct{}

type researchKnowledgeBinding struct {
	Kind             string  `json:"kind"`
	Subject          string  `json:"subject"`
	VersionID        string  `json:"version_id"`
	ScopeHash        string  `json:"scope_hash"`
	Weight           float64 `json:"weight"`
	Priority         int     `json:"priority"`
	QueryApplied     bool    `json:"query_applied"`
	IgnoredVersionID string  `json:"ignored_version_id,omitempty"`
	Reason           string  `json:"reason,omitempty"`
}

func knowledgeMetricKey(kind, subject string) string { return kind + ":" + subject }

func (s *Server) researchKnowledgeSnapshot(ctx context.Context) (map[string]KnowledgeVersion, error) {
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT v.id,v.kind,v.subject,v.data_json FROM knowledge_active a JOIN knowledge_versions v ON v.id=a.version_id WHERE a.kind IN ('SOURCE_RELIABILITY','RESEARCH_STRATEGY') AND v.status!='IMPORTED' ORDER BY a.kind,a.subject LIMIT 1000`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]KnowledgeVersion{}
	for rows.Next() {
		var v KnowledgeVersion
		var raw string
		if err = rows.Scan(&v.ID, &v.Kind, &v.Subject, &raw); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(raw), &v.Data) != nil {
			return nil, errors.New("invalid active research knowledge")
		}
		out[knowledgeMetricKey(v.Kind, v.Subject)] = v
	}
	return out, rows.Err()
}

func researchInputScope(kind string, in taskInput) string {
	if kind == "SOURCE" && in.SourceSnapshot != nil {
		return snapshotHash(map[string]any{"source": in.SourceSnapshot})
	}
	if kind == "SEARCH" {
		return snapshotHash(map[string]any{"language": in.Language, "query": in.Query, "country": "TW"})
	}
	return ""
}

func bindResearchKnowledge(task *researchPlannedTask, learned map[string]KnowledgeVersion, queryApplied bool) {
	kind, subject := "SOURCE_RELIABILITY", task.input.SourceID
	if task.kind == "SEARCH" {
		kind, subject = "RESEARCH_STRATEGY", task.input.Language
	}
	b := &researchKnowledgeBinding{Kind: kind, Subject: subject, ScopeHash: researchInputScope(task.kind, task.input), Weight: 1, Priority: 1}
	v := learned[knowledgeMetricKey(kind, subject)]
	if scope := str(v.Data["scope_hash"]); scope != "" && scope != b.ScopeHash {
		b.IgnoredVersionID = v.ID
		b.Reason = "來源或查詢已變，此任務使用內建優先序；需重新研究及評估原策略。"
		v = KnowledgeVersion{}
	}
	if v.ID != "" {
		b.VersionID, b.QueryApplied = v.ID, queryApplied
		if n := decimal(json.Number(numberText(v.Data["weight"]))); n != nil {
			f, _ := n.Float64()
			if !math.IsNaN(f) && !math.IsInf(f, 0) && f >= .25 && f <= 4 {
				b.Weight = f
			}
		}
		if task.kind == "SEARCH" {
			if n := quotaInteger(json.Number(numberText(v.Data["priority"]))); n != nil && *n >= 1 && *n <= 8 {
				b.Priority = int(*n)
			}
		}
	}
	task.binding = b
	task.input.Strategy = b
}

func numberText(v any) string {
	if value, ok := v.(string); ok {
		return value
	}
	raw, _ := json.Marshal(v)
	return string(raw)
}

type researchQueryer interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func researchBindings(ctx context.Context, q researchQueryer, run string) (map[string]researchKnowledgeBinding, error) {
	rows, err := q.QueryContext(ctx, "SELECT metric_key,binding_json FROM research_plan_bindings WHERE run_id=? ORDER BY metric_key", run)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]researchKnowledgeBinding{}
	for rows.Next() {
		var key, raw string
		var b researchKnowledgeBinding
		if err = rows.Scan(&key, &raw); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(raw), &b) != nil {
			return nil, errors.New("invalid local research binding")
		}
		out[key] = b
	}
	return out, rows.Err()
}
