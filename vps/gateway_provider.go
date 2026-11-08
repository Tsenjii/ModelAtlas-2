package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

type gatewayProvider struct {
	Provider
	Website         string             `json:"website"`
	CheckinURL      string             `json:"checkin_url"`
	RequiresCheckin bool               `json:"requires_checkin"`
	CheckinOffered  *bool              `json:"checkin_offered"`
	PendingCheckins int                `json:"pending_checkins"`
	Accounts        int                `json:"accounts"`
	Keys            int                `json:"keys"`
	Models          int                `json:"models"`
	Performance     gatewayPerformance `json:"performance"`
	Quotas          []gatewayQuota     `json:"quotas"`
}

type gatewayQuota struct {
	AccountID    string `json:"account_id"`
	Account      string `json:"account"`
	CredentialID string `json:"credential_id"`
	Credential   string `json:"credential"`
	Role         string `json:"role"`
	Remaining    string `json:"remaining"`
	Used         string `json:"used"`
	Granted      string `json:"granted"`
	Unit         string `json:"unit"`
	Unlimited    bool   `json:"unlimited"`
	Source       string `json:"source"`
	ObservedAt   int64  `json:"observed_at"`
}

const gatewayAccountQuota = "MANUAL_ACCOUNT_QUOTA"

func (s *Server) gatewayProviders(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, offset := pageRange(r, 25)
	id, search := q.Get("provider_id"), strings.TrimSpace(q.Get("q"))
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT p.id,p.name,p.base_url,p.description,p.auth_mode,
		(SELECT COUNT(*) FROM accounts a WHERE a.provider_id=p.id),
		(SELECT COUNT(*) FROM credentials c WHERE c.provider_id=p.id AND c.role='INFERENCE' AND c.enabled=1),
		(SELECT COUNT(DISTINCT m.model_id) FROM my_models m WHERE m.provider_id=p.id)
		FROM providers p WHERE (?='' OR p.id=?) AND instr(lower(p.name||' '||p.description||' '||p.base_url),lower(?))>0
		ORDER BY p.name COLLATE NOCASE,p.id LIMIT ? OFFSET ?`, id, id, search, limit+1, offset)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	list := []gatewayProvider{}
	for rows.Next() {
		var p gatewayProvider
		if err = rows.Scan(&p.ID, &p.Name, &p.BaseURL, &p.Description, &p.AuthMode, &p.Accounts, &p.Keys, &p.Models); err != nil {
			rows.Close()
			http.Error(w, "database error", 500)
			return
		}
		list = append(list, p)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	more := len(list) > limit
	list = list[:min(len(list), limit)]
	zone := str(s.setting(ctx, "timezone", "Asia/Taipei"))
	date, _ := checkinDate(zone, time.Now())
	for i := range list {
		p := &list[i]
		cfg := s.config(ctx, p.Provider)
		p.Website, p.CheckinURL, p.RequiresCheckin = cfg.Website, cfg.CheckinURL, cfg.RequiresCheckin
		p.PendingCheckins = 0
		if p.RequiresCheckin {
			err = s.Store.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM accounts a
				WHERE a.provider_id=? AND NOT EXISTS(SELECT 1 FROM account_checkins c
					WHERE c.provider_id=a.provider_id AND c.account_id=a.id AND c.date=?)`, p.ID, date).Scan(&p.PendingCheckins)
		}
		if err == nil {
			p.CheckinOffered, err = s.gatewayCheckinOffered(ctx, p.Provider)
		}
		if err == nil {
			p.Performance, err = s.gatewayReadPerformance(ctx, "provider_id=?", p.ID, now()-7*86400000)
		}
		if err == nil {
			quotaLimit := 3
			if id != "" {
				quotaLimit = 200
			}
			p.Quotas, err = s.gatewayProviderQuotas(ctx, p.ID, quotaLimit)
		}
		if err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	jsonOK(w, 200, map[string]any{"providers": list, "more": more, "timezone": zone})
}

func (s *Server) gatewayCheckinOffered(ctx context.Context, p Provider) (*bool, error) {
	rows, err := s.Store.DB.QueryContext(ctx, `SELECT json_extract(facts_json,'$.checkin_enabled')
		FROM newapi_snapshots WHERE provider_id=? AND account_id='' AND credential_id=''
		AND credential_role='ANONYMOUS' AND endpoint='/api/status' AND json_valid(facts_json)
		AND json_extract(facts_json,'$.origin')=? AND json_type(facts_json,'$.checkin_enabled') IN ('true','false')
		ORDER BY observed_at DESC,rowid DESC LIMIT 1`, p.ID, p.BaseURL)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var enabled bool
	if rows.Next() {
		err = rows.Scan(&enabled)
		return &enabled, err
	}
	return nil, rows.Err()
}

func (s *Server) gatewayProviderQuotas(ctx context.Context, provider string, limit int) ([]gatewayQuota, error) {
	rows, err := s.Store.DB.QueryContext(ctx, `WITH newest AS (
		SELECT a.id AS account_id,a.label AS account,c.id AS credential_id,c.label AS credential,c.role,
		(SELECT n.rowid FROM newapi_snapshots n
			WHERE n.provider_id=c.provider_id AND n.account_id=c.account_id AND n.credential_id=c.id AND n.credential_role=c.role
			AND n.endpoint=CASE c.role WHEN 'ACCOUNT_PAT' THEN '/api/user/self' ELSE '/api/usage/token/' END
			AND json_valid(n.facts_json) AND json_extract(n.facts_json,'$.origin')=COALESCE(o.base_url,p.base_url)
			AND n.observed_at>=c.created_at
			AND ((c.role='INFERENCE' OR json_type(n.facts_json,'$.credential_version')='integer')
				AND COALESCE(json_extract(n.facts_json,'$.credential_version'),0)=
				(SELECT COALESCE(MAX(v.version),0) FROM credential_secret_versions v WHERE v.provider_id=c.provider_id AND v.account_id=c.account_id AND v.credential_id=c.id)
				OR c.role='ACCOUNT_PAT' AND json_type(n.facts_json,'$.credential_version') IS NULL AND n.observed_at>
				(SELECT COALESCE(MAX(v.created_at),0) FROM credential_secret_versions v WHERE v.provider_id=c.provider_id AND v.account_id=c.account_id AND v.credential_id=c.id))
			ORDER BY n.observed_at DESC,n.rowid DESC LIMIT 1) AS snapshot
		FROM credentials c JOIN providers p ON p.id=c.provider_id JOIN accounts a ON a.provider_id=c.provider_id AND a.id=c.account_id
		LEFT JOIN credential_origins o ON o.provider_id=c.provider_id AND o.account_id=c.account_id AND o.credential_id=c.id
		WHERE c.provider_id=? AND c.enabled=1 AND c.role IN ('INFERENCE','ACCOUNT_PAT')
	)
	SELECT a.account_id,a.account,a.credential_id,a.credential,a.role,n.facts_json,n.source,n.observed_at
		FROM newest a JOIN newapi_snapshots n ON n.rowid=a.snapshot
	UNION ALL
	SELECT a.id,a.label,'','','ACCOUNT',o.facts_json,o.source,o.observed_at
		FROM accounts a JOIN observations o ON o.rowid=(SELECT rowid FROM observations
			WHERE scope_key=atlas_scope_key(a.provider_id,a.id,'','','','') AND kind=? AND source='MANUAL' AND status='SUCCESS'
			ORDER BY observed_at DESC,rowid DESC LIMIT 1)
		WHERE a.provider_id=?
	ORDER BY observed_at DESC LIMIT ?`, provider, gatewayAccountQuota, provider, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []gatewayQuota{}
	for rows.Next() {
		var v gatewayQuota
		var raw []byte
		if err = rows.Scan(&v.AccountID, &v.Account, &v.CredentialID, &v.Credential, &v.Role, &raw, &v.Source, &v.ObservedAt); err != nil {
			return nil, err
		}
		var facts map[string]json.RawMessage
		if json.Unmarshal(raw, &facts) != nil {
			continue
		}
		v.Unit = "站方額度"
		switch v.Role {
		case "ACCOUNT":
			v.Unit = stringField(facts, "unit")
			v.Remaining = stringField(facts, "remaining")
		case "ACCOUNT_PAT":
			v.Remaining = gatewayQuotaNumber(facts, "quota")
			v.Used = gatewayQuotaNumber(facts, "used_quota")
		case "INFERENCE":
			v.Remaining = gatewayQuotaNumber(facts, "total_available")
			v.Used = gatewayQuotaNumber(facts, "total_used")
			v.Granted = gatewayQuotaNumber(facts, "total_granted")
			v.Unlimited, _ = scalarField(facts, "unlimited_quota").(bool)
		}
		if v.Remaining != "" || v.Used != "" || v.Granted != "" || v.Unlimited {
			list = append(list, v)
		}
	}
	return list, rows.Err()
}

func gatewayQuotaNumber(facts map[string]json.RawMessage, key string) string {
	n, _ := scalarField(facts, key).(json.Number)
	return n.String()
}

func (s *Server) gatewaySaveProvider(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID          string `json:"provider_id"`
		Name        string `json:"name"`
		Description string `json:"description"`
		Website     string `json:"website"`
		CheckinURL  string `json:"checkin_url"`
		Required    bool   `json:"requires_checkin"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	in.Name, in.Description = strings.TrimSpace(in.Name), strings.TrimSpace(in.Description)
	in.Website, in.CheckinURL = strings.TrimSpace(in.Website), strings.TrimSpace(in.CheckinURL)
	ctx := r.Context()
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	if !validID(in.ID) || in.Name == "" || safeText(in.Name, 120, secrets...) != in.Name ||
		safeMultilineText(in.Description, 1000, secrets...) != in.Description ||
		in.Website != "" && (!validateHTTPS(in.Website) || safeText(in.Website, 4096, secrets...) != in.Website) ||
		in.Required && in.CheckinURL == "" ||
		in.CheckinURL != "" && (!validateHTTPS(in.CheckinURL) || safeText(in.CheckinURL, 4096, secrets...) != in.CheckinURL) {
		http.Error(w, "invalid provider profile or check-in URL", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, "UPDATE providers SET name=?,description=? WHERE id=?", in.Name, in.Description, in.ID)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := result.RowsAffected(); n != 1 {
		http.Error(w, "provider not found", 404)
		return
	}
	required, _ := json.Marshal(in.Required)
	_, err = tx.ExecContext(ctx, `INSERT INTO provider_config(provider_id,config_json) VALUES(?,json_object('website',?,'checkin_url',?,'requires_daily_checkin',json(?)))
		ON CONFLICT(provider_id) DO UPDATE SET config_json=json_set(config_json,'$.website',?,'$.checkin_url',?,'$.requires_daily_checkin',json(?))`,
		in.ID, in.Website, in.CheckinURL, string(required), in.Website, in.CheckinURL, string(required))
	if err != nil || tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, map[string]string{"id": in.ID})
}

func (s *Server) gatewaySaveQuota(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ProviderID string `json:"provider_id"`
		AccountID  string `json:"account_id"`
		Amount     string `json:"remaining"`
		Unit       string `json:"unit"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	in.Amount = strings.TrimSpace(in.Amount)
	if !validID(in.ProviderID) || !validID(in.AccountID) ||
		!oneOf(in.Unit, "USD", "TWD", "點", "次", "Token") ||
		len(in.Amount) > 32 || strings.ContainsAny(in.Amount, "-+eE") || !decimalPattern.MatchString(in.Amount) {
		http.Error(w, "valid non-negative amount and unit required", 400)
		return
	}
	ctx := r.Context()
	raw, _ := json.Marshal(map[string]string{"remaining": in.Amount, "unit": in.Unit})
	result, err := s.Store.DB.ExecContext(ctx, `INSERT INTO observations(id,scope_key,kind,source,status,facts_json,observed_at)
		SELECT ?,?,?,'MANUAL','SUCCESS',?,? FROM accounts WHERE provider_id=? AND id=?`,
		newID(), scopeKey(Selection{ProviderID: in.ProviderID, AccountID: in.AccountID}), gatewayAccountQuota, string(raw), now(), in.ProviderID, in.AccountID)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if n, _ := result.RowsAffected(); n != 1 {
		http.Error(w, "account not found", 404)
		return
	}
	jsonOK(w, 200, map[string]bool{"saved": true})
}

func (s *Server) gatewayAddCredential(w http.ResponseWriter, r *http.Request) {
	var v Credential
	if !decodeJSON(w, r, &v) {
		return
	}
	v.ID, v.Label = newID(), strings.TrimSpace(v.Label)
	secrets, err := s.knownSecrets(r.Context())
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	secrets = append(secrets, v.Secret)
	if v.Label == "" || safeText(v.Label, 120, secrets...) != v.Label ||
		safeMultilineText(v.Note, 512, secrets...) != v.Note || safeText(v.BaseURL, 4096, secrets...) != v.BaseURL {
		http.Error(w, "invalid credential label or note", 400)
		return
	}
	s.saveCredential(w, r, v)
}

// The existing private New API readers require an exact inference Key or
// account PAT. Bound these on-demand requests by the same concurrency budget.
func (s *Server) gatewayRefreshQuota(w http.ResponseWriter, r *http.Request) {
	var in struct {
		NewApiQuery
		Role string `json:"role"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validID(in.ProviderID) || !validID(in.AccountID) || !validID(in.CredentialID) || !oneOf(in.Role, "INFERENCE", "ACCOUNT_PAT") {
		http.Error(w, "valid credential scope required", 400)
		return
	}
	path := "/api/usage/token/"
	if in.Role == "ACCOUNT_PAT" {
		path = "/api/user/self"
	}
	id := fmt.Sprintf("gateway-quota:%s/%s/%s", in.ProviderID, in.AccountID, in.CredentialID)
	if !s.beginFlight(id) {
		http.Error(w, "upstream refresh already running or service busy", 409)
		return
	}
	defer s.endFlight(id)
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	result, err := s.newApiRead(ctx, in.NewApiQuery, path)
	if err != nil {
		writeProviderProblem(w, err, path)
		return
	}
	jsonOK(w, 200, result)
}
