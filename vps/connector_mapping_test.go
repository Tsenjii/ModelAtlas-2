package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const mappingPublicFixture = `{"document":{"reach":"MODEL_PROVIDER","money":"USD/1M tokens"},"tariffs":[{"handle":"other","fees":{"entry":"4","exit":"8"}},{"handle":"m","fees":{"entry":"0","exit":"2"},"daily":{"calls":"120","calls_unit":"REQUESTS/day","tokens":"1200000","tokens_unit":"TOKENS/day"}}],"api_key":"sk-synthetic-public-echo-that-must-not-be-kept","apiKey":"credential-echo-one","clientSecret":"credential-echo-two"}`

func mappingTestSetup(t *testing.T, body *atomic.Value) (*Server, Selection, connectorDiscoveryReport, *atomic.Int32) {
	t.Helper()
	calls := new(atomic.Int32)
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/pricing" {
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("mapping sent credential")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(body.Load().(string)))
	})
	v := seed(t, s, "https://unknown.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	report := discoverForTest(t, s, "mapping-source", "/api/pricing", v)
	if report.Status != "NO_SUPPORTED_MAPPING" || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" || len(report.DraftIDs) != 0 {
		t.Fatalf("unknown source lost: %+v", report)
	}
	return s, v, report, calls
}

func TestConnectorMappingIdempotentRequestPreservesParentAndOriginalActive(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, _, report, calls := mappingTestSetup(t, &body)
	var before string
	s.Store.DB.QueryRow("SELECT report_json FROM connector_discoveries WHERE id=?", report.ID).Scan(&before)
	v := mappingTestRequest(report)
	id := mappingTestPropose(t, s, v)
	s = reopenScheduleStore(t, s)
	r := req(t, s, "POST", "/api/v1/connectors/map", v)
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), id) {
		t.Fatal("lost durable mapping ID")
	}
	v.Fields["output"] = "/fees/entry"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", v), 409)
	v.ID = "mapping-revision"
	second := mappingTestPropose(t, s, v)
	if second == id || discoveredVersion(t, s, second).Version != 2 {
		t.Fatal("revision replaced an immutable draft")
	}
	var after string
	s.Store.DB.QueryRow("SELECT report_json FROM connector_discoveries WHERE id=?", report.ID).Scan(&after)
	if before != after || calls.Load() != 1 {
		t.Fatal("mapping rewrote source report or repeated network reads")
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
}

func TestConnectorMappingRejectsGuessedUnitsScopePointersAndHiddenDuplicates(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, _, report, calls := mappingTestSetup(t, &body)
	for _, change := range []func(*connectorMappingRequest){
		func(v *connectorMappingRequest) { v.Scope.Path = "/document/money" },
		func(v *connectorMappingRequest) { delete(v.Units, "input") },
		func(v *connectorMappingRequest) { v.Units["input"] = mappingLocation{Path: "/daily/calls_unit"} },
		func(v *connectorMappingRequest) { v.Root = "/tariffs/1" },
		func(v *connectorMappingRequest) { v.ModelPath = "/api_key" },
		func(v *connectorMappingRequest) { v.Fields["input"] = "/missing" },
		func(v *connectorMappingRequest) {
			v.Units["invented"] = mappingLocation{Path: "/document/money", Source: true}
		},
	} {
		v := mappingTestRequest(report)
		change(&v)
		requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", v), 409)
	}
	if calls.Load() != 1 {
		t.Fatal("invalid mappings emitted HTTP")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	body.Store(strings.Replace(mappingPublicFixture, `"handle":"other"`, `"handle":"m"`, 1))
	duplicate := discoverForTest(t, s, "duplicate-source", "/api/pricing", report.Selection)
	v := mappingTestRequest(duplicate)
	v.ID = "duplicate-mapping"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", v), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
}

func TestConnectorMappingQuotaZeroAndMissingRemainDistinct(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, calls := mappingTestSetup(t, &body)
	in := mappingTestRequest(report)
	in.Kind = "QUOTA"
	in.Fields = map[string]string{"rpd": "/daily/calls", "tpd": "/daily/tokens"}
	in.Units = map[string]mappingLocation{"rpd": {Path: "/daily/calls_unit"}, "tpd": {Path: "/daily/tokens_unit"}}
	id := mappingTestPropose(t, s, in)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	x := discoveredVersion(t, s, id)
	if x.Status != "VALIDATED" {
		t.Fatalf("mapped quota not validated: %+v", x.Test)
	}
	body.Store(strings.Replace(mappingPublicFixture, `"calls":"120"`, `"calls":"0"`, 1))
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	x = discoveredVersion(t, s, id)
	facts := x.Test["facts"].(map[string]any)
	if facts["rpd"] != "0" || facts["rpm"] != nil || x.Status != "VALIDATED" {
		t.Fatalf("zero or Unknown changed: %+v", x.Test)
	}
	body.Store(strings.Replace(mappingPublicFixture, `"calls":"120",`, "", 1))
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	if discoveredVersion(t, s, id).Status != "FAILED" || calls.Load() != 4 {
		t.Fatal("missing metric silently accepted or retried")
	}
}

func TestConnectorMappingChangedEvidenceAfterTestCannotActivate(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, _ := mappingTestSetup(t, &body)
	id := mappingTestPropose(t, s, mappingTestRequest(report))
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	if _, e := s.Store.DB.Exec("UPDATE connector_mapping_samples SET root_hash='different' WHERE id=?", report.Sources[0].MappingSampleID); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM action_ledger WHERE action='ACTIVATE_CONNECTOR'")
}

func TestConnectorMappingScopeRotationAndExpiredSamplesBlockWithoutHTTP(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, calls := mappingTestSetup(t, &body)
	if _, e := s.Store.DB.Exec("INSERT INTO credential_secret_versions SELECT provider_id,account_id,id,999,encrypted_key,? FROM credentials WHERE id=?", now(), v.CredentialID); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", mappingTestRequest(report)), 409)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	if calls.Load() != 1 {
		t.Fatal("stale scope emitted HTTP")
	}
}

func TestConnectorMappingSnapshotMigrationAndLocalOnlyData(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, _ := mappingTestSetup(t, &body)
	mappingTestPropose(t, s, mappingTestRequest(report))
	for _, kind := range workspaceTables {
		if strings.Contains(kind, "mapping") {
			t.Fatal("local mapping authority entered workspace registry")
		}
	}
	path := s.Store.path
	if _, e := s.Store.DB.Exec("DROP TABLE connector_mapping_requests; DROP TABLE connector_mapping_samples; PRAGMA user_version=35"); e != nil {
		t.Fatal(e)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models WHERE model_id=?", v.ModelID)
	files, e := filepath.Glob(filepath.Join(filepath.Dir(path), "upgrade-backups", fmt.Sprintf("schema-35-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(files) != 1 {
		t.Fatalf("missing consistent migration snapshot: %v %v", files, e)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_samples")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests")
}
func mappingTestRequest(report connectorDiscoveryReport) connectorMappingRequest {
	return connectorMappingRequest{ID: "mapping-price", SampleID: report.Sources[0].MappingSampleID, Kind: "PRICING", Root: "/tariffs", ModelPath: "/handle", Scope: mappingLocation{Path: "/document/reach", Source: true}, Fields: map[string]string{"input": "/fees/entry", "output": "/fees/exit"}, Units: map[string]mappingLocation{"input": {Path: "/document/money", Source: true}, "output": {Path: "/document/money", Source: true}}}
}
func mappingTestPropose(t *testing.T, s *Server, v connectorMappingRequest) string {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/connectors/map", v)
	requireCode(t, r, 201)
	var out map[string]string
	json.Unmarshal(r.Body.Bytes(), &out)
	if out["id"] == "" {
		t.Fatal("missing mapped version")
	}
	return out["id"]
}

func TestConnectorMappingFormalSavedSourceReviewRuntimeAndPersistentUndo(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, calls := mappingTestSetup(t, &body)
	r := req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+url.QueryEscape(report.Sources[0].MappingSampleID), nil)
	requireCode(t, r, 200)
	if strings.Contains(r.Body.String(), "sk-synthetic") || strings.Contains(r.Body.String(), "api_key") || strings.Contains(r.Body.String(), "credential-echo") || !strings.Contains(r.Body.String(), "/handle") {
		t.Fatal("source descriptors leaked credentials or lost unknown fields: " + r.Body.String())
	}
	request := mappingTestRequest(report)
	id := mappingTestPropose(t, s, request)
	if calls.Load() != 1 {
		t.Fatal("mapping generation issued HTTP")
	}
	version := discoveredVersion(t, s, id)
	if version.Active || version.Status != "DRAFT" || version.Discovery.MappingParentID != report.ID || version.Config.Effective || version.Config.FixedAbsent {
		t.Fatalf("mapping borrowed authority: %+v", version)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	version = discoveredVersion(t, s, id)
	if version.Status != "VALIDATED" {
		t.Fatalf("real mapping test failed: %+v", version.Test)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	var facts string
	s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE source=? AND kind='PRICING' AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id).Scan(&facts)
	if !strings.Contains(facts, `"output":"0.002"`) || !strings.Contains(facts, `"input":"0"`) || strings.Contains(facts, "effective_charge") {
		t.Fatal("mapped nominal values missing: " + facts)
	}
	body.Store(strings.Replace(mappingPublicFixture, "USD/1M tokens", "USD/token", 1))
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", v), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='SUCCESS'", "DECLARATIVE_PROVIDER_META:"+id)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM observations WHERE source=? AND status='FAILED'", "DECLARATIVE_PROVIDER_META:"+id)
	s = reopenScheduleStore(t, s)
	version = discoveredVersion(t, s, id)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": version.ActivationActionID, "confirm": true}), 200)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	if calls.Load() != 4 {
		t.Fatalf("expected discovery, test, two production reads; got %d", calls.Load())
	}
}

func TestConnectorMappingExpiredSampleNeedsNewDiscoveryButExistingVersionCanRetest(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, v, report, calls := mappingTestSetup(t, &body)
	id := mappingTestPropose(t, s, mappingTestRequest(report))
	// A controlled historical fixture has the same data and original source hash,
	// but was observed more than a day ago. No production clock is overridden.
	old := now() - int64(25*time.Hour/time.Millisecond)
	report.ObservedAt = old
	raw, _ := json.Marshal(report)
	if _, e := s.Store.DB.Exec("UPDATE connector_discoveries SET report_json=? WHERE id=?", string(raw), report.ID); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Store.DB.Exec("UPDATE connector_mapping_samples SET observed_at=?,report_hash=? WHERE id=?", old, connectorHash(report), report.Sources[0].MappingSampleID); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil), 409)
	next := mappingTestRequest(report)
	next.ID = "expired-new-mapping"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", next), 409)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": id, "selection": v}), 200)
	if discoveredVersion(t, s, id).Status != "VALIDATED" || calls.Load() != 2 {
		t.Fatal("existing source history expired fresh real validation")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: id, Confirm: true}), 200)
}

func TestConnectorMappingStorageFailureAndAtomicReservationRollback(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, _, report, calls := mappingTestSetup(t, &body)
	if _, e := s.Store.DB.Exec("CREATE TRIGGER fail_mapping_final BEFORE UPDATE ON connector_mapping_requests BEGIN SELECT RAISE(ABORT,'synthetic mapping persistence failure'); END"); e != nil {
		t.Fatal(e)
	}
	request := mappingTestRequest(report)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", request), 503)
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-result?id="+request.ID, nil), 404)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_generated_versions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_discoveries")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests")
	if _, e := s.Store.DB.Exec("DROP TRIGGER fail_mapping_final"); e != nil {
		t.Fatal(e)
	}
	id := mappingTestPropose(t, s, request)
	r := req(t, s, "GET", "/api/v1/connectors/mapping-result?id="+request.ID, nil)
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), id) {
		t.Fatal("missing read-only result")
	}
	if _, e := s.Store.DB.ExecContext(context.Background(), "DROP TABLE connector_mapping_samples"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/connectors/mapping-sample?id="+report.Sources[0].MappingSampleID, nil), 503)
	if calls.Load() != 1 {
		t.Fatal("local storage failure or recovery emitted HTTP")
	}
}

func TestConnectorMappingConcurrentSameIntentCreatesOneVersion(t *testing.T) {
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, _, report, calls := mappingTestSetup(t, &body)
	request := mappingTestRequest(report)
	var wg sync.WaitGroup
	codes := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); codes <- req(t, s, "POST", "/api/v1/connectors/map", request).Code }()
	}
	wg.Wait()
	close(codes)
	for code := range codes {
		if code != 200 && code != 201 && code != 503 {
			t.Fatalf("unexpected concurrent outcome %d", code)
		}
	}
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/map", request), 200)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests WHERE version_id IS NULL")
	if calls.Load() != 1 {
		t.Fatal("concurrent mapping emitted HTTP")
	}
}
