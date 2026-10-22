package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"math"
	"sort"
)

// These are observed research yields, not provider truth/identity scores. Only
// local plan receipts plus admitted work can enter an evaluation dataset.
type researchQuality struct {
	RunID           string   `json:"run_id"`
	MetricKey       string   `json:"metric_key"`
	Kind            string   `json:"kind"`
	Subject         string   `json:"subject"`
	ScopeHash       string   `json:"scope_hash"`
	VersionID       string   `json:"version_id"`
	TaskID          string   `json:"task_id"`
	Order           int      `json:"order"`
	Status          string   `json:"status"`
	HTTP            int      `json:"http_requests"`
	Inference       int      `json:"inference_requests"`
	Candidates      int      `json:"candidates"`
	NewCandidates   int      `json:"new_candidates"`
	NewUnresolved   int      `json:"new_unresolved"`
	RecurringClaims int      `json:"recurring_claims"`
	NegativeClaims  int      `json:"negative_claims"`
	Unresolved      int      `json:"unresolved"`
	DuplicateHits   int      `json:"duplicate_hits"`
	EvidenceCount   int      `json:"evidence_count"`
	EvidenceIDs     []string `json:"evidence_ids"`
	Utility         float64  `json:"utility"`
	Weight          float64  `json:"weight"`
	Priority        int      `json:"priority"`
	Truncated       bool     `json:"truncated"`
	ObservedAt      int64    `json:"observed_at"`
}

type qualityEvidenceRecord struct {
	CandidateID string         `json:"candidate_id"`
	Kind        string         `json:"kind"`
	Verified    bool           `json:"verified"`
	SourceID    string         `json:"source_id"`
	Language    string         `json:"language"`
	ModelID     string         `json:"model_id"`
	CreatedRun  string         `json:"created_run"`
	Facts       map[string]any `json:"facts"`
}
type qualityEvidence struct {
	id string
	qualityEvidenceRecord
}

func researchQualitySnapshot(ctx context.Context, tx *sql.Tx, run string) ([]researchQuality, error) {
	bindings, err := researchBindings(ctx, tx, run)
	if err != nil {
		return nil, err
	}
	// Read only evidence produced by this run; current mutable candidate labels
	// and evidence from other accounts/runs cannot retroactively improve its score.
	rows, err := tx.QueryContext(ctx, `SELECT evidence_id,record_json FROM research_local_evidence WHERE run_id=? ORDER BY rowid LIMIT 4001`, run)
	if err != nil {
		return nil, err
	}
	evidence := []qualityEvidence{}
	for rows.Next() {
		var e qualityEvidence
		var raw string
		if err = rows.Scan(&e.id, &raw); err != nil {
			rows.Close()
			return nil, err
		}
		decoder := json.NewDecoder(bytes.NewBufferString(raw))
		decoder.UseNumber()
		if decoder.Decode(&e.qualityEvidenceRecord) != nil {
			rows.Close()
			return nil, sql.ErrNoRows
		}
		evidence = append(evidence, e)
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()
	truncated := len(evidence) > 4000
	if truncated {
		evidence = evidence[:4000]
	}
	rows, err = tx.QueryContext(ctx, `SELECT t.id,t.kind,t.payload_json,t.status,t.result_json,COALESCE(u.http_requests,0),COALESCE(u.inference_requests,0),COALESCE(t.finished_at,0) FROM research_tasks t LEFT JOIN research_task_usage u ON u.task_id=t.id WHERE t.run_id=? AND t.kind IN ('SOURCE','SEARCH') ORDER BY t.rowid LIMIT 128`, run)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []researchQuality{}
	for rows.Next() {
		var id, kind, raw, status, result string
		var http, inference int
		var observedAt int64
		if err = rows.Scan(&id, &kind, &raw, &status, &result, &http, &inference, &observedAt); err != nil {
			return nil, err
		}
		var in taskInput
		if json.Unmarshal([]byte(raw), &in) != nil {
			continue
		}
		metricKind, subject := "SOURCE_RELIABILITY", in.SourceID
		if kind == "SEARCH" {
			metricKind, subject = "RESEARCH_STRATEGY", in.Language
		}
		key := knowledgeMetricKey(metricKind, subject)
		b, ok := bindings[key]
		if !ok || b.ScopeHash != researchInputScope(kind, in) {
			continue
		}
		q := researchQuality{RunID: run, MetricKey: key, Kind: metricKind, Subject: subject, ScopeHash: b.ScopeHash, VersionID: b.VersionID, TaskID: id, Order: len(out), Status: status, HTTP: http, Inference: inference, Weight: b.Weight, Priority: b.Priority, EvidenceIDs: []string{}, Truncated: truncated, ObservedAt: observedAt}
		var res map[string]any
		json.Unmarshal([]byte(result), &res)
		ids := map[string]bool{}
		for _, value := range list(res["candidate_ids"]) {
			cid := str(value)
			if validID(cid) {
				if ids[cid] {
					q.DuplicateHits++
				}
				ids[cid] = true
			}
		}
		if cid := str(res["candidate_id"]); validID(cid) {
			ids[cid] = true
		}
		// Failed retrievals may still have retained a candidate/failure evidence.
		for _, e := range evidence {
			if kind == "SOURCE" && e.SourceID == in.SourceID {
				ids[e.CandidateID] = true
			}
		}
		for cid := range ids {
			newLead, recurring, negative := false, false, false
			seen := false
			for _, e := range evidence {
				if e.CandidateID != cid || kind == "SOURCE" && e.SourceID != in.SourceID {
					continue
				}
				seen = true
				q.EvidenceCount++
				q.EvidenceIDs = append(q.EvidenceIDs, e.id)
				newLead = newLead || e.CreatedRun == run
				// Verified here describes the official source of a structured claim.
				// This deliberately does not say that the model identity is verified.
				if e.Verified && oneOf(e.Kind, "OFFICIAL_STRUCTURED_CLAIM", "OFFICIAL_RECURRING_POLICY", "OFFICIAL_RECURRING_QUOTA") && e.ModelID != "UNKNOWN" {
					free, hasFree := e.Facts["free"].(bool)
					rpd, tpd := quotaInteger(e.Facts["rpd"]), quotaInteger(e.Facts["tpd"])
					cycle := str(e.Facts["recurring"])
					if free && hasFree && rpd != nil && *rpd >= 100 && tpd != nil && *tpd >= 1000000 && oneOf(cycle, "DAILY_RESET", "ROLLING_24H", "WEEKLY", "MONTHLY") {
						recurring = true
					}
					if hasFree && !free || oneOf(cycle, "ONE_TIME_TRIAL", "PUBLIC_POOL") || str(e.Facts["taiwan"]) == "NO" {
						negative = true
					}
				}
			}
			if !seen {
				continue
			}
			q.Candidates++
			if newLead {
				q.NewCandidates++
			}
			// A conflicting negative is retained and never counted as a positive.
			if negative {
				q.NegativeClaims++
			} else if recurring {
				q.RecurringClaims++
			} else {
				q.Unresolved++
				if newLead {
					q.NewUnresolved++
				}
			}
		}
		sort.Strings(q.EvidenceIDs)
		if len(q.EvidenceIDs) > 20 {
			q.EvidenceIDs = q.EvidenceIDs[:20]
		}
		// New unresolved leads are weak utility. Negative/paid/trial claims are
		// retained counterexamples, never a reward for recurring-free discovery.
		// HTTP success alone contributes exactly zero.
		q.Utility = float64(q.NewUnresolved)*.5 + float64(q.RecurringClaims)*2
		if status != "SUCCESS" || q.Truncated {
			q.Utility = 0
		}
		out = append(out, q)
	}
	return out, rows.Err()
}

func saveResearchQuality(ctx context.Context, tx *sql.Tx, reflection string, quality []researchQuality) error {
	for _, q := range quality {
		raw, _ := json.Marshal(q)
		if _, err := tx.ExecContext(ctx, "INSERT INTO research_quality_observations VALUES(?,?,?,?,?,?)", newID(), q.RunID, reflection, q.MetricKey, string(raw), now()); err != nil {
			return err
		}
	}
	return nil
}

// Use the latest local observation per run/key. Earlier partial receipts remain
// permanent, but a resumed run is not counted as another independent trial.
func loadResearchQuality(ctx context.Context, q researchQueryer) ([]researchQuality, error) {
	rows, err := q.QueryContext(ctx, `SELECT o.observation_json FROM research_quality_observations o JOIN research_runs r ON r.id=o.run_id WHERE r.status='COMPLETED' AND o.rowid=(SELECT MAX(n.rowid) FROM research_quality_observations n WHERE n.run_id=o.run_id AND n.metric_key=o.metric_key) ORDER BY o.rowid DESC LIMIT 2048`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []researchQuality{}
	for rows.Next() {
		var raw string
		var item researchQuality
		if err = rows.Scan(&raw); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(raw), &item) == nil {
			out = append(out, item)
		}
	}
	return out, rows.Err()
}

func observedResearchWeight(data []researchQuality, key, scope string) float64 {
	utility, http, runs := 0.0, 0, 0
	for _, q := range data {
		if q.MetricKey != key || q.ScopeHash != scope || !usableResearchQuality(q) {
			continue
		}
		utility += q.Utility
		http += q.HTTP
		runs++
		if runs == 32 {
			break
		}
	}
	if runs == 0 {
		return 1
	}
	return math.Round(math.Max(.25, math.Min(4, 2*(1+utility)/float64(2+http)))*100) / 100
}

func usableResearchQuality(q researchQuality) bool {
	return q.HTTP > 0 && !q.Truncated && oneOf(q.Status, "SUCCESS", "FAILED") && q.ObservedAt > 0 && q.ObservedAt <= now() && now()-q.ObservedAt <= 30*24*60*60000
}
