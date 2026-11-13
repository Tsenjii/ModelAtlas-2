package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"slices"
)

const gatewayModelQuotaKind = "MANUAL_QUOTA:MODEL:"

// These reads use the same versioned observations as the workspace editor.
// Model terms are scoped to the exact connection. A public quote is eligible
// only for the same origin, account, model, group and operation.
const gatewayExactTerms = `o.scope_key=m.scope_key AND
	COALESCE(json_extract(o.facts_json,'$.credential_version'),0)=m.credential_version AND
	COALESCE(json_extract(o.facts_json,'$.origin'),m.base_url)=m.base_url`

func gatewayTermObservation(where, priority string) string {
	return `(SELECT json_object('id',o.id,'source',o.source,'observed_at',o.observed_at,'facts',json(o.facts_json))
		FROM observations o WHERE o.status='SUCCESS' AND (` + where + `)
		ORDER BY ` + priority + `o.observed_at DESC,o.rowid DESC LIMIT 1)`
}

var gatewayTermColumns = gatewayTermObservation(gatewayExactTerms+` AND o.kind='MANUAL_PRICE' AND o.source='USER'`, "") + "," +
	gatewayTermObservation(gatewayExactTerms+` AND o.kind='`+gatewayModelQuotaKind+`' AND o.source='USER'`, "") + "," +
	gatewayTermObservation(`(`+gatewayExactTerms+` AND o.kind IN ('CATALOG','PRICING','GROUP_PRICING')
		AND (json_extract(o.facts_json,'$.price') IS NOT NULL OR json_extract(o.facts_json,'$.effective_charge') IS NOT NULL))
		OR (o.scope_key=atlas_scope_key(m.provider_id,m.account_id,'',m.model_id,m.group_id,m.operation)
		AND o.kind='NOMINAL_PRICE' AND o.source='NEW_API_OFFICIAL'
		AND json_extract(o.facts_json,'$.origin')=m.base_url AND json_extract(o.facts_json,'$.price') IS NOT NULL)`,
		`json_extract(o.facts_json,'$.effective_charge') IS NULL, o.kind<>'NOMINAL_PRICE', `) + "," +
	gatewayTermObservation(gatewayExactTerms+` AND o.kind IN ('QUOTA','QUOTA_LIMITS','TOKEN_QUOTA')
		AND json_extract(o.facts_json,'$.rpd') IS NOT NULL
		AND COALESCE(json_extract(o.facts_json,'$.scope_kind'),'MODEL') IN ('MODEL','PROJECT_MODEL')`, "")

type gatewayTermObservationValue struct {
	ID         string `json:"id"`
	Source     string `json:"source"`
	ObservedAt int64  `json:"observed_at"`
	Facts      struct {
		Price     *PriceLegs                 `json:"price"`
		Effective *PriceLegs                 `json:"effective_charge"`
		RPD       json.RawMessage            `json:"rpd"`
		Values    map[string]json.RawMessage `json:"values"`
		Note      string                     `json:"note"`
	} `json:"facts"`
}

type gatewayTermRow struct {
	manualPrice, manualQuota, price, quota sql.NullString
}

func (r *gatewayTermRow) scan() []any {
	return []any{&r.manualPrice, &r.manualQuota, &r.price, &r.quota}
}

func readTermObservation(raw sql.NullString) gatewayTermObservationValue {
	var out gatewayTermObservationValue
	if raw.Valid {
		_ = json.Unmarshal([]byte(raw.String), &out)
	}
	return out
}

func termRPD(raw json.RawMessage) *int64 {
	var n json.Number
	if json.Unmarshal(raw, &n) != nil {
		return nil
	}
	return quotaInteger(n)
}

func validTermPrice(price *PriceLegs) bool {
	if price == nil {
		return true
	}
	if !oneOf(price.Unit, "USD/request", "USD/1K tokens") || price.Fixed != nil && price.FixedAbsent {
		return false
	}
	if price.Unit == "USD/request" && (price.Input != nil || price.Output != nil || price.Cache != nil) {
		return false
	}
	for _, leg := range []*string{price.Input, price.Output, price.Cache, price.Fixed} {
		if leg != nil {
			n := decimal(*leg)
			if n == nil || n.Sign() < 0 {
				return false
			}
		}
	}
	return true
}

func knownTermPrice(p *PriceLegs) *PriceLegs {
	if p == nil || !validTermPrice(p) {
		return nil
	}
	if p.Input == nil && p.Output == nil && p.Cache == nil && p.Fixed == nil && !(p.Unit == "USD/request" && p.FixedAbsent) {
		return nil
	}
	return p
}

type gatewayTermPrice struct {
	Legs       *PriceLegs `json:"legs"`
	Basis      string     `json:"basis"`
	Source     string     `json:"source"`
	ObservedAt int64      `json:"observed_at"`
	Free       bool       `json:"free,omitempty"`
}

type gatewayTermQuota struct {
	Value      int64  `json:"value,string"`
	Source     string `json:"source"`
	ObservedAt int64  `json:"observed_at"`
}

type gatewayTerms struct {
	Price *gatewayTermPrice `json:"price,omitempty"`
	Daily *gatewayTermQuota `json:"daily,omitempty"`
}

func (r gatewayTermRow) terms() gatewayTerms {
	out := gatewayTerms{}
	manual, published := readTermObservation(r.manualPrice), readTermObservation(r.price)
	switch {
	case knownTermPrice(manual.Facts.Price) != nil:
		out.Price = &gatewayTermPrice{Legs: manual.Facts.Price, Basis: "MANUAL", Source: manual.Source, ObservedAt: manual.ObservedAt}
	case knownTermPrice(published.Facts.Effective) != nil && priceState(*published.Facts.Effective) != "UNKNOWN":
		out.Price = &gatewayTermPrice{Legs: published.Facts.Effective, Basis: "EFFECTIVE", Source: published.Source, ObservedAt: published.ObservedAt}
	case knownTermPrice(published.Facts.Price) != nil:
		out.Price = &gatewayTermPrice{Legs: published.Facts.Price, Basis: "NOMINAL", Source: published.Source, ObservedAt: published.ObservedAt}
	}
	if out.Price != nil {
		out.Price.Free = out.Price.Basis != "NOMINAL" && priceState(*out.Price.Legs) == "FREE_NOW" &&
			(out.Price.Legs.Cache == nil || decimal(*out.Price.Legs.Cache).Sign() == 0)
	}
	manual, published = readTermObservation(r.manualQuota), readTermObservation(r.quota)
	if n := termRPD(manual.Facts.Values["rpd"]); n != nil {
		out.Daily = &gatewayTermQuota{*n, manual.Source, manual.ObservedAt}
	} else if n := termRPD(published.Facts.RPD); n != nil {
		out.Daily = &gatewayTermQuota{*n, published.Source, published.ObservedAt}
	}
	return out
}

type gatewayGroupTerms struct {
	gatewayTerms
	Connections int  `json:"connections"`
	VariedPrice bool `json:"varied_price,omitempty"`
}

func perRequestTerm(p *gatewayTermPrice) *big.Rat {
	if p.Legs.Unit != "USD/request" {
		return nil
	}
	if p.Legs.Fixed != nil {
		return decimal(*p.Legs.Fixed)
	}
	if p.Legs.FixedAbsent {
		return big.NewRat(0, 1)
	}
	return nil
}

func (g *gatewayGroupTerms) add(terms gatewayTerms) {
	g.Connections++
	if terms.Daily != nil && (g.Daily == nil || terms.Daily.Value > g.Daily.Value) {
		g.Daily = terms.Daily
	}
	if terms.Price == nil || g.Price != nil && g.Price.Free {
		return
	}
	if terms.Price.Free {
		g.Price, g.VariedPrice = terms.Price, false
		return
	}
	if g.VariedPrice {
		return
	}
	if g.Price == nil {
		g.Price = terms.Price
		return
	}
	a, b := perRequestTerm(g.Price), perRequestTerm(terms.Price)
	if a == nil || b == nil || (g.Price.Basis == "NOMINAL") != (terms.Price.Basis == "NOMINAL") {
		g.Price, g.VariedPrice = nil, true
	} else if b.Cmp(a) < 0 {
		g.Price = terms.Price
	}
}

type gatewayTermEditor struct {
	Selection
	ManualPrice *PriceLegs   `json:"manual_price"`
	ManualDaily *int64       `json:"manual_daily,string"`
	Revision    string       `json:"revision"`
	Terms       gatewayTerms `json:"terms"`
}

func gatewayTermEditorRead(ctx context.Context, db queryRows, scope Selection) (gatewayTermEditor, gatewayTermRow, error) {
	var row gatewayTermRow
	var origin string
	var version int
	editor := gatewayTermEditor{Selection: scope}
	err := db.QueryRowContext(ctx, gatewayCatalogSQL+`
		SELECT m.base_url,m.credential_version,`+gatewayTermColumns+`
		FROM scopes m WHERE m.provider_id=? AND m.account_id=? AND m.credential_id=?
		AND m.model_id=? AND m.group_id=? AND m.endpoint=?`,
		scope.ProviderID, scope.AccountID, scope.CredentialID, scope.ModelID, scope.Group, scope.Endpoint).
		Scan(append([]any{&origin, &version}, row.scan()...)...)
	if err != nil {
		return editor, row, err
	}
	price, quota := readTermObservation(row.manualPrice), readTermObservation(row.manualQuota)
	editor.ManualPrice = knownTermPrice(price.Facts.Price)
	editor.ManualDaily = termRPD(quota.Facts.Values["rpd"])
	editor.Terms = row.terms()
	editor.Revision = snapshotHash(map[string]any{
		"scope": scope, "origin": origin, "version": version, "price": price.ID, "quota": quota.ID,
	})
	return editor, row, nil
}

func writeGatewayTermError(w http.ResponseWriter, err error) {
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "membership missing", 404)
	} else {
		http.Error(w, "database error", 500)
	}
}

func (s *Server) gatewayGetOfferTerms(w http.ResponseWriter, r *http.Request) {
	scope := selectionQuery(r)
	if !validScope(scope) || !r.URL.Query().Has("group") {
		http.Error(w, "full selected scope required", 400)
		return
	}
	editor, _, err := gatewayTermEditorRead(r.Context(), s.Store.DB, scope)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	jsonOK(w, 200, editor)
}

func (s *Server) gatewaySaveOfferTerms(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Selection
		Price    *PriceLegs `json:"price"`
		Daily    *int64     `json:"daily,string"`
		Revision string     `json:"revision"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validScope(in.Selection) || !validTermPrice(in.Price) || in.Daily != nil && *in.Daily < 0 {
		http.Error(w, "invalid price or daily request limit", 400)
		return
	}
	in.Price = knownTermPrice(in.Price)
	ctx := r.Context()
	secrets, err := s.knownSecrets(ctx)
	if err != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	raw, _ := json.Marshal(in)
	if encodedJSONContainsSecret(raw, secrets) {
		http.Error(w, "secret in metadata", 422)
		return
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	defer tx.Rollback()
	editor, row, err := gatewayTermEditorRead(ctx, tx, in.Selection)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	if in.Revision != editor.Revision {
		http.Error(w, "model terms changed; reload before saving", 409)
		return
	}
	previousPrice, _ := json.Marshal(editor.ManualPrice)
	nextPrice, _ := json.Marshal(in.Price)
	if !slices.Equal(previousPrice, nextPrice) {
		state := "UNKNOWN"
		if in.Price != nil {
			state = priceState(*in.Price)
		}
		facts := map[string]any{"price": in.Price, "manual_price_state": state, "replaces_manual_price": true,
			"note": readTermObservation(row.manualPrice).Facts.Note}
		if err = writeObservation(ctx, tx, in.Selection, "MANUAL_PRICE", "USER", "SUCCESS", facts); err != nil {
			writeGatewayTermError(w, err)
			return
		}
	}
	if (editor.ManualDaily == nil) != (in.Daily == nil) || editor.ManualDaily != nil && *editor.ManualDaily != *in.Daily {
		quota := readTermObservation(row.manualQuota)
		values := quota.Facts.Values
		if values == nil {
			values = make(map[string]json.RawMessage)
		}
		values["rpd"], _ = json.Marshal(in.Daily)
		facts := map[string]any{"values": values, "scope_kind": "MODEL", "slice": "", "note": quota.Facts.Note}
		if err = writeObservation(ctx, tx, in.Selection, gatewayModelQuotaKind, "USER", "SUCCESS", facts); err != nil {
			writeGatewayTermError(w, err)
			return
		}
	}
	editor, _, err = gatewayTermEditorRead(ctx, tx, in.Selection)
	if err != nil {
		writeGatewayTermError(w, err)
		return
	}
	if err = tx.Commit(); err != nil {
		writeGatewayTermError(w, err)
		return
	}
	jsonOK(w, 200, editor)
}
