package main

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"sort"
	"strings"
)

type ToolFunction struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	Parameters  map[string]any `json:"parameters,omitempty"`
	Arguments   string         `json:"arguments,omitempty"`
}
type ToolDefinition struct {
	Type     string       `json:"type"`
	Function ToolFunction `json:"function"`
}
type ToolCall struct {
	Index    int          `json:"index,omitempty"`
	ID       string       `json:"id"`
	Type     string       `json:"type"`
	Function ToolFunction `json:"function"`
}
type ToolResult struct {
	CallID  string `json:"call_id"`
	Content string `json:"content"`
}
type MessageState struct {
	ToolCalls     []ToolCall      `json:"tool_calls,omitempty"`
	ToolCallID    string          `json:"tool_call_id,omitempty"`
	ToolName      string          `json:"tool_name,omitempty"`
	ProtocolState json.RawMessage `json:"protocol_state,omitempty"`
}

var toolNamePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_-]{0,63}$`)

func validTools(tools []ToolDefinition) bool {
	if len(tools) > 32 {
		return false
	}
	seen := map[string]bool{}
	for _, t := range tools {
		raw, _ := json.Marshal(t)
		if t.Type != "function" || !toolNamePattern.MatchString(t.Function.Name) || seen[t.Function.Name] || len(t.Function.Description) > 2000 || len(raw) > 16000 || t.Function.Arguments != "" || t.Function.Parameters["type"] != "object" {
			return false
		}
		seen[t.Function.Name] = true
	}
	return true
}
func validCalls(calls []ToolCall, tools []ToolDefinition) bool {
	seen := map[string]bool{}
	names := map[string]bool{}
	for _, t := range tools {
		names[t.Function.Name] = true
	}
	for _, c := range calls {
		var args map[string]any
		if !validID(c.ID) || seen[c.ID] || c.Type != "function" || !names[c.Function.Name] || json.Unmarshal([]byte(c.Function.Arguments), &args) != nil || args == nil || len(c.Function.Arguments) > 32000 {
			return false
		}
		seen[c.ID] = true
	}
	return true
}
func orderedCalls(calls map[int]*ToolCall) []ToolCall {
	indices := []int{}
	for i := range calls {
		indices = append(indices, i)
	}
	sort.Ints(indices)
	out := []ToolCall{}
	for _, i := range indices {
		out = append(out, *calls[i])
	}
	return out
}
func (s *Server) sessionTools(ctx context.Context, id string, tools []ToolDefinition) ([]ToolDefinition, error) {
	if tools == nil {
		var raw string
		if s.Store.DB.QueryRowContext(ctx, "SELECT tools_json FROM session_options WHERE session_id=?", id).Scan(&raw) == nil {
			if json.Unmarshal([]byte(raw), &tools) != nil {
				return nil, errors.New("invalid stored tools")
			}
		}
	}
	if !validTools(tools) {
		return nil, errors.New("invalid tools")
	}
	return tools, nil
}
func (s *Server) saveSessionTools(ctx context.Context, id string, tools []ToolDefinition) error {
	raw, _ := json.Marshal(tools)
	_, err := s.Store.DB.ExecContext(ctx, "INSERT INTO session_options VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET tools_json=excluded.tools_json", id, string(raw))
	return err
}
func (s *Server) saveMessage(ctx context.Context, session string, m Message) error {
	if m.ID == "" {
		m.ID = newID()
	}
	media, _ := json.Marshal(metadataOnly(m.Attachments))
	state, _ := json.Marshal(m.MessageState)
	_, err := s.Store.DB.ExecContext(ctx, "INSERT INTO messages(id,session_id,role,content,media_metadata,structured_json,created_at) VALUES(?,?,?,?,?,?,?)", m.ID, session, m.Role, m.Content, string(media), string(state), now())
	return err
}
func toolContinuation(history []Message, v inferRequest) ([]Message, error) {
	pending := map[string]ToolCall{}
	for _, m := range history {
		if m.Role == "assistant" {
			for _, c := range m.ToolCalls {
				pending[c.ID] = c
			}
		}
		if m.Role == "tool" {
			delete(pending, m.ToolCallID)
		}
	}
	if len(v.ToolResults) > 16 || len(pending) != len(v.ToolResults) {
		return nil, errors.New("all pending tool results required")
	}
	turns := append([]Message(nil), history...)
	for _, r := range v.ToolResults {
		call, ok := pending[r.CallID]
		if !ok || len(r.Content) > 32000 || strings.TrimSpace(r.Content) == "" {
			return nil, errors.New("invalid tool result")
		}
		turns = append(turns, Message{Role: "tool", Content: r.Content, MessageState: MessageState{ToolCallID: r.CallID, ToolName: call.Function.Name}})
		delete(pending, r.CallID)
	}
	if v.Prompt != "" {
		turns = append(turns, Message{Role: "user", Content: v.Prompt, Attachments: v.Attachments})
	}
	return turns, nil
}

func jsonContainsSecret(value any, secret string) bool {
	if secret == "" {
		return false
	}
	raw, _ := json.Marshal(value)
	var v any
	if json.Unmarshal(raw, &v) != nil {
		return true
	}
	var scan func(any) bool
	scan = func(v any) bool {
		switch x := v.(type) {
		case string:
			return strings.Contains(x, secret)
		case []any:
			for _, n := range x {
				if scan(n) {
					return true
				}
			}
		case map[string]any:
			for key, n := range x {
				if strings.Contains(key, secret) || scan(n) {
					return true
				}
			}
		}
		return false
	}
	return scan(v)
}
