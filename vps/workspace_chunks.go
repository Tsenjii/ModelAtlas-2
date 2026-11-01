package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strings"
)

const workspaceChunkBytes = 256 << 10
const workspaceChunkRecordBytes = 64 << 20
const workspaceChunkRequestBytes = 65 << 20
const workspaceChunkReplyBytes = 72 << 20

// Encrypted transport caches are local runtime state, never execution grants or
// replacements for product history. ACK removes cache bytes, not the receipt.
const workspaceChunkSchema = `
CREATE TABLE IF NOT EXISTS workspace_transfers(
 role TEXT NOT NULL CHECK(role IN('IN','OUT')),owner TEXT NOT NULL,id TEXT NOT NULL,
 context_hash TEXT NOT NULL,request_sha256 TEXT NOT NULL,
 request_bytes INTEGER NOT NULL CHECK(request_bytes>0 AND request_bytes<=68157440),
 received_bytes INTEGER NOT NULL DEFAULT 0 CHECK(received_bytes>=0),
 state TEXT NOT NULL,reply_sha256 TEXT NOT NULL DEFAULT '',
 reply_bytes INTEGER NOT NULL DEFAULT 0 CHECK(reply_bytes>=0 AND reply_bytes<=75497472),
 progress_bytes INTEGER NOT NULL DEFAULT 0 CHECK(progress_bytes>=0),
 consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN(0,1)),
 problem TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
 PRIMARY KEY(role,owner,id));
CREATE TABLE IF NOT EXISTS workspace_transfer_parts(
 role TEXT NOT NULL,owner TEXT NOT NULL,transfer_id TEXT NOT NULL,
 side TEXT NOT NULL CHECK(side IN('request','reply')),offset INTEGER NOT NULL CHECK(offset>=0),
 sha256 TEXT NOT NULL,bytes INTEGER NOT NULL CHECK(bytes>0 AND bytes<=262144),
 encrypted_payload TEXT NOT NULL CHECK(length(encrypted_payload)>0),
 PRIMARY KEY(role,owner,transfer_id,side,offset),
 FOREIGN KEY(role,owner,transfer_id) REFERENCES workspace_transfers(role,owner,id) ON DELETE RESTRICT);
`

type workspaceExchangeOptions struct {
	MaxBytes int
	Locked   bool
	Before   func() (bool, error)
	Save     func(*sql.Tx, *WorkspaceReply) error
}
type workspaceProtocolError struct {
	message string
	status  int
}

func (e *workspaceProtocolError) Error() string { return e.message }
func workspaceProtocolFailure(message string, status int) error {
	return &workspaceProtocolError{message, status}
}
func workspaceWriteFailure(w http.ResponseWriter, e error) {
	var p *workspaceProtocolError
	if errors.As(e, &p) {
		http.Error(w, p.message, p.status)
		return
	}
	http.Error(w, "sync storage unavailable; local changes retained", 503)
}

type workspaceChunkQuery interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func workspaceDeviceCurrent(ctx context.Context, q workspaceChunkQuery, d workspaceDevice, token string) error {
	var share int
	if !strings.HasPrefix(token, "matlas_device_") || len(token) > 128 ||
		q.QueryRowContext(ctx, "SELECT share_secrets FROM workspace_devices WHERE id=? AND token_hash=? AND revoked_at IS NULL", d.ID, workspaceHash(token)).Scan(&share) != nil ||
		(share == 1) != d.ShareSecrets {
		return workspaceProtocolFailure("device authorization required", 401)
	}
	return nil
}
func workspaceChunkContext(ctx context.Context, q workspaceChunkQuery, d workspaceDevice, token, host string) (string, error) {
	if e := workspaceDeviceCurrent(ctx, q, d, token); e != nil {
		return "", e
	}
	var ws string
	if e := q.QueryRowContext(ctx, "SELECT workspace_id FROM workspace_identity WHERE id='local'").Scan(&ws); e != nil {
		return "", e
	}
	if host == "" || len(host) > 1024 {
		return "", workspaceProtocolFailure("sync origin unavailable", 409)
	}
	return workspaceHash(workspaceJSON(map[string]any{"protocol": 1, "workspace": ws, "device": d.ID, "share": d.ShareSecrets, "token_hash": workspaceHash(token), "origin_host": strings.ToLower(host)})), nil
}
func workspaceDataJSON(data map[string]any, limit int) (string, error) {
	b, e := encodeRequestJSON(data, int64(limit))
	if e != nil {
		return "", e
	}
	var out strings.Builder
	out.Grow(int(b.size))
	if _, e = io.Copy(&out, b.reader()); e != nil {
		return "", e
	}
	return out.String(), nil
}

// Compare the same canonical JSON bytes in bounded buffers; two retained large
// records need not allocate two additional complete marshal buffers.
func workspaceDataEqual(a, b map[string]any) bool {
	left, e := encodeRequestJSON(a, workspaceChunkRecordBytes)
	if e != nil {
		return false
	}
	right, e := encodeRequestJSON(b, workspaceChunkRecordBytes)
	if e != nil || left.size != right.size {
		return false
	}
	x, y := left.reader(), right.reader()
	defer x.Close()
	defer y.Close()
	xb, yb := make([]byte, 32<<10), make([]byte, 32<<10)
	for remaining := left.size; remaining > 0; {
		n := int(min(int64(len(xb)), remaining))
		if _, e = io.ReadFull(x, xb[:n]); e != nil {
			return false
		}
		if _, e = io.ReadFull(y, yb[:n]); e != nil {
			return false
		}
		if !bytes.Equal(xb[:n], yb[:n]) {
			return false
		}
		remaining -= int64(n)
	}
	return true
}

// Preserve encoding/json's struct order for existing conflict hashes.
func workspaceRecordBody(v WorkspaceRecord, limit int64) (*requestJSON, error) {
	b := &requestJSON{limit: limit}
	fields := []struct {
		name  string
		value any
	}{{"kind", v.Kind}, {"id", v.ID}, {"revision", v.Revision}, {"base_revision", v.Base}, {"data", v.Data}, {"deleted", v.Deleted}, {"origin_device", v.Device}, {"seq", v.Seq}}
	if e := b.add("{"); e != nil {
		return nil, e
	}
	for i, f := range fields {
		if i > 0 {
			if e := b.add(","); e != nil {
				return nil, e
			}
		}
		if e := b.quoted(f.name); e != nil {
			return nil, e
		}
		if e := b.add(":"); e != nil {
			return nil, e
		}
		if e := b.encode(f.value, 0); e != nil {
			return nil, e
		}
	}
	if e := b.add("}"); e != nil {
		return nil, e
	}
	return b, nil
}
func workspaceRecordJSON(v WorkspaceRecord) (string, error) {
	b, e := workspaceRecordBody(v, workspaceChunkRecordBytes+(1<<20))
	if e != nil {
		return "", e
	}
	var out strings.Builder
	out.Grow(int(b.size))
	if _, e = io.Copy(&out, b.reader()); e != nil {
		return "", e
	}
	return out.String(), nil
}
func workspaceRecordWire(v WorkspaceRecord) map[string]any {
	return map[string]any{"kind": v.Kind, "id": v.ID, "revision": v.Revision, "base_revision": v.Base, "data": v.Data, "deleted": v.Deleted, "origin_device": v.Device, "seq": v.Seq}
}
func workspaceRecordsWire(rows []WorkspaceRecord) []any {
	out := make([]any, 0, len(rows))
	for _, v := range rows {
		out = append(out, workspaceRecordWire(v))
	}
	return out
}
func workspaceExchangeBody(v WorkspaceExchange) (*requestJSON, error) {
	out := map[string]any{"protocol": v.Protocol, "cursor": v.Cursor, "changes": workspaceRecordsWire(v.Changes)}
	if len(v.Secrets) > 0 {
		out["secrets"] = v.Secrets
	}
	if len(v.Fetch) > 0 {
		out["fetch"] = workspaceRecordsWire(v.Fetch)
	}
	return encodeRequestJSON(out, workspaceChunkRequestBytes)
}
func workspaceReplyBody(v WorkspaceReply) (*requestJSON, error) {
	out := map[string]any{"protocol": v.Protocol, "workspace_id": v.Workspace, "cursor": v.Cursor, "has_more": v.More, "records": workspaceRecordsWire(v.Records), "accepted": workspaceRecordsWire(v.Accepted), "conflicts": workspaceRecordsWire(v.Conflicts)}
	if len(v.Secrets) > 0 {
		out["secrets"] = v.Secrets
	}
	return encodeRequestJSON(out, workspaceChunkReplyBytes)
}

type workspaceTransfer struct {
	ID                   string `json:"id"`
	State                string `json:"state"`
	RequestHash          string `json:"request_sha256"`
	RequestBytes         int64  `json:"request_bytes"`
	Received             int64  `json:"received_bytes"`
	ReplyHash            string `json:"reply_sha256"`
	ReplyBytes           int64  `json:"reply_bytes"`
	Progress             int64  `json:"progress_bytes"`
	Consumed             bool   `json:"consumed"`
	Problem              string `json:"problem"`
	Role, Owner, Context string `json:"-"`
}

var workspaceTransferID = regexp.MustCompile(`^[A-Za-z0-9-]{1,120}$`)
var workspaceTransferHash = regexp.MustCompile(`^[a-f0-9]{64}$`)

func workspaceReadTransfer(ctx context.Context, q workspaceChunkQuery, role, owner, id string) (workspaceTransfer, error) {
	v := workspaceTransfer{ID: id, Role: role, Owner: owner}
	e := q.QueryRowContext(ctx, "SELECT context_hash,request_sha256,request_bytes,received_bytes,state,reply_sha256,reply_bytes,progress_bytes,consumed,problem FROM workspace_transfers WHERE role=? AND owner=? AND id=?", role, owner, id).Scan(&v.Context, &v.RequestHash, &v.RequestBytes, &v.Received, &v.State, &v.ReplyHash, &v.ReplyBytes, &v.Progress, &v.Consumed, &v.Problem)
	if e == sql.ErrNoRows {
		return v, workspaceProtocolFailure("sync transfer not found", 404)
	}
	return v, e
}
func workspaceBoundTransfer(ctx context.Context, q workspaceChunkQuery, role, owner, id, binding string) (workspaceTransfer, error) {
	v, e := workspaceReadTransfer(ctx, q, role, owner, id)
	if e == nil && v.Context != binding {
		e = workspaceProtocolFailure("sync transfer scope changed; local data retained", 409)
	}
	return v, e
}
func workspaceChunkBinding(v workspaceTransfer, side string, offset int64, hash string, count int) string {
	reply := ""
	if side == "reply" {
		reply = v.ReplyHash
	}
	return "workspace-chunk-v1:" + workspaceHash(workspaceJSON(map[string]any{"role": v.Role, "owner": v.Owner, "id": v.ID, "context": v.Context, "request_hash": v.RequestHash, "request_bytes": v.RequestBytes, "reply_hash": reply, "side": side, "offset": offset, "sha256": hash, "bytes": count})) + "\n"
}
func (s *Server) workspaceSealChunk(v workspaceTransfer, side string, offset int64, data []byte) (string, string, error) {
	hash := contentHash(data)
	encrypted, e := s.encrypt(workspaceChunkBinding(v, side, offset, hash, len(data)) + string(data))
	return encrypted, hash, e
}
func (s *Server) workspaceOpenChunk(v workspaceTransfer, side string, offset int64, hash string, count int, encrypted string) ([]byte, error) {
	plain, e := s.decrypt(encrypted)
	if e != nil {
		return nil, errors.New("encrypted sync fragment unavailable")
	}
	prefix := workspaceChunkBinding(v, side, offset, hash, count)
	if !strings.HasPrefix(plain, prefix) {
		return nil, errors.New("encrypted sync fragment scope mismatch")
	}
	data := []byte(plain[len(prefix):])
	if len(data) != count || contentHash(data) != hash {
		return nil, errors.New("sync fragment integrity mismatch")
	}
	return data, nil
}
func (s *Server) workspacePutChunk(ctx context.Context, tx *sql.Tx, v workspaceTransfer, side string, offset int64, data []byte) error {
	if len(data) == 0 || len(data) > workspaceChunkBytes || offset < 0 {
		return workspaceProtocolFailure("invalid sync fragment", 422)
	}
	encrypted, hash, e := s.workspaceSealChunk(v, side, offset, data)
	if e != nil {
		return e
	}
	_, e = tx.ExecContext(ctx, "INSERT INTO workspace_transfer_parts(role,owner,transfer_id,side,offset,sha256,bytes,encrypted_payload) VALUES(?,?,?,?,?,?,?,?)", v.Role, v.Owner, v.ID, side, offset, hash, len(data), encrypted)
	return e
}
func (s *Server) workspaceGetChunk(ctx context.Context, q workspaceChunkQuery, v workspaceTransfer, side string, offset int64) ([]byte, error) {
	var hash, encrypted string
	var n int
	e := q.QueryRowContext(ctx, "SELECT sha256,bytes,encrypted_payload FROM workspace_transfer_parts WHERE role=? AND owner=? AND transfer_id=? AND side=? AND offset=?", v.Role, v.Owner, v.ID, side, offset).Scan(&hash, &n, &encrypted)
	if e != nil {
		return nil, errors.New("sync fragment unavailable")
	}
	return s.workspaceOpenChunk(v, side, offset, hash, n, encrypted)
}

type workspaceChunkReader struct {
	server        *Server
	ctx           context.Context
	query         workspaceChunkQuery
	transfer      workspaceTransfer
	side          string
	total, offset int64
	pending       []byte
}

func (r *workspaceChunkReader) Read(out []byte) (int, error) {
	if len(out) == 0 {
		return 0, nil
	}
	if e := r.ctx.Err(); e != nil {
		return 0, e
	}
	if len(r.pending) == 0 {
		if r.offset == r.total {
			return 0, io.EOF
		}
		data, e := r.server.workspaceGetChunk(r.ctx, r.query, r.transfer, r.side, r.offset)
		if e != nil {
			return 0, e
		}
		if int64(len(data)) != min(int64(workspaceChunkBytes), r.total-r.offset) {
			return 0, errors.New("incomplete sync fragment sequence")
		}
		r.pending = data
		r.offset += int64(len(data))
	}
	n := copy(out, r.pending)
	r.pending = r.pending[n:]
	return n, nil
}
func (s *Server) workspaceChunkReader(ctx context.Context, q workspaceChunkQuery, v workspaceTransfer, side string) io.Reader {
	total := v.RequestBytes
	if side == "reply" {
		total = v.ReplyBytes
	}
	return &workspaceChunkReader{server: s, ctx: ctx, query: q, transfer: v, side: side, total: total}
}
func workspaceReaderHash(reader io.Reader) (string, int64, error) {
	h := sha256.New()
	n, e := io.Copy(h, reader)
	return hex.EncodeToString(h.Sum(nil)), n, e
}
func (s *Server) workspaceChunkTransaction(w http.ResponseWriter, r *http.Request, run func(*sql.Tx, workspaceDevice, string) (any, error)) {
	d, ok := r.Context().Value(workspaceDeviceContext{}).(workspaceDevice)
	if !ok {
		workspaceWriteFailure(w, workspaceProtocolFailure("device required", 401))
		return
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	defer tx.Rollback()
	binding, e := workspaceChunkContext(r.Context(), tx, d, token, r.Host)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	out, e := run(tx, d, binding)
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) workspaceCapabilitiesHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct{}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		var ws string
		if e := tx.QueryRowContext(r.Context(), "SELECT workspace_id FROM workspace_identity WHERE id='local'").Scan(&ws); e != nil {
			return nil, e
		}
		support := 0
		if len(s.Key) == 32 {
			support = 1
		}
		return map[string]any{"protocol": 1, "workspace_id": ws, "chunked_exchange": support, "chunk_bytes": workspaceChunkBytes, "record_max_bytes": workspaceChunkRecordBytes, "request_max_bytes": workspaceChunkRequestBytes, "reply_max_bytes": workspaceChunkReplyBytes}, nil
	})
}
func (s *Server) workspaceChunkBeginHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID    string `json:"id"`
		Bytes int64  `json:"bytes"`
		Hash  string `json:"sha256"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	if !workspaceTransferID.MatchString(in.ID) || !workspaceTransferHash.MatchString(in.Hash) || in.Bytes < 1 || in.Bytes > workspaceChunkRequestBytes {
		workspaceWriteFailure(w, workspaceProtocolFailure("bounded stable sync transfer required", 422))
		return
	}
	if len(s.Key) != 32 {
		workspaceWriteFailure(w, workspaceProtocolFailure("secure local sync storage unavailable", 503))
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		old, e := workspaceReadTransfer(r.Context(), tx, "IN", d.ID, in.ID)
		var missing *workspaceProtocolError
		if e == nil {
			if old.Context != binding || old.RequestHash != in.Hash || old.RequestBytes != in.Bytes {
				return nil, workspaceProtocolFailure("sync transfer ID content or scope changed", 409)
			}
			return old, nil
		}
		if !errors.As(e, &missing) || missing.status != 404 {
			return nil, e
		}
		var active int
		if e = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_transfers WHERE role='IN' AND owner=? AND state NOT IN('ACKED','ABORTED')", d.ID).Scan(&active); e != nil {
			return nil, e
		}
		if active != 0 {
			return nil, workspaceProtocolFailure("resume the pending sync transfer before starting another", 409)
		}
		_, e = tx.ExecContext(r.Context(), "INSERT INTO workspace_transfers(role,owner,id,context_hash,request_sha256,request_bytes,state,created_at,updated_at) VALUES('IN',?,?,?,?,?,'UPLOADING',?,?)", d.ID, in.ID, binding, in.Hash, in.Bytes, now(), now())
		if e != nil {
			return nil, e
		}
		return workspaceReadTransfer(r.Context(), tx, "IN", d.ID, in.ID)
	})
}
func (s *Server) workspaceChunkStatusHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		return workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, in.ID, binding)
	})
}
func (s *Server) workspaceChunkPartHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID     string `json:"id"`
		Offset int64  `json:"offset"`
		Hash   string `json:"sha256"`
		Data   string `json:"data"`
	}
	if !decodeLimit(w, r, &in, 384<<10) {
		return
	}
	if in.Offset < 0 || !workspaceTransferHash.MatchString(in.Hash) || len(in.Data) > base64.StdEncoding.EncodedLen(workspaceChunkBytes) {
		workspaceWriteFailure(w, workspaceProtocolFailure("invalid sync fragment", 422))
		return
	}
	data, e := base64.StdEncoding.DecodeString(in.Data)
	if e != nil || len(data) == 0 || len(data) > workspaceChunkBytes || contentHash(data) != in.Hash {
		workspaceWriteFailure(w, workspaceProtocolFailure("sync fragment hash mismatch", 422))
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		v, e := workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, in.ID, binding)
		if e != nil {
			return nil, e
		}
		if !oneOf(v.State, "UPLOADING", "UPLOADED") {
			return nil, workspaceProtocolFailure("sync request is immutable; read its result", 409)
		}
		if int64(len(data)) != min(int64(workspaceChunkBytes), v.RequestBytes-in.Offset) || in.Offset >= v.RequestBytes || in.Offset%workspaceChunkBytes != 0 {
			return nil, workspaceProtocolFailure("sync fragment bounds changed", 422)
		}
		if in.Offset < v.Received {
			var hash string
			var n int
			if e = tx.QueryRowContext(r.Context(), "SELECT sha256,bytes FROM workspace_transfer_parts WHERE role='IN' AND owner=? AND transfer_id=? AND side='request' AND offset=?", d.ID, v.ID, in.Offset).Scan(&hash, &n); e != nil {
				return nil, e
			}
			if hash != in.Hash || n != len(data) {
				return nil, workspaceProtocolFailure("sync fragment content changed", 409)
			}
			// An identical replay repairs only cache ciphertext, never a receipt.
			encrypted, _, e := s.workspaceSealChunk(v, "request", in.Offset, data)
			if e != nil {
				return nil, e
			}
			_, e = tx.ExecContext(r.Context(), "UPDATE workspace_transfer_parts SET encrypted_payload=? WHERE role='IN' AND owner=? AND transfer_id=? AND side='request' AND offset=?", encrypted, d.ID, v.ID, in.Offset)
			return v, e
		}
		if in.Offset != v.Received || v.State != "UPLOADING" {
			return nil, workspaceProtocolFailure("read the persisted sync offset before continuing", 409)
		}
		if e = s.workspacePutChunk(r.Context(), tx, v, "request", in.Offset, data); e != nil {
			return nil, e
		}
		v.Received += int64(len(data))
		if v.Received == v.RequestBytes {
			v.State = "UPLOADED"
		}
		_, e = tx.ExecContext(r.Context(), "UPDATE workspace_transfers SET received_bytes=?,state=?,updated_at=? WHERE role='IN' AND owner=? AND id=?", v.Received, v.State, now(), d.ID, v.ID)
		return v, e
	})
}
func (s *Server) workspaceChunkReplyHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID     string `json:"id"`
		Offset int64  `json:"offset"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		v, e := workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, in.ID, binding)
		if e != nil {
			return nil, e
		}
		if v.State != "DONE" || in.Offset < 0 || in.Offset >= v.ReplyBytes || in.Offset%workspaceChunkBytes != 0 {
			return nil, workspaceProtocolFailure("complete sync result required", 409)
		}
		data, e := s.workspaceGetChunk(r.Context(), tx, v, "reply", in.Offset)
		if e != nil {
			return nil, e
		}
		return map[string]any{"id": v.ID, "offset": in.Offset, "sha256": contentHash(data), "data": base64.StdEncoding.EncodeToString(data), "reply_sha256": v.ReplyHash, "reply_bytes": v.ReplyBytes}, nil
	})
}
func (s *Server) workspaceChunkAckHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID   string `json:"id"`
		Hash string `json:"reply_sha256"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		v, e := workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, in.ID, binding)
		if e != nil {
			return nil, e
		}
		if !oneOf(v.State, "DONE", "ACKED") || v.ReplyHash != in.Hash {
			return nil, workspaceProtocolFailure("confirmed sync result hash required", 409)
		}
		if _, e = tx.ExecContext(r.Context(), "DELETE FROM workspace_transfer_parts WHERE role='IN' AND owner=? AND transfer_id=?", d.ID, v.ID); e != nil {
			return nil, e
		}
		_, e = tx.ExecContext(r.Context(), "UPDATE workspace_transfers SET state='ACKED',consumed=1,updated_at=? WHERE role='IN' AND owner=? AND id=?", now(), d.ID, v.ID)
		v.State = "ACKED"
		v.Consumed = true
		return v, e
	})
}
func (s *Server) workspaceChunkAbortHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	s.workspaceChunkTransaction(w, r, func(tx *sql.Tx, d workspaceDevice, binding string) (any, error) {
		v, e := workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, in.ID, binding)
		if e != nil {
			return nil, e
		}
		if oneOf(v.State, "DONE", "ACKED") {
			return nil, workspaceProtocolFailure("sync already committed; read the existing result", 409)
		}
		if _, e = tx.ExecContext(r.Context(), "DELETE FROM workspace_transfer_parts WHERE role='IN' AND owner=? AND transfer_id=?", d.ID, v.ID); e != nil {
			return nil, e
		}
		_, e = tx.ExecContext(r.Context(), "UPDATE workspace_transfers SET state='ABORTED',updated_at=? WHERE role='IN' AND owner=? AND id=?", now(), d.ID, v.ID)
		v.State = "ABORTED"
		return v, e
	})
}
func (s *Server) workspaceAuthorizedTransfer(r *http.Request, id string) (workspaceTransfer, workspaceDevice, string, error) {
	d, ok := r.Context().Value(workspaceDeviceContext{}).(workspaceDevice)
	if !ok {
		return workspaceTransfer{}, d, "", workspaceProtocolFailure("device required", 401)
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		return workspaceTransfer{}, d, token, e
	}
	defer tx.Rollback()
	binding, e := workspaceChunkContext(r.Context(), tx, d, token, r.Host)
	if e != nil {
		return workspaceTransfer{}, d, token, e
	}
	v, e := workspaceBoundTransfer(r.Context(), tx, "IN", d.ID, id, binding)
	if e == nil {
		e = tx.Commit()
	}
	return v, d, token, e
}
func (s *Server) workspaceSaveChunkReply(ctx context.Context, tx *sql.Tx, v workspaceTransfer, reply *WorkspaceReply) error {
	body, e := workspaceReplyBody(*reply)
	if e != nil {
		return workspaceProtocolFailure("sync reply exceeds supported transfer; reduce batch", 413)
	}
	hash, n, e := workspaceReaderHash(body.reader())
	if e != nil {
		return e
	}
	v.ReplyHash = hash
	v.ReplyBytes = n
	reader := body.reader()
	buffer := make([]byte, workspaceChunkBytes)
	for offset := int64(0); offset < n; {
		count, e := io.ReadFull(reader, buffer[:min(int64(len(buffer)), n-offset)])
		if e != nil {
			return e
		}
		if e = s.workspacePutChunk(ctx, tx, v, "reply", offset, buffer[:count]); e != nil {
			return e
		}
		offset += int64(count)
	}
	result, e := tx.ExecContext(ctx, "UPDATE workspace_transfers SET state='DONE',reply_sha256=?,reply_bytes=?,problem='',updated_at=? WHERE role='IN' AND owner=? AND id=? AND state='UPLOADED'", hash, n, now(), v.Owner, v.ID)
	if e != nil {
		return e
	}
	changed, e := result.RowsAffected()
	if e != nil || changed != 1 {
		return workspaceProtocolFailure("sync transfer changed before commit", 409)
	}
	return nil
}
func (s *Server) workspaceChunkFinishHTTP(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID string `json:"id"`
	}
	if !decodeLimit(w, r, &in, 4096) {
		return
	}
	// Hold the same bounded worker slot while hashing and decoding a large
	// request. Repeated finish requests cannot allocate concurrent full records.
	if !s.workspaceMu.lockContext(r.Context()) {
		workspaceWriteFailure(w, workspaceProtocolFailure("sync cancelled; local changes retained", 503))
		return
	}
	defer s.workspaceMu.Unlock()
	v, d, token, e := s.workspaceAuthorizedTransfer(r, in.ID)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	if oneOf(v.State, "DONE", "ACKED") {
		jsonOK(w, 200, v)
		return
	}
	if v.State != "UPLOADED" || v.Received != v.RequestBytes {
		workspaceWriteFailure(w, workspaceProtocolFailure("complete sync fragments required", 409))
		return
	}
	hash, n, e := workspaceReaderHash(s.workspaceChunkReader(r.Context(), s.Store.DB, v, "request"))
	if e != nil || n != v.RequestBytes || hash != v.RequestHash {
		_, _ = s.Store.DB.ExecContext(r.Context(), "UPDATE workspace_transfers SET state='REJECTED',problem='REQUEST_HASH',updated_at=? WHERE role='IN' AND owner=? AND id=? AND state='UPLOADED'", now(), d.ID, v.ID)
		workspaceWriteFailure(w, workspaceProtocolFailure("complete sync request hash mismatch; no remote edits applied", 422))
		return
	}
	var request WorkspaceExchange
	decoder := json.NewDecoder(io.LimitReader(s.workspaceChunkReader(r.Context(), s.Store.DB, v, "request"), workspaceChunkRequestBytes+1))
	decoder.UseNumber()
	if decoder.Decode(&request) != nil || decoder.Decode(new(any)) != io.EOF {
		workspaceWriteFailure(w, workspaceProtocolFailure("invalid complete sync envelope; local data retained", 422))
		return
	}
	_, e = s.workspaceExchange(r.Context(), d, token, request, workspaceExchangeOptions{
		MaxBytes: workspaceChunkRecordBytes,
		Locked:   true,
		Before: func() (bool, error) {
			current, e := workspaceReadTransfer(r.Context(), s.Store.DB, "IN", d.ID, v.ID)
			if e != nil {
				return false, e
			}
			if current.Context != v.Context || current.RequestHash != v.RequestHash {
				return false, workspaceProtocolFailure("sync transfer scope changed", 409)
			}
			if oneOf(current.State, "DONE", "ACKED") {
				return true, nil
			}
			if current.State != "UPLOADED" {
				return false, workspaceProtocolFailure("sync transfer is not ready", 409)
			}
			return false, nil
		},
		Save: func(tx *sql.Tx, reply *WorkspaceReply) error {
			return s.workspaceSaveChunkReply(r.Context(), tx, v, reply)
		},
	})
	if e != nil {
		var p *workspaceProtocolError
		if errors.As(e, &p) && oneOfInt(p.status, 400, 413, 422) {
			_, _ = s.Store.DB.ExecContext(r.Context(), "UPDATE workspace_transfers SET state='REJECTED',problem='REQUEST_INVALID',updated_at=? WHERE role='IN' AND owner=? AND id=? AND state='UPLOADED'", now(), d.ID, v.ID)
		}
		workspaceWriteFailure(w, e)
		return
	}
	current, _, _, e := s.workspaceAuthorizedTransfer(r, v.ID)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	jsonOK(w, 200, current)
}
func oneOfInt(n int, values ...int) bool {
	for _, v := range values {
		if n == v {
			return true
		}
	}
	return false
}
