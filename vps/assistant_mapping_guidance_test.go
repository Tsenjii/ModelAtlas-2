package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAssistantMappingDescriptorListsCannotBeSilentlyCutByToolJournal(t *testing.T) {
	parallelResearchBudgetTest(t)
	var document map[string]any
	json.Unmarshal([]byte(mappingPublicFixture), &document)
	notes := map[string]any{}
	for i := 0; i < 105; i++ {
		notes[fmt.Sprintf("field%03d", i)] = "short"
	}
	document["public_notes"] = notes
	raw, _ := json.Marshal(document)
	var body atomic.Value
	body.Store(string(raw))
	s, selection, report, calls := mappingTestSetup(t, &body)
	value, e := s.assistantTool(context.Background(), "journal-descriptor-limit", functionCall("read", "read_connector_mapping_sample", map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection, "root_pointer": "/tariffs", "model_path": "/handle"}), &assistantBudget{})
	if e == nil || value != nil {
		t.Fatal("field descriptors exceeded the journal's 100-item limit but were offered as complete tool data")
	}
	if calls.Load() != 1 {
		t.Fatal("descriptor limit emitted another source read")
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_samples")
}

func TestAssistantMappingAutoDescribesOnlyOneProvenUniqueModelRow(t *testing.T) {
	parallelResearchBudgetTest(t)
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	args := map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}
	value, e := s.assistantTool(context.Background(), "observed-live-read", functionCall("read", "read_connector_mapping_sample", args), &assistantBudget{})
	if e != nil {
		t.Fatal(e)
	}
	raw, _ := json.Marshal(value)
	var out struct {
		Sample   mappingView `json:"sample"`
		Selected struct {
			Root  string `json:"root_pointer"`
			Model string `json:"model_path"`
		} `json:"selected_container"`
	}
	if json.Unmarshal(raw, &out) != nil || len(out.Sample.Fields) == 0 || out.Selected.Root != "/tariffs" || out.Selected.Model != "/handle" {
		t.Fatal("read returned only empty row fields despite a single complete unique model selection:", string(raw))
	}
	if !strings.Contains(string(raw), "/fees/entry") || !strings.Contains(string(raw), "/fees/exit") || calls.Load() != 1 {
		t.Fatal("unambiguous saved row descriptors were missing or refetched")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestAssistantMappingHelpfulValidationRejectsObservedInventedFieldNames(t *testing.T) {
	parallelResearchBudgetTest(t)
	var body atomic.Value
	body.Store(mappingPublicFixture)
	s, selection, report, calls := mappingTestSetup(t, &body)
	request := mappingTestRequest(report)
	request.Fields = map[string]string{"input_price": "/document/money", "output_price": "/document/money"}
	request.Scope = mappingLocation{Path: "pricing", Source: true}
	request.Units = map[string]mappingLocation{"input": {Path: "1M tokens", Source: true}, "output": {Path: "1M tokens", Source: true}}
	value, e := s.assistantTool(context.Background(), "observed-live-arguments", functionCall("preview", "preview_connector_mapping", map[string]any{"selection": selection, "mapping": request}), &assistantBudget{})
	if e == nil || value != nil || !strings.Contains(e.Error(), "input") || !strings.Contains(e.Error(), "output") || !strings.Contains(e.Error(), "JSON Pointer") || strings.Contains(e.Error(), "sample or reviewed mapping changed") {
		t.Fatal("invalid field arguments were presented as changed source evidence instead of actionable mapping requirements:", e)
	}
	if calls.Load() != 1 {
		t.Fatal("invalid model proposal emitted source HTTP")
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_versions")
}

func TestAssistantMappingAmbiguousModelColumnsStillRequireExplicitSelection(t *testing.T) {
	parallelResearchBudgetTest(t)
	var body atomic.Value
	body.Store(strings.Replace(mappingPublicFixture, `"handle":"m"`, `"handle":"m","alternate_handle":"m"`, 1))
	s, selection, report, calls := mappingTestSetup(t, &body)
	args := map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}
	value, e := s.assistantTool(context.Background(), "ambiguous-model", functionCall("read", "read_connector_mapping_sample", args), &assistantBudget{})
	if e != nil {
		t.Fatal(e)
	}
	raw, _ := json.Marshal(value)
	var out struct {
		Sample mappingView `json:"sample"`
	}
	json.Unmarshal(raw, &out)
	if len(out.Sample.Fields) != 0 {
		t.Fatal("ambiguous identifier columns were silently chosen")
	}
	args["root_pointer"], args["model_path"] = "/tariffs", "/handle"
	value, e = s.assistantTool(context.Background(), "ambiguous-model", functionCall("read", "read_connector_mapping_sample", args), &assistantBudget{})
	if e != nil {
		t.Fatal(e)
	}
	raw, _ = json.Marshal(value)
	json.Unmarshal(raw, &out)
	if len(out.Sample.Fields) == 0 || calls.Load() != 1 {
		t.Fatal("explicit complete model column lost its saved descriptors")
	}
}
