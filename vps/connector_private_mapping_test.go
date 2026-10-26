package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

const privateMappingFixture = "{\"scope\":\"MODEL_KEY_GROUP\",\"units\":{\"calls\":\"REQUESTS/day\",\"tokens\":\"TOKENS/day\"},\"entries\":[{\"opaque_model\":\"m\",\"opaque_group\":\"\",\"allowances\":{\"calls\":\"150\",\"tokens\":\"1200000\"},\"mirror\":\"top-secret-example-key\"}]}"
const privateMappingGroupedFixture = "{\"scope\":\"MODEL_KEY_GROUP\",\"units\":{\"calls\":\"REQUESTS/day\",\"tokens\":\"TOKENS/day\"},\"entries\":[{\"opaque_model\":\"m\",\"opaque_group\":\"vip\",\"allowances\":{\"calls\":\"99999\",\"tokens\":\"99999999\"}},{\"opaque_model\":\"m\",\"opaque_group\":\"\",\"allowances\":{\"calls\":\"150\",\"tokens\":\"1200000\"}}]}"
const privateMappingAccountFixture = "{\"scope\":\"ACCOUNT\",\"unit\":\"CREDITS\",\"opaque\":{\"balance\":\"275.25\"},\"mirror\":\"fixture-private-account-pat\"}"

func TestConnectorPrivateMappingExplicitReadKeepsPublicAnonymousAndSecretsOut(t *testing.T) {
	var anonymous, authenticated atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/model-plan" || r.Method != "GET" {
			t.Error("private discovery reached an unselected path or action")
			http.Error(w, "read only", 400)
			return
		}
		if r.Header.Get("Authorization") == "" {
			anonymous.Add(1)
			http.Error(w, "auth required", 401)
			return
		}
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("private discovery borrowed another credential")
			http.Error(w, "wrong credential", 403)
			return
		}
		authenticated.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingFixture))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	public := discoverForTest(t, s, "private-public-control", "/api/model-plan", v)
	if len(public.Sources) != 1 || public.Sources[0].Status != "AUTH_REQUIRED" || anonymous.Load() != 1 || authenticated.Load() != 0 {
		t.Fatal("public discovery must stay anonymous, including after 401")
	}
	w := req(t, s, "POST", "/api/v1/connectors/discover", map[string]any{
		"id": "private-explicit-read", "selection": v, "source_path": "/api/model-plan",
		"credential_role": "INFERENCE", "confirm_private": true,
	})
	requireCode(t, w, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(w.Body.Bytes(), &report) != nil || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" || len(report.DraftIDs) != 0 || report.HTTPRequests == nil || *report.HTTPRequests != 1 {
		t.Fatalf("explicit private source did not retain a reviewable sample: %s", w.Body.String())
	}
	view := req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil)
	requireCode(t, view, 200)
	if strings.Contains(view.Body.String(), "top-secret-example-key") || strings.Contains(w.Body.String(), "top-secret-example-key") {
		t.Fatal("selected credential echo entered a sample or report")
	}
	var stored string
	if e := s.Store.DB.QueryRow("SELECT root_json FROM connector_mapping_samples WHERE id=?", report.Sources[0].MappingSampleID).Scan(&stored); e != nil || strings.Contains(stored, "top-secret-example-key") {
		t.Fatal("private sample persisted a raw secret", e)
	}
	if anonymous.Load() != 1 || authenticated.Load() != 1 {
		t.Fatal("private read sprayed credentials or fetched another path")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestConnectorPrivateMappingKeyGroupReviewRealUseAndUndo(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/model-plan" {
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		if r.Method != "GET" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("private rule used wrong method/authority")
			http.Error(w, "forbidden", 403)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingGroupedFixture))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	r := req(t, s, "POST", "/api/v1/connectors/discover", map[string]any{"id": "private-flow", "selection": v, "source_path": "/api/model-plan", "credential_role": "INFERENCE", "confirm_private": true})
	requireCode(t, r, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(r.Body.Bytes(), &report) != nil || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" {
		t.Fatal("missing private sample")
	}
	request := connectorMappingRequest{
		ID: "private-reviewed-fields", SampleID: report.Sources[0].MappingSampleID, Kind: "QUOTA",
		Root: "/entries", ModelPath: "/opaque_model", GroupPath: "/opaque_group",
		Scope: mappingLocation{Path: "/scope", Source: true},
		Fields: map[string]string{"rpd": "/allowances/calls", "tpd": "/allowances/tokens"},
		Units: map[string]mappingLocation{"rpd": {Path: "/units/calls", Source: true}, "tpd": {Path: "/units/tokens", Source: true}},
	}
	id := mappingTestPropose(t, s, request)
	draft := discoveredVersion(t, s, id)
	if calls.Load() != 1 || draft.Config.Role != "INFERENCE" || draft.Config.Scope != "MODEL_KEY_GROUP" || draft.Config.Fields["group"] != "/opaque_group" || draft.Config.Effective {
		t.Fatalf("private authority/scope lost: %+v", draft.Config)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	draft = discoveredVersion(t, s, id)
	if draft.Status != "VALIDATED" || calls.Load() != 2 || numberText(draft.Test["http_requests"]) != "1" {
		t.Fatalf("private real HTTP/fixture test did not pass: %+v", draft.Test)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	if calls.Load() != 3 {
		t.Fatal("formal intelligence did not use the one selected private reader")
	}
	var facts string
	if e := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='QUOTA' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id).Scan(&facts); e != nil {
		t.Fatal(e)
	}
	var got map[string]any
	if json.Unmarshal([]byte(facts), &got) != nil || got["rpd"] != "150" || got["tpd"] != "1200000" || got["scope_kind"] != "MODEL_KEY_GROUP" {
		t.Fatal("another group's allowance was borrowed:", facts)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	draft = discoveredVersion(t, s, id)
	if draft.ActivationActionID == "" {
		t.Fatal("private activation lost durable Undo")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": draft.ActivationActionID, "confirm": true}), 200)
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active WHERE version_id=?", id)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND kind='QUOTA' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
}

func TestConnectorPrivateMappingPATKeepsAccountAuthorityAndExactCredits(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/user/self" {
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		if r.Method != "GET" || r.Header.Get("Authorization") != "Bearer fixture-private-account-pat" {
			t.Error("account metadata borrowed inference credentials")
			http.Error(w, "forbidden", 403)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingAccountFixture))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Label: "Account PAT", Role: "ACCOUNT_PAT", Secret: "fixture-private-account-pat"}), 201)
	r := req(t, s, "POST", "/api/v1/connectors/discover", map[string]any{"id": "private-account", "selection": v, "source_path": "/api/user/self", "credential_role": "ACCOUNT_PAT", "pat_credential_id": "pat", "confirm_private": true})
	requireCode(t, r, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(r.Body.Bytes(), &report) != nil || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" {
		t.Fatal("missing private account sample")
	}
	request := connectorMappingRequest{
		ID: "private-account-fields", SampleID: report.Sources[0].MappingSampleID, Kind: "BALANCE",
		Scope: mappingLocation{Path: "/scope", Source: true},
		Fields: map[string]string{"balance": "/opaque/balance"},
		Units: map[string]mappingLocation{"balance": {Path: "/unit", Source: true}},
	}
	id := mappingTestPropose(t, s, request)
	draft := discoveredVersion(t, s, id)
	if draft.Config.Role != "ACCOUNT_PAT" || draft.Config.PATID != "pat" || draft.Config.Scope != "ACCOUNT" || draft.Config.Unit != "CREDITS" || calls.Load() != 1 {
		t.Fatalf("private account facts were widened or converted: %+v", draft.Config)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	draft = discoveredVersion(t, s, id)
	if draft.Status != "VALIDATED" || calls.Load() != 2 {
		t.Fatalf("PAT real test failed: %+v", draft.Test)
	}
	encoded, _ := json.Marshal(draft)
	if strings.Contains(string(encoded), "fixture-private-account-pat") || strings.Contains(string(encoded), "top-secret-example-key") {
		t.Fatal("private source included a raw credential")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	if calls.Load() != 3 {
		t.Fatal("account reader was not used by formal intelligence")
	}
	var facts string
	if e := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='BALANCE' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id).Scan(&facts); e != nil {
		t.Fatal(e)
	}
	var got map[string]any
	if json.Unmarshal([]byte(facts), &got) != nil || got["balance"] != "275.25" || got["unit"] != "CREDITS" || got["scope_kind"] != "ACCOUNT" || got["effective_charge"] != nil {
		t.Fatal("private account credits were rounded, widened or treated as effective USD", facts)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func privateMappingRead(t *testing.T, s *Server, v Selection, id string) connectorDiscoveryReport {
	t.Helper()
	w := req(t, s, "POST", "/api/v1/connectors/discover", connectorDiscoveryRequest{ID: id, Selection: v, SourcePath: "/api/model-plan", CredentialRole: "INFERENCE", ConfirmPrivate: true})
	requireCode(t, w, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(w.Body.Bytes(), &report) != nil {
		t.Fatal("invalid private source report")
	}
	return report
}

func privateMappingRequest(report connectorDiscoveryReport, id string) connectorMappingRequest {
	return connectorMappingRequest{
		ID: id, SampleID: report.Sources[0].MappingSampleID, Kind: "QUOTA",
		Root: "/entries", ModelPath: "/opaque_model", GroupPath: "/opaque_group",
		Scope: mappingLocation{Path: "/scope", Source: true},
		Fields: map[string]string{"rpd": "/allowances/calls", "tpd": "/allowances/tokens"},
		Units: map[string]mappingLocation{"rpd": {Path: "/units/calls", Source: true}, "tpd": {Path: "/units/tokens", Source: true}},
	}
}

func TestConnectorPrivateMappingRejectsUnconfirmedAuthorityBeforeHTTP(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Error(w, "unexpected network", 500)
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Label: "Account PAT", Role: "ACCOUNT_PAT", Secret: "fixture-private-account-pat"}), 201)
	scenarios := []string{"unconfirmed", "role", "inference-pat", "missing-pat", "inference-as-pat", "root", "action", "references", "secret-path", "not-member"}
	for _, scenario := range scenarios {
		t.Run(scenario, func(t *testing.T) {
			request := connectorDiscoveryRequest{ID: "invalid-" + scenario, Selection: v, SourcePath: "/api/model-plan", CredentialRole: "INFERENCE", ConfirmPrivate: true}
			switch scenario {
			case "unconfirmed":
				request.ConfirmPrivate = false
			case "role":
				request.CredentialRole = "COOKIE"
			case "inference-pat":
				request.PATID = "pat"
			case "missing-pat":
				request.CredentialRole = "ACCOUNT_PAT"
			case "inference-as-pat":
				request.CredentialRole, request.PATID = "ACCOUNT_PAT", "c"
			case "root":
				request.SourcePath = "/"
			case "action":
				request.SourcePath = "/api/models/delete"
			case "references":
				request.ReferenceURLs = []string{"https://raw.githubusercontent.com/example/project/main/metadata.json"}
			case "secret-path":
				request.SourcePath = "/api/model-plan?key=top-secret-example-key"
			case "not-member":
				request.Selection.ModelID = "catalog-only"
			}
			_, _, e := s.generateConnectorDrafts(context.Background(), request, 6)
			if e == nil || calls.Load() != 0 {
				t.Fatal("unconfirmed, foreign or unsafe authority reached upstream", e, calls.Load())
			}
		})
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_discoveries")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_samples")
}

func TestConnectorPrivateMappingAssistantCannotAuthorizeItsOwnRead(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	request := connectorDiscoveryRequest{ID: "assistant-private", Selection: v, SourcePath: "/api/model-plan", CredentialRole: "INFERENCE", ConfirmPrivate: true}
	raw, _ := json.Marshal(request)
	var call ToolCall
	call.Function.Name, call.Function.Arguments = "discover_connectors", string(raw)
	budget := &assistantBudget{}
	if _, e := s.assistantTool(context.Background(), "", call, budget); e == nil || calls.Load() != 0 || budget.web != 0 {
		t.Fatal("model self-authorization spent network or acquired private data")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_discoveries")
}

func TestConnectorPrivateMappingFailuresDoNotFollowRedirectOrTryOtherKeys(t *testing.T) {
	var calls atomic.Int32
	var mode atomic.Value
	mode.Store("auth")
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/api/model-plan" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("private failure followed another origin/path or credential")
		}
		w.Header().Set("Content-Type", "application/json")
		switch mode.Load().(string) {
		case "auth":
			http.Error(w, "fixture-private-account-pat", 401)
		case "forbidden":
			http.Error(w, "top-secret-example-key", 403)
		case "redirect":
			http.Redirect(w, r, "https://foreign.example/api/model-plan", 302)
		case "malformed":
			w.Write([]byte("{top-secret-example-key"))
		case "oversize":
			w.Write([]byte(strings.Repeat(" ", (1<<20)+1)))
		case "trailing":
			w.Write([]byte(privateMappingFixture + "{}"))
		}
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "pat", ProviderID: "p", AccountID: "a", Label: "Other PAT", Role: "ACCOUNT_PAT", Secret: "fixture-private-account-pat"}), 201)
	for index, scenario := range []string{"auth", "forbidden", "redirect", "malformed", "oversize", "trailing"} {
		t.Run(scenario, func(t *testing.T) {
			mode.Store(scenario)
			report := privateMappingRead(t, s, v, "private-failed-"+scenario)
			if len(report.Sources) != 1 || report.Sources[0].MappingSampleID != "" || len(report.DraftIDs) != 0 || report.HTTPRequests == nil || *report.HTTPRequests != 1 || calls.Load() != int32(index+1) {
				t.Fatal("failed source claimed data or spent extra HTTP", report)
			}
			if oneOf(scenario, "auth", "forbidden") && report.Sources[0].Status != "AUTH_REQUIRED" {
				t.Fatal("denied authority lost actual auth failure")
			}
			raw, _ := json.Marshal(report)
			if strings.Contains(string(raw), "top-secret-example-key") || strings.Contains(string(raw), "fixture-private-account-pat") {
				t.Fatal("private error body entered history")
			}
		})
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_samples")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
}

func TestConnectorPrivateMappingLateRotationAndDurableReplayKeepNoSample(t *testing.T) {
	var server *Server
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if _, e := server.Store.DB.Exec("UPDATE credential_context SET group_id='changed' WHERE credential_id='c'"); e != nil {
			t.Error(e)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingFixture))
	})
	server = s
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	request := connectorDiscoveryRequest{ID: "private-late", Selection: v, SourcePath: "/api/model-plan", CredentialRole: "INFERENCE", ConfirmPrivate: true}
	report, created, e := s.generateConnectorDrafts(context.Background(), request, 6)
	if e != nil || !created || report.Status != "SCOPE_CHANGED" || calls.Load() != 1 {
		t.Fatal("late rotation did not fail closed", e, report)
	}
	for _, source := range report.Sources {
		if source.MappingSampleID != "" {
			t.Fatal("late rotation advertised an uncommitted sample")
		}
	}
	s = reopenScheduleStore(t, s)
	again, created, e := s.generateConnectorDrafts(context.Background(), request, 6)
	if e != nil || created || connectorHash(again) != connectorHash(report) || calls.Load() != 1 {
		t.Fatal("same-ID recovery after reopen repeated a private read", e)
	}
	request.SourcePath = "/api/pricing"
	if _, _, e = s.generateConnectorDrafts(context.Background(), request, 6); e == nil || calls.Load() != 1 {
		t.Fatal("request identity was reused for a different private read")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_samples")
}

func TestConnectorPrivateMappingStorageFailureRetainsUnknownReservation(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingFixture))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	if _, e := s.Store.DB.Exec("CREATE TRIGGER private_sample_fail BEFORE INSERT ON connector_mapping_samples BEGIN SELECT RAISE(ABORT,'synthetic sample persistence failure'); END"); e != nil {
		t.Fatal(e)
	}
	request := connectorDiscoveryRequest{ID: "private-atomic", Selection: v, SourcePath: "/api/model-plan", CredentialRole: "INFERENCE", ConfirmPrivate: true}
	report, _, e := s.generateConnectorDrafts(context.Background(), request, 6)
	if e == nil || calls.Load() != 1 {
		t.Fatal("sample commit fault was hidden", e)
	}
	for _, source := range report.Sources {
		if source.MappingSampleID != "" {
			t.Fatal("failed sample commit advertised a usable source")
		}
	}
	again, created, e := s.generateConnectorDrafts(context.Background(), request, 6)
	if e != nil || created || again.Status != "RUNNING" || again.HTTPRequests != nil || len(again.Sources) != 0 || calls.Load() != 1 {
		t.Fatal("unknown saved reservation became zero/success or repeated HTTP", e, again)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_samples")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestConnectorPrivateMappingScopeUnitGroupDriftRetainsLastGood(t *testing.T) {
	var body atomic.Value
	body.Store(privateMappingGroupedFixture)
	var calls, catalogReads atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			catalogReads.Add(1)
			if r.Method != "GET" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" || r.Header.Get("Cookie") != "" {
				t.Error("formal catalog refresh used an unexpected authority")
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte("{\"data\":[{\"id\":\"m\"},{\"id\":\"catalog-only\"}]}"))
			return
		}
		if r.URL.Path != "/api/model-plan" {
			t.Error("unexpected private metadata path")
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(body.Load().(string)))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := privateMappingRead(t, s, v, "private-drift")
	request := privateMappingRequest(report, "private-drift-map")
	id := mappingTestPropose(t, s, request)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	var original string
	if e := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='QUOTA' AND status='SUCCESS' ORDER BY observed_at DESC LIMIT 1", "DECLARATIVE_PROVIDER_META:"+id).Scan(&original); e != nil {
		t.Fatal(e)
	}
	for _, scenario := range []string{"scope", "unit", "group", "duplicate"} {
		t.Run(scenario, func(t *testing.T) {
			changed := privateMappingGroupedFixture
			switch scenario {
			case "scope":
				changed = strings.Replace(changed, "MODEL_KEY_GROUP", "ACCOUNT", 1)
			case "unit":
				changed = strings.Replace(changed, "REQUESTS/day", "REQUESTS/minute", 1)
			case "group":
				changed = strings.Replace(changed, "\"opaque_group\":\"\"", "\"opaque_group\":\"other\"", 1)
			case "duplicate":
				changed = strings.Replace(changed, "\"opaque_group\":\"vip\"", "\"opaque_group\":\"\"", 1)
			}
			body.Store(changed)
			requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
			var current string
			if e := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='QUOTA' AND status='SUCCESS' ORDER BY observed_at DESC LIMIT 1", "DECLARATIVE_PROVIDER_META:"+id).Scan(&current); e != nil || current != original {
				t.Fatal("drift overwrote the original exact private fact", e, current)
			}
		})
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND kind='QUOTA' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM observations WHERE source=? AND kind='QUOTA' AND status!='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	if calls.Load() != 7 {
		t.Fatal("private drift triggered retries or additional keys", calls.Load())
	}
	if catalogReads.Load() != 5 {
		t.Fatal("formal catalog refresh count changed", catalogReads.Load())
	}
}

func TestConnectorPrivateMappingOtherKeyCannotBorrowRuntimeOrActiveSlot(t *testing.T) {
	var calls, catalogReads atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			catalogReads.Add(1)
			if r.Method != "GET" || r.Header.Get("Cookie") != "" || (r.Header.Get("Authorization") != "Bearer top-secret-example-key" && r.Header.Get("Authorization") != "Bearer fixture-private-key-two") {
				t.Error("formal catalog refresh used an unexpected authority")
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte("{\"data\":[{\"id\":\"m\"},{\"id\":\"catalog-only\"}]}"))
			return
		}
		if r.URL.Path != "/api/model-plan" {
			t.Error("unexpected private metadata path")
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" && r.Header.Get("Authorization") != "Bearer fixture-private-key-two" {
			t.Error("unexpected private authority")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(privateMappingFixture))
	})
	v := seed(t, s, "https://private.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "key-two", ProviderID: "p", AccountID: "a", Label: "Other Key", Role: "INFERENCE", Secret: "fixture-private-key-two"}), 201)
	other := v
	other.CredentialID = "key-two"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", other), 201)
	first := privateMappingRead(t, s, v, "private-key-one")
	id := mappingTestPropose(t, s, privateMappingRequest(first, "private-map-one"))
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	if calls.Load() != 2 {
		t.Fatal("unexpected first authority reads")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", other), 200)
	if calls.Load() != 2 {
		t.Fatal("formal runtime used an unreviewed second Key")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	second := privateMappingRead(t, s, other, "private-key-two")
	secondID := mappingTestPropose(t, s, privateMappingRequest(second, "private-map-two"))
	if discoveredVersion(t, s, id).Name == discoveredVersion(t, s, secondID).Name {
		t.Fatal("two private authorities shared the active version slot")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": secondID, "selection": other}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: secondID, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", other), 200)
	if calls.Load() != 6 {
		t.Fatal("coexisting private readers executed outside their own authority", calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+secondID)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	if catalogReads.Load() != 3 {
		t.Fatal("formal catalog refresh count changed", catalogReads.Load())
	}
}
