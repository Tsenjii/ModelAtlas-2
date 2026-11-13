package main

import (
	"context"
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

const gatewayUsageSettled = "NOT IN ('RUNNING','INTERRUPTED','CANCELLED')"

// Only explicit model tests join relay calls. The latter are counted from the
// admission row once, even if two upstream attempts wrote telemetry.
const gatewayUsageRelayFrom = ` FROM gateway_requests g JOIN gateway_keys k ON k.id=g.key_id `

var gatewayUsageRelayFilter = ` WHERE :source<>'tests' AND g.created_at>=:since AND g.created_at<:until
	AND (:key='' OR g.key_id=:key) AND (:model='' OR instr(lower(g.model_id),lower(:model))>0)` + gatewayUsageOutcome("g.outcome")

const gatewayUsageModelGroup = `COALESCE(` + gatewayPreferredModelIDSQL + `,atlas_canonical_id(t.model_id))`
const gatewayUsageModelJoins = ` LEFT JOIN model_metadata md ON md.provider_id=t.provider_id AND md.model_id=t.model_id
	LEFT JOIN knowledge_active ka ON ka.kind='MODEL_ALIAS_RULE' AND ka.subject=t.provider_id||'/'||t.model_id
	LEFT JOIN knowledge_versions k ON k.id=ka.version_id `
const gatewayUsageModelMatch = ` AND (instr(lower(t.model_id),lower(:model))>0
	OR instr(lower(` + gatewayUsageModelGroup + `),atlas_canonical_id(:model))>0) `

func gatewayUsageOutcome(field string) string {
	return ` AND CASE :status WHEN 'success' THEN ` + field + `='SUCCESS'
		WHEN 'failed' THEN ` + field + `<>'SUCCESS' AND ` + field + ` ` + gatewayUsageSettled + `
		WHEN 'legacy_failure' THEN ` + field + ` NOT IN ('SUCCESS','RUNNING') ELSE 1 END `
}

func gatewayUsageTestRows(search string) string {
	from := " FROM telemetry t "
	filter := ` WHERE :source<>'gateway' AND :key='' AND t.source IN ('GATEWAY_TEST','MANUAL_TEST')
		AND t.created_at>=:since AND t.created_at<:until ` + gatewayUsageOutcome("t.outcome")
	if search != "" {
		from += gatewayUsageModelJoins
		filter += gatewayUsageModelMatch
	}
	return from + filter
}

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
	model := strings.TrimSpace(q.Get("model"))
	args := []any{sql.Named("source", source), sql.Named("since", since), sql.Named("until", until),
		sql.Named("key", q.Get("key_id")), sql.Named("model", model), sql.Named("status", status)}

	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()

	itemsOnly := q.Get("items_only") == "1"
	tests := gatewayUsageTestRows(model)
	var summary gatewayUsageSummary
	var rank []gatewayUsageRank
	if !itemsOnly {
		summary, rank, err = readGatewayUsageStats(ctx, tx, points, args, model != "", tests)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}

	if legacyRange {
		// Keep the previous API's unbounded history when no window is selected.
		args[1] = sql.Named("since", 0)
		if legacyFailures {
			args[5] = sql.Named("status", "legacy_failure")
		}
	}

	// Page only identities so old rows never need their labels or receipt
	// payloads read just to determine the visible page.
	rows, err := tx.QueryContext(ctx, `WITH page AS MATERIALIZED (
		SELECT g.id AS id,'gateway' AS source,g.created_at AS at`+gatewayUsageRelayFrom+gatewayUsageRelayFilter+`
		UNION ALL
		SELECT t.id AS id,'tests' AS source,t.created_at AS at`+tests+`
		ORDER BY at DESC,id,source LIMIT :limit OFFSET :offset
	)
	SELECT e.id,e.source,COALESCE(k.label,''),COALESCE(g.model_id,t.model_id),
		CASE WHEN e.source='gateway' THEN COALESCE(p.name,'') ELSE COALESCE(p.name,t.provider_id) END,
		COALESCE(t.model_id,''),COALESCE(g.outcome,t.outcome),e.at,
		g.http_status,t.status_code,CASE WHEN e.source='gateway' THEN g.latency_ms ELSE t.latency_ms END,
		t.ttft_ms,t.input_tokens,t.output_tokens,COALESCE(g.attempts,1)
	FROM page e LEFT JOIN gateway_requests g ON e.source='gateway' AND g.id=e.id
	LEFT JOIN gateway_keys k ON k.id=g.key_id
	LEFT JOIN telemetry t ON t.id=CASE WHEN e.source='gateway' THEN g.sample_id ELSE e.id END
	LEFT JOIN providers p ON p.id=t.provider_id ORDER BY e.at DESC,e.id,e.source`,
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
	page := gatewayUsagePage{items[:min(len(items), limit)], until, location.String(), len(items) > limit}
	if itemsOnly {
		jsonOK(w, 200, page)
		return
	}
	jsonOK(w, 200, struct {
		gatewayUsagePage
		Summary gatewayUsageSummary `json:"summary"`
		Trend   []gatewayUsagePoint `json:"trend"`
		Ranking []gatewayUsageRank  `json:"ranking"`
	}{page, summary, points, rank})
}

type gatewayUsagePage struct {
	Items    []gatewayUsageItem `json:"items"`
	AsOf     int64              `json:"as_of"`
	Timezone string             `json:"timezone"`
	More     bool               `json:"more"`
}

func readGatewayUsageStats(ctx context.Context, tx *sql.Tx, points []gatewayUsagePoint, args []any, filtered bool, tests string) (gatewayUsageSummary, []gatewayUsageRank, error) {
	var summary gatewayUsageSummary
	var rank []gatewayUsageRank
	var err error
	if filtered {
		// Match raw and canonical IDs once per provider/model pair for the
		// whole window, including tests for models removed from the catalog.
		var models sql.NullString
		rank, models, err = readGatewayUsageRanking(ctx, tx, args, true)
		if err != nil {
			return summary, nil, err
		}
		if models.Valid {
			tests = gatewayUsageTestRows("") + ` AND (t.provider_id,t.model_id) IN (
				SELECT json_extract(value,'$[0]'),json_extract(value,'$[1]') FROM json_each(:test_models)
			) `
			args = append(append([]any{}, args...), sql.Named("test_models", models.String))
		}
	}
	// Each interval can use the time indexes directly. Avoid sorting all
	// retained activity into buckets before calculating the chart.
	chart, err := tx.PrepareContext(ctx, `WITH activity(requests,completed,successes,input_tokens,output_tokens,input_reported,output_reported,ttft,ttft_reported) AS (
		SELECT `+gatewayUsageAggregate("g.outcome")+gatewayUsageRelayFrom+
		`LEFT JOIN telemetry t ON t.id=g.sample_id`+gatewayUsageRelayFilter+`
		UNION ALL
		SELECT `+gatewayUsageAggregate("t.outcome")+tests+`
	)
	SELECT SUM(requests),COALESCE(SUM(completed),0),COALESCE(SUM(successes),0),SUM(input_tokens),SUM(output_tokens),
		SUM(input_reported),SUM(output_reported),TOTAL(ttft),SUM(ttft_reported)
	FROM activity`)
	if err != nil {
		return summary, nil, err
	}
	defer chart.Close()
	chartArgs := append([]any{}, args...)
	var ttftTotal float64
	var ttftReports int
	for i := range points {
		chartArgs[1] = sql.Named("since", points[i].From)
		chartArgs[2] = sql.Named("until", points[i].Until)
		var reports int
		var ttft float64
		var point gatewayUsageSummary
		if err = chart.QueryRowContext(ctx, chartArgs...).Scan(&point.Requests, &point.Completed, &point.Successes, &point.Input, &point.Output,
			&point.InputReported, &point.OutputReported, &ttft, &reports); err != nil {
			return summary, nil, err
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
	if ttftReports != 0 {
		mean := ttftTotal / float64(ttftReports)
		summary.MeanTTFT = &mean
	}
	if rank != nil {
		return summary, rank, nil
	}

	rank, _, err = readGatewayUsageRanking(ctx, tx, args, false)
	return summary, rank, err
}

func readGatewayUsageRanking(ctx context.Context, tx *sql.Tx, args []any, filtered bool) ([]gatewayUsageRank, sql.NullString, error) {
	// Counts are grouped on raw identities first. Alias resolution is shared
	// with the record list, including saved aliases of removed models.
	match, materialized, matchedRows := "", "", ""
	if filtered {
		match, materialized = gatewayUsageModelMatch, "MATERIALIZED"
		matchedRows = ` UNION ALL SELECT 'matched',NULL,0,
			CASE WHEN COUNT(*)<=10000 THEN json_group_array(json_array(provider_id,model_id)) END,1
			FROM (SELECT provider_id,model_id FROM test_groups LIMIT 10001) `
	}
	rows, err := tx.QueryContext(ctx, `WITH test_counts AS (
		SELECT t.provider_id,t.model_id,COUNT(*) AS n`+gatewayUsageTestRows("")+` GROUP BY t.provider_id,t.model_id
	), test_groups AS `+materialized+` (
		SELECT t.provider_id,t.model_id,`+gatewayUsageModelGroup+` AS model_group,t.n
		FROM test_counts t`+gatewayUsageModelJoins+`WHERE 1`+match+`
	), ranking AS (
		SELECT 'gateway' AS source,g.model_id AS model_group,COUNT(*) AS n`+gatewayUsageRelayFrom+gatewayUsageRelayFilter+`
		GROUP BY g.model_id
		UNION ALL SELECT 'tests',model_group,SUM(n) FROM test_groups GROUP BY model_group
	), top_models AS (
		SELECT source,model_group,n FROM ranking ORDER BY n DESC,model_group,source LIMIT 6
	)
	SELECT source,model_group,n,NULL AS matches,0 AS match_row FROM top_models`+matchedRows+`
	ORDER BY match_row,n DESC,model_group,source`, args...)
	var matches sql.NullString
	if err != nil {
		return nil, matches, err
	}
	defer rows.Close()
	rank := []gatewayUsageRank{}
	for rows.Next() {
		var row gatewayUsageRank
		var model, encoded sql.NullString
		var matched bool
		if err = rows.Scan(&row.Source, &model, &row.Requests, &encoded, &matched); err != nil {
			return nil, matches, err
		}
		if matched {
			matches = encoded
		} else {
			row.Model = model.String
			rank = append(rank, row)
		}
	}
	return rank, matches, rows.Err()
}

func gatewayUsageAggregate(outcome string) string {
	return `COUNT(*),SUM(` + outcome + " " + gatewayUsageSettled + `),SUM(` + outcome + `='SUCCESS'),
		SUM(t.input_tokens),SUM(t.output_tokens),COUNT(t.input_tokens),COUNT(t.output_tokens),TOTAL(t.ttft_ms),COUNT(t.ttft_ms)`
}
