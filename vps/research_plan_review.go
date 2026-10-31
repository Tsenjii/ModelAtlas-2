package main

import (
	"context"
	"net/http"
)

func (s *Server) researchPlanReview(ctx context.Context, q queryRows, v ResearchOptions, tasks []researchPlannedTask) (map[string]any, error) {
	policy, err := readResearchResourcePolicy(ctx, q)
	if err != nil {
		return nil, err
	}
	type step struct{ Kind, InputHash, BindingHash string }
	steps := []step{}
	for _, task := range tasks {
		if task.input.Selection != nil {
			current, err := s.authenticityScope(ctx, q, *task.input.Selection)
			if err != nil || researchSecurityStamp(current) != task.input.ProbeStamp {
				return nil, errResearchProbeScopeChanged
			}
		}
		steps = append(steps, step{task.kind, connectorHash(task.input), connectorHash(task.binding)})
	}
	fingerprint := connectorHash(struct {
		Options        ResearchOptions
		Steps          []step
		ResourcePolicy researchResourcePolicyRow
	}{v, steps, policy})
	scopes := []map[string]any{}
	for _, selected := range v.Selections {
		bound, err := s.authenticityScope(ctx, q, selected)
		if err != nil {
			return nil, err
		}
		scopes = append(scopes, map[string]any{"selection": selected, "origin": bound.Origin, "operation": bound.Operation, "credential_version": bound.CredentialVersion, "deeper": researchDeeperSelection(v, selected)})
	}
	minimal := 0
	if v.MinimalInference {
		minimal = len(v.Selections)
	}
	return map[string]any{"fingerprint": fingerprint, "scopes": scopes, "planned_tasks": len(tasks), "minimal_inference_tasks": minimal, "deeper_probe_tasks": 2 * len(v.DeeperSelections), "maximum_new_inferences_this_round": min(v.ProbeBudget, minimal+2*len(v.DeeperSelections)), "http_limit": v.Budget, "observation_minutes": researchObservationMinutes(v.ObservationMinutes), "reserve_tokens_per_inference": policy.ReserveTokens, "reserve_usd_per_inference": policy.ReserveUSD, "suite_version": progressiveProbeSuite, "identity_status": "UNPROVEN"}, nil
}

func (s *Server) previewResearchPlan(w http.ResponseWriter, r *http.Request) {
	var v ResearchOptions
	if !decodeJSON(w, r, &v) {
		return
	}
	v, tasks, err := s.planResearch(r.Context(), v)
	if err != nil {
		http.Error(w, err.Error(), 422)
		return
	}
	plan, err := s.researchPlanReview(r.Context(), s.Store.DB, v, tasks)
	if err != nil {
		http.Error(w, "research plan review unavailable", 503)
		return
	}
	jsonOK(w, 200, plan)
}
