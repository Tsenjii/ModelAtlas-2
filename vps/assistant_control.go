package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strings"
)

type ControlChange struct {
	Kind         string                     `json:"kind"`
	ProviderID   string                     `json:"provider_id,omitempty"`
	AccountID    string                     `json:"account_id,omitempty"`
	CredentialID string                     `json:"credential_id,omitempty"`
	Selection    Selection                  `json:"selection,omitempty"`
	Selections   []Selection                `json:"selections,omitempty"`
	Patch        map[string]json.RawMessage `json:"patch"`
}
type queryRows interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func resourceID(v ControlChange) string {
	switch v.Kind {
	case "MODEL_BATCH":
		keys := []string{}
		for _, selection := range v.Selections {
			keys = append(keys, scopeKey(selection))
		}
		sort.Strings(keys)
		return contentHash([]byte(strings.Join(keys, "\n")))
	case "PROVIDER":
		return v.ProviderID
	case "CREDENTIAL", "CREDENTIAL_ADD", "CREDENTIAL_ROTATE":
		return v.ProviderID + "/" + v.AccountID + "/" + v.CredentialID
	case "MODEL":
		return scopeKey(v.Selection)
	case "NOTES":
		if v.Selection.ModelID != "" {
			return scopeKey(v.Selection)
		}
		return v.ProviderID
	}
	return ""
}
func resourceSnapshot(ctx context.Context, q queryRows, v ControlChange) (map[string]any, error) {
	out := map[string]any{}
	switch v.Kind {
	case "MODEL_BATCH":
		if len(v.Selections) < 2 || len(v.Selections) > 100 {
			return nil, errors.New("select 2..100 exact model scopes")
		}
		seen := map[string]bool{}
		items := []any{}
		for _, selection := range v.Selections {
			if seen[scopeKey(selection)] {
				return nil, errors.New("duplicate model scope")
			}
			seen[scopeKey(selection)] = true
			value, e := resourceSnapshot(ctx, q, ControlChange{Kind: "MODEL", Selection: selection})
			if e != nil {
				return nil, e
			}
			items = append(items, map[string]any{"selection": selection, "value": value})
		}
		out["items"] = items
	case "PROVIDER":
		var name, base, description, config string
		if q.QueryRowContext(ctx, "SELECT p.name,p.base_url,p.description,COALESCE(c.config_json,'{}') FROM providers p LEFT JOIN provider_config c ON c.provider_id=p.id WHERE p.id=?", v.ProviderID).Scan(&name, &base, &description, &config) != nil {
			return nil, errors.New("provider missing")
		}
		var cfg ProviderConfig
		json.Unmarshal([]byte(config), &cfg)
		out = map[string]any{"name": name, "base_url": base, "description": description, "website": cfg.Website, "notes": cfg.Notes, "_config": config}
	case "CREDENTIAL", "CREDENTIAL_ADD", "CREDENTIAL_ROTATE":
		var base string
		if q.QueryRowContext(ctx, "SELECT p.base_url FROM providers p JOIN accounts a ON a.provider_id=p.id WHERE p.id=? AND a.id=?", v.ProviderID, v.AccountID).Scan(&base) != nil {
			return nil, errors.New("account missing")
		}
		var label, role, encrypted, group, note, fields string
		var enabled, priority int
		if q.QueryRowContext(ctx, "SELECT c.label,c.role,c.encrypted_key,c.enabled,COALESCE(x.group_id,''),COALESCE(x.note,''),COALESCE(x.priority,0) FROM credentials c LEFT JOIN credential_context x ON x.provider_id=c.provider_id AND x.account_id=c.account_id AND x.credential_id=c.id WHERE c.provider_id=? AND c.account_id=? AND c.id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&label, &role, &encrypted, &enabled, &group, &note, &priority) != nil {
			if v.Kind == "CREDENTIAL_ADD" {
				return map[string]any{"exists": false, "origin": base}, nil
			}
			return nil, errors.New("credential missing")
		}
		q.QueryRowContext(ctx, "SELECT fields_json FROM credential_auth_fields WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&fields)
		out = map[string]any{"label": label, "role": role, "enabled": enabled == 1, "group": group, "note": note, "priority": priority, "origin": base, "exists": true, "_key_version_hash": contentHash([]byte(encrypted)), "_fields": fields}
		var keyVersion int
		q.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&keyVersion)
		out["_secret_version"] = keyVersion
		if v.Kind == "CREDENTIAL_ROTATE" || v.Kind == "CREDENTIAL_ADD" {
			out["_cipher"] = encrypted
		}
	case "MODEL":
		var count int
		if !validScope(v.Selection) || q.QueryRowContext(ctx, "SELECT COUNT(*) FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.Selection.ProviderID, v.Selection.AccountID, v.Selection.CredentialID, v.Selection.ModelID, v.Selection.Group, v.Selection.Endpoint).Scan(&count) != nil || count != 1 {
			return nil, errors.New("membership missing")
		}
		var state string
		var favorite, watchlist bool
		e := q.QueryRowContext(ctx, "SELECT state,favorite,watchlist FROM membership_state WHERE scope_key=?", scopeKey(v.Selection)).Scan(&state, &favorite, &watchlist)
		if e == sql.ErrNoRows {
			state = "ACTIVE"
		} else if e != nil {
			return nil, e
		}
		out = map[string]any{"state": state, "favorite": favorite, "watchlist": watchlist}
		var revision int64
		q.QueryRowContext(ctx, "SELECT COALESCE(MAX(id),0) FROM membership_mutations WHERE scope_key=?", scopeKey(v.Selection)).Scan(&revision)
		out["_revision"] = revision
	case "NOTES":
		subject := resourceID(v)
		if subject == "" {
			return nil, errors.New("subject required")
		}
		var count int
		if noteKind(v) == "MODEL" {
			if !validScope(v.Selection) || q.QueryRowContext(ctx, "SELECT COUNT(*) FROM models WHERE provider_id=? AND id=?", v.Selection.ProviderID, v.Selection.ModelID).Scan(&count) != nil || count != 1 {
				return nil, errors.New("model subject missing")
			}
		} else if q.QueryRowContext(ctx, "SELECT COUNT(*) FROM providers WHERE id=?", v.ProviderID).Scan(&count) != nil || count != 1 {
			return nil, errors.New("provider subject missing")
		}
		var notes, tags string
		e := q.QueryRowContext(ctx, "SELECT notes,tags_json FROM entity_notes WHERE subject_type=? AND subject_id=?", noteKind(v), subject).Scan(&notes, &tags)
		if e != nil && e != sql.ErrNoRows {
			return nil, e
		}
		var list []string
		json.Unmarshal([]byte(tags), &list)
		if list == nil {
			list = []string{}
		}
		out = map[string]any{"notes": notes, "tags": list}
	default:
		return nil, errors.New("unsupported resource kind")
	}
	return out, nil
}
func noteKind(v ControlChange) string {
	if v.Selection.ModelID != "" {
		return "MODEL"
	}
	return "PROVIDER"
}
func snapshotHash(v map[string]any) string { raw, _ := json.Marshal(v); return contentHash(raw) }
func controlAfter(v ControlChange, before map[string]any) (map[string]any, error) {
	if v.Kind == "MODEL_BATCH" {
		items := []any{}
		for _, item := range list(before["items"]) {
			row := object(item)
			value, e := controlAfter(ControlChange{Kind: "MODEL", Patch: v.Patch}, object(row["value"]))
			if e != nil {
				return nil, e
			}
			items = append(items, map[string]any{"selection": row["selection"], "value": value})
		}
		return map[string]any{"items": items}, nil
	}
	if len(v.Patch) < 1 || len(v.Patch) > 8 {
		return nil, errors.New("bounded patch required")
	}
	out := map[string]any{}
	for k, x := range before {
		out[k] = x
	}
	for key, raw := range v.Patch {
		var value any
		decoder := json.NewDecoder(strings.NewReader(string(raw)))
		decoder.UseNumber()
		if decoder.Decode(&value) != nil {
			return nil, errors.New("invalid patch value")
		}
		allowed := false
		switch v.Kind {
		case "PROVIDER":
			allowed = oneOf(key, "name", "description", "website", "notes", "base_url")
			if text, ok := value.(string); !ok || safeText(strings.NewReplacer("\n", " ", "\r", " ", "\t", " ").Replace(text), 4096) != strings.NewReplacer("\n", " ", "\r", " ", "\t", " ").Replace(text) {
				return nil, errors.New("invalid provider text")
			}
			if key == "name" && (str(value) == "" || len(str(value)) > 120) || key == "description" && len(str(value)) > 1000 {
				return nil, errors.New("provider text too long")
			}
			if oneOf(key, "base_url", "website") && str(value) != "" && !validateHTTPS(str(value)) {
				return nil, errors.New("HTTPS URL required")
			}
			if key == "base_url" {
				if str(value) == "" {
					return nil, errors.New("base URL required")
				}
				old, _ := url.Parse(str(before[key]))
				next, _ := url.Parse(str(value))
				if !strings.EqualFold(old.Host, next.Host) {
					return nil, errors.New("new origin requires shared onboarding and a new credential binding")
				}
			}
		case "CREDENTIAL":
			allowed = oneOf(key, "label", "note", "priority", "enabled")
			switch key {
			case "label", "note":
				if text, ok := value.(string); !ok || safeText(text, 512) != text || key == "label" && len(text) > 120 {
					return nil, errors.New("invalid credential metadata")
				}
			case "enabled":
				if _, ok := value.(bool); !ok {
					return nil, errors.New("enabled must be boolean")
				}
			case "priority":
				n := quotaInteger(value)
				if n == nil || *n > 1000 {
					return nil, errors.New("priority must be 0..1000")
				}
			}
		case "MODEL":
			allowed = oneOf(key, "state", "favorite", "watchlist")
			if key == "state" {
				if !oneOf(str(value), "ACTIVE", "HIDDEN", "ARCHIVED") {
					return nil, errors.New("invalid lifecycle")
				}
			} else if _, ok := value.(bool); !ok {
				return nil, errors.New("flag must be boolean")
			}
		case "NOTES":
			allowed = oneOf(key, "notes", "tags")
			if key == "notes" {
				text, ok := value.(string)
				if !ok || len(text) > 4000 {
					return nil, errors.New("bounded notes required")
				}
			} else {
				tags, ok := value.([]any)
				if !ok {
					return nil, errors.New("tags must be an array of strings")
				}
				if len(tags) > 32 {
					return nil, errors.New("too many tags")
				}
				for _, t := range tags {
					if str(t) == "" || safeText(str(t), 80) != str(t) {
						return nil, errors.New("invalid tag")
					}
				}
			}
		}
		if !allowed {
			return nil, errors.New("patch field not allowlisted")
		}
		out[key] = value
	}
	return out, nil
}
func (s *Server) previewControlAction(w http.ResponseWriter, r *http.Request) {
	var v ControlChange
	if !decodeJSON(w, r, &v) {
		return
	}
	if v.Kind == "CREDENTIAL_ADD" {
		if v.CredentialID != "" {
			http.Error(w, "new credential ID is allocated by the app", 422)
			return
		}
		v.CredentialID = newID()
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	rawPatch, _ := json.Marshal(v.Patch)
	if rawSecretPattern.Match(rawPatch) {
		http.Error(w, "use safe secret references", 422)
		return
	}
	for _, secret := range secrets {
		var val any
		json.Unmarshal(rawPatch, &val)
		if jsonContainsSecret(val, secret) {
			http.Error(w, "secret in metadata denied", 422)
			return
		}
	}
	before, e := resourceSnapshot(r.Context(), s.Store.DB, v)
	if e != nil {
		http.Error(w, e.Error(), 422)
		return
	}
	for _, secret := range secrets {
		if jsonContainsSecret(before, secret) {
			http.Error(w, "secret in existing metadata; correct it through secure settings", 422)
			return
		}
	}
	var after map[string]any
	if oneOf(v.Kind, "CREDENTIAL_ADD", "CREDENTIAL_ROTATE") {
		after, e = s.secretChangeAfter(r.Context(), v, before)
	} else {
		after, e = controlAfter(v, before)
	}
	if e != nil {
		http.Error(w, e.Error(), 422)
		return
	}
	id := newID()
	b, _ := json.Marshal(before)
	a, _ := json.Marshal(after)
	resource, _ := json.Marshal(v)
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO action_ledger VALUES(?,'CONTROL_CHANGE',?,?,?,'PENDING',?)", id, resourceID(v), string(b), string(a), now()); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO assistant_action_details(action_id,kind,resource_json,before_hash) VALUES(?,?,?,?)", id, v.Kind, string(resource), snapshotHash(before)); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, map[string]any{"id": id, "state": "PENDING", "action": "CONTROL_CHANGE", "kind": v.Kind, "before": visibleSnapshot(before), "after": visibleSnapshot(after), "requires_confirmation": true})
}
func visibleSnapshot(data map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range data {
		if !strings.HasPrefix(k, "_") {
			if nested, ok := v.(map[string]any); ok {
				out[k] = visibleSnapshot(nested)
			} else if items, ok := v.([]any); ok {
				values := []any{}
				for _, item := range items {
					if nested, ok := item.(map[string]any); ok {
						values = append(values, visibleSnapshot(nested))
					} else {
						values = append(values, item)
					}
				}
				out[k] = values
			} else {
				out[k] = v
			}
		}
	}
	return out
}
func (s *Server) writeResource(ctx context.Context, tx *sql.Tx, v ControlChange, data map[string]any) error {
	switch v.Kind {
	case "MODEL_BATCH":
		for _, item := range list(data["items"]) {
			row := object(item)
			raw, _ := json.Marshal(row["selection"])
			var selection Selection
			if json.Unmarshal(raw, &selection) != nil {
				return errors.New("invalid stored scope")
			}
			if e := s.writeResource(ctx, tx, ControlChange{Kind: "MODEL", Selection: selection}, object(row["value"])); e != nil {
				return e
			}
		}
		return nil
	case "PROVIDER":
		var cfg ProviderConfig
		json.Unmarshal([]byte(str(data["_config"])), &cfg)
		cfg.Website = str(data["website"])
		cfg.Notes = str(data["notes"])
		raw, _ := json.Marshal(cfg)
		if _, e := tx.ExecContext(ctx, "UPDATE providers SET name=?,base_url=?,description=? WHERE id=?", str(data["name"]), str(data["base_url"]), str(data["description"]), v.ProviderID); e != nil {
			return e
		}
		_, e := tx.ExecContext(ctx, "INSERT INTO provider_config VALUES(?,?) ON CONFLICT(provider_id) DO UPDATE SET config_json=excluded.config_json", v.ProviderID, string(raw))
		return e
	case "CREDENTIAL":
		if data["enabled"] == true && data["_key_version_hash"] == contentHash(nil) {
			var mode string
			tx.QueryRowContext(ctx, "SELECT auth_mode FROM providers WHERE id=?", v.ProviderID).Scan(&mode)
			if mode != "NONE" && str(data["_fields"]) == "" {
				return errors.New("credential secret must be restored first")
			}
		}
		if _, e := tx.ExecContext(ctx, "UPDATE credentials SET label=?,enabled=? WHERE provider_id=? AND account_id=? AND id=?", str(data["label"]), data["enabled"], v.ProviderID, v.AccountID, v.CredentialID); e != nil {
			return e
		}
		_, e := tx.ExecContext(ctx, "INSERT INTO credential_context VALUES(?,?,?,?,?,?) ON CONFLICT(provider_id,account_id,credential_id) DO UPDATE SET note=excluded.note,priority=excluded.priority", v.ProviderID, v.AccountID, v.CredentialID, str(data["group"]), str(data["note"]), data["priority"])
		return e
	case "CREDENTIAL_ADD", "CREDENTIAL_ROTATE":
		return s.writeSecretChange(ctx, tx, v, data)
	case "MODEL":
		_, e := tx.ExecContext(ctx, "INSERT INTO membership_state VALUES(?,?,?,?) ON CONFLICT(scope_key) DO UPDATE SET state=excluded.state,favorite=excluded.favorite,watchlist=excluded.watchlist", scopeKey(v.Selection), str(data["state"]), data["favorite"], data["watchlist"])
		if e != nil {
			return e
		}
		return bumpMembership(tx, v.Selection)
	case "NOTES":
		tags, _ := json.Marshal(data["tags"])
		_, e := tx.ExecContext(ctx, "INSERT INTO entity_notes VALUES(?,?,?,?) ON CONFLICT(subject_type,subject_id) DO UPDATE SET notes=excluded.notes,tags_json=excluded.tags_json", noteKind(v), resourceID(v), str(data["notes"]), string(tags))
		return e
	}
	return errors.New("unsupported mutation")
}
func (s *Server) controlTransition(w http.ResponseWriter, r *http.Request, undo bool) {
	var v struct {
		ID      string `json:"id"`
		Confirm bool   `json:"confirm"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || !validID(v.ID) {
		http.Error(w, "explicit confirmation required", 422)
		return
	}
	var action string
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT action FROM action_ledger WHERE id=?", v.ID).Scan(&action) != nil {
		http.Error(w, "action missing", 404)
		return
	}
	if oneOf(action, "ADD_MY_MODEL", "REMOVE_MY_MODEL") {
		path := "/assistant/confirm"
		if undo {
			path = "/assistant/undo"
		}
		result, e := s.localAPI(r.Context(), "POST", path, v)
		if e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
		jsonOK(w, 200, result)
		return
	}
	if undo && oneOf(action, "ACTIVATE_KNOWLEDGE", "ACTIVATE_CONNECTOR") {
		result, e := s.localAPI(r.Context(), "POST", "/versions/undo", v)
		if e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
		jsonOK(w, 200, result)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var state, beforeRaw, afterRaw, resource, beforeHash, afterHash string
	if tx.QueryRowContext(r.Context(), "SELECT a.state,a.before_json,a.after_json,d.resource_json,d.before_hash,d.after_hash FROM action_ledger a JOIN assistant_action_details d ON d.action_id=a.id WHERE a.id=? AND a.action='CONTROL_CHANGE'", v.ID).Scan(&state, &beforeRaw, &afterRaw, &resource, &beforeHash, &afterHash) != nil {
		http.Error(w, "control action missing", 404)
		return
	}
	expected, next := "PENDING", "CONFIRMED"
	target := afterRaw
	hash := beforeHash
	if undo {
		expected, next = "CONFIRMED", "UNDONE"
		target = beforeRaw
		hash = afterHash
	}
	if state != expected {
		http.Error(w, "action no longer available", 409)
		return
	}
	var change ControlChange
	var data map[string]any
	json.Unmarshal([]byte(resource), &change)
	decoder := json.NewDecoder(strings.NewReader(target))
	decoder.UseNumber()
	if decoder.Decode(&data) != nil {
		http.Error(w, "stored action invalid", 500)
		return
	}
	current, e := resourceSnapshot(r.Context(), tx, change)
	if e != nil || snapshotHash(current) != hash {
		http.Error(w, "resource changed; recreate preview or refuse stale undo", 409)
		return
	}
	if e = s.writeResource(r.Context(), tx, change, data); e != nil {
		http.Error(w, "mutation failed", 500)
		return
	}
	updated, e := resourceSnapshot(r.Context(), tx, change)
	if e != nil {
		http.Error(w, "mutation state unreadable", 500)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE action_ledger SET state=? WHERE id=? AND state=?", next, v.ID, expected); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE assistant_action_details SET after_hash=? WHERE action_id=?", snapshotHash(updated), v.ID); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"id": v.ID, "state": next, "history_preserved": true})
}
func (s *Server) confirmControlAction(w http.ResponseWriter, r *http.Request) {
	s.controlTransition(w, r, false)
}
func (s *Server) undoControlAction(w http.ResponseWriter, r *http.Request) {
	s.controlTransition(w, r, true)
}
func (s *Server) listAssistantActions(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,action,subject,state,before_json,after_json,created_at FROM action_ledger ORDER BY rowid DESC LIMIT 100")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, action, subject, state, b, a string
		var at int64
		if rows.Scan(&id, &action, &subject, &state, &b, &a, &at) == nil {
			var before, after map[string]any
			json.Unmarshal([]byte(b), &before)
			json.Unmarshal([]byte(a), &after)
			out = append(out, map[string]any{"id": id, "action": action, "subject": subject, "state": state, "before": visibleSnapshot(before), "after": visibleSnapshot(after), "at": at})
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) listNotes(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT subject_type,subject_id,notes,tags_json FROM entity_notes ORDER BY rowid DESC LIMIT 500")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var kind, id, notes, tags string
		if rows.Scan(&kind, &id, &notes, &tags) == nil {
			var values []string
			json.Unmarshal([]byte(tags), &values)
			out = append(out, map[string]any{"subject_type": kind, "subject_id": id, "notes": notes, "tags": values})
		}
	}
	jsonOK(w, 200, out)
}
