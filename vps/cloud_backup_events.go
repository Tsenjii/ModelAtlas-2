package main

import (
	"database/sql"
	"net/http"
	"strconv"
)

type cloudBackupEvent struct {
	Sequence  int64  `json:"sequence"`
	JobID     string `json:"job_id"`
	State     string `json:"state"`
	At        int64  `json:"at"`
	ErrorCode string `json:"error_code"`
}

func (s *Server) getCloudBackupEvents(w http.ResponseWriter, r *http.Request) {
	m := s.backups
	if m == nil || m.db == nil {
		http.Error(w, "備份紀錄暫時無法讀取。", 503)
		return
	}
	id := r.URL.Query().Get("id")
	if !validCloudBackupID(id) {
		http.Error(w, "invalid backup identity", 400)
		return
	}
	job, _, err := m.job(r.Context(), id)
	if err == sql.ErrNoRows {
		http.Error(w, "backup not found", 404)
		return
	}
	if err != nil || !validCloudBackupJob(job) {
		http.Error(w, "原備份狀態暫時無法讀取，沒有重新建立工作。", 503)
		return
	}
	before := int64(1<<53 - 1)
	if raw := r.URL.Query().Get("before"); raw != "" {
		before, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || before <= 0 || before > 1<<53-1 {
			http.Error(w, "invalid backup event cursor", 400)
			return
		}
	}
	rows, err := m.db.QueryContext(r.Context(), "SELECT seq,job_id,state,at,error_code FROM backup_events WHERE job_id=? AND seq<? ORDER BY seq DESC LIMIT 26", id, before)
	if err != nil {
		http.Error(w, "備份過程暫時無法讀取，保留上次結果。", 503)
		return
	}
	defer rows.Close()
	events := []cloudBackupEvent{}
	for rows.Next() {
		var event cloudBackupEvent
		if rows.Scan(&event.Sequence, &event.JobID, &event.State, &event.At, &event.ErrorCode) != nil || event.Sequence < 1 || event.Sequence >= 1<<53 || event.JobID != id || event.At < 1 || event.At >= 1<<53 || !oneOf(event.State, "PENDING", "SNAPSHOT", "UPLOADING", "VERIFYING", "SUCCESS", "FAILED", "INTERRUPTED", "CANCELLED") {
			http.Error(w, "備份過程包含無法辨識的紀錄，未當成空結果。", 503)
			return
		}
		events = append(events, event)
	}
	if rows.Err() != nil {
		http.Error(w, "備份過程暫時無法讀取。", 503)
		return
	}
	next := int64(0)
	if len(events) > 25 {
		events = events[:25]
		next = events[len(events)-1].Sequence
	}
	jsonOK(w, 200, map[string]any{"job": job, "events": events, "next_before": next})
}
