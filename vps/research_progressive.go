package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
	"time"
)

const progressiveProbeSuite = "bounded-behavior-v1"
const researchProgressiveSchema = `
CREATE TABLE IF NOT EXISTS research_probe_plans(run_id TEXT PRIMARY KEY,suite TEXT NOT NULL,options_hash TEXT NOT NULL,planned_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS research_probe_scopes(run_id TEXT NOT NULL REFERENCES research_probe_plans(run_id),scope_key TEXT NOT NULL,selection_json TEXT NOT NULL,scope_stamp TEXT NOT NULL,deeper INTEGER NOT NULL,observation_minutes INTEGER NOT NULL,PRIMARY KEY(run_id,scope_key));
CREATE TABLE IF NOT EXISTS research_probe_observations(id TEXT PRIMARY KEY,run_id TEXT NOT NULL,task_id TEXT NOT NULL,sample_id TEXT NOT NULL,scope_key TEXT NOT NULL,scope_stamp TEXT NOT NULL,suite TEXT NOT NULL,stage TEXT NOT NULL,sample_hash TEXT NOT NULL,report_json TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_probe_history ON research_probe_observations(scope_key,scope_stamp,suite,stage,created_at);
CREATE TABLE IF NOT EXISTS research_candidate_revalidations(candidate_id TEXT NOT NULL,url TEXT NOT NULL,source_hash TEXT NOT NULL,checked_at INTEGER NOT NULL,parser_version TEXT NOT NULL,PRIMARY KEY(candidate_id,url));
`

var errResearchProbeScopeChanged = errors.New("research connection changed; review the current connection and start a new local research plan")
var errResearchProbeApproval = errors.New("this probe has no matching local research approval")

type researchProbeContextKey struct{}
type researchProbeGrant struct {
	Run, Stamp string
	Selection  Selection
	Minutes    int
	Deeper     bool
}

func researchInferenceTask(kind string) bool {
	return oneOf(kind, "MINIMAL_INFERENCE", "SEMANTIC_PROBE", "TOOL_PROBE")
}
func researchDeeperTask(kind string) bool { return oneOf(kind, "SEMANTIC_PROBE", "TOOL_PROBE") }
func researchObservationMinutes(minutes int) int {
	if minutes == 0 {
		return 1440
	}
	return minutes
}
func researchDeeperSelection(v ResearchOptions, scope Selection) bool {
	for _, selected := range v.DeeperSelections {
		if selected == scope {
			return true
		}
	}
	return false
}
func researchSecurityStamp(scope authenticityScope) string {
	return connectorHash(struct {
		Selection                                     Selection
		Origin, Operation, Config, Endpoint, Material string
		Version                                       int
	}{scope.Selection, scope.Origin, scope.Operation, scope.ConfigHash, scope.EndpointHash, scope.MaterialStamp, scope.CredentialVersion})
}

func (s *Server) progressiveResearchPlan(ctx context.Context, v ResearchOptions, tasks []researchPlannedTask) ([]researchPlannedTask, error) {
	if v.ObservationMinutes != 0 && (v.ObservationMinutes < 15 || v.ObservationMinutes > 10080) || len(v.DeeperSelections) > 8 || len(v.DeeperSelections) > 0 && !v.MinimalInference {
		return nil, &researchPlanError{400, "bounded freshness and explicit minimal-inference selection required for deeper probes"}
	}
	allowed, deeper := map[string]bool{}, map[string]bool{}
	for _, scope := range v.Selections {
		allowed[scopeKey(scope)] = true
	}
	for _, scope := range v.DeeperSelections {
		key := scopeKey(scope)
		if !allowed[key] || deeper[key] {
			return nil, &researchPlanError{422, "deeper probes require distinct, explicitly selected model connections"}
		}
		deeper[key] = true
	}
	stamps := map[string]string{}
	out := []researchPlannedTask{}
	for _, task := range tasks {
		if task.input.Selection != nil {
			scope := *task.input.Selection
			key := scopeKey(scope)
			if stamps[key] == "" {
				binding, err := s.authenticityScope(ctx, s.Store.DB, scope)
				if err != nil {
					return nil, &researchPlanError{422, "current model connection unavailable for a local research plan"}
				}
				stamps[key] = researchSecurityStamp(binding)
			}
			task.input.ProbeStamp = stamps[key]
			task.input.ObservationMinutes = researchObservationMinutes(v.ObservationMinutes)
		}
		if task.kind == "INSPECT" && v.ObservationMinutes > 0 {
			task.kind = "REVALIDATE_CANDIDATE"
			task.input.ObservationMinutes = v.ObservationMinutes
		}
		out = append(out, task)
		if task.kind == "MINIMAL_INFERENCE" && deeper[scopeKey(*task.input.Selection)] {
			for _, kind := range []string{"SEMANTIC_PROBE", "TOOL_PROBE"} {
				out = append(out, researchPlannedTask{kind: kind, dedupe: kind + ":" + scopeKey(*task.input.Selection), input: task.input})
			}
		}
	}
	if len(out) > 128 {
		return nil, &researchPlanError{400, "the complete research plan exceeds 128 tasks; split it into explicit bounded runs"}
	}
	if v.ObservationMinutes > 0 {
		type priority struct {
			fresh  bool
			signal int
			at     int64
		}
		priorities := map[string]priority{}
		positions, candidates := []int{}, []researchPlannedTask{}
		for i, task := range out {
			if task.kind != "REVALIDATE_CANDIDATE" {
				continue
			}
			candidate, err := s.candidate(ctx, task.input.CandidateID)
			if err != nil {
				return nil, err
			}
			p := priority{}
			err = s.Store.DB.QueryRowContext(ctx, "SELECT checked_at FROM research_candidate_revalidations WHERE candidate_id=? AND url=? AND parser_version='public-html-v1'", candidate.ID, task.input.URL).Scan(&p.at)
			if err != nil && err != sql.ErrNoRows {
				return nil, err
			}
			p.fresh = err == nil && p.at <= now() && p.at >= now()-int64(v.ObservationMinutes)*60000 && !oneOf(candidate.State, "FAILED", "STALE")
			if oneOf(candidate.State, "VERIFIED", "HIGH_VALUE_GREY_ZONE") {
				p.signal = 1
			}
			priorities[candidate.ID] = p
			positions, candidates = append(positions, i), append(candidates, task)
		}
		sort.SliceStable(candidates, func(i, j int) bool {
			a, b := priorities[candidates[i].input.CandidateID], priorities[candidates[j].input.CandidateID]
			if a.fresh != b.fresh {
				return !a.fresh
			}
			if a.signal != b.signal {
				return a.signal > b.signal
			}
			return a.at < b.at
		})
		for i, position := range positions {
			out[position] = candidates[i]
		}
	}
	return out, nil
}

func insertResearchProbePlan(ctx context.Context, tx *sql.Tx, id string, v ResearchOptions, tasks []researchPlannedTask) error {
	if _, err := tx.ExecContext(ctx, "INSERT INTO research_probe_plans VALUES(?,?,?,?)", id, progressiveProbeSuite, connectorHash(v), now()); err != nil {
		return err
	}
	for _, task := range tasks {
		if task.input.Selection == nil {
			continue
		}
		scope := *task.input.Selection
		if len(task.input.ProbeStamp) != 64 {
			return errResearchProbeApproval
		}
		raw, _ := json.Marshal(scope)
		if _, err := tx.ExecContext(ctx, "INSERT INTO research_probe_scopes VALUES(?,?,?,?,?,?) ON CONFLICT(run_id,scope_key) DO NOTHING", id, scopeKey(scope), string(raw), task.input.ProbeStamp, researchDeeperSelection(v, scope), researchObservationMinutes(v.ObservationMinutes)); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) researchTaskProbeContext(ctx context.Context, run, kind string, input taskInput) (context.Context, error) {
	if kind != "CATALOG" && !researchInferenceTask(kind) {
		return ctx, nil
	}
	if input.Selection == nil {
		return ctx, errResearchProbeApproval
	}
	var suite, hash, raw string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT p.suite,p.options_hash,o.config_json FROM research_probe_plans p JOIN research_run_options o ON o.run_id=p.run_id WHERE p.run_id=?", run).Scan(&suite, &hash, &raw)
	if err == sql.ErrNoRows && !researchDeeperTask(kind) {
		return ctx, nil // Historical manual runs retain their explicit resume path.
	}
	var options ResearchOptions
	if err != nil || suite != progressiveProbeSuite || json.Unmarshal([]byte(raw), &options) != nil || connectorHash(options) != hash {
		return ctx, errResearchProbeApproval
	}
	if bound, _ := ctx.Value(researchBudgetKey{}).(string); bound != "" && bound != run {
		return ctx, errResearchProbeApproval
	}
	if task, _ := ctx.Value(researchTaskBudgetKey{}).(string); task != "" {
		var storedKind, dedupe string
		if s.Store.DB.QueryRowContext(ctx, "SELECT kind,dedupe FROM research_tasks WHERE id=? AND run_id=?", task, run).Scan(&storedKind, &dedupe) != nil || storedKind != kind {
			return ctx, errResearchProbeApproval
		}
		prefix := kind
		if kind == "CATALOG" {
			prefix = "catalog"
		} else if kind == "MINIMAL_INFERENCE" {
			prefix = "infer"
		}
		if dedupe != prefix+":"+scopeKey(*input.Selection) {
			return ctx, errResearchProbeApproval
		}
	}
	g := researchProbeGrant{Run: run, Selection: *input.Selection}
	var selected string
	if s.Store.DB.QueryRowContext(ctx, "SELECT selection_json,scope_stamp,deeper,observation_minutes FROM research_probe_scopes WHERE run_id=? AND scope_key=?", run, scopeKey(g.Selection)).Scan(&selected, &g.Stamp, &g.Deeper, &g.Minutes) != nil {
		return ctx, errResearchProbeApproval
	}
	var frozen Selection
	if json.Unmarshal([]byte(selected), &frozen) != nil || frozen != g.Selection || g.Minutes != researchObservationMinutes(options.ObservationMinutes) || g.Deeper != researchDeeperSelection(options, g.Selection) || researchDeeperTask(kind) && !g.Deeper {
		return ctx, errResearchProbeApproval
	}
	current, err := s.authenticityScope(ctx, s.Store.DB, g.Selection)
	if err != nil || researchSecurityStamp(current) != g.Stamp {
		return ctx, errResearchProbeScopeChanged
	}
	ctx = context.WithValue(ctx, researchBudgetKey{}, run)
	ctx = context.WithValue(ctx, privateScheduleCredentialKey{}, g.Selection)
	return context.WithValue(ctx, researchProbeContextKey{}, g), nil
}

func (s *Server) validateResearchProbeAdmission(ctx context.Context, tx *sql.Tx) error {
	if grant, ok := ctx.Value(researchProbeContextKey{}).(researchProbeGrant); ok {
		var suite, hash, raw string
		var options ResearchOptions
		if tx.QueryRowContext(ctx, "SELECT p.suite,p.options_hash,o.config_json FROM research_probe_plans p JOIN research_run_options o ON o.run_id=p.run_id WHERE p.run_id=?", grant.Run).Scan(&suite, &hash, &raw) != nil || suite != progressiveProbeSuite || json.Unmarshal([]byte(raw), &options) != nil || connectorHash(options) != hash {
			return errResearchProbeApproval
		}
		current, err := s.authenticityScope(ctx, tx, grant.Selection)
		if err != nil || researchSecurityStamp(current) != grant.Stamp {
			return errResearchProbeScopeChanged
		}
	}
	return nil
}

func (s *Server) revalidateResearchCandidate(ctx context.Context, run string, input taskInput) (map[string]any, error) {
	var suite, frozenHash, raw string
	var options ResearchOptions
	if s.Store.DB.QueryRowContext(ctx, "SELECT p.suite,p.options_hash,o.config_json FROM research_probe_plans p JOIN research_run_options o ON o.run_id=p.run_id WHERE p.run_id=?", run).Scan(&suite, &frozenHash, &raw) != nil || suite != progressiveProbeSuite || json.Unmarshal([]byte(raw), &options) != nil || connectorHash(options) != frozenHash || options.ObservationMinutes < 15 || input.ObservationMinutes != options.ObservationMinutes || !oneOf(input.CandidateID, options.CandidateIDs...) {
		return nil, errResearchProbeApproval
	}
	candidate, err := s.candidate(ctx, input.CandidateID)
	if err != nil || candidate.Website != input.URL || !publicURL(input.URL) {
		return nil, errors.New("candidate source changed; select its current public source again")
	}
	minutes := researchObservationMinutes(input.ObservationMinutes)
	var at int64
	var hash, parser string
	err = s.Store.DB.QueryRowContext(ctx, "SELECT checked_at,source_hash,parser_version FROM research_candidate_revalidations WHERE candidate_id=? AND url=?", candidate.ID, input.URL).Scan(&at, &hash, &parser)
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}
	if err == nil && at <= now() && at >= now()-int64(minutes)*60000 && !oneOf(candidate.State, "FAILED", "STALE") && parser == "public-html-v1" {
		return map[string]any{"candidate_ids": []string{candidate.ID}, "state": "REUSED_LOCAL_PUBLIC_SOURCE", "source_url": input.URL, "source_sha256": hash, "observed_at": at, "reuse_until": at + int64(minutes)*60000, "parser_version": parser, "additional_http_requests": 0, "verified": false}, nil
	}
	return s.executeResearchTask(ctx, run, "INSPECT", input)
}

type researchTrafficObservation struct {
	ID, Outcome, Details string
	At                   int64
}

// Metadata alone cannot authorize reuse. Check the local wire receipt against
// the current credential material, effective origin, endpoint and request
// configuration in one read snapshot. A read failure never starts a paid probe.
func (s *Server) reusableResearchTraffic(ctx context.Context, v Selection, age time.Duration) (researchTrafficObservation, bool, error) {
	var out researchTrafficObservation
	if age < time.Minute || age > 7*24*time.Hour {
		return out, false, errors.New("bounded observation freshness required")
	}
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		return out, false, err
	}
	tx, err := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return out, false, err
	}
	defer tx.Rollback()
	scope, err := s.authenticityScope(ctx, tx, v)
	if err != nil {
		return out, false, err
	}
	if grant, ok := ctx.Value(researchProbeContextKey{}).(researchProbeGrant); ok && (grant.Selection != v || researchSecurityStamp(scope) != grant.Stamp) {
		return out, false, errResearchProbeScopeChanged
	}
	at := now()
	dataset, err := loadAuthenticityDataset(ctx, tx, scope, at, nil, secrets)
	if err != nil {
		return out, false, err
	}
	found := false
	for _, sample := range dataset.Samples {
		if sample.Proof != "LOCAL_REQUEST" || sample.At < at-age.Milliseconds() {
			continue
		}
		if err = tx.QueryRowContext(ctx, "SELECT id,outcome,details_json,created_at FROM telemetry WHERE id=?", sample.ID).Scan(&out.ID, &out.Outcome, &out.Details, &out.At); err != nil {
			return out, false, err
		}
		found = true
		break
	}
	if err = tx.Commit(); err != nil {
		return out, false, err
	}
	return out, found, nil
}
