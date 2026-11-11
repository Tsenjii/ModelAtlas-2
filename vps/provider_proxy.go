package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Only the requirement to use a proxy is shared in provider_config. Addresses
// and authentication belong to this engine and are encrypted with its vault.
const providerProxySchema = `
CREATE TABLE IF NOT EXISTS provider_proxies (
 provider_id TEXT PRIMARY KEY REFERENCES providers(id) ON DELETE CASCADE,
 encrypted_url TEXT NOT NULL DEFAULT '',
 revision INTEGER NOT NULL,
 updated_at INTEGER NOT NULL
);
`

type providerProxyStatus struct {
	Mode       string `json:"mode"`
	URL        string `json:"url"`
	Configured bool   `json:"configured"`
	HasAuth    bool   `json:"has_auth"`
	Revision   int64  `json:"revision"`
	Problem    string `json:"problem,omitempty"`
	encrypted  string
	target     *url.URL
}

func parseProviderProxy(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || len(raw) > 4096 || safeText(raw, 4096) != raw || u.Hostname() == "" ||
		!oneOf(u.Scheme, "http", "https", "socks5", "socks5h") ||
		u.Opaque != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" ||
		u.Path != "" && u.Path != "/" || strings.ContainsAny(u.Host, "%\\\t\r\n ") {
		return nil, errors.New("invalid provider proxy URL")
	}
	port := u.Port()
	if port == "" {
		port = map[string]string{"http": "80", "https": "443", "socks5": "1080", "socks5h": "1080"}[u.Scheme]
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return nil, errors.New("invalid provider proxy URL")
	}
	if u.User != nil {
		user, password := u.User.Username(), ""
		password, _ = u.User.Password()
		if len(user) > 255 || len(password) > 255 || safeText(user, 255) != user || safeText(password, 255) != password {
			return nil, errors.New("invalid provider proxy authentication")
		}
	}
	u.Host = net.JoinHostPort(u.Hostname(), port)
	u.Path, u.RawPath = "", ""
	return u, nil
}

func (s *Server) providerProxy(ctx context.Context, id string, required bool) (providerProxyStatus, error) {
	status := providerProxyStatus{Mode: "DIRECT"}
	if required {
		status.Mode = "PROXY"
	}
	err := s.Store.DB.QueryRowContext(ctx,
		"SELECT encrypted_url,revision FROM provider_proxies WHERE provider_id=?", id).
		Scan(&status.encrypted, &status.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return status, nil
	}
	if err != nil {
		return status, err
	}
	status.Configured = status.encrypted != ""
	if !required || !status.Configured {
		return status, nil
	}
	raw, err := s.decrypt(status.encrypted)
	if err == nil {
		status.target, err = parseProviderProxy(raw)
	}
	if err != nil {
		status.Configured = false
		status.Problem = "PROXY_UNAVAILABLE"
		return status, nil
	}
	redacted := *status.target
	redacted.User = nil
	status.URL = redacted.String()
	status.HasAuth = status.target.User != nil
	return status, nil
}

func (s *Server) getProviderProxy(w http.ResponseWriter, r *http.Request) {
	p, err := s.provider(r.Context(), r.URL.Query().Get("provider_id"))
	if err != nil {
		http.Error(w, "provider missing", 404)
		return
	}
	status, err := s.providerProxy(r.Context(), p.ID, s.config(r.Context(), p).ProxyRequired)
	if err != nil {
		http.Error(w, "provider proxy unavailable", 503)
		return
	}
	jsonOK(w, 200, status)
}

func (s *Server) saveProviderProxy(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ProviderID string `json:"provider_id"`
		Mode       string `json:"mode"`
		URL        string `json:"proxy_url"`
		Revision   int64  `json:"revision"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !oneOf(in.Mode, "DIRECT", "PROXY") || in.Revision < 0 {
		http.Error(w, "valid provider proxy mode required", 400)
		return
	}
	ctx := r.Context()
	p, err := s.provider(ctx, in.ProviderID)
	if err != nil {
		http.Error(w, "provider missing", 404)
		return
	}
	cfg := s.config(ctx, p)
	cfg.ProxyRequired = in.Mode == "PROXY"
	var replacement string
	if cfg.ProxyRequired && strings.TrimSpace(in.URL) != "" {
		target, err := parseProviderProxy(strings.TrimSpace(in.URL))
		if err != nil {
			http.Error(w, "invalid provider proxy URL", 400)
			return
		}
		replacement, err = s.encrypt(target.String())
		if err != nil {
			http.Error(w, "vault unavailable", 503)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var encrypted string
	var revision int64
	err = tx.QueryRowContext(ctx, "SELECT encrypted_url,revision FROM provider_proxies WHERE provider_id=?", p.ID).Scan(&encrypted, &revision)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "database error", 500)
		return
	}
	if revision != in.Revision {
		http.Error(w, "provider proxy changed; reload before saving", 409)
		return
	}
	if replacement != "" {
		encrypted = replacement
	}
	if cfg.ProxyRequired && encrypted == "" {
		http.Error(w, "provider proxy URL required", 422)
		return
	}
	raw, _ := json.Marshal(cfg)
	_, err = tx.ExecContext(ctx, `INSERT INTO provider_config VALUES(?,?)
		ON CONFLICT(provider_id) DO UPDATE SET config_json=json_set(provider_config.config_json,'$.proxy_required',json(?))`,
		p.ID, string(raw), strconv.FormatBool(cfg.ProxyRequired))
	if err == nil {
		_, err = tx.ExecContext(ctx, `INSERT INTO provider_proxies VALUES(?,?,?,?)
			ON CONFLICT(provider_id) DO UPDATE SET encrypted_url=excluded.encrypted_url,revision=excluded.revision,updated_at=excluded.updated_at`,
			p.ID, encrypted, revision+1, now())
	}
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	s.closeProviderTransport(p.ID)
	jsonOK(w, 200, map[string]bool{"saved": true})
}
