package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"strings"
)

type authenticitySample struct {
	ID            string               `json:"id"`
	At            int64                `json:"observed_at"`
	Source        string               `json:"source"`
	Outcome       string               `json:"outcome"`
	Proof         string               `json:"proof"`
	ResponseModel string               `json:"response_model,omitempty"`
	ResponseName  string               `json:"response_name_status"`
	Input         *int64               `json:"provider_reported_input_tokens"`
	Output        *int64               `json:"provider_reported_output_tokens"`
	ReportedCost  *string              `json:"provider_reported_usd"`
	EstimatedCost *string              `json:"effective_estimated_usd"`
	NominalCost   *string              `json:"nominal_estimated_usd"`
	Features      *authenticityCapture `json:"observed_features,omitempty"`
}
type authenticityCosts struct {
	Unit          string  `json:"unit"`
	KnownSubtotal *string `json:"known_subtotal"`
	Total         *string `json:"total"`
	Known         int     `json:"known_samples"`
	Unknown       int     `json:"unknown_samples"`
}
type authenticityDataset struct {
	Scope          authenticityScope    `json:"scope"`
	Samples        []authenticitySample `json:"samples"`
	Truncated      bool                 `json:"truncated"`
	Hash           string               `json:"hash"`
	Attested       int                  `json:"attested_samples"`
	Usable         int                  `json:"usable_samples"`
	Failed         int                  `json:"failed_samples"`
	Excluded       int                  `json:"excluded_samples"`
	Tools          int                  `json:"tool_call_samples"`
	Reasoning      int                  `json:"visible_reasoning_samples"`
	LargestPayload int64                `json:"largest_observed_payload_bytes"`
	ReportedCost   authenticityCosts    `json:"provider_reported_cost"`
	EstimatedCost  authenticityCosts    `json:"effective_estimated_cost"`
	NominalCost    authenticityCosts    `json:"nominal_estimated_cost"`
}
type authenticityFinding struct {
	Code      string   `json:"code"`
	State     string   `json:"state"`
	SampleIDs []string `json:"sample_ids"`
	Reason    string   `json:"reason"`
}
type authenticityReport struct {
	ID                  string                `json:"id"`
	Suite               string                `json:"suite_version"`
	At                  int64                 `json:"observed_at"`
	WindowStart         int64                 `json:"window_start"`
	Status              string                `json:"observation_status"`
	Identity            string                `json:"identity_status"`
	Confidence          string                `json:"identity_confidence"`
	AdditionalHTTP      int                   `json:"additional_http_requests"`
	AdditionalInference int                   `json:"additional_inference_requests"`
	ProcessingCost      *string               `json:"processing_monetary_cost"`
	Target              authenticityDataset   `json:"target"`
	ReferenceID         string                `json:"reference_version_id,omitempty"`
	ReferenceState      string                `json:"reference_status"`
	Reference           *authenticityDataset  `json:"reference,omitempty"`
	MatchedConditions   int                   `json:"matched_conditions"`
	MatchedSamples      int                   `json:"matched_samples"`
	Findings            []authenticityFinding `json:"findings"`
	Limits              []string              `json:"limits"`
}
type authenticityAnalysisRequest struct {
	ID        string    `json:"id"`
	Selection Selection `json:"selection"`
}

func authenticityAmount(v any) *string {
	n := decimal(v)
	if n == nil || n.Sign() < 0 {
		return nil
	}
	return exactString(n)
}
func sumAuthenticityCosts(samples []authenticitySample, field func(authenticitySample) *string) authenticityCosts {
	out := authenticityCosts{Unit: "USD"}
	sum := big.NewRat(0, 1)
	for _, v := range samples {
		amount := field(v)
		if amount == nil {
			out.Unknown++
			continue
		}
		n := decimal(*amount)
		if n == nil || n.Sign() < 0 {
			out.Unknown++
			continue
		}
		out.Known++
		sum.Add(sum, n)
	}
	if out.Known > 0 {
		out.KnownSubtotal = exactString(sum)
		if out.Unknown == 0 {
			out.Total = exactString(sum)
		}
	}
	return out
}
func authenticityDetails(raw string) map[string]any {
	var details map[string]any
	d := json.NewDecoder(strings.NewReader(raw))
	d.UseNumber()
	_ = d.Decode(&details)
	return details
}
func loadAuthenticityDataset(ctx context.Context, q queryRows, scope authenticityScope, at int64, ids []string, secrets []string) (authenticityDataset, error) {
	out := authenticityDataset{Scope: scope, Samples: []authenticitySample{}}
	args := []any{scope.Selection.ProviderID, scope.Selection.AccountID, scope.Selection.CredentialID, scope.Selection.ModelID, scope.Selection.Group, scope.Selection.Endpoint, scope.Operation, scope.CredentialVersion, at - 7*86400000, at}
	where := ` WHERE t.provider_id=? AND t.account_id=? AND t.credential_id=? AND t.model_id=? AND t.group_id=? AND t.endpoint=? AND t.operation=? AND t.credential_version=? AND t.created_at BETWEEN ? AND ?`
	if len(ids) > 0 {
		if len(ids) > 32 {
			return out, errors.New("reference sample budget")
		}
		where += " AND t.id IN(" + strings.TrimRight(strings.Repeat("?,", len(ids)), ",") + ")"
		for _, id := range ids {
			args = append(args, id)
		}
	}
	rows, err := q.QueryContext(ctx, "SELECT "+authenticityTrafficColumns+`,COALESCE(a.material_stamp,''),COALESCE(a.telemetry_hash,''),COALESCE(a.features_json,'') FROM telemetry t LEFT JOIN authenticity_local_samples a ON a.sample_id=t.id`+where+` ORDER BY t.created_at DESC,t.id DESC LIMIT 33`, args...)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var t authenticityTraffic
		var stamp, hash, raw string
		if err = scanAuthenticityTraffic(rows, &t, &stamp, &hash, &raw); err != nil {
			return out, err
		}
		if len(out.Samples) == 32 {
			out.Truncated = true
			break
		}
		sample := authenticitySample{ID: t.ID, At: t.At, Source: safeText(t.Source, 80, secrets...), Outcome: safeText(t.Outcome, 80, secrets...), Proof: "UNATTESTED", ResponseName: "UNKNOWN"}
		var f authenticityCapture
		details := authenticityDetails(t.Details)
		var receipt RequestReceipt
		receiptRaw, _ := json.Marshal(details["receipt"])
		_ = json.Unmarshal(receiptRaw, &receipt)
		switch {
		case oneOf(t.Outcome, "RUNNING", "CANCELLED", "INTERRUPTED"):
			sample.Proof = "EXCLUDED"
			out.Excluded++
		case stamp == "" || hash == "" || raw == "":
		case stamp != scope.MaterialStamp:
			sample.Proof = "CREDENTIAL_CHANGED"
		case t.hash() != hash:
			sample.Proof = "RECORD_CHANGED"
		case json.Unmarshal([]byte(raw), &f) != nil || f.Version != authenticityCaptureVersion || f.SampleID != t.ID || f.ConfigHash != scope.ConfigHash || f.EndpointHash != scope.EndpointHash:
			sample.Proof = "CONDITIONS_CHANGED"
		case receipt.Origin != scope.Origin || receipt.CredentialVersion != scope.CredentialVersion || receipt.CapturedAt != t.At:
			sample.Proof = "ORIGIN_OR_REVISION_CHANGED"
		default:
			sample.Proof = "LOCAL_REQUEST"
			sample.Features = &f
			out.Attested++
			sample.Input = t.Input
			sample.Output = t.Output
			if sample.Input != nil && *sample.Input < 0 {
				sample.Input = nil
			}
			if sample.Output != nil && *sample.Output < 0 {
				sample.Output = nil
			}
			sample.ResponseModel = safeAuthenticityModel(str(details["response_model_id"]), secrets)
			if sample.ResponseModel != "" {
				sample.ResponseName = "DIFFERENT_CLAIM"
				if oneOf(sample.ResponseModel, scope.Aliases...) {
					sample.ResponseName = "EXPECTED_CLAIM"
				}
			}
			sample.ReportedCost = authenticityAmount(receipt.Cost["actual"])
			sample.EstimatedCost = authenticityAmount(receipt.Cost["estimated"])
			sample.NominalCost = authenticityAmount(receipt.Cost["nominal_estimated"])
			if t.Outcome == "SUCCESS" {
				out.Usable++
				if f.ValidToolCalls > 0 {
					out.Tools++
				}
				if f.ReasoningBytes > 0 {
					out.Reasoning++
				}
				out.LargestPayload = max(out.LargestPayload, f.PayloadBytes)
			} else {
				out.Failed++
			}
		}
		out.Samples = append(out.Samples, sample)
	}
	if err = rows.Err(); err != nil {
		return out, err
	}
	out.ReportedCost = sumAuthenticityCosts(out.Samples, func(v authenticitySample) *string { return v.ReportedCost })
	out.EstimatedCost = sumAuthenticityCosts(out.Samples, func(v authenticitySample) *string { return v.EstimatedCost })
	out.NominalCost = sumAuthenticityCosts(out.Samples, func(v authenticitySample) *string { return v.NominalCost })
	out.Hash = connectorHash(struct {
		Scope   authenticityScope
		Samples []authenticitySample
	}{scope, out.Samples})
	return out, nil
}
func authenticityDatasetIDs(d authenticityDataset) []string {
	out := []string{}
	for _, v := range d.Samples {
		out = append(out, v.ID)
	}
	return out
}
func authenticityStrong(d authenticityDataset) bool {
	conditions := map[string]bool{}
	strong := false
	count := 0
	for _, v := range d.Samples {
		if v.Proof != "LOCAL_REQUEST" || v.Outcome != "SUCCESS" || v.Features == nil || v.Features.ConditionHash == "" {
			continue
		}
		count++
		conditions[v.Features.ConditionHash] = true
		strong = strong || v.Features.ValidToolCalls > 0 || v.Features.ReasoningBytes > 0 || v.Features.PayloadBytes >= 4096
	}
	return count >= 3 && len(conditions) >= 2 && strong
}
func buildAuthenticityReport(id string, at int64, target authenticityDataset) authenticityReport {
	out := authenticityReport{ID: id, Suite: authenticitySuiteVersion, At: at, WindowStart: at - 7*86400000, Status: "INSUFFICIENT", Identity: "UNPROVEN", Confidence: "LOW", Target: target, ReferenceState: "MISSING", Findings: []authenticityFinding{}, Limits: []string{
		"重用最近七日、同一完整範圍與目前憑證版本的最多32筆紀錄；沒有新增外部HTTP或推論。",
		"成功請求、model名稱、站方metadata或一次能力命中均不能證明模型身份。",
		"雜湊只確認請求條件相同；未評分回答品質、內容正確性或最大context理解能力。",
		"參考為使用者確認的來源；同origin、自我比較、不同Canonical或不同條件均不當可信對照。",
		"隨機性、供應商工具選擇與隱藏reasoning可造成差異；矛盾不等於已證明替換。",
		"費用分站方回報、有效估計與標價估計；未知總額保留空值，不代表免費。",
	}}
	if target.Truncated {
		out.Limits = append(out.Limits, "本次資料超過32筆；完整原始歷史永久保留，可在請求紀錄查閱。")
	}
	for _, v := range target.Samples {
		if v.Proof != "LOCAL_REQUEST" {
			continue
		}
		if v.ResponseName == "DIFFERENT_CLAIM" {
			out.Status = "CONFLICTING_OBSERVATIONS"
			out.Findings = append(out.Findings, authenticityFinding{"RESPONSE_NAME", "CONFLICT", []string{v.ID}, "回應名稱與目前明確別名不同；可能是別名或站方標記，不能據此證明替換。"})
		}
	}
	out.Findings = append(out.Findings, authenticityFinding{"USABILITY", map[bool]string{true: "OBSERVED", false: "UNKNOWN"}[target.Usable > 0], []string{}, "可用性只代表這些具本機憑據的請求成功，與身份判定分開。"}, authenticityFinding{"CONTEXT", "OBSERVED_VOLUME_ONLY", []string{}, "保存實際輸入位元組與站方回報token數，不宣稱最大上下文或理解能力。"}, authenticityFinding{"TOOLS", map[bool]string{true: "OBSERVED", false: "UNKNOWN"}[target.Tools > 0], []string{}, "只計入對已提供工具通過驗證的呼叫；沒有呼叫不代表不支援。"}, authenticityFinding{"REASONING", map[bool]string{true: "VISIBLE_OUTPUT_OBSERVED", false: "UNKNOWN"}[target.Reasoning > 0], []string{}, "只記錄可見reasoning輸出；缺少輸出不代表沒有推理能力。"})
	return out
}
func compareAuthenticity(out *authenticityReport, reference authenticityDataset) {
	byCondition := map[string][]authenticitySample{}
	for _, v := range reference.Samples {
		if v.Proof == "LOCAL_REQUEST" && v.Outcome == "SUCCESS" && v.Features != nil && v.Features.ConditionHash != "" {
			byCondition[v.Features.ConditionHash] = append(byCondition[v.Features.ConditionHash], v)
		}
	}
	conditions := map[string]bool{}
	strong := false
	for _, v := range out.Target.Samples {
		if v.Proof != "LOCAL_REQUEST" || v.Features == nil || v.Features.ConditionHash == "" || !oneOf(v.Outcome, "SUCCESS", "INVALID_TOOL_CALL") {
			continue
		}
		matches := byCondition[v.Features.ConditionHash]
		if len(matches) == 0 {
			continue
		}
		conditions[v.Features.ConditionHash] = true
		out.MatchedSamples++
		for _, r := range matches {
			if r.Features.ValidToolCalls > 0 && v.Outcome == "INVALID_TOOL_CALL" {
				out.Status = "CONFLICTING_OBSERVATIONS"
				out.Findings = append(out.Findings, authenticityFinding{"MATCHED_TOOL_FORMAT", "CONFLICT", []string{v.ID, r.ID}, "相同請求條件下，參考的工具呼叫通過驗證，此次回應工具格式未通過；不直接推定模型身份。"})
			}
			strong = strong || (v.Features.ValidToolCalls > 0 && r.Features.ValidToolCalls > 0) || (v.Features.ReasoningBytes > 0 && r.Features.ReasoningBytes > 0) || (v.Outcome == "SUCCESS" && v.Features.PayloadBytes >= 4096 && r.Features.PayloadBytes >= 4096)
		}
	}
	out.MatchedConditions = len(conditions)
	if out.Status != "CONFLICTING_OBSERVATIONS" && out.MatchedSamples >= 3 && len(conditions) >= 2 && strong {
		out.Status = "CONSISTENT_OBSERVATIONS"
	}
	if out.Status == "INSUFFICIENT" {
		out.Findings = append(out.Findings, authenticityFinding{"COMPARISON", "INSUFFICIENT", []string{}, "至少需要三筆具本機憑據的相同條件觀測、兩種請求條件及一種工具／可見推理／較長輸入觀測；目前不足。"})
	}
}
func validateAuthenticitySecrets(value any, secrets []string) error {
	raw, _ := json.Marshal(value)
	if rawSecretPattern.Match(raw) {
		return errors.New("raw secrets are not authenticity metadata")
	}
	for _, key := range secrets {
		if jsonContainsSecret(value, key) {
			return errors.New("raw secrets are not authenticity metadata")
		}
	}
	return nil
}
func (s *Server) analyzeAuthenticity(ctx context.Context, v authenticityAnalysisRequest) (authenticityReport, bool, error) {
	var out authenticityReport
	if !validID(v.ID) || !validScope(v.Selection) {
		return out, false, errors.New("request ID and exact selection required")
	}
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		return out, false, errors.New("vault unavailable")
	}
	if err = validateAuthenticitySecrets(v, secrets); err != nil {
		return out, false, err
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return out, false, err
	}
	defer tx.Rollback()
	requestHash := connectorHash(v)
	var hash, raw string
	err = tx.QueryRowContext(ctx, "SELECT request_hash,report_json FROM authenticity_reports WHERE id=?", v.ID).Scan(&hash, &raw)
	if err == nil {
		if hash != requestHash {
			return out, false, errors.New("request ID already used for another scope")
		}
		if json.Unmarshal([]byte(raw), &out) != nil {
			return out, false, errors.New("saved report unavailable")
		}
		return out, true, nil
	}
	if err != sql.ErrNoRows {
		return out, false, err
	}
	scope, err := s.authenticityScope(ctx, tx, v.Selection)
	if err != nil {
		return out, false, err
	}
	if err = validateAuthenticitySecrets(scope, secrets); err != nil {
		return out, false, err
	}
	at := now()
	target, err := loadAuthenticityDataset(ctx, tx, scope, at, nil, secrets)
	if err != nil {
		return out, false, err
	}
	out = buildAuthenticityReport(v.ID, at, target)
	refID := activeVersion(ctx, tx, "AUTHENTICITY", scopeKey(v.Selection), "reference")
	if refID != "" {
		out.ReferenceID = refID
		ref, e := readAuthenticityReference(ctx, tx, refID)
		if e != nil {
			return out, false, e
		}
		var reference authenticityDataset
		if ref.Target.Scope.ContextHash != scope.ContextHash {
			e = errors.New("參考不屬於目前完整目標範圍，請重新審閱。")
		} else {
			reference, e = s.currentAuthenticityReference(ctx, tx, ref, at, secrets)
		}
		if e != nil {
			out.ReferenceState = "STALE"
			out.Findings = append(out.Findings, authenticityFinding{"REFERENCE", "STALE", []string{}, e.Error()})
		} else {
			out.ReferenceState = "USER_CONFIRMED"
			out.Reference = &reference
			compareAuthenticity(&out, reference)
		}
	}
	rawBytes, err := json.Marshal(out)
	if err != nil {
		return out, false, err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO authenticity_reports VALUES(?,?,?,?,?)", out.ID, requestHash, scopeKey(v.Selection), string(rawBytes), at); err != nil {
		return out, false, err
	}
	if err = tx.Commit(); err != nil {
		return out, false, err
	}
	return out, false, nil
}
func (s *Server) analyzeAuthenticityHTTP(w http.ResponseWriter, r *http.Request) {
	var v authenticityAnalysisRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	out, replayed, err := s.analyzeAuthenticity(r.Context(), v)
	if err != nil {
		http.Error(w, "authenticity analysis unavailable: "+safeText(err.Error(), 240), 409)
		return
	}
	code := 201
	if replayed {
		code = 200
	}
	jsonOK(w, code, out)
}
func (s *Server) listAuthenticityReports(w http.ResponseWriter, r *http.Request) {
	v := selectionQuery(r)
	if !validScope(v) {
		http.Error(w, "exact selection required", 400)
		return
	}
	limit, offset := pageRange(r, 20)
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT report_json FROM authenticity_reports WHERE target_scope=? AND (?='' OR id=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", scopeKey(v), r.URL.Query().Get("id"), r.URL.Query().Get("id"), limit, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []authenticityReport{}
	for rows.Next() {
		var raw string
		var value authenticityReport
		if rows.Scan(&raw) != nil || json.Unmarshal([]byte(raw), &value) != nil {
			http.Error(w, "report unavailable", 500)
			return
		}
		out = append(out, value)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, out)
}
