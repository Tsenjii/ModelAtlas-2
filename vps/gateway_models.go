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

const gatewayPreferredModelIDSQL = `NULLIF(CASE WHEN json_extract(md.metadata_json,'$.source') IS NOT 'MANUAL'
	THEN json_extract(k.data_json,'$.canonical_id') END,''),NULLIF(md.canonical_id,'')`

// Match the existing metadata precedence. The group is an index for reading
// measurements, while inference and publication still use exact selections.
const gatewayCatalogBaseSQL = `WITH scopes AS (
	SELECT m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint,
		p.name AS provider,a.label AS account,c.label AS credential,c.enabled,
		COALESCE(o.base_url,p.base_url) AS base_url,c.encrypted_key<>'' AS has_api_key,
		COALESCE(e.operation,m.endpoint) AS operation,
		atlas_scope_key(m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint) AS scope_key,
		(SELECT COALESCE(MAX(version),0) FROM credential_secret_versions v
		 WHERE v.provider_id=c.provider_id AND v.account_id=c.account_id AND v.credential_id=c.id) AS credential_version,
		COALESCE(cc.group_id,'')=m.group_id AS current_group,
		COALESCE(json_extract(md.metadata_json,'$.primary_category'),'') AS model_category,
		EXISTS(SELECT 1 FROM json_each(md.metadata_json,'$.modalities') WHERE value='IMAGE_OUTPUT') AS image_output,
		COALESCE(NULLIF(d.display_name,''),m.model_id) AS display_name,
		COALESCE(` + gatewayPreferredModelIDSQL + `,atlas_canonical_id(m.model_id)) AS canonical_id
	FROM my_models m JOIN providers p ON p.id=m.provider_id
	JOIN accounts a ON a.provider_id=m.provider_id AND a.id=m.account_id
	JOIN credentials c ON c.provider_id=m.provider_id AND c.account_id=m.account_id AND c.id=m.credential_id
	LEFT JOIN credential_origins o ON o.provider_id=m.provider_id AND o.account_id=m.account_id AND o.credential_id=m.credential_id
	LEFT JOIN credential_context cc ON cc.provider_id=m.provider_id AND cc.account_id=m.account_id AND cc.credential_id=m.credential_id
	LEFT JOIN models d ON d.provider_id=m.provider_id AND d.id=m.model_id
	LEFT JOIN endpoint_profiles e ON e.provider_id=m.provider_id AND e.id=m.endpoint
	LEFT JOIN model_metadata md ON md.provider_id=m.provider_id AND md.model_id=m.model_id
	LEFT JOIN knowledge_active ka ON ka.kind='MODEL_ALIAS_RULE' AND ka.subject=m.provider_id||'/'||m.model_id
	LEFT JOIN knowledge_versions k ON k.id=ka.version_id
	LEFT JOIN membership_state ms ON ms.scope_key=atlas_scope_key(m.provider_id,m.account_id,m.credential_id,m.model_id,m.group_id,m.endpoint)
	WHERE c.role='INFERENCE'`

const gatewayCatalogSQL = gatewayCatalogBaseSQL + ` AND COALESCE(ms.state,'ACTIVE')='ACTIVE')`
const gatewayAllCatalogSQL = gatewayCatalogBaseSQL + `)`

type gatewayModelGroup struct {
	ID          string                        `json:"id"`
	Name        string                        `json:"name"`
	Providers   int                           `json:"providers"`
	Connections int                           `json:"connections"`
	Types       []string                      `json:"types"`
	Terms       map[string]*gatewayGroupTerms `json:"terms,omitempty"`
}

func gatewayModelType(operation, category string, imageOutput bool) string {
	switch operation {
	case "IMAGES_GENERATION":
		return "image"
	case "IMAGES_EDIT":
		return "image-edit"
	case "AUDIO", "AUDIO_SPEECH", "GEMINI_SPEECH":
		return "audio"
	case "EMBEDDINGS", "RERANK":
		return "other"
	}
	switch category {
	case "IMAGE":
		return "image"
	case "AUDIO":
		return "audio"
	case "EMBEDDING", "RERANK", "SAFETY":
		return "other"
	}
	if imageOutput {
		return "image"
	}
	if oneOf(operation, "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE") {
		return "text"
	}
	return "other"
}

func (s *Server) gatewayModelGroups(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, offset := pageRange(r, 40)
	term, provider := strings.TrimSpace(q.Get("q")), q.Get("provider_id")
	rows, err := s.Store.DB.QueryContext(r.Context(), gatewayCatalogSQL+`, page AS (
		SELECT canonical_id,COALESCE(MIN(NULLIF(display_name,model_id)),MIN(model_id)) AS name,
			COUNT(DISTINCT provider_id) AS providers,COUNT(*) AS connections
		FROM scopes WHERE (?='' OR canonical_id=?) AND (?='' OR provider_id=?) GROUP BY canonical_id
		HAVING MAX(instr(lower(display_name||' '||model_id||' '||provider),lower(?)))>0 OR instr(lower(canonical_id),atlas_canonical_id(?))>0
		ORDER BY name COLLATE NOCASE,canonical_id LIMIT ? OFFSET ?)
		SELECT page.canonical_id,page.name,page.providers,page.connections,
			m.operation,m.model_category,m.image_output,m.enabled AND m.current_group,`+gatewayTermColumns+`
		FROM page JOIN scopes m ON m.canonical_id=page.canonical_id
		WHERE (?='' OR m.provider_id=?)
		ORDER BY page.name COLLATE NOCASE,page.canonical_id,m.scope_key`,
		q.Get("id"), q.Get("id"), provider, provider, term, term, limit+1, offset, provider, provider)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	groups := []gatewayModelGroup{}
	for rows.Next() {
		var g gatewayModelGroup
		var operation, category string
		var imageOutput, enabled bool
		var facts gatewayTermRow
		if err = rows.Scan(append([]any{&g.ID, &g.Name, &g.Providers, &g.Connections, &operation, &category, &imageOutput, &enabled}, facts.scan()...)...); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		if len(groups) == 0 || groups[len(groups)-1].ID != g.ID {
			g.Terms = map[string]*gatewayGroupTerms{}
			groups = append(groups, g)
		}
		group := &groups[len(groups)-1]
		modelType := gatewayModelType(operation, category, imageOutput)
		if !oneOf(modelType, group.Types...) {
			group.Types = append(group.Types, modelType)
		}
		if enabled {
			terms := facts.terms()
			for _, kind := range []string{"all", modelType} {
				if group.Terms[kind] == nil {
					group.Terms[kind] = new(gatewayGroupTerms)
				}
				group.Terms[kind].add(terms)
			}
		}
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
