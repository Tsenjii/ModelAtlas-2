package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type CandidateDetails struct {
	Website               string   `json:"website,omitempty"`
	BaseURL               string   `json:"base_url,omitempty"`
	AccessMethod          string   `json:"access_method,omitempty"`
	AuthKind              string   `json:"auth_kind,omitempty"`
	Restrictions          []string `json:"taiwan_restrictions,omitempty"`
	Reason                string   `json:"reason,omitempty"`
	Confidence            float64  `json:"confidence"`
	LastChecked           int64    `json:"last_checked,omitempty"`
	SourceID              string   `json:"source_id,omitempty"`
	Language              string   `json:"language,omitempty"`
	CreatedRun            string   `json:"created_run,omitempty"`
	EligibilityValidUntil int64    `json:"eligibility_valid_until,omitempty"`
}
type EvidenceDetails struct {
	Normalized    map[string]any `json:"normalized,omitempty"`
	Confidence    float64        `json:"confidence"`
	RunID         string         `json:"run_id,omitempty"`
	SourceID      string         `json:"source_id,omitempty"`
	Language      string         `json:"language,omitempty"`
	ContentHash   string         `json:"content_hash,omitempty"`
	ParserVersion string         `json:"parser_version,omitempty"`
}
type ResearchSource struct {
	ID       string       `json:"id"`
	Name     string       `json:"name"`
	Kind     string       `json:"kind"`
	URL      string       `json:"url"`
	Language string       `json:"language"`
	Enabled  bool         `json:"enabled"`
	Config   SourceConfig `json:"config"`
}
type SourceConfig struct {
	ProviderID   string            `json:"provider_id,omitempty"`
	ItemsPointer string            `json:"items_pointer,omitempty"`
	Fields       map[string]string `json:"fields,omitempty"`
	Official     bool              `json:"official"`
}

func validSource(v ResearchSource) bool {
	if !validID(v.ID) || v.Name == "" || safeText(v.Name, 120) != v.Name || !oneOf(v.Kind, "JSON", "HTML") || !publicURL(v.URL) || !researchLanguage(v.Language) || len(v.Config.Fields) > 20 {
		return false
	}
	if v.Config.ProviderID != "" && !validID(v.Config.ProviderID) {
		return false
	}
	if !validPointer(v.Config.ItemsPointer) {
		return false
	}
	for name, path := range v.Config.Fields {
		if !oneOf(name, "model_id", "rpd", "tpd", "taiwan", "recurring", "free", "base_url", "access_method", "auth_kind", "strength", "restrictions") || !validPointer(path) {
			return false
		}
	}
	return true
}
func researchLanguage(v string) bool { return oneOf(v, "en", "zh", "ru", "ja", "ko", "es", "pt", "id") }
func validPointer(v string) bool {
	return len(v) <= 256 && (v == "" || strings.HasPrefix(v, "/")) && len(strings.Split(v, "/")) <= 10 && !strings.ContainsAny(v, "\x00\r\n")
}
func pointer(value any, path string) any {
	if path == "" {
		return value
	}
	for _, part := range strings.Split(strings.TrimPrefix(path, "/"), "/") {
		part = strings.ReplaceAll(strings.ReplaceAll(part, "~1", "/"), "~0", "~")
		m, ok := value.(map[string]any)
		if ok {
			value = m[part]
			continue
		}
		array, ok := value.([]any)
		index, e := strconv.Atoi(part)
		if !ok || e != nil || index < 0 || index >= len(array) || strconv.Itoa(index) != part {
			return nil
		}
		value = array[index]
	}
	return value
}
func (s *Server) saveCandidateDetails(ctx context.Context, id string, v CandidateDetails) error {
	raw, _ := json.Marshal(v)
	_, err := s.Store.DB.ExecContext(ctx, "INSERT INTO research_candidate_details VALUES(?,?) ON CONFLICT(candidate_id) DO UPDATE SET details_json=excluded.details_json", id, string(raw))
	return err
}
func (s *Server) candidate(ctx context.Context, id string) (Candidate, error) {
	var c Candidate
	var rpd, tpd sql.NullInt64
	var raw string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT c.id,c.provider_id,c.model_id,c.rpd,c.tpd,c.taiwan,c.recurring,c.strength,c.state,COALESCE(d.details_json,'{}') FROM research_candidates c LEFT JOIN research_candidate_details d ON d.candidate_id=c.id WHERE c.id=?", id).Scan(&c.ID, &c.ProviderID, &c.ModelID, &rpd, &tpd, &c.Taiwan, &c.Recurring, &c.Strength, &c.State, &raw)
	if rpd.Valid {
		c.RPD = &rpd.Int64
	}
	if tpd.Valid {
		c.TPD = &tpd.Int64
	}
	json.Unmarshal([]byte(raw), &c.CandidateDetails)
	return c, err
}
func (s *Server) recordEvidence(ctx context.Context, candidate, kind, source, claim string, verified bool, details EvidenceDetails) (string, error) {
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return "", e
	}
	defer tx.Rollback()
	id, e := recordResearchEvidenceTx(ctx, tx, candidate, kind, source, claim, verified, details)
	if e != nil {
		return "", e
	}
	return id, tx.Commit()
}

// A discovery page and all of its evidence/checkpoint commit together.
func recordResearchEvidenceTx(ctx context.Context, tx *sql.Tx, candidate, kind, source, claim string, verified bool, details EvidenceDetails) (string, error) {
	if safeText(claim, 4000) != claim || (!validateHTTPS(source) && !publicURL(source)) {
		return "", errors.New("unsafe evidence")
	}
	id := newID()
	raw, _ := json.Marshal(details)
	var e error
	if _, e = tx.ExecContext(ctx, "INSERT INTO research_evidence VALUES(?,?,?,?,?,?,?)", id, candidate, kind, source, claim, verified, now()); e != nil {
		return "", e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO research_evidence_details VALUES(?,?)", id, string(raw)); e != nil {
		return "", e
	}
	if task, _ := ctx.Value(researchTaskBudgetKey{}).(string); task != "" {
		var model, metadata string
		if e = tx.QueryRowContext(ctx, "SELECT c.model_id,COALESCE(d.details_json,'{}') FROM research_candidates c LEFT JOIN research_candidate_details d ON d.candidate_id=c.id WHERE c.id=?", candidate).Scan(&model, &metadata); e != nil {
			return "", e
		}
		var c CandidateDetails
		json.Unmarshal([]byte(metadata), &c)
		receipt, _ := json.Marshal(qualityEvidenceRecord{CandidateID: candidate, Kind: kind, Verified: verified, SourceID: details.SourceID, Language: details.Language, ModelID: model, CreatedRun: c.CreatedRun, Facts: details.Normalized})
		if _, e = tx.ExecContext(ctx, "INSERT INTO research_local_evidence VALUES(?,?,?,?)", id, details.RunID, task, string(receipt)); e != nil {
			return "", e
		}
	}
	return id, nil
}
func (s *Server) saveResearchSource(w http.ResponseWriter, r *http.Request) {
	var v ResearchSource
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.ID == "" {
		v.ID = newID()
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	rawSource, _ := json.Marshal(v)
	if rawSecretPattern.Match(rawSource) {
		http.Error(w, "secrets cannot be stored in public source definitions", 422)
		return
	}
	var decoded any
	_ = json.Unmarshal(rawSource, &decoded)
	for _, secret := range secrets {
		if jsonContainsSecret(decoded, secret) {
			http.Error(w, "secrets cannot be stored in public source definitions", 422)
			return
		}
	}
	if !validSource(v) {
		http.Error(w, "invalid research source", 400)
		return
	}
	if v.Config.Official {
		p, e := s.provider(r.Context(), v.Config.ProviderID)
		if e != nil {
			http.Error(w, "official source must bind a known provider", 422)
			return
		}
		base, _ := url.Parse(p.BaseURL)
		target, _ := url.Parse(v.URL)
		cfg := s.config(r.Context(), p)
		site, _ := url.Parse(cfg.Website)
		if !strings.EqualFold(base.Host, target.Host) && (site == nil || !strings.EqualFold(site.Host, target.Host)) {
			http.Error(w, "official source origin mismatch", 422)
			return
		}
	}
	raw, _ := json.Marshal(v.Config)
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if _, e := tx.ExecContext(r.Context(), "INSERT INTO research_sources VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,kind=excluded.kind,url=excluded.url,language=excluded.language,enabled=excluded.enabled,config_json=excluded.config_json", v.ID, v.Name, v.Kind, v.URL, v.Language, v.Enabled, string(raw), now()); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	var version int
	tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM research_source_versions WHERE source_id=?", v.ID).Scan(&version)
	full, _ := json.Marshal(v)
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO research_source_versions VALUES(?,?,?,?)", v.ID, version, string(full), now()); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	s.wakeWorker()
	jsonOK(w, 200, v)
}
func (s *Server) source(ctx context.Context, id string) (ResearchSource, error) {
	var v ResearchSource
	var raw string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT id,name,kind,url,language,enabled,config_json FROM research_sources WHERE id=?", id).Scan(&v.ID, &v.Name, &v.Kind, &v.URL, &v.Language, &v.Enabled, &raw)
	json.Unmarshal([]byte(raw), &v.Config)
	return v, e
}
func (s *Server) listResearchSources(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,name,kind,url,language,enabled,config_json FROM research_sources ORDER BY rowid DESC LIMIT 500")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []ResearchSource{}
	for rows.Next() {
		var v ResearchSource
		var raw string
		if rows.Scan(&v.ID, &v.Name, &v.Kind, &v.URL, &v.Language, &v.Enabled, &raw) == nil {
			json.Unmarshal([]byte(raw), &v.Config)
			out = append(out, v)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) reviewCandidate(w http.ResponseWriter, r *http.Request) {
	var v struct {
		CandidateID  string   `json:"candidate_id"`
		Taiwan       string   `json:"taiwan"`
		Strength     string   `json:"strength"`
		AccessMethod string   `json:"access_method"`
		Reason       string   `json:"reason"`
		EvidenceIDs  []string `json:"evidence_ids"`
		Confirm      bool     `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	c, e := s.candidate(r.Context(), v.CandidateID)
	if e != nil {
		http.Error(w, "candidate missing", 404)
		return
	}
	if !v.Confirm || !oneOf(v.Taiwan, "YES", "NO", "UNKNOWN") || !oneOf(v.Strength, "STRONG", "WEAK", "UNKNOWN") || !oneOf(v.AccessMethod, "INFERENCE_READY", "CODING_AGENT_ONLY", "WEB_ONLY", "CUSTOM", "UNKNOWN") || safeText(v.Reason, 1000) != v.Reason || v.Reason == "" || len(v.EvidenceIDs) < 1 || len(v.EvidenceIDs) > 20 {
		http.Error(w, "explicit evidence-backed review required", 422)
		return
	}
	sourceURL := ""
	for _, id := range v.EvidenceIDs {
		var source string
		if s.Store.DB.QueryRowContext(r.Context(), "SELECT source_url FROM research_evidence WHERE id=? AND candidate_id=?", id, c.ID).Scan(&source) != nil {
			http.Error(w, "evidence scope mismatch", 422)
			return
		}
		if sourceURL == "" {
			sourceURL = source
		}
	}
	c.Taiwan = v.Taiwan
	c.Strength = v.Strength
	c.AccessMethod = v.AccessMethod
	c.Reason = v.Reason
	c.LastChecked = now()
	c.Confidence = 0.6
	raw, _ := json.Marshal(map[string]any{"evidence_ids": v.EvidenceIDs, "taiwan": v.Taiwan, "strength": v.Strength, "access_method": v.AccessMethod, "reason": v.Reason})
	_, e = s.recordEvidence(r.Context(), c.ID, "MANUAL_REVIEW", sourceURL, string(raw), true, EvidenceDetails{Normalized: map[string]any{"taiwan": v.Taiwan, "strength": v.Strength, "access_method": v.AccessMethod}, Confidence: 0.6, ParserVersion: "manual-review-v1"})
	if e != nil {
		http.Error(w, "review persistence failed", 500)
		return
	}
	_, e = s.Store.DB.ExecContext(r.Context(), "UPDATE research_candidates SET taiwan=?,strength=?,updated_at=? WHERE id=?", c.Taiwan, c.Strength, now(), c.ID)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	s.saveCandidateDetails(r.Context(), c.ID, c.CandidateDetails)
	s.reconcileCandidate(r.Context(), c.ID)
	c, _ = s.candidate(r.Context(), c.ID)
	jsonOK(w, 200, c)
}
func (s *Server) researchFunnel(w http.ResponseWriter, r *http.Request) {
	out := map[string]int{}
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT state,COUNT(*) FROM research_candidates GROUP BY state")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	for rows.Next() {
		var key string
		var n int
		rows.Scan(&key, &n)
		out[key] = n
	}
	rows.Close()
	for _, state := range []string{"DISCOVERED", "VERIFYING", "VERIFIED", "HIGH_VALUE_GREY_ZONE", "STALE", "FAILED", "REJECTED"} {
		if _, ok := out[state]; !ok {
			out[state] = 0
		}
	}
	var providers int
	s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(DISTINCT provider_id) FROM research_candidates").Scan(&providers)
	jsonOK(w, 200, map[string]any{"states": out, "providers": providers})
}
