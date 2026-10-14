package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
)

type OnboardingInput struct {
	EndpointProfiles []EndpointProfile `json:"endpoint_profiles,omitempty"`
	Mode             string            `json:"mode"`
	Provider         Provider          `json:"provider"`
	Account          Account           `json:"account"`
	Credentials      []Credential      `json:"credentials"`
	ModelIDs         []string          `json:"model_ids"`
	Group            string            `json:"group"`
	Endpoint         string            `json:"endpoint"`
}
type storedDraft struct {
	Input     OnboardingInput   `json:"input"`
	Encrypted map[string]string `json:"encrypted"`
}
type OnboardingReview struct {
	EndpointProfiles []EndpointProfile `json:"endpoint_profiles,omitempty"`
	ID               string            `json:"id"`
	Status           string            `json:"status"`
	Provider         Provider          `json:"provider"`
	Account          Account           `json:"account"`
	Credentials      []Credential      `json:"credentials"`
	Models           []string          `json:"models"`
	CatalogVerified  bool              `json:"catalog_verified"`
	Unknown          []string          `json:"unknown_fields"`
	Attempts         int               `json:"attempts"`
}

func (s *Server) prepareDraft(in OnboardingInput) (storedDraft, error) {
	if !oneOf(in.Mode, "QUICK", "ADVANCED") || !validateHTTPS(in.Provider.BaseURL) || len(in.Provider.Name) > 120 || len(in.Credentials) > 100 || len(in.ModelIDs) > 100 || safeText(in.Group, 120) != in.Group {
		return storedDraft{}, errors.New("invalid onboarding input")
	}
	if in.Provider.ID == "" {
		in.Provider.ID = newID()
	}
	if in.Provider.Name == "" {
		in.Provider.Name = "新 Provider"
	}
	if in.Account.ID == "" {
		in.Account.ID = newID()
	}
	in.Account.ProviderID = in.Provider.ID
	if in.Provider.AuthMode == "" {
		in.Provider.AuthMode = "BEARER"
	}
	cfg := ProviderConfig{Adapter: "GENERIC", AuthMode: in.Provider.AuthMode}
	if in.Provider.Config != nil {
		cfg = *in.Provider.Config
		cfg.AuthMode = in.Provider.AuthMode
		if cfg.Adapter == "" {
			cfg.Adapter = "GENERIC"
		}
	}
	if !safeConfig(cfg) {
		return storedDraft{}, errors.New("invalid configuration")
	}
	in.Provider.Config = &cfg
	if !validID(in.Provider.ID) || !validID(in.Account.ID) || len(in.Account.Label) > 120 {
		return storedDraft{}, errors.New("invalid identity")
	}
	if len(in.EndpointProfiles) > 10 {
		return storedDraft{}, errors.New("endpoint profile limit")
	}
	base, _ := url.Parse(in.Provider.BaseURL)
	for i := range in.EndpointProfiles {
		profile := &in.EndpointProfiles[i]
		profile.ProviderID = in.Provider.ID
		if profile.ID == "" {
			profile.ID = newID()
		}
		target, e := url.Parse(profile.URL)
		if e != nil || !validateHTTPS(profile.URL) || !strings.EqualFold(base.Host, target.Host) || !validID(profile.ID) || endpointNames[profile.Operation] == "" || profile.Operation == "MODELS" {
			return storedDraft{}, errors.New("invalid endpoint profile")
		}
		if _, reserved := endpointNames[profile.ID]; reserved {
			return storedDraft{}, errors.New("reserved endpoint ID")
		}
	}
	if in.Endpoint == "" {
		in.Endpoint = "CHAT_COMPLETIONS"
	}
	profileFound := false
	for _, profile := range in.EndpointProfiles {
		if profile.ID == in.Endpoint {
			profileFound = true
		}
	}
	if endpointNames[in.Endpoint] == "" && !profileFound {
		return storedDraft{}, errors.New("invalid endpoint")
	}
	if len(in.Credentials) == 0 && cfg.AuthMode == "NONE" {
		in.Credentials = []Credential{{ID: newID(), Label: "匿名", Role: "INFERENCE"}}
	}
	if len(in.Credentials) == 0 {
		return storedDraft{}, errors.New("credential required")
	}
	draft := storedDraft{Encrypted: map[string]string{}}
	ids := map[string]bool{}
	for i := range in.Credentials {
		c := &in.Credentials[i]
		if c.ID == "" {
			c.ID = newID()
		}
		c.ProviderID = in.Provider.ID
		c.AccountID = in.Account.ID
		if c.Role == "" {
			c.Role = "INFERENCE"
		}
		if c.Group == "" {
			c.Group = in.Group
		}
		if !validID(c.ID) || ids[c.ID] || len(c.Label) > 120 || len(c.Note) > 512 || safeText(c.Group, 120) != c.Group || !oneOf(c.Role, "INFERENCE", "ACCOUNT_PAT") {
			return storedDraft{}, errors.New("invalid credential metadata")
		}
		ids[c.ID] = true
		if cfg.AuthMode != "NONE" || c.Role == "ACCOUNT_PAT" {
			if c.Secret == "" && !(cfg.AuthMode == "CUSTOM_HEADER" && len(c.AuthFields) > 0 && c.Role == "INFERENCE") || len(c.Secret) > 8192 || strings.ContainsAny(c.Secret, "\r\n\x00") {
				return storedDraft{}, errors.New("invalid secret")
			}
			enc := ""
			var err error
			if c.Secret != "" {
				enc, err = s.encrypt(c.Secret)
			}
			if err != nil {
				return storedDraft{}, errors.New("vault unavailable")
			}
			draft.Encrypted[c.ID] = enc
		} else if c.Secret != "" || len(c.AuthFields) > 0 {
			return storedDraft{}, errors.New("anonymous secret not allowed")
		}
		fields, err := s.encryptFields(c.AuthFields)
		if err != nil || c.Role == "ACCOUNT_PAT" && len(c.AuthFields) > 0 {
			return storedDraft{}, errors.New("invalid custom auth fields")
		}
		draft.Encrypted[c.ID+":fields"] = fields
		for i := range c.AuthFields {
			c.AuthFields[i].Secret = ""
		}
		c.Secret = ""
		c.Enabled = true
	}
	for _, id := range in.ModelIDs {
		if !validID(id) {
			return storedDraft{}, errors.New("invalid model ID")
		}
	}
	draft.Input = in
	return draft, nil
}
func (s *Server) inspectDraft(ctx context.Context, id string, draft storedDraft, attempts int) OnboardingReview {
	in := draft.Input
	review := OnboardingReview{EndpointProfiles: in.EndpointProfiles, ID: id, Status: "REVIEW", Provider: in.Provider, Account: in.Account, Credentials: in.Credentials, Models: append([]string{}, in.ModelIDs...), Unknown: []string{"價格", "配額", "模型可用性"}, Attempts: attempts}
	if in.Mode == "QUICK" {
		if _, version, err := s.newApiStatus(ctx, in.Provider); err == nil && version == "v1.0.0-rc.25" {
			cfg := *in.Provider.Config
			cfg.Adapter = "NEW_API"
			review.Provider.Config = &cfg
		}
	}
	cfg := *review.Provider.Config
	for _, c := range in.Credentials {
		if c.Role != "INFERENCE" {
			continue
		}
		key := ""
		if enc := draft.Encrypted[c.ID]; enc != "" {
			key, _ = s.decrypt(enc)
		}
		probeProvider := in.Provider
		probeProvider.AuthFields, _ = s.decryptFields(draft.Encrypted[c.ID+":fields"])
		adapter := adapterRegistry[cfg.Adapter]
		if adapter.Discover != nil {
			rows, err := adapter.Discover(s, ctx, probeProvider, cfg, key)
			if err == nil {
				review.CatalogVerified = true
				seen := map[string]bool{}
				for _, id := range review.Models {
					seen[id] = true
				}
				for _, row := range rows {
					if !seen[row.ID] {
						review.Models = append(review.Models, row.ID)
						seen[row.ID] = true
					}
				}
			}
		}
		break
	}
	if !review.CatalogVerified {
		review.Unknown = append(review.Unknown, "Catalog / 憑證驗證")
	}
	return review
}
func (s *Server) onboardingPreview(w http.ResponseWriter, r *http.Request) {
	var in OnboardingInput
	if !decodeLimit(w, r, &in, 2<<20) {
		return
	}
	draft, err := s.prepareDraft(in)
	if err != nil {
		http.Error(w, "invalid onboarding input or unavailable vault", 422)
		return
	}
	id := newID()
	raw, _ := json.Marshal(draft)
	if _, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO onboarding VALUES(?,'DRAFT',?,'{}',1,?,?)", id, string(raw), now(), now()); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	review := s.inspectDraft(r.Context(), id, draft, 1)
	b, _ := json.Marshal(review)
	if _, err = s.Store.DB.ExecContext(r.Context(), "UPDATE onboarding SET status='REVIEW',review_json=?,updated_at=? WHERE id=?", string(b), now(), id); err != nil {
		http.Error(w, "review persistence failed", 500)
		return
	}
	jsonOK(w, 201, review)
}
func (s *Server) onboardingRetry(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID string `json:"id"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.beginFlight("onboarding:" + v.ID) {
		http.Error(w, "workflow busy", 409)
		return
	}
	defer s.endFlight("onboarding:" + v.ID)
	var raw, status string
	var attempts int
	if s.Store.DB.QueryRowContext(r.Context(), "SELECT draft_json,status,attempts FROM onboarding WHERE id=?", v.ID).Scan(&raw, &status, &attempts) != nil {
		http.Error(w, "workflow missing", 404)
		return
	}
	if oneOf(status, "COMMITTED", "ABANDONED") || attempts >= 6 {
		http.Error(w, "workflow finished or probe budget exhausted", 409)
		return
	}
	var draft storedDraft
	if json.Unmarshal([]byte(raw), &draft) != nil {
		http.Error(w, "invalid workflow", 500)
		return
	}
	review := s.inspectDraft(r.Context(), v.ID, draft, attempts+1)
	b, _ := json.Marshal(review)
	if _, err := s.Store.DB.ExecContext(r.Context(), "UPDATE onboarding SET review_json=?,attempts=?,status='REVIEW',updated_at=? WHERE id=? AND status!='COMMITTED'", string(b), attempts+1, now(), v.ID); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	jsonOK(w, 200, review)
}
func (s *Server) onboardingConfirm(w http.ResponseWriter, r *http.Request) {
	var v struct {
		ID       string   `json:"id"`
		Confirm  bool     `json:"confirm"`
		Name     string   `json:"name"`
		Selected []string `json:"selected_model_ids"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !v.Confirm || len(v.Selected) < 1 || len(v.Selected) > 100 || len(v.Name) > 120 {
		http.Error(w, "review confirmation and selected models required", 400)
		return
	}
	tx, err := s.Store.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer tx.Rollback()
	var raw, reviewRaw, status string
	if tx.QueryRowContext(r.Context(), "SELECT draft_json,review_json,status FROM onboarding WHERE id=?", v.ID).Scan(&raw, &reviewRaw, &status) != nil {
		http.Error(w, "workflow missing", 404)
		return
	}
	if status != "REVIEW" {
		http.Error(w, "review unavailable", 409)
		return
	}
	var draft storedDraft
	var review OnboardingReview
	if json.Unmarshal([]byte(raw), &draft) != nil || json.Unmarshal([]byte(reviewRaw), &review) != nil {
		http.Error(w, "invalid stored workflow", 500)
		return
	}
	for _, id := range v.Selected {
		if !oneOf(id, review.Models...) {
			http.Error(w, "model was not reviewed", 422)
			return
		}
	}
	p := review.Provider
	if v.Name != "" {
		p.Name = v.Name
	}
	mode := "BEARER"
	if p.AuthMode == "NONE" {
		mode = "NONE"
	}
	cfg := *p.Config
	configRaw, _ := json.Marshal(cfg)
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO providers(id,name,base_url,description,auth_mode,created_at) VALUES(?,?,?,?,?,?)", p.ID, p.Name, p.BaseURL, p.Description, mode, now()); err != nil {
		http.Error(w, "provider already exists; use existing provider controls", 409)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO provider_config VALUES(?,?)", p.ID, string(configRaw)); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	a := draft.Input.Account
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO accounts VALUES(?,?,?)", a.ID, p.ID, a.Label); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	for _, c := range draft.Input.Credentials {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credentials(id,provider_id,account_id,label,encrypted_key,role,enabled,created_at) VALUES(?,?,?,?,?,?,1,?)", c.ID, p.ID, a.ID, c.Label, draft.Encrypted[c.ID], c.Role, now()); err != nil {
			http.Error(w, "database error", 500)
			return
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_context VALUES(?,?,?,?,?,?)", p.ID, a.ID, c.ID, c.Group, c.Note, c.Priority); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	for _, c := range draft.Input.Credentials {
		raw := draft.Encrypted[c.ID+":fields"]
		if raw == "" {
			raw = "[]"
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO credential_auth_fields VALUES(?,?,?,?)", p.ID, a.ID, c.ID, raw); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	for _, id := range review.Models {
		source := "PROVIDER_CATALOG"
		if oneOf(id, draft.Input.ModelIDs...) {
			source = "MANUAL"
		}
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO models VALUES(?,?,?,'UNKNOWN','UNKNOWN',?,?)", p.ID, id, id, source, now()); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	for _, profile := range draft.Input.EndpointProfiles {
		if _, err = tx.ExecContext(r.Context(), "INSERT INTO endpoint_profiles VALUES(?,?,?,?)", p.ID, profile.ID, profile.Operation, profile.URL); err != nil {
			http.Error(w, "duplicate endpoint profile", 409)
			return
		}
	}
	selections := []Selection{}
	for _, c := range draft.Input.Credentials {
		if c.Role != "INFERENCE" {
			continue
		}
		for _, id := range v.Selected {
			selection := Selection{ProviderID: p.ID, AccountID: a.ID, CredentialID: c.ID, ModelID: id, Group: c.Group, Endpoint: draft.Input.Endpoint}
			if _, err = tx.ExecContext(r.Context(), "INSERT INTO my_models VALUES(?,?,?,?,?,?,?)", selection.ProviderID, selection.AccountID, selection.CredentialID, selection.ModelID, selection.Group, selection.Endpoint, now()); err != nil {
				http.Error(w, "duplicate selection", 409)
				return
			}
			if bumpMembership(tx, selection) != nil {
				http.Error(w, "database error", 500)
				return
			}
			if bindEndpoint(tx, p, cfg, selection.Endpoint) != nil {
				http.Error(w, "endpoint binding failed", 500)
				return
			}
			selections = append(selections, selection)
		}
	}
	if len(selections) == 0 {
		http.Error(w, "inference credential required", 422)
		return
	}
	if _, err = tx.ExecContext(r.Context(), "UPDATE onboarding SET status='COMMITTED',updated_at=? WHERE id=? AND status='REVIEW'", now(), v.ID); err != nil {
		http.Error(w, "database error", 500)
		return
	}
	if tx.Commit() != nil {
		http.Error(w, "database error", 500)
		return
	}
	for _, selection := range selections {
		s.scheduleEnrichment(r.Context(), selection)
	}
	jsonOK(w, 201, map[string]any{"id": v.ID, "provider_id": p.ID, "selections": selections, "status": "COMMITTED"})
}
func (s *Server) listOnboarding(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.QueryContext(r.Context(), "SELECT id,status,review_json,attempts FROM onboarding ORDER BY rowid DESC LIMIT 100")
	if err != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []OnboardingReview{}
	for rows.Next() {
		var x OnboardingReview
		var id, status, raw string
		var attempts int
		if rows.Scan(&id, &status, &raw, &attempts) == nil {
			_ = json.Unmarshal([]byte(raw), &x)
			x.ID = id
			x.Status = status
			x.Attempts = attempts
			out = append(out, x)
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) batchImport(w http.ResponseWriter, r *http.Request) {
	// A batch produces independent resumable reviews. It never bypasses confirmation.
	var v struct {
		Items []OnboardingInput `json:"items"`
	}
	if !decodeLimit(w, r, &v, 4<<20) {
		return
	}
	if len(v.Items) < 1 || len(v.Items) > 50 {
		http.Error(w, "batch must contain 1..50 providers", 400)
		return
	}
	out := []map[string]any{}
	for i, in := range v.Items {
		draft, err := s.prepareDraft(in)
		if err != nil {
			out = append(out, map[string]any{"index": i, "status": "INVALID"})
			continue
		}
		id := newID()
		raw, _ := json.Marshal(draft)
		review := OnboardingReview{ID: id, Status: "REVIEW", Provider: draft.Input.Provider, Account: draft.Input.Account, Credentials: draft.Input.Credentials, Models: draft.Input.ModelIDs, Unknown: []string{"未探測；可個別重試"}}
		b, _ := json.Marshal(review)
		_, err = s.Store.DB.ExecContext(r.Context(), "INSERT INTO onboarding VALUES(?,'REVIEW',?,?,0,?,?)", id, string(raw), string(b), now(), now())
		if err != nil {
			out = append(out, map[string]any{"index": i, "status": "FAILED"})
			continue
		}
		out = append(out, map[string]any{"index": i, "review": review})
	}
	jsonOK(w, 201, out)
}
