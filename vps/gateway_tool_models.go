package main

import (
	"bufio"
	"compress/gzip"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
)

func toolJSONWriter(w http.ResponseWriter, r *http.Request) (io.Writer, func()) {
	w.Header().Add("Vary", "Accept-Encoding")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if !acceptsAssetGzip(r.Header.Get("Accept-Encoding")) {
		return w, func() {}
	}
	// These are private, read-only snapshots. Use the same no-store policy as
	// other tool data, while reducing the transfer size of large catalogs.
	w.Header().Set("Content-Encoding", "gzip")
	zip, _ := gzip.NewWriterLevel(w, gzip.BestSpeed)
	return zip, func() { _ = zip.Close() }
}

func writeToolJSONRows[T any](w *bufio.Writer, rows []T) error {
	encoder := json.NewEncoder(w)
	w.WriteByte('[')
	for i := range rows {
		if i > 0 {
			w.WriteByte(',')
		}
		if err := encoder.Encode(&rows[i]); err != nil {
			return err
		}
	}
	return w.WriteByte(']')
}

type toolSelectedOffer struct {
	toolMembership
	Offer       map[string]any `json:"offer"`
	Enabled     bool           `json:"enabled"`
	PriceState  string         `json:"price_state"`
	Performance map[string]any `json:"performance"`
}

func (s *Server) readToolModelOffer(ctx context.Context, v Selection) (toolSelectedOffer, error) {
	var member toolSelectedOffer
	member.Selection = v
	err := s.Store.DB.QueryRowContext(ctx, `SELECT COALESCE(ms.state,'ACTIVE'),COALESCE(ms.favorite,0),COALESCE(ms.watchlist,0)
		FROM my_models m LEFT JOIN membership_state ms ON ms.scope_key=?
		WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=? AND m.model_id=? AND m.group_id=? AND m.endpoint=?`,
		scopeKey(v), v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint).Scan(&member.State, &member.Favorite, &member.Watchlist)
	if err != nil {
		return member, err
	}
	member.Offer = s.offerSummary(ctx, v)
	member.Enabled = s.selectionUsable(ctx, v)
	member.PriceState = offerPriceState(member.Offer)
	duration := performanceWindow(str(s.setting(ctx, "performance_window", "7d")))
	end := now()
	member.Performance = s.performanceSQL(ctx, v, end-duration, end, false)
	return member, nil
}

const toolMembershipKey = "atlas_scope_key(m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint)"
const toolMembershipFields = "m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint,COALESCE(ms.state,'ACTIVE'),COALESCE(ms.favorite,0),COALESCE(ms.watchlist,0)"
const toolMembershipOrder = " ORDER BY m.created_at DESC,m.rowid DESC"

type toolMembership struct {
	Selection
	State     string `json:"state"`
	Favorite  bool   `json:"favorite"`
	Watchlist bool   `json:"watchlist"`
}

func (v *toolMembership) scanFields() []any {
	return []any{&v.ProviderID, &v.AccountID, &v.CredentialID, &v.ModelID, &v.Group, &v.Endpoint, &v.State, &v.Favorite, &v.Watchlist}
}

const toolAliasJoins = ` LEFT JOIN knowledge_active ka ON ka.kind='MODEL_ALIAS_RULE' AND ka.subject=m.provider_id||'/'||%s
	LEFT JOIN knowledge_versions kv ON kv.id=ka.version_id `

func toolModelMetadata(providerID, modelID, raw, alias string) ModelMetadata {
	meta := modelMetadata(providerID, modelID, raw)
	if meta.Source != "MANUAL" && alias != "" {
		var rule map[string]any
		_ = json.Unmarshal([]byte(alias), &rule)
		meta.applyAliasRule(rule)
	}
	return meta
}

func (s *Server) toolModelCatalog(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	tx, err := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	models, err := readToolCatalog(ctx, tx)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	var memberships []toolMembership
	if r.URL.Query().Get("memberships") == "1" {
		memberships, err = readToolMembershipStates(ctx, tx)
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	body, finish := toolJSONWriter(w, r)
	defer finish()
	// Encode one row at a time, avoiding a second catalog-sized allocation.
	buffer := bufio.NewWriterSize(body, 32<<10)
	buffer.WriteString(`{"models":`)
	if writeToolJSONRows(buffer, models) != nil {
		return
	}
	if memberships != nil {
		buffer.WriteString(`,"memberships":`)
		if writeToolJSONRows(buffer, memberships) != nil {
			return
		}
	}
	buffer.WriteByte('}')
	_ = buffer.Flush()
}

func readToolCatalog(ctx context.Context, tx *sql.Tx) ([]Model, error) {
	rows, err := tx.QueryContext(ctx, `SELECT m.provider_id,m.id,m.display_name,m.price_state,m.quota_state,m.provenance,
		COALESCE(md.metadata_json,''),COALESCE(kv.data_json,'')
		FROM models m JOIN providers p ON p.id=m.provider_id
		LEFT JOIN model_metadata md ON md.provider_id=m.provider_id AND md.model_id=m.id
		`+fmt.Sprintf(toolAliasJoins, "m.id")+` WHERE `+gatewayActiveProvider+` ORDER BY m.provider_id,m.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Model{}
	for rows.Next() {
		var m Model
		var raw, alias string
		if err = rows.Scan(&m.ProviderID, &m.ID, &m.DisplayName, &m.PriceState, &m.QuotaState, &m.Provenance, &raw, &alias); err != nil {
			return nil, err
		}
		meta := toolModelMetadata(m.ProviderID, m.ID, raw, alias)
		m.Metadata = &meta
		out = append(out, m)
	}
	return out, rows.Err()
}

type toolReliability struct {
	Samples int      `json:"samples"`
	Success *float64 `json:"success_rate"`
}

func toolSuccess(samples, successes int) *float64 {
	if samples == 0 {
		return nil
	}
	rate := float64(successes) / float64(samples)
	return &rate
}

type toolPerformance struct {
	Source  string   `json:"source"`
	Samples int      `json:"samples"`
	Success *float64 `json:"success_rate"`
	Last    *int64   `json:"last_request_at,omitempty"`
	TTFT    any      `json:"ttft,omitempty"`
	TPS     any      `json:"tps,omitempty"`
	Latency any      `json:"latency,omitempty"`
}

type toolModelMember struct {
	toolMembership
	Metadata     ModelMetadata              `json:"metadata"`
	Capabilities []string                   `json:"capabilities"`
	Performance  toolPerformance            `json:"performance"`
	Reliability  map[string]toolReliability `json:"reliability"`
	PriceState   string                     `json:"price_state"`
	Offer        map[string]any             `json:"offer"`
	Enabled      bool                       `json:"enabled"`
}

type toolMemberRow struct {
	toolModelMember
	created      int64
	tags         string
	operation    string
	credentialOn bool
}

const toolMemberBatchSize = 400

func (s *Server) toolModelMemberships(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	_, offset := pageRange(r, toolMemberBatchSize)
	limit := toolMemberBatchSize
	if raw := r.URL.Query().Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > toolMemberBatchSize {
			http.Error(w, "invalid limit", 400)
			return
		}
		limit = n
	}
	duration := performanceWindow(str(s.setting(ctx, "performance_window", "7d")))
	rows, err := s.readToolMembers(ctx, limit, offset, now(), duration)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	type credentialKey struct{ provider, account, credential string }
	type groupKey struct {
		credentialKey
		group string
	}
	type credentialFacts struct {
		version int
		usable  bool
	}
	groups := map[groupKey]bool{}
	credentials := map[credentialKey]credentialFacts{}
	strength := map[string]any{}
	external := map[string]any{}
	out := make([]toolModelMember, 0, len(rows))
	for _, row := range rows {
		if ctx.Err() != nil {
			return
		}
		v := row.Selection
		ck := credentialKey{v.ProviderID, v.AccountID, v.CredentialID}
		facts, known := credentials[ck]
		if !known {
			s.Store.DB.QueryRowContext(ctx, credentialVersionQuery, v.ProviderID, v.AccountID, v.CredentialID).Scan(&facts.version)
			if row.credentialOn {
				if p, err := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID); err == nil {
					facts.usable = s.credentialUsable(ctx, v, p)
				}
			}
			credentials[ck] = facts
		}
		gk := groupKey{ck, v.Group}
		bound, known := groups[gk]
		if !known {
			bound = s.groupBound(ctx, v)
			groups[gk] = bound
		}
		row.Enabled = facts.usable && row.State == "ACTIVE" && bound && validScope(v) && row.operation != ""
		row.Offer = s.offerScopeSummary(ctx, v, offerSummaryInput{version: facts.version, created: row.created, tags: row.tags, groupBound: bound})
		if _, ok := strength[v.ModelID]; !ok {
			strength[v.ModelID] = s.offerStrength(ctx, v.ModelID)
		}
		if _, ok := external[row.Metadata.CanonicalID]; !ok {
			external[row.Metadata.CanonicalID] = s.offerExternal(ctx, row.Metadata.CanonicalID)
		}
		row.Offer["strength"] = strength[v.ModelID]
		row.Offer["external_performance"] = external[row.Metadata.CanonicalID]
		row.PriceState = offerPriceState(row.Offer)
		delete(row.Offer, "effective_price")
		delete(row.Offer, "credential_version")
		delete(row.Offer, "notes")
		out = append(out, row.toolModelMember)
	}
	body, finish := toolJSONWriter(w, r)
	defer finish()
	_ = json.NewEncoder(body).Encode(out)
}

func offerPriceState(offer map[string]any) string {
	if effective, ok := offer["effective_price"].(map[string]any); ok {
		if legs, ok := effective["legs"].(PriceLegs); ok {
			return priceState(legs)
		}
	}
	return "UNKNOWN"
}

const toolSettledTelemetry = "t.outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED')"
const toolCompletedWindow = "t.created_at>=:start AND t.created_at<:end AND " + toolSettledTelemetry

func (s *Server) readToolMembers(ctx context.Context, limit, offset int, end, duration int64) ([]toolMemberRow, error) {
	// One indexed scan per connection supplies the visible means, recent-use
	// timestamp, success rate and both reliability filters.
	rows, err := s.Store.DB.QueryContext(ctx, `WITH page AS MATERIALIZED (
		SELECT m.rowid AS member_id,m.* FROM my_models m `+toolMembershipOrder+` LIMIT :limit OFFSET :offset
	), metrics AS MATERIALIZED (
		SELECT m.member_id,
			COUNT(*) FILTER (WHERE `+toolCompletedWindow+`) AS samples,
			COUNT(*) FILTER (WHERE t.created_at>=:start AND t.created_at<:end AND t.outcome='SUCCESS') AS successes,
			MAX(t.created_at) FILTER (WHERE t.created_at>=:start AND t.created_at<:end) AS last_at,
			AVG(t.ttft_ms) FILTER (WHERE `+toolCompletedWindow+` AND t.ttft_ms>=0) AS ttft,
			AVG(t.latency_ms) FILTER (WHERE `+toolCompletedWindow+` AND t.latency_ms>=0) AS latency,
			AVG(CASE WHEN (`+numericTPS+`)>=0 THEN `+numericTPS+` END) FILTER (WHERE `+toolCompletedWindow+`) AS tps,
			COUNT(*) FILTER (WHERE t.created_at>=:hour AND `+toolSettledTelemetry+`) AS hour_samples,
			COUNT(*) FILTER (WHERE t.created_at>=:hour AND t.outcome='SUCCESS') AS hour_successes,
			COUNT(*) FILTER (WHERE t.created_at>=:day AND `+toolSettledTelemetry+`) AS day_samples,
			COUNT(*) FILTER (WHERE t.created_at>=:day AND t.outcome='SUCCESS') AS day_successes
		FROM page m LEFT JOIN telemetry t ON t.provider_id=m.provider_id AND t.account_id=m.account_id
			AND t.credential_id=m.credential_id AND t.model_id=m.model_id AND t.group_id=m.group_id
			AND t.endpoint=m.endpoint AND t.created_at>=MIN(:start,:day)
		GROUP BY m.member_id
	)
	SELECT `+toolMembershipFields+`,m.created_at,COALESCE(e.tags_json,''),COALESCE(cc.capabilities_json,''),
		COALESCE(md.metadata_json,''),COALESCE(kv.data_json,''),COALESCE(ep.operation,''),COALESCE(c.enabled=1 AND c.role='INFERENCE',0),
		x.samples,x.successes,x.last_at,x.ttft,x.latency,x.tps,x.hour_samples,x.hour_successes,x.day_samples,x.day_successes
	FROM page m JOIN metrics x ON x.member_id=m.member_id
	LEFT JOIN membership_state ms ON ms.scope_key=`+toolMembershipKey+`
	LEFT JOIN capability_confirmations cc ON cc.scope_key=`+toolMembershipKey+`
	LEFT JOIN entity_notes e ON e.subject_type='MODEL' AND e.subject_id=`+toolMembershipKey+`
	LEFT JOIN model_metadata md ON md.provider_id=m.provider_id AND md.model_id=m.model_id
	LEFT JOIN credentials c ON c.provider_id=m.provider_id AND c.account_id=m.account_id AND c.id=m.credential_id
	LEFT JOIN endpoint_profiles ep ON ep.provider_id=m.provider_id AND ep.id=m.endpoint
	`+fmt.Sprintf(toolAliasJoins, "m.model_id")+` ORDER BY m.created_at DESC,m.member_id DESC`,
		sql.Named("limit", limit), sql.Named("offset", offset), sql.Named("end", end), sql.Named("start", end-duration),
		sql.Named("hour", end-3600000), sql.Named("day", end-86400000))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]toolMemberRow, 0, limit)
	for rows.Next() {
		var row toolMemberRow
		var raw, alias, capabilities string
		var success, hour, hourSuccess, day, daySuccess int
		var last sql.NullInt64
		var ttft, latency, tps sql.NullFloat64
		if err := rows.Scan(append(row.scanFields(), &row.created, &row.tags, &capabilities, &raw, &alias, &row.operation,
			&row.credentialOn, &row.Performance.Samples, &success, &last, &ttft, &latency, &tps,
			&hour, &hourSuccess, &day, &daySuccess)...); err != nil {
			return nil, err
		}
		if _, ok := endpointNames[row.Endpoint]; ok {
			row.operation = row.Endpoint
		}
		row.Metadata = toolModelMetadata(row.ProviderID, row.ModelID, raw, alias)
		row.Capabilities = []string{}
		if capabilities != "" {
			_ = json.Unmarshal([]byte(capabilities), &row.Capabilities)
		}
		row.Performance.Source = "MY_KEY_LOCAL"
		row.Performance.Success = toolSuccess(row.Performance.Samples, success)
		if last.Valid {
			row.Performance.Last = &last.Int64
		}
		row.Performance.TTFT = plotMetric(ttft)
		row.Performance.Latency = plotMetric(latency)
		row.Performance.TPS = plotMetric(tps)
		row.Reliability = map[string]toolReliability{
			"1h":  {hour, toolSuccess(hour, hourSuccess)},
			"24h": {day, toolSuccess(day, daySuccess)},
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

func readToolMembershipStates(ctx context.Context, tx *sql.Tx) ([]toolMembership, error) {
	rows, err := tx.QueryContext(ctx, "SELECT "+toolMembershipFields+" FROM my_models m LEFT JOIN membership_state ms ON ms.scope_key="+toolMembershipKey+toolMembershipOrder)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []toolMembership{}
	for rows.Next() {
		var v toolMembership
		if err = rows.Scan(v.scanFields()...); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
