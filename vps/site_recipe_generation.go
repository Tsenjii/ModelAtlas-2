package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"time"
)

const siteRecipeParser = "public-metadata-recipe-v1"

var errSiteRecipeReview = errors.New("local recipe evidence or current review required")

func siteRecipeStorageFailure(e error) bool {
	var sqliteFailure interface{ Code() int }
	return errors.As(e, &sqliteFailure) || errors.Is(e, context.Canceled) || errors.Is(e, context.DeadlineExceeded)
}

func siteRecipeProblem(w http.ResponseWriter, e error, review string) {
	if siteRecipeStorageFailure(e) {
		http.Error(w, "recipe evidence storage unavailable; read saved state before retrying", 503)
		return
	}
	http.Error(w, review, 409)
}

type siteRecipeRequest struct {
	ID           string   `json:"id"`
	DiscoveryID  string   `json:"discovery_id"`
	ConnectorIDs []string `json:"connector_ids"`
}
type siteRecipeRule struct {
	VersionID  string          `json:"version_id"`
	Name       string          `json:"name"`
	Kind       string          `json:"kind"`
	ConfigHash string          `json:"config_hash"`
	Config     ConnectorConfig `json:"config"`
	SourceURL  string          `json:"source_url"`
	SourceHash string          `json:"source_sha256"`
	BeforeID   string          `json:"before_id,omitempty"`
}
type siteRecipeDefinition struct {
	Parser         string               `json:"parser_version"`
	InheritedID    string               `json:"inherited_recipe_id,omitempty"`
	Conflicts      []string             `json:"conflicting_kinds"`
	FamilyEvidence []siteFamilyEvidence `json:"family_evidence,omitempty"`
	Selection      Selection            `json:"selection"`
	Origin         string               `json:"origin"`
	Family         string               `json:"family_hint"`
	Version        string               `json:"version_hint"`
	Rules          []siteRecipeRule     `json:"rules"`
	Unknown        []string             `json:"unknown_fields"`
	Limitations    []string             `json:"limitations"`
}
type siteRecipeDocument struct {
	DiscoveryID      string               `json:"discovery_id"`
	Definition       siteRecipeDefinition `json:"definition"`
	RequestHash      string               `json:"request_hash"`
	DefinitionHash   string               `json:"definition_hash"`
	SettingsHash     string               `json:"settings_hash"`
	ContextHash      string               `json:"context_hash"`
	SourceReportHash string               `json:"source_report_hash"`
	At               int64                `json:"at"`
	ExpiresAt        int64                `json:"expires_at"`
}
type siteRecipeRuleState struct {
	VersionID string `json:"version_id"`
	Kind      string `json:"kind"`
	State     string `json:"state"`
	Reason    string `json:"reason"`
	TestedAt  int64  `json:"tested_at"`
	HTTP      *int   `json:"last_test_http_requests"`
}
type siteRecipeEvaluation struct {
	VersionID      string                `json:"version_id"`
	Status         string                `json:"status"`
	ContextHash    string                `json:"context_hash"`
	DefinitionHash string                `json:"definition_hash"`
	Rules          []siteRecipeRuleState `json:"rules"`
	TestCalls      int                   `json:"maximum_new_public_get_requests"`
	Inference      int                   `json:"model_inference_requests"`
	Cost           *string               `json:"total_monetary_cost"`
	Reason         string                `json:"reason"`
	At             int64                 `json:"at"`
}

func recipeRequestHash(in siteRecipeRequest) string {
	ids := append([]string(nil), in.ConnectorIDs...)
	sort.Strings(ids)
	return snapshotHash(map[string]any{"discovery_id": in.DiscoveryID, "connector_ids": ids})
}

func readSiteRecipe(ctx context.Context, q queryRows, id string) (Recipe, *siteRecipeDocument, error) {
	var v Recipe
	var raw, proof, definition string
	e := q.QueryRowContext(ctx, `SELECT v.id,v.provider_id,v.version,v.recipe_json,v.reason,g.proof_json,g.definition_json FROM recipe_versions v JOIN recipe_generated_versions g ON g.version_id=v.id WHERE v.id=?`, id).Scan(&v.ID, &v.ProviderID, &v.Version, &raw, &v.Reason, &proof, &definition)
	if e != nil {
		return v, nil, e
	}
	var p siteRecipeDocument
	var d siteRecipeDefinition
	if json.Unmarshal([]byte(raw), &v.Settings) != nil || json.Unmarshal([]byte(proof), &p) != nil || json.Unmarshal([]byte(definition), &d) != nil || !safeRecipe(v.Settings) || str(v.Settings["generated_recipe_id"]) != v.ID || p.DefinitionHash != connectorHash(d) || connectorHash(p.Definition) != p.DefinitionHash || p.SettingsHash != snapshotHash(v.Settings) || p.Definition.Parser != siteRecipeParser || p.Definition.Selection.ProviderID != v.ProviderID || len(d.Rules) < 1 || len(d.Rules) > 6 || !validateHTTPS(d.Origin) {
		return v, nil, errSiteRecipeReview
	}
	v.SiteDraft = &p
	v.Status = "DRAFT"
	return v, &p, nil
}

func siteRecipeCurrent(ctx context.Context, q queryRows, p *siteRecipeDocument) error {
	if now() < p.At || now() > p.ExpiresAt || p.ExpiresAt-p.At > int64(24*time.Hour/time.Millisecond) {
		return errSiteRecipeReview
	}
	return siteRecipeContextCurrent(ctx, q, p)
}

// Draft admission expires; an already approved reader continues to obtain new
// observations with its own parser, units and freshness checks. Its scope and
// immutable source evidence must still match on every use.
func siteRecipeContextCurrent(ctx context.Context, q queryRows, p *siteRecipeDocument) error {
	if !connectorMemberCurrent(ctx, q, p.Definition.Selection) {
		return errSiteRecipeReview
	}
	bound, e := boundProvider(ctx, q, p.Definition.Selection.ProviderID, p.Definition.Selection.AccountID, p.Definition.Selection.CredentialID)
	if e != nil {
		return e
	}
	if bound.BaseURL != p.Definition.Origin {
		return errSiteRecipeReview
	}
	hash, e := connectorContext(ctx, q, ConnectorConfig{Role: "NONE"}, p.Definition.Selection)
	if e != nil {
		return e
	}
	if hash != p.ContextHash {
		return errSiteRecipeReview
	}
	var raw string
	if e = q.QueryRowContext(ctx, "SELECT report_json FROM connector_discoveries WHERE id=?", p.DiscoveryID).Scan(&raw); e != nil {
		return e
	}
	var report connectorDiscoveryReport
	if json.Unmarshal([]byte(raw), &report) != nil || connectorHash(report) != p.SourceReportHash || report.Status != "DRAFTS_READY" || connectorHash(report.Selection) != connectorHash(p.Definition.Selection) {
		return errSiteRecipeReview
	}
	return nil
}

func readSiteRecipeRule(ctx context.Context, q queryRows, ref siteRecipeRule, p *siteRecipeDocument) (ConnectorVersion, error) {
	var c ConnectorVersion
	var raw, testRaw, sourceProof, discovery string
	e := q.QueryRowContext(ctx, `SELECT v.id,v.provider_id,v.name,v.config_json,v.status,v.test_json,v.tested_at,g.discovery_id,g.proof_json FROM connector_versions v JOIN connector_generated_versions g ON g.version_id=v.id WHERE v.id=?`, ref.VersionID).Scan(&c.ID, &c.ProviderID, &c.Name, &raw, &c.Status, &testRaw, &c.TestedAt, &discovery, &sourceProof)
	if e != nil {
		return c, e
	}

	var observed connectorDiscoveryProof
	if json.Unmarshal([]byte(raw), &c.Config) != nil || json.Unmarshal([]byte(testRaw), &c.Test) != nil || json.Unmarshal([]byte(sourceProof), &observed) != nil || !safeConnector(c.Config) || c.ProviderID != p.Definition.Selection.ProviderID || c.Name != ref.Name || c.Config.Kind != ref.Kind || c.Config.Role != "NONE" || c.Config.PATID != "" || connectorHash(c.Config) != ref.ConfigHash || connectorHash(ref.Config) != ref.ConfigHash || discovery != p.DiscoveryID || observed.SourceURL != ref.SourceURL || observed.SourceHash != ref.SourceHash || connectorHash(observed.Selection) != connectorHash(p.Definition.Selection) {
		return c, errSiteRecipeReview
	}
	if e = validateConnectorFixtureQuery(ctx, q, c.ID, c.Config, p.Definition.Selection, p.ContextHash); e != nil {
		return c, e
	}
	return c, nil
}

func evaluateSiteRecipe(ctx context.Context, tx *sql.Tx, v Recipe, p *siteRecipeDocument) (siteRecipeEvaluation, error) {
	out := siteRecipeEvaluation{VersionID: v.ID, Status: "TEST_NEEDED", ContextHash: p.ContextHash, DefinitionHash: p.DefinitionHash, Rules: []siteRecipeRuleState{}, Reason: "逐項實測選取的公開規則，再彙整本機回報。此彙整本身不發HTTP或模型推論；完整貨幣成本Unknown。", At: now()}
	check := siteRecipeCurrent
	if v.Active {
		check = siteRecipeContextCurrent
	}
	if e := check(ctx, tx, p); e != nil {
		return out, e
	}
	ready := true
	for _, ref := range p.Definition.Rules {
		c, e := readSiteRecipeRule(ctx, tx, ref, p)
		if e != nil {
			return out, e
		}
		state := siteRecipeRuleState{VersionID: c.ID, Kind: c.Config.Kind, State: "TEST_NEEDED", Reason: "尚無目前scope與版本的本機實測。"}
		if n := quotaInteger(json.Number(numberText(c.Test["http_requests"]))); n != nil && *n >= 0 && *n <= 1 {
			value := int(*n)
			state.HTTP = &value
		}

		var contextHash, configHash string
		var tested int64
		e = tx.QueryRowContext(ctx, "SELECT context_hash,config_hash,tested_at FROM connector_test_receipts WHERE version_id=?", c.ID).Scan(&contextHash, &configHash, &tested)
		if e != nil && e != sql.ErrNoRows {
			return out, e
		}
		ttl := int64(24 * time.Hour / time.Millisecond)
		if c.Config.Freshness > 0 {
			ttl = min(ttl, c.Config.Freshness*1000)
		}
		if e == nil && c.Status == "VALIDATED" && c.TestedAt != nil && *c.TestedAt == tested && tested <= now() && now()-tested <= ttl && contextHash == p.ContextHash && configHash == ref.ConfigHash {
			state.State = "READY"
			state.Reason = "已保存同一版本／scope的實際公開GET與本機fixture驗證。"
			state.TestedAt = tested
		} else {
			ready = false
			out.TestCalls++
		}
		out.Rules = append(out.Rules, state)
	}
	if ready {
		out.Status = "READY_FOR_REVIEW"
		out.Reason = "選取的公開規則都有目前scope的本機實測。Review明確確認後才整組啟用；不證明身份、免費或私人權限。"
	}
	return out, nil
}

func (s *Server) generateSiteRecipe(w http.ResponseWriter, r *http.Request) {
	var input siteRecipeRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	if !validID(input.ID) || !validID(input.DiscoveryID) || len(input.ConnectorIDs) < 1 || len(input.ConnectorIDs) > 6 {
		http.Error(w, "select one to six public rules from a saved discovery", 422)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "recipe generation unavailable", 503)
		return
	}
	defer tx.Rollback()
	old, proof, e := readSiteRecipe(r.Context(), tx, input.ID)
	if e == nil {
		if proof.RequestHash != recipeRequestHash(input) {
			http.Error(w, "recipe id belongs to another proposal", 409)
			return
		}
		jsonOK(w, 200, old)
		return
	}
	if e != sql.ErrNoRows {
		http.Error(w, "recipe evidence unavailable", 503)
		return
	}
	var raw string
	if e = tx.QueryRowContext(r.Context(), "SELECT report_json FROM connector_discoveries WHERE id=?", input.DiscoveryID).Scan(&raw); e != nil {
		http.Error(w, "saved local discovery required", 422)
		return
	}
	var report connectorDiscoveryReport
	if json.Unmarshal([]byte(raw), &report) != nil || report.Status != "DRAFTS_READY" || now() < report.ObservedAt || now()-report.ObservedAt > int64(24*time.Hour/time.Millisecond) || !connectorMemberCurrent(r.Context(), tx, report.Selection) {
		http.Error(w, "current local public evidence required", 409)
		return
	}
	hash, e := connectorContext(r.Context(), tx, ConnectorConfig{Role: "NONE"}, report.Selection)
	if e != nil {
		http.Error(w, "recipe scope unavailable", 409)
		return
	}
	var inheritedID string
	err := tx.QueryRowContext(r.Context(), "SELECT v.id FROM recipe_active a JOIN recipe_versions v ON v.provider_id=a.provider_id AND v.version=a.version WHERE a.provider_id=?", report.Selection.ProviderID).Scan(&inheritedID)
	if err != nil && err != sql.ErrNoRows {
		http.Error(w, "current recipe unavailable", 503)
		return
	}
	inherited, problem := localRecipeSettings(r.Context(), tx, Provider{ID: report.Selection.ProviderID})
	if problem != "" {
		http.Error(w, "review or restore the current recipe before generating another", 409)
		return
	}
	definition := siteRecipeDefinition{Parser: siteRecipeParser, InheritedID: inheritedID, Conflicts: report.Conflicts, FamilyEvidence: report.FamilyEvidence, Selection: report.Selection, Origin: report.BaseURL, Family: report.Family, Version: report.Version, Rules: []siteRecipeRule{}, Unknown: report.Unknown, Limitations: []string{"只包含這次選取且有本機證據的公開metadata規則；各筆單位、scope、freshness與失敗語意沿原宣告式擷取。", "不變更聊天／推論端點、認證方式或Key／PAT；未驗證私人入口、有效扣費、台灣資格、持續免費與模型身份。", "生成只讀本機證據；最多6個獨立公開GET實測由明確操作發起。沒有自動啟用，來源／版本變更保留last-good與可回退歷史。"}}
	ids := append([]string(nil), input.ConnectorIDs...)
	sort.Strings(ids)
	seenIDs := map[string]bool{}
	seenKinds := map[string]bool{}
	for _, id := range ids {
		if !validID(id) || seenIDs[id] || !oneOf(id, report.DraftIDs...) {
			http.Error(w, "rules must be distinct local discovery drafts", 422)
			return
		}
		seenIDs[id] = true
		var ref siteRecipeRule
		var cfg, sourceProof, sourceID string
		e = tx.QueryRowContext(r.Context(), `SELECT v.name,v.config_json,g.discovery_id,g.proof_json FROM connector_versions v JOIN connector_generated_versions g ON g.version_id=v.id WHERE v.id=? AND v.provider_id=?`, id, report.Selection.ProviderID).Scan(&ref.Name, &cfg, &sourceID, &sourceProof)
		var c ConnectorConfig
		var observed connectorDiscoveryProof
		if e != nil || json.Unmarshal([]byte(cfg), &c) != nil || json.Unmarshal([]byte(sourceProof), &observed) != nil || sourceID != input.DiscoveryID || c.Role != "NONE" || c.PATID != "" || !safeConnector(c) || c.Origin != report.BaseURL || seenKinds[c.Kind] {
			http.Error(w, "select one public draft per data kind; private roles are separate", 422)
			return
		}
		seenKinds[c.Kind] = true
		ref.VersionID = id
		ref.Kind = c.Kind
		ref.Config = c
		ref.ConfigHash = connectorHash(c)
		ref.SourceURL = observed.SourceURL
		ref.SourceHash = observed.SourceHash
		definition.Rules = append(definition.Rules, ref)
	}
	v := Recipe{ID: input.ID, ProviderID: report.Selection.ProviderID, Settings: map[string]any{"generated_recipe_id": input.ID, "notes": "已觀測的公開metadata配方；各來源與未知分開，啟用不代表免費或身份已證明。"}, Reason: "從已保存的公開來源規則建立具體配方草稿；先實測與Review，保留原啟用版本。"}
	for k, x := range inherited {
		if k != "generated_recipe_id" && k != "notes" {
			v.Settings[k] = x
		}
	}
	proof = &siteRecipeDocument{DiscoveryID: input.DiscoveryID, Definition: definition, RequestHash: recipeRequestHash(input), DefinitionHash: connectorHash(definition), SettingsHash: snapshotHash(v.Settings), ContextHash: hash, SourceReportHash: connectorHash(report), At: now(), ExpiresAt: report.ObservedAt + int64(24*time.Hour/time.Millisecond)}
	for _, ref := range definition.Rules {
		if _, e = readSiteRecipeRule(r.Context(), tx, ref, proof); e != nil {
			http.Error(w, "rule evidence or fixture changed; discover again", 409)
			return
		}
	}
	if containsKnownSecret(v, secrets) || containsKnownSecret(proof, secrets) {
		http.Error(w, "raw secrets are not recipe data", 422)
		return
	}
	if e = tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(version),0)+1 FROM recipe_versions WHERE provider_id=?", v.ProviderID).Scan(&v.Version); e != nil {
		http.Error(w, "recipe persistence unavailable", 503)
		return
	}
	settings, _ := json.Marshal(v.Settings)
	pr, _ := json.Marshal(proof)
	def, _ := json.Marshal(definition)
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO recipe_versions VALUES(?,?,?,?,?,?)", v.ID, v.ProviderID, v.Version, string(settings), v.Reason, proof.At); e != nil {
		http.Error(w, "recipe persistence unavailable", 503)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO recipe_generated_versions VALUES(?,?,?,?,?)", v.ID, proof.DiscoveryID, string(pr), string(def), proof.At); e != nil {
		http.Error(w, "recipe evidence persistence unavailable", 503)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "recipe persistence unavailable", 503)
		return
	}
	v.SiteDraft = proof
	v.Status = "DRAFT"
	jsonOK(w, 201, v)
}

func (s *Server) evaluateSiteRecipeHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "recipe evaluation unavailable", 503)
		return
	}
	defer tx.Rollback()
	v, p, e := readSiteRecipe(r.Context(), tx, in.ID)
	if e != nil {
		siteRecipeProblem(w, e, "local recipe evidence required")
		return
	}
	out, e := evaluateSiteRecipe(r.Context(), tx, v, p)
	if e != nil {
		siteRecipeProblem(w, e, "recipe source or scope changed; review new evidence")
		return
	}
	raw, _ := json.Marshal(out)
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO recipe_test_receipts VALUES(?,?,?,?,?) ON CONFLICT(version_id) DO UPDATE SET context_hash=excluded.context_hash,definition_hash=excluded.definition_hash,report_json=excluded.report_json,tested_at=excluded.tested_at", v.ID, p.ContextHash, p.DefinitionHash, string(raw), out.At); e != nil {
		http.Error(w, "recipe evaluation persistence unavailable", 503)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "recipe evaluation persistence unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}
