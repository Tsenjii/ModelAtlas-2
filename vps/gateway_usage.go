package main

import (
	"database/sql"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type gatewayUsageItem struct {
	ID            string `json:"id"`
	Source        string `json:"source"`
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
	Requests       int      `json:"requests"`
	Completed      int      `json:"completed"`
	Successes      int      `json:"successes"`
	Input          *int64   `json:"input_tokens"`
	Output         *int64   `json:"output_tokens"`
	InputReported  int      `json:"input_reported"`
	OutputReported int      `json:"output_reported"`
	MeanTTFT       *float64 `json:"mean_ttft_ms,omitempty"`
}

type gatewayUsagePoint struct {
	gatewayUsageSummary
	From  int64 `json:"from"`
	Until int64 `json:"until"`
}

type gatewayUsageRank struct {
	Source   string `json:"source"`
	Model    string `json:"model"`
	Requests int    `json:"requests"`
}

const gatewayUsageSettled = "outcome NOT IN ('RUNNING','INTERRUPTED','CANCELLED')"

// Only explicit model tests join relay calls. The latter are counted from the
// admission row once, even if two upstream attempts wrote telemetry.
const gatewayUsageRows = `WITH activity AS (
	SELECT g.id,'gateway' AS source,g.key_id,k.label AS key_label,g.model_id AS model,g.model_id AS model_group,
		COALESCE(p.name,'') AS provider,COALESCE(t.model_id,'') AS upstream_model,g.outcome,g.created_at AS at,
		g.http_status,t.status_code AS upstream_status,g.latency_ms,t.ttft_ms,t.input_tokens,t.output_tokens,g.attempts
	FROM gateway_requests g JOIN gateway_keys k ON k.id=g.key_id
	LEFT JOIN telemetry t ON t.id=g.sample_id LEFT JOIN providers p ON p.id=t.provider_id
	WHERE :source<>'tests' AND g.created_at>=:since AND g.created_at<:until
	UNION ALL
	SELECT t.id,'tests','','',t.model_id,COALESCE(` + gatewayPreferredModelIDSQL + `,atlas_canonical_id(t.model_id)),
		COALESCE(p.name,t.provider_id),t.model_id,t.outcome,t.created_at,
		NULL,t.status_code,t.latency_ms,t.ttft_ms,t.input_tokens,t.output_tokens,1
	FROM telemetry t LEFT JOIN providers p ON p.id=t.provider_id
	LEFT JOIN model_metadata md ON md.provider_id=t.provider_id AND md.model_id=t.model_id
	LEFT JOIN knowledge_active ka ON ka.kind='MODEL_ALIAS_RULE' AND ka.subject=t.provider_id||'/'||t.model_id
	LEFT JOIN knowledge_versions k ON k.id=ka.version_id
	WHERE :source<>'gateway' AND t.source IN ('GATEWAY_TEST','MANUAL_TEST') AND t.created_at>=:since AND t.created_at<:until
), filtered AS (
	SELECT * FROM activity
	WHERE (:key='' OR key_id=:key) AND (instr(lower(model),lower(:model))>0
		OR source='tests' AND instr(lower(model_group),atlas_canonical_id(:model))>0)
	AND CASE :status WHEN 'success' THEN outcome='SUCCESS'
		WHEN 'failed' THEN outcome<>'SUCCESS' AND ` + gatewayUsageSettled + `
		WHEN 'legacy_failure' THEN outcome NOT IN ('SUCCESS','RUNNING')
		ELSE 1 END
) `

func gatewayUsagePeriods(end time.Time, period string) []gatewayUsagePoint {
	var from time.Time
	if period == "24h" {
		from = end.Add(-24 * time.Hour)
	} else {
		days := 7
		if period == "30d" {
			days = 30
		}
		from = time.Date(end.Year(), end.Month(), end.Day(), 0, 0, 0, 0, end.Location()).AddDate(0, 0, 1-days)
	}
	points := make([]gatewayUsagePoint, 0, 30)
	for from.Before(end) {
		next := from.AddDate(0, 0, 1)
		if period == "24h" {
			next = time.Date(from.Year(), from.Month(), from.Day(), from.Hour(), 0, 0, 0, from.Location()).Add(time.Hour)
			if !next.After(from) {
				next = from.Add(time.Hour)
			}
		}
		if next.After(end) {
			next = end
		}
		points = append(points, gatewayUsagePoint{From: from.UnixMilli(), Until: next.UnixMilli()})
		from = next
	}
	return points
}

func gatewayUsageAdd(dst **int64, src *int64) {
	if src != nil {
		if *dst == nil {
			n := *src
			*dst = &n
		} else {
			**dst += *src
		}
	}
}

func (s *Server) gatewayUsage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	limit, offset := pageRange(r, 30)
	q := r.URL.Query()
	period, source, status := q.Get("range"), q.Get("source"), q.Get("status")
	legacyRange := period == ""
	if period == "" {
		period = "24h"
	}
	if source == "" {
		source = "gateway"
	}
	if !oneOf(period, "24h", "7d", "30d") || !oneOf(source, "all", "gateway", "tests") || !oneOf(status, "", "success", "failed") ||
		len(q.Get("model")) > 256 || q.Get("key_id") != "" && !validID(q.Get("key_id")) {
		http.Error(w, "invalid usage filters", 400)
		return
	}
	legacyFailures := status == "" && q.Get("failures") == "true"
	if legacyFailures && !legacyRange {
		status = "legacy_failure"
	}
	until := now() + 1
	if raw := q.Get("as_of"); raw != "" {
		anchor, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || anchor <= 0 || anchor > until {
			http.Error(w, "invalid usage snapshot", 400)
			return
		}
		until = anchor
	}
	zone := str(s.setting(ctx, "timezone", "Asia/Taipei"))
	location, err := time.LoadLocation(zone)
	if err != nil {
		location = time.FixedZone("Asia/Taipei", 8*60*60)
	}
	points := gatewayUsagePeriods(time.UnixMilli(until).In(location), period)
	since := points[0].From
	args := []any{sql.Named("source", source), sql.Named("since", since), sql.Named("until", until),
		sql.Named("key", q.Get("key_id")), sql.Named("model", strings.TrimSpace(q.Get("model"))), sql.Named("status", status)}

	// Aggregate with one ordered scan and a bounded set of time boundaries.
	// Each point describes an actual interval, including the partial current one.
	var buckets strings.Builder
	buckets.WriteString("CASE")
	chartArgs := append([]any{}, args...)
	for i, point := range points[:len(points)-1] {
		param := "edge" + strconv.Itoa(i)
		buckets.WriteString(" WHEN at<:" + param + " THEN " + strconv.Itoa(i))
		chartArgs = append(chartArgs, sql.Named(param, point.Until))
	}
	buckets.WriteString(" ELSE " + strconv.Itoa(len(points)-1) + " END")

	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, gatewayUsageRows+`SELECT `+buckets.String()+` AS bucket,
		COUNT(*),SUM(`+gatewayUsageSettled+`),SUM(outcome='SUCCESS'),SUM(input_tokens),SUM(output_tokens),
		COUNT(input_tokens),COUNT(output_tokens),TOTAL(ttft_ms),COUNT(ttft_ms)
		FROM filtered GROUP BY bucket`, chartArgs...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	var summary gatewayUsageSummary
	var ttftTotal float64
	var ttftReports int
	for rows.Next() {
		var i, reports int
		var ttft float64
		var point gatewayUsageSummary
		if err = rows.Scan(&i, &point.Requests, &point.Completed, &point.Successes, &point.Input, &point.Output,
			&point.InputReported, &point.OutputReported, &ttft, &reports); err != nil || i < 0 || i >= len(points) {
			rows.Close()
			http.Error(w, "database error", 500)
			return
		}
		points[i].gatewayUsageSummary = point
		summary.Requests += point.Requests
		summary.Completed += point.Completed
		summary.Successes += point.Successes
		summary.InputReported += point.InputReported
		summary.OutputReported += point.OutputReported
		gatewayUsageAdd(&summary.Input, point.Input)
		gatewayUsageAdd(&summary.Output, point.Output)
		ttftTotal += ttft
		ttftReports += reports
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if ttftReports != 0 {
		mean := ttftTotal / float64(ttftReports)
		summary.MeanTTFT = &mean
	}

	rows, err = tx.QueryContext(ctx, gatewayUsageRows+`SELECT source,model_group,COUNT(*) AS n FROM filtered
		GROUP BY source,model_group ORDER BY n DESC,model_group,source LIMIT 6`, args...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	rank := []gatewayUsageRank{}
	for rows.Next() {
		var v gatewayUsageRank
		if err = rows.Scan(&v.Source, &v.Model, &v.Requests); err != nil {
			rows.Close()
			http.Error(w, "database error", 500)
			return
		}
		rank = append(rank, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}

	if legacyRange {
		// Keep the previous API's unbounded history when no window is selected.
		args[1] = sql.Named("since", 0)
		if legacyFailures {
			args[5] = sql.Named("status", "legacy_failure")
		}
	}
	rows, err = tx.QueryContext(ctx, gatewayUsageRows+`SELECT id,source,key_label,model,provider,upstream_model,outcome,at,
		http_status,upstream_status,latency_ms,ttft_ms,input_tokens,output_tokens,attempts
		FROM filtered ORDER BY at DESC,id,source LIMIT :limit OFFSET :offset`,
		append(args, sql.Named("limit", limit+1), sql.Named("offset", offset))...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	items := []gatewayUsageItem{}
	for rows.Next() {
		var v gatewayUsageItem
		if err = rows.Scan(&v.ID, &v.Source, &v.Key, &v.Model, &v.Provider, &v.UpstreamModel, &v.Outcome, &v.At,
			&v.Status, &v.Upstream, &v.Latency, &v.TTFT, &v.Input, &v.Output, &v.Attempts); err != nil {
			rows.Close()
			http.Error(w, "database error", 500)
			return
		}
		items = append(items, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(items) > limit
	jsonOK(w, 200, struct {
		Summary  gatewayUsageSummary `json:"summary"`
		Items    []gatewayUsageItem  `json:"items"`
		Trend    []gatewayUsagePoint `json:"trend"`
		Ranking  []gatewayUsageRank  `json:"ranking"`
		AsOf     int64               `json:"as_of"`
		Timezone string              `json:"timezone"`
		More     bool                `json:"more"`
	}{summary, items[:min(len(items), limit)], points, rank, until, location.String(), more})
}
