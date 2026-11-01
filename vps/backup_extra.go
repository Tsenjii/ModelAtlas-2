package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

// Portable definitions. Credential material, active connector test assertions,
// chat content and telemetry are deliberately outside this configuration backup.
type BackupExtras struct {
	Aliases []struct {
		Provider  string `json:"provider_id"`
		RawID     string `json:"raw_id"`
		Canonical string `json:"canonical_id"`
		Source    string `json:"source"`
	} `json:"aliases"`
	Notes []struct {
		Kind  string `json:"subject_type"`
		ID    string `json:"subject_id"`
		Notes string `json:"notes"`
		Tags  string `json:"tags_json"`
	} `json:"notes"`
	Settings []struct {
		Key string `json:"key"`
		Raw string `json:"value_json"`
	} `json:"settings"`
	Membership []struct {
		Scope    string `json:"scope_key"`
		State    string `json:"state"`
		Favorite bool   `json:"favorite"`
		Watch    bool   `json:"watchlist"`
	} `json:"membership_state"`
	Recipes []struct {
		ID       string `json:"id"`
		Provider string `json:"provider_id"`
		Version  int    `json:"version"`
		Raw      string `json:"recipe_json"`
		Reason   string `json:"reason"`
		At       int64  `json:"created_at"`
	} `json:"recipes"`
	Sources []struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Kind     string `json:"kind"`
		URL      string `json:"url"`
		Language string `json:"language"`
		Enabled  bool   `json:"enabled"`
		Raw      string `json:"config_json"`
		At       int64  `json:"created_at"`
	} `json:"research_sources"`
	Knowledge []struct {
		ID       string `json:"id"`
		Kind     string `json:"kind"`
		Subject  string `json:"subject"`
		Version  int    `json:"version"`
		Raw      string `json:"data_json"`
		Evidence string `json:"evidence_json"`
		Reason   string `json:"reason"`
		At       int64  `json:"created_at"`
	} `json:"knowledge"`
	Connectors []struct {
		ID       string `json:"id"`
		Provider string `json:"provider_id"`
		Name     string `json:"name"`
		Version  int    `json:"version"`
		Raw      string `json:"config_json"`
		Evidence string `json:"evidence_json"`
		Reason   string `json:"reason"`
		At       int64  `json:"created_at"`
	} `json:"connectors"`
}

func restoreExtras(ctx context.Context, tx *sql.Tx, v BackupExtras) error {
	if len(v.Aliases) > 10000 || len(v.Notes) > 10000 || len(v.Sources) > 1000 || len(v.Recipes) > 10000 || len(v.Knowledge) > 10000 || len(v.Connectors) > 10000 || len(v.Settings) > 16 {
		return errors.New("too many definitions")
	}
	exec := func(q string, args ...any) error { _, e := tx.ExecContext(ctx, q, args...); return e }
	for _, a := range v.Aliases {
		if !validID(a.Provider) || !validID(a.RawID) || !validID(a.Canonical) {
			return errors.New("invalid alias")
		}
		if e := exec("INSERT INTO aliases VALUES(?,?,?,'MANUAL_IMPORT')", a.Provider, a.RawID, a.Canonical); e != nil {
			return e
		}
	}
	for _, a := range v.Notes {
		var tags []string
		if !oneOf(a.Kind, "PROVIDER", "MODEL") || a.ID == "" || len(a.Notes) > 4096 || json.Unmarshal([]byte(a.Tags), &tags) != nil || len(tags) > 20 {
			return errors.New("invalid notes")
		}
		for _, tag := range tags {
			if safeText(tag, 64) != tag || tag == "" {
				return errors.New("invalid tag")
			}
		}
		var n int
		if a.Kind == "PROVIDER" {
			_ = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM providers WHERE id=?", a.ID).Scan(&n)
		} else {
			var scope Selection
			if json.Unmarshal([]byte(a.ID), &scope) != nil || !validScope(scope) {
				return errors.New("invalid note scope")
			}
			_ = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint).Scan(&n)
		}
		if n != 1 {
			return errors.New("note subject missing")
		}
		if e := exec("INSERT INTO entity_notes VALUES(?,?,?,?)", a.Kind, a.ID, a.Notes, a.Tags); e != nil {
			return e
		}
	}
	for _, a := range v.Membership {
		var scope Selection
		if json.Unmarshal([]byte(a.Scope), &scope) != nil || !validScope(scope) || !oneOf(a.State, "ACTIVE", "HIDDEN", "ARCHIVED") {
			return errors.New("invalid membership state")
		}
		var n int
		_ = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint).Scan(&n)
		if n != 1 {
			return errors.New("membership missing")
		}
		if e := exec("INSERT INTO membership_state VALUES(?,?,?,?)", a.Scope, a.State, a.Favorite, a.Watch); e != nil {
			return e
		}
	}
	prefs := map[string]json.RawMessage{}
	for _, a := range v.Settings {
		prefs[a.Key] = json.RawMessage(a.Raw)
	}
	if e := validateSettings(prefs); e != nil {
		return e
	}
	// A restored configuration never silently starts network work or notifications.
	prefs["notifications"] = json.RawMessage("false")
	if raw, ok := prefs["background"]; ok {
		var b BackgroundSettings
		_ = json.Unmarshal(raw, &b)
		b.Enabled = false
		prefs["background"], _ = json.Marshal(b)
	}
	for key, raw := range prefs {
		if e := exec("INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json", key, string(raw)); e != nil {
			return e
		}
	}
	for _, a := range v.Recipes {
		var settings map[string]any
		if !validID(a.ID) || !validID(a.Provider) || a.Version < 1 || len(a.Reason) > 1000 || json.Unmarshal([]byte(a.Raw), &settings) != nil || !safeRecipe(settings) {
			return errors.New("invalid recipe")
		}
		if e := exec("INSERT INTO recipe_versions VALUES(?,?,?,?,?,?)", a.ID, a.Provider, a.Version, a.Raw, a.Reason, a.At); e != nil {
			return e
		}
	}
	for _, a := range v.Sources {
		var cfg SourceConfig
		if json.Unmarshal([]byte(a.Raw), &cfg) != nil || !validSource(ResearchSource{ID: a.ID, Name: a.Name, Kind: a.Kind, URL: a.URL, Language: a.Language, Config: cfg}) {
			return errors.New("invalid research source")
		}
		if e := exec("INSERT INTO research_sources VALUES(?,?,?,?,?,0,?,?)", a.ID, a.Name, a.Kind, a.URL, a.Language, a.Raw, a.At); e != nil {
			return e
		}
		source, _ := json.Marshal(ResearchSource{ID: a.ID, Name: a.Name, Kind: a.Kind, URL: a.URL, Language: a.Language, Config: cfg})
		if e := exec("INSERT INTO research_source_versions VALUES(?,1,?,?)", a.ID, string(source), now()); e != nil {
			return e
		}
	}
	for _, a := range v.Knowledge {
		var k KnowledgeVersion
		k.Kind = a.Kind
		k.Subject = a.Subject
		k.Reason = a.Reason
		d := json.NewDecoder(strings.NewReader(a.Raw))
		d.UseNumber()
		if d.Decode(&k.Data) != nil || json.Unmarshal([]byte(a.Evidence), &k.EvidenceIDs) != nil || !validID(a.ID) || a.Version < 1 || !validKnowledge(k) {
			return errors.New("invalid knowledge")
		}
		if e := exec("INSERT INTO knowledge_versions VALUES(?,?,?,?,?,?,?,'IMPORTED','',?)", a.ID, a.Kind, a.Subject, a.Version, a.Raw, a.Evidence, a.Reason, a.At); e != nil {
			return e
		}
	}
	for _, a := range v.Connectors {
		var cfg ConnectorConfig
		if json.Unmarshal([]byte(a.Raw), &cfg) != nil || !safeConnector(cfg) || !validID(a.ID) || !validID(a.Provider) || safeText(a.Name, 120) != a.Name || a.Name == "" || a.Version < 1 || safeText(a.Reason, 1000) != a.Reason {
			return errors.New("invalid connector")
		}
		var evidence []string
		if json.Unmarshal([]byte(a.Evidence), &evidence) != nil || len(evidence) > 20 {
			return errors.New("invalid connector evidence")
		}
		if e := exec("INSERT INTO connector_versions(id,provider_id,name,version,config_json,evidence_json,reason,status,created_at) VALUES(?,?,?,?,?,?,?,'DRAFT',?)", a.ID, a.Provider, a.Name, a.Version, a.Raw, a.Evidence, a.Reason, a.At); e != nil {
			return e
		}
	}
	return nil
}
