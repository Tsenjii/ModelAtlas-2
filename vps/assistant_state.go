package main

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
)

// Small identity projections leave room for the user's task and tool results.
// Details are fetched on demand, not copied wholesale into every model request.
func (s *Server) assistantSnapshot(ctx context.Context) (map[string]any, error) {
	out := map[string]any{}
	fields := map[string][]string{
		"providers":          {"id", "name", "base_url"},
		"accounts":           {"id", "provider_id", "label"},
		"credentials":        {"id", "provider_id", "account_id", "label", "role", "enabled", "group"},
		"my-models?all=true": {"provider_id", "account_id", "credential_id", "model_id", "group", "endpoint", "state", "enabled"},
		"assistant/secrets":  {"id", "base_url", "label", "role"},
	}
	for path, names := range fields {
		value, e := s.localAPI(ctx, "GET", "/"+path, nil)
		if e != nil {
			return nil, e
		}
		rows, _ := value.([]any)
		projected := []map[string]any{}
		for _, v := range rows[:min(len(rows), 24)] {
			row, _ := v.(map[string]any)
			item := map[string]any{}
			for _, name := range names {
				if x, ok := row[name]; ok {
					item[name] = x
				}
			}
			projected = append(projected, item)
		}
		out[path] = map[string]any{"items": projected, "returned": len(projected), "available": len(rows), "more": len(rows) > len(projected)}
	}
	return out, nil
}

func (s *Server) assistantResume(ctx context.Context, thread AssistantThread) ([]ToolResult, error) {
	history, e := s.history(ctx, thread.SessionID)
	if e != nil {
		return nil, e
	}
	type pendingCall struct {
		call ToolCall
		at   int64
	}
	pending := map[string]pendingCall{}
	for _, m := range history {
		for _, call := range m.ToolCalls {
			pending[call.ID] = pendingCall{call, m.CreatedAt}
		}
		if m.ToolCallID != "" {
			delete(pending, m.ToolCallID)
		}
	}
	ids := []string{}
	for id := range pending {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	out := []ToolResult{}
	for _, id := range ids {
		p := pending[id]
		var status, arguments, result string
		e := s.Store.DB.QueryRowContext(ctx, `SELECT e.status,e.args_json,e.result_json FROM assistant_tool_events e JOIN assistant_runs r ON r.id=e.run_id WHERE r.thread_id=? AND e.call_id=? AND e.name=? AND e.created_at>=? ORDER BY e.rowid DESC LIMIT 1`, thread.ID, id, p.call.Function.Name, p.at).Scan(&status, &arguments, &result)
		var expected, actual any
		json.Unmarshal([]byte(p.call.Function.Arguments), &expected)
		json.Unmarshal([]byte(arguments), &actual)
		a, _ := json.Marshal(expected)
		b, _ := json.Marshal(actual)
		if e == nil && string(a) == string(b) && oneOf(status, "SUCCESS", "FAILED") && json.Valid([]byte(result)) {
			out = append(out, ToolResult{CallID: id, Content: result})
			continue
		}
		// Never replay an interrupted side effect. Its durable result, if any,
		// remains inspectable through the action ledger / shared Review.
		message := "INTERRUPTED_NOT_EXECUTED"
		if e == nil {
			message = "INTERRUPTED_OUTCOME_UNCERTAIN_CHECK_PERSISTED_STATE"
		}
		out = append(out, ToolResult{CallID: id, Content: `{"status":"` + message + `","automatic_replay":false}`})
	}
	if len(out) > 16 {
		return nil, errors.New("pending tool limit")
	}
	return out, nil
}
