package main

import (
	"context"
	"fmt"
	"net/url"
	"reflect"
	"slices"
	"testing"
)

func TestGatewayModelBrowseCountsAndPages(t *testing.T) {
	s := testServer(t)
	const gateway = "/api/v1/gateway"
	read := func(q string) gatewayModelGroupPage {
		t.Helper()
		s.distribution = true
		defer func() { s.distribution = false }()
		return gatewayDecode[gatewayModelGroupPage](t, req(t, s, "GET", gateway+"/model-groups?"+q, nil), 200)
	}
	if empty := read("facets=1"); len(empty.Models) != 0 || empty.More || empty.Facets["all"][""] != 0 {
		t.Fatalf("empty catalog: %+v", empty)
	}
	first := seed(t, s, "https://provider.example/v1")
	for i := range 85 {
		scope := first
		scope.ModelID = fmt.Sprintf("unknown-%03d", i)
		name := fmt.Sprintf("A model %03d", i)
		if i >= 78 {
			scope.ModelID = fmt.Sprintf("deepseek-%03d", i)
			name = fmt.Sprintf("Z DeepSeek %03d", i)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: scope.ProviderID, ID: scope.ModelID, DisplayName: name}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	}
	// This provider's matching name is only on one connection. Search must
	// return the complete model group and keep both capabilities.
	const shared = "deepseek-084"
	s.distribution = true
	second := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", gateway+"/upstreams",
		map[string]string{"name": "Zeta gateway", "base_url": "https://zeta.example/v1"}), 201)
	requireCode(t, req(t, s, "POST", gateway+"/upstream-model", map[string]string{
		"provider_id": second.ProviderID, "account_id": second.AccountID, "credential_id": second.CredentialID,
		"model_id": shared, "endpoint": "IMAGES_GENERATION",
	}), 200)
	s.distribution = false
	hidden := first
	hidden.ModelID = "deepseek-083"
	if _, err := s.Store.DB.ExecContext(t.Context(), "INSERT INTO membership_state(scope_key,state) VALUES(?,'ARCHIVED')", scopeKey(hidden)); err != nil {
		t.Fatal(err)
	}
	legacy := read("limit=200")
	browse := read("facets=1&limit=40")
	if !browse.More || len(browse.Models) != 40 || browse.Facets["all"][""] != 84 ||
		browse.Facets["all"]["deepseek"] != 6 || browse.Facets["text"]["deepseek"] != 6 ||
		browse.Facets["image"]["deepseek"] != 1 {
		t.Fatalf("counts should include all active models and both capabilities: %+v", browse.Facets)
	}
	var found []gatewayModelGroup
	for offset := 0; ; offset += 40 {
		page := read(fmt.Sprintf("facets=1&limit=40&offset=%d", offset))
		for _, group := range page.Models {
			group.Brand = ""
			found = append(found, group)
		}
		if !page.More {
			break
		}
	}
	if !reflect.DeepEqual(found, legacy.Models) {
		t.Fatal("paged rows changed order, connection counts, capabilities or terms")
	}
	image := read("facets=1&brand=deepseek&type=image")
	if image.More || len(image.Models) != 1 || image.Models[0].ID != shared || image.Models[0].Connections != 2 ||
		!slices.Contains(image.Models[0].Types, "text") || !slices.Contains(image.Models[0].Types, "image") {
		t.Fatalf("brand and type should find the shared model beyond the first page: %+v", image.Models)
	}
	search := read("facets=1&q=Zeta+gateway")
	if len(search.Models) != 1 || search.Models[0].Connections != 2 || search.Models[0].Providers != 2 {
		t.Fatalf("a provider name match should keep the group: %+v", search.Models)
	}
	scoped := read("facets=1&type=image&provider_id=" + url.QueryEscape(second.ProviderID))
	if len(scoped.Models) != 1 || scoped.Models[0].Connections != 1 || scoped.Facets["text"][""] != 0 ||
		scoped.Facets["image"]["deepseek"] != 1 {
		t.Fatalf("provider facet or connection scope leaked: %+v", scoped)
	}
	noMatch := read("facets=1&brand=deepseek&type=audio")
	if len(noMatch.Models) != 0 || noMatch.More || noMatch.Facets["all"]["deepseek"] != 6 {
		t.Fatalf("empty filtered page lost available brands: %+v", noMatch)
	}
}

func TestGatewayModelTermsStayWithConnection(t *testing.T) {
	s := testServer(t)
	s.distribution = true
	const admin = "/api/v1/gateway"
	add := func(name, origin string) Selection {
		up := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", admin+"/upstreams", map[string]string{
			"name": name, "base_url": origin, "api_key": gatewayTestKeyA,
		}), 201)
		requireCode(t, req(t, s, "POST", admin+"/upstream-model", struct {
			syncRequest
			Model string `json:"model_id"`
		}{up.syncRequest, "shared-model"}), 200)
		return Selection{ProviderID: up.ProviderID, AccountID: up.AccountID, CredentialID: up.CredentialID,
			ModelID: "shared-model", Endpoint: "CHAT_COMPLETIONS"}
	}
	a, b := add("A", "https://a.example/v1"), add("B", "https://b.example/v1")
	read := func(v Selection) gatewayTermEditor {
		q := url.Values{"provider_id": {v.ProviderID}, "account_id": {v.AccountID}, "credential_id": {v.CredentialID},
			"model_id": {v.ModelID}, "group": {v.Group}, "endpoint": {v.Endpoint}}
		return gatewayDecode[gatewayTermEditor](t, req(t, s, "GET", admin+"/offering-terms?"+q.Encode(), nil), 200)
	}
	save := func(v Selection, revision string, price *PriceLegs, daily *int64, code int) gatewayTermEditor {
		response := req(t, s, "POST", admin+"/offering-terms", struct {
			Selection
			Revision string     `json:"revision"`
			Price    *PriceLegs `json:"price"`
			Daily    *int64     `json:"daily,string"`
		}{v, revision, price, daily})
		if code != 200 {
			requireCode(t, response, code)
			return gatewayTermEditor{}
		}
		return gatewayDecode[gatewayTermEditor](t, response, code)
	}
	group := func(filter string) gatewayModelGroup {
		query := url.Values{"id": {"shared-model"}, "provider_id": {filter}}
		groups := gatewayDecode[struct{ Models []gatewayModelGroup }](t,
			req(t, s, "GET", admin+"/model-groups?"+query.Encode(), nil), 200).Models
		if len(groups) != 1 {
			t.Fatalf("missing scoped model group: %+v", groups)
		}
		return groups[0]
	}
	zero, paid, quoted := "0", "0.02", "0.09"
	free := &PriceLegs{Fixed: &zero, Unit: "USD/request"}
	fixed := &PriceLegs{Fixed: &paid, Unit: "USD/request"}
	nominal := &PriceLegs{Fixed: &quoted, Unit: "USD/request"}
	var noRequests int64
	twenty, negative := int64(20), int64(-1)
	before := read(a)
	if before.Terms.Price != nil || before.Terms.Daily != nil {
		t.Fatal("unknown price or quota was invented")
	}
	if err := s.observe(context.Background(), a, gatewayModelQuotaKind, "USER", "SUCCESS",
		map[string]any{"values": map[string]int{"rpm": 4}, "scope_kind": "MODEL", "slice": ""}); err != nil {
		t.Fatal(err)
	}
	before = read(a)
	saved := save(a, before.Revision, free, &noRequests, 200)
	if saved.Terms.Price == nil || !saved.Terms.Price.Free || saved.Terms.Daily == nil || saved.Terms.Daily.Value != 0 {
		t.Fatalf("explicit zeros lost: %+v", saved.Terms)
	}
	scoped := group(b.ProviderID)
	if scoped.Providers != 1 || scoped.Connections != 1 {
		t.Fatalf("provider filter counted connections from another provider: %+v", scoped)
	}
	if read(b).Terms.Price != nil || scoped.Terms["all"].Daily != nil {
		t.Fatal("provider filter borrowed another connection's terms")
	}
	save(a, before.Revision, fixed, &twenty, 409)
	save(a, saved.Revision, fixed, &negative, 400)
	if current := read(a); current.Revision != saved.Revision || !current.Terms.Price.Free {
		t.Fatal("failed or stale update partially overwrote current terms")
	}
	if err := s.observe(context.Background(), b, "CATALOG", "GENERIC_OFFICIAL", "SUCCESS",
		map[string]any{"price": nominal}); err != nil {
		t.Fatal(err)
	}
	if err := s.observe(context.Background(), b, "PRICING", "GENERIC_OFFICIAL", "NETWORK", nil); err != nil {
		t.Fatal(err)
	}
	savedB := save(b, read(b).Revision, fixed, &twenty, 200)
	if got := group("").Terms["all"]; !got.Price.Free || got.Daily.Value != twenty || got.Connections != 2 {
		t.Fatalf("incorrect model group summary: %+v", got)
	}
	if err := s.observe(context.Background(), a, "MANUAL_PRICE", "USER", "SUCCESS",
		map[string]any{"price": free, "effective_charge": free}); err != nil {
		t.Fatal(err)
	}
	if s.offerSummary(context.Background(), a)["effective_price"] == nil {
		t.Fatal("legacy confirmed price fixture missing")
	}
	if got := save(a, read(a).Revision, nil, nil, 200); got.Terms.Price != nil || got.Terms.Daily != nil {
		t.Fatal("cleared manual terms still show on the connection")
	}
	if s.offerSummary(context.Background(), a)["effective_price"] != nil {
		t.Fatal("clearing the current manual price revived an older effective quote")
	}
	assertSQLInt(t, s, 4, `SELECT json_extract(facts_json,'$.values.rpm') FROM observations
		WHERE kind='MANUAL_QUOTA:MODEL:' ORDER BY rowid DESC LIMIT 1`)
	restored := save(b, savedB.Revision, nil, nil, 200)
	if restored.Terms.Price == nil || restored.Terms.Price.Basis != "NOMINAL" || *restored.Terms.Price.Legs.Fixed != quoted || restored.Terms.Daily != nil {
		t.Fatal("failed synchronization or manual clearing erased the known catalog quote")
	}
}
