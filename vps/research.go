package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

type Candidate struct {
	CandidateDetails
	ID         string `json:"id"`
	ProviderID string `json:"provider_id"`
	ModelID    string `json:"model_id"`
	RPD        *int64 `json:"rpd"`
	TPD        *int64 `json:"tpd"`
	Taiwan     string `json:"taiwan"`
	Recurring  string `json:"recurring"`
	Strength   string `json:"strength"`
	State      string `json:"state"`
}
type Evidence struct {
	EvidenceDetails
	ID          string `json:"id"`
	CandidateID string `json:"candidate_id"`
	SourceType  string `json:"source_type"`
	SourceURL   string `json:"source_url"`
	Claim       string `json:"claim"`
	Verified    bool   `json:"verified"`
	ObservedAt  int64  `json:"observed_at"`
}
type ResearchRun struct {
	QueryComparison bool           `json:"query_comparison,omitempty"`
	ScheduleID      string         `json:"schedule_id,omitempty"`
	ScheduleName    string         `json:"schedule_name,omitempty"`
	ID              string         `json:"id"`
	Status          string         `json:"status"`
	Checkpoint      string         `json:"checkpoint"`
	StartedAt       int64          `json:"started_at"`
	Budget          *int64         `json:"budget"`
	Used            *int64         `json:"used"`
	ProbeBudget     *int64         `json:"probe_budget"`
	ProbesUsed      *int64         `json:"probes_used"`
	Summary         map[string]any `json:"summary"`
}

func (s *Server) listCandidates(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT c.id,c.provider_id,c.model_id,c.rpd,c.tpd,c.taiwan,c.recurring,c.strength,c.state,COALESCE(d.details_json,'{}') FROM research_candidates c LEFT JOIN research_candidate_details d ON d.candidate_id=c.id ORDER BY c.updated_at DESC LIMIT 1000")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Candidate{}
	for rows.Next() {
		var x Candidate
		var rp, tp sql.NullInt64
		var raw string
		if rows.Scan(&x.ID, &x.ProviderID, &x.ModelID, &rp, &tp, &x.Taiwan, &x.Recurring, &x.Strength, &x.State, &raw) == nil {
			_ = json.Unmarshal([]byte(raw), &x.CandidateDetails)
			if rp.Valid {
				v := rp.Int64
				x.RPD = &v
			}
			if tp.Valid {
				v := tp.Int64
				x.TPD = &v
			}
			out = append(out, x)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) addCandidate(w http.ResponseWriter, r *http.Request) {
	var x Candidate
	if !decodeJSON(w, r, &x) {
		return
	}
	if !validID(x.ProviderID) || !validID(x.ModelID) || x.Website != "" && !publicURL(x.Website) || x.BaseURL != "" && !validateHTTPS(x.BaseURL) || len(x.Restrictions) > 16 || len(x.Reason) > 1000 {
		http.Error(w, "invalid candidate", 400)
		return
	}
	if (x.RPD != nil && *x.RPD < 0) || (x.TPD != nil && *x.TPD < 0) {
		http.Error(w, "invalid quota", 400)
		return
	}
	if x.Taiwan == "" {
		x.Taiwan = "UNKNOWN"
	}
	if x.Recurring == "" {
		x.Recurring = "UNKNOWN"
	}
	if x.Strength == "" {
		x.Strength = "UNKNOWN"
	}
	if x.Taiwan != "YES" && x.Taiwan != "NO" && x.Taiwan != "UNKNOWN" {
		http.Error(w, "invalid Taiwan value", 400)
		return
	}
	if x.Recurring != "UNKNOWN" && x.Recurring != "DAILY_RESET" && x.Recurring != "ROLLING_24H" && x.Recurring != "WEEKLY" && x.Recurring != "MONTHLY" && x.Recurring != "ONE_TIME_TRIAL" && x.Recurring != "PUBLIC_POOL" {
		http.Error(w, "invalid recurrence", 400)
		return
	}
	if x.Strength != "UNKNOWN" && x.Strength != "STRONG" && x.Strength != "WEAK" {
		http.Error(w, "invalid strength", 400)
		return
	}
	x.Confidence = 0
	x.CreatedRun = ""
	x.LastChecked = 0
	x.EligibilityValidUntil = 0
	x.ID = newID()
	x.State = "DISCOVERED"
	var rp, tp any
	if x.RPD != nil {
		rp = *x.RPD
	}
	if x.TPD != nil {
		tp = *x.TPD
	}
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO research_candidates(id,provider_id,model_id,rpd,tpd,taiwan,recurring,strength,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)", x.ID, x.ProviderID, x.ModelID, rp, tp, x.Taiwan, x.Recurring, x.Strength, x.State, now(), now())
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	_ = s.saveCandidateDetails(r.Context(), x.ID, x.CandidateDetails)
	jsonOK(w, 201, x)
}
func (s *Server) listEvidence(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("candidate_id")
	if id != "" && !validID(id) {
		http.Error(w, "invalid candidate id", 400)
		return
	}
	q := "SELECT e.id,e.candidate_id,e.source_type,e.source_url,e.claim,e.verified,e.observed_at,COALESCE(d.details_json,'{}') FROM research_evidence e LEFT JOIN research_evidence_details d ON d.evidence_id=e.id"
	var args []any
	if id != "" {
		q += " WHERE e.candidate_id=?"
		args = append(args, id)
	}
	q += " ORDER BY e.observed_at DESC LIMIT 500"
	rows, err := s.Store.DB.QueryContext(r.Context(), q, args...)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Evidence{}
	for rows.Next() {
		var x Evidence
		var b int
		var raw string
		if rows.Scan(&x.ID, &x.CandidateID, &x.SourceType, &x.SourceURL, &x.Claim, &b, &x.ObservedAt, &raw) == nil {
			json.Unmarshal([]byte(raw), &x.EvidenceDetails)
			x.Verified = b == 1
			out = append(out, x)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) addEvidence(w http.ResponseWriter, r *http.Request) {
	var e Evidence
	if !decodeJSON(w, r, &e) {
		return
	}
	if !validID(e.CandidateID) || len(e.Claim) < 1 || len(e.Claim) > 4000 || !validateHTTPS(e.SourceURL) || len(e.SourceType) > 64 {
		http.Error(w, "invalid evidence", 400)
		return
	}
	if e.SourceType == "" {
		e.SourceType = "MANUAL"
	}
	e.ID = newID()
	e.Verified = false
	e.ObservedAt = now()
	_, err := s.Store.DB.ExecContext(r.Context(), "INSERT INTO research_evidence(id,candidate_id,source_type,source_url,claim,verified,observed_at) VALUES(?,?,?,?,?,0,?)", e.ID, e.CandidateID, e.SourceType, e.SourceURL, e.Claim, e.ObservedAt)
	if err != nil {
		http.Error(w, "candidate missing or database error", 422)
		return
	}
	jsonOK(w, 201, e)
}
