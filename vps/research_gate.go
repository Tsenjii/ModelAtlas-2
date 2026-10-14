package main

import (
	"context"
	"database/sql"
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
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT c.id,c.provider_id,c.model_id,c.rpd,c.tpd,c.taiwan,c.recurring,c.strength,c.state,EXISTS(SELECT 1 FROM research_evidence e WHERE e.candidate_id=c.id AND e.verified=1 AND e.source_type='OFFICIAL_RECURRING_QUOTA') FROM research_candidates c ORDER BY c.updated_at DESC LIMIT 300")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Eligibility{}
	for rows.Next() {
		var c Candidate
		var rp, tp sql.NullInt64
		var evidence int
		if rows.Scan(&c.ID, &c.ProviderID, &c.ModelID, &rp, &tp, &c.Taiwan, &c.Recurring, &c.Strength, &c.State, &evidence) != nil {
			continue
		}
		if rp.Valid {
			v := rp.Int64
			c.RPD = &v
		}
		if tp.Valid {
			v := tp.Int64
			c.TPD = &v
		}
		// No trusted AA ingestion means there is no legitimate current top-4 exception.
		out = append(out, EvaluateCandidate(c, evidence == 1, false))
	}
	jsonOK(w, 200, out)
}
func (s *Server) listResearchRuns(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,status,checkpoint,started_at FROM research_runs ORDER BY started_at DESC LIMIT 100")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []ResearchRun{}
	for rows.Next() {
		var v ResearchRun
		if rows.Scan(&v.ID, &v.Status, &v.Checkpoint, &v.StartedAt) == nil {
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
