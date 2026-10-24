package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
)

type siteRecipeReview struct {
	VersionID      string               `json:"version_id"`
	ExpectedID     string               `json:"expected_active_id"`
	Fingerprint    string               `json:"fingerprint"`
	Definition     siteRecipeDefinition `json:"definition"`
	Evaluation     siteRecipeEvaluation `json:"evaluation"`
	Changes        []siteRecipeRule     `json:"changes"`
	Settings       map[string]any       `json:"settings"`
	BeforeSettings map[string]any       `json:"before_settings"`
	MaximumHTTP    int                  `json:"maximum_activation_http_requests"`
	Cost           *string              `json:"total_monetary_cost"`
	Note           string               `json:"note"`
}

func siteRecipeEvaluationHash(e siteRecipeEvaluation) string {
	return connectorHash(map[string]any{"status": e.Status, "context_hash": e.ContextHash, "definition_hash": e.DefinitionHash, "rules": e.Rules})
}

func siteRecipePlan(ctx context.Context, tx *sql.Tx, id string) (siteRecipeReview, error) {
	out := siteRecipeReview{VersionID: id, Changes: []siteRecipeRule{}, Note: "確認只啟用這組已實測的公開metadata規則，沒有新HTTP或模型推論；保留原已確認聊天與目錄設定值；若選取模型清單規則，目錄改用該公開來源，不提升免費、身份或私人權限。完整貨幣成本Unknown。"}
	v, p, e := readSiteRecipe(ctx, tx, id)
	if e != nil {
		return out, e
	}
	out.Definition = p.Definition
	out.Settings = v.Settings
	if e = siteRecipeCurrent(ctx, tx, p); e != nil {
		return out, e
	}
	out.ExpectedID = activeVersion(ctx, tx, "RECIPE", v.ProviderID, "")
	if out.ExpectedID != p.Definition.InheritedID || out.ExpectedID == v.ID {
		return out, errSiteRecipeReview
	}
	before, problem := localRecipeSettings(ctx, tx, Provider{ID: v.ProviderID})
	if problem != "" {
		return out, errSiteRecipeReview
	}
	out.BeforeSettings = before
	evaluation, e := evaluateSiteRecipe(ctx, tx, v, p)
	if e != nil {
		return out, e
	}
	out.Evaluation = evaluation
	var contextHash, definitionHash, raw string
	e = tx.QueryRowContext(ctx, "SELECT context_hash,definition_hash,report_json FROM recipe_test_receipts WHERE version_id=?", id).Scan(&contextHash, &definitionHash, &raw)
	if e != nil {
		return out, e
	}
	var saved siteRecipeEvaluation
	if json.Unmarshal([]byte(raw), &saved) != nil || saved.Status != "READY_FOR_REVIEW" || evaluation.Status != "READY_FOR_REVIEW" || contextHash != p.ContextHash || definitionHash != p.DefinitionHash || siteRecipeEvaluationHash(saved) != siteRecipeEvaluationHash(evaluation) {
		return out, errSiteRecipeReview
	}
	for _, ref := range p.Definition.Rules {
		var prior string
		e = tx.QueryRowContext(ctx, "SELECT version_id FROM connector_active WHERE provider_id=? AND name=?", v.ProviderID, ref.Name).Scan(&prior)
		if e != nil && e != sql.ErrNoRows {
			return out, e
		}
		if ref.Kind == "CATALOG" {
			var other int
			if e = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM connector_active a JOIN connector_versions v ON v.id=a.version_id WHERE a.provider_id=? AND a.name!=? AND json_extract(v.config_json,'$.kind')='CATALOG'`, v.ProviderID, ref.Name).Scan(&other); e != nil {
				return out, e
			}
			if other != 0 {
				return out, errors.New("review the existing primary catalog before replacing it")
			}
		}
		ref.BeforeID = prior
		out.Changes = append(out.Changes, ref)
	}
	out.Fingerprint = connectorHash(map[string]any{"policy": siteRecipeParser, "version_id": id, "definition_hash": p.DefinitionHash, "settings": v.Settings, "before_settings": before, "expected_id": out.ExpectedID, "changes": out.Changes, "actual_tests": siteRecipeEvaluationHash(evaluation)})
	return out, nil
}

func (s *Server) previewSiteRecipe(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "recipe review unavailable", 503)
		return
	}
	defer tx.Rollback()
	out, e := siteRecipePlan(r.Context(), tx, in.ID)
	if e != nil {
		siteRecipeProblem(w, e, "current local rule tests and unchanged recipe baseline required")
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "recipe review unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}

func (s *Server) activateSiteRecipe(w http.ResponseWriter, r *http.Request) {
	var in struct {
		versionActivation
		Fingerprint string `json:"review_fingerprint"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !in.Confirm || in.Fingerprint == "" {
		http.Error(w, "explicit recipe review required", 422)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "recipe activation unavailable", 503)
		return
	}
	defer tx.Rollback()
	out, e := siteRecipePlan(r.Context(), tx, in.ID)
	if e != nil {
		siteRecipeProblem(w, e, "recipe scope or review changed; preview again")
		return
	}
	if out.Fingerprint != in.Fingerprint || out.ExpectedID != in.ExpectedID {
		http.Error(w, "recipe scope or review changed; preview again", 409)
		return
	}
	v, p, e := readSiteRecipe(r.Context(), tx, in.ID)
	if e != nil {
		http.Error(w, "local recipe evidence unavailable", 503)
		return
	}
	action, e := applyVersion(r.Context(), tx, "RECIPE", v.ProviderID, "", v.ID, in.ExpectedID)
	if e != nil {
		http.Error(w, "active recipe changed; refresh first", 409)
		return
	}
	for _, ref := range out.Changes {
		if e = setActiveVersion(r.Context(), tx, "CONNECTOR", v.ProviderID, ref.Name, ref.VersionID); e != nil {
			http.Error(w, "recipe rule activation unavailable", 503)
			return
		}
		raw, _ := json.Marshal(ref.Config)
		if _, e = tx.ExecContext(r.Context(), "INSERT INTO connector_execution_bindings VALUES(?,?) ON CONFLICT(version_id) DO UPDATE SET config_json=excluded.config_json", ref.VersionID, string(raw)); e != nil {
			http.Error(w, "recipe rule binding unavailable", 503)
			return
		}
		if _, e = tx.ExecContext(r.Context(), "INSERT INTO recipe_connector_action_changes VALUES(?,?,?,?,?)", action, v.ProviderID, ref.Name, ref.BeforeID, ref.VersionID); e != nil {
			http.Error(w, "recipe rollback persistence unavailable", 503)
			return
		}
	}
	settings, _ := json.Marshal(v.Settings)
	binding, _ := json.Marshal(map[string]any{"mode": "PUBLIC_METADATA_COMPOSITE", "definition_hash": p.DefinitionHash, "context_hash": p.ContextHash, "actual_tests": siteRecipeEvaluationHash(out.Evaluation)})
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO recipe_execution_bindings VALUES(?,?,?) ON CONFLICT(version_id) DO UPDATE SET settings_json=excluded.settings_json,context_json=excluded.context_json", v.ID, string(settings), string(binding)); e != nil {
		http.Error(w, "recipe execution binding unavailable", 503)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "recipe activation unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "active": true, "action_id": action})
}

func undoSiteRecipeConnectors(ctx context.Context, tx *sql.Tx, action string) error {
	rows, e := tx.QueryContext(ctx, "SELECT provider_id,name,before_id,after_id FROM recipe_connector_action_changes WHERE action_id=? ORDER BY name", action)
	if e != nil {
		return e
	}
	type change struct{ provider, name, before, after string }
	items := []change{}
	for rows.Next() {
		var c change
		if e = rows.Scan(&c.provider, &c.name, &c.before, &c.after); e != nil {
			rows.Close()
			return e
		}
		items = append(items, c)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	if len(items) == 0 {
		return errSiteRecipeReview
	}
	for _, c := range items {
		if activeVersion(ctx, tx, "CONNECTOR", c.provider, c.name) != c.after {
			return errors.New("recipe rules changed after activation; review before Undo")
		}
	}
	for _, c := range items {
		if e = setActiveVersion(ctx, tx, "CONNECTOR", c.provider, c.name, c.before); e != nil {
			return e
		}
	}
	return nil
}

// Generated recipes compose public data readers. Their original exact local
// context remains required before a reader can issue HTTP; other accounts or
// changed keys cannot borrow the recipe's successful tests.
func validateSiteRecipeConnectorUse(ctx context.Context, q queryRows, id string, v Selection) error {
	var recipeID string
	// The public readers remain active when unrelated manual settings or a
	// later partial recipe is selected. Keep each reader's original local group
	// approval instead of borrowing the currently selected recipe's context.
	e := q.QueryRowContext(ctx, `SELECT d.after_id FROM recipe_connector_action_changes c JOIN version_action_details d ON d.action_id=c.action_id JOIN action_ledger a ON a.id=c.action_id WHERE c.provider_id=? AND c.after_id=? AND d.family='RECIPE' AND a.state='CONFIRMED' ORDER BY a.rowid DESC LIMIT 1`, v.ProviderID, id).Scan(&recipeID)
	if e == sql.ErrNoRows {
		return nil
	}
	if e != nil {
		return e
	}
	_, p, e := readSiteRecipe(ctx, q, recipeID)
	if e != nil {
		return e
	}
	for _, ref := range p.Definition.Rules {
		if ref.VersionID != id {
			continue
		}
		anchor := p.Definition.Selection
		if anchor.ProviderID != v.ProviderID || anchor.AccountID != v.AccountID || anchor.CredentialID != v.CredentialID {
			return errSiteRecipeReview
		}
		if ref.Config.Scope != "CATALOG" && (anchor.Group != v.Group || anchor.Endpoint != v.Endpoint || ref.Config.Scope == "MODEL_KEY_GROUP" && anchor.ModelID != v.ModelID) {
			return errSiteRecipeReview
		}
		if _, e = readSiteRecipeRule(ctx, q, ref, p); e != nil {
			return e
		}
		return siteRecipeContextCurrent(ctx, q, p)
	}
	return nil
}

func (s *Server) enrichSiteRecipes(ctx context.Context, versions []Recipe) error {
	tx, e := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if e != nil {
		return e
	}
	defer tx.Rollback()
	for i := range versions {
		v := &versions[i]
		v.ActiveID = activeVersion(ctx, tx, "RECIPE", v.ProviderID, "")
		v.Active = v.ID == v.ActiveID
		if str(v.Settings["generated_recipe_id"]) != "" {
			_, p, err := readSiteRecipe(ctx, tx, v.ID)
			if err == sql.ErrNoRows {
				v.Status = "LOCAL_EVIDENCE_UNAVAILABLE"
				continue
			}
			if err != nil {
				return err
			}
			v.SiteDraft = p
			evaluation, err := evaluateSiteRecipe(ctx, tx, *v, p)
			if err != nil {
				if siteRecipeStorageFailure(err) {
					return err
				}
				v.Status = "REVIEW_REQUIRED"
			} else {
				v.Status = evaluation.Status
				v.Test = &evaluation
			}
		} else {
			v.Status = "MANUAL"
		}
		if v.Active && v.SiteDraft != nil {
			for _, ref := range v.SiteDraft.Definition.Rules {
				if activeVersion(ctx, tx, "CONNECTOR", v.ProviderID, ref.Name) != ref.VersionID {
					v.Status = "RULES_CHANGED"
					break
				}
			}
		}
		if v.Active {
			var action string
			err := tx.QueryRowContext(ctx, `SELECT d.action_id FROM version_action_details d JOIN action_ledger a ON a.id=d.action_id WHERE d.family='RECIPE' AND d.after_id=? AND a.state='CONFIRMED' ORDER BY a.rowid DESC LIMIT 1`, v.ID).Scan(&action)
			if err != nil && err != sql.ErrNoRows {
				return err
			}
			v.ActivationActionID = action
		}
	}
	return tx.Commit()
}
