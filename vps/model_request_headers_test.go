package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestGatewayModelRequestHeadersStayWithConnection(t *testing.T) {
	const agent = "Atlas-Fixture-Client/1.2"
	const private = "synthetic-private-client-proof"
	const mediaAgent = "Atlas-Fixture-Media/1.2"
	const secretName = "X-Client-Proof"
	image := testPNG(t)
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+gatewayTestKeyA {
			t.Error("selected credential was overridden")
			http.Error(w, "invalid credential", 401)
			return
		}
		if r.URL.Path == "/a/v1/models" {
			if r.UserAgent() == agent || r.Header.Get(secretName) != "" {
				t.Error("model settings leaked to discovery")
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"data":[{"id":"shared-model"}]}`)
			return
		}
		if r.URL.Path == "/a/v1/images/generations" {
			if r.UserAgent() != mediaAgent || r.Header.Get(secretName) != "" {
				t.Error("image request received a different endpoint's headers")
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"data":[{"b64_json":%q}]}`, image)
			return
		}
		switch r.URL.Path {
		case "/a/v1/chat/completions":
			if r.UserAgent() != agent || r.Header.Get(secretName) != private {
				http.Error(w, "client headers required", 403)
				return
			}
		case "/b/v1/chat/completions":
			if r.UserAgent() == agent || r.Header.Get(secretName) != "" {
				t.Error("headers leaked to another provider offering the same model")
			}
		default:
			http.NotFound(w, r)
			return
		}
		var chat struct {
			Stream   bool                `json:"stream"`
			Messages []map[string]string `json:"messages"`
		}
		if json.NewDecoder(r.Body).Decode(&chat) != nil || len(chat.Messages) == 0 {
			http.Error(w, "invalid request", 400)
			return
		}
		reply := "OK"
		if chat.Messages[0]["content"] == "reflect" {
			reply = r.Header.Get(secretName)
		}
		if chat.Stream {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":%q}}]}\n\ndata: [DONE]\n\n", reply)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"choices":[{"message":{"role":"assistant","content":%q}}]}`, reply)
	}))
	defer upstream.Close()
	s := testServer(t)
	s.Client, s.distribution = upstream.Client(), true
	const admin = "/api/v1/gateway"
	addUpstream := func(side string) gatewayUpstream {
		t.Helper()
		return gatewayDecode[gatewayUpstream](t, req(t, s, "POST", admin+"/upstreams", map[string]string{
			"name": "Provider " + side, "base_url": upstream.URL + "/" + side + "/v1", "api_key": gatewayTestKeyA,
		}), 201)
	}
	addModel := func(up gatewayUpstream, endpoint string) Selection {
		t.Helper()
		v := Selection{ProviderID: up.ProviderID, AccountID: up.AccountID, CredentialID: up.CredentialID,
			ModelID: "shared-model", Endpoint: endpoint}
		requireCode(t, req(t, s, "POST", admin+"/upstream-model", struct {
			syncRequest
			Model    string `json:"model_id"`
			Endpoint string `json:"endpoint"`
		}{up.syncRequest, v.ModelID, endpoint}), 200)
		return v
	}
	upA, upB := addUpstream("a"), addUpstream("b")
	a, b := addModel(upA, "CHAT_COMPLETIONS"), addModel(upB, "CHAT_COMPLETIONS")
	media := addModel(upA, "IMAGES_GENERATION")
	read := func(v Selection) modelHeaderEditor {
		t.Helper()
		query := url.Values{"provider_id": {v.ProviderID}, "account_id": {v.AccountID}, "credential_id": {v.CredentialID},
			"model_id": {v.ModelID}, "group": {v.Group}, "endpoint": {v.Endpoint}}
		return gatewayDecode[modelHeaderEditor](t, req(t, s, "GET", admin+"/offering-headers?"+query.Encode(), nil), 200)
	}
	write := func(v Selection, revision string, edits []modelHeaderEdit, status int) modelHeaderEditor {
		t.Helper()
		resp := req(t, s, "POST", admin+"/offering-headers", modelHeaderEditor{v, revision, edits})
		if strings.Contains(resp.Body.String(), private) {
			t.Fatal("private header value appeared in a management response")
		}
		if status != 200 {
			requireCode(t, resp, status)
			return modelHeaderEditor{}
		}
		return gatewayDecode[modelHeaderEditor](t, resp, 200)
	}
	probe := func(v Selection, expected string) {
		t.Helper()
		result := gatewayDecode[struct{ Outcome string }](t, req(t, s, "POST", admin+"/probe", v), 200)
		if result.Outcome != expected {
			t.Fatalf("unexpected probe outcome %s", result.Outcome)
		}
	}
	probe(a, "INFERENCE_DENIED")
	initial := read(a)
	if initial.Selection != a || len(initial.Headers) != 0 {
		t.Fatal("wrong initial header scope")
	}
	ua, secret, mediaUA := agent, private, mediaAgent
	edits := []modelHeaderEdit{{Name: "User-Agent", Value: &ua}, {Name: secretName, Value: &secret, Private: true}}
	saved := write(a, initial.Revision, edits, 200)
	if len(saved.Headers) != 2 || saved.Headers[0].Value == nil || *saved.Headers[0].Value != agent ||
		!saved.Headers[1].Private || saved.Headers[1].Value != nil || read(a).Headers[1].Value != nil {
		t.Fatal("header editor did not mask the private value")
	}
	for _, name := range []string{"Authorization", "X_Api_Key", "Content-Length"} {
		write(a, saved.Revision, []modelHeaderEdit{{Name: name, Value: &ua}}, 400)
	}
	invalid := "ok\r\nInjected: header"
	write(a, saved.Revision, []modelHeaderEdit{{Name: "X-Client", Value: &invalid}}, 400)
	write(a, saved.Revision, []modelHeaderEdit{{Name: "User-Agent", Value: &ua}, {Name: "user-agent", Value: &ua}}, 400)
	write(a, initial.Revision, edits, 409)
	write(a, saved.Revision, []modelHeaderEdit{{Name: secretName}}, 400)
	current := write(a, saved.Revision, []modelHeaderEdit{{Name: "User-Agent", Value: &ua}, {Name: secretName, Private: true}}, 200)
	if current.Revision != saved.Revision || len(read(b).Headers) != 0 || len(read(media).Headers) != 0 {
		t.Fatal("header updates did not preserve exact connection scope")
	}
	var encrypted string
	if err := s.Store.DB.QueryRow("SELECT encrypted_headers FROM model_request_headers WHERE "+modelHeaderScope, modelHeaderArgs(a)...).Scan(&encrypted); err != nil ||
		encrypted == "" || strings.Contains(encrypted, private) || strings.Contains(encrypted, agent) ||
		verifyVault(context.Background(), s.Store.DB, s.Key) != nil {
		t.Fatal("model headers were not saved as a verifiable ciphertext")
	}
	probe(a, "SUCCESS")
	probe(b, "SUCCESS")
	if result := gatewayDecode[InferenceResult](t, req(t, s, "POST", "/api/v1/models/test", a), 200); result.Outcome != "SUCCESS" {
		t.Fatalf("standard model test did not use saved headers: %s", result.Outcome)
	}
	write(media, read(media).Revision, []modelHeaderEdit{{Name: "User-Agent", Value: &mediaUA}}, 200)
	if result := gatewayDecode[MediaResponse](t, req(t, s, "POST", admin+"/test-image", struct {
		Selection
		Prompt string `json:"prompt"`
	}{media, "fixture image preview"}), 200); result.Outcome != "SUCCESS" {
		t.Fatalf("image test did not use the selected headers: %s", result.Outcome)
	}
	requireCode(t, req(t, s, "POST", admin+"/sync", upA.syncRequest), 200)
	requireCode(t, req(t, s, "POST", admin+"/models", gatewayModel{ID: "atlas-chat", Routes: []Selection{a}, Enabled: true}), 200)
	issued := gatewayDecode[struct{ Token string }](t, req(t, s, "POST", admin+"/keys",
		map[string]any{"label": "fixture", "models": []string{"atlas-chat"}, "daily_limit": 4}), 201)
	requireCode(t, gatewayClient(s, issued.Token, "POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "normal", false)), 200)
	echo := gatewayClient(s, issued.Token, "POST", "/v1/chat/completions", gatewayTestChat("atlas-chat", "reflect", false))
	requireCode(t, echo, 502)
	if strings.Contains(echo.Body.String(), private) || !strings.Contains(echo.Body.String(), "secret_in_response") {
		t.Fatal("relay returned a private header reflected in the upstream answer")
	}
	requireCode(t, req(t, s, "POST", admin+"/remove", gatewayRemoval{Selection: a, Kind: "CONNECTION", Confirm: true}), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM model_request_headers")
}
