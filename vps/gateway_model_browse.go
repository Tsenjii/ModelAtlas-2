package main

import (
	"context"
	"database/sql"
	"strings"
)

const gatewayModelGroupTypes = `GROUP_CONCAT(DISTINCT atlas_model_type(operation,model_category,image_output))`
const gatewayModelGroupName = `COALESCE(MIN(NULLIF(display_name,model_id)),MIN(model_id))`

type gatewayModelGroupQuery struct {
	filter string
	args   []any
	term   string
}

func (q gatewayModelGroupQuery) catalog() string {
	return gatewayCatalogSQL + `
		SELECT canonical_id,` + gatewayModelGroupName + ` AS name,
			COUNT(DISTINCT provider_id) AS providers,COUNT(*) AS connections,` + gatewayModelGroupTypes + ` AS types
		FROM scopes WHERE ` + q.filter + ` GROUP BY canonical_id` + q.search() + `
		ORDER BY name COLLATE NOCASE,canonical_id`
}

func (q gatewayModelGroupQuery) search() string {
	if q.term == "" {
		return ""
	}
	return ` HAVING MAX(instr(lower(display_name||' '||model_id||' '||provider),lower(?)))>0
		OR instr(lower(canonical_id),atlas_canonical_id(?))>0`
}

func (q gatewayModelGroupQuery) catalogArgs() []any {
	if q.term == "" {
		return q.args
	}
	return append(append([]any{}, q.args...), q.term, q.term)
}

type gatewayModelGroupPage struct {
	Facets map[string]map[string]int `json:"facets,omitempty"`
	Models []gatewayModelGroup       `json:"models"`
	More   bool                      `json:"more"`
}

func (s *Server) gatewayBrowseModelGroups(ctx context.Context, q gatewayModelGroupQuery, brand, modelType string, limit, offset int) (gatewayModelGroupPage, error) {
	tx, err := s.Store.DB.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return gatewayModelGroupPage{}, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, q.catalog(), q.catalogArgs()...)
	if err != nil {
		return gatewayModelGroupPage{}, err
	}
	if modelType == "" {
		modelType = "all"
	}
	page := gatewayModelGroupPage{Models: make([]gatewayModelGroup, 0, limit), Facets: map[string]map[string]int{"all": {"": 0}}}
	matches := 0
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			rows.Close()
			return gatewayModelGroupPage{}, err
		}
		var group gatewayModelGroup
		var typeFacts string
		if err = rows.Scan(&group.ID, &group.Name, &group.Providers, &group.Connections, &typeFacts); err != nil {
			rows.Close()
			return gatewayModelGroupPage{}, err
		}
		group.Brand = gatewayModelBrand(group.ID, group.Name)
		page.countGroup("all", group.Brand)
		typeMatches := modelType == "all"
		for kind := range strings.SplitSeq(typeFacts, ",") {
			page.countGroup(kind, group.Brand)
			typeMatches = typeMatches || modelType == kind
		}
		if typeMatches && (brand == "" || brand == group.Brand) {
			if matches >= offset && len(page.Models) < limit {
				page.Models = append(page.Models, group)
			}
			matches++
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return gatewayModelGroupPage{}, err
	}
	page.More = matches > offset+len(page.Models)
	return page, gatewayReadModelGroupTerms(ctx, tx, q, page.Models)
}

func (page *gatewayModelGroupPage) countGroup(kind, brand string) {
	counts := page.Facets[kind]
	if counts == nil {
		counts = map[string]int{}
		page.Facets[kind] = counts
	}
	counts[""]++
	counts[brand]++
}

// Only groups on the page need their exact connection terms. The same read
// transaction keeps the aggregate and the terms in one SQLite snapshot.
func gatewayReadModelGroupTerms(ctx context.Context, tx *sql.Tx, q gatewayModelGroupQuery, groups []gatewayModelGroup) error {
	if len(groups) == 0 {
		return nil
	}
	args := append([]any{}, q.args...)
	positions := make(map[string]int, len(groups))
	for i, group := range groups {
		args = append(args, group.ID)
		positions[group.ID] = i
	}
	marks := strings.TrimRight(strings.Repeat("?,", len(groups)), ",")
	rows, err := tx.QueryContext(ctx, gatewayCatalogSQL+`, selected AS MATERIALIZED (
		SELECT * FROM scopes WHERE `+q.filter+` AND canonical_id IN (`+marks+`))
		SELECT m.canonical_id,m.operation,m.model_category,m.image_output,m.enabled AND m.current_group,`+gatewayTermColumns+`
		FROM selected m ORDER BY m.canonical_id,m.scope_key`, args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id, operation, category string
		var imageOutput, enabled bool
		var facts gatewayTermRow
		if err = rows.Scan(append([]any{&id, &operation, &category, &imageOutput, &enabled}, facts.scan()...)...); err != nil {
			return err
		}
		groups[positions[id]].addConnection(gatewayModelType(operation, category, imageOutput), enabled, facts)
	}
	return rows.Err()
}

func (group *gatewayModelGroup) addConnection(modelType string, enabled bool, facts gatewayTermRow) {
	if !oneOf(modelType, group.Types...) {
		group.Types = append(group.Types, modelType)
	}
	if !enabled {
		return
	}
	if group.Terms == nil {
		group.Terms = map[string]*gatewayGroupTerms{}
	}
	terms := facts.terms()
	for _, kind := range []string{"all", modelType} {
		if group.Terms[kind] == nil {
			group.Terms[kind] = new(gatewayGroupTerms)
		}
		group.Terms[kind].add(terms)
	}
}
