package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func batchTestSelections(t *testing.T, s *Server, base string) []Selection {
	t.Helper()
	v := seed(t, s, base)
	rows := []Selection{}
	for _, model := range []string{"m", "second", "third"} {
		m := v
		m.ModelID = model
		if model != "m" {
			requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: "p", ID: model, DisplayName: model}), 201)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", m), 201)
		rows = append(rows, m)
	}
	return rows
}

func readBatchEvents(t *testing.T, r io.Reader) []modelTestBatchEvent {
	t.Helper()
	decoder, events := json.NewDecoder(r), []modelTestBatchEvent{}
	for {
		var event modelTestBatchEvent
		if err := decoder.Decode(&event); err == io.EOF {
			return events
		} else if err != nil {
			t.Fatal(err)
		}
		events = append(events, event)
	}
}

func batchTestReply(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/event-stream")
	io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n")
}

func TestModelBatchExactScopesReceiptsAndPreflight(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil || r.Header.Get("Authorization") != "Bearer top-secret-example-key" || r.URL.Path != "/v1/chat/completions" || body["max_tokens"] != float64(16) {
			t.Error("batch changed selected authentication, endpoint or test budget")
		}
		batchTestReply(w)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	selections := batchTestSelections(t, s, up.URL+"/v1")
	for _, input := range []struct {
		request modelTestBatchRequest
		code    int
	}{
		{modelTestBatchRequest{ID: "empty"}, 400},
		{modelTestBatchRequest{ID: "duplicate", Selections: []Selection{selections[0], selections[0]}}, 400},
		{modelTestBatchRequest{ID: "bad-scope", Selections: []Selection{selections[0], {ProviderID: "p", AccountID: "different", CredentialID: "c", ModelID: "m", Endpoint: "CHAT_COMPLETIONS"}}}, 422},
		{modelTestBatchRequest{ID: "too-many", Selections: make([]Selection, 101)}, 400},
	} {
		requireCode(t, req(t, s, "POST", "/api/v1/models/test-batch", input.request), input.code)
	}
	if calls.Load() != 0 {
		t.Fatal("invalid batch sent an inference")
	}
	response := req(t, s, "POST", "/api/v1/models/test-batch", modelTestBatchRequest{ID: "exact-batch", Selections: selections})
	requireCode(t, response, 200)
	if !strings.HasPrefix(response.Header().Get("Content-Type"), "application/x-ndjson") || strings.Contains(response.Body.String(), "top-secret") {
		t.Fatal("invalid stream type or plaintext secret")
	}
	events := readBatchEvents(t, response.Body)
	if len(events) != 7 || calls.Load() != 3 || events[6].Status != "COMPLETED" || events[6].Completed != 3 {
		t.Fatalf("incomplete batch: %+v, calls=%d", events, calls.Load())
	}
	for i, selection := range selections {
		v := events[2*i+1]
		if v.Kind != "result" || v.Index != i || v.Result == nil || v.Result.Scope != selection || v.Result.Outcome != "SUCCESS" || v.Result.Details["sample_id"] == nil || v.Result.Text != "" {
			t.Fatalf("wrong scope, receipt or result: %+v", v)
		}
	}
	var count int
	if err := s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE source='MANUAL_TEST' AND outcome='SUCCESS'").Scan(&count); err != nil || count != 3 {
		t.Fatalf("tests bypassed persistent telemetry: %d %v", count, err)
	}
}

func TestModelBatchStopsAtRateLimitWithoutRetry(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Error(w, `{"error":{"message":"daily quota"}}`, 429)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	selections := batchTestSelections(t, s, up.URL+"/v1")
	response := req(t, s, "POST", "/api/v1/models/test-batch", modelTestBatchRequest{ID: "limited", Selections: selections})
	requireCode(t, response, 200)
	events := readBatchEvents(t, response.Body)
	if len(events) != 3 || events[1].Result.Status != 429 || events[2].Status != "LIMIT_REACHED" || events[2].Completed != 1 || calls.Load() != 1 {
		t.Fatalf("rate limit retried or consumed other selections: %+v / %d", events, calls.Load())
	}
}

func TestModelBatchCancellationStopsActiveSocketAndPendingModels(t *testing.T) {
	entered := make(chan struct{}, 1)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		io.Copy(io.Discard, r.Body)
		entered <- struct{}{}
		<-r.Context().Done()
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	selections := batchTestSelections(t, s, up.URL+"/v1")
	app := httptest.NewServer(s.Handler())
	defer app.Close()
	input := modelTestBatchRequest{ID: "cancel-this-batch", Selections: selections}
	raw, _ := json.Marshal(input)
	r, _ := http.NewRequest("POST", app.URL+"/api/v1/models/test-batch", bytes.NewReader(raw))
	r.Header.Set("Authorization", "Bearer "+s.Token)
	r.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 10 * time.Second}
	response, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("batch never reached the real upstream socket")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models/test-batch", input), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/models/test-batch/cancel", map[string]string{"id": input.ID}), 200)
	events := readBatchEvents(t, response.Body)
	if len(events) != 3 || events[1].Result.Outcome != "CANCELLED" || events[2].Status != "CANCELLED" || calls.Load() != 1 {
		t.Fatalf("cancellation replayed pending models: %+v / %d", events, calls.Load())
	}
	var count int
	if err = s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry WHERE outcome='CANCELLED'").Scan(&count); err != nil || count != 1 {
		t.Fatalf("active cancellation not persisted: %d %v", count, err)
	}
}
