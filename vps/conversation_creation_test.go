package main

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"sync"
	"testing"
)

const creationTestRequestID = "d95c359a-0926-4fac-9eca-4d3f5abf0321"

func creationTestPayload(v Selection, requestID string) map[string]any {
	return map[string]any{"provider_id": v.ProviderID, "account_id": v.AccountID, "credential_id": v.CredentialID, "model_id": v.ModelID, "group": v.Group, "endpoint": v.Endpoint, "title": "新對話", "client_request_id": requestID}
}

func TestConversationCreationSameRequestKeepsOneCommittedIdentity(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	body := creationTestPayload(v, creationTestRequestID)
	first := req(t, s, "POST", "/api/v1/sessions", body)
	requireCode(t, first, 201)
	var created Session
	if json.Unmarshal(first.Body.Bytes(), &created) != nil || created.ID != "r-d95c359a09264fac9eca4d3f5abf0321" || created.Selection != v {
		t.Fatal("wrong committed conversation identity")
	}
	again := req(t, s, "POST", "/api/v1/sessions", body)
	requireCode(t, again, 201)
	if again.Header().Get("Idempotent-Replayed") != "true" || again.Body.String() != first.Body.String() {
		t.Fatal("same request did not return its original committed identity")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM sessions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
}

func TestConversationCreationConcurrentRetriesAreAtomic(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	body, err := json.Marshal(creationTestPayload(v, creationTestRequestID))
	if err != nil {
		t.Fatal(err)
	}
	const callers = 8
	results := make(chan *httptest.ResponseRecorder, callers)
	var group sync.WaitGroup
	for range callers {
		group.Go(func() {
			r := httptest.NewRequest("POST", "/api/v1/sessions", bytes.NewReader(body))
			r.Header.Set("Authorization", "Bearer "+s.Token)
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			results <- w
		})
	}
	group.Wait()
	close(results)
	for response := range results {
		requireCode(t, response, 201)
		var created Session
		if json.Unmarshal(response.Body.Bytes(), &created) != nil || created.ID != "r-d95c359a09264fac9eca4d3f5abf0321" || created.Selection != v {
			t.Fatal("concurrent retry changed identity or scope")
		}
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM sessions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestConversationCreationRequestCannotBeReusedForAnotherScopeOrTitle(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	body := creationTestPayload(v, creationTestRequestID)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions", body), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "B"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Secret: "synthetic-other-creation-key", Group: "other"}), 201)
	other := v
	other.AccountID, other.Group = "b", "other"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", other), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/sessions", creationTestPayload(other, creationTestRequestID)), 409)
	body["title"] = "不同的新對話"
	requireCode(t, req(t, s, "POST", "/api/v1/sessions", body), 409)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM sessions WHERE account_id='a' AND title='新對話'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM sessions")
}

func TestConversationCreationRequestSurvivesReopenAndPreservesHistory(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	body := creationTestPayload(v, creationTestRequestID)
	response := req(t, s, "POST", "/api/v1/sessions", body)
	requireCode(t, response, 201)
	var first Session
	if json.Unmarshal(response.Body.Bytes(), &first) != nil {
		t.Fatal("invalid creation response")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/sessions/update", map[string]any{"session_id": first.ID, "revision": 0, "pinned": true}), 200)
	if _, err := s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('retained-creation-message',?,'user','永久保留的對話歷史',1)", first.ID); err != nil {
		t.Fatal(err)
	}
	path := s.Store.path
	if err := s.Store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	reopened := &Server{Store: store, Token: s.Token, Key: s.Key, Client: s.Client}
	replay := req(t, reopened, "POST", "/api/v1/sessions", body)
	requireCode(t, replay, 201)
	var after Session
	if json.Unmarshal(replay.Body.Bytes(), &after) != nil || after.ID != first.ID || after.CreatedAt != first.CreatedAt || !after.Pinned || after.Revision != 1 || after.MessageCount != 1 {
		t.Fatal("restart replay changed persisted conversation or metadata")
	}
	assertSQLInt(t, reopened, 1, "SELECT COUNT(*) FROM sessions")
	assertSQLInt(t, reopened, 1, "SELECT COUNT(*) FROM messages WHERE content='永久保留的對話歷史'")
	requireCode(t, req(t, reopened, "POST", "/api/v1/sessions/update", map[string]any{"session_id": first.ID, "revision": 1, "archived": true}), 200)
	requireCode(t, req(t, reopened, "POST", "/api/v1/sessions", body), 409)
	assertSQLInt(t, reopened, 1, "SELECT COUNT(*) FROM session_metadata WHERE archived=1")
}

func TestConversationCreationRejectsMalformedRequestIdentity(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	for _, value := range []string{"bad", "d95c359a09264fac9eca4d3f5abf0321", "d95c359a-0926-1fac-9eca-4d3f5abf0321", "d95c359a-0926-4fac-feca-4d3f5abf0321", "D95C359A-0926-4FAC-9ECA-4D3F5ABF0321", "d95c359a-0926-4fac-9eca-4d3f5abf032g"} {
		requireCode(t, req(t, s, "POST", "/api/v1/sessions", creationTestPayload(v, value)), 400)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM sessions")
}

func TestConversationCreationLegacyClientsStillCreateDistinctSessions(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	for range 2 {
		response := req(t, s, "POST", "/api/v1/sessions", Session{ID: "caller-id-remains-ignored", Selection: v})
		requireCode(t, response, 201)
		var created Session
		if json.Unmarshal(response.Body.Bytes(), &created) != nil || len(created.ID) != 32 || created.ID == "caller-id-remains-ignored" {
			t.Fatal("legacy conversation creation changed")
		}
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM sessions")
}
