package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAssistantMappingTypedRowPointersKeepCanonicalRecoveryAndRejectInventedUnits(t *testing.T) {
	parallelResearchBudgetTest(t)
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	request := mappingTestRequest(report)
	request.ID = "typed-pointer-draft"
	raw, _ := json.Marshal(request)
	var wire map[string]any
	json.Unmarshal(raw, &wire)
	wire["fields"] = map[string]any{"input": map[string]any{"path": "/fees/entry"}, "output": map[string]any{"path": "/fees/exit"}}
	propose := func(mapping any) (any, error) {
		return s.assistantTool(context.Background(), "typed-pointer-run", functionCall("preview", "preview_connector_mapping", map[string]any{"selection": selection, "mapping": mapping}), &assistantBudget{})
	}
	value, e := propose(wire)
	if e != nil {
		t.Fatal("observed typed row pointers rejected:", e)
	}
	id := str(value.(map[string]any)["id"])
	s = reopenScheduleStore(t, s)
	legacy, e := propose(request)
	if e != nil || id == "" || str(legacy.(map[string]any)["id"]) != id {
		t.Fatal("equivalent pointer encodings did not recover the same durable draft:", e)
	}
	wire["id"] = "invented-units"
	wire["scope_field"] = mappingLocation{Path: "MODEL_PROVIDER", Source: false}
	wire["units"] = map[string]mappingLocation{"input_tokens": {Path: "/daily/tokens_unit"}, "output_tokens": {Path: "/daily/tokens_unit"}}
	if value, e = propose(wire); e == nil || value != nil {
		t.Fatal("invented pricing units or literal scope were silently repaired")
	}
	wire["scope_field"] = request.Scope
	if value, e = propose(wire); e == nil || value != nil {
		t.Fatal("invented pricing unit metric names were silently repaired")
	}
	wire["units"] = map[string]mappingLocation{"input": {Path: "/daily/tokens_unit"}, "output": {Path: "/daily/tokens_unit"}}
	if value, e = propose(wire); e == nil || value != nil {
		t.Fatal("observed quota units were silently converted into pricing units")
	}
	wire["id"], wire["scope_field"], wire["units"] = "root-override", request.Scope, request.Units
	wire["fields"] = map[string]any{"input": map[string]any{"path": "/fees/entry", "source_root": true}}
	if value, e = propose(wire); e == nil || value != nil {
		t.Fatal("typed row pointer accepted hidden source-root authority")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	if calls.Load() != 1 {
		t.Fatal("local proposals repeated source HTTP")
	}
}

func TestAssistantMappingObservedScopeAndUnitLocationsAreCopyableEvidence(t *testing.T) {
	parallelResearchBudgetTest(t)
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	value, e := s.assistantTool(context.Background(), "copyable-locations", functionCall("read", "read_connector_mapping_sample", map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}), &assistantBudget{})
	if e != nil {
		t.Fatal(e)
	}
	raw, _ := json.Marshal(value)
	var out struct {
		Scopes  []mappingLocation            `json:"scope_fields"`
		Units   map[string][]mappingLocation `json:"unit_fields"`
		Context struct {
			SampleID string          `json:"sample_id"`
			Root     string          `json:"root_pointer"`
			Model    string          `json:"model_path"`
			Scope    mappingLocation `json:"scope_field"`
		} `json:"mapping_context"`
	}
	json.Unmarshal(raw, &out)
	scope := mappingLocation{Path: "/document/reach", Source: true}
	if !reflect.DeepEqual(out.Scopes, []mappingLocation{scope}) || !reflect.DeepEqual(out.Units["USD/1M tokens"], []mappingLocation{{Path: "/document/money", Source: true}}) || !reflect.DeepEqual(out.Units["TOKENS/day"], []mappingLocation{{Path: "/daily/tokens_unit"}}) || out.Context.SampleID != report.Sources[0].MappingSampleID || out.Context.Root != "/tariffs" || out.Context.Model != "/handle" || out.Context.Scope != scope {
		t.Fatal("observed locations could not be copied without inventing pointer syntax:", string(raw))
	}
	if calls.Load() != 1 {
		t.Fatal("describing observed locations repeated HTTP")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestAssistantMappingReasoningOutputLimitDoesNotCompleteAnEmptyTask(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			http.Error(w, "wrong inference scope", 403)
			return
		}
		calls.Add(1)
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"index": 0, "message": map[string]any{"role": "assistant", "content": "", "reasoning_content": "synthetic reasoning only"}, "finish_reason": "length"}}, "usage": map[string]any{"prompt_tokens": 10, "completion_tokens": 2048, "completion_tokens_details": map[string]int{"reasoning_tokens": 2048}}})
	}))
	defer up.Close()
	s.Client = up.Client()
	configureAssistant(t, s, seed(t, s, up.URL+"/v1"))
	r := req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: "請準備公開欄位草稿。"})
	requireCode(t, r, 200)
	var status string
	s.Store.DB.QueryRow("SELECT status FROM assistant_runs ORDER BY rowid DESC LIMIT 1").Scan(&status)
	if status != "MODEL_OUTPUT_LIMIT" || strings.Contains(r.Body.String(), "event: done") || !strings.Contains(r.Body.String(), "event: error") || calls.Load() != 1 {
		t.Fatal("empty output-limit response falsely completed or retried the task:", status, r.Body.String(), calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE source='ASSISTANT_AGENT' AND outcome='SUCCESS'")
}
