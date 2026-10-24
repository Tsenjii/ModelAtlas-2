package main

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
)

type observationFilter struct {
	Source   string `json:"source"`
	Kind     string `json:"kind"`
	Category string `json:"category"`
	Start    *int64 `json:"start"`
	End      *int64 `json:"end"`
}

type observationCursor struct {
	Snapshot int64  `json:"snapshot"`
	Before   int64  `json:"before"`
	Query    string `json:"query"`
}

// Keyset pagination freezes the insertion boundary. New observations cannot
// shift subsequent pages, including observations sharing one timestamp.
// The cursor is a query continuation, not an authorization credential.
func (s *Server) modelObservations(w http.ResponseWriter, r *http.Request) {
	v, q := selectionQuery(r), r.URL.Query()
	f := observationFilter{Source: q.Get("source"), Kind: q.Get("kind"), Category: q.Get("category")}
	limit := 200
	bad := func() { http.Error(w, "invalid observation query or cursor", 400) }
	if !validScope(v) || safeText(f.Source, 256) != f.Source || safeText(f.Kind, 256) != f.Kind || !oneOf(f.Category, "", "PRICE") {
		bad()
		return
	}
	if raw := q.Get("limit"); raw != "" {
		n, e := strconv.Atoi(raw)
		if e != nil || n < 1 || n > 200 {
			bad()
			return
		}
		limit = n
	}
	for name, target := range map[string]**int64{"start": &f.Start, "end": &f.End} {
		if raw := q.Get(name); raw != "" {
			n, e := strconv.ParseInt(raw, 10, 64)
			if e != nil || n < 0 {
				bad()
				return
			}
			*target = &n
		}
	}
	if f.Start != nil && f.End != nil && *f.End <= *f.Start {
		bad()
		return
	}
	identity, _ := json.Marshal(struct {
		Scope  Selection
		Filter observationFilter
	}{v, f})
	digest := sha256.Sum256(identity)
	queryID := hex.EncodeToString(digest[:])
	var cursor observationCursor
	if raw := q.Get("cursor"); raw != "" {
		if len(raw) > 1024 {
			bad()
			return
		}
		data, e := base64.RawURLEncoding.DecodeString(raw)
		if e != nil {
			bad()
			return
		}
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.DisallowUnknownFields()
		var extra any
		if decoder.Decode(&cursor) != nil || decoder.Decode(&extra) != io.EOF || cursor.Query != queryID || cursor.Snapshot <= 0 || cursor.Before <= 0 || cursor.Before > cursor.Snapshot {
			bad()
			return
		}
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if q.Get("cursor") == "" {
		if tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(rowid),0) FROM observations WHERE scope_key=?", scopeKey(v)).Scan(&cursor.Snapshot) != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	base := "scope_key=? AND rowid<=?"
	args := []any{scopeKey(v), cursor.Snapshot}
	if f.Start != nil {
		base += " AND observed_at>=?"
		args = append(args, *f.Start)
	}
	if f.End != nil {
		base += " AND observed_at<?"
		args = append(args, *f.End)
	}
	if f.Category == "PRICE" {
		base += " AND (json_type(facts_json,'$.price')='object' OR json_type(facts_json,'$.effective_charge')='object')"
	}
	where := base
	filtered := append([]any{}, args...)
	if f.Source != "" {
		where += " AND source=?"
		filtered = append(filtered, f.Source)
	}
	if f.Kind != "" {
		where += " AND kind=?"
		filtered = append(filtered, f.Kind)
	}
	var total int
	if tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM observations WHERE "+where, filtered...).Scan(&total) != nil {
		http.Error(w, "database error", 500)
		return
	}
	pageWhere := where
	parameters := append([]any{}, filtered...)
	if cursor.Before > 0 {
		pageWhere += " AND rowid<?"
		parameters = append(parameters, cursor.Before)
	}
	parameters = append(parameters, limit+1)
	rows, e := tx.QueryContext(r.Context(), "SELECT rowid,id,kind,source,status,facts_json,observed_at FROM observations WHERE "+pageWhere+" ORDER BY rowid DESC LIMIT ?", parameters...)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	out, rowIDs := []Observation{}, []int64{}
	for rows.Next() {
		var o Observation
		var rowID int64
		var raw string
		o.Selection = v
		if rows.Scan(&rowID, &o.ID, &o.Kind, &o.Source, &o.Status, &raw, &o.ObservedAt) != nil || json.Unmarshal([]byte(raw), &o.Facts) != nil {
			rows.Close()
			http.Error(w, "observation unavailable", 500)
			return
		}
		out = append(out, o)
		rowIDs = append(rowIDs, rowID)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	next := ""
	if len(out) > limit {
		out = out[:limit]
		data, _ := json.Marshal(observationCursor{Snapshot: cursor.Snapshot, Before: rowIDs[limit-1], Query: queryID})
		next = base64.RawURLEncoding.EncodeToString(data)
	}
	// Facets cover the snapshot/time range, not only the first displayed page.
	sources, kinds := []string{}, []string{}
	for _, field := range []string{"source", "kind"} {
		rows, e := tx.QueryContext(r.Context(), "SELECT DISTINCT "+field+" FROM observations WHERE "+base+" ORDER BY "+field, args...)
		if e != nil {
			http.Error(w, "database error", 500)
			return
		}
		for rows.Next() {
			var value string
			if rows.Scan(&value) != nil {
				rows.Close()
				http.Error(w, "database error", 500)
				return
			}
			if field == "source" {
				sources = append(sources, value)
			} else {
				kinds = append(kinds, value)
			}
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"selection": v, "query": f, "history": out, "total": total, "next_cursor": next, "sources": sources, "kinds": kinds})
}
