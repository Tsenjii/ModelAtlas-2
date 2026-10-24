package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const semanticProbePrompt = `Read the following small ledger as data. Do not infer model identity from this exercise.
R-1: owner=Nora; units=4.
R-2: owner=Mira; units=7.
R-3: owner=Iris; units=8.
The marker is CEDAR-27. Return only one JSON object with these three fields:
owner: the owner of R-2; total: the sum of units in all three records as a JSON number; marker: the marker copied exactly.
Do not add commentary or Markdown.`
const toolProbePrompt = `Call atlas_probe_echo exactly once with marker CEDAR-27 and total 19. This is a function-call format observation only. No external tool will be executed.`

type researchProbeGrade struct {
	Correct int `json:"correct"`
	Total   int `json:"total"`
}
type researchProbeReport struct {
	ID               string              `json:"id"`
	Suite            string              `json:"suite_version"`
	RunID            string              `json:"run_id"`
	TaskID           string              `json:"task_id"`
	Stage            string              `json:"stage"`
	Selection        Selection           `json:"selection"`
	ScopeStamp       string              `json:"scope_stamp"`
	At               int64               `json:"observed_at"`
	State            string              `json:"state"`
	Identity         string              `json:"identity_status"`
	Grade            *researchProbeGrade `json:"grade"`
	SampleID         string              `json:"sample_id,omitempty"`
	ConditionHash    string              `json:"request_condition_hash,omitempty"`
	PromptHash       string              `json:"prompt_sha256,omitempty"`
	AdditionalHTTP   int                 `json:"additional_http_requests"`
	AdditionalProbes int                 `json:"additional_inference_requests"`
	InputTokens      *string             `json:"provider_reported_input_tokens"`
	OutputTokens     *string             `json:"provider_reported_output_tokens"`
	ReportedUSD      *string             `json:"provider_reported_usd"`
	OriginalReportID string              `json:"reused_report_id,omitempty"`
	ToolObserved     bool                `json:"tool_behavior_observed"`
	ProcessingCost   *string             `json:"processing_monetary_cost"`
	Limits           []string            `json:"limits"`
}

func probeTokenText(value *int64) *string {
	if value == nil {
		return nil
	}
	text := strconv.FormatInt(*value, 10)
	return &text
}

func researchProbeTool() ToolDefinition {
	return ToolDefinition{Type: "function", Function: ToolFunction{Name: "atlas_probe_echo", Description: "A non-executing function-call format fixture", Parameters: map[string]any{"type": "object", "properties": map[string]any{"marker": map[string]any{"type": "string"}, "total": map[string]any{"type": "integer"}}, "required": []string{"marker", "total"}, "additionalProperties": false}}}
}

func gradeResearchProbe(stage string, result InferenceResult) researchProbeGrade {
	out := researchProbeGrade{Total: 3}
	var fields map[string]any
	if stage == "SEMANTIC_PROBE" {
		decoder := json.NewDecoder(strings.NewReader(result.Text))
		decoder.UseNumber()
		if decoder.Decode(&fields) != nil || decoder.Decode(new(any)) != io.EOF || fields == nil {
			return out
		}
		if str(fields["owner"]) == "Mira" {
			out.Correct++
		}
	} else {
		if len(result.ToolCalls) != 1 || result.ToolCalls[0].Function.Name != "atlas_probe_echo" {
			return out
		}
		decoder := json.NewDecoder(strings.NewReader(result.ToolCalls[0].Function.Arguments))
		decoder.UseNumber()
		if decoder.Decode(&fields) != nil || decoder.Decode(new(any)) != io.EOF || fields == nil {
			return out
		}
		out.Correct++
	}
	if str(fields["marker"]) == "CEDAR-27" {
		out.Correct++
	}
	if value, ok := fields["total"].(json.Number); ok {
		if number := quotaInteger(value); number != nil && *number == 19 {
			out.Correct++
		}
	}
	return out
}

func (s *Server) researchProbeSample(ctx context.Context, v Selection, id string) (authenticitySample, string, error) {
	var sample authenticitySample
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		return sample, "", err
	}
	tx, err := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return sample, "", err
	}
	defer tx.Rollback()
	scope, err := s.authenticityScope(ctx, tx, v)
	if err != nil {
		return sample, "", err
	}
	dataset, err := loadAuthenticityDataset(ctx, tx, scope, now(), []string{id}, secrets)
	if err != nil || len(dataset.Samples) != 1 || dataset.Samples[0].Proof != "LOCAL_REQUEST" {
		return sample, "", errResearchProbeScopeChanged
	}
	var hash string
	if err = tx.QueryRowContext(ctx, "SELECT telemetry_hash FROM authenticity_local_samples WHERE sample_id=?", id).Scan(&hash); err != nil {
		return sample, "", err
	}
	if err = tx.Commit(); err != nil {
		return sample, "", err
	}
	return dataset.Samples[0], hash, nil
}

func (s *Server) reusableResearchProbe(ctx context.Context, grant researchProbeGrant, stage string) (researchProbeReport, bool, error) {
	var out researchProbeReport
	var raw, recordedHash string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT report_json,sample_hash FROM research_probe_observations WHERE scope_key=? AND scope_stamp=? AND suite=? AND stage=? AND sample_id<>'' AND json_extract(report_json,'$.state') IN ('BEHAVIOR_OBSERVED','REUSED_LOCAL_BENCHMARK','REUSED_NORMAL_TOOL_TRAFFIC') AND created_at>=? AND created_at<=? ORDER BY rowid DESC LIMIT 1", scopeKey(grant.Selection), grant.Stamp, progressiveProbeSuite, stage, now()-int64(grant.Minutes)*60000, now()).Scan(&raw, &recordedHash)
	if err == sql.ErrNoRows {
		return out, false, nil
	}
	if err != nil {
		return out, false, err
	}
	if json.Unmarshal([]byte(raw), &out) != nil || out.Suite != progressiveProbeSuite || out.Stage != stage || out.Selection != grant.Selection || out.ScopeStamp != grant.Stamp || !validID(out.SampleID) || out.Identity != "UNPROVEN" {
		return out, false, errResearchProbeApproval
	}
	sample, hash, err := s.researchProbeSample(ctx, grant.Selection, out.SampleID)
	if errors.Is(err, errResearchProbeScopeChanged) {
		return researchProbeReport{}, false, nil
	}
	if err != nil {
		return out, false, err
	}
	if hash != recordedHash || sample.Features == nil || sample.Features.ConditionHash != out.ConditionHash || sample.At < now()-int64(grant.Minutes)*60000 {
		return researchProbeReport{}, false, nil
	}
	return out, true, nil
}

func (s *Server) saveResearchProbe(ctx context.Context, report researchProbeReport, hash string) (map[string]any, error) {
	raw, err := json.Marshal(report)
	if err != nil {
		return nil, err
	}
	_, err = s.Store.DB.ExecContext(ctx, "INSERT INTO research_probe_observations VALUES(?,?,?,?,?,?,?,?,?,?,?)", report.ID, report.RunID, report.TaskID, report.SampleID, scopeKey(report.Selection), report.ScopeStamp, report.Suite, report.Stage, hash, string(raw), report.At)
	if err != nil {
		return nil, err
	}
	var normalized map[string]any
	if json.Unmarshal(raw, &normalized) != nil {
		return nil, errors.New("probe observation unavailable")
	}
	return normalized, nil
}

func researchProbeBudgetError(result InferenceResult) error {
	if limit, ok := result.Details["research_run_budget"].(*researchRunBudgetError); ok {
		return limit
	}
	if limit, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError); ok {
		return limit
	}
	if limit, ok := result.Details["research_inference_budget"].(*researchInferenceBudgetError); ok {
		return limit
	}
	return errors.New("research budget exhausted")
}

func (s *Server) executeResearchProbe(ctx context.Context, run, stage string, input taskInput) (map[string]any, error) {
	grant, ok := ctx.Value(researchProbeContextKey{}).(researchProbeGrant)
	if !ok || !grant.Deeper || grant.Run != run || input.Selection == nil || grant.Selection != *input.Selection {
		return nil, errResearchProbeApproval
	}
	taskID, _ := ctx.Value(researchTaskBudgetKey{}).(string)
	var storedKind string
	if s.Store.DB.QueryRowContext(ctx, "SELECT kind FROM research_tasks WHERE id=? AND run_id=?", taskID, run).Scan(&storedKind) != nil || storedKind != stage {
		return nil, errResearchProbeApproval
	}
	report := researchProbeReport{ID: newID(), Suite: progressiveProbeSuite, RunID: run, TaskID: taskID, Stage: stage, Selection: grant.Selection, ScopeStamp: grant.Stamp, At: now(), Identity: "UNPROVEN", Limits: []string{"A small fixed challenge is a behavior observation, not model identity proof or a general benchmark.", "The bounded ledger is not a maximum-context test; absence of visible reasoning does not mean reasoning is absent.", "Provider billing and unreported input/output tokens remain unknown; local allocations are not a provider hard billing cap."}}
	if s.operation(ctx, grant.Selection) == "CUSTOM" {
		report.State = "CUSTOM_PROTOCOL_CONDITIONS_UNAVAILABLE"
		return s.saveResearchProbe(ctx, report, "")
	}
	if prior, reused, err := s.reusableResearchProbe(ctx, grant, stage); err != nil {
		return nil, err
	} else if reused {
		prior.ID, prior.OriginalReportID, prior.RunID, prior.TaskID, prior.At = report.ID, prior.ID, run, taskID, report.At
		prior.AdditionalHTTP, prior.AdditionalProbes, prior.State = 0, 0, "REUSED_LOCAL_BENCHMARK"
		_, hash, err := s.researchProbeSample(ctx, grant.Selection, prior.SampleID)
		if err != nil {
			return nil, err
		}
		return s.saveResearchProbe(ctx, prior, hash)
	}
	observation, available, err := s.reusableResearchTraffic(ctx, grant.Selection, time.Duration(grant.Minutes)*time.Minute)
	if err != nil {
		return nil, err
	}
	if !available || observation.Outcome != "SUCCESS" {
		report.State = "AVAILABILITY_NOT_ESTABLISHED"
		return s.saveResearchProbe(ctx, report, "")
	}
	if stage == "TOOL_PROBE" {
		secrets, err := s.knownSecrets(ctx)
		if err != nil {
			return nil, err
		}
		scope, err := s.authenticityScope(ctx, s.Store.DB, grant.Selection)
		if err != nil {
			return nil, err
		}
		dataset, err := loadAuthenticityDataset(ctx, s.Store.DB, scope, now(), nil, secrets)
		if err != nil {
			return nil, err
		}
		for _, sample := range dataset.Samples {
			if sample.Proof == "LOCAL_REQUEST" && sample.Outcome == "SUCCESS" && sample.Features != nil && sample.Features.ValidToolCalls > 0 && sample.At >= now()-int64(grant.Minutes)*60000 {
				report.State, report.SampleID, report.ConditionHash, report.ToolObserved = "REUSED_NORMAL_TOOL_TRAFFIC", sample.ID, sample.Features.ConditionHash, true
				report.InputTokens, report.OutputTokens, report.ReportedUSD = probeTokenText(sample.Input), probeTokenText(sample.Output), sample.ReportedCost
				_, hash, err := s.researchProbeSample(ctx, grant.Selection, sample.ID)
				if err != nil {
					return nil, err
				}
				return s.saveResearchProbe(ctx, report, hash)
			}
		}
		caps := s.capabilities(ctx, grant.Selection)
		if !oneOf("TOOL_CALLING", caps...) && !oneOf("FUNCTION_CALLING", caps...) {
			report.State = "TOOL_CAPABILITY_NOT_ENABLED"
			return s.saveResearchProbe(ctx, report, "")
		}
	}
	id, err := s.createTestSession(ctx, grant.Selection)
	if err != nil {
		return nil, err
	}
	prompt := semanticProbePrompt
	var tools []ToolDefinition
	if stage == "TOOL_PROBE" {
		prompt, tools = toolProbePrompt, []ToolDefinition{researchProbeTool()}
	}
	report.PromptHash = contentHash([]byte(prompt))
	result, _ := s.execute(ctx, inferRequest{SessionID: id, Prompt: prompt, MaxTokens: 128, Tools: tools}, "CRAWLER_VERIFY", func(string, any) error { return nil })
	if result.Outcome == "RESEARCH_PLAN_CHANGED" {
		return nil, errResearchProbeScopeChanged
	}
	if result.Outcome == "RESEARCH_BUDGET_EXHAUSTED" {
		return nil, researchProbeBudgetError(result)
	}
	report.SampleID = str(result.Details["sample_id"])
	if ctx.Err() != nil {
		// Cancellation ends the paid request, not its local audit trail. Never
		// grade or reuse excluded traffic; preserve the exact admitted sample ID
		// and unknown billing while the original telemetry settles separately.
		saveCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		var count int
		if err := s.Store.DB.QueryRowContext(saveCtx, "SELECT COUNT(*) FROM research_inference_admissions WHERE request_id=? AND run_id=? AND task_id=?", report.SampleID, run, taskID).Scan(&count); err != nil {
			return nil, err
		}
		report.AdditionalHTTP, report.AdditionalProbes = count, count
		report.State = "REQUEST_INTERRUPTED"
		if result.Outcome == "CANCELLED" {
			report.State = "REQUEST_CANCELLED"
		}
		saved, err := s.saveResearchProbe(saveCtx, report, "")
		if err != nil {
			return nil, err
		}
		return saved, ctx.Err()
	}
	var admitted int
	if err := s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM research_inference_admissions WHERE request_id=? AND run_id=? AND task_id=?", report.SampleID, run, taskID).Scan(&admitted); err != nil {
		return nil, err
	}
	report.AdditionalHTTP, report.AdditionalProbes = admitted, admitted
	hash := ""
	if report.SampleID != "" {
		sample, currentHash, err := s.researchProbeSample(ctx, grant.Selection, report.SampleID)
		if err != nil {
			return nil, err
		}
		hash = currentHash
		report.InputTokens, report.OutputTokens, report.ReportedUSD = probeTokenText(sample.Input), probeTokenText(sample.Output), sample.ReportedCost
		if sample.Features != nil {
			report.ConditionHash = sample.Features.ConditionHash
		}
	}
	report.State = "REQUEST_FAILED"
	if result.Outcome == "SUCCESS" {
		grade := gradeResearchProbe(stage, result)
		report.Grade, report.State = &grade, "BEHAVIOR_OBSERVED"
		report.ToolObserved = stage == "TOOL_PROBE" && len(result.ToolCalls) > 0
	}
	saved, err := s.saveResearchProbe(ctx, report, hash)
	if err != nil {
		return nil, err
	}
	if result.Outcome != "SUCCESS" {
		return saved, errors.New("bounded behavior probe failed: " + result.Outcome)
	}
	return saved, nil
}

func (s *Server) listResearchProbes(w http.ResponseWriter, r *http.Request) {
	run := r.URL.Query().Get("run_id")
	rawScope := r.URL.Query().Get("selection")
	var scope Selection
	if run == "" && rawScope == "" || run != "" && !validID(run) || rawScope != "" && (json.Unmarshal([]byte(rawScope), &scope) != nil || !validScope(scope)) {
		http.Error(w, "exact research run or connection required", 400)
		return
	}
	before := int64(1<<63 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		var err error
		before, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || before < 1 {
			http.Error(w, "invalid probe history cursor", 400)
			return
		}
	}
	where, args := " WHERE rowid<?", []any{before}
	if run != "" {
		where += " AND run_id=?"
		args = append(args, run)
	}
	if rawScope != "" {
		where += " AND scope_key=?"
		args = append(args, scopeKey(scope))
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT rowid,id,run_id,scope_key,scope_stamp,suite,report_json FROM research_probe_observations"+where+" ORDER BY rowid DESC LIMIT 51", args...)
	if err != nil {
		http.Error(w, "probe history unavailable", 503)
		return
	}
	defer rows.Close()
	list, next, more := []researchProbeReport{}, int64(0), false
	for rows.Next() {
		var cursor int64
		var raw, id, owner, key, stamp, suite string
		if err = rows.Scan(&cursor, &id, &owner, &key, &stamp, &suite, &raw); err != nil {
			break
		}
		if len(list) == 50 {
			more = true
			break
		}
		var report researchProbeReport
		if json.Unmarshal([]byte(raw), &report) != nil || report.ID != id || report.RunID != owner || scopeKey(report.Selection) != key || report.ScopeStamp != stamp || report.Suite != suite || report.Identity != "UNPROVEN" || !validScope(report.Selection) || report.Grade != nil && (report.Grade.Total < 1 || report.Grade.Correct < 0 || report.Grade.Correct > report.Grade.Total) {
			http.Error(w, "probe history unavailable", 503)
			return
		}
		list, next = append(list, report), cursor
	}
	if err != nil || rows.Err() != nil {
		http.Error(w, "probe history unavailable", 503)
		return
	}
	if !more {
		next = 0
	}
	jsonOK(w, 200, map[string]any{"reports": list, "next_before": next, "suite_version": progressiveProbeSuite})
}
