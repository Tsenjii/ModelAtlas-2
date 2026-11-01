package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
)

const workspaceProtocol = 1
const workspacePageBytes = 32 << 20

type WorkspaceRecord struct {
	Kind     string         `json:"kind"`
	ID       string         `json:"id"`
	Revision int64          `json:"revision"`
	Base     int64          `json:"base_revision"`
	Data     map[string]any `json:"data"`
	Deleted  bool           `json:"deleted"`
	Device   string         `json:"origin_device"`
	Seq      int64          `json:"seq"`
}

func (v *WorkspaceRecord) UnmarshalJSON(raw []byte) error {
	type record WorkspaceRecord
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	return d.Decode((*record)(v))
}

type WorkspaceSecret struct {
	Key    string      `json:"key"`
	Fields []AuthField `json:"fields,omitempty"`
}
type WorkspaceExchange struct {
	Protocol int                        `json:"protocol"`
	Cursor   int64                      `json:"cursor"`
	Changes  []WorkspaceRecord          `json:"changes"`
	Secrets  map[string]WorkspaceSecret `json:"secrets,omitempty"`
	Fetch    []WorkspaceRecord          `json:"fetch,omitempty"`
}
type WorkspaceReply struct {
	Protocol  int                        `json:"protocol"`
	Workspace string                     `json:"workspace_id"`
	Cursor    int64                      `json:"cursor"`
	More      bool                       `json:"has_more"`
	Records   []WorkspaceRecord          `json:"records"`
	Accepted  []WorkspaceRecord          `json:"accepted"`
	Conflicts []WorkspaceRecord          `json:"conflicts"`
	Secrets   map[string]WorkspaceSecret `json:"secrets,omitempty"`
}
type workspaceDevice struct {
	ID           string
	ShareSecrets bool
}
type workspaceDeviceContext struct{}

func workspaceRandom() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func workspaceHash(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }
func workspaceJSON(v any) string    { b, _ := json.Marshal(v); return string(b) }
func workspaceText(v string, limit int) bool {
	if len(v) > limit {
		return false
	}
	for _, c := range v {
		if c < 32 {
			return false
		}
	}
	return true
}
func workspaceAllowed(kind string) bool {
	if workspaceAndroidKind(kind) {
		return true
	}
	for _, k := range workspaceTables {
		if k == kind {
			return true
		}
	}
	return false
}
func workspaceImmutable(kind string) bool {
	if workspaceAndroidKind(kind) {
		return oneOf(strings.TrimPrefix(kind, "android:"), "samples", "evidence", "price_snapshots", "quota_snapshots", "snapshot_facts", "external_observations", "provider_perf_facts", "provider_account_usage_facts", "provider_notices", "provider_events", "price_events", "quota_events")
	}
	return oneOf(kind, "credential_origins", "endpoint_bindings", "messages", "telemetry", "observations", "external_models", "newapi_snapshots", "research_evidence", "research_evidence_details", "research_reflections", "connector_test_history", "recipe_versions", "knowledge_versions")
}
func workspaceRecordKey(v WorkspaceRecord) string { return v.Kind + "/" + v.ID }

func (s *Server) workspaceIdentity(ctx context.Context) (device, workspace string, e error) {
	device, workspace = newID(), newID()
	_, e = s.Store.DB.ExecContext(ctx, "INSERT OR IGNORE INTO workspace_identity VALUES('local',?,?)", device, workspace)
	if e != nil {
		return
	}
	e = s.Store.DB.QueryRowContext(ctx, "SELECT device_id,workspace_id FROM workspace_identity WHERE id='local'").Scan(&device, &workspace)
	return
}
func (s *Server) workspaceRoutes(api *http.ServeMux) {
	api.HandleFunc("GET /workspace", s.workspaceStatus)
	api.HandleFunc("POST /workspace/control", s.workspaceControl)
	api.HandleFunc("POST /workspace/transfer/discard", s.workspaceDiscardTransfer)
	api.HandleFunc("GET /workspace/conflict", s.workspaceConflictDetail)
	api.HandleFunc("POST /workspace/pair", s.workspacePair)
	api.HandleFunc("POST /workspace/revoke", s.workspaceRevoke)
	api.HandleFunc("POST /workspace/connect", s.workspaceConnect)
	api.HandleFunc("POST /workspace/sync", s.workspaceSyncHTTP)
	api.HandleFunc("POST /workspace/disconnect", s.workspaceDisconnect)
	api.HandleFunc("POST /workspace/resolve", s.workspaceResolve)
}
func workspaceRequest(w http.ResponseWriter, r *http.Request) bool {
	security(w)
	if r.Method != "POST" || r.Header.Get("Sec-Fetch-Site") == "cross-site" || !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		http.Error(w, "JSON request required", 403)
		return false
	}
	if o := r.Header.Get("Origin"); o != "" {
		if o != "https://"+r.Host && o != "http://"+r.Host {
			http.Error(w, "origin mismatch", 403)
			return false
		}
	}
	return true
}
func (s *Server) workspaceDeviceAuth(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !workspaceRequest(w, r) {
			return
		}
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if len(token) > 128 || !strings.HasPrefix(token, "matlas_device_") {
			http.Error(w, "device authorization required", 401)
			return
		}
		var d workspaceDevice
		var share int
		if e := s.Store.DB.QueryRowContext(r.Context(), "SELECT id,share_secrets FROM workspace_devices WHERE token_hash=? AND revoked_at IS NULL", workspaceHash(token)).Scan(&d.ID, &share); e != nil {
			http.Error(w, "device authorization required", 401)
			return
		}
		d.ShareSecrets = share == 1
		h.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), workspaceDeviceContext{}, d)))
	})
}
func (s *Server) workspacePair(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ShareSecrets bool `json:"share_secrets"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	var linked int
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_peer").Scan(&linked)
	if linked > 0 {
		http.Error(w, "create pairings on the workspace center", 409)
		return
	}
	code := workspaceRandom()
	expires := now() + 5*60*1000
	if _, e := s.Store.DB.ExecContext(r.Context(), "INSERT INTO workspace_pairings VALUES(?,?,?,NULL)", workspaceHash(code), boolInt(v.ShareSecrets), expires); e != nil {
		http.Error(w, "pairing unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"code": code, "expires_at": expires, "share_secrets": v.ShareSecrets, "protocol": workspaceProtocol})
}
func (s *Server) workspaceClaim(w http.ResponseWriter, r *http.Request) {
	if !workspaceRequest(w, r) {
		return
	}
	var v struct {
		Code     string `json:"code"`
		Label    string `json:"label"`
		Platform string `json:"platform"`
	}
	if !decodeLimit(w, r, &v, 4096) {
		return
	}
	if len(v.Code) != 43 || v.Label == "" || !workspaceText(v.Label, 120) || !oneOf(v.Platform, "macOS", "Android", "VPS") {
		http.Error(w, "invalid pairing", 400)
		return
	}
	s.workspaceMu.Lock()
	defer s.workspaceMu.Unlock()
	_, ws, e := s.workspaceIdentity(r.Context())
	if e != nil {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	defer tx.Rollback()
	var share int
	if e = tx.QueryRowContext(r.Context(), "SELECT share_secrets FROM workspace_pairings WHERE code_hash=? AND expires_at>? AND claimed_at IS NULL", workspaceHash(v.Code), now()).Scan(&share); e != nil {
		http.Error(w, "pairing expired or already used", 403)
		return
	}
	id, token := newID(), "matlas_device_"+workspaceRandom()
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO workspace_devices(id,label,platform,token_hash,share_secrets,created_at) VALUES(?,?,?,?,?,?)", id, v.Label, v.Platform, workspaceHash(token), share, now()); e != nil {
		http.Error(w, "pairing unavailable", 503)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE workspace_pairings SET claimed_at=? WHERE code_hash=?", now(), workspaceHash(v.Code)); e != nil {
		http.Error(w, "pairing unavailable", 503)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "pairing unavailable", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"device_id": id, "workspace_id": ws, "token": token, "share_secrets": share == 1, "protocol": workspaceProtocol})
}
func (s *Server) workspaceRevoke(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID      string `json:"device_id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !validID(v.ID) {
		http.Error(w, "explicit device required", 400)
		return
	}
	s.workspaceMu.Lock()
	defer s.workspaceMu.Unlock()
	if _, e := s.Store.DB.ExecContext(r.Context(), "UPDATE workspace_devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL", now(), v.ID); e != nil {
		http.Error(w, "revoke failed", 503)
		return
	}
	jsonOK(w, 200, map[string]any{"revoked": true})
}
func (s *Server) workspaceStatus(w http.ResponseWriter, r *http.Request) {
	_, ws, e := s.workspaceIdentity(r.Context())
	if e != nil {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	result := map[string]any{"protocol": workspaceProtocol, "workspace_id": ws, "devices": []any{}, "conflicts": []any{}}
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,label,platform,share_secrets,created_at,last_seen,revoked_at FROM workspace_devices ORDER BY created_at")
	if e != nil {
		http.Error(w, "workspace unavailable", 503)
		return
	}
	devices := []any{}
	for rows.Next() {
		var id, label, platform string
		var share, at int64
		var seen, revoked sql.NullInt64
		if rows.Scan(&id, &label, &platform, &share, &at, &seen, &revoked) == nil {
			devices = append(devices, map[string]any{"id": id, "label": label, "platform": platform, "share_secrets": share == 1, "created_at": at, "last_seen": nullableInt(seen), "revoked_at": nullableInt(revoked)})
		}
	}
	rows.Close()
	result["devices"] = devices
	var url, id, lastError string
	var enabled, share, cursor, resumable int
	var last sql.NullInt64
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT base_url,device_id,enabled,share_secrets,cursor,last_success,last_error,resumable FROM workspace_peer WHERE id=1").Scan(&url, &id, &enabled, &share, &cursor, &last, &lastError, &resumable) == nil {
		result["peer"] = map[string]any{"url": url, "device_id": id, "enabled": enabled == 1, "share_secrets": share == 1, "cursor": cursor, "last_success": nullableInt(last), "last_error": lastError, "resumable": resumable == 1}
	}
	var pending, conflicts int
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_dirty").Scan(&pending)
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_conflicts WHERE state='OPEN'").Scan(&conflicts)
	result["pending"] = pending
	result["conflict_count"] = conflicts
	rows, e = s.Store.DB.QueryContext(r.Context(), "SELECT id,kind,record_id,base_revision,created_at FROM workspace_conflicts WHERE state='OPEN' ORDER BY created_at LIMIT 100")
	list := []any{}
	if e == nil {
		for rows.Next() {
			var id, kind, key string
			var base, at int64
			if rows.Scan(&id, &kind, &key, &base, &at) == nil {
				list = append(list, map[string]any{"id": id, "kind": kind, "record_id": key, "base_revision": base, "created_at": at})
			}
		}
		rows.Close()
	}
	result["conflicts"] = list
	var outgoing, incoming int
	if e = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_transfers WHERE role='OUT' AND state='ACKED'").Scan(&outgoing); e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	if e = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM workspace_transfers WHERE role='IN' AND state='ACKED'").Scan(&incoming); e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	result["transport_receipts"] = map[string]int{"outgoing_acked": outgoing, "incoming_acked": incoming}
	if id != "" {
		if transfer, pending, err := s.workspacePendingOutgoing(r.Context(), id); err == nil && pending {
			var downloaded int64
			_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COALESCE(SUM(bytes),0) FROM workspace_transfer_parts WHERE role='OUT' AND owner=? AND transfer_id=? AND side='reply'", id, transfer.ID).Scan(&downloaded)
			result["transfer"] = map[string]any{"id": transfer.ID, "state": transfer.State, "request_bytes": transfer.RequestBytes, "progress_bytes": transfer.Progress, "reply_bytes": transfer.ReplyBytes, "downloaded_bytes": downloaded, "consumed": transfer.Consumed, "problem": transfer.Problem}
		}
	}
	jsonOK(w, 200, result)
}
func (s *Server) workspaceControl(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Enabled   bool  `json:"enabled"`
		Resumable *bool `json:"resumable"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	// Pause does not wait for the transfer mutex. The active request may finish;
	// each following fragment and the destination commit recheck this flag.
	var preference any
	if in.Resumable != nil {
		preference = boolInt(*in.Resumable)
	}
	result, e := s.Store.DB.ExecContext(r.Context(), "UPDATE workspace_peer SET enabled=?,next_at=0,resumable=COALESCE(?,resumable) WHERE id=1", boolInt(in.Enabled), preference)
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	if n, _ := result.RowsAffected(); n != 1 {
		http.Error(w, "connect a sync center first", 409)
		return
	}
	jsonOK(w, 200, map[string]any{"enabled": in.Enabled, "local_data_retained": true})
}
func nullableInt(v sql.NullInt64) any {
	if !v.Valid {
		return nil
	}
	return v.Int64
}

type workspaceQuerier interface {
	Query(string, ...any) (*sql.Rows, error)
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func (s *Server) workspaceLocalRecord(ctx context.Context, kind, id string) (WorkspaceRecord, *WorkspaceSecret, error) {
	return s.workspaceLocalRecordWith(ctx, s.Store.DB, kind, id)
}
func (s *Server) workspaceLocalRecordWith(ctx context.Context, q workspaceQuerier, kind, id string) (WorkspaceRecord, *WorkspaceSecret, error) {
	v := WorkspaceRecord{Kind: kind, ID: id, Data: map[string]any{}}
	if !workspaceAllowed(kind) || len(id) > 8192 {
		return v, nil, errors.New("invalid record")
	}
	if workspaceAndroidKind(kind) {
		var raw string
		var deleted int
		e := q.QueryRowContext(ctx, "SELECT revision,data_json,deleted,origin_device,seq FROM workspace_native_archive WHERE kind=? AND id=?", kind, id).Scan(&v.Revision, &raw, &deleted, &v.Device, &v.Seq)
		if e == sql.ErrNoRows {
			v.Deleted = true
			return v, nil, nil
		}
		if e != nil {
			return v, nil, e
		}
		decoder := json.NewDecoder(strings.NewReader(raw))
		decoder.UseNumber()
		e = decoder.Decode(&v.Data)
		v.Deleted = deleted == 1
		return v, nil, e
	}
	t, e := workspaceTableInfo(q, kind)
	if e != nil {
		return v, nil, e
	}
	var keys []any
	if json.Unmarshal([]byte(id), &keys) != nil || len(keys) != len(t.PK) {
		return v, nil, errors.New("invalid scope")
	}
	where := []string{}
	for _, k := range t.PK {
		where = append(where, k+"=?")
	}
	rows, e := q.QueryContext(ctx, "SELECT * FROM "+kind+" WHERE "+strings.Join(where, " AND "), keys...)
	if e != nil {
		return v, nil, e
	}
	values := make([]any, len(t.Columns))
	pointers := make([]any, len(values))
	for i := range values {
		pointers[i] = &values[i]
	}
	if !rows.Next() {
		e = rows.Err()
		rows.Close()
		v.Deleted = true
		return v, nil, e
	}
	if e = rows.Scan(pointers...); e != nil {
		rows.Close()
		return v, nil, e
	}
	rows.Close()
	var encrypted string
	for i, k := range t.Columns {
		value := values[i]
		if b, ok := value.([]byte); ok {
			value = string(b)
		}
		if k == "encrypted_key" {
			encrypted, _ = value.(string)
			continue
		}
		v.Data[k] = value
	}
	var secret *WorkspaceSecret
	if kind == "credentials" {
		var base, auth string
		e = q.QueryRowContext(ctx, "SELECT base_url,auth_mode FROM credential_origins WHERE provider_id=? AND account_id=? AND credential_id=?", keys...).Scan(&base, &auth)
		if e != nil {
			return v, nil, e
		}
		v.Data["_base_url"] = base
		v.Data["_auth_mode"] = auth
		var version int64
		if e = q.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", keys...).Scan(&version); e != nil {
			return v, nil, e
		}
		v.Data["_credential_version"] = version
		if encrypted != "" {
			key, e := s.decrypt(encrypted)
			if e != nil {
				return v, nil, e
			}
			var rawFields string
			e = q.QueryRowContext(ctx, "SELECT fields_json FROM credential_auth_fields WHERE provider_id=? AND account_id=? AND credential_id=?", keys...).Scan(&rawFields)
			if e != nil && e != sql.ErrNoRows {
				return v, nil, e
			}
			fields, e := s.decryptFields(rawFields)
			if e != nil {
				return v, nil, e
			}
			secret = &WorkspaceSecret{Key: key, Fields: fields}
			v.Data["_secret_digest"] = workspaceHash(workspaceJSON(secret))
		}
	}
	var previous string
	if q.QueryRowContext(ctx, "SELECT data_json FROM workspace_rows WHERE kind=? AND id=?", kind, id).Scan(&previous) == nil {
		var extra map[string]any
		decoder := json.NewDecoder(strings.NewReader(previous))
		decoder.UseNumber()
		if decoder.Decode(&extra) == nil {
			if native, exists := extra["_native_android"]; exists {
				v.Data["_native_android"] = native
			}
		}
	}
	return v, secret, nil
}
func workspaceReadRow(tx *sql.Tx, kind, id string) (WorkspaceRecord, error) {
	v := WorkspaceRecord{Kind: kind, ID: id, Data: map[string]any{}}
	var raw string
	var deleted int
	e := tx.QueryRow("SELECT revision,data_json,deleted,origin_device,seq FROM workspace_rows WHERE kind=? AND id=?", kind, id).Scan(&v.Revision, &raw, &deleted, &v.Device, &v.Seq)
	if e == sql.ErrNoRows {
		return v, nil
	}
	if e != nil {
		return v, e
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	e = decoder.Decode(&v.Data)
	v.Deleted = deleted == 1
	return v, e
}
func workspaceAppend(tx *sql.Tx, v WorkspaceRecord) (WorkspaceRecord, error) {
	raw, e := workspaceDataJSON(v.Data, workspaceChunkRecordBytes)
	if e != nil {
		return v, e
	}
	row, e := tx.Exec("INSERT INTO workspace_changes(kind,id,revision,data_json,deleted,origin_device,created_at) VALUES(?,?,?,?,?,?,?)", v.Kind, v.ID, v.Revision, raw, boolInt(v.Deleted), v.Device, now())
	if e != nil {
		return v, e
	}
	v.Seq, e = row.LastInsertId()
	if e != nil {
		return v, e
	}
	_, e = tx.Exec("INSERT INTO workspace_rows VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET revision=excluded.revision,data_json=excluded.data_json,deleted=excluded.deleted,origin_device=excluded.origin_device,seq=excluded.seq", v.Kind, v.ID, v.Revision, raw, boolInt(v.Deleted), v.Device, v.Seq)
	return v, e
}
func (s *Server) workspaceDirtyRecords(ctx context.Context, limit int) ([]WorkspaceRecord, map[string]WorkspaceSecret, error) {
	return s.workspaceDirtyRecordsLimit(ctx, limit, workspacePageBytes)
}
func (s *Server) workspaceDirtyRecordsLimit(ctx context.Context, limit, pageBytes int) ([]WorkspaceRecord, map[string]WorkspaceSecret, error) {
	// Dependency order is stable even after offline edits in arbitrary order.
	order := "CASE kind "
	for i, k := range workspaceTables {
		order += fmt.Sprintf("WHEN '%s' THEN %d ", k, i)
	}
	order += "END"
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT kind,id FROM workspace_dirty WHERE NOT EXISTS(SELECT 1 FROM workspace_conflicts c WHERE c.kind=workspace_dirty.kind AND c.record_id=workspace_dirty.id AND c.state='OPEN') ORDER BY "+order+",id LIMIT ?", limit)
	if e != nil {
		return nil, nil, e
	}
	pairs := [][2]string{}
	for rows.Next() {
		var k, id string
		if e = rows.Scan(&k, &id); e != nil {
			rows.Close()
			return nil, nil, e
		}
		pairs = append(pairs, [2]string{k, id})
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return nil, nil, e
	}
	records := []WorkspaceRecord{}
	secrets := map[string]WorkspaceSecret{}
	size := 0
	for _, p := range pairs {
		v, secret, e := s.workspaceLocalRecord(ctx, p[0], p[1])
		if e != nil {
			return nil, nil, e
		}
		var raw string
		var deleted int
		var rev int64
		e = s.Store.DB.QueryRowContext(ctx, "SELECT revision,data_json,deleted FROM workspace_rows WHERE kind=? AND id=?", v.Kind, v.ID).Scan(&rev, &raw, &deleted)
		if e != nil && e != sql.ErrNoRows {
			return nil, nil, e
		}
		payload, payloadError := workspaceDataJSON(v.Data, pageBytes)
		if payloadError != nil {
			return nil, nil, errWorkspacePageTooLarge
		}
		if (e == sql.ErrNoRows && v.Deleted) || (e == nil && raw == payload && deleted == boolInt(v.Deleted)) {
			_, e = s.Store.DB.ExecContext(ctx, "DELETE FROM workspace_dirty WHERE kind=? AND id=?", v.Kind, v.ID)
			if e != nil {
				return nil, nil, e
			}
			continue
		}
		n := len(payload) + len(v.ID) + 1024
		if secret != nil {
			n += len(workspaceJSON(secret))
		}
		if n > pageBytes {
			return nil, nil, errWorkspacePageTooLarge
		}
		if len(records) > 0 && size+n > pageBytes {
			break
		}
		size += n
		v.Base = rev
		records = append(records, v)
		if secret != nil {
			secrets[workspaceRecordKey(v)] = *secret
		}
	}
	return records, secrets, nil
}
func (s *Server) workspacePublishLocal(ctx context.Context) error {
	return s.workspacePublishLocalLimit(ctx, workspacePageBytes)
}
func (s *Server) workspacePublishLocalLimit(ctx context.Context, limit int) error {
	device, _, e := s.workspaceIdentity(ctx)
	if e != nil {
		return e
	}
	// One bounded batch per exchange. More queued data is explicitly advertised.
	records, _, e := s.workspaceDirtyRecordsLimit(ctx, 100, limit)
	if e != nil {
		return e
	}
	known, e := s.knownSecrets(ctx)
	if e != nil {
		return e
	}
	for _, v := range records {
		if e = workspaceSafeMetadataLimit(v, known, limit); e != nil {
			return e
		}
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	for _, v := range records {
		v.Revision = v.Base + 1
		v.Device = device
		if _, e = workspaceAppend(tx, v); e != nil {
			return e
		}
		// Leave a concurrent edit queued: compare the live row on the next pass.
	}
	return tx.Commit()
}
func (s *Server) workspaceApply(tx *sql.Tx, v WorkspaceRecord, secret *WorkspaceSecret) error {
	return s.workspaceApplyLimit(tx, v, secret, workspacePageBytes)
}
func (s *Server) workspaceApplyLimit(tx *sql.Tx, v WorkspaceRecord, secret *WorkspaceSecret, recordLimit int) error {
	payload, payloadError := workspaceDataJSON(v.Data, recordLimit)
	if !workspaceAllowed(v.Kind) || len(v.ID) > 8192 {
		return errors.New("unsupported data kind")
	}
	if workspaceAndroidKind(v.Kind) {
		if payloadError != nil || v.Data == nil {
			return errors.New("invalid native observation")
		}
		if v.Deleted && workspaceImmutable(v.Kind) {
			return errors.New("history is append-only")
		}
		_, e := tx.Exec("INSERT INTO workspace_native_archive VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET data_json=excluded.data_json,deleted=excluded.deleted,origin_device=excluded.origin_device,revision=excluded.revision,seq=excluded.seq", v.Kind, v.ID, payload, boolInt(v.Deleted), v.Device, v.Revision, v.Seq)
		return e
	}
	t, e := workspaceTableInfo(tx, v.Kind)
	if e != nil {
		return e
	}
	var keys []any
	if json.Unmarshal([]byte(v.ID), &keys) != nil || len(keys) != len(t.PK) {
		return errors.New("invalid record scope")
	}
	where := []string{}
	for _, k := range t.PK {
		where = append(where, k+"=?")
	}
	if v.Deleted {
		if workspaceImmutable(v.Kind) {
			return errors.New("history is append-only")
		}
		_, e = tx.Exec("DELETE FROM "+v.Kind+" WHERE "+strings.Join(where, " AND "), keys...)
		return e
	}
	if v.Data == nil || payloadError != nil {
		return errors.New("invalid record")
	}
	allowed := map[string]bool{"_base_url": true, "_auth_mode": true, "_secret_digest": true, "_credential_version": true, "_native_android": true}
	for _, col := range t.Columns {
		if col != "encrypted_key" {
			allowed[col] = true
		}
	}
	for col := range v.Data {
		if !allowed[col] {
			return errors.New("unsupported record field")
		}
	}
	for i, k := range t.PK {
		if workspaceJSON(v.Data[k]) != workspaceJSON(keys[i]) {
			return errors.New("scope mismatch")
		}
	}
	if v.Kind == "providers" {
		if !validateHTTPS(str(v.Data["base_url"])) || str(v.Data["name"]) == "" || !oneOf(str(v.Data["auth_mode"]), "NONE", "BEARER") {
			return errors.New("invalid provider")
		}
	}
	if v.Kind == "endpoint_profiles" || v.Kind == "endpoint_bindings" {
		if !validateHTTPS(str(v.Data["url"])) {
			return errors.New("invalid endpoint")
		}
	}
	if v.Kind == "credential_origins" || v.Kind == "endpoint_bindings" {
		// These rows bind historical requests and encrypted material to their
		// original destination. Editing a profile must never rewrite that binding.
		previous, _, err := s.workspaceLocalRecordWith(context.Background(), tx, v.Kind, v.ID)
		if err != nil {
			return err
		}
		if !previous.Deleted && !workspaceDataEqual(previous.Data, v.Data) {
			return errors.New("immutable destination binding conflict")
		}
	}
	if v.Kind == "provider_config" {
		var cfg ProviderConfig
		if json.Unmarshal([]byte(str(v.Data["config_json"])), &cfg) != nil || !safeConfig(cfg) {
			return errors.New("unsafe provider configuration")
		}
	}
	data := map[string]any{}
	for k, value := range v.Data {
		data[k] = value
	}
	if v.Kind == "credentials" {
		if !oneOf(str(data["role"]), "INFERENCE", "ACCOUNT_PAT") || !validateHTTPS(str(data["_base_url"])) {
			return errors.New("invalid credential")
		}
		var existing string
		lookup := tx.QueryRow("SELECT encrypted_key FROM credentials WHERE "+strings.Join(where, " AND "), keys...).Scan(&existing)
		if lookup != nil && lookup != sql.ErrNoRows {
			return lookup
		}
		if secret != nil {
			if secret.Key == "" || len(secret.Key) > 8192 || !workspaceText(secret.Key, 8192) || !validAuthFields(secret.Fields) || str(data["_secret_digest"]) != workspaceHash(workspaceJSON(secret)) {
				return errors.New("invalid secret channel")
			}
			var oldBase string
			_ = tx.QueryRow("SELECT base_url FROM credential_origins WHERE provider_id=? AND account_id=? AND credential_id=?", keys...).Scan(&oldBase)
			if oldBase != "" && oldBase != str(data["_base_url"]) {
				return errors.New("credential origin change requires review")
			}
			data["encrypted_key"], e = s.encrypt(secret.Key)
			if e != nil {
				return e
			}
		} else if existing != "" {
			data["encrypted_key"] = existing
		} else {
			data["encrypted_key"] = ""
			var providerAuth string
			if e = tx.QueryRow("SELECT auth_mode FROM providers WHERE id=?", data["provider_id"]).Scan(&providerAuth); e != nil {
				return e
			}
			if str(data["_auth_mode"]) != "NONE" && !(str(data["_auth_mode"]) == "" && providerAuth == "NONE") {
				data["enabled"] = 0
			}
		}
	}
	// Incoming histories are observations, never resumable work on a second host.
	ownScheduledAck := false
	if oneOf(v.Kind, "research_runs", "research_run_options", "research_tasks") {
		run := str(data["run_id"])
		if v.Kind == "research_runs" {
			run = str(data["id"])
		}
		var owned int
		var localDevice string
		if e = tx.QueryRow("SELECT COUNT(*) FROM research_schedule_runs WHERE run_id=?", run).Scan(&owned); e != nil {
			return e
		}
		if owned > 0 {
			_ = tx.QueryRow("SELECT device_id FROM workspace_identity WHERE id='local'").Scan(&localDevice)
			ownScheduledAck = localDevice != "" && v.Device == localDevice
			if !ownScheduledAck {
				if e = suspendScheduledRun(context.Background(), tx, run, "SYNC_CHANGED", now()); e != nil {
					return e
				}
			}
		}
	}
	if !ownScheduledAck && v.Kind == "research_runs" && oneOf(str(data["status"]), "RUNNING", "QUEUED") {
		data["status"] = "PAUSED"
	}
	if !ownScheduledAck && v.Kind == "research_tasks" && oneOf(str(data["status"]), "RUNNING", "PENDING", "QUEUED") {
		data["status"] = "INTERRUPTED"
	}
	if v.Kind == "telemetry" && str(data["outcome"]) == "RUNNING" {
		data["outcome"] = "INTERRUPTED"
	}
	columns, values, marks, updates := []string{}, []any{}, []string{}, []string{}
	pk := map[string]bool{}
	for _, k := range t.PK {
		pk[k] = true
	}
	for _, k := range t.Columns {
		value, exists := data[k]
		if !exists {
			continue
		}
		if _, bad := value.(map[string]any); bad {
			return errors.New("invalid column value")
		}
		if _, bad := value.([]any); bad {
			return errors.New("invalid column value")
		}
		columns = append(columns, k)
		values = append(values, value)
		marks = append(marks, "?")
		if !pk[k] {
			updates = append(updates, k+"=excluded."+k)
		}
	}
	conflict := " DO NOTHING"
	if len(updates) > 0 {
		conflict = " DO UPDATE SET " + strings.Join(updates, ",")
	}
	if _, e = tx.Exec("INSERT INTO "+v.Kind+"("+strings.Join(columns, ",")+") VALUES("+strings.Join(marks, ",")+") ON CONFLICT("+strings.Join(t.PK, ",")+")"+conflict, values...); e != nil {
		return errors.New("record validation or dependency failed")
	}
	if v.Kind == "credentials" {
		var previousBase, previousMode string
		originError := tx.QueryRow("SELECT base_url,auth_mode FROM credential_origins WHERE provider_id=? AND account_id=? AND credential_id=?", keys...).Scan(&previousBase, &previousMode)
		if originError == nil && (previousBase != str(data["_base_url"]) || previousMode != str(data["_auth_mode"])) {
			return errors.New("credential origin change requires review")
		}
		if originError == sql.ErrNoRows {
			e = insertCredentialOrigin(context.Background(), tx, str(data["provider_id"]), str(data["account_id"]), str(data["id"]), str(data["_base_url"]), str(data["_auth_mode"]))
		} else {
			e = originError
		}
		if e != nil {
			return e
		}
		if secret != nil {
			var version int64
			_ = json.Unmarshal([]byte(workspaceJSON(data["_credential_version"])), &version)
			if version < 0 {
				return errors.New("invalid credential material version")
			}
			if version > 0 {
				var old string
				versionError := tx.QueryRow("SELECT encrypted_key FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=? AND version=?", data["provider_id"], data["account_id"], data["id"], version).Scan(&old)
				if versionError == nil {
					plain, e := s.decrypt(old)
					if e != nil || plain != secret.Key {
						return errors.New("credential material version conflict")
					}
				} else if versionError == sql.ErrNoRows {
					if _, e = tx.Exec("INSERT INTO credential_secret_versions VALUES(?,?,?,?,?,?)", data["provider_id"], data["account_id"], data["id"], version, data["encrypted_key"], now()); e != nil {
						return e
					}
				} else {
					return versionError
				}
			}
			fields, e := s.encryptFields(secret.Fields)
			if e != nil {
				return e
			}
			if _, e = tx.Exec("INSERT INTO credential_auth_fields VALUES(?,?,?,?) ON CONFLICT(provider_id,account_id,credential_id) DO UPDATE SET fields_json=excluded.fields_json", data["provider_id"], data["account_id"], data["id"], fields); e != nil {
				return e
			}
		}
	}
	return nil
}
func workspaceConflict(tx *sql.Tx, device string, local, remote WorkspaceRecord) error {
	localJSON, e := workspaceRecordJSON(local)
	if e != nil {
		return e
	}
	remoteJSON, e := workspaceRecordJSON(remote)
	if e != nil {
		return e
	}
	_, e = tx.Exec("INSERT OR IGNORE INTO workspace_conflicts(id,device_id,kind,record_id,base_revision,local_json,remote_json,created_at) VALUES(?,?,?,?,?,?,?,?)", newID(), device, local.Kind, local.ID, local.Base, localJSON, remoteJSON, now())
	return e
}
func (s *Server) workspaceExchangeHTTP(w http.ResponseWriter, r *http.Request) {
	d, ok := r.Context().Value(workspaceDeviceContext{}).(workspaceDevice)
	if !ok {
		http.Error(w, "device required", 401)
		return
	}
	var v WorkspaceExchange
	if !decodeLimit(w, r, &v, workspacePageBytes+(1<<20)) {
		return
	}
	reply, e := s.workspaceExchange(r.Context(), d, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), v, workspaceExchangeOptions{})
	if e != nil {
		workspaceWriteFailure(w, e)
		return
	}
	jsonOK(w, 200, reply)
}
func (s *Server) workspaceExchange(ctx context.Context, d workspaceDevice, token string, v WorkspaceExchange, options workspaceExchangeOptions) (WorkspaceReply, error) {
	limit := options.MaxBytes
	if limit == 0 {
		limit = workspacePageBytes
	}
	if limit != workspacePageBytes && limit != workspaceChunkRecordBytes {
		return WorkspaceReply{}, workspaceProtocolFailure("unsupported sync limit", 400)
	}
	if v.Protocol != workspaceProtocol || v.Cursor < 0 || len(v.Changes) > 100 || len(v.Fetch) > 100 || (!d.ShareSecrets && len(v.Secrets) > 0) {
		return WorkspaceReply{}, workspaceProtocolFailure("unsupported sync envelope", 400)
	}
	known, e := s.knownSecrets(ctx)
	if e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("vault unavailable", 503)
	}
	known = append(known, token)
	for _, secret := range v.Secrets {
		known = append(known, secret.Key)
		for _, field := range secret.Fields {
			known = append(known, field.Secret)
		}
	}
	for _, record := range v.Changes {
		if workspaceSafeMetadataLimit(record, known, limit) != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("secret or unsafe metadata rejected", 422)
		}
	}
	if !options.Locked {
		if !s.workspaceMu.lockContext(ctx) {
			return WorkspaceReply{}, workspaceProtocolFailure("sync cancelled; local changes retained", 503)
		}
		defer s.workspaceMu.Unlock()
	}
	if e := workspaceDeviceCurrent(ctx, s.Store.DB, d, token); e != nil {
		return WorkspaceReply{}, e
	}
	if options.Before != nil {
		done, e := options.Before()
		if e != nil {
			return WorkspaceReply{}, e
		}
		if done {
			return WorkspaceReply{}, nil
		}
	}
	if e := s.workspacePublishLocalLimit(ctx, limit); e != nil {
		if errors.Is(e, errWorkspacePageTooLarge) {
			return WorkspaceReply{}, workspaceProtocolFailure("large local record requires chunked synchronization; local changes retained", 413)
		}
		return WorkspaceReply{}, workspaceProtocolFailure("local changes unavailable", 503)
	}
	_, ws, e := s.workspaceIdentity(ctx)
	if e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("workspace unavailable", 503)
	}
	reply := WorkspaceReply{Protocol: workspaceProtocol, Workspace: ws, Cursor: v.Cursor, Records: []WorkspaceRecord{}, Accepted: []WorkspaceRecord{}, Conflicts: []WorkspaceRecord{}, Secrets: map[string]WorkspaceSecret{}}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("workspace unavailable", 503)
	}
	defer tx.Rollback()
	if _, e = tx.Exec("UPDATE workspace_importing SET active=1 WHERE id=1"); e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("workspace unavailable", 503)
	}
	size := 0
	reserve := func(record WorkspaceRecord) error {
		body, e := workspaceRecordBody(record, int64(limit))
		if e != nil || int64(size)+body.size > int64(limit+(1<<20)) {
			return workspaceProtocolFailure("sync result exceeds supported page; local changes retained", 413)
		}
		size += int(body.size)
		return nil
	}
	for _, change := range v.Changes {
		current, e := workspaceReadRow(tx, change.Kind, change.ID)
		if e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("workspace unavailable", 503)
		}
		if change.Kind == "credentials" && !d.ShareSecrets {
			if change.Data == nil {
				change.Data = map[string]any{}
			}
			if digest, exists := current.Data["_secret_digest"]; exists {
				change.Data["_secret_digest"] = digest
			} else {
				delete(change.Data, "_secret_digest")
			}
			if version, exists := current.Data["_credential_version"]; exists {
				change.Data["_credential_version"] = version
			} else {
				change.Data["_credential_version"] = int64(0)
			}
		}
		if current.Revision > 0 && current.Deleted == change.Deleted && workspaceDataEqual(current.Data, change.Data) {
			if e = reserve(current); e != nil {
				return WorkspaceReply{}, e
			}
			reply.Accepted = append(reply.Accepted, current)
			continue
		}
		if change.Base != current.Revision {
			if e = reserve(current); e != nil {
				return WorkspaceReply{}, e
			}
			if e = workspaceConflict(tx, d.ID, change, current); e != nil {
				return WorkspaceReply{}, workspaceProtocolFailure("conflict persistence failed", 503)
			}
			reply.Conflicts = append(reply.Conflicts, current)
			continue
		}
		if change.Kind == "credentials" {
			var incomingVersion, currentVersion int64
			_ = json.Unmarshal([]byte(workspaceJSON(change.Data["_credential_version"])), &incomingVersion)
			_ = json.Unmarshal([]byte(workspaceJSON(current.Data["_credential_version"])), &currentVersion)
			if current.Revision > 0 && str(change.Data["_secret_digest"]) != str(current.Data["_secret_digest"]) && incomingVersion <= currentVersion {
				incomingVersion = currentVersion + 1
			}
			if str(change.Data["_secret_digest"]) == str(current.Data["_secret_digest"]) && incomingVersion < currentVersion {
				incomingVersion = currentVersion
			}
			change.Data["_credential_version"] = incomingVersion
		}
		change.Revision = current.Revision + 1
		change.Device = d.ID
		var secret *WorkspaceSecret
		if value, exists := v.Secrets[workspaceRecordKey(change)]; exists {
			valueCopy := value
			secret = &valueCopy
		}
		if e = s.workspaceApplyLimit(tx, change, secret, limit); e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("record rejected; no changes applied", 422)
		}
		if change, e = workspaceAppend(tx, change); e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("sync commit failed", 503)
		}
		if e = reserve(change); e != nil {
			return WorkspaceReply{}, e
		}
		reply.Accepted = append(reply.Accepted, change)
		if _, e = tx.Exec("UPDATE workspace_conflicts SET state='LOCAL' WHERE device_id=? AND kind=? AND record_id=? AND state='OPEN'", d.ID, change.Kind, change.ID); e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("conflict save failed", 503)
		}
	}
	if _, e = tx.Exec("UPDATE workspace_importing SET active=0 WHERE id=1"); e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("sync commit failed", 503)
	}
	if _, e = tx.Exec("UPDATE workspace_devices SET last_seen=? WHERE id=?", now(), d.ID); e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("sync commit failed", 503)
	}
	rows, e := tx.Query("SELECT seq,kind,id,revision,length(CAST(data_json AS BLOB)),deleted,origin_device FROM workspace_changes WHERE seq>? ORDER BY seq LIMIT 100", v.Cursor)
	if e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
	}
	type eventHeader struct {
		record  WorkspaceRecord
		bytes   int
		deleted int
	}
	headers := []eventHeader{}
	for rows.Next() {
		var header eventHeader
		if e = rows.Scan(&header.record.Seq, &header.record.Kind, &header.record.ID, &header.record.Revision, &header.bytes, &header.deleted, &header.record.Device); e != nil {
			rows.Close()
			return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
		}
		headers = append(headers, header)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
	}
	acknowledged := map[string]int64{}
	for _, record := range reply.Accepted {
		acknowledged[workspaceRecordKey(record)] = record.Revision
	}
	for _, header := range headers {
		x := header.record
		x.Deleted = header.deleted == 1
		if acknowledged[workspaceRecordKey(x)] == x.Revision {
			reply.Cursor = x.Seq
			continue
		}
		if size+header.bytes > limit && (len(reply.Records) > 0 || len(reply.Accepted) > 0 || len(reply.Conflicts) > 0) {
			break
		}
		if header.bytes > limit {
			return WorkspaceReply{}, workspaceProtocolFailure("large retained record requires chunked synchronization", 413)
		}
		var raw string
		if e = tx.QueryRow("SELECT data_json FROM workspace_changes WHERE seq=?", x.Seq).Scan(&raw); e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
		}
		decoder := json.NewDecoder(strings.NewReader(raw))
		decoder.UseNumber()
		if decoder.Decode(&x.Data) != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
		}
		if e = reserve(x); e != nil {
			return WorkspaceReply{}, e
		}
		reply.Records = append(reply.Records, x)
		reply.Cursor = x.Seq
	}
	for _, want := range v.Fetch {
		if !workspaceAllowed(want.Kind) || len(want.ID) > 8192 {
			return WorkspaceReply{}, workspaceProtocolFailure("invalid requested record", 400)
		}
		x, e := workspaceReadRow(tx, want.Kind, want.ID)
		if e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("sync read failed", 503)
		}
		if x.Revision > 0 {
			if e = reserve(x); e != nil {
				return WorkspaceReply{}, e
			}
			reply.Records = append(reply.Records, x)
		}
		if _, e = tx.Exec("UPDATE workspace_conflicts SET state='REMOTE' WHERE device_id=? AND kind=? AND record_id=? AND state='OPEN'", d.ID, want.Kind, want.ID); e != nil {
			return WorkspaceReply{}, workspaceProtocolFailure("conflict save failed", 503)
		}
	}
	var remaining, dirty int
	_ = tx.QueryRow("SELECT COUNT(*) FROM workspace_changes WHERE seq>?", reply.Cursor).Scan(&remaining)
	_ = tx.QueryRow("SELECT COUNT(*) FROM workspace_dirty").Scan(&dirty)
	reply.More = remaining > 0 || dirty > 0
	if d.ShareSecrets {
		for _, x := range append(append([]WorkspaceRecord{}, reply.Records...), reply.Accepted...) {
			if x.Kind != "credentials" || x.Deleted {
				continue
			}
			live, secret, e := s.workspaceLocalRecordWith(ctx, tx, x.Kind, x.ID)
			if e == nil && secret != nil && live.Data["_secret_digest"] == x.Data["_secret_digest"] {
				reply.Secrets[workspaceRecordKey(x)] = *secret
			}
		}
	}
	if options.Save != nil {
		if e = options.Save(tx, &reply); e != nil {
			return WorkspaceReply{}, e
		}
	}
	if e = tx.Commit(); e != nil {
		return WorkspaceReply{}, workspaceProtocolFailure("sync commit failed", 503)
	}
	return reply, nil
}

func workspaceSafeMetadata(v WorkspaceRecord, secrets []string) error {
	return workspaceSafeMetadataLimit(v, secrets, workspacePageBytes)
}
func workspaceSafeMetadataLimit(v WorkspaceRecord, secrets []string, limit int) error {
	if !workspaceAllowed(v.Kind) || len(v.ID) > 8192 {
		return errors.New("unsupported record")
	}
	body, e := workspaceRecordBody(v, int64(limit))
	if e != nil {
		return errWorkspacePageTooLarge
	}
	if body.contains("matlas_device_") {
		return errors.New("transport credential in metadata")
	}
	if body.containsSecret(secrets) {
		return errors.New("secret in sync metadata")
	}
	return nil
}

// Rows are applied in foreign-key dependency order when a page contains an
// interleaving of edits from several devices.
func workspaceSort(rows []WorkspaceRecord) {
	order := map[string]int{}
	for i, k := range workspaceTables {
		order[k] = i
	}
	sort.SliceStable(rows, func(i, j int) bool {
		if rows[i].Kind == rows[j].Kind {
			return rows[i].Seq < rows[j].Seq
		}
		return order[rows[i].Kind] < order[rows[j].Kind]
	})
}
