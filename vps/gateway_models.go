package main

import (
	"database/sql/driver"
	"net/http"
	"strings"

	"modernc.org/sqlite"
)

func init() {
	sqlite.MustRegisterDeterministicScalarFunction("atlas_canonical_id", 1, func(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
		raw, _ := args[0].(string)
		if id := canonicalID(raw); id != "" {
			return id, nil
		}
		return raw, nil
	})
	sqlite.MustRegisterDeterministicScalarFunction("atlas_scope_key", 6, func(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
		return scopeKey(Selection{
			ProviderID: args[0].(string), AccountID: args[1].(string), CredentialID: args[2].(string),
			ModelID: args[3].(string), Group: args[4].(string), Endpoint: args[5].(string),
		}), nil
	})
}

// Match the existing metadata precedence. The group is an index for reading
// measurements, while inference and publication still use exact selections.
const gatewayCatalogSQL = `WITH scopes AS (
	SELECT m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint,
		p.name AS provider,a.label AS account,c.label AS credential,c.enabled,
		COALESCE(e.operation,m.endpoint) AS operation,
		COALESCE(NULLIF(d.display_name,''),m.model_id) AS display_name,
		COALESCE(NULLIF(CASE WHEN json_extract(md.metadata_json,'$.source') IS NOT 'MANUAL'
			THEN json_extract(k.data_json,'$.canonical_id') END,''),
			NULLIF(md.canonical_id,''),atlas_canonical_id(m.model_id)) AS canonical_id
	FROM my_models m JOIN providers p ON p.id=m.provider_id
	JOIN accounts a ON a.provider_id=m.provider_id AND a.id=m.account_id
	JOIN credentials c ON c.provider_id=m.provider_id AND c.account_id=m.account_id AND c.id=m.credential_id
	LEFT JOIN models d ON d.provider_id=m.provider_id AND d.id=m.model_id
	LEFT JOIN endpoint_profiles e ON e.provider_id=m.provider_id AND e.id=m.endpoint
	LEFT JOIN model_metadata md ON md.provider_id=m.provider_id AND md.model_id=m.model_id
	LEFT JOIN knowledge_active ka ON ka.kind='MODEL_ALIAS_RULE' AND ka.subject=m.provider_id||'/'||m.model_id
	LEFT JOIN knowledge_versions k ON k.id=ka.version_id
	LEFT JOIN membership_state ms ON ms.scope_key=atlas_scope_key(m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint)
	WHERE c.role='INFERENCE' AND COALESCE(ms.state,'ACTIVE')='ACTIVE'
)`

type gatewayModelGroup struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Providers   int    `json:"providers"`
	Connections int    `json:"connections"`
}

func (s *Server) gatewayModelGroups(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, offset := pageRange(r, 40)
	term, provider := strings.TrimSpace(q.Get("q")), q.Get("provider_id")
	rows, err := s.Store.DB.QueryContext(r.Context(), gatewayCatalogSQL+`
		SELECT canonical_id,COALESCE(MIN(NULLIF(display_name,model_id)),MIN(model_id)) AS name,COUNT(DISTINCT provider_id),COUNT(*)
		FROM scopes GROUP BY canonical_id
		HAVING (?='' OR MAX(provider_id=?)=1) AND
			(MAX(instr(lower(display_name||' '||model_id||' '||provider),lower(?)))>0 OR instr(lower(canonical_id),atlas_canonical_id(?))>0)
		ORDER BY name COLLATE NOCASE,canonical_id LIMIT ? OFFSET ?`, provider, provider, term, term, limit+1, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	groups := []gatewayModelGroup{}
	for rows.Next() {
		var g gatewayModelGroup
		if err = rows.Scan(&g.ID, &g.Name, &g.Providers, &g.Connections); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		groups = append(groups, g)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(groups) > limit
	jsonOK(w, 200, map[string]any{"models": groups[:min(limit, len(groups))], "more": more})
}

func (s *Server) gatewayAlignModel(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ProviderID string `json:"provider_id"`
		ModelID    string `json:"model_id"`
		Canonical  string `json:"canonical_id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	in.Canonical = strings.TrimSpace(in.Canonical)
	if !validID(in.ProviderID) || !validID(in.ModelID) || !validID(in.Canonical) {
		http.Error(w, "valid model grouping ID required", 400)
		return
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if safeText(in.Canonical, 256, secrets...) != in.Canonical {
		http.Error(w, "valid model grouping ID required", 400)
		return
	}
	m := s.metadata(r.Context(), in.ProviderID, in.ModelID)
	m.CanonicalID = in.Canonical
	s.saveMetadata(w, r, m)
}
