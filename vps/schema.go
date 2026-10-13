package main

const schema = `
CREATE TABLE IF NOT EXISTS providers (id TEXT PRIMARY KEY,name TEXT NOT NULL,base_url TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',auth_mode TEXT NOT NULL CHECK(auth_mode IN('BEARER','NONE')),created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS accounts (id TEXT NOT NULL,provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE RESTRICT,label TEXT NOT NULL,PRIMARY KEY(provider_id,id));
CREATE TABLE IF NOT EXISTS credentials (id TEXT NOT NULL,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,label TEXT NOT NULL,encrypted_key TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,PRIMARY KEY(provider_id,account_id,id),FOREIGN KEY(provider_id,account_id) REFERENCES accounts(provider_id,id) ON DELETE RESTRICT);
CREATE TABLE IF NOT EXISTS models (provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE RESTRICT,id TEXT NOT NULL,display_name TEXT NOT NULL,price_state TEXT NOT NULL DEFAULT 'UNKNOWN',quota_state TEXT NOT NULL DEFAULT 'UNKNOWN',provenance TEXT NOT NULL DEFAULT 'MANUAL',updated_at INTEGER NOT NULL,PRIMARY KEY(provider_id,id));
CREATE TABLE IF NOT EXISTS my_models (provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,model_id TEXT NOT NULL,group_id TEXT NOT NULL DEFAULT '',endpoint TEXT NOT NULL DEFAULT 'CHAT_COMPLETIONS',created_at INTEGER NOT NULL,PRIMARY KEY(provider_id,account_id,credential_id,model_id,group_id,endpoint),FOREIGN KEY(provider_id,model_id) REFERENCES models(provider_id,id),FOREIGN KEY(provider_id,account_id,credential_id) REFERENCES credentials(provider_id,account_id,id));
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,model_id TEXT NOT NULL,group_id TEXT NOT NULL,endpoint TEXT NOT NULL,title TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,role TEXT NOT NULL CHECK(role IN('user','assistant','tool')),content TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS messages_session ON messages(session_id,created_at,id);
CREATE TABLE IF NOT EXISTS telemetry (id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,account_id TEXT NOT NULL,credential_id TEXT NOT NULL,model_id TEXT NOT NULL,group_id TEXT NOT NULL,endpoint TEXT NOT NULL,operation TEXT NOT NULL,source TEXT NOT NULL,outcome TEXT NOT NULL,status_code INTEGER,ttfb_ms INTEGER,ttft_ms INTEGER,latency_ms INTEGER,input_tokens INTEGER,output_tokens INTEGER,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS telemetry_scope ON telemetry(provider_id,account_id,credential_id,model_id,group_id,endpoint,created_at);
CREATE TABLE IF NOT EXISTS research_candidates (id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,model_id TEXT NOT NULL,rpd INTEGER,tpd INTEGER,taiwan TEXT NOT NULL DEFAULT 'UNKNOWN',recurring TEXT NOT NULL DEFAULT 'UNKNOWN',strength TEXT NOT NULL DEFAULT 'UNKNOWN',state TEXT NOT NULL DEFAULT 'DISCOVERED',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS research_evidence (id TEXT PRIMARY KEY,candidate_id TEXT NOT NULL REFERENCES research_candidates(id) ON DELETE RESTRICT,source_type TEXT NOT NULL,source_url TEXT NOT NULL,claim TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0,observed_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_evidence_candidate ON research_evidence(candidate_id,observed_at);
CREATE TABLE IF NOT EXISTS research_runs (id TEXT PRIMARY KEY,status TEXT NOT NULL,checkpoint TEXT NOT NULL,started_at INTEGER NOT NULL,finished_at INTEGER);
CREATE TABLE IF NOT EXISTS research_rankings (id TEXT PRIMARY KEY,model_id TEXT NOT NULL,source_url TEXT NOT NULL,rank INTEGER NOT NULL,score REAL NOT NULL,observed_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS rankings_freshness ON research_rankings(observed_at,rank);
CREATE TABLE IF NOT EXISTS action_ledger (id TEXT PRIMARY KEY,action TEXT NOT NULL,subject TEXT NOT NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS recipe_versions (id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,version INTEGER NOT NULL,recipe_json TEXT NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(provider_id,version));
CREATE TABLE IF NOT EXISTS recipe_active (provider_id TEXT PRIMARY KEY,version INTEGER NOT NULL);
`
