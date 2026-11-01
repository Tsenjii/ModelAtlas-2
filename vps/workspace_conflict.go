package main

import (
	"net/http"
)

func (s *Server) workspaceConflictDetail(w http.ResponseWriter, r *http.Request) {
	var kind, id, local, remote string
	key := r.URL.Query().Get("id")
	if !validID(key) {
		http.Error(w, "valid conflict required", 400)
		return
	}
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT kind,record_id,local_json,remote_json FROM workspace_conflicts WHERE id=? AND state='OPEN'", key).Scan(&kind, &id, &local, &remote) != nil {
		http.Error(w, "conflict unavailable", 404)
		return
	}
	live, _, e := s.workspaceLocalRecord(r.Context(), kind, id)
	if e != nil {
		http.Error(w, "local record unavailable", 503)
		return
	}
	original, originalError := workspaceDecodeRecord(local)
	other, otherError := workspaceDecodeRecord(remote)
	if originalError != nil || otherError != nil {
		http.Error(w, "conflict unavailable", 503)
		return
	}
	hash, e := workspaceRecordDigest(live)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	limited := false
	if r.URL.Query().Get("preview") == "1" {
		var a, b, c bool
		live, a = workspacePreviewRecord(live)
		original, b = workspacePreviewRecord(original)
		other, c = workspacePreviewRecord(other)
		limited = a || b || c
	}
	jsonOK(w, 200, map[string]any{"id": key, "kind": kind, "local": live, "original_local": original, "remote": other, "local_hash": hash, "preview_limited": limited})
}
