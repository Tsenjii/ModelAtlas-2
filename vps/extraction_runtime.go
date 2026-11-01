package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"
)

type extractionBatch struct {
	s          *Server
	ctx        context.Context
	id         string
	action     string
	trace      string
	definition extractionDefinition
	selected   map[string]bool
	cache      map[string]extractionRead
	denied     error
	localWait  bool
	http       int
}

// Only an explicitly measured and activated local plan can change read mode.
// The cache belongs to this exact enrichment call and is discarded afterwards.
func (s *Server) extractionReader(ctx context.Context, v Selection, configs map[string]ConnectorConfig) *extractionBatch {
	var id string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT version_id FROM extraction_active WHERE scope_key=?", extractionScope(v)).Scan(&id)
	if e == sql.ErrNoRows {
		return nil
	}
	b := &extractionBatch{s: s, ctx: ctx, id: id, selected: map[string]bool{}, cache: map[string]extractionRead{}, denied: e}
	plan, e := readExtraction(ctx, s.Store.DB, id)
	if e != nil {
		b.denied = e
	} else {
		b.definition = plan.Definition
		var hash, receipt, report, status, reportHash string
		e = s.Store.DB.QueryRowContext(ctx, `SELECT b.definition_hash,b.comparison_id,c.report_json,c.status,b.comparison_hash FROM extraction_bindings b JOIN extraction_comparisons c ON c.id=b.comparison_id WHERE b.version_id=? AND c.version_id=b.version_id`, id).Scan(&hash, &receipt, &report, &status, &reportHash)
		var measured extractionComparison
		if e != nil {
			b.denied = e
		} else if hash != connectorHash(plan.Definition) || json.Unmarshal([]byte(report), &measured) != nil || measured.ID != receipt || connectorHash(measured) != reportHash || !validExtractionComparison(measured, plan.Definition, id) || measured.Status != status {
			b.denied = errExtractionReview
		}
		for _, ref := range plan.Definition.Rules {
			b.selected[ref.ID] = true
			live, ok := configs[ref.ID]
			if !ok || connectorHash(live) != connectorHash(ref.Config) {
				b.denied = errExtractionReview
			}
		}
		if b.denied == nil {
			b.denied = extractionCurrent(ctx, s.Store.DB, plan.Definition)
		}
	}
	if b.denied == nil {
		e = s.Store.DB.QueryRowContext(ctx, `SELECT d.action_id FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id WHERE d.family='EXTRACTION' AND d.kind=? AND d.after_id=? AND a.state='CONFIRMED' ORDER BY a.rowid DESC LIMIT 1`, extractionScope(v), id).Scan(&b.action)
		if e != nil {
			b.denied = e
		}
	}
	if b.denied == nil {
		b.trace = newID()
		raw, _ := json.Marshal(map[string]any{"version_id": id, "activation_action_id": b.action, "status": "RUNNING", "http_requests": nil, "inference_requests": 0, "total_monetary_cost": nil, "at": now()})
		if _, e = s.Store.DB.ExecContext(ctx, "INSERT INTO extraction_observations VALUES(?,?,?,?,?)", b.trace, id, "RUNNING", string(raw), now()); e != nil {
			b.denied = e
			b.trace = ""
		}
	}
	return b
}
func (b *extractionBatch) handles(id string, c ConnectorConfig) bool {
	// If local authority cannot be read, do not silently switch back to a more
	// expensive anonymous plan. Other independently configured roles are intact.
	return b.selected[id] || b.denied != nil && extractionEligible(c)
}
func (b *extractionBatch) rows(c ConnectorConfig) ([]any, error) {
	if b.denied != nil {
		return nil, b.denied
	}
	if e := extractionCurrent(b.ctx, b.s.Store.DB, b.definition); e != nil {
		b.denied = e
		return nil, e
	}
	key := extractionGroup(c, b.definition.Selection)
	entry, ok := b.cache[key]
	if !ok {
		ctx := context.WithValue(b.ctx, extractionMeterKey{}, &b.http)
		entry.root, entry.err = b.s.connectorRoot(ctx, c, b.definition.Selection)
		b.cache[key] = entry
	}
	if e := extractionCurrent(b.ctx, b.s.Store.DB, b.definition); e != nil {
		b.denied = e
		return nil, e
	}
	if entry.err != nil {
		if b.http == 0 || researchTransportStopped(b.ctx, entry.err) || siteRecipeStorageFailure(entry.err) || entry.err.Error() == "vault unavailable" {
			b.localWait = true
		}
		return nil, entry.err
	}
	return connectorRowsFromRoot(c, entry.root)
}
func (b *extractionBatch) finish(out map[string]string) string {
	status := "SUCCESS"
	if b.ctx.Err() != nil {
		status = "CANCELLED"
	} else if b.denied != nil {
		status = "SCOPE_CHANGED"
	} else if b.localWait {
		status = "LOCAL_WAIT"
	} else {
		for id := range b.selected {
			if out["connector:"+id] != "SUCCESS" {
				status = "FAILED"
			}
		}
	}
	if b.id == "" {
		return "UNAVAILABLE"
	}
	ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
	defer stop()
	tx, e := b.s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return "MONITOR_UNAVAILABLE"
	}
	defer tx.Rollback()
	report := map[string]any{"version_id": b.id, "activation_action_id": b.action, "status": status, "http_requests": b.http, "inference_requests": 0, "total_monetary_cost": nil, "at": now(), "auto_rollback": false}
	id := b.trace
	raw, _ := json.Marshal(report)
	if id == "" {
		id = newID()
		_, e = tx.ExecContext(ctx, "INSERT INTO extraction_observations VALUES(?,?,?,?,?)", id, b.id, status, string(raw), now())
	} else {
		_, e = tx.ExecContext(ctx, "UPDATE extraction_observations SET status=?,report_json=? WHERE id=? AND status='RUNNING'", status, string(raw), id)
	}
	if e != nil {
		return "MONITOR_UNAVAILABLE"
	}
	if status == "FAILED" {
		rows, e := tx.QueryContext(ctx, "SELECT status FROM extraction_observations WHERE version_id=? AND json_extract(report_json,'$.activation_action_id')=? AND status IN('SUCCESS','FAILED') ORDER BY rowid DESC LIMIT 3", b.id, b.action)
		if e != nil {
			return "MONITOR_UNAVAILABLE"
		}
		failures := 0
		for rows.Next() {
			var value string
			if e = rows.Scan(&value); e != nil {
				break
			}
			if value == "FAILED" {
				failures++
			}
		}
		if e == nil {
			e = rows.Err()
		}
		rows.Close()
		if e != nil {
			return "MONITOR_UNAVAILABLE"
		}
		if failures == 3 && activeVersion(ctx, tx, "EXTRACTION", extractionScope(b.definition.Selection), "") == b.id {
			var action, before string
			e = tx.QueryRowContext(ctx, `SELECT d.action_id,d.before_id FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id WHERE d.action_id=? AND d.family='EXTRACTION' AND d.after_id=? AND a.state='CONFIRMED'`, b.action, b.id).Scan(&action, &before)
			if e == sql.ErrNoRows {
				if tx.Commit() != nil {
					return "MONITOR_UNAVAILABLE"
				}
				return status
			}
			if e != nil || setActiveVersion(ctx, tx, "EXTRACTION", extractionScope(b.definition.Selection), "", before) != nil {
				return "MONITOR_UNAVAILABLE"
			}
			if _, e = tx.ExecContext(ctx, "UPDATE action_ledger SET state='UNDONE' WHERE id=?", action); e != nil {
				return "MONITOR_UNAVAILABLE"
			}
			report["auto_rollback"] = true
			report["rollback_action_id"] = action
			raw, _ = json.Marshal(report)
			if _, e = tx.ExecContext(ctx, "UPDATE extraction_observations SET report_json=? WHERE id=?", string(raw), id); e != nil {
				return "MONITOR_UNAVAILABLE"
			}
			status = "AUTO_ROLLED_BACK"
		}
	}
	if tx.Commit() != nil {
		return "MONITOR_UNAVAILABLE"
	}
	return status
}
