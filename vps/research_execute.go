package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// Local admission control and user cancellation are not upstream failures.
func researchTransportStopped(ctx context.Context, err error) bool {
	var originWait *researchOriginWait
	return ctx.Err() != nil || errors.As(err, &originWait) || errors.Is(err, errScheduledDailyBudget) || errors.Is(err, errScheduledDefinitionChanged) || err != nil && oneOf(err.Error(), "public cooldown storage unavailable", "research run paused", "scheduled research paused", "research HTTP budget exhausted", "research inference budget exhausted")
}

func excerpt(v string, max int) string {
	v = strings.Map(func(r rune) rune {
		if unicode.Is(unicode.Cf, r) {
			return -1
		}
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, strings.ToValidUTF8(v, ""))
	if len(v) > max {
		v = v[:max]
		for !utf8.ValidString(v) {
			v = v[:len(v)-1]
		}
	}
	return strings.TrimSpace(v)
}
func (s *Server) ensureCandidate(ctx context.Context, provider, model, run, website string) (Candidate, error) {
	var id string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT id FROM research_candidates WHERE provider_id=? AND model_id=? ORDER BY rowid LIMIT 1", provider, model).Scan(&id)
	if e == nil {
		return s.candidate(ctx, id)
	}
	if e != sql.ErrNoRows {
		return Candidate{}, e
	}
	c := Candidate{ID: newID(), ProviderID: provider, ModelID: model, Taiwan: "UNKNOWN", Recurring: "UNKNOWN", Strength: "UNKNOWN", State: "DISCOVERED", CandidateDetails: CandidateDetails{Website: website, CreatedRun: run, Confidence: 0}}
	_, e = s.Store.DB.ExecContext(ctx, "INSERT INTO research_candidates VALUES(?,?,?,NULL,NULL,'UNKNOWN','UNKNOWN','UNKNOWN','DISCOVERED',?,?)", c.ID, provider, model, now(), now())
	if e != nil {
		return c, e
	}
	return c, s.saveCandidateDetails(ctx, c.ID, c.CandidateDetails)
}
func providerFromURL(raw string) string {
	u, _ := url.Parse(raw)
	return "discovered-" + contentHash([]byte(strings.ToLower(u.Host)))[:24]
}
func normalizeFacts(row any, fields map[string]string) map[string]any {
	out := map[string]any{}
	if len(fields) == 0 {
		fields = map[string]string{"model_id": "/model_id", "rpd": "/rpd", "tpd": "/tpd", "taiwan": "/taiwan", "recurring": "/recurring", "free": "/free", "base_url": "/base_url", "access_method": "/access_method", "auth_kind": "/auth_kind", "strength": "/strength", "restrictions": "/restrictions"}
	}
	for name, path := range fields {
		if value := pointer(row, path); value != nil {
			out[name] = value
		}
	}
	return out
}
func quotaInteger(v any) *int64 {
	var value string
	switch x := v.(type) {
	case json.Number:
		value = x.String()
	case string:
		value = x
	case float64:
		value = fmt.Sprint(x)
	default:
		return nil
	}
	n := decimal(value)
	if n == nil || n.Sign() < 0 || !n.IsInt() || !n.Num().IsInt64() {
		return nil
	}
	out := n.Num().Int64()
	return &out
}
func (s *Server) sourceTask(ctx context.Context, run, id string, snapshot *ResearchSource) (map[string]any, error) {
	src, e := s.source(ctx, id)
	if snapshot != nil {
		src = *snapshot
		e = nil
	}
	if e != nil || !src.Enabled {
		return nil, errors.New("source unavailable")
	}
	raw, ctype, status, e := s.publicFetch(ctx, src.URL)
	if e != nil {
		if researchTransportStopped(ctx, e) {
			return nil, e
		}
		// A failed Provider source is a retained lead, even before a model ID is known.
		candidate, saveErr := s.ensureCandidate(ctx, src.Config.ProviderIDOr(src.URL), "UNKNOWN", run, src.URL)
		if saveErr == nil {
			facts := map[string]any{"retrieval": "FAILED"}
			if status > 0 {
				facts["http_status"] = status
			}
			_, _ = s.recordEvidence(ctx, candidate.ID, "SOURCE_FETCH_FAILURE", src.URL, "Public source retrieval failed; no eligibility facts verified.", false, EvidenceDetails{RunID: run, SourceID: id, Language: src.Language, Normalized: facts, Confidence: 0})
			if oneOf(candidate.State, "DISCOVERED", "VERIFYING", "FAILED") {
				_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_candidates SET state='FAILED',updated_at=? WHERE id=?", now(), candidate.ID)
			}
			candidate.SourceID = id
			candidate.LastChecked = now()
			candidate.Reason = "Public source retrieval failed; last-good evidence retained."
			_ = s.saveCandidateDetails(ctx, candidate.ID, candidate.CandidateDetails)
		}
		return nil, e
	}
	if src.Kind == "HTML" {
		candidate, e := s.ensureCandidate(ctx, src.Config.ProviderIDOr(src.URL), "UNKNOWN", run, src.URL)
		if e != nil {
			return nil, e
		}
		return s.inspectCandidateBody(ctx, run, candidate, src.URL, raw, src.ID, src.Language)
	}
	var data any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var extra any
	if decoder.Decode(&data) != nil || decoder.Decode(&extra) != io.EOF || !strings.Contains(strings.ToLower(ctype), "json") {
		return nil, errors.New("source is not structured JSON")
	}
	items := pointer(data, src.Config.ItemsPointer)
	rows, ok := items.([]any)
	if !ok {
		rows = []any{items}
	}
	if len(rows) > 200 || items == nil {
		return nil, errors.New("source item budget or shape")
	}
	ids := []string{}
	for _, row := range rows {
		facts := normalizeFacts(row, src.Config.Fields)
		model := str(facts["model_id"])
		if !validID(model) {
			continue
		}
		candidate, e := s.ensureCandidate(ctx, src.Config.ProviderIDOr(src.URL), model, run, src.URL)
		if e != nil {
			return nil, e
		}
		details := EvidenceDetails{RunID: run, SourceID: id, Language: src.Language, ContentHash: contentHash(raw), ParserVersion: "json-pointer-v1", Normalized: facts, Confidence: 0.3}
		kind := "EXTERNAL_CLAIM"
		verified := false
		if src.Config.Official {
			kind = "OFFICIAL_STRUCTURED_CLAIM"
			verified = true
			details.Confidence = 0.75
		}
		claim, _ := json.Marshal(facts)
		if len(claim) > 4000 {
			continue
		}
		if _, e = s.recordEvidence(ctx, candidate.ID, kind, src.URL, string(claim), verified, details); e != nil {
			return nil, e
		}
		if src.Config.Official {
			cycle := str(facts["recurring"])
			_, hasFree := facts["free"].(bool)
			if hasFree && oneOf(cycle, "UNKNOWN", "DAILY_RESET", "ROLLING_24H", "WEEKLY", "MONTHLY", "ONE_TIME_TRIAL", "PUBLIC_POOL") {
				s.recordEvidence(ctx, candidate.ID, "OFFICIAL_RECURRING_POLICY", src.URL, string(claim), true, details)
			}
			if quotaInteger(facts["rpd"]) != nil && quotaInteger(facts["tpd"]) != nil {
				s.recordEvidence(ctx, candidate.ID, "OFFICIAL_RECURRING_QUOTA", src.URL, string(claim), true, details)
			}
		}
		candidate.Restrictions = []string{}
		for _, v := range list(facts["restrictions"]) {
			if text := excerpt(str(v), 120); text != "" && len(candidate.Restrictions) < 16 {
				candidate.Restrictions = append(candidate.Restrictions, text)
			}
		}
		candidate.SourceID = id
		candidate.Language = src.Language
		candidate.LastChecked = now()
		candidate.Confidence = details.Confidence
		if base := str(facts["base_url"]); validateHTTPS(base) {
			candidate.BaseURL = base
		}
		if a := str(facts["access_method"]); oneOf(a, "INFERENCE_READY", "CODING_AGENT_ONLY", "WEB_ONLY", "CUSTOM", "UNKNOWN") {
			candidate.AccessMethod = a
		}
		if auth := str(facts["auth_kind"]); oneOf(auth, "NONE", "API_KEY", "PAT", "CUSTOM", "UNKNOWN") {
			candidate.AuthKind = auth
		}
		if candidate.RPD == nil {
			candidate.RPD = quotaInteger(facts["rpd"])
		}
		if candidate.TPD == nil {
			candidate.TPD = quotaInteger(facts["tpd"])
		}
		if candidate.Recurring == "UNKNOWN" && str(facts["recurring"]) != "" {
			candidate.Recurring = str(facts["recurring"])
		}
		_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_candidates SET state='VERIFYING',rpd=?,tpd=?,recurring=?,updated_at=? WHERE id=?", candidate.RPD, candidate.TPD, candidate.Recurring, now(), candidate.ID)
		s.saveCandidateDetails(ctx, candidate.ID, candidate.CandidateDetails)
		s.reconcileCandidate(ctx, candidate.ID)
		ids = append(ids, candidate.ID)
	}
	return map[string]any{"candidate_ids": ids, "scanned_count": len(rows), "source_id": id}, nil
}
func (c SourceConfig) ProviderIDOr(raw string) string {
	if c.ProviderID != "" {
		return c.ProviderID
	}
	return providerFromURL(raw)
}
func (s *Server) inspectCandidateBody(ctx context.Context, run string, c Candidate, rawURL string, raw []byte, source, language string) (map[string]any, error) {
	text, links, paths := inspectHTML(raw, rawURL)
	evidence := EvidenceDetails{RunID: run, SourceID: source, Language: language, ContentHash: contentHash(raw), ParserVersion: "public-html-v1", Normalized: map[string]any{"text": text, "links": links, "endpoint_paths": paths}, Confidence: 0.25}
	_, e := s.recordEvidence(ctx, c.ID, "PUBLIC_WEB_CLAIM", rawURL, excerpt(text, 3800), false, evidence)
	if e != nil {
		return nil, e
	}
	c.Website = rawURL
	c.LastChecked = now()
	c.SourceID = source
	c.Language = language
	s.saveCandidateDetails(ctx, c.ID, c.CandidateDetails)
	// Pages and scripts are evidence, never executable instructions. At most two
	// same-origin documentation/pricing/model links are followed per inspection.
	base, _ := url.Parse(rawURL)
	followed := 0
	for _, link := range links {
		u, _ := url.Parse(link)
		if followed >= 2 {
			break
		}
		if u.Host == base.Host && (strings.Contains(strings.ToLower(u.Path), "pricing") || strings.Contains(strings.ToLower(u.Path), "docs") || strings.Contains(strings.ToLower(u.Path), "model")) && link != rawURL {
			if e = s.enqueueResearch(ctx, run, "INSPECT_LINK", "link:"+c.ID+":"+link, taskInput{CandidateID: c.ID, URL: link, SourceID: source, Language: language}); e != nil {
				return nil, e
			}
			followed++
		}
	}
	s.reconcileCandidate(ctx, c.ID)
	return map[string]any{"candidate_ids": []string{c.ID}, "endpoint_paths": paths, "links": links, "characters": len(text)}, nil
}
func (s *Server) searchTask(ctx context.Context, run string, v taskInput) (map[string]any, error) {
	task, _ := ctx.Value(researchTaskBudgetKey{}).(string)
	if task == "" {
		return nil, errors.New("Brave search requires a local research task")
	}
	var previous string
	if err := s.Store.DB.QueryRowContext(ctx, "SELECT result_json FROM research_tasks WHERE id=? AND run_id=? AND kind='SEARCH'", task, run).Scan(&previous); err != nil {
		return nil, err
	}
	return s.executeBraveResearchTask(ctx, ResearchTask{ID: task, RunID: run, Kind: "SEARCH", Result: json.RawMessage(previous)}, v)
}
func (s *Server) executeResearchTask(ctx context.Context, run, kind string, input taskInput) (map[string]any, error) {
	switch kind {
	case "LMSPEED_SEEDS":
		rows, e := s.Store.DB.QueryContext(ctx, "SELECT canonical_id,facts_json,observed_at FROM external_models WHERE source='LMSPEED' ORDER BY rowid DESC LIMIT 20")
		if e != nil {
			return nil, e
		}
		hints := []map[string]any{}
		for rows.Next() {
			var model, raw string
			var at int64
			rows.Scan(&model, &raw, &at)
			var facts map[string]any
			json.Unmarshal([]byte(raw), &facts)
			hints = append(hints, map[string]any{"model_id": model, "source": "LMSPEED", "observed_at": at, "facts": facts, "provider_status": "UNRESOLVED"})
		}
		rows.Close()
		var config string
		s.Store.DB.QueryRowContext(ctx, "SELECT config_json FROM research_run_options WHERE run_id=?", run).Scan(&config)
		var options ResearchOptions
		json.Unmarshal([]byte(config), &options)
		if options.Search {
			for _, h := range hints[:min(3, len(hints))] {
				model := str(h["model_id"])
				s.enqueueResearch(ctx, run, "SEARCH", "lms-search:"+model, taskInput{Language: "en", Query: model + " recurring free API quota provider", SearchPages: options.SearchPages, SearchCountry: options.SearchCountry})
			}
		}
		return map[string]any{"seed_hints": hints, "unresolved_count": len(hints)}, nil

	case "SOURCE":
		return s.sourceTask(ctx, run, input.SourceID, input.SourceSnapshot)
	case "SEARCH":
		return s.searchTask(ctx, run, input)
	case "INSPECT", "INSPECT_LINK":
		c, e := s.candidate(ctx, input.CandidateID)
		if e != nil {
			return nil, e
		}
		raw, _, _, e := s.publicFetch(ctx, input.URL)
		if e != nil {
			if researchTransportStopped(ctx, e) {
				return nil, e
			}
			c.Reason = "website fetch failed; prior evidence retained"
			c.LastChecked = now()
			s.saveCandidateDetails(ctx, c.ID, c.CandidateDetails)
			_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_candidates SET state=CASE WHEN state='VERIFIED' THEN 'STALE' ELSE 'FAILED' END,updated_at=? WHERE id=?", now(), c.ID)
			return nil, e
		}
		return s.inspectCandidateBody(ctx, run, c, input.URL, raw, input.SourceID, input.Language)
	case "CATALOG":
		if input.Selection == nil {
			return nil, errors.New("missing selection")
		}
		v := *input.Selection
		snapshot, e := s.discoverSnapshot(ctx, syncRequest{ProviderID: v.ProviderID, AccountID: v.AccountID, CredentialID: v.CredentialID})
		if e != nil {
			return nil, e
		}
		p, cfg := snapshot.Provider, snapshot.Config
		site := cfg.Website
		if site == "" {
			site = p.BaseURL
		}
		ids := []string{}
		for _, entry := range snapshot.Entries {
			c, e := s.ensureCandidate(ctx, p.ID, entry.ID, run, site)
			if e != nil {
				return nil, e
			}
			modelScope := v
			modelScope.ModelID = entry.ID
			_, e = s.recordEvidence(ctx, c.ID, "PROVIDER_MODEL_CATALOG", p.BaseURL, "model exists in selected credential catalog", true, EvidenceDetails{RunID: run, SourceID: cfg.Adapter, Normalized: map[string]any{"selection": modelScope, "model_id": entry.ID, "origin": p.BaseURL, "credential_version": p.CredentialVersion}, Confidence: 0.65, ParserVersion: "catalog-v2-exact-response"})
			if e != nil {
				return nil, e
			}
			c.BaseURL = p.BaseURL
			c.AccessMethod = "INFERENCE_READY"
			c.AuthKind = cfg.AuthMode
			c.LastChecked = now()
			if e = s.saveCandidateDetails(ctx, c.ID, c.CandidateDetails); e != nil {
				return nil, e
			}
			s.reconcileCandidate(ctx, c.ID)
			ids = append(ids, c.ID)
		}
		facts := s.enrichWithoutCatalog(ctx, v)
		return map[string]any{"candidate_ids": ids, "catalog_count": len(snapshot.Entries), "intelligence": facts}, nil

	case "MINIMAL_INFERENCE":
		if input.Selection == nil {
			return nil, errors.New("missing selection")
		}
		v := *input.Selection
		p, e := s.provider(ctx, v.ProviderID)
		if e != nil {
			return nil, e
		}
		selected, _, e := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
		if e != nil || !s.validateSelection(ctx, v) || !s.groupBound(ctx, v) {
			return nil, errors.New("current inference scope unavailable")
		}
		c, e := s.ensureCandidate(ctx, p.ID, v.ModelID, run, selected.BaseURL)
		if e != nil {
			return nil, e
		}
		var sampleID, outcome, details string
		var at int64
		e = s.Store.DB.QueryRowContext(ctx, "SELECT id,outcome,details_json,created_at FROM telemetry WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=? AND credential_version=? AND outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED') AND created_at>=? ORDER BY rowid DESC LIMIT 1", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, selected.CredentialVersion, now()-int64(24*time.Hour/time.Millisecond)).Scan(&sampleID, &outcome, &details, &at)
		reused := e == nil
		if !reused {
			id, e := s.createTestSession(ctx, v)
			if e != nil {
				return nil, e
			}
			result, _ := s.execute(ctx, inferRequest{SessionID: id, Prompt: "Reply OK.", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			outcome = result.Outcome
			sampleID = str(result.Details["sample_id"])
			raw, _ := json.Marshal(result.Details)
			details = string(raw)
			at = now()
			if outcome == "RESEARCH_BUDGET_EXHAUSTED" {
				if limit, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError); ok {
					return nil, limit
				}
				if limit, ok := result.Details["research_inference_budget"].(*researchInferenceBudgetError); ok {
					return nil, limit
				}
				return nil, errors.New("research budget exhausted")
			}
		}
		var parsed map[string]any
		json.Unmarshal([]byte(details), &parsed)
		normalized := map[string]any{"selection": v, "outcome": outcome, "observed_at": at, "reused_local_traffic": reused, "probe_version": "minimal-inference-v1", "response_model_id": parsed["response_model_id"], "authenticity": "BEHAVIOR_OBSERVED_IDENTITY_NOT_PROVEN"}
		// Analyze only saved traffic; this caller never adds an authenticity probe
		// or upgrades eligibility/identity because a request happened to succeed.
		// Each actual task attempt and observation keeps its own immutable report.
		// A successful retry must not replay the first local failure's empty report.
		taskID, _ := ctx.Value(researchTaskBudgetKey{}).(string)
		attempt := 0
		var reportErr error
		if taskID != "" {
			reportErr = s.Store.DB.QueryRowContext(ctx, "SELECT attempts FROM research_tasks WHERE id=? AND run_id=?", taskID, run).Scan(&attempt)
		}
		reportID := "auth-" + connectorHash(struct {
			Run, Task, Sample, Outcome, Suite string
			Selection                         Selection
			Attempt                           int
		}{run, taskID, sampleID, outcome, authenticitySuiteVersion, v, attempt})
		var report authenticityReport
		if reportErr == nil {
			report, _, reportErr = s.analyzeAuthenticity(ctx, authenticityAnalysisRequest{ID: reportID, Selection: v})
		}
		if reportErr == nil {
			normalized["authenticity_report_id"] = report.ID
			normalized["authenticity_suite_version"] = report.Suite
			normalized["authenticity_observation_status"] = report.Status
			normalized["identity_status"] = report.Identity
		} else {
			normalized["authenticity_suite_status"] = "UNAVAILABLE"
		}
		_, e = s.recordEvidence(ctx, c.ID, "SCOPED_INFERENCE", selected.BaseURL, "scoped inference outcome: "+outcome, outcome == "SUCCESS", EvidenceDetails{RunID: run, Normalized: normalized, Confidence: 0.65, ParserVersion: "minimal-inference-v1"})
		s.reconcileCandidate(ctx, c.ID)
		if e != nil {
			return nil, e
		}
		if outcome != "SUCCESS" {
			return nil, errors.New("minimal inference failed: " + outcome)
		}
		return normalized, nil
	}
	return nil, errors.New("unsupported research task")
}
func (s *Server) enrichWithoutCatalog(ctx context.Context, v Selection) map[string]string {
	p, key, e := s.endpointAuth(ctx, v.ProviderID, v.AccountID, v.CredentialID)
	if e != nil {
		return map[string]string{"scope": "UNAVAILABLE"}
	}
	cfg := s.config(ctx, p)
	adapter := adapterRegistry[cfg.Adapter]
	if adapter.Enrich == nil {
		return map[string]string{"intelligence": "UNSUPPORTED"}
	}
	if e = adapter.Enrich(s, ctx, v, p, cfg, key); e != nil {
		return map[string]string{"intelligence": "PARTIAL"}
	}
	return map[string]string{"intelligence": "SUCCESS"}
}
func (s *Server) verifiedEvidence(ctx context.Context, id, kind string) (map[string]any, bool) {
	var raw string
	var at int64
	e := s.Store.DB.QueryRowContext(ctx, "SELECT COALESCE(d.details_json,'{}'),e.observed_at FROM research_evidence e LEFT JOIN research_evidence_details d ON d.evidence_id=e.id WHERE e.candidate_id=? AND e.source_type=? AND e.verified=1 AND e.observed_at>=? ORDER BY e.rowid DESC LIMIT 1", id, kind, now()-int64(7*24*time.Hour/time.Millisecond)).Scan(&raw, &at)
	if e != nil {
		return nil, false
	}
	var v EvidenceDetails
	if json.Unmarshal([]byte(raw), &v) != nil {
		return nil, false
	}
	if v.Normalized == nil {
		v.Normalized = map[string]any{}
	}
	v.Normalized["_valid_until"] = at + int64(7*24*time.Hour/time.Millisecond)
	return v.Normalized, true
}
func (s *Server) assessCandidate(ctx context.Context, c Candidate) (Candidate, Eligibility) {
	top4 := s.aaTop4(ctx, c)
	policy, verifiedPolicy := s.verifiedEvidence(ctx, c.ID, "OFFICIAL_RECURRING_POLICY")
	quota, verifiedQuota := s.verifiedEvidence(ctx, c.ID, "OFFICIAL_RECURRING_QUOTA")
	manual, hasManual := s.verifiedEvidence(ctx, c.ID, "MANUAL_REVIEW")
	official, _ := s.verifiedEvidence(ctx, c.ID, "OFFICIAL_STRUCTURED_CLAIM")
	if hasManual {
		if t := str(manual["taiwan"]); t != "" {
			c.Taiwan = t
		}
		c.Strength = str(manual["strength"])
		c.AccessMethod = str(manual["access_method"])
	} else {
		c.Taiwan = "UNKNOWN"
		if t := str(official["taiwan"]); oneOf(t, "YES", "NO") {
			c.Taiwan = t
		}
		c.Strength = "UNKNOWN"
		c.AccessMethod = str(official["access_method"])
		if _, ok := s.verifiedEvidence(ctx, c.ID, "PROVIDER_MODEL_CATALOG"); ok {
			c.AccessMethod = "INFERENCE_READY"
		}
	}
	if hasManual && c.Strength == "WEAK" {
		return c, Eligibility{c.ID, "GREY_ZONE", "manual strength review is weak"}
	}
	if top4 || s.aaStrong(ctx, c) {
		c.Strength = "STRONG"
	}
	if verifiedPolicy {
		c.Recurring = str(policy["recurring"])
		if c.Recurring == "ONE_TIME_TRIAL" {
			return c, Eligibility{c.ID, "REJECTED", "one-time trial is not recurring free"}
		}
		if free, ok := policy["free"].(bool); ok && !free {
			return c, Eligibility{c.ID, "REJECTED", "official source does not currently claim free access"}
		}
	}
	if verifiedQuota {
		c.RPD = quotaInteger(quota["rpd"])
		c.TPD = quotaInteger(quota["tpd"])
	}
	if !hasManual && len(c.Restrictions) > 0 {
		return c, Eligibility{c.ID, "GREY_ZONE", "Taiwan access restrictions require review"}
	}
	result := EvaluateCandidate(c, verifiedPolicy && (top4 || verifiedQuota), top4)
	c.EligibilityValidUntil = 0
	if expiry := quotaInteger(policy["_valid_until"]); expiry != nil {
		c.EligibilityValidUntil = *expiry
	}
	for _, proof := range []map[string]any{quota, manual, official} {
		if expiry := quotaInteger(proof["_valid_until"]); expiry != nil && c.EligibilityValidUntil > 0 {
			c.EligibilityValidUntil = min(c.EligibilityValidUntil, *expiry)
		}
	}
	if top4 || s.aaStrong(ctx, c) {
		var expiry int64
		s.Store.DB.QueryRowContext(ctx, "SELECT valid_until FROM aa_snapshots WHERE status='VALID' ORDER BY rowid DESC LIMIT 1").Scan(&expiry)
		c.EligibilityValidUntil = min(c.EligibilityValidUntil, expiry)
	}
	if result.Classification == "ELIGIBLE" && c.AccessMethod != "INFERENCE_READY" {
		result.Classification = "GREY_ZONE"
		result.Reason = "direct inference API readiness unverified"
	}
	return c, result
}

func (s *Server) candidateEligibility(ctx context.Context, c Candidate) Eligibility {
	_, result := s.assessCandidate(ctx, c)
	return result
}
func (s *Server) aaStrong(ctx context.Context, c Candidate) bool {
	score, _, valid := s.aaModelScore(ctx, c)
	if !valid {
		return false
	}
	var floor float64
	err := s.Store.DB.QueryRowContext(ctx, `SELECT m.score FROM aa_models m JOIN aa_snapshots a ON a.id=m.snapshot_id WHERE a.id=(SELECT id FROM aa_snapshots WHERE status='VALID' ORDER BY rowid DESC LIMIT 1) AND a.valid_until>? AND (lower(m.slug)='deepseek-v4.1-flash' OR lower(m.name)='deepseek v4.1 flash') ORDER BY m.rank LIMIT 1`, now()).Scan(&floor)
	return err == nil && score >= floor
}
func (s *Server) reconcileCandidate(ctx context.Context, id string) {
	c, e := s.candidate(ctx, id)
	if e != nil {
		return
	}
	c, result := s.assessCandidate(ctx, c)
	state := "HIGH_VALUE_GREY_ZONE"
	if result.Classification == "ELIGIBLE" {
		state = "VERIFIED"
		if c.State == "STALE" {
			state = "STALE"
		}
	}
	if result.Classification == "REJECTED" || c.Recurring == "ONE_TIME_TRIAL" {
		state = "REJECTED"
	}
	c.Reason = result.Reason
	if c.Recurring == "ONE_TIME_TRIAL" {
		c.Reason = "one-time trial is not recurring free"
	}
	_, _ = s.Store.DB.ExecContext(ctx, "UPDATE research_candidates SET state=?,rpd=?,tpd=?,taiwan=?,recurring=?,strength=?,updated_at=? WHERE id=?", state, c.RPD, c.TPD, c.Taiwan, c.Recurring, c.Strength, now(), id)
	s.saveCandidateDetails(ctx, id, c.CandidateDetails)
}
func (s *Server) reconcileAllCandidates(ctx context.Context) {
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT id FROM research_candidates ORDER BY rowid LIMIT 10000")
	if e != nil {
		return
	}
	ids := []string{}
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	for _, id := range ids {
		if ctx.Err() != nil {
			return
		}
		s.reconcileCandidate(ctx, id)
	}
}

func (s *Server) expireResearchFacts(ctx context.Context) {
	_, _ = s.Store.DB.ExecContext(ctx, `UPDATE research_candidates SET state='STALE' WHERE state='VERIFIED' AND id IN (SELECT candidate_id FROM research_candidate_details WHERE CAST(json_extract(details_json,'$.eligibility_valid_until') AS INTEGER)>0 AND CAST(json_extract(details_json,'$.eligibility_valid_until') AS INTEGER)<=?)`, now())
}
