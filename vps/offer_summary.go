package main

import (
	"context"
	"encoding/json"
	"strings"
	"time"
)

type credentialObservationKey struct{}
type credentialObservation struct {
	Provider, Account, Credential string
	Origin                        string
	Version                       int
}

func withCredentialObservation(ctx context.Context, v Selection, p Provider) context.Context {
	return context.WithValue(ctx, credentialObservationKey{}, credentialObservation{Provider: v.ProviderID, Account: v.AccountID, Credential: v.CredentialID, Version: p.CredentialVersion, Origin: p.BaseURL})
}

func (s *Server) setting(ctx context.Context, key string, fallback any) any {
	var raw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT value_json FROM settings WHERE key=?", key).Scan(&raw) != nil {
		return fallback
	}
	var out any
	if json.Unmarshal([]byte(raw), &out) != nil {
		return fallback
	}
	return out
}

func (s *Server) offerSummary(ctx context.Context, v Selection) map[string]any {
	var input offerSummaryInput
	s.Store.DB.QueryRowContext(ctx, credentialVersionQuery, v.ProviderID, v.AccountID, v.CredentialID).Scan(&input.version)
	s.Store.DB.QueryRowContext(ctx, "SELECT created_at FROM my_models WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=?", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint).Scan(&input.created)
	s.Store.DB.QueryRowContext(ctx, "SELECT notes,tags_json FROM entity_notes WHERE subject_type='MODEL' AND subject_id=?", scopeKey(v)).Scan(&input.notes, &input.tags)
	input.groupBound = s.groupBound(ctx, v)
	if input.groupBound {
		input.operation = s.operation(ctx, v)
		if p, err := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID); err == nil {
			input.origin = &p.BaseURL
		}
	}
	out := s.offerScopeSummary(ctx, v, input)
	out["strength"] = s.offerStrength(ctx, v.ModelID)
	out["external_performance"] = s.offerExternal(ctx, s.metadata(ctx, v.ProviderID, v.ModelID).CanonicalID)
	return out
}

const credentialVersionQuery = "SELECT COALESCE(MAX(version),0) FROM credential_secret_versions WHERE provider_id=? AND account_id=? AND credential_id=?"

type offerSummaryInput struct {
	version    int
	created    int64
	notes      string
	tags       string
	groupBound bool
	operation  string
	origin     *string
}

func (s *Server) offerScopeSummary(ctx context.Context, v Selection, input offerSummaryInput) map[string]any {
	out := map[string]any{
		"credential_version": input.version, "price": nil, "effective_price": nil, "quota": nil,
		"provider_performance": nil, "price_changed": false, "created_at": input.created,
		"newly_added": input.created >= now()-int64(7*24*time.Hour/time.Millisecond), "tags": []string{}, "notes": input.notes,
	}
	var tagList []string
	json.Unmarshal([]byte(input.tags), &tagList)
	if tagList != nil {
		out["tags"] = tagList
	}
	if !input.groupBound {
		out["scope_state"] = "GROUP_MISMATCH"
		return out
	}
	rows, e := s.Store.DB.QueryContext(ctx, "SELECT kind,source,facts_json,observed_at FROM observations WHERE scope_key=? AND status='SUCCESS' ORDER BY rowid DESC LIMIT 100", scopeKey(v))
	if e == nil {
		previousPrice := ""
		priceCount := 0
		manualPriceReplaced := false
		for rows.Next() {
			var kind, source, raw string
			var at int64
			if rows.Scan(&kind, &source, &raw, &at) != nil || at < now()-86400000 {
				continue
			}
			var facts map[string]any
			decoder := json.NewDecoder(strings.NewReader(raw))
			decoder.UseNumber()
			if decoder.Decode(&facts) != nil {
				continue
			}
			if n := quotaInteger(facts["credential_version"]); n != nil && int(*n) != input.version || facts["credential_version"] == nil && input.version != 0 {
				continue
			}
			if kind == "MANUAL_PRICE" {
				if manualPriceReplaced {
					continue
				}
				manualPriceReplaced = facts["replaces_manual_price"] == true
			}
			if out["effective_price"] == nil && facts["effective_charge"] != nil && oneOf(kind, "PRICING", "GROUP_PRICING", "MANUAL_PRICE") {
				raw, _ := json.Marshal(facts["effective_charge"])
				var legs PriceLegs
				if json.Unmarshal(raw, &legs) == nil && oneOf(legs.Unit, "USD/1K tokens", "USD/request") && priceState(legs) != "UNKNOWN" {
					out["effective_price"] = map[string]any{"legs": legs, "source": source, "observed_at": at, "basis": "EFFECTIVE"}
				}
			}
			if oneOf(kind, "CATALOG", "PRICING", "GROUP_PRICING") && facts["price"] != nil {
				priceRaw, _ := json.Marshal(facts["price"])
				var legs PriceLegs
				if json.Unmarshal(priceRaw, &legs) == nil && legs.Unit == "USD/1K tokens" {
					if out["price"] == nil {
						out["price"] = map[string]any{"legs": legs, "source": source, "observed_at": at, "basis": "NOMINAL"}
						previousPrice = string(priceRaw)
						priceCount = 1
					} else if priceCount == 1 && source == out["price"].(map[string]any)["source"] {
						out["price_changed"] = previousPrice != string(priceRaw)
						priceCount = 2
					}
				}
			}
			if out["quota"] == nil && oneOf(kind, "QUOTA", "QUOTA_LIMITS", "TOKEN_QUOTA") && (facts["rpd"] != nil || facts["tpd"] != nil) {
				out["quota"] = map[string]any{"rpd": facts["rpd"], "tpd": facts["tpd"], "remaining": facts["remaining"], "source": source, "observed_at": at}
			}
			if out["provider_performance"] == nil && oneOf(kind, "MODEL_PERFORMANCE", "PROVIDER_PERFORMANCE", "PERFORMANCE") {
				out["provider_performance"] = map[string]any{"facts": facts, "source": source, "observed_at": at}
			}
		}
		rows.Close()
	}
	// A nominal group quote stays independent from this key's effective charge.
	if input.origin != nil {
		nominal := v
		nominal.CredentialID = ""
		nominal.Endpoint = input.operation
		for _, quote := range s.observations(ctx, nominal) {
			if quote.Kind == "NOMINAL_PRICE" && quote.Status == "SUCCESS" && str(quote.Facts["origin"]) == *input.origin && quote.ObservedAt >= now()-86400000 {
				out["nominal_price"] = quote
				break
			}
		}
	}
	return out
}

func (s *Server) offerStrength(ctx context.Context, modelID string) any {
	if score, rank, valid := s.aaModelScore(ctx, Candidate{ModelID: modelID}); valid {
		return map[string]any{"score": score, "rank": rank, "source": "ARTIFICIAL_ANALYSIS"}
	}
	return nil
}

func (s *Server) offerExternal(ctx context.Context, canonicalID string) any {
	for _, facts := range s.externalFor(ctx, canonicalID) {
		if at := quotaInteger(facts["observed_at"]); at != nil && *at >= now()-86400000 {
			return facts
		}
	}
	return nil
}

// Configured availability is separate from successful inference history.
func (s *Server) selectionUsable(ctx context.Context, v Selection) bool {
	if !s.validateSelection(ctx, v) || !s.groupBound(ctx, v) {
		return false
	}
	p, e := boundProvider(ctx, s.Store.DB, v.ProviderID, v.AccountID, v.CredentialID)
	if e != nil {
		return false
	}
	return s.credentialUsable(ctx, v, p)
}

func (s *Server) credentialUsable(ctx context.Context, v Selection, p Provider) bool {
	cfg := s.config(ctx, p)
	if cfg.AuthMode == "NONE" {
		return true
	}
	if len(s.Key) != 32 {
		return false
	}
	var encrypted, fields string
	if s.Store.DB.QueryRowContext(ctx, "SELECT c.encrypted_key,COALESCE(f.fields_json,'[]') FROM credentials c LEFT JOIN credential_auth_fields f ON f.provider_id=c.provider_id AND f.account_id=c.account_id AND f.credential_id=c.id WHERE c.provider_id=? AND c.account_id=? AND c.id=?", v.ProviderID, v.AccountID, v.CredentialID).Scan(&encrypted, &fields) != nil {
		return false
	}
	return encrypted != "" || cfg.AuthMode == "CUSTOM_HEADER" && fields != "[]"
}
