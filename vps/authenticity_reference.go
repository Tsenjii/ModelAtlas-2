package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type authenticityReferenceRequest struct {
	ID        string    `json:"id"`
	Target    Selection `json:"target"`
	Reference Selection `json:"reference"`
	Reason    string    `json:"reason"`
}
type authenticityReference struct {
	ID           string              `json:"id"`
	Suite        string              `json:"suite_version"`
	Version      int                 `json:"version"`
	At           int64               `json:"created_at"`
	RequestHash  string              `json:"request_hash"`
	Reason       string              `json:"reason"`
	Target       authenticityDataset `json:"target"`
	Reference    authenticityDataset `json:"reference"`
	ReviewStatus string              `json:"review_status"`
	ActiveID     string              `json:"active_id"`
	ActionID     string              `json:"activation_action_id,omitempty"`
	Comparison   authenticityReport  `json:"comparison"`
}

func readAuthenticityReference(ctx context.Context, q queryRows, id string) (authenticityReference, error) {
	var raw string
	var out authenticityReference
	if err := q.QueryRowContext(ctx, "SELECT definition_json FROM authenticity_reference_versions WHERE id=?", id).Scan(&raw); err != nil {
		return out, err
	}
	if json.Unmarshal([]byte(raw), &out) != nil || out.ID != id {
		return out, errors.New("reference definition unavailable")
	}
	return out, nil
}
func authenticityReferenceAction(ctx context.Context, q queryRows, scope, id string) string {
	var action string
	q.QueryRowContext(ctx, `SELECT a.id FROM action_ledger a JOIN version_action_details d ON d.action_id=a.id WHERE d.family='AUTHENTICITY' AND d.kind=? AND d.subject='reference' AND d.after_id=? AND a.state='CONFIRMED' ORDER BY a.created_at DESC,a.rowid DESC LIMIT 1`, scope, id).Scan(&action)
	return action
}
func (s *Server) currentAuthenticityReference(ctx context.Context, q queryRows, ref authenticityReference, at int64, secrets []string) (authenticityDataset, error) {
	var out authenticityDataset
	if ref.Suite != authenticitySuiteVersion || authenticityOriginEqual(ref.Target.Scope.Origin, ref.Reference.Scope.Origin) || ref.Target.Scope.Selection.ProviderID == ref.Reference.Scope.Selection.ProviderID || ref.Target.Scope.Canonical != ref.Reference.Scope.Canonical {
		return out, errors.New("參考版本、來源或Canonical不符合目前套件規則，請重新審閱。")
	}
	target, err := s.authenticityScope(ctx, q, ref.Target.Scope.Selection)
	if err != nil || target.ContextHash != ref.Target.Scope.ContextHash {
		return out, errors.New("目標的Key、來源、群組、端點或模型對應已變更，請建立新參考版本。")
	}
	reference, err := s.authenticityScope(ctx, q, ref.Reference.Scope.Selection)
	if err != nil || reference.ContextHash != ref.Reference.Scope.ContextHash {
		return out, errors.New("參考來源的Key、範圍或模型對應已變更，舊版本保留但不再用於對照。")
	}
	out, err = loadAuthenticityDataset(ctx, q, reference, at, authenticityDatasetIDs(ref.Reference), secrets)
	if err != nil {
		return out, err
	}
	if out.Hash != ref.Reference.Hash || !authenticityStrong(out) {
		return out, errors.New("參考樣本已逾七日、資料或本機憑據變更，或尚無足夠實際能力觀測。")
	}
	for _, v := range out.Samples {
		if v.ResponseName == "DIFFERENT_CLAIM" {
			return out, errors.New("參考來源仍有名稱聲明衝突，請先核對別名及來源。")
		}
	}
	return out, nil
}
func (s *Server) previewAuthenticityReferenceHTTP(w http.ResponseWriter, r *http.Request) {
	var v authenticityReferenceRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || !validScope(v.Target) || !validScope(v.Reference) || v.Reason == "" || safeText(v.Reason, 1000) != v.Reason {
		http.Error(w, "request ID, scopes and review reason required", 422)
		return
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if validateAuthenticitySecrets(v, secrets) != nil {
		http.Error(w, "raw secrets are not reference data", 422)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	hash := connectorHash(v)
	saved, err := readAuthenticityReference(r.Context(), tx, v.ID)
	if err == nil {
		if saved.RequestHash != hash {
			http.Error(w, "request ID already used for another reference", 409)
			return
		}
		saved.ActiveID = activeVersion(r.Context(), tx, "AUTHENTICITY", scopeKey(v.Target), "reference")
		saved.ActionID = authenticityReferenceAction(r.Context(), tx, scopeKey(v.Target), saved.ID)
		jsonOK(w, 200, saved)
		return
	}
	if err != sql.ErrNoRows {
		http.Error(w, "reference unavailable", 500)
		return
	}
	target, err := s.authenticityScope(r.Context(), tx, v.Target)
	if err != nil {
		http.Error(w, "target scope unavailable", 422)
		return
	}
	reference, err := s.authenticityScope(r.Context(), tx, v.Reference)
	if err != nil {
		http.Error(w, "reference scope unavailable", 422)
		return
	}
	if authenticityOriginEqual(target.Origin, reference.Origin) || v.Target.ProviderID == v.Reference.ProviderID || target.Canonical != reference.Canonical {
		http.Error(w, "another provider and origin with the same Canonical Model required", 422)
		return
	}
	if validateAuthenticitySecrets(target, secrets) != nil || validateAuthenticitySecrets(reference, secrets) != nil {
		http.Error(w, "raw secrets are not scope metadata", 422)
		return
	}
	at := now()
	targetData, err := loadAuthenticityDataset(r.Context(), tx, target, at, nil, secrets)
	if err != nil {
		http.Error(w, "target evidence unavailable", 500)
		return
	}
	referenceData, err := loadAuthenticityDataset(r.Context(), tx, reference, at, nil, secrets)
	if err != nil {
		http.Error(w, "reference evidence unavailable", 500)
		return
	}
	ref := authenticityReference{ID: v.ID, Suite: authenticitySuiteVersion, At: at, RequestHash: hash, Reason: v.Reason, Target: targetData, Reference: referenceData, ReviewStatus: "READY_FOR_REVIEW", ActiveID: activeVersion(r.Context(), tx, "AUTHENTICITY", scopeKey(v.Target), "reference")}
	if !authenticityStrong(referenceData) {
		ref.ReviewStatus = "INSUFFICIENT"
	}
	for _, sample := range referenceData.Samples {
		if sample.ResponseName == "DIFFERENT_CLAIM" {
			ref.ReviewStatus = "CONFLICTING_REFERENCE"
		}
	}
	if tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM authenticity_reference_versions WHERE target_scope=?", scopeKey(v.Target)).Scan(&ref.Version) != nil {
		http.Error(w, "database error", 500)
		return
	}
	ref.Comparison = buildAuthenticityReport(v.ID, at, targetData)
	ref.Comparison.ReferenceID = v.ID
	ref.Comparison.ReferenceState = "UNCONFIRMED_DRAFT"
	ref.Comparison.Reference = &referenceData
	if ref.ReviewStatus == "READY_FOR_REVIEW" {
		compareAuthenticity(&ref.Comparison, referenceData)
	}
	raw, _ := json.Marshal(ref)
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO authenticity_reference_versions VALUES(?,?,?,?,?)", ref.ID, scopeKey(v.Target), ref.Version, string(raw), at); err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, ref)
}
func (s *Server) activateAuthenticityReferenceHTTP(w http.ResponseWriter, r *http.Request) {
	var v versionActivation
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm {
		http.Error(w, "explicit review confirmation required", 422)
		return
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	ref, err := readAuthenticityReference(r.Context(), tx, v.ID)
	if err != nil {
		http.Error(w, "reference missing", 404)
		return
	}
	if ref.ReviewStatus != "READY_FOR_REVIEW" || now() < ref.At || now()-ref.At > 86400000 {
		http.Error(w, "reference needs a fresh, sufficient Review", 409)
		return
	}
	if _, err = s.currentAuthenticityReference(r.Context(), tx, ref, now(), secrets); err != nil {
		http.Error(w, err.Error(), 409)
		return
	}
	// Recheck the exact target evidence reviewed as well as current context.
	scope, err := s.authenticityScope(r.Context(), tx, ref.Target.Scope.Selection)
	if err != nil {
		http.Error(w, "target scope changed", 409)
		return
	}
	target, err := loadAuthenticityDataset(r.Context(), tx, scope, now(), authenticityDatasetIDs(ref.Target), secrets)
	if err != nil || target.Hash != ref.Target.Hash {
		http.Error(w, "reviewed target evidence changed; preview again", 409)
		return
	}
	action, err := applyVersion(r.Context(), tx, "AUTHENTICITY", scopeKey(ref.Target.Scope.Selection), "reference", ref.ID, v.ExpectedID)
	if err != nil {
		http.Error(w, err.Error(), 409)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": ref.ID, "active": true, "action_id": action, "identity_status": "UNPROVEN"})
}
func (s *Server) listAuthenticityReferences(w http.ResponseWriter, r *http.Request) {
	v := selectionQuery(r)
	if !validScope(v) {
		http.Error(w, "exact target selection required", 400)
		return
	}
	limit, offset := pageRange(r, 20)
	scope := scopeKey(v)
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT definition_json FROM authenticity_reference_versions WHERE target_scope=? AND (?='' OR id=?) ORDER BY version DESC LIMIT ? OFFSET ?", scope, r.URL.Query().Get("id"), r.URL.Query().Get("id"), limit+1, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	out := []authenticityReference{}
	for rows.Next() {
		var raw string
		var ref authenticityReference
		if rows.Scan(&raw) != nil || json.Unmarshal([]byte(raw), &ref) != nil {
			rows.Close()
			http.Error(w, "reference unavailable", 500)
			return
		}
		out = append(out, ref)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	active := activeVersion(r.Context(), s.Store.DB, "AUTHENTICITY", scope, "reference")
	action := authenticityReferenceAction(r.Context(), s.Store.DB, scope, active)
	for i := range out {
		out[i].ActiveID = active
		if out[i].ID == active {
			out[i].ActionID = action
		} else {
			out[i].ActionID = ""
		}
	}
	jsonOK(w, 200, map[string]any{"versions": out, "active_id": active, "activation_action_id": action, "limit": limit, "offset": offset, "has_more": more})
}

func authenticityOriginEqual(a, b string) bool {
	canonical := func(raw string) string {
		u, e := url.Parse(raw)
		if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
			return ""
		}
		host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
		port := u.Port()
		if port != "" {
			n, e := strconv.ParseUint(port, 10, 16)
			if e != nil || n == 0 {
				return ""
			}
			port = strconv.FormatUint(n, 10)
		}
		if port == "" || port == "443" {
			port = "443"
		}
		return net.JoinHostPort(host, port)
	}
	left := canonical(a)
	return left != "" && left == canonical(b)
}
