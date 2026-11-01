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
	"net/url"
	"strconv"
)

const researchRunColumns = "r.rowid,r.id,r.status,r.checkpoint,r.started_at,o.budget,o.used,o.probe_budget,o.probes_used,COALESCE(o.summary_json,'{}'),COALESCE(p.schedule_id,''),COALESCE(s.name,''),q.run_id IS NOT NULL"
const researchRunTables = " FROM research_runs r LEFT JOIN research_run_options o ON o.run_id=r.id LEFT JOIN research_schedule_runs p ON p.run_id=r.id LEFT JOIN research_schedules s ON s.id=p.schedule_id LEFT JOIN research_query_comparison_runs q ON q.run_id=r.id"

func scanResearchRun(rows *sql.Rows) (ResearchRun, int64, error) {
	var run ResearchRun
	var rowID int64
	var budget, used, probes, probeUsed sql.NullInt64
	var raw string
	err := rows.Scan(&rowID, &run.ID, &run.Status, &run.Checkpoint, &run.StartedAt, &budget, &used, &probes, &probeUsed, &raw, &run.ScheduleID, &run.ScheduleName, &run.QueryComparison)
	if err != nil {
		return run, rowID, err
	}
	if budget.Valid {
		run.Budget = &budget.Int64
	}
	if used.Valid {
		run.Used = &used.Int64
	}
	if probes.Valid {
		run.ProbeBudget = &probes.Int64
	}
	if probeUsed.Valid {
		run.ProbesUsed = &probeUsed.Int64
	}
	err = json.Unmarshal([]byte(raw), &run.Summary)
	return run, rowID, err
}

type researchHistoryCursor struct {
	Version       int    `json:"version"`
	Snapshot      int64  `json:"snapshot"`
	BeforeStarted int64  `json:"before_started_at"`
	BeforeRowID   int64  `json:"before_rowid"`
	Query         string `json:"query"`
}
type researchHistoryPage struct {
	Runs       []ResearchRun `json:"runs"`
	Total      int64         `json:"total"`
	NextCursor string        `json:"next_cursor"`
}

// This query continuation grants no execution or credential authority. Both
// new runs and late imports stay outside a previously opened page boundary.
func (s *Server) researchHistory(w http.ResponseWriter, r *http.Request) {
	bad := func() { http.Error(w, "invalid research history query or cursor", 400) }
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		bad()
		return
	}
	for name, values := range q {
		if !oneOf(name, "limit", "cursor", "schedule_id") || len(values) != 1 {
			bad()
			return
		}
	}
	owner := q.Get("schedule_id")
	if owner != "" && !validID(owner) {
		bad()
		return
	}
	limit := 50
	if raw := q.Get("limit"); raw != "" {
		n, e := strconv.Atoi(raw)
		if e != nil || n < 1 || n > 100 {
			bad()
			return
		}
		limit = n
	}
	digest := sha256.Sum256([]byte("research-history-v1\n" + owner))
	queryID := hex.EncodeToString(digest[:])
	var cursor researchHistoryCursor
	if raw := q.Get("cursor"); raw != "" {
		if len(raw) > 2048 {
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
		if decoder.Decode(&cursor) != nil || decoder.Decode(&extra) != io.EOF || cursor.Version != 1 || cursor.Query != queryID || cursor.Snapshot < 1 || cursor.BeforeRowID < 1 || cursor.BeforeRowID > cursor.Snapshot {
			bad()
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, "research history unavailable", 500)
		return
	}
	defer tx.Rollback()
	var newest int64
	if tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(rowid),0) FROM research_runs").Scan(&newest) != nil {
		http.Error(w, "research history unavailable", 500)
		return
	}
	if q.Get("cursor") == "" {
		cursor.Snapshot = newest
	} else if cursor.Snapshot > newest {
		bad()
		return
	}
	where := " WHERE r.rowid<=?"
	args := []any{cursor.Snapshot}
	if owner != "" {
		where += " AND p.schedule_id=?"
		args = append(args, owner)
	}
	page := researchHistoryPage{Runs: []ResearchRun{}}
	if tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM research_runs r LEFT JOIN research_schedule_runs p ON p.run_id=r.id"+where, args...).Scan(&page.Total) != nil {
		http.Error(w, "research history unavailable", 500)
		return
	}
	if cursor.BeforeRowID > 0 {
		where += " AND (r.started_at,r.rowid)<(?,?)"
		args = append(args, cursor.BeforeStarted, cursor.BeforeRowID)
	}
	args = append(args, limit+1)
	rows, err := tx.QueryContext(r.Context(), "SELECT "+researchRunColumns+researchRunTables+where+" ORDER BY r.started_at DESC,r.rowid DESC LIMIT ?", args...)
	if err != nil {
		http.Error(w, "research history unavailable", 500)
		return
	}
	var lastRowID int64
	for rows.Next() {
		run, rowID, e := scanResearchRun(rows)
		if e != nil {
			rows.Close()
			http.Error(w, "research history record unavailable", 500)
			return
		}
		if len(page.Runs) == limit {
			last := page.Runs[len(page.Runs)-1]
			data, _ := json.Marshal(researchHistoryCursor{Version: 1, Snapshot: cursor.Snapshot, BeforeStarted: last.StartedAt, BeforeRowID: lastRowID, Query: queryID})
			page.NextCursor = base64.RawURLEncoding.EncodeToString(data)
			break
		}
		page.Runs = append(page.Runs, run)
		lastRowID = rowID
	}
	err = rows.Err()
	rows.Close()
	if err != nil || tx.Commit() != nil {
		http.Error(w, "research history unavailable", 500)
		return
	}
	jsonOK(w, 200, page)
}
