package main

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// One source snapshot is also packaged as an Android asset. It is public
// provider metadata, never a credential or proof of inference availability.
//
//go:embed defaults/kilo-anonymous.json
var kiloFactoryJSON []byte

const kiloFactoryID = "builtin-kilo-anonymous"
const kiloFactoryBase = "https://ai-gateway.kilo.ai/api/v1"
const kiloFactoryKey = "kilo-v1"
const kiloFactoryNotice = "Kilo免Key來源可能保存對話並用於改善模型；請勿輸入個人或機密資料。預載資料不是實測，送出前會核對公開目錄。"

type kiloFactoryModel struct {
	ID           string                 `json:"id"`
	Name         string                 `json:"name"`
	Context      int64                  `json:"context_length"`
	Pricing      map[string]json.Number `json:"pricing"`
	Parameters   []string               `json:"supported_parameters"`
	Expiration   *string                `json:"expiration_date"`
	Architecture struct {
		Inputs  []string `json:"input_modalities"`
		Outputs []string `json:"output_modalities"`
	} `json:"architecture"`
}
type kiloFactoryManifest struct {
	Version      int                `json:"version"`
	Provider     string             `json:"provider_id"`
	Account      string             `json:"account_id"`
	Credential   string             `json:"credential_id"`
	BaseURL      string             `json:"base_url"`
	CatalogURL   string             `json:"catalog_url"`
	Hash         string             `json:"catalog_sha256"`
	Observed     string             `json:"observed_at"`
	DefaultModel string             `json:"default_model_id"`
	Models       []kiloFactoryModel `json:"models"`
}

func kiloFactory() (kiloFactoryManifest, error) {
	var d kiloFactoryManifest
	if json.Unmarshal(kiloFactoryJSON, &d) != nil || d.Version != 1 || d.Provider != kiloFactoryID || d.Account != kiloFactoryID || d.Credential != kiloFactoryID || d.BaseURL != kiloFactoryBase || d.CatalogURL != kiloFactoryBase+"/models" || len(d.Hash) != 64 || len(d.Models) < 1 || len(d.Models) > 64 {
		return d, errors.New("invalid bundled model catalog")
	}
	seen := map[string]bool{}
	for _, m := range d.Models {
		if !validID(m.ID) || seen[m.ID] || safeText(m.Name, 240) != m.Name || m.Name == "" || decimal(m.Pricing["prompt"]) == nil || decimal(m.Pricing["prompt"]).Sign() != 0 || decimal(m.Pricing["completion"]) == nil || decimal(m.Pricing["completion"]).Sign() != 0 {
			return d, errors.New("invalid bundled anonymous model")
		}
		seen[m.ID] = true
	}
	if !seen[d.DefaultModel] {
		return d, errors.New("bundled default model missing")
	}
	return d, nil
}

type factoryDefaultsInfo struct {
	Installed  bool      `json:"installed"`
	ModelCount int       `json:"model_count"`
	Source     string    `json:"source_url"`
	Observed   string    `json:"observed_at"`
	Notice     string    `json:"notice"`
	Selection  Selection `json:"suggested_selection"`
}

func (s *Server) factoryDefaultsInfo(ctx context.Context) (factoryDefaultsInfo, error) {
	d, e := kiloFactory()
	if e != nil {
		return factoryDefaultsInfo{}, e
	}
	out := factoryDefaultsInfo{ModelCount: len(d.Models), Source: d.CatalogURL, Observed: d.Observed, Notice: kiloFactoryNotice, Selection: Selection{ProviderID: kiloFactoryID, AccountID: kiloFactoryID, CredentialID: kiloFactoryID, ModelID: d.DefaultModel, Endpoint: "CHAT_COMPLETIONS"}}
	var state string
	e = s.Store.DB.QueryRowContext(ctx, "SELECT state FROM factory_defaults WHERE id=?", kiloFactoryKey).Scan(&state)
	if e != nil && e != sql.ErrNoRows {
		return out, e
	}
	out.Installed = state == "APPLIED"
	return out, nil
}

var errFactoryCollision = errors.New("the bundled profile ID already exists; existing data was kept")

func (s *Server) installKiloFactory(ctx context.Context, onlyEmpty bool) (bool, error) {
	d, e := kiloFactory()
	if e != nil {
		return false, e
	}
	tx, e := s.Store.DB.BeginTx(ctx, nil)
	if e != nil {
		return false, e
	}
	defer tx.Rollback()
	var state string
	e = tx.QueryRowContext(ctx, "SELECT state FROM factory_defaults WHERE id=?", kiloFactoryKey).Scan(&state)
	if e != nil && e != sql.ErrNoRows {
		return false, e
	}
	if state == "APPLIED" || onlyEmpty && state == "AVAILABLE" {
		return false, nil
	}
	var providers int
	if e = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM providers").Scan(&providers); e != nil {
		return false, e
	}
	if onlyEmpty && providers != 0 {
		_, e = tx.ExecContext(ctx, "INSERT INTO factory_defaults VALUES(?,'AVAILABLE',0) ON CONFLICT(id) DO NOTHING", kiloFactoryKey)
		if e != nil {
			return false, e
		}
		return false, tx.Commit()
	}
	var collision int
	if e = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM providers WHERE id=?", kiloFactoryID).Scan(&collision); e != nil {
		return false, e
	}
	if collision != 0 {
		return false, errFactoryCollision
	}
	at := now()
	queries := []struct {
		sql  string
		args []any
	}{
		{"INSERT INTO providers VALUES(?,?,?,?,?,?)", []any{kiloFactoryID, "Kilo · 免Key", kiloFactoryBase, kiloFactoryNotice, "NONE", at}},
		{"INSERT INTO accounts VALUES(?,?,?)", []any{kiloFactoryID, kiloFactoryID, "免Key公開連線"}},
		{"INSERT INTO credentials VALUES(?,?,?,?,?,'INFERENCE',1,?)", []any{kiloFactoryID, kiloFactoryID, kiloFactoryID, "匿名（不使用Key）", "", at}},
		{"INSERT INTO credential_origins VALUES(?,?,?,?,?)", []any{kiloFactoryID, kiloFactoryID, kiloFactoryID, kiloFactoryBase, "NONE"}},
		{"INSERT INTO credential_context VALUES(?,?,?,'',?,0)", []any{kiloFactoryID, kiloFactoryID, kiloFactoryID, kiloFactoryNotice}},
	}
	for _, q := range queries {
		if _, e = tx.ExecContext(ctx, q.sql, q.args...); e != nil {
			return false, e
		}
	}
	cfg := ProviderConfig{Adapter: "GENERIC", AuthMode: "NONE", Website: "https://kilo.ai", Notes: kiloFactoryNotice}
	raw, _ := json.Marshal(cfg)
	if _, e = tx.ExecContext(ctx, "INSERT INTO provider_config VALUES(?,?)", kiloFactoryID, string(raw)); e != nil {
		return false, e
	}
	if e = bindEndpoint(tx, Provider{ID: kiloFactoryID, BaseURL: kiloFactoryBase}, cfg, "CHAT_COMPLETIONS"); e != nil {
		return false, e
	}
	for _, m := range d.Models {
		if _, e = tx.ExecContext(ctx, "INSERT INTO models VALUES(?,?,?,'UNKNOWN','UNKNOWN','BUNDLED_KILO_CATALOG',?)", kiloFactoryID, m.ID, m.Name, at); e != nil {
			return false, e
		}
		meta := ModelMetadata{ProviderID: kiloFactoryID, ModelID: m.ID, CanonicalID: canonicalID(m.ID), Category: "CHAT_REASONING", Source: "BUNDLED_KILO_CATALOG", Capabilities: []string{}, Modalities: []string{}, Endpoints: []string{"CHAT_COMPLETIONS"}, Aliases: []string{}}
		if m.Context > 0 {
			v := m.Context
			meta.ContextTokens = &v
		}
		for _, mode := range m.Architecture.Inputs {
			if oneOf(mode, "text", "image", "audio") {
				meta.Modalities = append(meta.Modalities, strings.ToUpper(mode)+"_INPUT")
			}
			if mode == "image" {
				meta.Capabilities = append(meta.Capabilities, "VISION")
			}
		}
		for _, mode := range m.Architecture.Outputs {
			if mode == "text" {
				meta.Modalities = append(meta.Modalities, "TEXT_OUTPUT")
			}
		}
		for _, cap := range []struct{ parameter, name string }{{"tools", "TOOL_CALLING"}, {"reasoning", "REASONING"}, {"structured_outputs", "STRUCTURED_OUTPUT"}} {
			if oneOf(cap.parameter, m.Parameters...) {
				meta.Capabilities = append(meta.Capabilities, cap.name)
			}
		}
		metaRaw, _ := json.Marshal(meta)
		if _, e = tx.ExecContext(ctx, "INSERT INTO model_metadata VALUES(?,?,?,?)", kiloFactoryID, m.ID, meta.CanonicalID, string(metaRaw)); e != nil {
			return false, e
		}
		if _, e = tx.ExecContext(ctx, "INSERT INTO my_models VALUES(?,?,?,?,'','CHAT_COMPLETIONS',?)", kiloFactoryID, kiloFactoryID, kiloFactoryID, m.ID, at); e != nil {
			return false, e
		}
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO factory_defaults VALUES(?,'APPLIED',?) ON CONFLICT(id) DO UPDATE SET state='APPLIED',applied_at=excluded.applied_at", kiloFactoryKey, at); e != nil {
		return false, e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO action_ledger VALUES(?,'INSTALL_FACTORY_DEFAULTS',?,'{}',?,'CONFIRMED',?)", newID(), kiloFactoryID, `{"source":"USER_AUTHORIZED_FACTORY_DEFAULTS","inference_requests":0}`, at); e != nil {
		return false, e
	}
	return true, tx.Commit()
}
func (s *Server) getFactoryDefaults(w http.ResponseWriter, r *http.Request) {
	out, e := s.factoryDefaultsInfo(r.Context())
	if e != nil {
		http.Error(w, "factory defaults unavailable", 503)
		return
	}
	jsonOK(w, 200, out)
}
func (s *Server) addKiloFactoryDefaults(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Confirm bool `json:"confirm"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !in.Confirm {
		http.Error(w, "explicit default model addition required", 422)
		return
	}
	created, e := s.installKiloFactory(r.Context(), false)
	if e != nil {
		code := 503
		if errors.Is(e, errFactoryCollision) {
			code = 409
		}
		http.Error(w, "Kilo defaults were not changed; existing data was kept", code)
		return
	}
	out, e := s.factoryDefaultsInfo(r.Context())
	if e != nil {
		http.Error(w, "defaults saved; reload their status", 503)
		return
	}
	code := 200
	if created {
		code = 201
	}
	jsonOK(w, code, out)
}

// Only the built-in anonymous connection is constrained by this gate. A
// personal Kilo connection remains an explicit, separately scoped account.
func (s *Server) guardFactoryKilo(ctx context.Context, v Selection, p Provider, cfg ProviderConfig, endpoint, key string) error {
	if v.ProviderID != kiloFactoryID {
		return nil
	}
	if v.AccountID != kiloFactoryID || v.CredentialID != kiloFactoryID || v.Group != "" || p.BaseURL != kiloFactoryBase || cfg.AuthMode != "NONE" || key != "" || len(p.AuthFields) != 0 || len(cfg.Headers) != 0 || endpoint != kiloFactoryBase+"/chat/completions" || v.Endpoint != "CHAT_COMPLETIONS" {
		return &providerReadError{Code: "KILO_DEFAULT_CONNECTION_CHANGED"}
	}
	admissionContext := ctx
	ctx, stop := context.WithTimeout(ctx, 20*time.Second)
	defer stop()
	ctx = context.WithValue(ctx, publicAnonymousOnboardKey{}, true)
	root, _, e := s.getStructured(ctx, p, ProviderConfig{AuthMode: "NONE"}, kiloFactoryBase+"/models", "", 4<<20)
	if e != nil {
		var limit *researchRunBudgetError
		if errors.As(e, &limit) || researchTransportStopped(admissionContext, e) {
			return &conversationAdmissionError{Outcome: "RESEARCH_BUDGET_EXHAUSTED", Status: 429, Cause: e}
		}
		return &providerReadError{Code: "KILO_CATALOG_UNAVAILABLE"}
	}
	items, ok := pointer(root, "/data").([]any)
	if !ok || len(items) > 4096 {
		return &providerReadError{Code: "KILO_CATALOG_UNAVAILABLE"}
	}
	matched := 0
	for _, item := range items {
		if str(pointer(item, "/id")) != v.ModelID {
			continue
		}
		matched++
		free, _ := pointer(item, "/isFree").(bool)
		if !free || pointer(item, "/pricing/prompt") == nil || pointer(item, "/pricing/completion") == nil {
			return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
		}
		prices, ok := pointer(item, "/pricing").(map[string]any)
		if !ok || len(prices) > 16 {
			return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
		}
		for name, value := range prices {
			if name == "discount" {
				continue
			}
			text := numberText(value)
			if len(text) > 80 {
				return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
			}
			amount := decimal(value)
			if amount == nil || amount.Sign() != 0 {
				return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
			}
		}
		if expiry := str(pointer(item, "/expiration_date")); expiry != "" {
			at, err := time.Parse("2006-01-02", expiry)
			if err != nil || !s.scheduleTime().Before(at) {
				return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
			}
		}
	}
	if matched != 1 {
		return &providerReadError{Code: "KILO_FREE_MODEL_UNAVAILABLE"}
	}
	return nil
}
