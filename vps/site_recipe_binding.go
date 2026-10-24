package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// Version history is portable; execution bindings are local. Capture only the
// legacy versions that are already active during this upgrade. Selecting a
// different legacy version explicitly creates its own local binding.
const siteRecipeSchema = `
CREATE TABLE IF NOT EXISTS recipe_execution_bindings(version_id TEXT PRIMARY KEY REFERENCES recipe_versions(id),settings_json TEXT NOT NULL CHECK(json_valid(settings_json)),context_json TEXT NOT NULL CHECK(json_valid(context_json)));
INSERT OR IGNORE INTO recipe_execution_bindings SELECT v.id,v.recipe_json,'{"mode":"LEGACY_PROVIDER_WIDE"}' FROM recipe_active a JOIN recipe_versions v ON v.provider_id=a.provider_id AND v.version=a.version;
CREATE TABLE IF NOT EXISTS recipe_generated_versions(version_id TEXT PRIMARY KEY REFERENCES recipe_versions(id),discovery_id TEXT NOT NULL REFERENCES connector_discoveries(id),proof_json TEXT NOT NULL CHECK(json_valid(proof_json)),definition_json TEXT NOT NULL CHECK(json_valid(definition_json)),created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS recipe_test_receipts(version_id TEXT PRIMARY KEY REFERENCES recipe_versions(id),context_hash TEXT NOT NULL,definition_hash TEXT NOT NULL,report_json TEXT NOT NULL CHECK(json_valid(report_json)),tested_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS recipe_connector_action_changes(action_id TEXT NOT NULL REFERENCES action_ledger(id),provider_id TEXT NOT NULL,name TEXT NOT NULL,before_id TEXT NOT NULL,after_id TEXT NOT NULL,PRIMARY KEY(action_id,provider_id,name));`

func bindLegacyRecipe(ctx context.Context, tx *sql.Tx, id, raw string) error {
	var settings map[string]any
	if json.Unmarshal([]byte(raw), &settings) != nil || !safeRecipe(settings) {
		return errors.New("invalid recipe definition")
	}
	// A generated version must take its own tested, scoped Review path.
	var generated int
	if e := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM recipe_generated_versions WHERE version_id=?", id).Scan(&generated); e != nil {
		return e
	}
	if generated != 0 || str(settings["generated_recipe_id"]) != "" {
		return errors.New("generated recipe requires its test and review")
	}
	_, e := tx.ExecContext(ctx, `INSERT INTO recipe_execution_bindings VALUES(?,?,'{"mode":"LEGACY_PROVIDER_WIDE"}') ON CONFLICT(version_id) DO UPDATE SET settings_json=excluded.settings_json,context_json=excluded.context_json`, id, raw)
	return e
}

func localRecipeSettings(ctx context.Context, q queryRows, p Provider) (map[string]any, string) {
	var id, current, approved, binding string
	e := q.QueryRowContext(ctx, `SELECT COALESCE(v.id,''),COALESCE(v.recipe_json,''),COALESCE(b.settings_json,''),COALESCE(b.context_json,'') FROM recipe_active a LEFT JOIN recipe_versions v ON v.provider_id=a.provider_id AND v.version=a.version LEFT JOIN recipe_execution_bindings b ON b.version_id=v.id WHERE a.provider_id=?`, p.ID).Scan(&id, &current, &approved, &binding)
	if e == sql.ErrNoRows {
		return nil, ""
	}
	if e != nil {
		return nil, "RECIPE_STORAGE_UNAVAILABLE"
	}
	var live, saved map[string]any
	var bindingContext struct {
		Mode           string `json:"mode"`
		DefinitionHash string `json:"definition_hash"`
		ContextHash    string `json:"context_hash"`
		ActualTests    string `json:"actual_tests"`
	}
	if id == "" || json.Unmarshal([]byte(current), &live) != nil || json.Unmarshal([]byte(approved), &saved) != nil || json.Unmarshal([]byte(binding), &bindingContext) != nil || !safeRecipe(live) || !safeRecipe(saved) || connectorHash(live) != connectorHash(saved) {
		return nil, "RECIPE_REVIEW_REQUIRED"
	}
	switch bindingContext.Mode {
	case "LEGACY_PROVIDER_WIDE":
		if str(live["generated_recipe_id"]) != "" {
			return nil, "RECIPE_REVIEW_REQUIRED"
		}
	case "PUBLIC_METADATA_COMPOSITE":
		_, p, e := readSiteRecipe(ctx, q, id)
		if siteRecipeStorageFailure(e) {
			return nil, "RECIPE_STORAGE_UNAVAILABLE"
		}
		if e != nil || str(live["generated_recipe_id"]) != id || p.DefinitionHash != bindingContext.DefinitionHash || p.ContextHash != bindingContext.ContextHash || len(bindingContext.ActualTests) != 64 {
			return nil, "RECIPE_REVIEW_REQUIRED"
		}
	default:
		return nil, "RECIPE_REVIEW_REQUIRED"
	}

	return saved, ""
}
