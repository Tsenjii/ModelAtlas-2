package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
)

// Keep parent identities and disabled public IDs for history and foreign keys.
// Stored upstream credentials themselves are deleted, including old versions.
const gatewayRemovalSchema = `
CREATE TABLE IF NOT EXISTS gateway_removals (
	kind TEXT NOT NULL, provider_id TEXT NOT NULL, account_id TEXT NOT NULL, item_id TEXT NOT NULL,
	PRIMARY KEY(kind,provider_id,account_id,item_id)
);
CREATE TRIGGER IF NOT EXISTS gateway_removed_account_insert BEFORE INSERT ON accounts
WHEN EXISTS(SELECT 1 FROM gateway_removals WHERE provider_id=NEW.provider_id AND
	((kind='PROVIDER' AND account_id='' AND item_id='') OR (kind='ACCOUNT' AND account_id=NEW.id AND item_id='')))
BEGIN SELECT RAISE(ABORT,'removed provider or account'); END;
CREATE TRIGGER IF NOT EXISTS gateway_removed_credential_insert BEFORE INSERT ON credentials
WHEN EXISTS(SELECT 1 FROM gateway_removals WHERE provider_id=NEW.provider_id AND
	((kind='PROVIDER' AND account_id='' AND item_id='') OR
	 (kind='ACCOUNT' AND account_id=NEW.account_id AND item_id='') OR
	 (kind='CREDENTIAL' AND account_id=NEW.account_id AND item_id=NEW.id)))
BEGIN SELECT RAISE(ABORT,'removed credential or account'); END;
`

const gatewayActiveProvider = `NOT EXISTS(SELECT 1 FROM gateway_removals x
	WHERE x.kind='PROVIDER' AND x.provider_id=p.id AND x.account_id='' AND x.item_id='')`

const gatewayActiveAccount = `NOT EXISTS(SELECT 1 FROM gateway_removals x
	WHERE x.provider_id=a.provider_id AND x.item_id='' AND
	((x.kind='ACCOUNT' AND x.account_id=a.id) OR (x.kind='PROVIDER' AND x.account_id='')))`

const gatewayLivePublicModel = `NOT EXISTS(SELECT 1 FROM gateway_removals x
	WHERE x.kind='PUBLIC_MODEL' AND x.provider_id='' AND x.account_id='' AND x.item_id=gateway_models.id)`

// The route's scope is used directly, even for a previously disabled alias
// whose routes were allowed to reference an unavailable connection.
const gatewayRemovedRoute = `(EXISTS(SELECT 1 FROM membership_state
	WHERE state='REMOVED' AND scope_key=atlas_scope_key(
		json_extract(route.value,'$.provider_id'),json_extract(route.value,'$.account_id'),
		json_extract(route.value,'$.credential_id'),json_extract(route.value,'$.model_id'),
		json_extract(route.value,'$.group'),json_extract(route.value,'$.endpoint')))
	OR EXISTS(SELECT 1 FROM gateway_removals x WHERE x.provider_id=json_extract(route.value,'$.provider_id') AND
		((x.kind='PROVIDER' AND x.account_id='' AND x.item_id='') OR
		 (x.kind='ACCOUNT' AND x.account_id=json_extract(route.value,'$.account_id') AND x.item_id='') OR
		 (x.kind='CREDENTIAL' AND x.account_id=json_extract(route.value,'$.account_id') AND x.item_id=json_extract(route.value,'$.credential_id')))))`

const gatewayPruneRoutes = `WITH cleaned AS MATERIALIZED (
	SELECT g.id,(SELECT json_group_array(json(value)) FROM (
		SELECT route.value FROM json_each(g.routes_json) route WHERE NOT ` + gatewayRemovedRoute + ` ORDER BY route.key
	)) AS routes FROM gateway_models g
	WHERE EXISTS(SELECT 1 FROM json_each(g.routes_json) route WHERE ` + gatewayRemovedRoute + `)
) UPDATE gateway_models AS g SET routes_json=cleaned.routes,enabled=CASE WHEN cleaned.routes='[]' THEN 0 ELSE g.enabled END
	FROM cleaned WHERE g.id=cleaned.id`

type gatewayRemoval struct {
	Selection
	Kind      string `json:"kind"`
	ID        string `json:"id"`
	Canonical string `json:"canonical_id"`
	Confirm   bool   `json:"confirm"`
}

func (in gatewayRemoval) valid() bool {
	switch in.Kind {
	case "CONNECTION":
		return validScope(in.Selection)
	case "MODEL_GROUP":
		return validID(in.Canonical)
	case "PROVIDER":
		return validID(in.ProviderID) && in.AccountID == "" && in.CredentialID == ""
	case "ACCOUNT":
		return validID(in.ProviderID) && validID(in.AccountID) && in.CredentialID == ""
	case "CREDENTIAL":
		return validID(in.ProviderID) && validID(in.AccountID) && validID(in.CredentialID)
	case "PUBLIC_MODEL", "PUBLIC_KEY":
		return validID(in.ID)
	}
	return false
}

func (s *Server) gatewayRemove(w http.ResponseWriter, r *http.Request) {
	var in gatewayRemoval
	if !decodeJSON(w, r, &in) {
		return
	}
	if !in.Confirm || !in.valid() {
		http.Error(w, "explicit valid removal required", 400)
		return
	}
	ctx := r.Context()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	switch in.Kind {
	case "PUBLIC_MODEL", "PUBLIC_KEY":
		err = removeGatewayPublic(ctx, tx, in.Kind, in.ID)
	case "CONNECTION", "MODEL_GROUP":
		where := gatewayOfferScopeSQL
		args := []any{in.ProviderID, in.AccountID, in.CredentialID, in.ModelID, in.Group, in.Endpoint}
		if in.Kind == "MODEL_GROUP" {
			where = `(provider_id,account_id,credential_id,model_id,group_id,endpoint) IN (` + gatewayAllCatalogSQL +
				` SELECT provider_id,account_id,credential_id,model_id,group_id,endpoint FROM scopes WHERE canonical_id=?)`
			args = []any{in.Canonical}
		}
		var n int64
		n, err = removeGatewayMemberships(ctx, tx, where, args)
		if n == 0 && err == nil {
			err = sql.ErrNoRows
		}
	default:
		err = removeGatewayUpstream(ctx, tx, in)
	}
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "management item missing", 404)
		return
	}
	if err == nil && in.Kind != "PUBLIC_KEY" && in.Kind != "PUBLIC_MODEL" {
		_, err = tx.ExecContext(ctx, gatewayPruneRoutes)
	}
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]bool{"removed": true, "history_preserved": true})
}

func removeGatewayMemberships(ctx context.Context, tx *sql.Tx, where string, args []any) (int64, error) {
	const keys = "atlas_scope_key(provider_id,account_id,credential_id,model_id,group_id,endpoint)"
	if _, err := tx.ExecContext(ctx, "INSERT INTO membership_mutations(scope_key,at) SELECT "+keys+",? FROM my_models WHERE "+where, append([]any{now()}, args...)...); err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO membership_state(scope_key,state,favorite,watchlist)
		SELECT `+keys+`,'REMOVED',0,0 FROM my_models WHERE `+where+
		` ON CONFLICT(scope_key) DO UPDATE SET state='REMOVED',favorite=0,watchlist=0`, args...); err != nil {
		return 0, err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM my_models WHERE "+where, args...)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func removeGatewayUpstream(ctx context.Context, tx *sql.Tx, in gatewayRemoval) error {
	where, credentialWhere := "provider_id=?", "provider_id=?"
	args := []any{in.ProviderID}
	check := "SELECT 1 FROM providers WHERE id=?"
	if in.Kind != "PROVIDER" {
		where += " AND account_id=?"
		credentialWhere = where
		args = append(args, in.AccountID)
		check = "SELECT 1 FROM accounts WHERE provider_id=? AND id=?"
	}
	if in.Kind == "CREDENTIAL" {
		credentialWhere += " AND id=?"
		where += " AND credential_id=?"
		args = append(args, in.CredentialID)
		check = "SELECT 1 FROM credentials WHERE " + credentialWhere
	}
	// A repeated submission can succeed after the credential row is gone.
	var found int
	err := tx.QueryRowContext(ctx, "SELECT 1 FROM gateway_removals WHERE kind=? AND provider_id=? AND account_id=? AND item_id=?",
		in.Kind, in.ProviderID, in.AccountID, in.CredentialID).Scan(&found)
	if err == nil {
		return nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err = tx.QueryRowContext(ctx, check, args...).Scan(&found); err != nil {
		return err
	}
	if _, err = removeGatewayMemberships(ctx, tx, where, args); err != nil {
		return err
	}
	for _, table := range []string{"credential_auth_fields", "credential_context", "credential_origins", "credential_secret_versions"} {
		if _, err = tx.ExecContext(ctx, "DELETE FROM "+table+" WHERE "+where, args...); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM credentials WHERE "+credentialWhere, args...); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO gateway_removals VALUES(?,?,?,?)",
		in.Kind, in.ProviderID, in.AccountID, in.CredentialID)
	return err
}

func removeGatewayPublic(ctx context.Context, tx *sql.Tx, kind, id string) error {
	query := "UPDATE gateway_keys SET enabled=0,models_json='[]' WHERE id=?"
	if kind == "PUBLIC_MODEL" {
		query = "UPDATE gateway_models SET enabled=0,routes_json='[]' WHERE id=?"
	}
	result, err := tx.ExecContext(ctx, query, id)
	if err != nil {
		return err
	}
	if n, err := result.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return sql.ErrNoRows
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO gateway_removals VALUES(?,'','',?) ON CONFLICT DO NOTHING", kind, id)
	return err
}
