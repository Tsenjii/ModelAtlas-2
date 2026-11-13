package main

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestGatewayKeyPolicyEnforced(t *testing.T) {
	s := testServer(t)
	s.distribution = true
	ctx := t.Context()
	if _, err := s.Store.DB.ExecContext(ctx, "INSERT INTO gateway_models(id,routes_json,enabled,created_at) VALUES ('public-a','[]',1,?),('public-b','[]',1,?)", now(), now()); err != nil {
		t.Fatal(err)
	}
	const keys = "/api/v1/gateway/keys"
	past, future := now()-3600000, now()+3600000
	in := gatewayKeyInput{Label: "用途測試", Models: []string{"public-a"}, Daily: 4, ExpiresAt: &past}
	requireCode(t, req(t, s, "POST", keys, in), 400)
	in.ExpiresAt = nil
	issued := gatewayDecode[struct {
		gatewayKey
		Token string
	}](t, req(t, s, "POST", keys, in), 201)
	if issued.ID == "" || issued.Token == "" || issued.ExpiresAt != nil {
		t.Fatal("the existing create format must produce a valid, non-expiring key")
	}
	auth := func(code int) {
		t.Helper()
		requireCode(t, gatewayClient(s, issued.Token, "GET", "/v1/models", nil), code)
	}
	pause := func(paused bool, code int) {
		t.Helper()
		requireCode(t, req(t, s, "POST", keys+"/pause", map[string]any{"id": issued.ID, "paused": paused}), code)
	}
	auth(200)
	pause(true, 200)
	auth(401)

	in.ID, in.Models, in.Daily, in.ExpiresAt = issued.ID, []string{"public-b"}, 6, &future
	requireCode(t, req(t, s, "POST", keys+"/edit", in), 200)
	auth(401)
	pause(false, 200)
	list := gatewayClient(s, issued.Token, "GET", "/v1/models", nil)
	requireCode(t, list, 200)
	if !strings.Contains(list.Body.String(), "public-b") || strings.Contains(list.Body.String(), "public-a") {
		t.Fatalf("model restriction was not updated: %s", list.Body.String())
	}
	in.Models, in.Daily = []string{"does-not-exist"}, 9
	requireCode(t, req(t, s, "POST", keys+"/edit", in), 422)
	response := req(t, s, "GET", keys, nil)
	rows := gatewayDecode[[]gatewayKey](t, response, 200)
	if len(rows) != 1 || rows[0].DailyLimit != 6 || rows[0].Paused || rows[0].ExpiresAt == nil ||
		*rows[0].ExpiresAt != future || strings.Contains(response.Body.String(), issued.Token) {
		t.Fatal("stored policy changed unexpectedly or leaked the token")
	}
	if _, err := s.Store.DB.ExecContext(ctx, "UPDATE gateway_key_policies SET expires_at=? WHERE key_id=?", past, issued.ID); err != nil {
		t.Fatal(err)
	}
	auth(401)
	requireCode(t, gatewayClient(s, issued.Token, "POST", "/v1/chat/completions", gatewayTestChat("public-b", "blocked", true)), 401)
	requireCode(t, req(t, s, "POST", keys+"/revoke", map[string]string{"id": issued.ID}), 200)
	pause(false, 404)
	in.Models = []string{"public-b"}
	requireCode(t, req(t, s, "POST", keys+"/edit", in), 422)
	auth(401)
}

func TestGatewayOfferingSortKeepsScopeAndMissingMetrics(t *testing.T) {
	s := testServer(t)
	scope := seed(t, s, "https://provider.example/v1")
	for _, id := range []string{"00-no-samples", "model-a", "model-b", "model-c"} {
		scope.ModelID = id
		requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: scope.ProviderID, ID: id, DisplayName: id}), 201)
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	}
	at := now()
	sample := func(id, model, outcome, details string, ttft, latency any, created int64) {
		t.Helper()
		if _, err := s.Store.DB.ExecContext(t.Context(), `INSERT INTO telemetry(
			id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,details_json,ttft_ms,latency_ms,created_at)
			VALUES(?,?,?,?,?,'','CHAT_COMPLETIONS','CHAT_COMPLETIONS','GATEWAY_TEST',?,?,?,?,?)`,
			id, scope.ProviderID, scope.AccountID, scope.CredentialID, model, outcome, details, ttft, latency, created); err != nil {
			t.Fatal(err)
		}
	}
	sample("a-success", "model-a", "SUCCESS", `{"tps":0}`, 8, 90, at)
	sample("a-failure", "model-a", "UPSTREAM_5XX", `{}`, nil, nil, at)
	sample("b-success", "model-b", "SUCCESS", `{"tps":12.5}`, 40, 30, at)
	sample("c-failure", "model-c", "UPSTREAM_5XX", `{}`, nil, nil, at)
	sample("d-cancelled", "00-no-samples", "CANCELLED", `{"tps":300}`, 1, 1, at)
	sample("d-old", "00-no-samples", "SUCCESS", `{"tps":300}`, 1, 1, at-8*86400000)

	s.distribution = true
	type page struct {
		Offerings []gatewayOffering
		More      bool
	}
	for _, tc := range []struct {
		sort string
		ids  [4]string
	}{
		{"ttft", [4]string{"model-a", "model-b", "00-no-samples", "model-c"}},
		{"tps", [4]string{"model-b", "model-a", "00-no-samples", "model-c"}},
		{"latency", [4]string{"model-b", "model-a", "00-no-samples", "model-c"}},
		{"success", [4]string{"model-b", "model-a", "model-c", "00-no-samples"}},
	} {
		for _, offset := range []int{0, 2} {
			path := fmt.Sprintf("/api/v1/gateway/offerings?sort=%s&limit=2&offset=%d", tc.sort, offset)
			result := gatewayDecode[page](t, req(t, s, "GET", path, nil), 200)
			if len(result.Offerings) != 2 || result.More != (offset == 0) ||
				result.Offerings[0].ModelID != tc.ids[offset] || result.Offerings[1].ModelID != tc.ids[offset+1] {
				t.Fatalf("%s page %d sorted the wrong records: %+v", tc.sort, offset, result)
			}
			for _, offer := range result.Offerings {
				p := offer.Performance
				switch offer.ModelID {
				case "model-a":
					if p.Samples != 2 || p.Successes != 1 || p.TPS == nil || *p.TPS != 0 || p.TTFT == nil || *p.TTFT != 8 || p.Latency == nil || *p.Latency != 90 {
						t.Fatalf("%s changed the complete performance payload: %+v", tc.sort, p)
					}
				case "00-no-samples":
					if p.Samples != 0 || p.Successes != 0 || p.TPS != nil || p.TTFT != nil || p.Latency != nil {
						t.Fatalf("%s counted an old or cancelled request: %+v", tc.sort, p)
					}
				}
			}
		}
	}
}

func TestGatewayUsageIntervalsAndSources(t *testing.T) {
	s := testServer(t)
	s.distribution = true
	zone := time.FixedZone("Asia/Taipei", 8*60*60)
	first := time.Date(2026, 2, 2, 0, 0, 0, 0, zone).UnixMilli()
	last := time.Date(2026, 2, 8, 12, 0, 0, 0, zone).UnixMilli()
	ctx := t.Context()
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := s.Store.DB.ExecContext(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec("INSERT INTO gateway_models(id,routes_json,enabled,created_at) VALUES('public-a','[]',1,?)", first)
	exec("INSERT INTO gateway_keys(id,label,digest,prefix,models_json,daily_limit,enabled,created_at) VALUES ('key-a','Key A',X'AB','sk-ma-test','[\"public-a\"]',50,1,?)", first)
	sample := func(id, source, model string, at int64, input, output, ttft *int64) {
		t.Helper()
		exec(`INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,
			source,outcome,ttft_ms,input_tokens,output_tokens,created_at) VALUES (?,'site-a','acct','up-key',?,'','CHAT_COMPLETIONS',
			'CHAT_COMPLETIONS',?,'SUCCESS',?,?,?,?)`, id, model, source, ttft, input, output, at)
	}
	add := func(id, outcome string, at int64, sampleID *string) {
		t.Helper()
		exec("INSERT INTO gateway_requests(id,key_id,model_id,created_at,outcome,attempts,sample_id) VALUES(?,'key-a','public-a',?,?,2,?)", id, at, outcome, sampleID)
	}
	zero, input, output, ttft := int64(0), int64(5), int64(11), int64(42)
	sample("first-attempt", "GATEWAY", "up-a", first, &input, nil, nil)
	sample("second-attempt", "GATEWAY", "up-b", first, &zero, &output, &ttft)
	id := "second-attempt"
	add("forwarded", "SUCCESS", first, &id)
	add("failure", "UPSTREAM_5XX", first+1000, nil)
	add("cancelled", "CANCELLED", first+2000, nil)
	add("old", "SUCCESS", first-1, nil)
	add("future", "SUCCESS", last, nil)
	sample("direct-probe", "GATEWAY_TEST", "Probe_Model", last-1, &input, nil, &ttft)

	type payload struct {
		Summary gatewayUsageSummary
		Items   []gatewayUsageItem
		Trend   []gatewayUsagePoint
		Ranking []gatewayUsageRank
		More    bool
	}
	query := fmt.Sprintf("/api/v1/gateway/usage?range=7d&as_of=%d", last)
	load := func(filters string) payload {
		t.Helper()
		return gatewayDecode[payload](t, req(t, s, "GET", query+filters, nil), 200)
	}
	all := load("&source=all&limit=1")
	if all.Summary.Requests != 4 || all.Summary.Completed != 3 || all.Summary.Successes != 2 ||
		all.Summary.Input == nil || *all.Summary.Input != 5 || all.Summary.Output == nil || *all.Summary.Output != 11 ||
		all.Summary.InputReported != 2 || all.Summary.OutputReported != 1 || all.Summary.MeanTTFT == nil ||
		*all.Summary.MeanTTFT != 42 || !all.More || len(all.Items) != 1 || all.Items[0].ID != "direct-probe" {
		t.Fatalf("wrong chart summary or pagination: %+v", all.Summary)
	}
	if len(all.Trend) != 7 || all.Trend[0].From != first || all.Trend[0].Requests != 3 ||
		all.Trend[0].Input == nil || *all.Trend[0].Input != 0 || all.Trend[1].Input != nil ||
		all.Trend[6].Until != last || all.Trend[6].Requests != 1 {
		t.Fatal("local day boundaries or unknown token values were lost")
	}
	if len(all.Ranking) != 2 || all.Ranking[0] != (gatewayUsageRank{Source: "gateway", Model: "public-a", Requests: 3}) ||
		all.Ranking[1] != (gatewayUsageRank{Source: "tests", Model: "probe-model", Requests: 1}) {
		t.Fatalf("rank should count relay admission once: %+v", all.Ranking)
	}
	next := load("&source=all&limit=1&offset=1&items_only=1")
	if !next.More || len(next.Items) != 1 || next.Items[0].ID != "cancelled" ||
		next.Summary.Requests != 0 || next.Trend != nil || next.Ranking != nil {
		t.Fatalf("record-only page should preserve the anchored order and omit aggregates: %+v", next)
	}
	failed := load("&source=all&key_id=key-a&status=failed&model=public")
	if failed.Summary.Requests != 1 || failed.Summary.Input != nil || len(failed.Items) != 1 || failed.Items[0].ID != "failure" {
		t.Fatalf("failure filter did not match chart and list: %+v", failed)
	}
	tests := load("&source=tests&model=probe-model")
	if tests.Summary.Requests != 1 || len(tests.Items) != 1 || tests.Items[0].Source != "tests" || tests.Items[0].Model != "Probe_Model" {
		t.Fatalf("invalid model-test filter: %+v", tests)
	}
	exec(`INSERT INTO knowledge_versions(id,kind,subject,version,data_json,evidence_json,reason,status,created_at)
		VALUES('usage-alias','MODEL_ALIAS_RULE','site-a/Probe_Model',1,'{"canonical_id":"alias-family"}','{}','fixture','APPROVED',?)`, first)
	exec("INSERT INTO knowledge_active VALUES('MODEL_ALIAS_RULE','site-a/Probe_Model','usage-alias')")
	sample("same-id-other-provider", "MANUAL_TEST", "Probe_Model", last-2, nil, nil, nil)
	exec("UPDATE telemetry SET provider_id='site-b' WHERE id='same-id-other-provider'")
	alias := load("&source=all&model=alias-family")
	if alias.Summary.Requests != 1 || len(alias.Items) != 1 || alias.Items[0].ID != "direct-probe" ||
		len(alias.Ranking) != 1 || alias.Ranking[0] != (gatewayUsageRank{Source: "tests", Model: "alias-family", Requests: 1}) {
		t.Fatalf("alias filter crossed provider scope or excluded a historical model: %+v", alias)
	}

	for i := range 21 {
		sample(fmt.Sprintf("history-%d", i), "MANUAL_TEST", "up-b", first+int64(i+1), &zero, nil, &ttft)
	}
	exec(`UPDATE telemetry SET details_json='{"tps":0,"private":"internal-detail"}' WHERE id='history-20'`)
	exec(`UPDATE telemetry SET group_id='other',model_id='up-b',created_at=? WHERE id='first-attempt'`, last)
	const history = "/api/v1/gateway/offering-history?provider_id=site-a&account_id=acct&credential_id=up-key&model_id=up-b&endpoint=CHAT_COMPLETIONS"
	requireCode(t, req(t, s, "GET", history, nil), 400)
	requireCode(t, gatewayClient(s, "invalid", "GET", history+"&group=", nil), 401)
	reply := req(t, s, "GET", history+"&group=", nil)
	samples := gatewayDecode[[]gatewayOfferSample](t, reply, 200)
	if len(samples) != 20 || samples[0].At != first+21 || samples[19].At != first+2 ||
		samples[0].TPS == nil || *samples[0].TPS != 0 || samples[0].InputTokens == nil || *samples[0].InputTokens != 0 ||
		samples[0].OutputTokens != nil || samples[0].TTFB != nil || strings.Contains(reply.Body.String(), "internal-detail") {
		t.Fatalf("incorrect scoped history, bounds or missing-data representation: %s", reply.Body)
	}
	other := gatewayDecode[[]gatewayOfferSample](t, req(t, s, "GET", history+"&group=other", nil), 200)
	if len(other) != 1 || other[0].At != last {
		t.Fatalf("expected the selected group only: %+v", other)
	}
}
