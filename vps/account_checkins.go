package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"time"
)

type accountCheckin struct {
	ProviderID  string `json:"provider_id"`
	AccountID   string `json:"account_id"`
	Label       string `json:"label"`
	Required    bool   `json:"required"`
	URL         string `json:"checkin_url"`
	CompletedAt *int64 `json:"completed_at"`
	Source      string `json:"source"`
}

func checkinDate(zone string, at time.Time) (string, error) {
	loc, err := time.LoadLocation(zone)
	if err != nil || zone == "" || len(zone) > 100 {
		return "", http.ErrNotSupported
	}
	return at.In(loc).Format("2006-01-02"), nil
}

// Legacy provider-wide records remain intact. They cannot establish that a
// particular account completed a check-in. This endpoint never calls upstream.
func (s *Server) accountCheckinsHTTP(w http.ResponseWriter, r *http.Request) {
	zone := r.URL.Query().Get("timezone")
	date, err := checkinDate(zone, time.Now())
	if err != nil {
		http.Error(w, "invalid timezone", 400)
		return
	}
	rows, err := s.Store.DB.QueryContext(r.Context(), `SELECT a.provider_id,a.id,a.label,p.name,p.base_url,p.description,p.auth_mode,c.config_json,x.completed_at,x.source
 FROM accounts a JOIN providers p ON p.id=a.provider_id
 LEFT JOIN provider_config c ON c.provider_id=a.provider_id
 LEFT JOIN account_checkins x ON x.provider_id=a.provider_id AND x.account_id=a.id AND x.date=?
 ORDER BY a.provider_id,a.id`, date)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []accountCheckin{}
	for rows.Next() {
		var item accountCheckin
		var p Provider
		var raw, source sql.NullString
		var completed sql.NullInt64
		if err = rows.Scan(&item.ProviderID, &item.AccountID, &item.Label, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode, &raw, &completed, &source); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		config := ProviderConfig{}
		if raw.Valid && raw.String != "" {
			if err = json.Unmarshal([]byte(raw.String), &config); err != nil {
				http.Error(w, "invalid saved configuration", 500)
				return
			}
		}
		item.Required = config.RequiresCheckin
		item.URL = config.CheckinURL
		if completed.Valid {
			at := completed.Int64
			item.CompletedAt = &at
			item.Source = source.String
		}
		out = append(out, item)
	}
	if rows.Err() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"date": date, "timezone": zone, "accounts": out})
}

func (s *Server) completeAccountCheckin(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ProviderID string `json:"provider_id"`
		AccountID  string `json:"account_id"`
		Timezone   string `json:"timezone"`
		Date       string `json:"date"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	acceptedAt := time.Now()
	date, err := checkinDate(v.Timezone, acceptedAt)
	if err != nil || !validID(v.ProviderID) || !validID(v.AccountID) {
		http.Error(w, "invalid account or timezone", 400)
		return
	}
	if v.Date != date {
		http.Error(w, "local date changed; reload check-in status", 409)
		return
	}
	p, err := s.provider(r.Context(), v.ProviderID)
	if err != nil || !s.config(r.Context(), p).RequiresCheckin {
		http.Error(w, "check-in not configured", 422)
		return
	}
	var exists int
	if err = s.Store.DB.QueryRowContext(r.Context(), "SELECT 1 FROM accounts WHERE provider_id=? AND id=?", v.ProviderID, v.AccountID).Scan(&exists); err != nil {
		if err == sql.ErrNoRows {
			http.Error(w, "account not found", 422)
		} else {
			http.Error(w, "database error", 500)
		}
		return
	}
	// Repeated submission is idempotent and preserves the first manual receipt.
	_, err = s.Store.DB.ExecContext(r.Context(), `INSERT INTO account_checkins(provider_id,account_id,date,timezone,completed_at,source) VALUES(?,?,?,?,?,'MANUAL_COMPLETION') ON CONFLICT(provider_id,account_id,date) DO NOTHING`, v.ProviderID, v.AccountID, date, v.Timezone, acceptedAt.UnixMilli())
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	var at int64
	if err = s.Store.DB.QueryRowContext(r.Context(), "SELECT completed_at FROM account_checkins WHERE provider_id=? AND account_id=? AND date=?", v.ProviderID, v.AccountID, date).Scan(&at); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"provider_id": v.ProviderID, "account_id": v.AccountID, "date": date, "timezone": v.Timezone, "completed_at": at, "source": "MANUAL_COMPLETION"})
}
