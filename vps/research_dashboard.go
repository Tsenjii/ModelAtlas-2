package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

// A single read transaction prevents a completed run from being paired with a
// pre-reconciliation candidate list. No network I/O occurs while holding it.
func (s *Server) researchDashboard(w http.ResponseWriter, r *http.Request) {
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	ctx := r.Context()
	candidates := []Candidate{}
	sources := []ResearchSource{}
	runs := []ResearchRun{}
	counts := map[string]int{}
	rows, e := tx.QueryContext(ctx, "SELECT c.id,c.provider_id,c.model_id,c.rpd,c.tpd,c.taiwan,c.recurring,c.strength,c.state,COALESCE(d.details_json,'{}') FROM research_candidates c LEFT JOIN research_candidate_details d ON d.candidate_id=c.id ORDER BY c.updated_at DESC LIMIT 1000")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	for rows.Next() {
		var c Candidate
		var rp, tp sql.NullInt64
		var raw string
		if rows.Scan(&c.ID, &c.ProviderID, &c.ModelID, &rp, &tp, &c.Taiwan, &c.Recurring, &c.Strength, &c.State, &raw) != nil {
			continue
		}
		if rp.Valid {
			c.RPD = &rp.Int64
		}
		if tp.Valid {
			c.TPD = &tp.Int64
		}
		json.Unmarshal([]byte(raw), &c.CandidateDetails)
		candidates = append(candidates, c)
	}
	rows.Close()
	rows, e = tx.QueryContext(ctx, "SELECT id,name,kind,url,language,enabled,config_json FROM research_sources ORDER BY rowid DESC LIMIT 500")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	for rows.Next() {
		var src ResearchSource
		var raw string
		if rows.Scan(&src.ID, &src.Name, &src.Kind, &src.URL, &src.Language, &src.Enabled, &raw) == nil {
			json.Unmarshal([]byte(raw), &src.Config)
			sources = append(sources, src)
		}
	}
	rows.Close()
	rows, e = tx.QueryContext(ctx, "SELECT r.id,r.status,r.checkpoint,r.started_at,o.budget,o.used,o.probe_budget,o.probes_used,COALESCE(o.summary_json,'{}') FROM research_runs r LEFT JOIN research_run_options o ON o.run_id=r.id ORDER BY r.rowid DESC LIMIT 100")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	for rows.Next() {
		var run ResearchRun
		var b, u, p, pu sql.NullInt64
		var raw string
		if rows.Scan(&run.ID, &run.Status, &run.Checkpoint, &run.StartedAt, &b, &u, &p, &pu, &raw) == nil {
			if b.Valid {
				run.Budget = &b.Int64
			}
			if u.Valid {
				run.Used = &u.Int64
			}
			if p.Valid {
				run.ProbeBudget = &p.Int64
			}
			if pu.Valid {
				run.ProbesUsed = &pu.Int64
			}
			json.Unmarshal([]byte(raw), &run.Summary)
			runs = append(runs, run)
		}
	}
	rows.Close()
	rows, e = tx.QueryContext(ctx, "SELECT state,COUNT(*) FROM research_candidates GROUP BY state")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	for rows.Next() {
		var state string
		var n int
		rows.Scan(&state, &n)
		counts[state] = n
	}
	rows.Close()
	for _, state := range []string{"DISCOVERED", "VERIFYING", "VERIFIED", "HIGH_VALUE_GREY_ZONE", "STALE", "FAILED", "REJECTED"} {
		if _, ok := counts[state]; !ok {
			counts[state] = 0
		}
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"candidates": candidates, "sources": sources, "runs": runs, "funnel": map[string]any{"states": counts}})
}
