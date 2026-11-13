package main

import (
	"compress/gzip"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strings"
	"testing"
)

func TestLiteToolCatalogAndMembershipReads(t *testing.T) {
	s := testServer(t)
	primary := seed(t, s, "https://provider.example/v1")
	add := func(v Selection) {
		t.Helper()
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	}
	add(primary)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{
		ID: "other", ProviderID: primary.ProviderID, AccountID: primary.AccountID, Label: "Other", Secret: "other-test-credential",
	}), 201)
	retained := primary
	retained.CredentialID = "other"
	add(retained)
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: primary.ProviderID, ID: "unjoined", DisplayName: "Unjoined"}), 201)

	at := now() - 10000
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := s.Store.DB.ExecContext(t.Context(), query, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec("INSERT INTO membership_state VALUES(?,'HIDDEN',1,1)", scopeKey(retained))
	exec("INSERT INTO entity_notes VALUES('MODEL',?,'scoped note',?)", scopeKey(primary), `["pinned"]`)
	exec(`INSERT INTO knowledge_versions(id,kind,subject,version,data_json,evidence_json,reason,status,created_at)
		VALUES('alias','MODEL_ALIAS_RULE','p/m',1,'{"canonical_id":"preferred"}','{}','fixture','APPROVED',?)`, at)
	exec("INSERT INTO knowledge_active VALUES('MODEL_ALIAS_RULE','p/m','alias')")
	exec("INSERT INTO capability_confirmations VALUES(?,?,'MANUAL',?)", scopeKey(primary), `["CODING"]`, at)
	for _, sample := range []struct {
		id, key, status string
		ttft, latency   int
		details         string
	}{
		{"good", "c", "SUCCESS", 40, 100, `{"tps":30}`},
		{"bad", "c", "UPSTREAM_5XX", -1, 300, `{"tps":"unknown"}`},
		{"cancel", "c", "CANCELLED", 900, 900, `{"tps":900}`},
		{"other", "other", "SUCCESS", 55, 555, `{"tps":555}`},
	} {
		exec(`INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,
			source,outcome,ttft_ms,latency_ms,details_json,created_at)
			VALUES(?,'p','a',?,'m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','MANUAL_TEST',?,?,?,?,?)`,
			sample.id, sample.key, sample.status, sample.ttft, sample.latency, sample.details, at)
	}

	legacyCatalog := gatewayDecode[[]Model](t, req(t, s, "GET", "/api/v1/models", nil), 200)
	s.distribution = true
	const origin = "http://localhost"
	login := httptest.NewRequest("POST", origin+"/login", strings.NewReader(url.Values{"username": {"admin"}, "password": {s.Token}}.Encode()))
	login.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	login.Header.Set("Origin", origin)
	handler := s.Handler()
	auth := httptest.NewRecorder()
	handler.ServeHTTP(auth, login)
	requireCode(t, auth, http.StatusSeeOther)
	cookie := auth.Result().Cookies()[0]
	get := func(path string, authenticated bool) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest("GET", origin+"/tools/api/"+path, nil)
		if authenticated {
			r.AddCookie(cookie)
			r.Header.Set("Accept-Encoding", "gzip")
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	decode := func(path string, into any) {
		t.Helper()
		w := get(path, true)
		requireCode(t, w, 200)
		if w.Header().Get("Content-Encoding") != "gzip" {
			t.Fatal("tool lists should use the negotiated gzip encoding")
		}
		zip, err := gzip.NewReader(w.Body)
		if err != nil {
			t.Fatal(err)
		}
		defer zip.Close()
		if err := json.NewDecoder(zip).Decode(into); err != nil {
			t.Fatal(err)
		}
	}
	requireCode(t, get("model-catalog?memberships=1", false), 401)
	var catalog struct {
		Models      []Model
		Memberships []toolMembership
	}
	decode("model-catalog?memberships=1", &catalog)
	if !reflect.DeepEqual(catalog.Models, legacyCatalog) || len(catalog.Models) != 2 ||
		catalog.Models[0].Metadata.CanonicalID != "preferred" || len(catalog.Memberships) != 2 ||
		catalog.Memberships[0] != (toolMembership{retained, "HIDDEN", true, true}) {
		t.Fatalf("tool catalog disagrees with model definitions or exact membership state: %+v", catalog)
	}
	var rows []toolModelMember
	decode("model-memberships?limit=1&offset=1", &rows)
	if len(rows) != 1 {
		t.Fatalf("membership page size: %d", len(rows))
	}
	row := rows[0]
	if row.Selection != primary || !row.Enabled || row.Metadata.CanonicalID != "preferred" ||
		!reflect.DeepEqual(row.Capabilities, []string{"CODING"}) || row.Performance.Samples != 2 ||
		row.Performance.Success == nil || *row.Performance.Success != .5 ||
		row.Performance.TTFT != float64(40) || row.Performance.TPS != float64(30) || row.Performance.Latency != float64(200) ||
		row.Reliability["1h"].Samples != 2 || row.Reliability["24h"].Samples != 2 ||
		!reflect.DeepEqual(row.Offer["tags"], []any{"pinned"}) {
		t.Fatalf("unexpected scoped ranking, filters or measurements: %+v", row)
	}
	if _, hasNote := row.Offer["notes"]; hasNote {
		t.Fatal("model list returned detail-only notes")
	}
	var detail struct {
		Membership toolSelectedOffer
	}
	q := "model-detail?provider_id=p&account_id=a&credential_id=c&model_id=m&group=&endpoint=CHAT_COMPLETIONS"
	detail = gatewayDecode[struct{ Membership toolSelectedOffer }](t, get(q, true), 200)
	if detail.Membership.Selection != primary || !detail.Membership.Enabled || detail.Membership.Offer["notes"] != "scoped note" {
		t.Fatalf("model detail did not restore the selected offer: %+v", detail)
	}
	requireCode(t, get(strings.Replace(q, "model_id=m&", "model_id=unjoined&", 1), true), 404)
}
