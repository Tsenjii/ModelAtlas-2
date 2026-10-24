package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestManualChatAndSpeechNeverSendAnotherStoredCredential(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); http.Error(w, "should not be called", 500) }))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	id := sessionForTest(t, s, v)
	secret := "abcdefghijklmnopqrstuvwx"
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "unused", ProviderID: "p", AccountID: "a", Label: "unused", Secret: secret, Role: "INFERENCE"}), 201)
	result, code := s.execute(context.Background(), inferRequest{SessionID: id, Prompt: "read " + secret}, "PLAYGROUND", func(string, any) error { return nil })
	if code != 422 || result.Outcome != "SECRET_IN_REQUEST_CONTENT" {
		t.Fatal("manual request leaked another Key")
	}
	speech := v
	speech.Endpoint = "AUDIO_SPEECH"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", speech), 201)
	confirmCaps(t, s, speech, []string{"AUDIO_OUTPUT"})
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", MediaRequest{Selection: speech, Prompt: "hello", Voice: secret, Format: "wav"}), 422)
	var messages int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM messages WHERE session_id=?", id).Scan(&messages)
	if calls.Load() != 0 || messages != 0 {
		t.Fatal("blocked secret crossed HTTP/history boundary")
	}
}
