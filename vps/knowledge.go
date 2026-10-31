package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
)

type KnowledgeVersion struct {
	QueryGenerationUnavailable bool                  `json:"query_generation_unavailable,omitempty"`
	QueryGeneration            *queryGenerationProof `json:"query_generation,omitempty"`
	ID                         string                `json:"id"`
	Kind                       string                `json:"kind"`
	Subject                    string                `json:"subject"`
	Version                    int                   `json:"version"`
	Data                       map[string]any        `json:"data"`
	EvidenceIDs                []string              `json:"evidence_ids"`
	Reason                     string                `json:"reason"`
	Status                     string                `json:"status"`
	RunID                      string                `json:"run_id,omitempty"`
	Active                     bool                  `json:"active"`
	ActiveID                   string                `json:"active_id"`
	CreatedAt                  int64                 `json:"created_at"`
	Evaluation                 *knowledgeEvaluation  `json:"evaluation,omitempty"`
	Monitor                    *knowledgeMonitor     `json:"monitor,omitempty"`
	ActivationActionID         string                `json:"activation_action_id,omitempty"`
	ActiveEpoch                string                `json:"active_epoch,omitempty"`
}

var knowledgeFields = map[string]string{
	"SOURCE_RELIABILITY":   "source_id retrieval_success retrieval_failure attempts evidence_count rejected_trials freshness_days weight confidence scope_hash",
	"RESEARCH_STRATEGY":    "language query success failure attempts hits api_ready cost_http priority weight confidence scope_hash search_country search_pages query_generation_id",
	"ENDPOINT_PATTERN":     "provider_id path operation observed_at source_url confidence",
	"AUTH_PATTERN":         "provider_id auth_kind source_url observed_at confidence",
	"DISCOVERY_PATH":       "source_id source_url path operation status evidence_count confidence",
	"MODEL_ALIAS_RULE":     "provider_id raw_id canonical_id confidence",
	"VERIFICATION_OUTCOME": "reused_existing new_requests observations identity_proven confidence",
	"PROVIDER_RECIPE":      "provider_id settings source_url confidence",
}

func validKnowledge(v KnowledgeVersion) bool {
	allowed, ok := knowledgeFields[v.Kind]
	if !ok || safeText(v.Subject, 240) != v.Subject || v.Subject == "" || safeText(v.Reason, 1000) != v.Reason || v.Reason == "" || len(v.Data) < 1 || len(v.Data) > 16 || len(v.EvidenceIDs) < 1 || len(v.EvidenceIDs) > 20 {
		return false
	}
	for k, x := range v.Data {
		if !oneOf(k, strings.Fields(allowed)...) {
			return false
		}
		if k == "settings" {
			settings, ok := x.(map[string]any)
			if !ok || !safeRecipe(settings) {
				return false
			}
			continue
		}
		switch value := x.(type) {
		case string:
			limit := 1000
			if oneOf(k, "source_url", "path") {
				limit = 2048
			}
			if safeText(value, limit) != value {
				return false
			}
		case bool:
		case json.Number:
			if decimal(value) == nil || decimal(value).Sign() < 0 {
				return false
			}
		case int:
			if value < 0 {
				return false
			}
		case float64:
			if value < 0 || value > 1e12 {
				return false
			}
		default:
			return false
		}
		if k == "weight" {
			n := decimal(json.Number(fmt.Sprint(x)))
			if n == nil || n.Cmp(decimal("0.25")) < 0 || n.Cmp(decimal("4")) > 0 {
				return false
			}
		}
		if k == "priority" {
			n := quotaInteger(json.Number(fmt.Sprint(x)))
			if n == nil || *n < 1 || *n > 8 {
				return false
			}
		}
		if k == "confidence" && !oneOf(str(x), "TENTATIVE", "EVIDENCE_BACKED") {
			return false
		}
		if k == "scope_hash" && (len(str(x)) != 64 || !validID(str(x))) {
			return false
		}
		if k == "search_country" && !validBraveCountry(str(x)) {
			return false
		}
		if k == "search_pages" {
			n := quotaInteger(json.Number(fmt.Sprint(x)))
			if n == nil || *n < 1 || *n > 10 {
				return false
			}
		}
		if k == "language" && !researchLanguage(str(x)) {
			return false
		}
		if k == "source_url" && !publicURL(str(x)) && !validateHTTPS(str(x)) {
			return false
		}
		if oneOf(k, "raw_id", "canonical_id", "provider_id", "source_id", "query_generation_id") && !validID(str(x)) {
			return false
		}
		if k == "query" && (str(x) == "" || len(str(x)) > 300) {
			return false
		}
	}
	for _, id := range v.EvidenceIDs {
		if !validID(id) {
			return false
		}
	}
	return true
}
func appendKnowledge(ctx context.Context, tx *sql.Tx, v KnowledgeVersion) (KnowledgeVersion, error) {
	if !validKnowledge(v) {
		return v, errors.New("invalid declarative knowledge")
	}
	if e := tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0)+1 FROM knowledge_versions WHERE kind=? AND subject=?", v.Kind, v.Subject).Scan(&v.Version); e != nil {
		return v, e
	}
	v.ID = newID()
	v.Status = "TENTATIVE"
	v.CreatedAt = now()
	data, _ := json.Marshal(v.Data)
	evidence, _ := json.Marshal(v.EvidenceIDs)
	_, e := tx.ExecContext(ctx, "INSERT INTO knowledge_versions VALUES(?,?,?,?,?,?,?,?,?,?)", v.ID, v.Kind, v.Subject, v.Version, string(data), string(evidence), v.Reason, v.Status, v.RunID, v.CreatedAt)
	return v, e
}
func (s *Server) proposeKnowledge(w http.ResponseWriter, r *http.Request) {
	var v KnowledgeVersion
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.QueryGenerationUnavailable || v.QueryGeneration != nil || v.Evaluation != nil || v.Monitor != nil || v.ActivationActionID != "" || v.ActiveEpoch != "" {
		http.Error(w, "derived evaluation fields are read only", 422)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, key := range secrets {
		if jsonContainsSecret(v, key) {
			http.Error(w, "use safe references", 422)
			return
		}
	}
	if !validKnowledge(v) {
		http.Error(w, "invalid knowledge fields", 422)
		return
	}
	for _, id := range v.EvidenceIDs {
		var count int
		s.Store.DB.QueryRowContext(r.Context(), "SELECT (SELECT COUNT(*) FROM research_evidence WHERE id=?)+(SELECT COUNT(*) FROM research_reflections WHERE id=?)+(SELECT COUNT(*) FROM connector_versions WHERE id=? AND status='VALIDATED')", id, id, id).Scan(&count)
		if count < 1 {
			http.Error(w, "saved evidence required", 422)
			return
		}
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	proof, e := prepareQueryRevision(r.Context(), tx, &v)
	if e != nil {
		http.Error(w, "a local generated parent with unchanged context is required", 422)
		return
	}
	v, e = appendKnowledge(r.Context(), tx, v)
	if e == nil && proof != nil {
		e = saveQueryGeneration(r.Context(), tx, proof, v, snapshotHash(map[string]any{"parent": proof.ParentID, "query": proof.Query}), snapshotHash(map[string]any{"revision": proof.ID}))
		v.QueryGeneration = proof
	}
	if e != nil || tx.Commit() != nil {
		http.Error(w, "knowledge persistence failed", 500)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) listKnowledge(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT v.id,v.kind,v.subject,v.version,v.data_json,v.evidence_json,v.reason,v.status,v.run_id,v.created_at,COALESCE(a.version_id,''),COALESCE(e.epoch_id,'') FROM knowledge_versions v LEFT JOIN knowledge_active a ON a.kind=v.kind AND a.subject=v.subject LEFT JOIN knowledge_activation_epochs e ON e.kind=a.kind AND e.subject=a.subject WHERE (?='' OR v.kind=?) ORDER BY v.rowid DESC LIMIT 200", r.URL.Query().Get("kind"), r.URL.Query().Get("kind"))
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []KnowledgeVersion{}
	for rows.Next() {
		var v KnowledgeVersion
		var data, evidence string
		if rows.Scan(&v.ID, &v.Kind, &v.Subject, &v.Version, &data, &evidence, &v.Reason, &v.Status, &v.RunID, &v.CreatedAt, &v.ActiveID, &v.ActiveEpoch) == nil {
			json.Unmarshal([]byte(data), &v.Data)
			json.Unmarshal([]byte(evidence), &v.EvidenceIDs)
			v.Active = v.ID == v.ActiveID
			out = append(out, v)
		}
	}
	if e = rows.Err(); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	rows.Close()
	if e = s.enrichKnowledge(r.Context(), out); e != nil {
		http.Error(w, "evaluation history unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}

type versionActivation struct {
	ID           string `json:"id"`
	ExpectedID   string `json:"expected_active_id"`
	Confirm      bool   `json:"confirm"`
	EvaluationID string `json:"evaluation_id,omitempty"`
}

func (s *Server) activateKnowledge(w http.ResponseWriter, r *http.Request) {
	var v versionActivation
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm {
		http.Error(w, "explicit confirmation required", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var k KnowledgeVersion
	var raw string
	if tx.QueryRowContext(r.Context(), "SELECT kind,subject,data_json,status FROM knowledge_versions WHERE id=?", v.ID).Scan(&k.Kind, &k.Subject, &raw, &k.Status) != nil {
		http.Error(w, "version missing", 404)
		return
	}
	json.Unmarshal([]byte(raw), &k.Data)
	if k.Status == "IMPORTED" {
		http.Error(w, "imported knowledge requires a new evidence-backed proposal before activation", 422)
		return
	}
	k.ID = v.ID
	if oneOf(k.Kind, "SOURCE_RELIABILITY", "RESEARCH_STRATEGY") && (str(k.Data["scope_hash"]) != "" || str(k.Data["query_generation_id"]) != "" || v.EvaluationID != "") {
		if e = checkKnowledgeEvaluation(r.Context(), tx, k, v.EvaluationID); e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
	}
	if k.Kind == "MODEL_ALIAS_RULE" {
		var count int
		if k.Subject != str(k.Data["provider_id"])+"/"+str(k.Data["raw_id"]) || tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM models WHERE provider_id=? AND id=?", str(k.Data["provider_id"]), str(k.Data["raw_id"])).Scan(&count) != nil || count != 1 {
			http.Error(w, "alias must bind an exact existing model", 422)
			return
		}
	}
	if k.Kind == "PROVIDER_RECIPE" {
		settings, _ := k.Data["settings"].(map[string]any)
		for field := range settings {
			if !oneOf(field, "notes", "supports_tools", "supports_vision", "sync_enabled") {
				http.Error(w, "endpoint changes require a tested connector or reviewed endpoint profile", 422)
				return
			}
		}
	}
	id, e := applyVersion(r.Context(), tx, "KNOWLEDGE", k.Kind, k.Subject, v.ID, v.ExpectedID)
	if e != nil {
		http.Error(w, e.Error(), 409)
		return
	}
	if v.EvaluationID != "" && oneOf(k.Kind, "SOURCE_RELIABILITY", "RESEARCH_STRATEGY") {
		if _, e = tx.ExecContext(r.Context(), "INSERT INTO knowledge_activation_evaluations VALUES(?,?)", id, v.EvaluationID); e != nil {
			http.Error(w, "evaluation link failed", 500)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "active": true, "action_id": id, "security_policy_changed": false})
}
func applyVersion(ctx context.Context, tx *sql.Tx, family, kind, subject, id, expected string) (string, error) {
	before := activeVersion(ctx, tx, family, kind, subject)
	if before != expected || before == id {
		return "", errors.New("active version changed; refresh before applying")
	}
	if e := setActiveVersion(ctx, tx, family, kind, subject, id); e != nil {
		return "", e
	}
	action := newID()
	b, _ := json.Marshal(map[string]string{"version_id": before})
	a, _ := json.Marshal(map[string]string{"version_id": id})
	if _, e := tx.ExecContext(ctx, "INSERT INTO action_ledger VALUES(?,?,?,?,?,'CONFIRMED',?)", action, "ACTIVATE_"+family, kind+"/"+subject, string(b), string(a), now()); e != nil {
		return "", e
	}
	_, e := tx.ExecContext(ctx, "INSERT INTO version_action_details VALUES(?,?,?,?,?,?)", action, family, kind, subject, before, id)
	return action, e
}
func (s *Server) undoVersion(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID      string `json:"id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm {
		http.Error(w, "explicit confirmation required", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var family, kind, subject, before, after, state string
	if tx.QueryRowContext(r.Context(), "SELECT d.family,d.kind,d.subject,d.before_id,d.after_id,a.state FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id WHERE d.action_id=?", v.ID).Scan(&family, &kind, &subject, &before, &after, &state) != nil {
		http.Error(w, "version action missing", 404)
		return
	}
	if state != "CONFIRMED" || activeVersion(r.Context(), tx, family, kind, subject) != after {
		http.Error(w, "refuse stale version Undo", 409)
		return
	}
	if family == "RECIPE" {
		if e = undoSiteRecipeConnectors(r.Context(), tx, v.ID); e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
	}
	if e = setActiveVersion(r.Context(), tx, family, kind, subject, before); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE action_ledger SET state='UNDONE' WHERE id=?", v.ID); e != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "state": "UNDONE", "history_preserved": true})
}
func (s *Server) activeKnowledge(ctx context.Context, kind, subject string) map[string]any {
	var raw string
	s.Store.DB.QueryRowContext(ctx, "SELECT v.data_json FROM knowledge_versions v JOIN knowledge_active a ON a.version_id=v.id WHERE a.kind=? AND a.subject=?", kind, subject).Scan(&raw)
	var data map[string]any
	json.Unmarshal([]byte(raw), &data)
	return data
}
