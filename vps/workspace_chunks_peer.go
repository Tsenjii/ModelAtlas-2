package main

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

var errWorkspacePageTooLarge = errors.New("sync record exceeds supported page; local data retained")

type workspaceHTTPError struct {
	status  int
	message string
}

func (e *workspaceHTTPError) Error() string { return e.message }

type workspaceChunkCapabilities struct {
	Protocol  int    `json:"protocol"`
	Workspace string `json:"workspace_id"`
	Version   int    `json:"chunked_exchange"`
	Chunk     int    `json:"chunk_bytes"`
	Record    int    `json:"record_max_bytes"`
	Request   int    `json:"request_max_bytes"`
	Reply     int    `json:"reply_max_bytes"`
}
type workspaceChunkOutcome struct {
	Sent, Received int
	Cursor         int64
	More           bool
}

func (s *Server) workspaceChunkCall(ctx context.Context, base, path, token string, in, out any) error {
	return s.workspaceCallLimit(ctx, base, path, token, in, out, 384<<10, 512<<10)
}

// Read the live connection before every request and before the destination
// transaction. Neither a paused nor a newly paired device inherits this spool.
func workspaceOutgoingBinding(ctx context.Context, q workspaceChunkQuery, base, token string) (string, string, error) {
	return workspaceOutgoingBindingWith(ctx, q, base, token, false)
}
func workspaceOutgoingBindingWith(ctx context.Context, q workspaceChunkQuery, base, token string, allowPaused bool) (string, string, error) {
	var actual, encrypted, device, ws string
	var enabled, share int
	e := q.QueryRowContext(ctx, "SELECT p.base_url,p.encrypted_token,p.device_id,p.share_secrets,p.enabled,i.workspace_id FROM workspace_peer p JOIN workspace_identity i ON i.id='local' WHERE p.id=1").Scan(&actual, &encrypted, &device, &share, &enabled, &ws)
	if e != nil || actual != base {
		return "", "", errors.New("sync pairing changed; local changes retained")
	}
	if enabled != 1 && !allowPaused {
		return "", "", errors.New("sync paused; local changes retained")
	}
	// The encrypted credential is part of the local lineage as well as its
	// decrypted token hash: replacement cannot reuse an old pending transfer.
	binding := workspaceHash(workspaceJSON(map[string]any{"base": base, "device": device, "workspace": ws, "share": share, "token_hash": workspaceHash(token), "vault_ref": workspaceHash(encrypted)}))
	return device, binding, nil
}
func (s *Server) workspaceDiscardTransfer(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID      string `json:"id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !in.Confirm || !workspaceTransferID.MatchString(in.ID) {
		http.Error(w, "explicit transport-cache reset required", 400)
		return
	}
	if !s.workspaceWait(w, r) {
		return
	}
	defer s.workspaceMu.Unlock()
	var base, encrypted string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT base_url,encrypted_token FROM workspace_peer WHERE id=1").Scan(&base, &encrypted) != nil {
		http.Error(w, "connect a sync center first", 409)
		return
	}
	token, e := s.decrypt(encrypted)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	owner, binding, e := workspaceOutgoingBindingWith(r.Context(), s.Store.DB, base, token, true)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	v, e := workspaceBoundTransfer(r.Context(), s.Store.DB, "OUT", owner, in.ID, binding)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	if v.Consumed || oneOf(v.State, "ACKED", "ABORTED") {
		http.Error(w, "sync result already stored; resume confirmation instead", 409)
		return
	}
	var remote workspaceTransfer
	e = s.workspaceChunkCall(r.Context(), base, "transfers/status", token, map[string]any{"id": v.ID}, &remote)
	var response *workspaceHTTPError
	missing := errors.As(e, &response) && response.status == 404 && v.State == "READY"
	if e != nil && !missing {
		workspaceWriteFailure(w, e)
		return
	}
	if !missing {
		if e = workspaceValidateRemoteTransfer(v, remote); e != nil {
			workspaceWriteFailure(w, e)
			return
		}
		if oneOf(remote.State, "DONE", "ACKED") {
			http.Error(w, "sync already committed; resume the existing result", 409)
			return
		}
		if e = s.workspaceChunkCall(r.Context(), base, "transfers/abort", token, map[string]any{"id": v.ID}, &remote); e != nil {
			workspaceWriteFailure(w, e)
			return
		}
		if e = workspaceValidateRemoteTransfer(v, remote); e != nil || remote.State != "ABORTED" {
			http.Error(w, "transport-cache reset not confirmed; local changes retained", 409)
			return
		}
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM workspace_transfer_parts WHERE role='OUT' AND owner=? AND transfer_id=?", owner, v.ID); e == nil {
		_, e = tx.ExecContext(r.Context(), "UPDATE workspace_transfers SET state='ABORTED',updated_at=? WHERE role='OUT' AND owner=? AND id=? AND consumed=0", now(), owner, v.ID)
	}
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	jsonOK(w, 200, map[string]any{"transport_cache_reset": true, "local_data_retained": true})
}
func (s *Server) workspaceChunkCapabilities(ctx context.Context, base, token string) error {
	supported, e := s.workspaceChunkSupported(ctx, base, token)
	if e != nil {
		return e
	}
	if !supported {
		return errors.New("center does not support bounded large-record recovery; local changes retained")
	}
	return nil
}
func (s *Server) workspaceChunkSupported(ctx context.Context, base, token string) (bool, error) {
	var c workspaceChunkCapabilities
	if e := s.workspaceChunkCall(ctx, base, "capabilities", token, map[string]any{}, &c); e != nil {
		var response *workspaceHTTPError
		if errors.As(e, &response) && response.status == 404 {
			return false, nil
		}
		return false, e
	}
	var ws string
	if e := s.Store.DB.QueryRowContext(ctx, "SELECT workspace_id FROM workspace_identity WHERE id='local'").Scan(&ws); e != nil {
		return false, e
	}
	if c.Protocol == workspaceProtocol && c.Workspace == ws && c.Version == 0 {
		return false, nil
	}
	if c.Protocol != workspaceProtocol || c.Workspace != ws || c.Version != 1 || c.Chunk != workspaceChunkBytes || c.Record != workspaceChunkRecordBytes || c.Request != workspaceChunkRequestBytes || c.Reply != workspaceChunkReplyBytes {
		return false, errors.New("center does not support this bounded large-record protocol; local changes retained")
	}
	return true, nil
}
func (s *Server) workspacePendingOutgoing(ctx context.Context, owner string) (workspaceTransfer, bool, error) {
	var id string
	e := s.Store.DB.QueryRowContext(ctx, "SELECT id FROM workspace_transfers WHERE role='OUT' AND owner=? AND state NOT IN('ACKED','ABORTED') ORDER BY created_at LIMIT 1", owner).Scan(&id)
	if e == sql.ErrNoRows {
		return workspaceTransfer{}, false, nil
	}
	if e != nil {
		return workspaceTransfer{}, false, e
	}
	v, e := workspaceReadTransfer(ctx, s.Store.DB, "OUT", owner, id)
	return v, e == nil, e
}
func (s *Server) workspaceStageOutgoing(ctx context.Context, base, token string, request WorkspaceExchange) (workspaceTransfer, error) {
	body, e := workspaceExchangeBody(request)
	if e != nil {
		return workspaceTransfer{}, errWorkspacePageTooLarge
	}
	hash, n, e := workspaceReaderHash(body.reader())
	if e != nil {
		return workspaceTransfer{}, e
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return workspaceTransfer{}, e
	}
	defer tx.Rollback()
	owner, binding, e := workspaceOutgoingBinding(ctx, tx, base, token)
	if e != nil {
		return workspaceTransfer{}, e
	}
	v := workspaceTransfer{Role: "OUT", Owner: owner, ID: newID(), Context: binding, RequestHash: hash, RequestBytes: n, Received: n, State: "READY"}
	var active int
	if e = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM workspace_transfers WHERE role='OUT' AND owner=? AND state NOT IN('ACKED','ABORTED')", owner).Scan(&active); e != nil {
		return v, e
	}
	if active != 0 {
		return v, errors.New("resume the pending sync transfer first")
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO workspace_transfers(role,owner,id,context_hash,request_sha256,request_bytes,received_bytes,state,created_at,updated_at) VALUES('OUT',?,?,?,?,?,?,'READY',?,?)", owner, v.ID, binding, hash, n, n, now(), now()); e != nil {
		return v, e
	}
	reader := body.reader()
	defer reader.Close()
	buffer := make([]byte, workspaceChunkBytes)
	for offset := int64(0); offset < n; {
		count, e := io.ReadFull(reader, buffer[:min(int64(len(buffer)), n-offset)])
		if e != nil {
			return v, e
		}
		if e = s.workspacePutChunk(ctx, tx, v, "request", offset, buffer[:count]); e != nil {
			return v, e
		}
		offset += int64(count)
	}
	return v, tx.Commit()
}
func (s *Server) workspaceCheckOutgoing(ctx context.Context, base, token string, v workspaceTransfer) error {
	owner, binding, e := workspaceOutgoingBinding(ctx, s.Store.DB, base, token)
	if e != nil {
		return e
	}
	if owner != v.Owner || binding != v.Context {
		return errors.New("sync transfer belongs to an earlier pairing; local changes retained")
	}
	return nil
}
func workspaceValidateRemoteTransfer(local, remote workspaceTransfer) error {
	if remote.ID != local.ID || remote.RequestHash != local.RequestHash || remote.RequestBytes != local.RequestBytes || remote.Received < 0 || remote.Received > local.RequestBytes ||
		(remote.Received != local.RequestBytes && remote.Received%workspaceChunkBytes != 0) ||
		!oneOf(remote.State, "UPLOADING", "UPLOADED", "DONE", "ACKED", "REJECTED", "ABORTED") {
		return errors.New("invalid persisted sync receipt; local changes retained")
	}
	if oneOf(remote.State, "DONE", "ACKED") && (remote.Received != local.RequestBytes || remote.ReplyBytes < 1 || remote.ReplyBytes > workspaceChunkReplyBytes || !workspaceTransferHash.MatchString(remote.ReplyHash)) {
		return errors.New("invalid complete sync receipt")
	}
	if local.ReplyHash != "" && (remote.ReplyHash != local.ReplyHash || remote.ReplyBytes != local.ReplyBytes) {
		return errors.New("committed sync result changed; local changes retained")
	}
	return nil
}
func (s *Server) workspaceResumeOutgoing(ctx context.Context, base, token string, v workspaceTransfer) (workspaceChunkOutcome, error) {
	var result workspaceChunkOutcome
	call := func(path string, in, out any) error {
		if e := s.workspaceCheckOutgoing(ctx, base, token, v); e != nil {
			return e
		}
		return s.workspaceChunkCall(ctx, base, path, token, in, out)
	}
	var remote workspaceTransfer
	if e := call("transfers/begin", map[string]any{"id": v.ID, "bytes": v.RequestBytes, "sha256": v.RequestHash}, &remote); e != nil {
		return result, e
	}
	if e := workspaceValidateRemoteTransfer(v, remote); e != nil {
		return result, e
	}
	if oneOf(remote.State, "REJECTED", "ABORTED") {
		return result, errors.New("pending sync request was rejected; inspect or discard its transport cache; local changes retained")
	}
	if !v.Consumed {
		if remote.State == "ACKED" {
			return result, errors.New("remote result was already acknowledged without a local receipt; local changes retained")
		}
		for offset := remote.Received; offset < v.RequestBytes; {
			data, e := s.workspaceGetChunk(ctx, s.Store.DB, v, "request", offset)
			if e != nil {
				return result, e
			}
			var progress workspaceTransfer
			if e = call("transfers/part", map[string]any{"id": v.ID, "offset": offset, "sha256": contentHash(data), "data": base64.StdEncoding.EncodeToString(data)}, &progress); e != nil {
				return result, e
			}
			if e = workspaceValidateRemoteTransfer(v, progress); e != nil || progress.Received != offset+int64(len(data)) {
				return result, errors.New("sync upload progress changed")
			}
			offset = progress.Received
			if _, e = s.Store.DB.ExecContext(ctx, "UPDATE workspace_transfers SET progress_bytes=?,state='UPLOADING',updated_at=? WHERE role='OUT' AND owner=? AND id=? AND consumed=0", offset, now(), v.Owner, v.ID); e != nil {
				return result, e
			}
		}
		if !oneOf(remote.State, "DONE", "ACKED") {
			if e := call("transfers/finish", map[string]any{"id": v.ID}, &remote); e != nil {
				return result, e
			}
			if e := workspaceValidateRemoteTransfer(v, remote); e != nil {
				return result, e
			}
		}
		if remote.State != "DONE" {
			return result, errors.New("complete sync receipt required")
		}
		v.ReplyHash = remote.ReplyHash
		v.ReplyBytes = remote.ReplyBytes
		if _, e := s.Store.DB.ExecContext(ctx, "UPDATE workspace_transfers SET state='DOWNLOADING',reply_sha256=?,reply_bytes=?,updated_at=? WHERE role='OUT' AND owner=? AND id=? AND consumed=0", v.ReplyHash, v.ReplyBytes, now(), v.Owner, v.ID); e != nil {
			return result, e
		}
		var offset int64
		if e := s.Store.DB.QueryRowContext(ctx, "SELECT COALESCE(SUM(bytes),0) FROM workspace_transfer_parts WHERE role='OUT' AND owner=? AND transfer_id=? AND side='reply'", v.Owner, v.ID).Scan(&offset); e != nil {
			return result, e
		}
		for offset < v.ReplyBytes {
			var part struct {
				ID         string `json:"id"`
				Offset     int64  `json:"offset"`
				Hash       string `json:"sha256"`
				Data       string `json:"data"`
				ReplyHash  string `json:"reply_sha256"`
				ReplyBytes int64  `json:"reply_bytes"`
			}
			if e := call("transfers/reply", map[string]any{"id": v.ID, "offset": offset}, &part); e != nil {
				return result, e
			}
			data, e := base64.StdEncoding.DecodeString(part.Data)
			if e != nil || part.ID != v.ID || part.Offset != offset || part.ReplyHash != v.ReplyHash || part.ReplyBytes != v.ReplyBytes || int64(len(data)) != min(int64(workspaceChunkBytes), v.ReplyBytes-offset) || contentHash(data) != part.Hash {
				return result, errors.New("sync reply fragment integrity mismatch")
			}
			tx, e := s.Store.DB.BeginTx(ctx, nil)
			if e != nil {
				return result, e
			}
			if e = s.workspacePutChunk(ctx, tx, v, "reply", offset, data); e == nil {
				e = tx.Commit()
			}
			_ = tx.Rollback()
			if e != nil {
				return result, e
			}
			offset += int64(len(data))
		}
		hash, n, e := workspaceReaderHash(s.workspaceChunkReader(ctx, s.Store.DB, v, "reply"))
		if e != nil || n != v.ReplyBytes || hash != v.ReplyHash {
			// Only an unconsumed cache is repairable. Product data and receipts remain.
			_, _ = s.Store.DB.ExecContext(context.WithoutCancel(ctx), "DELETE FROM workspace_transfer_parts WHERE role='OUT' AND owner=? AND transfer_id=? AND side='reply' AND EXISTS(SELECT 1 FROM workspace_transfers t WHERE t.role='OUT' AND t.owner=? AND t.id=? AND t.consumed=0)", v.Owner, v.ID, v.Owner, v.ID)
			return result, errors.New("complete sync reply hash mismatch; resume to download a fresh cache")
		}
		var request WorkspaceExchange
		var reply WorkspaceReply
		decode := func(side string, target any) error {
			decoder := json.NewDecoder(s.workspaceChunkReader(ctx, s.Store.DB, v, side))
			decoder.UseNumber()
			if decoder.Decode(target) != nil || decoder.Decode(new(any)) != io.EOF {
				return errors.New("invalid complete sync transfer")
			}
			return nil
		}
		if e = decode("request", &request); e != nil {
			return result, e
		}
		if e = decode("reply", &reply); e != nil {
			return result, e
		}
		e = s.workspaceConsumeWith(ctx, request, reply, workspaceChunkRecordBytes, func(tx *sql.Tx) error {
			owner, binding, e := workspaceOutgoingBinding(ctx, tx, base, token)
			if e != nil {
				return e
			}
			if owner != v.Owner || binding != v.Context {
				return errors.New("sync pairing changed")
			}
			changed, e := tx.ExecContext(ctx, "UPDATE workspace_transfers SET state='CONSUMED',consumed=1,updated_at=? WHERE role='OUT' AND owner=? AND id=? AND context_hash=? AND consumed=0 AND reply_sha256=?", now(), v.Owner, v.ID, v.Context, v.ReplyHash)
			if e != nil {
				return e
			}
			n, e := changed.RowsAffected()
			if e != nil || n != 1 {
				return errors.New("sync receipt changed before local commit")
			}
			return nil
		})
		if e != nil {
			return result, e
		}
		v.Consumed = true
		result = workspaceChunkOutcome{len(reply.Accepted), len(reply.Records), reply.Cursor, reply.More}
	} else {
		var cursor int64
		if e := s.Store.DB.QueryRowContext(ctx, "SELECT cursor FROM workspace_peer WHERE id=1").Scan(&cursor); e != nil {
			return result, e
		}
		result.Cursor = cursor
	}
	var acknowledged workspaceTransfer
	if e := call("transfers/ack", map[string]any{"id": v.ID, "reply_sha256": v.ReplyHash}, &acknowledged); e != nil {
		return result, e
	}
	if e := workspaceValidateRemoteTransfer(v, acknowledged); e != nil || acknowledged.State != "ACKED" {
		return result, errors.New("sync acknowledgement not confirmed")
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return result, e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, "DELETE FROM workspace_transfer_parts WHERE role='OUT' AND owner=? AND transfer_id=?", v.Owner, v.ID); e != nil {
		return result, e
	}
	if _, e = tx.ExecContext(ctx, "UPDATE workspace_transfers SET state='ACKED',updated_at=? WHERE role='OUT' AND owner=? AND id=? AND consumed=1", now(), v.Owner, v.ID); e != nil {
		return result, e
	}
	return result, tx.Commit()
}
func (s *Server) workspaceSyncChunksLocked(ctx context.Context, base, token string, share int) (map[string]any, error) {
	if e := s.workspaceChunkCapabilities(ctx, base, token); e != nil {
		return nil, e
	}
	sent, received := 0, 0
	var cursor int64
	more := false
	for page := 0; page < 20; page++ {
		owner, _, e := workspaceOutgoingBinding(ctx, s.Store.DB, base, token)
		if e != nil {
			return nil, e
		}
		v, pending, e := s.workspacePendingOutgoing(ctx, owner)
		if e != nil {
			return nil, e
		}
		if !pending {
			changes, secrets, e := s.workspaceDirtyRecordsLimit(ctx, 100, workspaceChunkRecordBytes)
			if e != nil {
				return nil, e
			}
			if e = s.Store.DB.QueryRowContext(ctx, "SELECT cursor FROM workspace_peer WHERE id=1").Scan(&cursor); e != nil {
				return nil, e
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
				return nil, e
			}
			for rows.Next() {
				var want WorkspaceRecord
				if e = rows.Scan(&want.Kind, &want.ID); e != nil {
					break
				}
				request.Fetch = append(request.Fetch, want)
			}
			if e == nil {
				e = rows.Err()
			}
			rows.Close()
			if e != nil {
				return nil, e
			}
			v, e = s.workspaceStageOutgoing(ctx, base, token, request)
			if e != nil {
				return nil, e
			}
		}
		result, e := s.workspaceResumeOutgoing(ctx, base, token, v)
		if e != nil {
			return nil, e
		}
		sent += result.Sent
		received += result.Received
		cursor = result.Cursor
		var dirty int
		if e = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM workspace_dirty WHERE NOT EXISTS(SELECT 1 FROM workspace_conflicts c WHERE c.kind=workspace_dirty.kind AND c.record_id=workspace_dirty.id AND c.state='OPEN')").Scan(&dirty); e != nil {
			return nil, e
		}
		more = result.More || dirty > 0
		if !more || ctx.Err() != nil {
			break
		}
	}
	return map[string]any{"sent": sent, "received": received, "cursor": cursor, "has_more": more}, nil
}
