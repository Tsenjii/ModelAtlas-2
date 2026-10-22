package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"
	"time"
)

type ResearchOptions struct {
	SourceIDs        []string          `json:"source_ids"`
	Selections       []Selection       `json:"selections"`
	CandidateIDs     []string          `json:"candidate_ids"`
	Queries          map[string]string `json:"queries"`
	Search           bool              `json:"search"`
	MinimalInference bool              `json:"minimal_inference"`
	Budget           int               `json:"budget"`
	ProbeBudget      int               `json:"probe_budget"`
	RefreshAA        bool              `json:"refresh_aa"`
	LMSpeedSeeds     bool              `json:"lmspeed_seeds"`
}
type ResearchTask struct {
	ID       string          `json:"id"`
	RunID    string          `json:"run_id"`
	Kind     string          `json:"kind"`
	Status   string          `json:"status"`
	Attempts int             `json:"attempts"`
	Payload  json.RawMessage `json:"payload"`
	Result   json.RawMessage `json:"result"`
}
type taskInput struct {
	SourceSnapshot *ResearchSource           `json:"source_snapshot,omitempty"`
	SourceID       string                    `json:"source_id,omitempty"`
	CandidateID    string                    `json:"candidate_id,omitempty"`
	Selection      *Selection                `json:"selection,omitempty"`
	Language       string                    `json:"language,omitempty"`
	Query          string                    `json:"query,omitempty"`
	URL            string                    `json:"url,omitempty"`
	Strategy       *researchKnowledgeBinding `json:"strategy,omitempty"`
}
type researchBudgetKey struct{}

func (s *Server) chargeNetwork(ctx context.Context, probe bool) error {
	return s.chargeResearchNetwork(ctx, probe, "")
}
func (s *Server) chargeResearchNetwork(ctx context.Context, probe bool, publicOrigin string) error {
	run, _ := ctx.Value(researchBudgetKey{}).(string)
	if run == "" && publicOrigin == "" {
		return nil
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if e = s.chargeResearchNetworkTx(ctx, tx, probe, publicOrigin); e != nil {
		return e
	}
	return tx.Commit()
}

// Integration admission reserves its quota and the HTTP meter atomically.
func (s *Server) chargeResearchNetworkTx(ctx context.Context, tx *sql.Tx, probe bool, publicOrigin string) error {
	run, _ := ctx.Value(researchBudgetKey{}).(string)
	if e := s.checkResearchOrigin(ctx, tx, publicOrigin); e != nil {
		return e
	}
	if run == "" {
		return nil
	}
	var status string
	var budget, used, probeBudget, probeUsed int
	if e := tx.QueryRowContext(ctx, "SELECT r.status,o.budget,o.used,o.probe_budget,o.probes_used FROM research_runs r JOIN research_run_options o ON o.run_id=r.id WHERE r.id=?", run).Scan(&status, &budget, &used, &probeBudget, &probeUsed); e != nil {
		return e
	}
	if !oneOf(status, "QUEUED", "RUNNING") {
		return errors.New("research run paused")
	}
	if used >= budget {
		return errors.New("research HTTP budget exhausted")
	}
	if probe && probeUsed >= probeBudget {
		return errors.New("research inference budget exhausted")
	}
	if e := s.scheduledNetworkCharge(ctx, tx, run, probe); e != nil {
		return e
	}
	if _, e := tx.ExecContext(ctx, "UPDATE research_run_options SET used=used+1,probes_used=probes_used+? WHERE run_id=?", map[bool]int{true: 1, false: 0}[probe], run); e != nil {
		return e
	}
	if task, _ := ctx.Value(researchTaskBudgetKey{}).(string); task != "" {
		if _, e := tx.ExecContext(ctx, "INSERT INTO research_task_usage VALUES(?,1,?) ON CONFLICT(task_id) DO UPDATE SET http_requests=http_requests+1,inference_requests=inference_requests+excluded.inference_requests", task, map[bool]int{true: 1, false: 0}[probe]); e != nil {
			return e
		}
	}
	return nil
}
func (s *Server) wakeWorker() {
	s.flightMu.Lock()
	wake := s.wake
	s.flightMu.Unlock()
	if wake != nil {
		select {
		case wake <- struct{}{}:
		default:
		}
	}
}

var searchQueries = map[string]string{"en": "recurring free strong coding reasoning model API daily token quota", "zh": "免費 強模型 API 每日 額度 推理 程式 註冊 台灣", "ru": "бесплатный API сильные модели ежедневная квота токены", "ja": "無料 高性能 AI モデル API 毎日 トークン 制限", "ko": "무료 고성능 AI 모델 API 일일 토큰 할당량", "es": "API modelos IA gratuitos cuota diaria programación", "pt": "API modelos IA grátis cota diária raciocínio", "id": "API model AI gratis kuota token harian coding"}

type researchPlannedTask struct {
	kind, dedupe string
	input        taskInput
	retry        *researchRetryState
	originWait   *researchOriginWait
	aaCheckpoint *aaResearchCheckpoint
	binding      *researchKnowledgeBinding
}
type researchPlanError struct {
	status  int
	message string
}

func (e *researchPlanError) Error() string { return e.message }
func (s *Server) planResearch(ctx context.Context, v ResearchOptions) (ResearchOptions, []researchPlannedTask, error) {
	if v.Budget == 0 {
		v.Budget = 40
	}
	if v.ProbeBudget == 0 && v.MinimalInference {
		v.ProbeBudget = 2
	}
	if v.Budget < 1 || v.Budget > 200 || v.ProbeBudget < 0 || v.ProbeBudget > 10 || len(v.SourceIDs) > 32 || len(v.Selections) > 32 || len(v.CandidateIDs) > 32 || len(v.Queries) > 8 {
		return v, nil, &researchPlanError{400, "bounded research options required"}
	}
	for lang, q := range v.Queries {
		if !researchLanguage(lang) || safeText(q, 300) != q || q == "" {
			return v, nil, &researchPlanError{400, "invalid query"}
		}
	}
	learned, err := s.researchKnowledgeSnapshot(ctx)
	if err != nil {
		return v, nil, err
	}
	tasks := []researchPlannedTask{}
	for _, id := range v.SourceIDs {
		src, e := s.source(ctx, id)
		if e != nil || !src.Enabled {
			return v, nil, &researchPlanError{422, "source unavailable"}
		}
		task := researchPlannedTask{kind: "SOURCE", dedupe: "source:" + id, input: taskInput{SourceID: id, SourceSnapshot: &src}}
		bindResearchKnowledge(&task, learned, false)
		tasks = append(tasks, task)
	}
	for i := range v.Selections {
		scope := v.Selections[i]
		if !s.validateSelection(ctx, scope) || !s.groupBound(ctx, scope) || !conversationOperation(s.operation(ctx, scope)) {
			return v, nil, &researchPlanError{422, "explicit inference selection unavailable"}
		}
		tasks = append(tasks, researchPlannedTask{kind: "CATALOG", dedupe: "catalog:" + scopeKey(scope), input: taskInput{Selection: &scope}})
		if v.MinimalInference {
			tasks = append(tasks, researchPlannedTask{kind: "MINIMAL_INFERENCE", dedupe: "infer:" + scopeKey(scope), input: taskInput{Selection: &scope}})
		}
	}
	for _, id := range v.CandidateIDs {
		c, e := s.candidate(ctx, id)
		if e != nil || !publicURL(c.Website) {
			return v, nil, &researchPlanError{422, "candidate website unavailable"}
		}
		tasks = append(tasks, researchPlannedTask{kind: "INSPECT", dedupe: "inspect:" + id, input: taskInput{CandidateID: id, URL: c.Website}})
	}
	if v.Search {
		defaultQueries := len(v.Queries) == 0
		if len(v.Queries) == 0 {
			v.Queries = map[string]string{}
			for lang, q := range searchQueries {
				if rule := learned[knowledgeMetricKey("RESEARCH_STRATEGY", lang)]; str(rule.Data["query"]) != "" {
					q = str(rule.Data["query"])
				}
				v.Queries[lang] = q
			}
		}
		languages := make([]string, 0, len(v.Queries))
		for lang := range v.Queries {
			languages = append(languages, lang)
		}
		sort.Strings(languages)
		for _, lang := range languages {
			task := researchPlannedTask{kind: "SEARCH", dedupe: "search:" + lang, input: taskInput{Language: lang, Query: v.Queries[lang]}}
			bindResearchKnowledge(&task, learned, defaultQueries)
			tasks = append(tasks, task)
		}
	}
	if v.RefreshAA {
		tasks = append(tasks, researchPlannedTask{kind: "AA", dedupe: "aa"})
	}
	if v.LMSpeedSeeds {
		tasks = append(tasks, researchPlannedTask{kind: "LMSPEED_SEEDS", dedupe: "lmspeed"})
	}
	// Learned priorities only reorder work the user explicitly selected.
	weight := func(task researchPlannedTask) float64 {
		if task.binding != nil {
			return task.binding.Weight * float64(task.binding.Priority)
		}
		return 1
	}
	sort.SliceStable(tasks, func(i, j int) bool { return weight(tasks[i]) > weight(tasks[j]) })
	if len(tasks) == 0 {
		return v, nil, &researchPlanError{400, "select sources, providers, candidates or search"}
	}
	return v, tasks, nil
}
func insertResearchRun(ctx context.Context, tx *sql.Tx, id string, v ResearchOptions, tasks []researchPlannedTask) error {
	config, _ := json.Marshal(v)
	if _, err := tx.ExecContext(ctx, "INSERT INTO research_runs VALUES(?,'QUEUED','',?,NULL)", id, now()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO research_run_options(run_id,config_json,budget,probe_budget) VALUES(?,?,?,?)", id, string(config), v.Budget, v.ProbeBudget); err != nil {
		return err
	}
	for _, task := range tasks {
		if task.binding != nil {
			binding, _ := json.Marshal(task.binding)
			if _, err := tx.ExecContext(ctx, "INSERT INTO research_plan_bindings VALUES(?,?,?) ON CONFLICT(run_id,metric_key) DO NOTHING", id, knowledgeMetricKey(task.binding.Kind, task.binding.Subject), string(binding)); err != nil {
				return err
			}
		}
		raw, _ := json.Marshal(task.input)
		result := "{}"
		if task.retry != nil || task.originWait != nil {
			envelope := map[string]any{}
			if task.retry != nil {
				envelope["retry"] = task.retry
			}
			if task.originWait != nil {
				envelope["origin_wait"] = task.originWait
			}
			encoded, _ := json.Marshal(envelope)
			result = string(encoded)
		}
		taskID := newID()
		if _, err := tx.ExecContext(ctx, "INSERT INTO research_tasks(id,run_id,kind,dedupe,payload_json,status,result_json,created_at) VALUES(?,?,?,?,?,'QUEUED',?,?) ON CONFLICT(run_id,dedupe) DO NOTHING", taskID, id, task.kind, task.dedupe, string(raw), result, now()); err != nil {
			return err
		}
		if task.aaCheckpoint != nil {
			if err := saveAACheckpointTx(ctx, tx, taskID, task.aaCheckpoint); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *Server) startResearch(w http.ResponseWriter, r *http.Request) {
	var v ResearchOptions
	if !decodeJSON(w, r, &v) {
		return
	}
	v, tasks, err := s.planResearch(r.Context(), v)
	if err != nil {
		var issue *researchPlanError
		if errors.As(err, &issue) {
			http.Error(w, issue.message, issue.status)
		} else {
			http.Error(w, "research plan unavailable", 500)
		}
		return
	}
	id := newID()
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if err = insertResearchRun(r.Context(), tx, id, v, tasks); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if err = tx.Commit(); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	s.wakeWorker()
	jsonOK(w, 201, map[string]any{"id": id, "status": "QUEUED", "tasks": len(tasks), "budget": v.Budget, "probe_budget": v.ProbeBudget})
}
func (s *Server) controlResearch(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID               string `json:"id"`
		Action           string `json:"action"`
		AdditionalBudget int    `json:"additional_budget"`
		AdditionalProbes int    `json:"additional_probes"`
		RetryFailed      bool   `json:"retry_failed"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || !oneOf(v.Action, "PAUSE", "RESUME", "CANCEL") || v.AdditionalBudget < 0 || v.AdditionalBudget > 200 || v.AdditionalProbes < 0 || v.AdditionalProbes > 10 {
		http.Error(w, "invalid control", 400)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var status string
	var budget, used, probes int
	if tx.QueryRowContext(r.Context(), "SELECT r.status,o.budget,o.used,o.probe_budget FROM research_runs r JOIN research_run_options o ON r.id=o.run_id WHERE r.id=?", v.ID).Scan(&status, &budget, &used, &probes) != nil {
		http.Error(w, "run missing", 404)
		return
	}
	next := "PAUSED"
	if v.Action == "CANCEL" {
		next = "CANCELLED"
	}
	if v.Action == "RESUME" {
		next = "QUEUED"
		var remaining int
		tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND (status IN ('QUEUED','INTERRUPTED','BUDGET_EXHAUSTED') OR (?=1 AND status='FAILED' AND attempts<3 AND COALESCE(json_extract(result_json,'$.aa_progress.stopped_reason'),'')=''))", v.ID, v.RetryFailed).Scan(&remaining)
		if remaining == 0 {
			http.Error(w, "nothing to resume; start a new run", 409)
			return
		}
		if status == "CANCELLED" || budget+v.AdditionalBudget > 200 || probes+v.AdditionalProbes > 10 || used >= budget+v.AdditionalBudget {
			http.Error(w, "resume needs remaining bounded budget", 409)
			return
		}
		_, e = tx.ExecContext(r.Context(), "UPDATE research_run_options SET budget=budget+?,probe_budget=probe_budget+? WHERE run_id=?", v.AdditionalBudget, v.AdditionalProbes, v.ID)
		if e != nil {
			http.Error(w, "database error", 500)
			return
		}
		_, e = tx.ExecContext(r.Context(), "UPDATE research_tasks SET status='QUEUED' WHERE run_id=? AND (status IN ('INTERRUPTED','BUDGET_EXHAUSTED') OR (?=1 AND status='FAILED' AND attempts<3 AND COALESCE(json_extract(result_json,'$.aa_progress.stopped_reason'),'')=''))", v.ID, v.RetryFailed)
		if e != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if v.Action != "RESUME" && !oneOf(status, "RUNNING", "QUEUED", "PAUSED", "BUDGET_EXHAUSTED") {
		http.Error(w, "run already finished", 409)
		return
	}
	if e = s.scheduledRunControl(r.Context(), tx, v.ID, v.Action, v.AdditionalProbes); e != nil {
		http.Error(w, e.Error(), 409)
		return
	}
	if v.Action == "CANCEL" {
		_, e = tx.ExecContext(r.Context(), "UPDATE research_tasks SET status='CANCELLED' WHERE run_id=? AND status IN ('QUEUED','INTERRUPTED','BUDGET_EXHAUSTED')", v.ID)
		if e != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE research_runs SET status=?,finished_at=NULL WHERE id=?", next, v.ID); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if next != "QUEUED" {
		s.flightMu.Lock()
		cancel := s.cancels["research:"+v.ID]
		s.flightMu.Unlock()
		if cancel != nil {
			cancel()
		}
	} else {
		s.wakeWorker()
	}
	s.updateResearchSummary(r.Context(), v.ID)
	jsonOK(w, 200, map[string]any{"id": v.ID, "status": next})
}
func (s *Server) listResearchTasks(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("run_id")
	if !validID(id) {
		http.Error(w, "run id required", 400)
		return
	}
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,run_id,kind,status,attempts,payload_json,result_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 500", id)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []ResearchTask{}
	for rows.Next() {
		var v ResearchTask
		var p, res string
		if rows.Scan(&v.ID, &v.RunID, &v.Kind, &v.Status, &v.Attempts, &p, &res) == nil {
			v.Payload = json.RawMessage(p)
			v.Result = json.RawMessage(res)
			out = append(out, v)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) enqueueResearch(ctx context.Context, run, kind, dedupe string, input taskInput) error {
	raw, _ := json.Marshal(input)
	_, e := s.Store.DB.ExecContext(ctx, "INSERT INTO research_tasks(id,run_id,kind,dedupe,payload_json,status,created_at) SELECT ?,?,?,?,?,'QUEUED',? WHERE (SELECT COUNT(*) FROM research_tasks WHERE run_id=?)<128 ON CONFLICT(run_id,dedupe) DO NOTHING", newID(), run, kind, dedupe, string(raw), now(), run)
	return e
}
func (s *Server) runResearchPending(parent context.Context) {
	s.workMu.Lock()
	defer s.workMu.Unlock()
	for count := 0; count < 4; count++ {
		var t ResearchTask
		var payload, previous string
		stamp := s.scheduleTime().UnixMilli()
		e := s.Store.DB.QueryRowContext(parent, "SELECT t.id,t.run_id,t.kind,t.payload_json,t.result_json FROM research_tasks t JOIN research_runs r ON r.id=t.run_id WHERE t.status='QUEUED' AND r.status IN ('QUEUED','RUNNING') AND COALESCE(json_extract(t.result_json,'$.retry.next_attempt_at'),0)<=? AND COALESCE(json_extract(t.result_json,'$.origin_wait.next_attempt_at'),0)<=? AND COALESCE(json_extract(t.result_json,'$.aa_progress.next_attempt_at'),0)<=? ORDER BY t.rowid LIMIT 1", stamp, stamp, stamp).Scan(&t.ID, &t.RunID, &t.Kind, &payload, &previous)
		if e != nil {
			return
		}
		t.Result = json.RawMessage(previous)
		flight := "research:" + t.RunID
		if t.Kind != "MINIMAL_INFERENCE" && !s.beginFlight(flight) {
			return
		}
		budgetCtx := context.WithValue(context.WithValue(parent, researchBudgetKey{}, t.RunID), researchTaskBudgetKey{}, t.ID)
		ctx, cancel := context.WithTimeout(budgetCtx, 40*time.Second)
		s.flightMu.Lock()
		if s.cancels == nil {
			s.cancels = map[string]context.CancelFunc{}
		}
		s.cancels[flight] = cancel
		s.flightMu.Unlock()
		_, e = s.Store.DB.ExecContext(ctx, "UPDATE research_tasks SET status='RUNNING',attempts=attempts+1 WHERE id=? AND status='QUEUED'", t.ID)
		if e != nil {
			cancel()
			s.endFlight(flight)
			return
		}
		_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_runs SET status='RUNNING',checkpoint=? WHERE id=? AND status IN ('RUNNING','QUEUED')", t.ID, t.RunID)
		var input taskInput
		json.Unmarshal([]byte(payload), &input)
		var result map[string]any
		var taskErr error
		var scheduled int
		if oneOf(t.Kind, "MINIMAL_INFERENCE", "CATALOG") {
			_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_schedule_runs WHERE run_id=?", t.RunID).Scan(&scheduled)
		}
		if scheduled > 0 {
			taskErr = errors.New("automatic schedule cannot use private model credentials")
		} else if t.Kind == "AA" {
			result, taskErr = s.executeAAResearchTask(ctx, t)
		} else {
			result, taskErr = s.executeResearchTask(ctx, t.RunID, t.Kind, input)
		}
		status := "SUCCESS"
		if taskErr != nil {
			status = "FAILED"
			result = map[string]any{"reason": taskErr.Error()}
		}
		if ctx.Err() != nil {
			status = "INTERRUPTED"
			if ctx.Err() == context.DeadlineExceeded {
				status = "FAILED"
				result = map[string]any{"reason": "task timeout"}
			}
		}
		saveCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		if errors.Is(taskErr, errScheduledDefinitionChanged) {
			var owner string
			if s.Store.DB.QueryRowContext(saveCtx, "SELECT schedule_id FROM research_schedule_runs WHERE run_id=?", t.RunID).Scan(&owner) == nil {
				s.suspendSchedule(saveCtx, owner, "SOURCES_CHANGED")
			}
		}
		var runState string
		var used, budget int
		_ = s.Store.DB.QueryRowContext(saveCtx, "SELECT r.status,o.used,o.budget FROM research_runs r JOIN research_run_options o ON o.run_id=r.id WHERE r.id=?", t.RunID).Scan(&runState, &used, &budget)
		if runState == "CANCELLED" {
			status = "CANCELLED"
		}
		if runState == "PAUSED" && status == "FAILED" {
			status = "INTERRUPTED"
		}
		s.scheduleBudgetWait(saveCtx, t.RunID, t.ID, taskErr)
		if taskErr != nil && strings.Contains(taskErr.Error(), "budget") && !oneOf(runState, "CANCELLED", "PAUSED") {
			status = "BUDGET_EXHAUSTED"
			_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE research_runs SET status='BUDGET_EXHAUSTED' WHERE id=? AND status='RUNNING'", t.RunID)
		}
		result, status = s.researchRetryResult(t, taskErr, result, status)
		var originWait *researchOriginWait
		if errors.As(taskErr, &originWait) {
			// An admission wait sent no HTTP request and is not a failed probe.
			_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE research_tasks SET attempts=MAX(0,attempts-1) WHERE id=?", t.ID)
			result["origin_wait"] = originWait
			if status == "FAILED" {
				status = "QUEUED"
			}
		}
		if t.Kind == "AA" {
			result, status = s.aaResearchResult(saveCtx, t.ID, result, status, taskErr)
		}
		raw, _ := json.Marshal(result)
		_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE research_tasks SET status=?,result_json=?,finished_at=CASE WHEN ?='QUEUED' THEN NULL ELSE ? END WHERE id=?", status, string(raw), status, now(), t.ID)
		var queued int
		_ = s.Store.DB.QueryRowContext(saveCtx, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND status IN ('QUEUED','RUNNING','INTERRUPTED','BUDGET_EXHAUSTED')", t.RunID).Scan(&queued)
		if queued == 0 && runState == "RUNNING" {
			_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE research_runs SET status='COMPLETED',finished_at=? WHERE id=? AND status='RUNNING'", now(), t.RunID)
		} else if used >= budget {
			_, _ = s.Store.DB.ExecContext(saveCtx, "UPDATE research_runs SET status='BUDGET_EXHAUSTED' WHERE id=? AND status='RUNNING'", t.RunID)
		}
		s.updateResearchSummary(saveCtx, t.RunID)
		stop()
		cancel()
		s.endFlight(flight)
		if parent.Err() != nil {
			return
		}
	}
}
func (s *Server) updateResearchSummary(ctx context.Context, id string) {
	summary := map[string]any{"at": now(), "tasks": map[string]int{}}
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT status,COUNT(*) FROM research_tasks WHERE run_id=? GROUP BY status", id)
	if e != nil {
		return
	}
	counts := summary["tasks"].(map[string]int)
	for rows.Next() {
		var k string
		var n int
		rows.Scan(&k, &n)
		counts[k] = n
	}
	rows.Close()
	var retryWaiting int
	var retryAt sql.NullInt64
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*),MIN(json_extract(result_json,'$.retry.next_attempt_at')) FROM research_tasks WHERE run_id=? AND status='QUEUED' AND json_extract(result_json,'$.retry.next_attempt_at')>0", id).Scan(&retryWaiting, &retryAt)
	summary["retry_waiting_count"] = retryWaiting
	if retryAt.Valid {
		summary["next_retry_at"] = retryAt.Int64
	}
	var originWaiting int
	var originAt sql.NullInt64
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*),MIN(json_extract(result_json,'$.origin_wait.next_attempt_at')) FROM research_tasks WHERE run_id=? AND status='QUEUED' AND json_extract(result_json,'$.origin_wait.next_attempt_at')>0", id).Scan(&originWaiting, &originAt)
	summary["origin_waiting_count"] = originWaiting
	if originAt.Valid {
		summary["next_origin_at"] = originAt.Int64
	}
	var aaWaiting int
	var aaAt sql.NullInt64
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*),MIN(json_extract(result_json,'$.aa_progress.next_attempt_at')) FROM research_tasks WHERE run_id=? AND status='QUEUED' AND json_extract(result_json,'$.aa_progress.next_attempt_at')>0", id).Scan(&aaWaiting, &aaAt)
	summary["aa_waiting_count"] = aaWaiting
	if aaAt.Valid {
		summary["next_aa_at"] = aaAt.Int64
	}
	summary["task_count"] = counts["SUCCESS"] + counts["FAILED"] + counts["CANCELLED"]
	states := map[string]int{}
	rows, e = s.Store.DB.QueryContext(ctx, "SELECT c.state,COUNT(DISTINCT c.id) FROM research_candidates c JOIN research_evidence e ON e.candidate_id=c.id JOIN research_evidence_details d ON d.evidence_id=e.id WHERE json_extract(d.details_json,'$.run_id')=? GROUP BY c.state", id)
	if e == nil {
		for rows.Next() {
			var k string
			var n int
			rows.Scan(&k, &n)
			states[k] = n
		}
		rows.Close()
	}
	var providers, models, newProviders int
	s.Store.DB.QueryRowContext(ctx, `SELECT COUNT(DISTINCT c.provider_id),COUNT(DISTINCT CASE WHEN c.model_id!='UNKNOWN' THEN c.id END) FROM research_candidates c JOIN research_evidence e ON e.candidate_id=c.id JOIN research_evidence_details d ON d.evidence_id=e.id WHERE json_extract(d.details_json,'$.run_id')=?`, id).Scan(&providers, &models)
	s.Store.DB.QueryRowContext(ctx, `SELECT COUNT(DISTINCT c.provider_id) FROM research_candidates c JOIN research_candidate_details d ON d.candidate_id=c.id WHERE json_extract(d.details_json,'$.created_run')=?`, id).Scan(&newProviders)
	summary["scanned_count"] = providers
	summary["model_count"] = models
	summary["new_providers"] = newProviders
	summary["updated_providers"] = max(0, providers-newProviders)
	changes := []map[string]any{}
	authenticity := []map[string]any{}
	rows, e = s.Store.DB.QueryContext(ctx, `SELECT e.candidate_id,e.source_type,e.claim FROM research_evidence e JOIN research_evidence_details d ON d.evidence_id=e.id WHERE json_extract(d.details_json,'$.run_id')=? AND e.source_type IN ('OFFICIAL_RECURRING_QUOTA','OFFICIAL_RECURRING_POLICY','SCOPED_INFERENCE') ORDER BY e.rowid LIMIT 200`, id)
	if e == nil {
		for rows.Next() {
			var cid, kind, claim string
			rows.Scan(&cid, &kind, &claim)
			entry := map[string]any{"candidate_id": cid, "kind": kind, "claim": claim}
			if kind == "SCOPED_INFERENCE" {
				authenticity = append(authenticity, entry)
			} else {
				changes = append(changes, entry)
			}
		}
		rows.Close()
	}
	summary["free_quota_observations"] = changes
	summary["authenticity_findings"] = authenticity
	summary["unresolved_high_value_count"] = states["HIGH_VALUE_GREY_ZONE"]
	summary["funnel"] = states
	summary["verified_count"] = states["VERIFIED"]
	summary["grey_zone_count"] = states["HIGH_VALUE_GREY_ZONE"]
	summary["rejected_count"] = states["REJECTED"]
	summary["high_signal_count"] = states["VERIFIED"] + states["HIGH_VALUE_GREY_ZONE"]
	raw, _ := json.Marshal(summary)
	_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_run_options SET summary_json=? WHERE run_id=?", string(raw), id)
	_ = s.reflectResearch(ctx, id)
}
