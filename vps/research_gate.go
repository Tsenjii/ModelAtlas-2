package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
)

type Eligibility struct {
	CandidateID    string `json:"candidate_id"`
	Classification string `json:"classification"`
	Reason         string `json:"reason"`
}

// The ordinary hard gate requires both bounds, confirmed recurring evidence,
// Taiwan eligibility and a strong model; a claimed AA rank alone is not evidence.
func EvaluateCandidate(c Candidate, verifiedQuota bool, verifiedTop4 bool) Eligibility {
	x := Eligibility{CandidateID: c.ID, Classification: "GREY_ZONE", Reason: "unverified evidence"}
	if c.Taiwan == "NO" {
		x.Classification = "REJECTED"
		x.Reason = "Taiwan access unavailable"
		return x
	}
	if c.Taiwan != "YES" {
		x.Reason = "Taiwan accessibility unknown"
		return x
	}
	if c.Strength != "STRONG" {
		x.Reason = "strength unverified"
		return x
	}
	if c.Recurring != "DAILY_RESET" && c.Recurring != "ROLLING_24H" && c.Recurring != "WEEKLY" && c.Recurring != "MONTHLY" {
		x.Reason = "recurring-free mechanism unverified"
		return x
	}
	if !verifiedQuota {
		x.Reason = "independent recurring quota evidence missing"
		return x
	}
	if verifiedTop4 || (c.RPD != nil && *c.RPD >= 100 && c.TPD != nil && *c.TPD >= 1000000) {
		x.Classification = "ELIGIBLE"
		x.Reason = "verified recurring quota and accessibility"
		return x
	}
	x.Reason = "RPD >= 100 AND TPD >= 1000000 not both verified"
	return x
}
func (s *Server) eligibility(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id FROM research_candidates ORDER BY updated_at DESC LIMIT 1000")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	ids := []string{}
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	out := []Eligibility{}
	for _, id := range ids {
		c, e := s.candidate(r.Context(), id)
		if e == nil {
			out = append(out, s.candidateEligibility(r.Context(), c))
		}
	}
	jsonOK(w, 200, out)
}

func (s *Server) listResearchRuns(w http.ResponseWriter, r *http.Request) {
	owner := r.URL.Query().Get("schedule_id")
	id := r.URL.Query().Get("id")
	if owner != "" && !validID(owner) || id != "" && !validID(id) {
		http.Error(w, "invalid research lookup", 400)
		return
	}
	query := "SELECT r.id,r.status,r.checkpoint,r.started_at,o.budget,o.used,o.probe_budget,o.probes_used,COALESCE(o.summary_json,'{}'),COALESCE(p.schedule_id,''),COALESCE(s.name,'') FROM research_runs r LEFT JOIN research_run_options o ON o.run_id=r.id LEFT JOIN research_schedule_runs p ON p.run_id=r.id LEFT JOIN research_schedules s ON s.id=p.schedule_id WHERE (?='' OR p.schedule_id=?)"
	args := []any{owner, owner}
	if id != "" {
		query += " AND r.id=?"
		args = append(args, id)
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), query+" ORDER BY r.started_at DESC,r.rowid DESC LIMIT 100", args...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []ResearchRun{}
	for rows.Next() {
		var v ResearchRun
		var budget, used, probes, probeUsed sql.NullInt64
		var raw string
		if rows.Scan(&v.ID, &v.Status, &v.Checkpoint, &v.StartedAt, &budget, &used, &probes, &probeUsed, &raw, &v.ScheduleID, &v.ScheduleName) == nil {
			if budget.Valid {
				v.Budget = &budget.Int64
			}
			if used.Valid {
				v.Used = &used.Int64
			}
			if probes.Valid {
				v.ProbeBudget = &probes.Int64
			}
			if probeUsed.Valid {
				v.ProbesUsed = &probeUsed.Int64
			}
			json.Unmarshal([]byte(raw), &v.Summary)
			out = append(out, v)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) addResearchRun(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Checkpoint string `json:"checkpoint"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if len(v.Checkpoint) > 1024 {
		http.Error(w, "checkpoint too long", 400)
		return
	}
	x := ResearchRun{ID: newID(), Status: "CREATED", Checkpoint: v.Checkpoint, StartedAt: now()}
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO research_runs(id,status,checkpoint,started_at) VALUES(?,?,?,?)", x.ID, x.Status, x.Checkpoint, x.StartedAt)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, x)
}

// Evidence from a real scoped Provider /models call verifies only catalog
// existence. It is NOT evidence of a recurring-free quota or Taiwan access.
func (s *Server) probeCandidate(w http.ResponseWriter, r *http.Request) {
	var v struct {
		CandidateID  string `json:"candidate_id"`
		AccountID    string `json:"account_id"`
		CredentialID string `json:"credential_id"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	var provider, model string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT provider_id,model_id FROM research_candidates WHERE id=?", v.CandidateID).Scan(&provider, &model) != nil {
		http.Error(w, "candidate missing", 404)
		return
	}
	p, key, err := s.endpointAuth(r.Context(), provider, v.AccountID, v.CredentialID)
	if err != nil {
		http.Error(w, "credential scope invalid", 422)
		return
	}
	cfg := s.config(r.Context(), p)
	url, err := endpointURL(p, cfg, "MODELS", "")
	if err != nil {
		http.Error(w, "catalog endpoint unavailable", 422)
		return
	}
	adapter := adapterRegistry[cfg.Adapter]
	catalog, err := adapter.Discover(s, r.Context(), p, cfg, key)
	if err != nil {
		http.Error(w, "provider catalog unavailable", 502)
		return
	}
	found := false
	for _, m := range catalog {
		if m.ID == model {
			found = true
			break
		}
	}
	if !found {
		jsonOK(w, 200, map[string]any{"found": false, "quota_verified": false})
		return
	}
	_, err = s.Store.DB.ExecContext(context.Background(), "INSERT INTO research_evidence(id,candidate_id,source_type,source_url,claim,verified,observed_at) VALUES(?,?,'PROVIDER_MODEL_CATALOG',?,'model exists in catalog',1,?)", newID(), v.CandidateID, url, now())
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	_, _ = s.Store.DB.ExecContext(context.Background(), "UPDATE research_candidates SET state='VERIFYING',updated_at=? WHERE id=?", now(), v.CandidateID)
	jsonOK(w, 200, map[string]any{"found": true, "quota_verified": false})
}
