package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
)

type EndpointProfile struct {
	ProviderID string          `json:"provider_id"`
	ID         string          `json:"id"`
	Operation  string          `json:"operation"`
	URL        string          `json:"url"`
	Custom     *CustomProtocol `json:"custom_protocol,omitempty"`
}

func (s *Server) operation(ctx context.Context, v Selection) string {
	if _, ok := endpointNames[v.Endpoint]; ok {
		return v.Endpoint
	}
	var op string
	if s.Store.DB.QueryRowContext(ctx, "SELECT operation FROM endpoint_profiles WHERE provider_id=? AND id=?", v.ProviderID, v.Endpoint).Scan(&op) != nil {
		return ""
	}
	return op
}
func (s *Server) requestEndpoint(ctx context.Context, p Provider, c ProviderConfig, id, model string) (string, error) {
	return requestEndpointFrom(ctx, s.Store.DB, p, c, id, model)
}
func requestEndpointFrom(ctx context.Context, q queryRows, p Provider, c ProviderConfig, id, model string) (string, error) {
	var raw string
	err := q.QueryRowContext(ctx, "SELECT url FROM endpoint_bindings WHERE provider_id=? AND endpoint_id=?", p.ID, id).Scan(&raw)
	if err == nil {
		return modelEndpoint(raw, model)
	}
	if _, ok := endpointNames[id]; ok {
		return endpointURL(p, c, id, model)
	}
	err = q.QueryRowContext(ctx, "SELECT url FROM endpoint_profiles WHERE provider_id=? AND id=?", p.ID, id).Scan(&raw)
	if err != nil {
		return "", err
	}
	return modelEndpoint(raw, model)
}
func bindEndpoint(tx *sql.Tx, p Provider, c ProviderConfig, id string) error {
	if id == "CUSTOM" {
		return errors.New("custom operation requires an explicit endpoint profile")
	}
	var raw, op string
	if _, ok := endpointNames[id]; ok {
		op = id
		var err error
		raw, err = endpointURL(p, c, id, "{model}")
		if err != nil {
			return err
		}
	} else {
		if err := tx.QueryRow("SELECT operation,url FROM endpoint_profiles WHERE provider_id=? AND id=?", p.ID, id).Scan(&op, &raw); err != nil {
			return err
		}
	}
	_, err := tx.Exec("INSERT INTO endpoint_bindings VALUES(?,?,?,?,?) ON CONFLICT(provider_id,endpoint_id) DO NOTHING", p.ID, id, op, raw, now())
	return err
}
func (s *Server) addEndpoint(w http.ResponseWriter, r *http.Request) {
	var v EndpointProfile
	if !decodeJSON(w, r, &v) {
		return
	}
	p, err := s.provider(r.Context(), v.ProviderID)
	if err != nil || !validID(v.ID) || endpointNames[v.Operation] == "" || v.Operation == "MODELS" || !validateHTTPS(v.URL) {
		http.Error(w, "invalid endpoint profile", 400)
		return
	}
	if v.Operation == "CUSTOM" && !validCustom(v.Custom) || v.Operation != "CUSTOM" && v.Custom != nil {
		http.Error(w, "custom operation requires a valid declarative protocol", 400)
		return
	}
	if v.Custom != nil {
		secrets, e := s.knownSecrets(r.Context())
		if e != nil {
			http.Error(w, "vault unavailable", 503)
			return
		}
		for _, secret := range secrets {
			if jsonContainsSecret(v.Custom, secret) {
				http.Error(w, "secret found in protocol metadata", 422)
				return
			}
		}
	}
	if _, builtin := endpointNames[v.ID]; builtin {
		http.Error(w, "profile ID conflicts with built-in endpoint", 409)
		return
	}
	base, _ := url.Parse(p.BaseURL)
	target, _ := url.Parse(v.URL)
	if !strings.EqualFold(base.Host, target.Host) {
		rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT base_url FROM credential_origins WHERE provider_id=?", p.ID)
		allowed := false
		if e == nil {
			for rows.Next() {
				var origin string
				rows.Scan(&origin)
				allowed = allowed || sameOrigin(origin, v.URL)
			}
			rows.Close()
		}
		if !allowed {
			http.Error(w, "endpoint requires an explicitly bound credential origin", 422)
			return
		}
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO endpoint_profiles VALUES(?,?,?,?)", v.ProviderID, v.ID, v.Operation, v.URL); err != nil {
		http.Error(w, "immutable endpoint profile exists", 409)
		return
	}
	if v.Custom != nil {
		if saveCustom(r.Context(), tx, v.ProviderID, v.ID, v.Custom) != nil {
			http.Error(w, "invalid custom definition", 400)
			return
		}
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) listEndpoints(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT e.provider_id,e.id,e.operation,e.url,COALESCE(c.config_json,'') FROM (SELECT provider_id,id,operation,url FROM endpoint_profiles UNION SELECT provider_id,endpoint_id,operation,url FROM endpoint_bindings) e LEFT JOIN custom_protocols c ON c.provider_id=e.provider_id AND c.endpoint_id=e.id ORDER BY e.provider_id,e.id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []EndpointProfile{}
	for rows.Next() {
		var v EndpointProfile
		var raw string
		if rows.Scan(&v.ProviderID, &v.ID, &v.Operation, &v.URL, &raw) == nil {
			if raw != "" {
				v.Custom = new(CustomProtocol)
				_ = json.Unmarshal([]byte(raw), v.Custom)
			}
			out = append(out, v)
		}
	}
	jsonOK(w, 200, out)
}

func backfillEndpointBindings(db *sql.DB) error {
	rows, err := db.Query("SELECT DISTINCT p.id,p.name,p.base_url,p.description,p.auth_mode,m.endpoint FROM providers p JOIN my_models m ON m.provider_id=p.id")
	if err != nil {
		return err
	}
	type entry struct {
		p  Provider
		id string
	}
	list := []entry{}
	for rows.Next() {
		var e entry
		if rows.Scan(&e.p.ID, &e.p.Name, &e.p.BaseURL, &e.p.Description, &e.p.AuthMode, &e.id) != nil {
			rows.Close()
			return sql.ErrNoRows
		}
		list = append(list, e)
	}
	rows.Close()
	s := &Server{Store: &Store{DB: db}}
	for _, e := range list {
		cfg := s.config(context.Background(), e.p)
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		err = bindEndpoint(tx, e.p, cfg, e.id)
		if err != nil {
			tx.Rollback()
			return err
		}
		if err = tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// Model substitution occurs after immutable origin/path binding. IDs cannot add
// path segments, a query, or change the credential origin.
func modelEndpoint(raw, model string) (string, error) {
	if strings.Contains(raw, "%7Bmodel%7D") || strings.Contains(raw, "{model}") {
		model = strings.TrimPrefix(model, "models/")
		if model == "" || strings.ContainsAny(model, "/?#\\") || model == "." || model == ".." {
			return "", errors.New("invalid native model id")
		}
		raw = strings.ReplaceAll(strings.ReplaceAll(raw, "%7Bmodel%7D", url.PathEscape(model)), "{model}", url.PathEscape(model))
	}
	return raw, nil
}
