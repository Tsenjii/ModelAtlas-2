package main

import (
	"context"
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
	sqlite.MustRegisterDeterministicScalarFunction("atlas_model_type", 3, func(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
		operation, _ := args[0].(string)
		category, _ := args[1].(string)
		imageOutput, _ := args[2].(int64)
		return gatewayModelType(operation, category, imageOutput != 0), nil
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
	Brand       string                        `json:"brand,omitempty"`
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

func gatewayLegacyModelGroups(ctx context.Context, db queryRows, q gatewayModelGroupQuery, limit, offset int) (gatewayModelGroupPage, error) {
	// The older JSON entry point also serves direct links and post-edit refreshes.
	args := append(append([]any{}, q.catalogArgs()...), limit+1, offset)
	rows, err := db.QueryContext(ctx, gatewayCatalogSQL+`, filtered AS MATERIALIZED (
		SELECT * FROM scopes WHERE `+q.filter+`
	), page AS (
		SELECT canonical_id,`+gatewayModelGroupName+` AS name,
			COUNT(DISTINCT provider_id) AS providers,COUNT(*) AS connections
		FROM filtered GROUP BY canonical_id`+q.search()+`
		ORDER BY name COLLATE NOCASE,canonical_id LIMIT ? OFFSET ?)
		SELECT page.canonical_id,page.name,page.providers,page.connections,
			m.operation,m.model_category,m.image_output,m.enabled AND m.current_group,`+gatewayTermColumns+`
		FROM page JOIN filtered m ON m.canonical_id=page.canonical_id
		ORDER BY page.name COLLATE NOCASE,page.canonical_id,m.scope_key`, args...)
	if err != nil {
		return gatewayModelGroupPage{}, err
	}
	defer rows.Close()
	groups := []gatewayModelGroup{}
	for rows.Next() {
		var group gatewayModelGroup
		var operation, category string
		var imageOutput, enabled bool
		var facts gatewayTermRow
		if err = rows.Scan(append([]any{&group.ID, &group.Name, &group.Providers, &group.Connections, &operation, &category, &imageOutput, &enabled}, facts.scan()...)...); err != nil {
			return gatewayModelGroupPage{}, err
		}
		if len(groups) == 0 || groups[len(groups)-1].ID != group.ID {
			groups = append(groups, group)
		}
		groups[len(groups)-1].addConnection(gatewayModelType(operation, category, imageOutput), enabled, facts)
	}
	return gatewayModelGroupPage{Models: groups[:min(limit, len(groups))], More: len(groups) > limit}, rows.Err()
}

func (s *Server) gatewayModelGroups(w http.ResponseWriter, r *http.Request) {
	values := r.URL.Query()
	limit, offset := pageRange(r, 40)
	provider := values.Get("provider_id")
	q := gatewayModelGroupQuery{filter: "1", term: strings.TrimSpace(values.Get("q"))}
	if provider != "" {
		q.filter += " AND provider_id=?"
		q.args = append(q.args, provider)
	}
	if id := values.Get("id"); id != "" {
		q.filter += " AND canonical_id=?"
		q.args = append(q.args, id)
	}

	ctx := r.Context()
	var page gatewayModelGroupPage
	var err error
	if values.Get("facets") == "1" {
		page, err = s.gatewayBrowseModelGroups(ctx, q, values.Get("brand"), values.Get("type"), limit, offset)
	} else {
		page, err = gatewayLegacyModelGroups(ctx, s.Store.DB, q, limit, offset)
	}
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, page)
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
