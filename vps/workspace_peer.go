package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

func workspaceURL(raw string) bool {
	if !validateHTTPS(raw) {
		return false
	}
	u, e := url.Parse(raw)
	return e == nil && (u.Path == "" || u.Path == "/")
}
func (s *Server) workspaceCall(ctx context.Context, base, path, token string, body, target any) error {
	if !workspaceURL(base) {
		return errors.New("sync center requires an HTTPS origin")
	}
	b, e := json.Marshal(body)
	if e != nil {
		return e
	}
	if len(b) > workspacePageBytes+(1<<20) {
		return errors.New("sync page too large")
	}
	request, e := http.NewRequestWithContext(ctx, "POST", strings.TrimRight(base, "/")+"/api/workspace/"+path, bytes.NewReader(b))
	if e != nil {
		return errors.New("invalid sync center")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	client := *s.Client
	client.Timeout = 35 * time.Second
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, e := client.Do(request)
	if e != nil {
		return errors.New("sync center unreachable; local changes retained")
	}
	defer response.Body.Close()
	if response.StatusCode == 401 || response.StatusCode == 403 {
		return errors.New("device revoked or pairing expired")
	}
	if response.StatusCode != 200 {
		return errors.New("sync center rejected this page; local changes retained")
	}
	raw, e := io.ReadAll(io.LimitReader(response.Body, workspacePageBytes+(8<<20)+1))
	if e != nil || len(raw) > workspacePageBytes+(8<<20) {
		return errors.New("invalid sync response")
	}
	if target == nil {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if decoder.Decode(target) != nil || decoder.Decode(new(any)) != io.EOF {
		return errors.New("invalid sync response")
	}
	return nil
}
func (s *Server) workspaceConnect(w http.ResponseWriter, r *http.Request) {
	var v struct {
		URL      string `json:"url"`
		Code     string `json:"code"`
		Label    string `json:"label"`
		Platform string `json:"platform"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !workspaceURL(v.URL) || len(v.Code) != 43 || v.Label == "" || !workspaceText(v.Label, 120) || !oneOf(v.Platform, "macOS", "VPS") {
		http.Error(w, "valid HTTPS center and pairing code required", 400)
		return
	}
	if !s.workspaceMu.TryLock() {
		http.Error(w, "sync busy", 409)
		return
	}
	defer s.workspaceMu.Unlock()
	var exists, devices int
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_peer").Scan(&exists)
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_devices WHERE revoked_at IS NULL").Scan(&devices)
	if exists > 0 || devices > 0 {
		http.Error(w, "disconnect the current workspace first", 409)
		return
	}
	var claim struct {
		Protocol  int    `json:"protocol"`
		Device    string `json:"device_id"`
		Workspace string `json:"workspace_id"`
		Token     string `json:"token"`
		Share     bool   `json:"share_secrets"`
	}
	e := s.workspaceCall(r.Context(), v.URL, "pair", "", map[string]any{"code": v.Code, "label": v.Label, "platform": v.Platform}, &claim)
	if e != nil {
		http.Error(w, e.Error(), 502)
		return
	}
	if claim.Protocol != workspaceProtocol || !validID(claim.Device) || !validID(claim.Workspace) || !strings.HasPrefix(claim.Token, "matlas_device_") || len(claim.Token) > 128 {
		http.Error(w, "incompatible sync center", 502)
		return
	}
	encrypted, e := s.encrypt(claim.Token)
	if e != nil {
		http.Error(w, "local vault unavailable; revoke this device on center", 503)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "local database unavailable", 503)
		return
	}
	defer tx.Rollback()
	if _, e = tx.Exec("INSERT OR REPLACE INTO workspace_identity VALUES('local',?,?)", claim.Device, claim.Workspace); e != nil {
		http.Error(w, "local database unavailable", 503)
		return
	}
	if _, e = tx.Exec("INSERT INTO workspace_peer(id,base_url,device_id,encrypted_token,share_secrets,enabled) VALUES(1,?,?,?,?,1)", strings.TrimRight(v.URL, "/"), claim.Device, encrypted, boolInt(claim.Share)); e != nil {
		http.Error(w, "local database unavailable", 503)
		return
	}
	// A connection changes sync lineage, never product data. Previously published
	// local records return to the outbox and must pass the center's CAS checks.
	if _, e = tx.Exec("INSERT OR IGNORE INTO workspace_dirty SELECT kind,id FROM workspace_rows; DELETE FROM workspace_rows; DELETE FROM workspace_fetch"); e != nil {
		http.Error(w, "local database unavailable", 503)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "local database unavailable", 503)
		return
	}
	s.wakeWorker()
	jsonOK(w, 200, map[string]any{"connected": true, "workspace_id": claim.Workspace, "share_secrets": claim.Share})
}
func (s *Server) workspaceDisconnect(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Confirm bool `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm {
		http.Error(w, "explicit disconnect required", 400)
		return
	}
	if !s.workspaceMu.TryLock() {
		http.Error(w, "sync busy", 409)
		return
	}
	defer s.workspaceMu.Unlock()
	// Forget the local transport credential. No product/history rows are removed;
	// the center's independently visible device can still be revoked there.
	if _, e := s.Store.DB.ExecContext(r.Context(), "DELETE FROM workspace_peer; DELETE FROM workspace_fetch"); e != nil {
		http.Error(w, "disconnect failed", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"disconnected": true, "local_data_retained": true})
}
func (s *Server) workspaceSyncHTTP(w http.ResponseWriter, r *http.Request) {
	var v struct{}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.workspaceMu.TryLock() {
		http.Error(w, "sync busy", 409)
		return
	}
	defer s.workspaceMu.Unlock()
	result, e := s.workspaceSyncLocked(r.Context())
	if e != nil {
		http.Error(w, e.Error(), 502)
		return
	}
	jsonOK(w, 200, result)
}
func (s *Server) workspaceTick(ctx context.Context) {
	var enabled int
	var next int64
	if s.Store.DB.QueryRowContext(ctx, "SELECT enabled,next_at FROM workspace_peer WHERE id=1").Scan(&enabled, &next) != nil || enabled != 1 || next > now() || !s.workspaceMu.TryLock() {
		return
	}
	defer s.workspaceMu.Unlock()
	run, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	_, _ = s.workspaceSyncLocked(run)
}
func workspaceShadow(tx *sql.Tx, v WorkspaceRecord) error {
	_, e := tx.Exec("INSERT INTO workspace_rows VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET revision=excluded.revision,data_json=excluded.data_json,deleted=excluded.deleted,origin_device=excluded.origin_device,seq=excluded.seq", v.Kind, v.ID, v.Revision, workspaceJSON(v.Data), boolInt(v.Deleted), v.Device, v.Seq)
	return e
}
func (s *Server) workspaceConsume(ctx context.Context, request WorkspaceExchange, reply WorkspaceReply) error {
	if reply.Protocol != workspaceProtocol || reply.Cursor < request.Cursor || len(reply.Records) > 200 || len(reply.Accepted) > 100 {
		return errors.New("incompatible sync response")
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var expected, device string
	if e = tx.QueryRow("SELECT workspace_id,device_id FROM workspace_identity WHERE id='local'").Scan(&expected, &device); e != nil {
		return e
	}
	if expected != reply.Workspace {
		return errors.New("workspace identity changed; local data retained")
	}
	if _, e = tx.Exec("UPDATE workspace_importing SET active=1 WHERE id=1"); e != nil {
		return e
	}
	submitted := map[string]WorkspaceRecord{}
	for _, v := range request.Changes {
		submitted[workspaceRecordKey(v)] = v
	}
	accepted := map[string]bool{}
	for _, v := range reply.Accepted {
		key := workspaceRecordKey(v)
		sent, exists := submitted[key]
		if !exists || !workspaceAllowed(v.Kind) || v.Revision < sent.Base {
			return errors.New("invalid sync acknowledgement")
		}
		live, _, e := s.workspaceLocalRecordWith(ctx, tx, v.Kind, v.ID)
		if e != nil {
			return e
		}
		liveCompare, sentCompare := live.Data, sent.Data
		if v.Kind == "credentials" && request.Secrets == nil {
			clone := func(m map[string]any) map[string]any {
				out := map[string]any{}
				for k, x := range m {
					if k != "_secret_digest" && k != "_credential_version" {
						out[k] = x
					}
				}
				return out
			}
			liveCompare, sentCompare = clone(live.Data), clone(sent.Data)
		}
		if live.Deleted == sent.Deleted && workspaceJSON(liveCompare) == workspaceJSON(sentCompare) {
			var secret *WorkspaceSecret
			if value, ok := reply.Secrets[key]; ok && str(v.Data["_secret_digest"]) == workspaceHash(workspaceJSON(value)) {
				copy := value
				secret = &copy
			}
			if e = s.workspaceApply(tx, v, secret); e != nil {
				return e
			}
			if _, e = tx.Exec("DELETE FROM workspace_dirty WHERE kind=? AND id=?", v.Kind, v.ID); e != nil {
				return e
			}
		}
		if e = workspaceShadow(tx, v); e != nil {
			return e
		}
		accepted[key] = true
	}
	// Latest requested revisions may be ahead of the ordinary page cursor.
	// Processing ascending sequence avoids overwriting them with older events.
	records := append([]WorkspaceRecord{}, reply.Records...)
	workspaceSort(records)
	for _, v := range records {
		if !workspaceAllowed(v.Kind) || v.Revision < 1 {
			return errors.New("unsupported sync record")
		}
		shadow, e := workspaceReadRow(tx, v.Kind, v.ID)
		if e != nil {
			return e
		}
		if v.Revision <= shadow.Revision {
			continue
		}
		live, _, e := s.workspaceLocalRecordWith(ctx, tx, v.Kind, v.ID)
		if e != nil {
			return e
		}
		var dirty int
		_ = tx.QueryRow("SELECT COUNT(*) FROM workspace_dirty WHERE kind=? AND id=?", v.Kind, v.ID).Scan(&dirty)
		different := live.Deleted != shadow.Deleted || workspaceJSON(live.Data) != workspaceJSON(shadow.Data)
		same := live.Deleted == v.Deleted && workspaceJSON(live.Data) == workspaceJSON(v.Data)
		if dirty > 0 && different && !same {
			live.Base = shadow.Revision
			if e = workspaceConflict(tx, device, live, v); e != nil {
				return e
			}
			continue
		}
		var secret *WorkspaceSecret
		if value, ok := reply.Secrets[workspaceRecordKey(v)]; ok && str(v.Data["_secret_digest"]) == workspaceHash(workspaceJSON(value)) {
			copy := value
			secret = &copy
		}
		if e = s.workspaceApply(tx, v, secret); e != nil {
			return e
		}
		if e = workspaceShadow(tx, v); e != nil {
			return e
		}
		if _, e = tx.Exec("DELETE FROM workspace_dirty WHERE kind=? AND id=?; DELETE FROM workspace_fetch WHERE kind=? AND id=?", v.Kind, v.ID, v.Kind, v.ID); e != nil {
			return e
		}
	}
	for _, remote := range reply.Conflicts {
		local, exists := submitted[workspaceRecordKey(remote)]
		if !exists {
			return errors.New("invalid conflict acknowledgement")
		}
		if e = workspaceConflict(tx, device, local, remote); e != nil {
			return e
		}
	}
	if _, e = tx.Exec("UPDATE workspace_importing SET active=0 WHERE id=1; UPDATE workspace_peer SET cursor=?,last_success=?,last_error='',next_at=? WHERE id=1", reply.Cursor, now(), now()+60000); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Server) workspaceSyncLocked(ctx context.Context) (map[string]any, error) {
	var base, encrypted string
	var share, enabled int
	var cursor int64
	if e := s.Store.DB.QueryRowContext(ctx, "SELECT base_url,encrypted_token,share_secrets,enabled,cursor FROM workspace_peer WHERE id=1").Scan(&base, &encrypted, &share, &enabled, &cursor); e != nil {
		return nil, errors.New("connect a sync center first")
	}
	if enabled != 1 {
		return nil, errors.New("sync paused")
	}
	token, e := s.decrypt(encrypted)
	if e != nil {
		return nil, errors.New("local sync vault unavailable")
	}
	total, received := 0, 0
	more := false
	fail := func(e error) (map[string]any, error) {
		_, _ = s.Store.DB.ExecContext(context.WithoutCancel(ctx), "UPDATE workspace_peer SET last_error=?,next_at=? WHERE id=1", e.Error(), now()+60000)
		return nil, e
	}
	for page := 0; page < 20; page++ {
		changes, secrets, e := s.workspaceDirtyRecords(ctx, 100)
		if e != nil {
			return fail(e)
		}
		request := WorkspaceExchange{Protocol: workspaceProtocol, Cursor: cursor, Changes: changes, Secrets: secrets}
		if share != 1 {
			request.Secrets = nil
			for i := range request.Changes {
				delete(request.Changes[i].Data, "_secret_digest")
			}
		}
		rows, e := s.Store.DB.QueryContext(ctx, "SELECT kind,id FROM workspace_fetch LIMIT 100")
		if e != nil {
			return fail(e)
		}
		for rows.Next() {
			var v WorkspaceRecord
			if rows.Scan(&v.Kind, &v.ID) == nil {
				request.Fetch = append(request.Fetch, v)
			}
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return fail(e)
		}
		var reply WorkspaceReply
		if e = s.workspaceCall(ctx, base, "exchange", token, request, &reply); e != nil {
			return fail(e)
		}
		if e = s.workspaceConsume(ctx, request, reply); e != nil {
			return fail(errors.New("sync validation failed; local changes retained"))
		}
		total += len(reply.Accepted)
		received += len(reply.Records)
		cursor = reply.Cursor
		var dirty int
		_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM workspace_dirty WHERE NOT EXISTS(SELECT 1 FROM workspace_conflicts c WHERE c.kind=workspace_dirty.kind AND c.record_id=workspace_dirty.id AND c.state='OPEN')").Scan(&dirty)
		more = reply.More || dirty > 0
		if !more || ctx.Err() != nil {
			break
		}
	}
	return map[string]any{"sent": total, "received": received, "cursor": cursor, "has_more": more}, nil
}
func (s *Server) workspaceResolve(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID        string `json:"id"`
		Choice    string `json:"choice"`
		Confirm   bool   `json:"confirm"`
		LocalHash string `json:"local_hash"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !oneOf(v.Choice, "local", "remote") || !validID(v.ID) {
		http.Error(w, "explicit conflict choice required", 400)
		return
	}
	if !s.workspaceMu.TryLock() {
		http.Error(w, "sync busy", 409)
		return
	}
	defer s.workspaceMu.Unlock()
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "conflict unavailable", 503)
		return
	}
	defer tx.Rollback()
	var kind, id, remoteRaw string
	if tx.QueryRow("SELECT kind,record_id,remote_json FROM workspace_conflicts WHERE id=? AND state='OPEN'", v.ID).Scan(&kind, &id, &remoteRaw) != nil {
		http.Error(w, "conflict already handled", 409)
		return
	}
	var remote WorkspaceRecord
	if json.Unmarshal([]byte(remoteRaw), &remote) != nil {
		http.Error(w, "conflict invalid", 503)
		return
	}
	live, _, e := s.workspaceLocalRecordWith(r.Context(), tx, kind, id)
	if e != nil {
		http.Error(w, "local record unavailable", 503)
		return
	}
	if len(v.LocalHash) != 64 || v.LocalHash != workspaceHash(workspaceJSON(live)) {
		http.Error(w, "local data changed; review the conflict again", 409)
		return
	}
	var peer int
	_ = tx.QueryRow("SELECT COUNT(*) FROM workspace_peer").Scan(&peer)
	if peer == 0 {
		http.Error(w, "resolve this device conflict on the originating client", 409)
		return
	}
	if v.Choice == "local" {
		e = workspaceShadow(tx, remote)
		if e == nil {
			_, e = tx.Exec("INSERT OR IGNORE INTO workspace_dirty VALUES(?,?)", kind, id)
		}
	} else {
		_, e = tx.Exec("DELETE FROM workspace_dirty WHERE kind=? AND id=?; INSERT OR IGNORE INTO workspace_fetch VALUES(?,?)", kind, id, kind, id)
	}
	if e != nil {
		http.Error(w, "conflict save failed", 503)
		return
	}
	if _, e = tx.Exec("UPDATE workspace_conflicts SET state=? WHERE kind=? AND record_id=? AND state='OPEN'; UPDATE workspace_peer SET next_at=0 WHERE id=1", strings.ToUpper(v.Choice), kind, id); e != nil || tx.Commit() != nil {
		http.Error(w, "conflict save failed", 503)
		return
	}
	s.wakeWorker()
	jsonOK(w, 200, map[string]any{"resolved": true, "choice": v.Choice, "history_retained": true})
}
