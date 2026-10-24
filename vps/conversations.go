package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"unicode"
)

// Conversation management never changes a connection or removes its retained history.
func (s *Server) safeSessionTitle(ctx context.Context, title string) bool {
	if len(title) == 0 || len(title) > 200 || strings.TrimSpace(title) != title || strings.IndexFunc(title, func(r rune) bool { return unicode.IsControl(r) || unicode.Is(unicode.Cf, r) }) >= 0 || rawSecretPattern.MatchString(title) {
		return false
	}
	secrets, e := s.knownSecrets(ctx)
	if e != nil {
		return false
	}
	for _, secret := range secrets {
		if secret != "" && strings.Contains(title, secret) {
			return false
		}
	}
	return true
}
func (s *Server) sessionArchived(ctx context.Context, id string) bool {
	var archived bool
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT archived FROM session_metadata WHERE session_id=?", id).Scan(&archived)
	return archived
}
func (s *Server) searchSessions(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	search := q.Get("q")
	state := q.Get("state")
	if len(search) > 256 || !oneOf(state, "", "ACTIVE", "ARCHIVED", "ALL") {
		http.Error(w, "invalid conversation search", 400)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	for _, secret := range secrets {
		if secret != "" && strings.Contains(search, secret) {
			http.Error(w, "invalid conversation search", 422)
			return
		}
	}
	where := "NOT EXISTS(SELECT 1 FROM assistant_threads t WHERE t.session_id=s.id)"
	args := []any{}
	if id := q.Get("session_id"); id != "" {
		if !validID(id) {
			http.Error(w, "invalid conversation id", 400)
			return
		}
		where += " AND s.id=?"
		args = append(args, id)
	}
	if state != "ALL" {
		where += " AND COALESCE(sm.archived,0)=?"
		args = append(args, state == "ARCHIVED")
	}
	if search != "" {
		where += " AND (instr(lower(s.title),lower(?))>0 OR instr(lower(s.model_id),lower(?))>0 OR EXISTS(SELECT 1 FROM messages m WHERE m.session_id=s.id AND instr(lower(m.content),lower(?))>0))"
		args = append(args, search, search, search)
	}
	if q.Has("provider_id") {
		scope := Selection{ProviderID: q.Get("provider_id"), AccountID: q.Get("account_id"), CredentialID: q.Get("credential_id"), ModelID: q.Get("model_id"), Group: q.Get("group"), Endpoint: q.Get("endpoint")}
		if !validID(scope.ProviderID) || !validID(scope.AccountID) || !validID(scope.CredentialID) || !validID(scope.ModelID) || !validID(scope.Endpoint) || len(scope.Group) > 256 {
			http.Error(w, "complete conversation scope required", 400)
			return
		}
		where += " AND s.provider_id=? AND s.account_id=? AND s.credential_id=? AND s.model_id=? AND s.group_id=? AND s.endpoint=?"
		args = append(args, scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint)
	}
	limit, offset := pageRange(r, 200)
	args = append(args, limit, offset)
	rows, e := s.Store.DB.QueryContext(r.Context(), `SELECT s.id,s.provider_id,s.account_id,s.credential_id,s.model_id,s.group_id,s.endpoint,s.title,s.created_at,MAX(s.created_at,COALESCE(sm.updated_at,0),COALESCE((SELECT MAX(created_at) FROM messages WHERE session_id=s.id),0)),COALESCE(sm.pinned,0),COALESCE(sm.archived,0),COALESCE(sm.revision,0),(SELECT COUNT(*) FROM messages WHERE session_id=s.id),COALESCE((SELECT substr(content,1,160) FROM messages WHERE session_id=s.id AND content<>'' ORDER BY rowid DESC LIMIT 1),'') FROM sessions s LEFT JOIN session_metadata sm ON sm.session_id=s.id WHERE `+where+` ORDER BY COALESCE(sm.pinned,0) DESC,10 DESC,s.rowid DESC LIMIT ? OFFSET ?`, args...)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []Session{}
	for rows.Next() {
		var x Session
		if e = rows.Scan(&x.ID, &x.ProviderID, &x.AccountID, &x.CredentialID, &x.ModelID, &x.Group, &x.Endpoint, &x.Title, &x.CreatedAt, &x.UpdatedAt, &x.Pinned, &x.Archived, &x.Revision, &x.MessageCount, &x.Preview); e != nil {
			http.Error(w, "database error", 500)
			return
		}
		x.Title = redactConversationText(x.Title, secrets)
		x.Preview = redactConversationText(x.Preview, secrets)
		out = append(out, x)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) updateSession(w http.ResponseWriter, r *http.Request) {
	var v struct {
		SessionID string  `json:"session_id"`
		Revision  int     `json:"revision"`
		Title     *string `json:"title,omitempty"`
		Pinned    *bool   `json:"pinned,omitempty"`
		Archived  *bool   `json:"archived,omitempty"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.manualSession(r.Context(), v.SessionID) {
		http.Error(w, "manual conversation missing", 404)
		return
	}
	if v.Revision < 0 || v.Title == nil && v.Pinned == nil && v.Archived == nil || v.Title != nil && !s.safeSessionTitle(r.Context(), *v.Title) {
		http.Error(w, "invalid conversation change", 422)
		return
	}
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	s.flightMu.Lock()
	defer s.flightMu.Unlock()
	if s.flights[v.SessionID] {
		http.Error(w, "conversation is generating", 409)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var before Session
	e = tx.QueryRowContext(r.Context(), `SELECT s.id,s.title,COALESCE(sm.pinned,0),COALESCE(sm.archived,0),COALESCE(sm.revision,0) FROM sessions s LEFT JOIN session_metadata sm ON sm.session_id=s.id WHERE s.id=?`, v.SessionID).Scan(&before.ID, &before.Title, &before.Pinned, &before.Archived, &before.Revision)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if before.Revision != v.Revision {
		http.Error(w, "conversation changed; reload before editing", 409)
		return
	}
	after := before
	if v.Title != nil {
		after.Title = *v.Title
	}
	if v.Pinned != nil {
		after.Pinned = *v.Pinned
	}
	if v.Archived != nil {
		after.Archived = *v.Archived
	}
	after.Revision++
	after.UpdatedAt = now()
	if _, e = tx.ExecContext(r.Context(), "UPDATE sessions SET title=? WHERE id=?", after.Title, v.SessionID); e == nil {
		_, e = tx.ExecContext(r.Context(), "INSERT INTO session_metadata VALUES(?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET pinned=excluded.pinned,archived=excluded.archived,revision=excluded.revision,updated_at=excluded.updated_at", v.SessionID, after.Pinned, after.Archived, after.Revision, after.UpdatedAt)
	}
	b, _ := json.Marshal(cleanExportValue(before, secrets))
	a, _ := json.Marshal(cleanExportValue(after, secrets))
	if e == nil {
		_, e = tx.ExecContext(r.Context(), "INSERT INTO session_changes VALUES(?,?,?,?,?)", newID(), v.SessionID, string(b), string(a), after.UpdatedAt)
	}
	if e != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": after.ID, "title": redactConversationText(after.Title, secrets), "pinned": after.Pinned, "archived": after.Archived, "revision": after.Revision, "updated_at": after.UpdatedAt})
}

func redactConversationText(text string, secrets []string) string {
	for _, secret := range secrets {
		if secret != "" {
			text = strings.ReplaceAll(text, secret, "[REDACTED]")
		}
	}
	return rawSecretPattern.ReplaceAllString(text, "[REDACTED]")
}
func redactConversationValue(v any, secrets []string) any {
	switch x := v.(type) {
	case string:
		return redactConversationText(x, secrets)
	case map[string]any:
		for k, v := range x {
			x[k] = redactConversationValue(v, secrets)
		}
	case []any:
		for i, v := range x {
			x[i] = redactConversationValue(v, secrets)
		}
	}
	return v
}
func cleanExportValue(v any, secrets []string) any {
	raw, _ := json.Marshal(v)
	var decoded any
	_ = json.Unmarshal(raw, &decoded)
	return redactConversationValue(decoded, secrets)
}

// Stream all messages, including conversations beyond the inference context limit.
// Raw attachment bytes remain transient; exports explicitly contain metadata only.
func (s *Server) exportSession(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("session_id")
	format := r.URL.Query().Get("format")
	if !s.manualSession(r.Context(), id) {
		http.Error(w, "manual conversation missing", 404)
		return
	}
	if !oneOf(format, "", "json", "markdown") {
		http.Error(w, "invalid export format", 400)
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	var session Session
	e = s.Store.DB.QueryRowContext(r.Context(), "SELECT s.id,s.provider_id,s.account_id,s.credential_id,s.model_id,s.group_id,s.endpoint,s.title,s.created_at,COALESCE(sm.pinned,0),COALESCE(sm.archived,0),COALESCE(sm.revision,0) FROM sessions s LEFT JOIN session_metadata sm ON sm.session_id=s.id WHERE s.id=?", id).Scan(&session.ID, &session.ProviderID, &session.AccountID, &session.CredentialID, &session.ModelID, &session.Group, &session.Endpoint, &session.Title, &session.CreatedAt, &session.Pinned, &session.Archived, &session.Revision)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	options, e := s.sessionSettings(r.Context(), id)
	if e != nil {
		http.Error(w, "settings unavailable", 503)
		return
	}
	tools, e := s.sessionTools(r.Context(), id, nil)
	if e != nil {
		http.Error(w, "tools unavailable", 503)
		return
	}
	flight := "export:" + newID()
	if !s.beginFlight(flight) {
		http.Error(w, "export flight limit reached", 409)
		return
	}
	defer s.endFlight(flight)
	var messageCount int
	var watermark int64
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*),COALESCE(MAX(rowid),0) FROM messages WHERE session_id=?", id).Scan(&messageCount, &watermark) != nil {
		http.Error(w, "database error", 500)
		return
	}

	var writeError error
	write := func(text string) {
		if writeError == nil {
			_, writeError = io.WriteString(w, text)
		}
	}
	extension := "json"
	if format == "markdown" {
		extension = "md"
		w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	} else {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
	}
	filenameID := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' {
			return r
		}
		return '-'
	}, id)
	w.Header().Set("Content-Disposition", `attachment; filename="modelatlas-conversation-`+filenameID+`.`+extension+`"`)
	if format == "markdown" {
		write("# " + redactConversationText(session.Title, secrets) + "\n\n" + redactConversationText(session.ModelID, secrets) + " · " + redactConversationText(session.ProviderID, secrets) + "\n\n")
	} else {
		header, _ := json.Marshal(map[string]any{"version": 1, "session": session, "settings": options, "tools": tools, "attachment_storage": "METADATA_ONLY", "exported_at": now()})
		var h map[string]any
		_ = json.Unmarshal(header, &h)
		clean, _ := json.Marshal(redactConversationValue(h, secrets))
		write(strings.TrimSuffix(string(clean), "}") + `,"messages":[`)
	}
	first := true
	var cursor *int64
	for offset := 0; offset < messageCount; offset += 4 {
		pageSize := min(4, messageCount-offset)
		page, next, e := s.historyExportPage(r.Context(), id, cursor, watermark)
		cursor = &next
		if e != nil || len(page) != pageSize {
			panic(http.ErrAbortHandler)
		}
		for _, m := range page {
			m.Attachments = metadataOnly(m.Attachments)
			if format == "markdown" {
				write("## " + map[string]string{"user": "使用者", "assistant": "模型", "tool": "工具結果"}[m.Role] + "\n\n" + redactConversationText(m.Content, secrets) + "\n\n")
				if len(m.Attachments) > 0 {
					for _, a := range m.Attachments {
						write("附件（僅中繼資料）：" + redactConversationText(a.Filename, secrets) + "\n\n")
					}
				}
				if len(m.ToolCalls) > 0 {
					raw, _ := json.MarshalIndent(cleanExportValue(m.ToolCalls, secrets), "", "  ")
					write("工具呼叫：\n\n" + string(raw) + "\n\n")
				}
			} else {
				if !first {
					write(",")
				}
				raw, _ := json.Marshal(cleanExportValue(m, secrets))
				write(string(raw))
				first = false
			}
			if writeError != nil {
				panic(http.ErrAbortHandler)
			}
		}
	}
	if format != "markdown" {
		write("]}\n")
	}
	if writeError != nil {
		panic(http.ErrAbortHandler)
	}
}
