package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const gatewayTestKeyA = "upstream-a-fixture-Key_abcdefghijklmnopqrstuvwxyz"
const gatewayTestKeyB = "upstream-b-fixture-Key_ABCDEFGHIJKLMNOPQRSTUVWXYZ"
const gatewayTestPAT = "upstream-account-fixture-PAT_0123456789"
const gatewayBrowserAgent = "Atlas-Allowed-Fixture/1.0"
const gatewayBrowserProof = "synthetic-browser-client-proof"

type gatewayFixture struct {
	*httptest.Server
	a, b         atomic.Int32
	verification atomic.Int32
	images       atomic.Int32
	headerCalls  atomic.Int32
	bRateLimitAt atomic.Int32
	perfDown     atomic.Bool
}

func newGatewayFixture(t *testing.T, s *Server, models [2]string) *gatewayFixture {
	t.Helper()
	f := &gatewayFixture{}
	f.Server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		proof := r.Header.Get("X-Client-Proof")
		if r.UserAgent() == gatewayBrowserAgent || proof != "" {
			if r.URL.Path != "/a/v1/chat/completions" || r.UserAgent() != gatewayBrowserAgent || proof != gatewayBrowserProof {
				t.Error("browser header settings were sent outside their exact model connection")
			} else {
				f.headerCalls.Add(1)
			}
		}
		switch r.URL.Path {
		case "/api/status":
			if auth != "" {
				t.Error("public provider status received a credential")
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"success":true,"data":{"version":"v1.0.0-rc.25","checkin_enabled":true,"quota_per_unit":500000}}`)
			return
		case "/api/pricing":
			if auth != "" || r.Header.Get("Cookie") != "" {
				t.Error("public pricing received private authentication")
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"success":true,"data":[{"model_name":%q,"quota_type":0,"model_ratio":1,"completion_ratio":2,"enable_groups":["standard"],"supported_endpoint_types":["openai"]}],"group_ratio":{"standard":1}}`, models[0])
			return
		case "/api/notice":
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"success":true,"data":"站方測試公告"}`)
			return
		case "/api/perf-metrics/summary":
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"success":true,"data":{"models":[]}}`)
			return
		case "/api/perf-metrics":
			if auth != "" || r.Header.Get("Cookie") != "" {
				t.Error("public model performance received private authentication")
			}
			model := r.URL.Query().Get("model")
			var ttft int
			switch model {
			case models[0]:
				ttft = 81
			case models[1]:
				ttft = 197
			default:
				http.Error(w, "wrong model", 404)
				return
			}
			if f.perfDown.Load() {
				http.Error(w, "provider performance unavailable", 503)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			hour := time.Now().Unix() / 3600 * 3600
			series := []string{}
			for i := 2; i >= 0; i-- {
				if r.URL.Query().Get("hours") == "1" && i != 0 {
					continue
				}
				series = append(series, fmt.Sprintf(`{"ts":%d,"avg_ttft_ms":%d,"avg_tps":46.5,"avg_latency_ms":683,"success_rate":98.7}`, hour-int64(i)*3600, ttft+i*14))
			}
			points := strings.Join(series, ",")
			fmt.Fprintf(w, `{"success":true,"data":{"model_name":%q,"series_schema":"v1","groups":[{"group":"standard","avg_ttft_ms":%d,"avg_tps":46.5,"avg_latency_ms":683,"success_rate":98.7,"request_count":7,"series":[%s]}],"summary":{"avg_ttft_ms":%d,"avg_tps":46.5,"avg_latency_ms":683,"success_rate":98.7,"request_count":7},"series":[%s]}}`, model, ttft+1, points, ttft, points)
			return
		case "/api/usage/token/":
			if auth != "Bearer "+gatewayTestKeyA {
				t.Error("provider Key quota received the wrong credential")
				http.Error(w, "unauthorized", 401)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"code":true,"data":{"total_available":0,"total_granted":1200,"total_used":1200}}`)
			return
		case "/api/user/self":
			if auth != "Bearer "+gatewayTestPAT {
				t.Error("provider account quota received the wrong credential")
				http.Error(w, "unauthorized", 401)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"success":true,"data":{"quota":45600,"used_quota":400}}`)
			return
		}
		side, key, model := "a", gatewayTestKeyA, models[0]
		if strings.HasPrefix(r.URL.Path, "/b/") {
			side, key, model = "b", gatewayTestKeyB, models[1]
		}
		if auth != "Bearer "+key {
			t.Error("gateway sent the wrong upstream credential")
			http.Error(w, "unauthorized", 401)
			return
		}
		switch r.URL.Path {
		case "/" + side + "/v1/models":
			w.Header().Set("Content-Type", "application/json")
			display := model
			if strings.Contains(strings.ToLower(model), "deepseek") {
				display = "DeepSeek 4.1 Flash"
			}
			fmt.Fprintf(w, `{"data":[{"id":%q,"name":%q},{"id":%q}]}`, model, display, model)
			return
		case "/a/v1/images/generations":
			var image struct {
				Model  string `json:"model"`
				Prompt string `json:"prompt"`
				N      int    `json:"n"`
				Format string `json:"response_format"`
			}
			if json.NewDecoder(r.Body).Decode(&image) != nil || image.Model != "gpt-image-fixture" ||
				image.Prompt != "fixture image preview" || image.N != 1 || image.Format != "b64_json" {
				t.Error("image test did not send the chosen scope and prompt")
				http.Error(w, "wrong image request", 400)
				return
			}
			f.images.Add(1)
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"data":[{"b64_json":%q}]}`, testPNG(t))
			return
		case "/" + side + "/v1/chat/completions":
		default:
			http.NotFound(w, r)
			return
		}
		var in struct {
			Model     string `json:"model"`
			Stream    bool   `json:"stream"`
			MaxTokens int    `json:"max_tokens"`
			Messages  []struct {
				Content string `json:"content"`
			} `json:"messages"`
			Tools []json.RawMessage `json:"tools"`
		}
		if json.NewDecoder(r.Body).Decode(&in) != nil || in.Model != model || len(in.Messages) == 0 {
			t.Error("gateway did not send the selected model or chat body")
			http.Error(w, "bad body", 400)
			return
		}
		prompt := in.Messages[0].Content
		if prompt == pelicanPrompt {
			if in.Stream || in.MaxTokens != pelicanMaxTokens {
				t.Error("verification did not use the exact selected scope and fixed prompt settings")
			}
			n := f.verification.Add(1)
			drawing := fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 480"><circle cx="%d" cy="100" r="40"/></svg>`, 80+n*20)
			if side == "b" && n == 3 {
				drawing = "upstream returned text instead of an SVG"
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"model":%q,"choices":[{"message":{"role":"assistant","content":%q}}],"usage":{"prompt_tokens":30,"completion_tokens":100}}`, model, drawing)
			return
		}
		if side == "a" {
			f.a.Add(1)
			if prompt == "fallback" {
				http.Error(w, "upstream capacity", 429)
				return
			}
		} else {
			if f.b.Add(1) == f.bRateLimitAt.Load() {
				http.Error(w, "upstream capacity", 429)
				return
			}
			time.Sleep(50 * time.Millisecond)
		}
		if prompt == "upstream-error" {
			http.Error(w, key, 500)
			return
		}
		if !in.Stream {
			if len(in.Tools) != 1 {
				t.Error("gateway did not forward the tools parameter")
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"model":%q,"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":4,"completion_tokens":0}}`, model)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		if prompt == "split-secret" {
			for _, part := range []string{key[:18], key[18:]} {
				fmt.Fprintf(w, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":%q}}]}\n\n", part)
				w.(http.Flusher).Flush()
			}
		} else {
			for _, part := range []string{"O", "K"} {
				fmt.Fprintf(w, "data: {\"model\":%q,\"choices\":[{\"index\":0,\"delta\":{\"content\":%q}}]}\n\n", model, part)
				w.(http.Flusher).Flush()
				time.Sleep(4 * time.Millisecond)
			}
		}
		if prompt != "broken-stream" {
			fmt.Fprint(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n")
		}
	}))
	s.Client = f.Client()
	t.Cleanup(f.Close)
	return f
}

func gatewayDecode[T any](t *testing.T, r *httptest.ResponseRecorder, code int) (v T) {
	t.Helper()
	requireCode(t, r, code)
	if err := json.Unmarshal(r.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func gatewayClient(s *Server, token, method, path string, body any) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(body)
	r := httptest.NewRequest(method, path, bytes.NewReader(raw))
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	return w
}

func gatewayTestChat(alias, prompt string, stream bool) map[string]any {
	return map[string]any{"model": alias, "messages": []map[string]string{{"role": "user", "content": prompt}},
		"stream": stream, "tools": []map[string]any{{"type": "function", "function": map[string]string{"name": "test_tool"}}}}
}

func TestGatewayDistributionWorkflow(t *testing.T) {
	s := testServer(t)
	f := newGatewayFixture(t, s, [2]string{"model-a", "model-b"})
	const admin = "/api/v1/gateway"
	requireCode(t, req(t, s, "GET", admin+"/upstreams", nil), 404)
	s.distribution = true
	checkGatewayBrowserLogin(t, s)
	requireCode(t, req(t, s, "GET", "/api/v1/providers", nil), 404)
	requireCode(t, req(t, s, "POST", "/api/v1/research/probe", map[string]any{}), 404)

	add := func(side, name, key string) gatewayUpstream {
		r := req(t, s, "POST", admin+"/upstreams", map[string]string{"name": name, "base_url": f.URL + "/" + side + "/v1", "api_key": key})
		up := gatewayDecode[gatewayUpstream](t, r, 201)
		if strings.Contains(r.Body.String(), key) || up.AuthMode != "BEARER" || !up.Enabled {
			t.Fatal("invalid upstream response")
		}
		for _, want := range []int{1, 0} {
			result := gatewayDecode[map[string]int](t, req(t, s, "POST", admin+"/sync", up.syncRequest), 200)
			if result["added"] != want || result["models_seen"] != 1 {
				t.Fatalf("unexpected catalog counts: %v", result)
			}
		}
		return up
	}
	a, b := add("a", "供應商 A", gatewayTestKeyA), add("b", "供應商 B", gatewayTestKeyB)
	manual := struct {
		syncRequest
		Model string `json:"model_id"`
	}{a.syncRequest, "aaa-manual-model"}
	requireCode(t, req(t, s, "POST", admin+"/upstream-model", manual), 200)
	scope := func(up gatewayUpstream, id string) Selection {
		return Selection{ProviderID: up.ProviderID, AccountID: up.AccountID, CredentialID: up.CredentialID, ModelID: id, Endpoint: "CHAT_COMPLETIONS"}
	}
	routeA, routeB := scope(a, "model-a"), scope(b, "model-b")
	// A canonical identity saved in the original workspace must group the
	// same model in the lightweight UI without changing the real destinations.
	s.distribution = false
	requireCode(t, req(t, s, "POST", "/api/v1/models/metadata", ModelMetadata{
		ProviderID: a.ProviderID, ModelID: routeA.ModelID, CanonicalID: "family-model", Category: "OTHER",
	}), 200)
	s.distribution = true
	requireCode(t, req(t, s, "POST", admin+"/model-group", map[string]string{
		"provider_id": b.ProviderID, "model_id": routeB.ModelID, "canonical_id": "family-model",
	}), 200)
	readGroups := func(query string) []gatewayModelGroup {
		return gatewayDecode[struct{ Models []gatewayModelGroup }](t, req(t, s, "GET", admin+"/model-groups?"+query, nil), 200).Models
	}
	if groups := readGroups("q=family-model"); len(groups) != 1 || groups[0].Providers != 2 || groups[0].Connections != 2 {
		t.Fatalf("same model did not group both sites: %+v", groups)
	}
	if groups := readGroups("q=family-model&provider_id=" + a.ProviderID); len(groups) != 1 ||
		groups[0].ID != "family-model" || groups[0].Providers != 1 || groups[0].Connections != 1 {
		t.Fatalf("provider filter did not preserve the selected model group and its local connections: %+v", groups)
	}
	for _, v := range []Selection{routeA, routeB} {
		for i := 0; i < 3; i++ {
			out := gatewayDecode[struct {
				Outcome     string
				Performance gatewayPerformance
			}](t, req(t, s, "POST", admin+"/probe", v), 200)
			if out.Outcome != "SUCCESS" || out.Performance.Samples != i+1 || out.Performance.TTFT == nil || out.Performance.TPS == nil {
				t.Fatalf("incorrect measured performance: %+v", out)
			}
		}
	}
	ordered, err := s.gatewayRankRoutes(context.Background(), []Selection{routeB, routeA}, true)
	if err != nil || len(ordered) != 2 || ordered[0] != routeA {
		t.Fatal("healthy routes were not ordered by measured TTFT", err)
	}
	type offeringsPage struct {
		Offerings []gatewayOffering
		More      bool
	}
	for _, query := range []string{"?limit=1", "?limit=1&sort=ttft"} {
		page := gatewayDecode[offeringsPage](t, req(t, s, "GET", admin+"/offerings"+query, nil), 200)
		want := manual.Model
		if strings.Contains(query, "sort") {
			want = routeA.ModelID
		}
		if !page.More || len(page.Offerings) != 1 || page.Offerings[0].ModelID != want {
			t.Fatalf("first result did not honor sort before paging: %+v", page)
		}
	}
	page := gatewayDecode[offeringsPage](t, req(t, s, "GET", admin+"/offerings?canonical_id=family-model&sort=ttft", nil), 200)
	if len(page.Offerings) != 2 || page.Offerings[0].Selection != routeA || page.Offerings[1].Selection != routeB ||
		*page.Offerings[0].Performance.TTFT >= *page.Offerings[1].Performance.TTFT {
		t.Fatalf("group failed to compare exact scope measurements: %+v", page.Offerings)
	}
	for _, model := range []gatewayModel{
		{ID: "atlas-chat", Routes: []Selection{routeA, routeB}, Enabled: true},
		{ID: "private", Routes: []Selection{routeB}, Enabled: true},
	} {
		requireCode(t, req(t, s, "POST", admin+"/models", model), 200)
	}
	type issuedKey struct {
		gatewayKey
		Token string
	}
	issue := func(label, model string, limit int) issuedKey {
		v := gatewayDecode[issuedKey](t, req(t, s, "POST", admin+"/keys", map[string]any{"label": label, "models": []string{model}, "daily_limit": limit}), 201)
		if !strings.HasPrefix(v.Token, gatewayKeyPrefix) {
			t.Fatal("missing one-time key")
		}
		var digest []byte
		want := sha256.Sum256([]byte(v.Token))
		if err := s.Store.DB.QueryRow("SELECT digest FROM gateway_keys WHERE id=?", v.ID).Scan(&digest); err != nil || !bytes.Equal(digest, want[:]) {
			t.Fatal("key was not stored as its digest", err)
		}
		return v
	}
	k := issue("手機", "atlas-chat", 3)
	call := func(method, path string, body any) *httptest.ResponseRecorder {
		return gatewayClient(s, k.Token, method, path, body)
	}
	requireCode(t, req(t, s, "GET", "/v1/models", nil), 401)
	requireCode(t, call("GET", admin+"/keys", nil), 401)
	r := call("GET", "/v1/models", nil)
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), `"id":"atlas-chat"`) || strings.Contains(r.Body.String(), "private") {
		t.Fatal("public catalog exceeded key scope")
	}
	requireCode(t, call("POST", "/v1/chat/completions", gatewayTestChat("private", "ok", false)), 404)
	if f.a.Load() != 3 || f.b.Load() != 3 {
		t.Fatal("unauthorized chat reached an upstream")
	}
	r = call("POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "fallback", true))
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "data: [DONE]") || !strings.Contains(r.Body.String(), `"model":"atlas-chat"`) ||
		strings.Contains(r.Body.String(), "model-b") || !strings.HasPrefix(r.Header().Get("Content-Type"), "text/event-stream") {
		t.Fatal("stream did not retain its public alias and terminator")
	}
	if f.a.Load() != 4 || f.b.Load() != 4 {
		t.Fatalf("429 failed to fall back once: %d / %d", f.a.Load(), f.b.Load())
	}
	receipt := gatewayDecode[struct {
		Summary gatewayUsageSummary
		Items   []gatewayUsageItem
	}](t, req(t, s, "GET", admin+"/usage?key_id="+k.ID, nil), 200)
	if receipt.Summary.Requests != 1 || receipt.Summary.Successes != 1 || len(receipt.Items) != 1 {
		t.Fatal("failover consumed more than one request")
	}
	v := receipt.Items[0]
	if v.Provider != "供應商 B" || v.UpstreamModel != "model-b" || v.Attempts != 2 || v.Output == nil || *v.Output != 2 {
		t.Fatalf("inaccurate fallback usage: %+v", v)
	}
	r = call("POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "ok", false))
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), `"model":"atlas-chat"`) || !strings.Contains(r.Body.String(), `"completion_tokens":0`) {
		t.Fatal("ordinary JSON response changed the model or known zero usage")
	}
	// B now has a better success rate. A failed request with assigned
	// connection must never be retried on A.
	beforeA := f.a.Load()
	r = call("POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "broken-stream", true))
	requireCode(t, r, 200)
	if f.a.Load() != beforeA || !strings.Contains(r.Body.String(), "stream_interrupted") || strings.Contains(r.Body.String(), "[DONE]") {
		t.Fatal("interrupted stream was retried or reported as complete")
	}
	r = call("POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "quota", true))
	requireCode(t, r, 429)
	if !strings.Contains(r.Body.String(), "daily_limit_reached") || r.Header().Get("Retry-After") == "" {
		t.Fatal("daily limit was not enforced")
	}
	requireCode(t, req(t, s, "POST", admin+"/keys/revoke", map[string]string{"id": k.ID}), 200)
	requireCode(t, call("GET", "/v1/models", nil), 401)
	requireCode(t, call("POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "revoked", true)), 401)

	private := issue("限額", "private", 1)
	var wg sync.WaitGroup
	results := make(chan *httptest.ResponseRecorder, 2)
	for i := 0; i < 2; i++ {
		wg.Go(func() {
			results <- gatewayClient(s, private.Token, "POST", "/v1/chat/completions", gatewayTestChat("private", "quota-race", false))
		})
	}
	wg.Wait()
	close(results)
	statuses := map[int]int{}
	for result := range results {
		statuses[result.Code]++
	}
	if statuses[200] != 1 || statuses[429] != 1 {
		t.Fatalf("concurrent quota check produced %v", statuses)
	}
	private = issue("測試敏感資料", "private", 1)
	r = gatewayClient(s, private.Token, "POST", "/v1/chat/completions", gatewayTestChat("private", "split-secret", true))
	requireCode(t, r, 502)
	if strings.Contains(r.Body.String(), gatewayTestKeyB[:18]) || !strings.Contains(r.Body.String(), "secret_in_response") {
		t.Fatal("stream exposed a prefix of an upstream key")
	}
	failureKey := issue("測試錯誤", "atlas-chat", 1)
	before := f.a.Load() + f.b.Load()
	r = gatewayClient(s, failureKey.Token, "POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "upstream-error", true))
	requireCode(t, r, 502)
	if f.a.Load()+f.b.Load() != before+1 || strings.Contains(r.Body.String(), "fixture-Key") {
		t.Fatal("upstream 5xx retried or returned a private response")
	}
	r = req(t, s, "GET", admin+"/keys", nil)
	requireCode(t, r, 200)
	for _, secret := range []string{k.Token, private.Token, failureKey.Token, gatewayTestKeyA, gatewayTestKeyB} {
		if strings.Contains(r.Body.String(), secret) {
			t.Fatal("listing returned a full secret")
		}
	}
	checkGatewayUpdateWorkflow(t, s)
	checkGatewayConnectionManagement(t, s, f, routeA, routeB, k.ID, k.Token, failureKey.ID, failureKey.Token)
}

func checkGatewayConnectionManagement(t *testing.T, s *Server, f *gatewayFixture, a, b Selection, revokedID, revokedToken, activeID, activeToken string) {
	t.Helper()
	const admin = "/api/v1/gateway"
	query := func(v Selection) string {
		return (url.Values{"provider_id": {v.ProviderID}, "account_id": {v.AccountID}, "credential_id": {v.CredentialID},
			"model_id": {v.ModelID}, "group": {v.Group}, "endpoint": {v.Endpoint}}).Encode()
	}
	copyPath := "/ui/api/gateway/credential/copy"
	credential := syncRequest{a.ProviderID, a.AccountID, a.CredentialID}
	encoded, _ := json.Marshal(credential)
	requireCode(t, req(t, s, "POST", admin+"/credential/copy", credential), 404)
	requireCode(t, req(t, s, "POST", copyPath, credential), 403)
	server := httptest.NewTLSServer(s.Handler())
	defer server.Close()
	origin := server.URL
	login := httptest.NewRequest("POST", origin+"/login", strings.NewReader((url.Values{"username": {"admin"}, "password": {s.Token}}).Encode()))
	login.Header.Set("Origin", origin)
	login.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	auth := httptest.NewRecorder()
	s.Handler().ServeHTTP(auth, login)
	requireCode(t, auth, 303)
	cookie := auth.Result().Cookies()[0]
	copyCall := func(target string, session bool, scope []byte) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", origin+copyPath, bytes.NewReader(scope))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", target)
		if session {
			r.AddCookie(cookie)
		}
		out := httptest.NewRecorder()
		s.Handler().ServeHTTP(out, r)
		return out
	}
	requireCode(t, copyCall(origin, false, encoded), 401)
	requireCode(t, copyCall("https://example.net", true, encoded), 403)
	wrong, _ := json.Marshal(syncRequest{a.ProviderID, a.AccountID, b.CredentialID})
	requireCode(t, copyCall(origin, true, wrong), 404)
	copyResponse := copyCall(origin, true, encoded)
	value := gatewayDecode[map[string]string](t, copyResponse, 200)
	if value["api_key"] != gatewayTestKeyA || len(value) != 1 || copyResponse.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("credential copy did not return the exact stored Key in a non-cacheable response")
	}

	for i := range 25 {
		outcome := "SUCCESS"
		if i == 24 {
			outcome = "CANCELLED"
		}
		if _, err := s.Store.DB.Exec(`INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,
			operation,source,outcome,ttft_ms,latency_ms,created_at)
			VALUES(?,?,?,?,?,?,?,'CHAT_COMPLETIONS','MANUAL_TEST',?,80,900,?)`,
			newID(), a.ProviderID, a.AccountID, a.CredentialID, a.ModelID, a.Group, a.Endpoint, outcome, now()-7200000+int64(i)); err != nil {
			t.Fatal(err)
		}
	}
	type localResult struct {
		Selection Selection
		Local     struct {
			Samples, Cancelled int
			Source             string
			History            []gatewayOfferSample
		}
	}
	local := func(window string) localResult {
		return gatewayDecode[localResult](t, req(t, s, "GET", admin+"/offering-performance?"+query(a)+"&window="+window, nil), 200)
	}
	lastHour, lastDay := local("1h"), local("24h")
	if lastDay.Selection != a || lastDay.Local.Source != "MY_KEY_LOCAL" || lastDay.Local.Cancelled != 1 ||
		lastDay.Local.Samples != lastHour.Local.Samples+24 || len(lastDay.Local.History) <= 20 {
		t.Fatal("performance window ignored older samples or counted a cancellation")
	}
	type siteRequest struct {
		Selection
		Hours int `json:"hours"`
	}
	path := admin + "/offering-site"
	requireCode(t, req(t, s, "POST", path, siteRequest{a, 721}), 400)
	wrongScope := a
	wrongScope.Group = "different"
	requireCode(t, req(t, s, "POST", path, siteRequest{wrongScope, 168}), 404)
	cache := func(v Selection) string { return path + "?" + query(v) + "&hours=168" }
	before := gatewayDecode[*NewApiResult](t, req(t, s, "GET", cache(a), nil), 200)
	if before != nil {
		t.Fatal("unexpected initial site snapshot")
	}
	for _, v := range []Selection{a, b} {
		fresh := gatewayDecode[NewApiResult](t, req(t, s, "POST", path, siteRequest{v, 168}), 200)
		cached := gatewayDecode[NewApiResult](t, req(t, s, "GET", cache(v), nil), 200)
		if fresh.ProviderID != v.ProviderID || fresh.Role != "ANONYMOUS" || fresh.AccountID != "" || fresh.CredentialID != "" ||
			fresh.Facts["model_name"] != v.ModelID || cached.Facts["model_name"] != v.ModelID || cached.Facts["origin"] != fresh.Facts["origin"] {
			t.Fatal("incorrect exact public snapshot")
		}
	}
	f.perfDown.Store(true)
	requireCode(t, req(t, s, "POST", path, siteRequest{a, 168}), 502)
	if cached := gatewayDecode[NewApiResult](t, req(t, s, "GET", cache(a), nil), 200); cached.Facts["model_name"] != a.ModelID {
		t.Fatal("a failed refresh erased the last-good result")
	}

	setQuota := func(amount string, clear bool) {
		requireCode(t, req(t, s, "POST", admin+"/quota", map[string]any{"provider_id": a.ProviderID, "account_id": a.AccountID,
			"credential_id": a.CredentialID, "unit": "TWD", "remaining": amount, "clear": clear}), 200)
	}
	manualAmount := func() string {
		view := gatewayDecode[struct{ Providers []gatewayProvider }](t, req(t, s, "GET", admin+"/providers?provider_id="+a.ProviderID, nil), 200)
		for _, quota := range view.Providers[0].Quotas {
			if quota.CredentialID == a.CredentialID && quota.Source == "MANUAL" {
				return quota.Remaining
			}
		}
		return ""
	}
	setQuota("53.25", false)
	if manualAmount() != "53.25" {
		t.Fatal("missing manual key quota")
	}
	setQuota("0", false)
	if manualAmount() != "0" {
		t.Fatal("manual zero did not update the exact key")
	}
	setQuota("", true)
	if manualAmount() != "" {
		t.Fatal("clearing manual quota revived the previous value")
	}

	remove := func(in gatewayRemoval, want int) {
		requireCode(t, req(t, s, "POST", admin+"/remove", in), want)
	}
	remove(gatewayRemoval{Selection: a, Kind: "CONNECTION"}, 400)
	remove(gatewayRemoval{Selection: a, Kind: "CONNECTION", Confirm: true}, 200)
	aliases := gatewayDecode[[]gatewayModel](t, req(t, s, "GET", admin+"/models", nil), 200)
	for _, alias := range aliases {
		if alias.ID == "atlas-chat" && (!alias.Enabled || len(alias.Routes) != 1 || alias.Routes[0] != b) {
			t.Fatal("deleting one connection removed its sibling route")
		}
	}
	if !s.selectionUsable(context.Background(), b) || s.selectionUsable(context.Background(), a) {
		t.Fatal("removal changed the wrong model scope")
	}
	if synced := gatewayDecode[map[string]int](t, req(t, s, "POST", admin+"/sync", credential), 200); synced["added"] != 0 {
		t.Fatal("catalog sync reintroduced a removed connection")
	}
	if retained := local("24h"); retained.Local.Samples != lastDay.Local.Samples {
		t.Fatal("deleting a model connection removed telemetry")
	}
	requireCode(t, req(t, s, "POST", admin+"/upstream-model", struct {
		syncRequest
		ModelID string `json:"model_id"`
	}{credential, a.ModelID}), 200)
	if !s.selectionUsable(context.Background(), a) {
		t.Fatal("explicit re-add of a removed model failed")
	}
	remove(gatewayRemoval{Kind: "MODEL_GROUP", Canonical: "family-model", Confirm: true}, 200)
	if s.selectionUsable(context.Background(), a) || s.selectionUsable(context.Background(), b) {
		t.Fatal("deleting a group left an active model connection")
	}
	requireCode(t, gatewayClient(s, activeToken, "POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "removed", false)), 404)
	remove(gatewayRemoval{Kind: "PUBLIC_MODEL", ID: "atlas-chat", Confirm: true}, 200)
	requireCode(t, req(t, s, "POST", admin+"/models", gatewayModel{ID: "atlas-chat", Routes: []Selection{a}, Enabled: false}), 409)
	remove(gatewayRemoval{Kind: "PUBLIC_KEY", ID: activeID, Confirm: true}, 200)
	remove(gatewayRemoval{Kind: "PUBLIC_KEY", ID: revokedID, Confirm: true}, 200)
	requireCode(t, gatewayClient(s, activeToken, "GET", "/v1/models", nil), 401)
	requireCode(t, gatewayClient(s, revokedToken, "GET", "/v1/models", nil), 401)
	keys := gatewayDecode[[]gatewayKey](t, req(t, s, "GET", admin+"/keys", nil), 200)
	for _, key := range keys {
		if key.ID == activeID || key.ID == revokedID {
			t.Fatal("removed key still appears in management")
		}
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM gateway_requests WHERE key_id=?", revokedID)

	setQuota("10", false)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": a.ProviderID, "account_id": a.AccountID,
		"credential_id": a.CredentialID, "secret": "synthetic-rotated-key", "confirm": true}), 200)
	if manualAmount() != "" {
		t.Fatal("manual quota followed a rotated credential")
	}
	remove(gatewayRemoval{Selection: a, Kind: "CREDENTIAL", Confirm: true}, 200)
	requireCode(t, copyCall(origin, true, encoded), 404)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", a.ProviderID, a.AccountID, a.CredentialID)
	remove(gatewayRemoval{Selection: Selection{ProviderID: b.ProviderID, AccountID: b.AccountID}, Kind: "ACCOUNT", Confirm: true}, 200)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND account_id=?", b.ProviderID, b.AccountID)
	remove(gatewayRemoval{Selection: Selection{ProviderID: a.ProviderID}, Kind: "PROVIDER", Confirm: true}, 200)
	view := gatewayDecode[struct{ Providers []gatewayProvider }](t, req(t, s, "GET", admin+"/providers", nil), 200)
	if len(view.Providers) != 1 || view.Providers[0].ID != b.ProviderID || view.Providers[0].Accounts != 0 {
		t.Fatal("provider/account deletion failed to update management lists")
	}
}
