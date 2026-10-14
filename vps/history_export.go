package main

import (
	"context"
	"encoding/json"
	"errors"
)

// The (session_id, implicit rowid) index advances without rescanning old pages.
// Each query releases SQLite before the browser receives its bounded batch.
func (s *Server) historyExportPage(ctx context.Context, id string, cursor *int64, watermark int64) ([]Message, int64, error) {
	where := "m.session_id=? AND m.rowid<=?"
	args := []any{id, watermark}
	if cursor != nil {
		where += " AND m.rowid>?"
		args = append(args, *cursor)
	}
	rows, e := s.Store.DB.QueryContext(ctx, `SELECT m.id,m.session_id,m.role,m.content,m.media_metadata,m.created_at,m.structured_json,COALESCE(mr.sample_id,''),COALESCE(t.outcome,''),m.rowid FROM messages m LEFT JOIN message_requests mr ON mr.message_id=m.id LEFT JOIN telemetry t ON t.id=mr.sample_id WHERE `+where+` ORDER BY m.rowid ASC LIMIT 4`, args...)
	if e != nil {
		return nil, 0, e
	}
	defer rows.Close()
	out := []Message{}
	var next int64
	for rows.Next() {
		var m Message
		var media, state string
		if e = rows.Scan(&m.ID, &m.SessionID, &m.Role, &m.Content, &media, &m.CreatedAt, &state, &m.SampleID, &m.RequestOutcome, &next); e != nil {
			return nil, 0, e
		}
		if json.Unmarshal([]byte(media), &m.Attachments) != nil || json.Unmarshal([]byte(state), &m.MessageState) != nil {
			return nil, 0, errors.New("invalid history")
		}
		out = append(out, m)
	}
	return out, next, rows.Err()
}
