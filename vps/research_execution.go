package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
)

// A local execution preference grants no source, credential, model or budget
// permission. It is absent from workspace sync and portable settings import.
type researchExecutionPolicy struct {
	Revision        int64 `json:"revision"`
	MaxParallelRuns int   `json:"max_parallel_runs"`
	UpdatedAt       int64 `json:"updated_at"`
}

func readResearchExecutionPolicy(ctx context.Context, q scheduleQuerier) (researchExecutionPolicy, error) {
	var raw string
	err := q.QueryRowContext(ctx, "SELECT value_json FROM settings WHERE key='research_execution_policy'").Scan(&raw)
	if err == sql.ErrNoRows {
		return researchExecutionPolicy{MaxParallelRuns: 1}, nil
	}
	var v researchExecutionPolicy
	if err != nil {
		return v, err
	}
	if !resourceFields(raw, "revision", "max_parallel_runs", "updated_at") || json.Unmarshal([]byte(raw), &v) != nil || v.Revision < 1 || v.MaxParallelRuns < 1 || v.MaxParallelRuns > 4 || v.UpdatedAt < 1 {
		return v, errors.New("local research execution policy unavailable")
	}
	return v, nil
}

func researchScheduleHasSlot(ctx context.Context, q scheduleQuerier, exclude string) (bool, error) {
	policy, err := readResearchExecutionPolicy(ctx, q)
	if err != nil {
		return false, err
	}
	var active int
	err = q.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_schedule_runs p JOIN research_runs r ON r.id=p.run_id WHERE r.status IN ('QUEUED','RUNNING') AND r.id!=?", exclude).Scan(&active)
	return err == nil && active < policy.MaxParallelRuns, err
}

func (s *Server) getResearchExecutionPolicy(w http.ResponseWriter, r *http.Request) {
	policy, err := readResearchExecutionPolicy(r.Context(), s.Store.DB)
	if err != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	var running, scheduled int
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(DISTINCT run_id) FROM research_tasks WHERE status='RUNNING'").Scan(&running) != nil || s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM research_schedule_runs p JOIN research_runs r ON r.id=p.run_id WHERE r.status IN ('QUEUED','RUNNING')").Scan(&scheduled) != nil {
		http.Error(w, "research execution state unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"policy": policy, "running_runs": running, "scheduled_rounds": scheduled})
}

func (s *Server) setResearchExecutionPolicy(w http.ResponseWriter, r *http.Request) {
	var value struct {
		Revision        *int64 `json:"revision"`
		MaxParallelRuns int    `json:"max_parallel_runs"`
	}
	if !decodeJSON(w, r, &value) {
		return
	}
	if value.Revision == nil || *value.Revision < 0 || *value.Revision == 1<<63-1 || value.MaxParallelRuns < 1 || value.MaxParallelRuns > 4 {
		http.Error(w, "exact revision and research concurrency 1..4 required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	defer tx.Rollback()
	policy, err := readResearchExecutionPolicy(r.Context(), tx)
	if err != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	if policy.Revision != *value.Revision {
		http.Error(w, "research execution policy changed; reload before saving", 409)
		return
	}
	policy = researchExecutionPolicy{Revision: policy.Revision + 1, MaxParallelRuns: value.MaxParallelRuns, UpdatedAt: s.scheduleTime().UnixMilli()}
	raw, _ := json.Marshal(policy)
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO settings VALUES('research_execution_policy',?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json", string(raw)); err != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "research execution policy unavailable", 503)
		return
	}
	s.wakeWorker()
	jsonOK(w, 200, policy)
}
