package main

import (
	"net/http"
)

type gatewayUsageItem struct {
	ID            string `json:"id"`
	Key           string `json:"key"`
	Model         string `json:"model"`
	Provider      string `json:"provider"`
	UpstreamModel string `json:"upstream_model"`
	Outcome       string `json:"outcome"`
	At            int64  `json:"at"`
	Status        *int   `json:"http_status"`
	Upstream      *int   `json:"upstream_status"`
	Latency       *int64 `json:"latency_ms"`
	TTFT          *int64 `json:"ttft_ms"`
	Input         *int64 `json:"input_tokens"`
	Output        *int64 `json:"output_tokens"`
	Attempts      int    `json:"attempts"`
}

type gatewayUsageSummary struct {
	Requests  int    `json:"requests"`
	Completed int    `json:"completed"`
	Successes int    `json:"successes"`
	Input     *int64 `json:"input_tokens"`
	Output    *int64 `json:"output_tokens"`
}

func (s *Server) gatewayUsage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	limit, offset := pageRange(r, 30)
	key := r.URL.Query().Get("key_id")
	failures := r.URL.Query().Get("failures") == "true"
	var summary gatewayUsageSummary
	err := s.Store.DB.QueryRowContext(ctx, `SELECT COUNT(*),
		COALESCE(SUM(g.outcome NOT IN ('RUNNING','INTERRUPTED','CANCELLED')),0),
		COALESCE(SUM(g.outcome='SUCCESS'),0),SUM(t.input_tokens),SUM(t.output_tokens)
		FROM gateway_requests g LEFT JOIN telemetry t ON t.id=g.sample_id
		WHERE g.created_at>=? AND (?='' OR g.key_id=?)`, now()-86400000, key, key).
		Scan(&summary.Requests, &summary.Completed, &summary.Successes, &summary.Input, &summary.Output)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT g.id,k.label,g.model_id,COALESCE(p.name,''),COALESCE(t.model_id,''),g.outcome,
		g.created_at,g.http_status,t.status_code,g.latency_ms,t.ttft_ms,t.input_tokens,t.output_tokens,g.attempts
		FROM gateway_requests g JOIN gateway_keys k ON k.id=g.key_id
		LEFT JOIN telemetry t ON t.id=g.sample_id LEFT JOIN providers p ON p.id=t.provider_id
		WHERE (?='' OR g.key_id=?) AND (?=0 OR g.outcome NOT IN ('SUCCESS','RUNNING'))
		ORDER BY g.created_at DESC,g.id LIMIT ? OFFSET ?`, key, key, failures, limit+1, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	items := []gatewayUsageItem{}
	for rows.Next() {
		var v gatewayUsageItem
		err = rows.Scan(&v.ID, &v.Key, &v.Model, &v.Provider, &v.UpstreamModel, &v.Outcome, &v.At, &v.Status, &v.Upstream, &v.Latency, &v.TTFT, &v.Input, &v.Output, &v.Attempts)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
		items = append(items, v)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(items) > limit
	if more {
		items = items[:limit]
	}
	jsonOK(w, 200, struct {
		Summary gatewayUsageSummary `json:"summary"`
		Items   []gatewayUsageItem  `json:"items"`
		More    bool                `json:"more"`
	}{summary, items, more})
}
