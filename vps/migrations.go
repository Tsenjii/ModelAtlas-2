package main

import (
	"database/sql"
	"fmt"
)

// Additive migrations preserve the first VPS database and all observation history.
const schemaVersion = 4

func migrate(db *sql.DB) error {
	var version int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > schemaVersion {
		return fmt.Errorf("database version %d is newer than this binary", version)
	}
	if version == schemaVersion {
		return nil
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if version < 2 {
		if _, err = tx.Exec(`
 CREATE TABLE IF NOT EXISTS provider_config(provider_id TEXT PRIMARY KEY REFERENCES providers(id),config_json TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS credential_context(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,group_id TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',priority INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(provider_id,account_id,credential_id),FOREIGN KEY(provider_id,account_id,credential_id) REFERENCES credentials(provider_id,account_id,id));
 CREATE TABLE IF NOT EXISTS model_metadata(provider_id TEXT NOT NULL,model_id TEXT NOT NULL,canonical_id TEXT NOT NULL,metadata_json TEXT NOT NULL,PRIMARY KEY(provider_id,model_id),FOREIGN KEY(provider_id,model_id) REFERENCES models(provider_id,id));
 CREATE TABLE IF NOT EXISTS aliases(provider_id TEXT NOT NULL,raw_id TEXT NOT NULL,canonical_id TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(provider_id,raw_id));
 CREATE TABLE IF NOT EXISTS membership_state(scope_key TEXT PRIMARY KEY,state TEXT NOT NULL DEFAULT 'ACTIVE',favorite INTEGER NOT NULL DEFAULT 0,watchlist INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS capability_confirmations(scope_key TEXT PRIMARY KEY,capabilities_json TEXT NOT NULL,source TEXT NOT NULL,observed_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS observations(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,facts_json TEXT NOT NULL,observed_at INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS observations_scope ON observations(scope_key,kind,source,observed_at);
 CREATE TABLE IF NOT EXISTS sync_history(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,status TEXT NOT NULL,summary_json TEXT NOT NULL,observed_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS checkins(provider_id TEXT NOT NULL,date TEXT NOT NULL,completed_at INTEGER NOT NULL,PRIMARY KEY(provider_id,date));
 CREATE TABLE IF NOT EXISTS onboarding(id TEXT PRIMARY KEY,status TEXT NOT NULL,draft_json TEXT NOT NULL,review_json TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS external_models(id TEXT PRIMARY KEY,canonical_id TEXT NOT NULL,source TEXT NOT NULL,facts_json TEXT NOT NULL,observed_at INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS external_model_lookup ON external_models(canonical_id,source,observed_at);
 CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value_json TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS integration_credentials(name TEXT PRIMARY KEY,encrypted_key TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS request_budgets(name TEXT NOT NULL,date TEXT NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(name,date));
 ALTER TABLE messages ADD COLUMN media_metadata TEXT NOT NULL DEFAULT '[]';
 ALTER TABLE telemetry ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}';
 PRAGMA user_version=2;`); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS credential_auth_fields(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,fields_json TEXT NOT NULL,PRIMARY KEY(provider_id,account_id,credential_id),FOREIGN KEY(provider_id,account_id,credential_id) REFERENCES credentials(provider_id,account_id,id));
 CREATE TABLE IF NOT EXISTS credential_secret_versions(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,version INTEGER NOT NULL,encrypted_key TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(provider_id,account_id,credential_id,version));
 CREATE TABLE IF NOT EXISTS membership_mutations(id INTEGER PRIMARY KEY AUTOINCREMENT,scope_key TEXT NOT NULL,at INTEGER NOT NULL); CREATE INDEX IF NOT EXISTS membership_mutations_scope ON membership_mutations(scope_key,id);
 PRAGMA user_version=4;`); err != nil {
		return err
	}
	return tx.Commit()
}
