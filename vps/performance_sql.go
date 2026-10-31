package main

import (
	"context"
	"database/sql"
	"math"
)

const numericTPS = "CASE WHEN json_valid(details_json) THEN CASE WHEN json_type(details_json,'$.tps') IN ('real','integer') THEN json_extract(details_json,'$.tps') END END"
const performanceScope = "provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=? AND created_at>=? AND created_at<?"

// Aggregates use all retained samples. Only the displayed chart is paged;
// percentile sorts stay in SQLite rather than unbounded Go slices / maps.
func (s *Server) performanceSQL(ctx context.Context, v Selection, start, end int64, detail bool) map[string]any {
	out := map[string]any{"source": "MY_KEY_LOCAL", "window_ms": end - start, "window_start": start, "window_end": end, "samples": 0, "cancelled": 0, "interrupted": 0, "success_rate": nil, "observed_rpm": nil, "observed_input_tpm": nil, "observed_output_tpm": nil, "observed_total_tpm": nil, "ttft": nil, "latency": nil, "tps": nil, "failures": map[string]int{}}
	tx, e := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if e != nil {
		return out
	}
	defer tx.Rollback()
	args := []any{v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, start, end}
	var samples, success, cancelled, interrupted int
	var last sql.NullInt64
	e = tx.QueryRowContext(ctx, "SELECT COALESCE(SUM(outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED')),0),COALESCE(SUM(outcome='SUCCESS'),0),COALESCE(SUM(outcome='CANCELLED'),0),COALESCE(SUM(outcome='INTERRUPTED'),0),MAX(created_at) FROM telemetry WHERE "+performanceScope, args...).Scan(&samples, &success, &cancelled, &interrupted, &last)
	if e != nil {
		return out
	}
	out["samples"] = samples
	out["cancelled"] = cancelled
	out["interrupted"] = interrupted
	out["last_request_at"] = nullable(last)
	if samples > 0 {
		out["success_rate"] = float64(success) / float64(samples)
	}
	completed := performanceScope + " AND outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED')"
	for name, expression := range map[string]string{"ttft": "ttft_ms", "latency": "latency_ms", "tps": numericTPS} {
		out[name] = sqlDistribution(ctx, tx, completed, args, expression, detail)
	}
	rows, e := tx.QueryContext(ctx, "SELECT outcome,COUNT(*) FROM telemetry WHERE "+completed+" AND outcome!='SUCCESS' GROUP BY outcome", args...)
	if e == nil {
		failures := map[string]int{}
		for rows.Next() {
			var key string
			var count int
			rows.Scan(&key, &count)
			failures[key] = count
		}
		rows.Close()
		out["failures"] = failures
	}
	currentArgs := []any{v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, now() - 60000, now()}
	var current, knownIn, knownOut int
	var in, output sql.NullInt64
	if tx.QueryRowContext(ctx, "SELECT COUNT(*),COUNT(input_tokens),COUNT(output_tokens),SUM(input_tokens),SUM(output_tokens) FROM telemetry WHERE "+performanceScope+" AND outcome NOT IN ('CANCELLED','INTERRUPTED')", currentArgs...).Scan(&current, &knownIn, &knownOut, &in, &output) == nil && current > 0 {
		out["observed_rpm"] = current
		if knownIn == current {
			out["observed_input_tpm"] = in.Int64
		}
		if knownOut == current {
			out["observed_output_tpm"] = output.Int64
		}
		if knownIn == current && knownOut == current {
			out["observed_total_tpm"] = in.Int64 + output.Int64
		}
	}
	if detail {
		history := []map[string]any{}
		rows, e := tx.QueryContext(ctx, "SELECT id,created_at,outcome,status_code,ttft_ms,latency_ms,"+numericTPS+" FROM telemetry WHERE "+completed+" ORDER BY rowid DESC LIMIT 400", args...)
		if e == nil {
			for rows.Next() {
				var at int64
				var id, outcome string
				var status sql.NullInt64
				var ttft, latency, tps sql.NullFloat64
				if rows.Scan(&id, &at, &outcome, &status, &ttft, &latency, &tps) != nil {
					continue
				}
				history = append(history, map[string]any{"id": id, "at": at, "outcome": outcome, "http_status": nullable(status), "ttft_ms": plotMetric(ttft), "latency_ms": plotMetric(latency), "tps": plotMetric(tps)})
			}
			rows.Close()
		}
		for i, j := 0, len(history)-1; i < j; i, j = i+1, j-1 {
			history[i], history[j] = history[j], history[i]
		}
		out["history"] = history
		out["history_total"] = samples
		out["history_limit"] = 400
	}
	return out
}

func plotMetric(value sql.NullFloat64) any {
	if !value.Valid || math.IsNaN(value.Float64) || math.IsInf(value.Float64, 0) || value.Float64 < 0 {
		return nil
	}
	return value.Float64
}
func sqlDistribution(ctx context.Context, q *sql.Tx, where string, args []any, expression string, detail bool) any {
	base := "SELECT " + expression + " AS n FROM telemetry WHERE " + where
	var count int
	var mean sql.NullFloat64
	if q.QueryRowContext(ctx, "SELECT COUNT(n),AVG(n) FROM ("+base+") WHERE n IS NOT NULL AND n>=0", args...).Scan(&count, &mean) != nil || count == 0 || !mean.Valid {
		return nil
	}
	out := map[string]float64{"mean": mean.Float64}
	if !detail {
		return out
	}
	for name, p := range map[string]float64{"p50": .5, "p90": .9, "p95": .95} {
		position := float64(count-1) * p
		low := int(math.Floor(position))
		high := min(low+1, count-1)
		values := []float64{}
		parameters := append(append([]any{}, args...), high-low+1, low)
		rows, e := q.QueryContext(ctx, "SELECT n FROM ("+base+") WHERE n IS NOT NULL AND n>=0 ORDER BY n LIMIT ? OFFSET ?", parameters...)
		if e != nil {
			continue
		}
		for rows.Next() {
			var value float64
			if rows.Scan(&value) == nil {
				values = append(values, value)
			}
		}
		rows.Close()
		if len(values) == 1 {
			out[name] = values[0]
		} else if len(values) == 2 {
			out[name] = values[0] + (values[1]-values[0])*(position-float64(low))
		}
	}
	return out
}
