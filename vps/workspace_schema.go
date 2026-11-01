package main

import (
	"database/sql"
	"fmt"
	"sort"
	"strings"
)

// Only product data crosses devices. Runtime leases, budgets, pending actions,
// integration/admin tokens and device vault ciphertexts stay on their machine.
var workspaceTables = []string{
	"providers", "accounts", "endpoint_profiles", "endpoint_bindings", "credentials",
	"credential_origins", "credential_context", "provider_config", "models",
	"model_metadata", "aliases", "my_models", "membership_state",
	"capability_confirmations", "observations", "external_models", "newapi_snapshots",
	"sessions", "session_metadata", "session_options", "session_settings", "messages",
	"telemetry", "request_context", "message_requests", "account_checkins", "entity_notes",
	"research_sources", "research_source_versions", "research_candidates",
	"research_candidate_details", "research_evidence", "research_evidence_details",
	"research_rankings", "aa_snapshots", "aa_models", "research_runs",
	"research_run_options", "research_tasks", "research_reflections",
	"knowledge_versions", "connector_versions", "connector_test_history", "recipe_versions",
	"custom_protocols",
}
var workspaceAndroidTables = []string{"providers", "accounts", "credentials", "endpoints", "provider_configurations", "canonical_models", "offerings", "credential_availability", "provider_model_aliases", "model_classifications", "classification_capabilities", "classification_modalities", "classification_endpoint_types", "my_models", "favorite_models", "watchlist_models", "recent_models", "samples", "evidence", "price_snapshots", "quota_snapshots", "snapshot_facts", "snapshot_heads", "snapshot_slice_heads", "external_observations", "external_mappings", "checkins", "provider_notices", "provider_events", "price_events", "quota_events", "provider_perf_facts", "provider_perf_heads", "provider_account_usage_facts", "provider_account_usage_heads"}

func workspaceAndroidKind(kind string) bool {
	if !strings.HasPrefix(kind, "android:") {
		return false
	}
	for _, t := range workspaceAndroidTables {
		if kind == "android:"+t {
			return true
		}
	}
	return false
}

type workspaceTable struct {
	Name        string
	Columns, PK []string
}

func workspaceTableInfo(db interface {
	Query(string, ...any) (*sql.Rows, error)
}, name string) (workspaceTable, error) {
	t := workspaceTable{Name: name}
	rows, e := db.Query("PRAGMA table_info(" + name + ")")
	if e != nil {
		return t, e
	}
	defer rows.Close()
	keys := map[int]string{}
	for rows.Next() {
		var n, notnull, pk int
		var col, typ string
		var d any
		if e = rows.Scan(&n, &col, &typ, &notnull, &d, &pk); e != nil {
			return t, e
		}
		t.Columns = append(t.Columns, col)
		if pk > 0 {
			keys[pk] = col
		}
	}
	order := []int{}
	for n := range keys {
		order = append(order, n)
	}
	sort.Ints(order)
	for _, n := range order {
		t.PK = append(t.PK, keys[n])
	}
	if len(t.PK) == 0 {
		return t, fmt.Errorf("missing sync primary key: %s", name)
	}
	return t, rows.Err()
}
func workspaceCreateSchema(tx *sql.Tx) error {
	_, e := tx.Exec(`
 CREATE TABLE workspace_identity(id TEXT PRIMARY KEY CHECK(id='local'),device_id TEXT NOT NULL,workspace_id TEXT NOT NULL);
 CREATE TABLE workspace_devices(id TEXT PRIMARY KEY,label TEXT NOT NULL,platform TEXT NOT NULL,token_hash TEXT NOT NULL UNIQUE,share_secrets INTEGER NOT NULL,created_at INTEGER NOT NULL,last_seen INTEGER,revoked_at INTEGER);
 CREATE TABLE workspace_pairings(code_hash TEXT PRIMARY KEY,share_secrets INTEGER NOT NULL,expires_at INTEGER NOT NULL,claimed_at INTEGER);
 CREATE TABLE workspace_rows(kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,data_json TEXT NOT NULL,deleted INTEGER NOT NULL,origin_device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(kind,id));
 CREATE TABLE workspace_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,data_json TEXT NOT NULL,deleted INTEGER NOT NULL,origin_device TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE INDEX workspace_changes_record ON workspace_changes(kind,id,revision);
 CREATE TABLE workspace_dirty(kind TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(kind,id));
 CREATE TABLE workspace_conflicts(id TEXT PRIMARY KEY,device_id TEXT NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,base_revision INTEGER NOT NULL,local_json TEXT NOT NULL,remote_json TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'OPEN',created_at INTEGER NOT NULL,UNIQUE(device_id,kind,record_id,local_json,remote_json));
 CREATE TABLE workspace_peer(id INTEGER PRIMARY KEY CHECK(id=1),base_url TEXT NOT NULL,device_id TEXT NOT NULL,encrypted_token TEXT NOT NULL,share_secrets INTEGER NOT NULL,enabled INTEGER NOT NULL,cursor INTEGER NOT NULL DEFAULT 0,last_success INTEGER,last_error TEXT NOT NULL DEFAULT '',next_at INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE workspace_importing(id INTEGER PRIMARY KEY CHECK(id=1),active INTEGER NOT NULL);
 CREATE TABLE workspace_fetch(kind TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(kind,id));
 CREATE TABLE workspace_native_archive(kind TEXT NOT NULL,id TEXT NOT NULL,data_json TEXT NOT NULL,deleted INTEGER NOT NULL,origin_device TEXT NOT NULL,revision INTEGER NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(kind,id));
 INSERT INTO workspace_importing VALUES(1,0);
 `)
	if e != nil {
		return e
	}
	for _, name := range workspaceTables {
		t, e := workspaceTableInfo(tx, name)
		if e != nil {
			return e
		}
		refs := func(prefix string) string {
			p := []string{}
			for _, k := range t.PK {
				p = append(p, prefix+k)
			}
			return "json_array(" + strings.Join(p, ",") + ")"
		}
		for _, op := range []string{"INSERT", "UPDATE", "DELETE"} {
			prefix := "NEW."
			if op == "DELETE" {
				prefix = "OLD."
			}
			key := refs(prefix)
			q := "CREATE TRIGGER workspace_" + name + "_" + strings.ToLower(op) + " AFTER " + op + " ON " + name + " WHEN (SELECT active FROM workspace_importing WHERE id=1)=0 BEGIN INSERT INTO workspace_dirty(kind,id) SELECT '" + name + "'," + key + " WHERE NOT EXISTS(SELECT 1 FROM workspace_dirty WHERE kind='" + name + "' AND id=" + key + "); END;"
			if _, e = tx.Exec(q); e != nil {
				return e
			}
		}
		// Seed existing data with a bounded SQL cursor; do not load the database into RAM.
		if _, e = tx.Exec("INSERT OR IGNORE INTO workspace_dirty SELECT '" + name + "'," + refs("") + " FROM " + name); e != nil {
			return e
		}
	}
	// Custom auth fields are encrypted locally and travel only on the privileged
	// secret channel, attached to their credential rather than metadata rows.
	for _, op := range []string{"INSERT", "UPDATE", "DELETE"} {
		p := "NEW."
		if op == "DELETE" {
			p = "OLD."
		}
		key := "json_array(" + p + "provider_id," + p + "account_id," + p + "credential_id)"
		if _, e = tx.Exec("CREATE TRIGGER workspace_auth_" + strings.ToLower(op) + " AFTER " + op + " ON credential_auth_fields WHEN (SELECT active FROM workspace_importing WHERE id=1)=0 BEGIN INSERT INTO workspace_dirty SELECT 'credentials'," + key + " WHERE NOT EXISTS(SELECT 1 FROM workspace_dirty WHERE kind='credentials' AND id=" + key + "); END;"); e != nil {
			return e
		}
	}
	return nil
}
