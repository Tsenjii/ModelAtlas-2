package main

import (
	"encoding/json"
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
	var original, other WorkspaceRecord
	if json.Unmarshal([]byte(local), &original) != nil || json.Unmarshal([]byte(remote), &other) != nil {
		http.Error(w, "conflict unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"id": key, "kind": kind, "local": live, "original_local": original, "remote": other, "local_hash": workspaceHash(workspaceJSON(live))})
}
