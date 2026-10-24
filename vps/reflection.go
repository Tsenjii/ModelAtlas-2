package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/url"
	"time"
)

// A transient save failure must not permanently lose a terminal run's report.
// Retry local work only, at most two runs per minute, with no external request.
func (s *Server) retryMissingReflections(ctx context.Context) {
	s.workMu.Lock()
	defer s.workMu.Unlock()
	if now() < s.reflectionDue {
		return
	}
	s.reflectionDue = now() + int64(time.Minute/time.Millisecond)
	rows, e := s.Store.DB.QueryContext(ctx, `SELECT r.id FROM research_runs r JOIN research_run_options o ON o.run_id=r.id LEFT JOIN reflection_receipts f ON f.run_id=r.id WHERE r.status IN ('COMPLETED','CANCELLED','BUDGET_EXHAUSTED') AND (f.run_id IS NULL OR f.status!=r.status OR f.used!=o.used OR f.checkpoint!=r.checkpoint OR COALESCE(r.finished_at,0)>f.reflected_at) ORDER BY r.rowid LIMIT 2`)
	if e != nil {
		return
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		if ctx.Err() != nil {
			return
		}
		_ = s.reflectResearch(ctx, id)
	}
}

func (s *Server) reflectResearch(ctx context.Context, run string) error {
	secrets, e := s.knownSecrets(ctx)
	if e != nil {
		return e
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var state, summary, checkpoint string
	var used, probes int
	if e := tx.QueryRowContext(ctx, "SELECT r.status,o.summary_json,o.used,o.probes_used,r.checkpoint FROM research_runs r JOIN research_run_options o ON o.run_id=r.id WHERE r.id=?", run).Scan(&state, &summary, &used, &probes, &checkpoint); e != nil {
		return e
	}
	if !oneOf(state, "COMPLETED", "CANCELLED", "BUDGET_EXHAUSTED") {
		return nil
	}
	report := map[string]any{"run_id": run, "status": state, "http_requests": used, "inference_requests": probes, "partial": state != "COMPLETED", "confidence": "TENTATIVE", "interpretation": "Retrieval success is not proof that a claim is true. Reused traffic is behavior evidence, not identity proof."}
	var result map[string]any
	json.Unmarshal([]byte(summary), &result)
	delete(result, "at")
	report["outcomes"] = result
	rows, e := tx.QueryContext(ctx, "SELECT id,kind,status,attempts,payload_json,result_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 128", run)
	if e != nil {
		return e
	}
	metrics := map[string]map[string]any{}
	tasks := []map[string]any{}
	for rows.Next() {
		var id, kind, status, payload, result string
		var attempts int
		if rows.Scan(&id, &kind, &status, &attempts, &payload, &result) != nil {
			continue
		}
		var input taskInput
		json.Unmarshal([]byte(payload), &input)
		metricKey := kind + ":" + input.SourceID + ":" + input.Language
		m := metrics[metricKey]
		if m == nil {
			m = map[string]any{"task_kind": kind, "source_id": input.SourceID, "language": input.Language, "query": input.Query, "search_country": input.SearchCountry, "search_pages": input.SearchPages, "success": 0, "failure": 0, "cancelled": 0, "attempts": 0}
			metrics[metricKey] = m
		}
		m["attempts"] = m["attempts"].(int) + attempts
		if status == "SUCCESS" {
			m["success"] = m["success"].(int) + 1
		} else if status == "CANCELLED" || status == "INTERRUPTED" {
			m["cancelled"] = m["cancelled"].(int) + 1
		} else if oneOf(status, "FAILED", "BUDGET_EXHAUSTED") {
			m["failure"] = m["failure"].(int) + 1
		}
		task := map[string]any{"id": id, "kind": kind, "status": status, "attempts": attempts, "source_id": input.SourceID, "language": input.Language, "query": input.Query, "result_hash": contentHash([]byte(result))}
		if input.SourceSnapshot != nil {
			task["source_version_hash"] = snapshotHash(map[string]any{"source": input.SourceSnapshot})
			task["source_url"] = input.SourceSnapshot.URL
			task["provider_id"] = input.SourceSnapshot.Config.ProviderID
		}
		var taskResult map[string]any
		json.Unmarshal([]byte(result), &taskResult)
		task["candidate_hits"] = len(list(taskResult["candidate_ids"]))
		tasks = append(tasks, task)
	}
	if e = rows.Err(); e != nil {
		rows.Close()
		return e
	}
	rows.Close()
	report["tasks"] = tasks
	report["source_query_metrics"] = metrics
	var reuse, observations int
	tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(CASE WHEN json_extract(d.details_json,'$.normalized.reused_local_traffic')=1 THEN 1 ELSE 0 END),0) FROM research_evidence e JOIN research_evidence_details d ON d.evidence_id=e.id WHERE json_extract(d.details_json,'$.run_id')=? AND e.source_type='SCOPED_INFERENCE'`, run).Scan(&observations, &reuse)
	var deeperRequests int
	if e := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_inference_admissions a JOIN research_tasks t ON t.id=a.task_id WHERE a.run_id=? AND t.kind IN ('SEMANTIC_PROBE','TOOL_PROBE')", run).Scan(&deeperRequests); e != nil {
		return e
	}
	report["probe_value"] = map[string]any{"observations": observations, "reused_existing": reuse, "new_requests": probes, "new_deeper_requests": deeperRequests, "repeated_probes": max(0, probes-deeperRequests-(observations-reuse)), "identity_proven": false}
	resources, _, e := readResourceUsageQuery(ctx, tx, " WHERE a.run_id=?", []any{run})
	if e != nil {
		return e
	}
	report["inference_resource_usage"] = resources.view()
	probeRows, e := tx.QueryContext(ctx, "SELECT report_json FROM research_probe_observations WHERE run_id=? ORDER BY rowid LIMIT 128", run)
	if e != nil {
		return e
	}
	findings := []researchProbeReport{}
	for probeRows.Next() {
		var raw string
		var finding researchProbeReport
		if e = probeRows.Scan(&raw); e != nil {
			break
		}
		if json.Unmarshal([]byte(raw), &finding) != nil || finding.RunID != run || finding.Identity != "UNPROVEN" {
			e = errResearchProbeApproval
			break
		}
		findings = append(findings, finding)
	}
	if e == nil {
		e = probeRows.Err()
	}
	probeRows.Close()
	if e != nil {
		return e
	}
	report["progressive_probe_findings"] = findings
	quality, e := researchQualitySnapshot(ctx, tx, run)
	if e != nil {
		return e
	}
	report["quality_policy"] = researchQualityPolicy
	report["quality_observations"] = quality
	report["cost"] = nil
	report["cost_status"] = "NOT_MEASURED"
	report["cost_note"] = "HTTP counts are admitted requests. Inference provider-reported cost and unknown coverage are separate from reserved local allocations. Public/search billing remains unknown; no complete run total is inferred from HTTP success."
	report = safeAssistantData(report, secrets).(map[string]any)
	raw, _ := json.Marshal(report)
	fingerprint := contentHash(raw)
	if _, e = tx.ExecContext(ctx, "INSERT INTO reflection_receipts VALUES(?,?,?,?,?) ON CONFLICT(run_id) DO UPDATE SET status=excluded.status,used=excluded.used,checkpoint=excluded.checkpoint,reflected_at=excluded.reflected_at", run, state, used, checkpoint, now()); e != nil {
		return e
	}
	var existing string
	if tx.QueryRowContext(ctx, "SELECT id FROM research_reflections WHERE run_id=? AND fingerprint=?", run, fingerprint).Scan(&existing) == nil {
		return tx.Commit()
	}
	id := newID()
	if _, e = tx.ExecContext(ctx, "INSERT INTO research_reflections VALUES(?,?,?,?,?)", id, run, fingerprint, string(raw), now()); e != nil {
		return e
	}
	if e = saveResearchQuality(ctx, tx, id, quality); e != nil {
		return e
	}
	if state == "COMPLETED" {
		history, err := loadResearchQuality(ctx, tx)
		if err != nil {
			return err
		}
		qualityByKey := map[string]researchQuality{}
		for _, q := range quality {
			qualityByKey[q.MetricKey] = q
		}
		for _, m := range metrics {
			if str(m["source_id"]) != "" {
				data := map[string]any{"source_id": m["source_id"], "retrieval_success": m["success"], "retrieval_failure": m["failure"], "attempts": m["attempts"], "weight": 1, "confidence": "TENTATIVE"}
				key := knowledgeMetricKey("SOURCE_RELIABILITY", str(m["source_id"]))
				if q, ok := qualityByKey[key]; ok {
					data["scope_hash"] = q.ScopeHash
					data["weight"] = observedResearchWeight(history, key, q.ScopeHash)
				}
				if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "SOURCE_RELIABILITY", Subject: str(m["source_id"]), Data: data, EvidenceIDs: []string{id}, Reason: "依最多32輪同一來源版本的本機觀測估計研究優先序；HTTP 200本身不增加品質分數，先評估再確認試用。", RunID: run}); e != nil {
					return e
				}
			}
			if str(m["language"]) != "" && str(m["task_kind"]) == "SEARCH" {
				data := map[string]any{"language": m["language"], "query": m["query"], "success": m["success"], "failure": m["failure"], "attempts": m["attempts"], "weight": 1, "priority": 1, "confidence": "TENTATIVE"}
				pages, country := braveSearchOptions(taskInput{SearchPages: m["search_pages"].(int), SearchCountry: str(m["search_country"])})
				data["search_country"], data["search_pages"] = country, pages
				key := knowledgeMetricKey("RESEARCH_STRATEGY", str(m["language"]))
				if q, ok := qualityByKey[key]; ok {
					data["scope_hash"] = q.ScopeHash
					data["weight"] = observedResearchWeight(history, key, q.ScopeHash)
				}
				if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "RESEARCH_STRATEGY", Subject: str(m["language"]), Data: data, EvidenceIDs: []string{id}, Reason: "本輪語言與查詢成效；取消不記為失敗。", RunID: run}); e != nil {
					return e
				}
			}
		}
		for _, task := range tasks {
			if rawURL := str(task["source_url"]); rawURL != "" {
				u, _ := url.Parse(rawURL)
				data := map[string]any{"source_url": rawURL, "path": u.Path, "operation": "PUBLIC_GET", "status": task["status"], "source_id": task["source_id"], "confidence": "TENTATIVE"}
				if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "DISCOVERY_PATH", Subject: str(task["source_id"]), Data: data, EvidenceIDs: []string{id}, Reason: "保存成功或失敗的公開資料探索路徑，無自動執行權限。", RunID: run}); e != nil {
					return e
				}
				if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "ENDPOINT_PATTERN", Subject: u.Host + "/" + contentHash([]byte(u.Path))[:24], Data: map[string]any{"source_url": rawURL, "path": u.Path, "operation": "PUBLIC_GET", "confidence": "TENTATIVE"}, EvidenceIDs: []string{id}, Reason: "本輪觀測的端點路徑；啟用擷取須另經 connector 測試。", RunID: run}); e != nil {
					return e
				}
				providerID := ""
				for _, t := range tasks {
					if str(t["source_id"]) == str(task["source_id"]) {
						providerID = str(t["provider_id"])
						break
					}
				}
				if validID(providerID) {
					if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "AUTH_PATTERN", Subject: providerID + "/public-source", Data: map[string]any{"provider_id": providerID, "auth_kind": "PUBLIC_SOURCE_NO_AUTH", "source_url": rawURL, "confidence": "TENTATIVE"}, EvidenceIDs: []string{id}, Reason: "此公開來源未使用憑證；不推論 API 的 authentication。", RunID: run}); e != nil {
						return e
					}
					if _, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "PROVIDER_RECIPE", Subject: providerID, Data: map[string]any{"provider_id": providerID, "source_url": rawURL, "settings": map[string]any{"notes": "本輪公開資料探索：" + excerpt(rawURL, 800)}, "confidence": "TENTATIVE"}, EvidenceIDs: []string{id}, Reason: "已觀測公開路徑的 recipe 備註；任何端點變更仍須實測與確認。", RunID: run}); e != nil {
						return e
					}
				}

			}
		}
		_, e = appendKnowledge(ctx, tx, KnowledgeVersion{Kind: "VERIFICATION_OUTCOME", Subject: run, Data: map[string]any{"reused_existing": reuse, "new_requests": probes, "observations": observations, "identity_proven": false, "confidence": "TENTATIVE"}, EvidenceIDs: []string{id}, Reason: "真實推論的驗證價值與重用成效；不把行為成功當身份證明。", RunID: run})
		if e != nil {
			return e
		}
	}
	return tx.Commit()
}

func (s *Server) listReflections(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,run_id,report_json,created_at FROM research_reflections WHERE (?='' OR run_id=?) ORDER BY rowid DESC LIMIT 100", r.URL.Query().Get("run_id"), r.URL.Query().Get("run_id"))
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, run, raw string
		var at int64
		if rows.Scan(&id, &run, &raw, &at) == nil {
			var report any
			json.Unmarshal([]byte(raw), &report)
			out = append(out, map[string]any{"id": id, "run_id": run, "report": report, "at": at})
		}
	}
	jsonOK(w, 200, out)
}

func activeVersion(ctx context.Context, q queryRows, family, kind, subject string) string {
	var id string
	if family == "KNOWLEDGE" {
		q.QueryRowContext(ctx, "SELECT version_id FROM knowledge_active WHERE kind=? AND subject=?", kind, subject).Scan(&id)
	} else if family == "AUTHENTICITY" {
		q.QueryRowContext(ctx, "SELECT version_id FROM authenticity_reference_active WHERE target_scope=?", kind).Scan(&id)
	} else {
		q.QueryRowContext(ctx, "SELECT version_id FROM connector_active WHERE provider_id=? AND name=?", kind, subject).Scan(&id)
	}
	return id
}
func setActiveVersion(ctx context.Context, tx *sql.Tx, family, kind, subject, id string) error {
	if family == "AUTHENTICITY" {
		if id == "" {
			_, e := tx.ExecContext(ctx, "DELETE FROM authenticity_reference_active WHERE target_scope=?", kind)
			return e
		}
		_, e := tx.ExecContext(ctx, "INSERT INTO authenticity_reference_active VALUES(?,?) ON CONFLICT(target_scope) DO UPDATE SET version_id=excluded.version_id", kind, id)
		return e
	}
	if family == "KNOWLEDGE" {
		if id == "" {
			if _, e := tx.ExecContext(ctx, "DELETE FROM knowledge_active WHERE kind=? AND subject=?", kind, subject); e != nil {
				return e
			}
			_, e := tx.ExecContext(ctx, "DELETE FROM knowledge_activation_epochs WHERE kind=? AND subject=?", kind, subject)
			return e
		}
		_, e := tx.ExecContext(ctx, "INSERT INTO knowledge_active VALUES(?,?,?) ON CONFLICT(kind,subject) DO UPDATE SET version_id=excluded.version_id", kind, subject, id)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, "INSERT INTO knowledge_activation_epochs VALUES(?,?,?) ON CONFLICT(kind,subject) DO UPDATE SET epoch_id=excluded.epoch_id", kind, subject, newID())
		return e
	}
	if id == "" {
		_, e := tx.ExecContext(ctx, "DELETE FROM connector_active WHERE provider_id=? AND name=?", kind, subject)
		return e
	}
	_, e := tx.ExecContext(ctx, "INSERT INTO connector_active VALUES(?,?,?) ON CONFLICT(provider_id,name) DO UPDATE SET version_id=excluded.version_id", kind, subject, id)
	return e
}
