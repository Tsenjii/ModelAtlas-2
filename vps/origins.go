package main

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"strings"
)

// Origin is immutable for each credential. Changing the Provider's primary URL
// never relocates existing keys, PATs, endpoint bindings or conversation history.
func boundProvider(ctx context.Context, q queryRows, p, a, c string) (Provider, error) {
	var out Provider
	e := q.QueryRowContext(ctx, "SELECT p.id,p.name,COALESCE(o.base_url,p.base_url),p.description,p.auth_mode,COALESCE(o.auth_mode,'') FROM providers p JOIN credentials c ON c.provider_id=p.id LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id WHERE p.id=? AND c.account_id=? AND c.id=?", p, a, c).Scan(&out.ID, &out.Name, &out.BaseURL, &out.Description, &out.AuthMode, &out.CredentialMode)
	return out, e
}
func sameOrigin(a, b string) bool {
	left, e := url.Parse(a)
	right, f := url.Parse(b)
	return e == nil && f == nil && left.Scheme == "https" && right.Scheme == "https" && strings.EqualFold(left.Host, right.Host)
}
func insertCredentialOrigin(ctx context.Context, tx *sql.Tx, p, a, c, base, mode string) error {
	if !validateHTTPS(base) || !oneOf(mode, "", "NONE", "BEARER", "API_KEY", "CUSTOM_HEADER") {
		return errors.New("valid immutable origin required")
	}
	_, e := tx.ExecContext(ctx, "INSERT INTO credential_origins VALUES(?,?,?,?,?)", p, a, c, base, mode)
	return e
}
func bindSelection(ctx context.Context, tx *sql.Tx, primary Provider, cfg ProviderConfig, v Selection) error {
	p, e := boundProvider(ctx, tx, v.ProviderID, v.AccountID, v.CredentialID)
	if e != nil {
		return e
	}
	var binding string
	e = tx.QueryRowContext(ctx, "SELECT url FROM endpoint_bindings WHERE provider_id=? AND endpoint_id=?", p.ID, v.Endpoint).Scan(&binding)
	if e == nil {
		if !sameOrigin(p.BaseURL, binding) {
			return errors.New("endpoint and credential origins differ")
		}
		return nil
	}
	if e != sql.ErrNoRows {
		return e
	}
	if _, builtin := endpointNames[v.Endpoint]; builtin {
		if p.BaseURL != primary.BaseURL {
			return errors.New("secondary base URL requires an explicit endpoint profile")
		}
	} else {
		if tx.QueryRowContext(ctx, "SELECT url FROM endpoint_profiles WHERE provider_id=? AND id=?", p.ID, v.Endpoint).Scan(&binding) != nil || !sameOrigin(p.BaseURL, binding) {
			return errors.New("endpoint and credential origins differ")
		}
	}
	return bindEndpoint(tx, p, cfg, v.Endpoint)
}
