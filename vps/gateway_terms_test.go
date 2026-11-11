package main

import (
	"context"
	"net/url"
	"testing"
)

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
	if read(b).Terms.Price != nil || group(b.ProviderID).Terms["all"].Daily != nil {
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
