package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
)

//go:embed gateway_ui/tools.js
var gatewayToolsJS string

//go:embed gateway_ui/tools.css
var gatewayToolsCSS string

var gatewayToolsScript = staticAsset{raw: gatewayModelTermsJS + "\n" + gatewayToolsJS, revalidate: true}
var gatewayToolsStyles = staticAsset{raw: gatewayAppearanceCSS + gatewayToolsCSS, revalidate: true}

func (s *Server) gatewayToolRoutes(root *http.ServeMux) {
	api := http.NewServeMux()
	s.managementToolRoutes(api)
	api.HandleFunc("GET /model-catalog", s.toolModelCatalog)
	api.HandleFunc("GET /model-memberships", s.toolModelMemberships)
	api.HandleFunc("GET /model-detail", func(w http.ResponseWriter, r *http.Request) { s.readModelDetail(w, r, true) })
	api.HandleFunc("GET /offerings", s.gatewayOfferings)
	api.HandleFunc("POST /offerings/prices", s.gatewayRefreshOfferPrice)
	api.HandleFunc("GET /offerings/price-history", s.gatewayPublishedPriceHistory)
	api.HandleFunc("GET /verification/runs", s.listModelVerificationRuns)
	api.HandleFunc("POST /verification/runs", s.runModelVerification)
	root.Handle("/tools/api/", s.gatewayBrowserAPI(http.StripPrefix("/tools/api", api)))
	root.HandleFunc("GET /tools/{tool}", s.gatewayPage(gatewayToolPage))
	root.HandleFunc("GET /tools/app.css", s.gatewayPrivateAsset(s.stylesheet))
	root.HandleFunc("GET /tools/app.js", s.gatewayPrivateAsset(s.script))
	root.HandleFunc("GET /tools/tools.js", s.gatewayPrivateAsset(func(w http.ResponseWriter, r *http.Request) {
		gatewayToolsScript.serve(w, r, "text/javascript; charset=utf-8")
	}))
	root.HandleFunc("GET /tools/tools.css", s.gatewayPrivateAsset(func(w http.ResponseWriter, r *http.Request) {
		gatewayToolsStyles.serve(w, r, "text/css; charset=utf-8")
	}))
}

func (s *Server) gatewayPublishedPriceHistory(w http.ResponseWriter, r *http.Request) {
	ctx, v := r.Context(), selectionQuery(r)
	group := r.URL.Query().Get("price_group")
	if !validScope(v) || safeText(group, 120) != group {
		http.Error(w, "full selected scope and valid group required", 400)
		return
	}
	p, err := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		http.Error(w, "selected connection unavailable", 422)
		return
	}
	v.Endpoint = s.operation(ctx, v)
	v.CredentialID, v.Group = "", group
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT id,facts_json,observed_at FROM observations
		WHERE scope_key=? AND kind='NOMINAL_PRICE' AND source='NEW_API_OFFICIAL' AND status='SUCCESS'
		AND json_extract(facts_json,'$.origin')=? ORDER BY rowid DESC LIMIT 100`, scopeKey(v), p.BaseURL)
	if err != nil {
		http.Error(w, "price history unavailable", 500)
		return
	}
	defer rows.Close()
	prices := []Observation{}
	for rows.Next() {
		price := Observation{Selection: v, Kind: "NOMINAL_PRICE", Source: "NEW_API_OFFICIAL", Status: "SUCCESS"}
		var raw string
		if rows.Scan(&price.ID, &raw, &price.ObservedAt) != nil || json.Unmarshal([]byte(raw), &price.Facts) != nil {
			http.Error(w, "price history unavailable", 500)
			return
		}
		prices = append(prices, price)
	}
	if rows.Err() != nil {
		http.Error(w, "price history unavailable", 500)
		return
	}
	jsonOK(w, 200, prices)
}

func (s *Server) gatewayRefreshOfferPrice(w http.ResponseWriter, r *http.Request) {
	var v Selection
	if !decodeJSON(w, r, &v) {
		return
	}
	ctx := r.Context()
	if !validScope(v) || !s.selectionUsable(ctx, v) {
		http.Error(w, "selected connection unavailable", 422)
		return
	}
	p, err := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
	if err != nil {
		http.Error(w, "selected connection unavailable", 422)
		return
	}
	result, err := s.newApiRead(ctx, NewApiQuery{ProviderID: v.ProviderID, AccountID: v.AccountID, Origin: p.BaseURL}, "/api/pricing")
	if err != nil {
		writeProviderProblem(w, err, "pricing")
		return
	}
	jsonOK(w, 200, map[string]any{"mapping_available": result.Facts["price_mapping_available"] == true})
}

// Public group prices are shown as a separate price list. An unknown key group
// must never silently use one of these rates as its effective price.
const gatewayPublicGroupQuote = `kind='NOMINAL_PRICE' AND source='NEW_API_OFFICIAL' AND status='SUCCESS'
	AND json_valid(scope_key) AND json_valid(facts_json)`

const gatewayPublicGroupIndex = `CREATE INDEX IF NOT EXISTS gateway_public_group_quotes ON observations(
	json_extract(scope_key,'$.provider_id'),json_extract(scope_key,'$.account_id'),
	json_extract(scope_key,'$.model_id'),json_extract(scope_key,'$.endpoint'),
	json_extract(facts_json,'$.origin'),scope_key) WHERE ` + gatewayPublicGroupQuote

func (s *Server) gatewayPublishedGroupPrices(ctx context.Context, offer gatewayOffering) []Observation {
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT scope_key,facts_json,observed_at FROM observations
		WHERE rowid IN (SELECT MAX(rowid) FROM observations
		WHERE `+gatewayPublicGroupQuote+`
		AND json_extract(scope_key,'$.provider_id')=? AND json_extract(scope_key,'$.account_id')=?
		AND json_extract(scope_key,'$.model_id')=? AND json_extract(scope_key,'$.endpoint')=?
		AND json_extract(facts_json,'$.origin')=? GROUP BY scope_key)
		ORDER BY json_extract(scope_key,'$.group') LIMIT 12`,
		offer.ProviderID, offer.AccountID, offer.ModelID, offer.Operation, offer.BaseURL)
	if err != nil {
		return nil
	}
	defer rows.Close()
	prices := []Observation{}
	for rows.Next() {
		price := Observation{Kind: "NOMINAL_PRICE", Source: "NEW_API_OFFICIAL", Status: "SUCCESS"}
		var selected, facts string
		if rows.Scan(&selected, &facts, &price.ObservedAt) == nil && json.Unmarshal([]byte(selected), &price.Selection) == nil && json.Unmarshal([]byte(facts), &price.Facts) == nil {
			prices = append(prices, price)
		}
	}
	return prices
}

var gatewayToolPages = sync.OnceValue(func() map[string]http.HandlerFunc {
	const toolMarker = `data-lite-tool=""`
	page := strings.NewReplacer(
		`<html lang="zh-Hant"`, `<html lang="zh-Hant" data-wallpaper="elaina" data-api-base="/tools/api" `+toolMarker,
		`<title>ModelAtlas</title>`, `<title>ModelAtlas · Lite 管理工具</title><link rel="icon" href="/gateway-mark.svg" type="image/svg+xml">`,
		`href="/app.css"`, `href="/tools/app.css"><link rel="stylesheet" href="/tools/tools.css"`,
		`src="/app.js"`, `src="/tools/app.js" defer></script><script src="/tools/tools.js"`,
		`href="/api/v1/backup"`, `href="/tools/api/backup"`,
		"<body>\n<div class=\"shell\">\n<header>", "<body>\n"+gatewaySceneHTML+`<header class="gateway-bar">`,
		`</header>`, "</header>\n"+`<div class="shell">`,
		`<div class="brand">`, `<div class="brand glass">`,
		`<span class="mark" aria-hidden="true">M</span>`, `<img class="tool-mark" src="/gateway-mark.svg" alt="" width="36" height="36">`,
		`<small>你的模型工作台</small>`, `<small>模型工作台</small>`,
		`<div class="header-actions">`, `<div class="header-actions glass"><a id="lite-tool-back" class="link-button" data-icon="back" href="/">返回 Lite</a>`,
		`正在讀取你的供應商、模型與對話。`, `正在讀取管理資料。`,
		`<section id="playground"`, `<section id="lite-tool-content" class="pane" aria-labelledby="lite-tool-title" hidden><div class="heading"><h1 id="lite-tool-title"></h1><p id="lite-tool-model" hidden></p></div><div id="lite-tool-results"></div></section><section id="playground"`,
	).Replace(homeHTML)
	// All tool pages share the same large shell. Only the selected tool differs.
	before, after, _ := strings.Cut(page, toolMarker)
	tools := map[string]http.HandlerFunc{}
	for _, name := range []string{"models", "providers", "requests", "data", "prices", "verification"} {
		tools[name] = staticHTML(before, `data-lite-tool="`+name+`"`, after)
	}
	return tools
})

func gatewayToolPage(w http.ResponseWriter, r *http.Request) {
	page := gatewayToolPages()[r.PathValue("tool")]
	if page == nil {
		http.NotFound(w, r)
		return
	}
	page(w, r)
}
