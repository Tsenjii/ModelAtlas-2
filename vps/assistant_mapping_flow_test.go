package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

// A real TLS peer exercises the production agent dispatcher and durable tool
// journal. Its scripted tool choices are a fixture, not evidence that a remote
// model has understood an arbitrary site or that the provider is authentic.
func assistantMappingFlowFixture(t *testing.T) (*Server, Selection, *atomic.Int32, *atomic.Int32) {
	t.Helper()
	metadata, models := new(atomic.Int32), new(atomic.Int32)
	var server *Server
	var selection Selection
	var report connectorDiscoveryReport
	var mu sync.Mutex
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/pricing" && r.Method == "GET" {
			if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
				t.Error("public mapping source received credentials")
			}
			metadata.Add(1)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(mappingPublicFixture))
			return
		}
		if r.URL.Path != "/v1/chat/completions" || r.Method != "POST" {
			http.NotFound(w, r)
			return
		}
		mu.Lock()
		defer mu.Unlock()
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			http.Error(w, "invalid model request", 400)
			return
		}
		raw, _ := json.Marshal(body)
		for _, secret := range []string{"top-secret-example-key", "credential-echo", "sk-synthetic", server.Token, base64.StdEncoding.EncodeToString(server.Key)} {
			if strings.Contains(string(raw), secret) {
				t.Error("raw secret or removed source credential field reached the model")
			}
		}
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("agent borrowed a different model credential")
		}
		messages, _ := body["messages"].([]any)
		if len(messages) == 0 || messages[0].(map[string]any)["content"] != assistantSystem {
			t.Error("untrusted public data replaced the agent system boundary")
		}
		last := func(out any) {
			t.Helper()
			for i := len(messages) - 1; i >= 0; i-- {
				m := messages[i].(map[string]any)
				if m["role"] == "tool" {
					if json.Unmarshal([]byte(str(m["content"])), out) != nil {
						t.Error("real tool continuation was not complete JSON")
					}
					return
				}
			}
			t.Error("real model tool continuation missing")
		}
		switch models.Add(1) {
		case 1:
			assistantReply(w, "", functionCall("discover-public", "discover_connectors", map[string]any{"id": "assistant-unknown-source", "selection": selection, "source_path": "/api/pricing"}))
		case 2:
			last(&report)
			if report.Status != "NO_SUPPORTED_MAPPING" || len(report.Sources) != 1 || report.Sources[0].MappingSampleID == "" {
				t.Error("unknown source did not produce a real saved sample")
				http.Error(w, "missing sample", 500)
				return
			}
			assistantReply(w, "", functionCall("read-public-fields", "read_connector_mapping_sample", map[string]any{"sample_id": report.Sources[0].MappingSampleID, "selection": selection}))
		case 3:
			var result struct {
				Sample mappingView `json:"sample"`
			}
			last(&result)
			if result.Sample.ID != report.Sources[0].MappingSampleID || len(result.Sample.Fields) == 0 {
				t.Error("field proposal did not consume the saved public descriptors")
			}
			request := mappingTestRequest(report)
			request.ID = "assistant-mapping-flow-proposal"
			assistantReply(w, "", functionCall("preview-public-fields", "preview_connector_mapping", map[string]any{"selection": selection, "mapping": request}))
		case 4:
			var result map[string]any
			last(&result)
			if str(result["id"]) == "" {
				t.Error("agent reported completion without a durable draft ID")
			}
			assistantReply(w, "已準備公開欄位草稿，請到研究知識核對並實測。")
		default:
			t.Error("agent unexpectedly repeated inference")
			http.Error(w, "unexpected model request", 500)
		}
	})
	server = s
	selection = seed(t, s, "https://unknown.example/v1")
	configureAssistant(t, s, selection)
	return s, selection, metadata, models
}

func TestAssistantMappingRealAgentLoopPreservesDraftAndToolJournal(t *testing.T) {
	s, _, metadata, models := assistantMappingFlowFixture(t)
	r := req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: "請辨識 /api/pricing 的公開資料，依實際欄位提出價格擷取草稿。"})
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "event: done") || !strings.Contains(r.Body.String(), "請到研究知識核對並實測") || models.Load() != 4 || metadata.Load() != 1 {
		t.Fatal("real agent flow did not complete within its exact request budget:", r.Body.String(), models.Load(), metadata.Load())
	}
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM assistant_tool_events WHERE status='SUCCESS'")
	assertSQLInt(t, s, 4, "SELECT COUNT(*) FROM telemetry WHERE source='ASSISTANT_AGENT' AND outcome='SUCCESS'")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_versions WHERE status='DRAFT'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM connector_active")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_mapping_requests")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM connector_generated_versions WHERE json_extract(proof_json,'$.mapping_author')='ASSISTANT'")
}
