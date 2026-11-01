package main

import (
	"context"
	"encoding/json"
	"net/http"
)

// A batch is an explicit, bounded series of ordinary manual tests. Disconnect
// and cancellation stop the series; an ambiguous write is never replayed.
type modelTestBatchRequest struct {
	ID         string      `json:"id"`
	Selections []Selection `json:"selections"`
}

type modelTestBatchEvent struct {
	Kind      string           `json:"kind"`
	ID        string           `json:"id"`
	Index     int              `json:"index"`
	Total     int              `json:"total"`
	Completed int              `json:"completed"`
	Selection *Selection       `json:"selection,omitempty"`
	Result    *InferenceResult `json:"result,omitempty"`
	Status    string           `json:"status,omitempty"`
}

func (s *Server) testModelBatchHTTP(w http.ResponseWriter, r *http.Request) {
	var v modelTestBatchRequest
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) || len(v.Selections) < 1 || len(v.Selections) > 100 {
		http.Error(w, "batch requires an id and 1..100 explicit selections", 400)
		return
	}
	seen := make(map[string]bool, len(v.Selections))
	for _, selection := range v.Selections {
		key := scopeKey(selection)
		if seen[key] {
			http.Error(w, "duplicate batch selection", 400)
			return
		}
		seen[key] = true
		if !s.validateSelection(r.Context(), selection) || !conversationOperation(s.operation(r.Context(), selection)) {
			http.Error(w, "batch selection unavailable or not a conversation", 422)
			return
		}
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	s.flightMu.Lock()
	if s.batchTests == nil {
		s.batchTests = map[string]context.CancelFunc{}
	}
	if s.batchTests[v.ID] != nil || len(s.batchTests) >= 2 {
		s.flightMu.Unlock()
		http.Error(w, "batch test already running", 409)
		return
	}
	s.batchTests[v.ID] = cancel
	s.flightMu.Unlock()
	defer func() {
		s.flightMu.Lock()
		delete(s.batchTests, v.ID)
		s.flightMu.Unlock()
	}()

	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("X-Accel-Buffering", "no")
	encoder := json.NewEncoder(w)
	emit := func(event modelTestBatchEvent) bool {
		event.ID, event.Total = v.ID, len(v.Selections)
		if encoder.Encode(event) != nil {
			cancel()
			return false
		}
		if flush, ok := w.(http.Flusher); ok {
			flush.Flush()
		}
		return true
	}
	completed, status := 0, "COMPLETED"
	for index, selection := range v.Selections {
		if ctx.Err() != nil {
			status = "CANCELLED"
			break
		}
		if !emit(modelTestBatchEvent{Kind: "start", Index: index, Completed: completed, Selection: &selection}) {
			return
		}
		// Recheck before every request: another tab may disable a credential or
		// archive a model while this explicitly selected batch is running.
		result := InferenceResult{Scope: selection, Outcome: "SELECTION_UNAVAILABLE", Details: map[string]any{}}
		if s.validateSelection(ctx, selection) {
			id, err := s.createTestSession(ctx, selection)
			if err != nil {
				status = "DATABASE_ERROR"
				break
			}
			result, _ = s.execute(ctx, inferRequest{SessionID: id, Prompt: "Reply OK.", MaxTokens: 16}, "MANUAL_TEST", func(string, any) error { return nil })
		}
		completed++
		// Batch results expose the receipt and metrics, not generated content.
		result.Text, result.Reasoning, result.ToolCalls = "", "", nil
		if !emit(modelTestBatchEvent{Kind: "result", Index: index, Completed: completed, Selection: &selection, Result: &result}) {
			return
		}
		if ctx.Err() != nil || result.Outcome == "CANCELLED" {
			status = "CANCELLED"
			break
		}
		// Do not consume the rest of a limited key after an explicit quota or
		// admission refusal. Resuming is a new user action, never an auto retry.
		if result.Status == 429 || result.Status == 402 || result.Outcome == "REQUEST_IN_PROGRESS" {
			status = "LIMIT_REACHED"
			break
		}
	}
	if ctx.Err() != nil {
		status = "CANCELLED"
	}
	emit(modelTestBatchEvent{Kind: "done", Completed: completed, Status: status})
}

func (s *Server) cancelModelTestBatchHTTP(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !validID(v.ID) {
		http.Error(w, "batch id required", 400)
		return
	}
	s.flightMu.Lock()
	cancel := s.batchTests[v.ID]
	s.flightMu.Unlock()
	if cancel == nil {
		http.Error(w, "no active batch test", 404)
		return
	}
	cancel()
	jsonOK(w, 200, map[string]bool{"cancelled": true})
}
