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
	failed := load("&source=all&key_id=key-a&status=failed&model=public")
	if failed.Summary.Requests != 1 || failed.Summary.Input != nil || len(failed.Items) != 1 || failed.Items[0].ID != "failure" {
		t.Fatalf("failure filter did not match chart and list: %+v", failed)
	}
	tests := load("&source=tests&model=probe-model")
	if tests.Summary.Requests != 1 || len(tests.Items) != 1 || tests.Items[0].Source != "tests" || tests.Items[0].Model != "Probe_Model" {
		t.Fatalf("invalid model-test filter: %+v", tests)
	}
}
