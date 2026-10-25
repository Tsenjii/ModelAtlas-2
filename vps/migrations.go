package main

import (
	"database/sql"
	"fmt"
)

// Additive migrations preserve the first VPS database and all observation history.
const schemaVersion = 34

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
 CREATE TABLE IF NOT EXISTS endpoint_profiles(provider_id TEXT NOT NULL REFERENCES providers(id),id TEXT NOT NULL,operation TEXT NOT NULL,url TEXT NOT NULL,PRIMARY KEY(provider_id,id));
 CREATE TABLE IF NOT EXISTS endpoint_bindings(provider_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,operation TEXT NOT NULL,url TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(provider_id,endpoint_id));
 PRAGMA user_version=5;`); err != nil {
		return err
	}
	if version < 6 {
		if _, err = tx.Exec(`ALTER TABLE messages ADD COLUMN structured_json TEXT NOT NULL DEFAULT '{}';
 CREATE TABLE IF NOT EXISTS session_options(session_id TEXT PRIMARY KEY REFERENCES sessions(id),tools_json TEXT NOT NULL);
 PRAGMA user_version=6;`); err != nil {
			return err
		}
	}
	if version < 7 {
		if _, err = tx.Exec(`
 CREATE TABLE research_candidate_details(candidate_id TEXT PRIMARY KEY REFERENCES research_candidates(id),details_json TEXT NOT NULL);
 CREATE TABLE research_evidence_details(evidence_id TEXT PRIMARY KEY REFERENCES research_evidence(id),details_json TEXT NOT NULL);
 CREATE TABLE research_sources(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,url TEXT NOT NULL,language TEXT NOT NULL,enabled INTEGER NOT NULL,config_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE research_run_options(run_id TEXT PRIMARY KEY REFERENCES research_runs(id),config_json TEXT NOT NULL,budget INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0,probe_budget INTEGER NOT NULL,probes_used INTEGER NOT NULL DEFAULT 0,summary_json TEXT NOT NULL DEFAULT '{}');
 CREATE TABLE research_tasks(id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES research_runs(id),kind TEXT NOT NULL,dedupe TEXT NOT NULL,payload_json TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,result_json TEXT NOT NULL DEFAULT '{}',created_at INTEGER NOT NULL,finished_at INTEGER,UNIQUE(run_id,dedupe));
 CREATE INDEX research_task_queue ON research_tasks(status,created_at);
 CREATE TABLE integration_windows(name TEXT PRIMARY KEY,window_start INTEGER NOT NULL,reset_at INTEGER NOT NULL,used INTEGER NOT NULL,request_limit INTEGER NOT NULL,provider_remaining INTEGER);
 CREATE INDEX research_candidate_expiry ON research_candidate_details(CAST(json_extract(details_json,'$.eligibility_valid_until') AS INTEGER));
 CREATE TABLE aa_snapshots(id TEXT PRIMARY KEY,index_version TEXT NOT NULL,status TEXT NOT NULL,source_url TEXT NOT NULL,observed_at INTEGER NOT NULL,valid_until INTEGER NOT NULL,content_hash TEXT NOT NULL);
 CREATE TABLE aa_models(snapshot_id TEXT NOT NULL REFERENCES aa_snapshots(id),aa_id TEXT NOT NULL,slug TEXT NOT NULL,name TEXT NOT NULL,rank INTEGER NOT NULL,score REAL NOT NULL,PRIMARY KEY(snapshot_id,aa_id));
 CREATE INDEX aa_slug ON aa_models(slug,snapshot_id);
 PRAGMA user_version=7;`); err != nil {
			return err
		}
	}
	if version < 8 {
		if _, err = tx.Exec(`
 CREATE TABLE assistant_threads(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),scope_key TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE assistant_runs(id TEXT PRIMARY KEY,thread_id TEXT NOT NULL REFERENCES assistant_threads(id),status TEXT NOT NULL,user_message TEXT NOT NULL,result_json TEXT NOT NULL DEFAULT '{}',created_at INTEGER NOT NULL,finished_at INTEGER);
 CREATE TABLE assistant_tool_events(id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES assistant_runs(id),call_id TEXT NOT NULL,name TEXT NOT NULL,args_json TEXT NOT NULL,result_json TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE assistant_secret_refs(id TEXT PRIMARY KEY,base_url TEXT NOT NULL,label TEXT NOT NULL,role TEXT NOT NULL,encrypted_key TEXT NOT NULL,fields_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE entity_notes(subject_type TEXT NOT NULL,subject_id TEXT NOT NULL,notes TEXT NOT NULL,tags_json TEXT NOT NULL,PRIMARY KEY(subject_type,subject_id));
 CREATE TABLE assistant_action_details(action_id TEXT PRIMARY KEY REFERENCES action_ledger(id),kind TEXT NOT NULL,resource_json TEXT NOT NULL,before_hash TEXT NOT NULL,after_hash TEXT NOT NULL DEFAULT '');
 PRAGMA user_version=8;`); err != nil {
			return err
		}
	}
	if version < 9 {
		if _, err = tx.Exec(`
 ALTER TABLE telemetry ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 0;
 CREATE TABLE research_source_versions(source_id TEXT NOT NULL REFERENCES research_sources(id),version INTEGER NOT NULL,source_json TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(source_id,version));
 CREATE TABLE research_reflections(id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES research_runs(id),fingerprint TEXT NOT NULL,report_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(run_id,fingerprint));
 CREATE TABLE knowledge_versions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,subject TEXT NOT NULL,version INTEGER NOT NULL,data_json TEXT NOT NULL,evidence_json TEXT NOT NULL,reason TEXT NOT NULL,status TEXT NOT NULL,run_id TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,UNIQUE(kind,subject,version));
 CREATE TABLE knowledge_active(kind TEXT NOT NULL,subject TEXT NOT NULL,version_id TEXT NOT NULL REFERENCES knowledge_versions(id),PRIMARY KEY(kind,subject));
 CREATE TABLE connector_versions(id TEXT PRIMARY KEY,provider_id TEXT NOT NULL REFERENCES providers(id),name TEXT NOT NULL,version INTEGER NOT NULL,config_json TEXT NOT NULL,evidence_json TEXT NOT NULL,reason TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'DRAFT',test_json TEXT NOT NULL DEFAULT '{}',tested_at INTEGER,created_at INTEGER NOT NULL,UNIQUE(provider_id,name,version));
 CREATE TABLE connector_active(provider_id TEXT NOT NULL,name TEXT NOT NULL,version_id TEXT NOT NULL REFERENCES connector_versions(id),PRIMARY KEY(provider_id,name));
 CREATE TABLE connector_test_history(id TEXT PRIMARY KEY,version_id TEXT NOT NULL REFERENCES connector_versions(id),status TEXT NOT NULL,result_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE version_action_details(action_id TEXT PRIMARY KEY REFERENCES action_ledger(id),family TEXT NOT NULL,kind TEXT NOT NULL,subject TEXT NOT NULL,before_id TEXT NOT NULL,after_id TEXT NOT NULL);
 PRAGMA user_version=9;`); err != nil {
			return err
		}
	}
	if version < 10 {
		if _, err = tx.Exec(`CREATE TABLE background_state(id TEXT PRIMARY KEY,due_at INTEGER NOT NULL,cursor INTEGER NOT NULL);
 CREATE TABLE notification_state(scope_key TEXT PRIMARY KEY,state_json TEXT NOT NULL);
 CREATE TABLE notification_events(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,dedupe TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,seen_at INTEGER);
 PRAGMA user_version=10;`); err != nil {
			return err
		}
	}
	if version < 11 {
		if _, err = tx.Exec(`CREATE TABLE credential_origins(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,base_url TEXT NOT NULL,auth_mode TEXT NOT NULL DEFAULT '',PRIMARY KEY(provider_id,account_id,credential_id),FOREIGN KEY(provider_id,account_id,credential_id) REFERENCES credentials(provider_id,account_id,id));
 INSERT INTO credential_origins SELECT c.provider_id,c.account_id,c.id,p.base_url,'' FROM credentials c JOIN providers p ON p.id=c.provider_id;
 PRAGMA user_version=11;`); err != nil {
			return err
		}
	}
	if version < 12 {
		if _, err = tx.Exec(`CREATE TABLE custom_protocols(provider_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,config_json TEXT NOT NULL,PRIMARY KEY(provider_id,endpoint_id),FOREIGN KEY(provider_id,endpoint_id) REFERENCES endpoint_profiles(provider_id,id));
 CREATE TABLE session_settings(session_id TEXT PRIMARY KEY REFERENCES sessions(id),settings_json TEXT NOT NULL);

 PRAGMA user_version=12;`); err != nil {
			return err
		}
	}
	if version < 13 {
		if _, err = tx.Exec(` CREATE INDEX IF NOT EXISTS research_terminal_runs ON research_runs(status);
 CREATE TABLE reflection_receipts(run_id TEXT PRIMARY KEY REFERENCES research_runs(id),status TEXT NOT NULL,used INTEGER NOT NULL,checkpoint TEXT NOT NULL,reflected_at INTEGER NOT NULL);
 INSERT INTO reflection_receipts SELECT r.id,json_extract(f.report_json,'$.status'),json_extract(f.report_json,'$.http_requests'),r.checkpoint,f.created_at FROM research_runs r JOIN research_reflections f ON f.run_id=r.id WHERE json_valid(f.report_json) AND json_type(f.report_json,'$.status')='text' AND json_type(f.report_json,'$.http_requests')='integer' AND f.rowid IN (SELECT MAX(rowid) FROM research_reflections GROUP BY run_id);
 PRAGMA user_version=13;`); err != nil {
			return err
		}
	}

	if version < 14 {
		if _, err = tx.Exec(`CREATE TABLE session_metadata(session_id TEXT PRIMARY KEY REFERENCES sessions(id),pinned INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL);
 CREATE TABLE session_changes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),before_json TEXT NOT NULL,after_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE INDEX messages_activity ON messages(session_id,created_at DESC);
 PRAGMA user_version=14;`); err != nil {
			return err
		}
	}
	if version < 15 {
		if _, err = tx.Exec(`CREATE TABLE request_context(sample_id TEXT PRIMARY KEY REFERENCES telemetry(id),session_id TEXT REFERENCES sessions(id),request_id TEXT NOT NULL DEFAULT '',retry_of TEXT REFERENCES telemetry(id));
 CREATE INDEX requests_session ON request_context(session_id,sample_id);
 CREATE TABLE message_requests(message_id TEXT PRIMARY KEY REFERENCES messages(id),sample_id TEXT NOT NULL REFERENCES telemetry(id));
 PRAGMA user_version=15;`); err != nil {
			return err
		}
	}
	if version < 16 {
		if _, err = tx.Exec(`CREATE INDEX IF NOT EXISTS messages_sequence ON messages(session_id); PRAGMA user_version=16;`); err != nil {
			return err
		}
	}
	if version < 17 {
		if _, err = tx.Exec(`CREATE TABLE account_checkins(provider_id TEXT NOT NULL,account_id TEXT NOT NULL,date TEXT NOT NULL,timezone TEXT NOT NULL,completed_at INTEGER NOT NULL,source TEXT NOT NULL CHECK(source='MANUAL_COMPLETION'),PRIMARY KEY(provider_id,account_id,date),FOREIGN KEY(provider_id,account_id) REFERENCES accounts(provider_id,id) ON DELETE RESTRICT);
 CREATE INDEX account_checkins_history ON account_checkins(provider_id,account_id,completed_at DESC);
 PRAGMA user_version=17;`); err != nil {
			return err
		}
	}
	if version < 18 {
		if err = workspaceCreateSchema(tx); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=18"); err != nil {
			return err
		}
	}
	if version < 19 {
		if _, err = tx.Exec(researchScheduleSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=19"); err != nil {
			return err
		}
	}
	if version < 20 {
		if _, err = tx.Exec(researchOriginSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=20"); err != nil {
			return err
		}
	}
	if version < 21 {
		if _, err = tx.Exec(aaResearchSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=21"); err != nil {
			return err
		}
	}
	if version < 22 {
		if _, err = tx.Exec(researchEvaluationSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=22"); err != nil {
			return err
		}
	}
	if version < 23 {
		if _, err = tx.Exec(knowledgeEpochSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=23"); err != nil {
			return err
		}
	}
	if version < 24 {
		if _, err = tx.Exec(braveResearchSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=24"); err != nil {
			return err
		}
	}
	if version < 25 {
		if _, err = tx.Exec(connectorDiscoverySchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=25"); err != nil {
			return err
		}
	}
	if version < 26 {
		if _, err = tx.Exec(authenticitySchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=26"); err != nil {
			return err
		}
	}
	if version < 27 {
		if _, err = tx.Exec(researchInferenceBudgetSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=27"); err != nil {
			return err
		}
	}
	if version < 28 {
		if _, err = tx.Exec(researchResourceBudgetSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=28"); err != nil {
			return err
		}
	}
	if version < 29 {
		if _, err = tx.Exec(researchQuotaPoolSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=29"); err != nil {
			return err
		}
	}
	if version < 30 {
		if _, err = tx.Exec(researchPrivateScheduleSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=30"); err != nil {
			return err
		}
	}
	if version < 31 {
		if _, err = tx.Exec(researchProgressiveSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=31"); err != nil {
			return err
		}
	}
	if version < 32 {
		if _, err = tx.Exec(researchQuerySchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=32"); err != nil {
			return err
		}
	}
	if version < 33 {
		if _, err = tx.Exec(siteRecipeSchema); err != nil {
			return err
		}
		if _, err = tx.Exec("PRAGMA user_version=33"); err != nil {
			return err
		}
	}
	if version < 34 {
		if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS factory_defaults(id TEXT PRIMARY KEY,state TEXT NOT NULL CHECK(state IN('AVAILABLE','APPLIED')),applied_at INTEGER NOT NULL)`); err != nil {
			return err
		}
		if version > 0 {
			if _, err = tx.Exec("INSERT OR IGNORE INTO factory_defaults VALUES('kilo-v1','AVAILABLE',0)"); err != nil {
				return err
			}
		}
		if _, err = tx.Exec("PRAGMA user_version=34"); err != nil {
			return err
		}
	}
	return tx.Commit()
}
