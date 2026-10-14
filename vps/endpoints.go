package main

import (
	"context"
	"database/sql"
	"net/http"
	"net/url"
	"strings"
)

type EndpointProfile struct {
	ProviderID string `json:"provider_id"`
	ID         string `json:"id"`
	Operation  string `json:"operation"`
	URL        string `json:"url"`
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
	var raw string
	err := s.Store.DB.QueryRowContext(ctx, "SELECT url FROM endpoint_bindings WHERE provider_id=? AND endpoint_id=?", p.ID, id).Scan(&raw)
	if err == nil {
		return raw, nil
	}
	if _, ok := endpointNames[id]; ok {
		return endpointURL(p, c, id, model)
	}
	err = s.Store.DB.QueryRowContext(ctx, "SELECT url FROM endpoint_profiles WHERE provider_id=? AND id=?", p.ID, id).Scan(&raw)
	if err != nil {
		return "", err
	}
	return raw, nil
}
func bindEndpoint(tx *sql.Tx, p Provider, c ProviderConfig, id string) error {
	var raw, op string
	if _, ok := endpointNames[id]; ok {
		op = id
		var err error
		raw, err = endpointURL(p, c, id, "")
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
	if _, builtin := endpointNames[v.ID]; builtin {
		http.Error(w, "profile ID conflicts with built-in endpoint", 409)
		return
	}
	base, _ := url.Parse(p.BaseURL)
	target, _ := url.Parse(v.URL)
	if !strings.EqualFold(base.Host, target.Host) {
		http.Error(w, "endpoint must match this credential origin", 422)
		return
	}
	if _, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO endpoint_profiles VALUES(?,?,?,?)", v.ProviderID, v.ID, v.Operation, v.URL); err != nil {
		http.Error(w, "immutable endpoint profile exists", 409)
		return
	}
	jsonOK(w, 201, v)
}
func (s *Server) listEndpoints(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT provider_id,id,operation,url FROM endpoint_profiles ORDER BY provider_id,id")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []EndpointProfile{}
	for rows.Next() {
		var v EndpointProfile
		if rows.Scan(&v.ProviderID, &v.ID, &v.Operation, &v.URL) == nil {
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
