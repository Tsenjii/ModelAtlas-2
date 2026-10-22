package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestConnectorDiscoveryCreatesEvidenceBackedDraftFromPublicSite(t *testing.T) {
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if (r.URL.Path == "/" || r.URL.Path == "/api/metadata") && (r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "") {
			t.Error("public discovery forwarded credentials")
		}
		if r.URL.Path == "/" {
			w.Write([]byte(`<a href="/api/metadata">Structured metadata</a>`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","input":"0","output":"2"}}]}`))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	r := req(t, s, "POST", "/api/v1/connectors/discover", map[string]any{"id": "discovery-first", "selection": v, "source_path": "/"})
	requireCode(t, r, 201)
	var report connectorDiscoveryReport
	json.Unmarshal(r.Body.Bytes(), &report)
	if report.Status != "DRAFTS_READY" || (report.HTTPRequests == nil || *report.HTTPRequests != 2) || len(report.DraftIDs) != 1 || len(report.Unknown) < 5 || report.Family != "UNKNOWN" {
		t.Fatalf("untruthful discovery: %s", r.Body.String())
	}
	draft := discoveredVersion(t, s, report.DraftIDs[0])
	if draft.Active || draft.Status != "DRAFT" || draft.Discovery == nil || draft.Discovery.FixtureStatus != "PASSED" || len(draft.Discovery.SourceHash) != 64 || draft.Config.FieldUnits["input"] != "USD/1M tokens" || draft.Config.Effective || draft.Config.FixedAbsent {
		t.Fatalf("unreviewed/ambiguous draft: %+v", draft)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: draft.ID, Confirm: true}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": draft.ID, "selection": v}), 200)
	draft = discoveredVersion(t, s, draft.ID)
	if draft.Status != "VALIDATED" {
		var raw string
		s.Store.DB.QueryRow("SELECT fixture_json FROM connector_generated_versions WHERE version_id=?", draft.ID).Scan(&raw)
		var fixture connectorFixture
		json.Unmarshal([]byte(raw), &fixture)
		got, err := extractConnector(draft.Config, v, fixture.Rows)
		a, _ := json.Marshal(got)
		b, _ := json.Marshal(fixture.Expected)
		t.Logf("fixture replay err=%v got=%s want=%s rows=%v", err, a, b, fixture.Rows)
		t.Fatalf("real test did not validate: %+v", draft.Test)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: draft.ID, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	var raw string
	if err := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='PRICING' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+draft.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var facts map[string]any
	json.Unmarshal([]byte(raw), &facts)
	price := facts["price"].(map[string]any)
	if price["input"] != "0" || price["output"] != "0.002" || facts["effective_charge"] != nil || facts["valid_until"] == nil {
		t.Fatal("price conversion, Unknown or freshness lost: " + raw)
	}
	s = reopenScheduleStore(t, s)
	draft = discoveredVersion(t, s, draft.ID)
	if draft.ActivationActionID == "" || !draft.Active {
		t.Fatal("activation Undo did not survive reopening")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": draft.ActivationActionID, "confirm": true}), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
}

func discoveredVersion(t *testing.T, s *Server, id string) ConnectorVersion {
	t.Helper()
	r := req(t, s, "GET", "/api/v1/connectors", nil)
	requireCode(t, r, 200)
	var versions []ConnectorVersion
	if json.Unmarshal(r.Body.Bytes(), &versions) != nil {
		t.Fatal("invalid connector list")
	}
	for _, v := range versions {
		if v.ID == id {
			return v
		}
	}
	t.Fatal("draft missing")
	return ConnectorVersion{}
}
func discoverForTest(t *testing.T, s *Server, id, path string, v Selection) connectorDiscoveryReport {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/connectors/discover", connectorDiscoveryRequest{ID: id, SourcePath: path, Selection: v})
	requireCode(t, r, 201)
	var report connectorDiscoveryReport
	if json.Unmarshal(r.Body.Bytes(), &report) != nil {
		t.Fatal("invalid report")
	}
	return report
}

const connectorPriceFixture = `{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/1M tokens","currency":"USD","input":"1","output":"2"}}]}`

func TestConnectorDiscoveryRequestIDIsDurableAndDoesNotRepeatHTTP(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "same-id", "/api/pricing", v)
	s = reopenScheduleStore(t, s)
	in := connectorDiscoveryRequest{ID: "same-id", Selection: v, SourcePath: "/api/pricing"}
	r := req(t, s, "POST", "/api/v1/connectors/discover", in)
	requireCode(t, r, 200)
	if calls.Load() != 1 || !strings.Contains(r.Body.String(), report.DraftIDs[0]) {
		t.Fatal("durable retry reissued HTTP or lost report")
	}
	in.SourcePath = "/api/models"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/discover", in), 409)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
}
func TestConnectorDiscoveryMissingAmbiguousUnitsAndScopesRemainUnknown(t *testing.T) {
	for name, body := range map[string]string{
		"no-scope":          `{"data":[{"model_id":"m","pricing":{"unit":"USD/token","input":"0"}}]}`,
		"no-unit":           `{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"input":"0"}}]}`,
		"currency-conflict": `{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/token","currency":"CNY","input":"0"}}]}`,
		"account":           `{"data":[{"model_id":"m","scope":"ACCOUNT","quota":{"rpd":100,"tpd":1000000}}]}`,
		"wrong-group":       `{"data":[{"model_id":"m","scope":"MODEL_KEY_GROUP","group":"other","quota":{"rpd":100}}]}`,
		"invalid-numeric":   `{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","quota":{"rpd":1.5,"tpd":-1}}]}`,
	} {
		t.Run(name, func(t *testing.T) {
			s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(body))
			})
			v := seed(t, s, "https://unknown.example/v1")
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
			report := discoverForTest(t, s, "unknown", "/api/metadata", v)
			if len(report.DraftIDs) != 0 || report.Status != "NO_SUPPORTED_MAPPING" || len(report.Unknown) == 0 {
				t.Fatalf("guessed unknown claim: %+v", report)
			}
		})
	}
}
func TestConnectorDiscoveryShapeOrUnitChangesKeepLastGoodAndCannotActivate(t *testing.T) {
	var mode atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		body := connectorPriceFixture
		switch mode.Load() {
		case 1:
			body = strings.Replace(body, "USD/1M tokens", "USD/token", 1)
		case 2:
			body = strings.Replace(body, `,"output":"2"`, "", 1)
		case 3:
			http.Error(w, "private error body", 401)
			return
		}
		w.Write([]byte(body))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "shape", "/api/pricing", v)
	id := report.DraftIDs[0]
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	for _, m := range []int32{1, 2, 3} {
		mode.Store(m)
		requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
		r := req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v})
		requireCode(t, r, 200)
		if discoveredVersion(t, s, id).Status != "FAILED" {
			t.Fatal("changed shape/unit/auth passed")
		}
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM observations WHERE source=? AND status='FAILED'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM connector_test_history WHERE version_id=?", id)
}
func TestConnectorDiscoveryFixtureAndCredentialChangesBlockBeforeHTTP(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "fixture", "/api/pricing", v)
	id := report.DraftIDs[0]
	s.Store.DB.Exec("UPDATE connector_generated_versions SET fixture_json=json_set(fixture_json,'$.expected.input','999') WHERE version_id=?", id)
	r := req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v})
	requireCode(t, r, 200)
	if calls.Load() != 1 || discoveredVersion(t, s, id).Status != "FAILED" {
		t.Fatal("tampered fixture reached HTTP")
	}
	other := discoverForTest(t, s, "new-fixture", "/api/pricing", v).DraftIDs[0]
	changed, err := s.encrypt("rotated-private-example")
	if err != nil {
		t.Fatal(err)
	}
	s.Store.DB.Exec("UPDATE credentials SET encrypted_key=? WHERE id='c'", changed)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": other, "selection": v}), 200)
	if calls.Load() != 2 || discoveredVersion(t, s, other).Status != "FAILED" {
		t.Fatal("stale fixture reused after key change")
	}
}
func TestConnectorDiscoveryDoesNotCommitLateChangedScope(t *testing.T) {
	var server *Server
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		server.Store.DB.Exec("UPDATE credential_context SET group_id='changed' WHERE credential_id='c'")
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	server = s
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "late", "/api/pricing", v)
	if report.Status != "SCOPE_CHANGED" || len(report.DraftIDs) != 0 {
		t.Fatal("late changed scope committed")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}
func TestConnectorDiscoveryBoundedPathsOriginsRedirectAuthAndSecrets(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Fatal("credentials leaked")
		}
		if r.URL.Path == "/" {
			w.Write([]byte(`<a href="https://evil.example/api/pricing">other</a><a href="/api/models/delete">delete</a><a href="/api/token">token</a><a href="/api/pricing?key=forbidden">query</a><a href="/api/redirect-metadata">redirect</a><a href="/api/quota">quota</a><a href="/api/status">status</a><a href="/api/usage">usage</a><a href="/api/balance">balance</a><a href="/api/notices">notices</a>`))
			return
		}
		if r.URL.Path == "/api/redirect-metadata" {
			http.Redirect(w, r, "https://evil.example/api/pricing", 302)
			return
		}
		if r.URL.Path == "/api/quota" {
			http.Error(w, "private error body", 403)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","quota":{"rpd":100,"tpd":1000000},"api_key":"sk-abcdefghijklmnopqrstuv","unmapped":"top-secret-example-key"}]}`))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "bounded", "/", v)
	if calls.Load() != 6 || (report.HTTPRequests == nil || *report.HTTPRequests != 6) || report.Attempts != 6 || len(report.Sources) != 6 {
		t.Fatalf("unbounded: %+v calls=%d", report, calls.Load())
	}
	for _, source := range report.Sources {
		if strings.Contains(source.URL, "evil") || strings.Contains(source.URL, "delete") || strings.Contains(source.URL, "token") {
			t.Fatalf("unsafe path read: %+v", source)
		}
	}
	raw := req(t, s, "GET", "/api/v1/connectors", nil).Body.String() + req(t, s, "GET", "/api/v1/connectors/discoveries", nil).Body.String()
	var fixtures string
	rows, _ := s.Store.DB.Query("SELECT fixture_json FROM connector_generated_versions")
	for rows.Next() {
		var f string
		rows.Scan(&f)
		fixtures += f
	}
	rows.Close()
	for _, secret := range []string{"sk-abcdefghijklmnopqrstuv", "top-secret-example-key", "private error body"} {
		if strings.Contains(raw+fixtures, secret) {
			t.Fatal("remote/private payload persisted")
		}
	}
	for i, path := range []string{"https://else.example/api/models", "//evil.example/api/models", "/api/models/%64elete", "/api/keys", "/logout", "/api/pricing?x=1"} {
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/discover", connectorDiscoveryRequest{ID: fmt.Sprint("bad-", i), Selection: v, SourcePath: path}), 409)
	}
}
func TestConnectorDiscoverySourceConflictsAndNewVersionsDoNotReplaceActive(t *testing.T) {
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			w.Write([]byte(`<a href="/api/pricing">one</a><a href="/api/pricing-alt">two</a>`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		body := connectorPriceFixture
		if strings.HasSuffix(r.URL.Path, "alt") {
			body = strings.Replace(body, `"input":"1"`, `"input":"7"`, 1)
		}
		w.Write([]byte(body))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "conflict", "/", v)
	if len(report.DraftIDs) != 2 || len(report.Conflicts) != 1 || report.Conflicts[0] != "PRICING" {
		t.Fatal("conflicting sources lost")
	}
	id := report.DraftIDs[0]
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	again := discoverForTest(t, s, "new-version", "/api/pricing", v)
	if discoveredVersion(t, s, again.DraftIDs[0]).Version != 2 || !discoveredVersion(t, s, id).Active {
		t.Fatal("proposal replaced active or failed to version")
	}
}
func TestConnectorDiscoveryRootScopeFixtureAndExplicitMetricUnits(t *testing.T) {
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"scope":"MODEL_PROVIDER","data":[{"model_id":"m","quota":{"rpd":0,"tpd":1000000,"reset_at":123,"reset_at_ms":1000},"performance":{"tps":"4.5","success_rate":99},"notice":"Public provider notice"}]}`))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "root-scope", "/api/metadata", v)
	if len(report.DraftIDs) != 3 {
		t.Fatalf("expected quota/performance/notice: %+v", report)
	}
	for _, id := range report.DraftIDs {
		draft := discoveredVersion(t, s, id)
		if draft.Config.RootChecks["/scope"] != "MODEL_PROVIDER" {
			t.Fatal("root scope not guarded")
		}
		if draft.Config.Fields["success_rate"] != "" {
			t.Fatal("percent silently converted to ratio")
		}
		if draft.Config.Kind == "QUOTA" && (draft.Config.FieldUnits["rpd"] != "REQUESTS/day" || draft.Config.FieldUnits["tpd"] != "TOKENS/day" || draft.Config.Fields["reset_at"] != "/quota/reset_at_ms") {
			t.Fatal("metric units mixed")
		}
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
		if discoveredVersion(t, s, id).Status != "VALIDATED" {
			t.Fatalf("root fixture replay failed: %+v", discoveredVersion(t, s, id).Test)
		}
	}
}
func TestConnectorDiscoveryAtomicDraftsAndFixtureFailure(t *testing.T) {
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	s.Store.DB.Exec(`CREATE TRIGGER fail_generated_fixture BEFORE INSERT ON connector_generated_versions BEGIN SELECT RAISE(ABORT,'fixture fail'); END`)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/discover", connectorDiscoveryRequest{ID: "atomic", SourcePath: "/api/pricing", Selection: v}), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_generated_versions")
	var raw string
	s.Store.DB.QueryRow("SELECT report_json FROM connector_discoveries WHERE id='atomic'").Scan(&raw)
	if !strings.Contains(raw, `"status":"RUNNING"`) {
		t.Fatal("failed atomic commit claimed completion")
	}
}
func TestConnectorDiscoverySchema24UpgradeAndSyncDoNotCopyExecutionAuthority(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://unknown.example/v1")
	for _, table := range []string{"connector_generated_versions", "connector_test_receipts", "connector_execution_bindings", "connector_discoveries"} {
		if oneOf(table, workspaceTables...) {
			t.Fatal("local fixture/request authority included in sync")
		}
		s.Store.DB.Exec("DROP TABLE " + table)
	}
	legacy := ConnectorConfig{Kind: "QUOTA", Path: "/api/quota", Role: "NONE", Scope: "MODEL_PROVIDER", Root: "/data", Unit: "REQUESTS", Fields: map[string]string{"model_id": "/model_id", "rpd": "/rpd"}}
	legacyRaw, _ := json.Marshal(legacy)
	s.Store.DB.Exec("INSERT INTO connector_versions(id,provider_id,name,version,config_json,evidence_json,reason,status,created_at) VALUES('legacy-local','p','legacy',1,?,'[]','Existing local activation','VALIDATED',?)", string(legacyRaw), now())
	s.Store.DB.Exec("INSERT INTO connector_active VALUES('p','legacy','legacy-local')")
	s.Store.DB.Exec("PRAGMA user_version=24")
	path := s.Store.path
	s.Store.Close()
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	s.Store = store
	assertSQLInt(t, s, 25, "PRAGMA user_version")
	if err := s.connectorExecutionBound(context.Background(), "legacy-local", legacy); err != nil {
		t.Fatal("upgrade did not preserve the previously approved local definition", err)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM credentials")
	files, err := filepath.Glob(filepath.Join(filepath.Dir(path), "upgrade-backups", "schema-24-to-25-*.sqlite"))
	if err != nil || len(files) != 1 {
		t.Fatal("consistent migration snapshot missing")
	}
	if _, err := connectorContext(context.Background(), s.Store.DB, ConnectorConfig{Role: "NONE"}, Selection{ProviderID: "p", AccountID: "a", CredentialID: "c"}); err != nil {
		t.Fatal("upgrade lost credential context")
	}
}

func connectorDiscoveryFixture(t *testing.T, h http.HandlerFunc) (*Server, *atomic.Int64) {
	t.Helper()
	s, c := retryFixture(t, h)
	s.Client = s.PublicClient
	return s, c
}

func TestConnectorDiscoveryLocalReceiptCurrentScopeAndFreshnessRequired(t *testing.T) {
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	id := discoverForTest(t, s, "receipt", "/api/pricing", v).DraftIDs[0]
	test := func() {
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
		if discoveredVersion(t, s, id).Status != "VALIDATED" {
			t.Fatal("live test did not validate")
		}
	}
	test()
	s.Store.DB.Exec("DELETE FROM connector_test_receipts WHERE version_id=?", id)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	test()
	s.Store.DB.Exec("UPDATE connector_versions SET config_json=json_set(config_json,'$.path','/api/pricing-other') WHERE id=?", id)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	s.Store.DB.Exec("UPDATE connector_versions SET config_json=json_set(config_json,'$.path','/api/pricing') WHERE id=?", id)
	test()
	s.Store.DB.Exec("UPDATE connector_versions SET tested_at=tested_at-86400001 WHERE id=?", id)
	s.Store.DB.Exec("UPDATE connector_test_receipts SET tested_at=tested_at-86400001 WHERE version_id=?", id)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	test()
	s.Store.DB.Exec("INSERT INTO membership_state(scope_key,state) VALUES(?,'ARCHIVED') ON CONFLICT(scope_key) DO UPDATE SET state='ARCHIVED'", scopeKey(v))
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
}
func TestConnectorDiscoveryOpenAPIGetHintsFamilyAndCancelledWork(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/openapi.json":
			w.Write([]byte(`{"openapi":"3.0.0","paths":{"/api/pricing":{"get":{}},"/api/models/delete":{"get":{}},"/api/quota":{"post":{}},"/api/status":{"get":{}}}}`))
		case "/api/status":
			w.Write([]byte(`{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"checkin_enabled":false}}`))
		default:
			w.Write([]byte(connectorPriceFixture))
		}
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "openapi", "/openapi.json", v)
	if calls.Load() != 3 || report.Family != "NEW_API" || report.Version != "v1.0.0-rc.25" || len(report.DraftIDs) != 1 {
		t.Fatalf("untruthful family/entry hints: %+v", report)
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.PublicClient = &http.Client{Transport: connectorCancelTransport{cancel: cancel, inner: s.PublicClient.Transport}}
	report, _, err := s.generateConnectorDrafts(ctx, connectorDiscoveryRequest{ID: "cancelled", SourcePath: "/api/pricing", Selection: v}, 6)
	if err != nil || report.Status != "INTERRUPTED" || len(report.DraftIDs) != 0 || report.HTTPRequests == nil || *report.HTTPRequests != 1 {
		t.Fatalf("cancelled discovery claimed completion: %+v %v", report, err)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
}

type connectorCancelTransport struct {
	cancel context.CancelFunc
	inner  http.RoundTripper
}

func (t connectorCancelTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	resp, err := t.inner.RoundTrip(r)
	t.cancel()
	return resp, err
}

func TestConnectorDiscoveryAssistantUsesSharedPipelineAndWebBudget(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path == "/" {
			w.Write([]byte(`<a href="/api/pricing">one</a><a href="/api/status">two</a>`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	raw, _ := json.Marshal(connectorDiscoveryRequest{ID: "assistant-draft", SourcePath: "/", Selection: v})
	var call ToolCall
	call.Function.Name = "discover_connectors"
	call.Function.Arguments = string(raw)
	budget := &assistantBudget{web: 3}
	result, err := s.assistantTool(context.Background(), "", call, budget)
	if err != nil {
		t.Fatal(err)
	}
	report := result.(connectorDiscoveryReport)
	if calls.Load() != 1 || report.RequestLimit != 1 || budget.web != 4 {
		t.Fatal("assistant exceeded remaining public web budget")
	}
	if _, err = s.assistantTool(context.Background(), "", call, budget); err == nil || calls.Load() != 1 {
		t.Fatal("assistant bypassed exhausted web budget")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
}
func TestConnectorDiscoveryAmbiguousRowsBoundOriginAndSharedCooldown(t *testing.T) {
	for _, scenario := range []string{"duplicate-model", "bound-origin", "cooldown"} {
		t.Run(scenario, func(t *testing.T) {
			var calls atomic.Int32
			s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				w.Header().Set("Content-Type", "application/json")
				if scenario == "cooldown" {
					w.Header().Set("Retry-After", "120")
					http.Error(w, "private body", 429)
					return
				}
				if scenario == "duplicate-model" {
					w.Write([]byte(`{"data":[{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/token","input":"1"}},{"model_id":"m","scope":"MODEL_PROVIDER","pricing":{"unit":"USD/token","input":"9"}}]}`))
					return
				}
				w.Write([]byte(connectorPriceFixture))
			})
			v := seed(t, s, "https://unknown.example/v1")
			requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
			report := discoverForTest(t, s, "first", "/api/pricing", v)
			switch scenario {
			case "duplicate-model":
				if len(report.DraftIDs) != 0 {
					t.Fatal("conflicting duplicate model rows became an authoritative mapping")
				}
			case "bound-origin":
				draft := discoveredVersion(t, s, report.DraftIDs[0])
				s.Store.DB.Exec("UPDATE credential_origins SET base_url='https://secondary.example/v1' WHERE credential_id='c'")
				if _, err := s.connectorRows(context.Background(), draft.Config, v); err == nil || calls.Load() != 1 {
					t.Fatal("generated mapping borrowed another credential origin")
				}
			case "cooldown":
				if report.HTTPRequests == nil || *report.HTTPRequests != 1 {
					t.Fatal("missing first admission")
				}
				next := discoverForTest(t, s, "waiting", "/api/models", v)
				if next.HTTPRequests == nil || *next.HTTPRequests != 0 || calls.Load() != 1 || next.Sources[0].Status != "WAITING_ORIGIN" {
					t.Fatal("discovery bypassed shared cooldown or charged local rejection")
				}
			}
		})
	}
}
func TestConnectorDiscoveryChangedActiveDefinitionNeedsNewReview(t *testing.T) {
	var calls atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/pricing" {
			calls.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(connectorPriceFixture))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	id := discoverForTest(t, s, "immutable-activation", "/api/pricing", v).DraftIDs[0]
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	before := calls.Load()
	// A paired metadata update may change a version row; the local activation
	// pointer must not authorize that different definition or its next HTTP read.
	s.Store.DB.Exec("UPDATE connector_versions SET config_json=json_set(config_json,'$.fields.input','/pricing/output') WHERE id=?", id)
	s = reopenScheduleStore(t, s)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	if calls.Load() != before {
		t.Fatal("changed active definition reached HTTP without a new Review")
	}
}
