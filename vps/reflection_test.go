package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestMissingTerminalReflectionRetriesFromDurableState(t *testing.T) {
	s := testServer(t)
	ids := []string{newID(), newID()}
	for i, status := range []string{"COMPLETED", "BUDGET_EXHAUSTED"} {
		if _, e := s.Store.DB.Exec("INSERT INTO research_runs VALUES(?,?,'',?,?)", ids[i], status, now(), now()); e != nil {
			t.Fatal(e)
		}
		if _, e := s.Store.DB.Exec("INSERT INTO research_run_options(run_id,config_json,budget,used,probe_budget,probes_used,summary_json) VALUES(?,'{}',5,1,0,0,'{}')", ids[i]); e != nil {
			t.Fatal(e)
		}
		if _, e := s.Store.DB.Exec(`INSERT INTO research_tasks(id,run_id,kind,dedupe,payload_json,status,attempts,result_json,created_at) VALUES(?,?,'SEARCH','search:en','{"language":"en","query":"free models"}','SUCCESS',1,'{}',?)`, newID(), ids[i], now()); e != nil {
			t.Fatal(e)
		}
	}
	s.retryMissingReflections(context.Background())
	var n int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_reflections").Scan(&n)
	if n != 2 {
		t.Fatal("missing terminal reports not rebuilt")
	}
	s.retryMissingReflections(context.Background())
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_reflections").Scan(&n)
	if n != 2 {
		t.Fatal("retry duplicated reflection")
	}
	s.Store.DB.Exec("UPDATE research_run_options SET used=2 WHERE run_id=?", ids[0])
	s.reflectionDue = 0
	s.retryMissingReflections(context.Background())
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_reflections").Scan(&n)
	if n != 3 {
		t.Fatal("changed terminal summary not rebuilt")
	}
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM knowledge_active").Scan(&n)
	if n != 0 {
		t.Fatal("retry activated learning without confirmation")
	}
}

func TestResearchReflectionFrozenSourcesVersionedLearningAndUndo(t *testing.T) {
	var path string
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"model_id":"m","rpd":100,"tpd":1000000,"taiwan":"YES","recurring":"DAILY_RESET","free":true,"access_method":"INFERENCE_READY"}]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	seed(t, s, "https://provider.example/v1")
	src := ResearchSource{ID: "source", Name: "Policy", Kind: "JSON", URL: "https://provider.example/original", Language: "en", Enabled: true, Config: SourceConfig{ProviderID: "p", Official: true, ItemsPointer: "/data"}}
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	run := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"source"}, Budget: 2})
	src.URL = "https://provider.example/new-version"
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	s.runResearchPending(context.Background())
	if path != "/original" {
		t.Fatal("queued run read a newer mutable source definition")
	}
	var sourceVersions, reflections, knowledge int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_source_versions WHERE source_id='source'").Scan(&sourceVersions)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_reflections WHERE run_id=?", run).Scan(&reflections)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM knowledge_versions").Scan(&knowledge)
	if sourceVersions != 2 || reflections != 1 || knowledge < 6 {
		t.Fatalf("source versions=%d reflections=%d learned versions=%d", sourceVersions, reflections, knowledge)
	}
	if e := s.reflectResearch(context.Background(), run); e != nil {
		t.Fatal(e)
	}
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_reflections WHERE run_id=?", run).Scan(&reflections)
	if reflections != 1 {
		t.Fatal("identical reflection duplicated learning")
	}
	var proof, first string
	s.Store.DB.QueryRow("SELECT id FROM research_reflections WHERE run_id=?", run).Scan(&proof)
	s.Store.DB.QueryRow("SELECT id FROM knowledge_versions WHERE kind='SOURCE_RELIABILITY'").Scan(&first)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: first, Confirm: false}), 422)
	v := KnowledgeVersion{Kind: "SOURCE_RELIABILITY", Subject: "source", Data: map[string]any{"source_id": "source", "weight": 2, "confidence": "TENTATIVE"}, EvidenceIDs: []string{proof}, Reason: "Give this saved source higher priority."}
	r := req(t, s, "POST", "/api/v1/research/knowledge", v)
	requireCode(t, r, 201)
	var second KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &second)
	r = req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: second.ID, Confirm: true})
	requireCode(t, r, 200)
	var activation map[string]string
	json.Unmarshal(r.Body.Bytes(), &activation)
	src.ID = "other-source"
	src.Name = "Other"
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	next := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"other-source", "source"}, Budget: 2})
	var payload string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 1", next).Scan(&payload)
	var task taskInput
	json.Unmarshal([]byte(payload), &task)
	if task.SourceID != "source" {
		t.Fatal("confirmed source priority had no production effect")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": activation["action_id"], "confirm": true}), 200)
	if s.activeKnowledge(context.Background(), "SOURCE_RELIABILITY", "source") != nil {
		t.Fatal("knowledge Undo failed")
	}
	v.Data["system_prompt"] = "unsafe"
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge", v), 422)
	v.Data = map[string]any{"source_id": "source", "weight": 10}
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge", v), 422)
	var members int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if members != 0 {
		t.Fatal("reflection silently added membership")
	}
}

func TestDeclarativeConnectorRealTestActivationSyncCatalogAndRollback(t *testing.T) {
	var failed atomic.Bool
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" {
			t.Error("connector did not use selected inference credential")
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v999/v1/models":
			w.Write([]byte(`{"data":[{"id":"m"}]}`))
		case "/metadata":
			if failed.Load() {
				http.Error(w, "upstream unavailable", 503)
				return
			}
			w.Write([]byte(`{"data":[{"model":"m","group":"other","quota":{"rpd":999}},{"model":"m","group":"","quota":{"rpd":0,"tpd":1000000}}]}`))
		case "/custom-catalog":
			w.Write([]byte(`[ {"model":"m","label":"Mapped model"},{"model":"catalog-only","label":"Catalog only"} ]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	selection := seed(t, s, up.URL+"/v999/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selection), 201)
	cfg := ConnectorConfig{Kind: "QUOTA", Path: "/metadata", Role: "INFERENCE", Scope: "MODEL_KEY_GROUP", Root: "/data", Unit: "REQUESTS", Fields: map[string]string{"model_id": "/model", "group": "/group", "rpd": "/quota/rpd", "tpd": "/quota/tpd"}}
	proposal := ConnectorVersion{ProviderID: "p", Name: "quota", Config: cfg, Reason: "Real scoped structured metadata mapping."}
	r := req(t, s, "POST", "/api/v1/connectors", proposal)
	requireCode(t, r, 201)
	var v ConnectorVersion
	json.Unmarshal(r.Body.Bytes(), &v)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: v.ID, Confirm: true}), 409)
	r = req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": v.ID, "selection": selection})
	requireCode(t, r, 200)
	if !strings.Contains(r.Body.String(), "VALIDATED") || !strings.Contains(r.Body.String(), `"rpd":"0"`) {
		t.Fatal("exact group or known zero was lost: " + r.Body.String())
	}
	r = req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: v.ID, Confirm: true})
	requireCode(t, r, 200)
	var activation map[string]string
	json.Unmarshal(r.Body.Bytes(), &activation)
	sync := req(t, s, "POST", "/api/v1/intelligence/sync", selection)
	requireCode(t, sync, 200)
	var facts string
	queryErr := s.Store.DB.QueryRow("SELECT facts_json FROM observations WHERE kind='QUOTA' AND scope_key=? AND source=? AND status='SUCCESS'", scopeKey(selection), "DECLARATIVE_PROVIDER_META:"+v.ID).Scan(&facts)
	var quota map[string]any
	jsonErr := json.Unmarshal([]byte(facts), &quota)
	// A valid origin can contain 999 (including an ephemeral TLS port). Assert
	// actual scoped fields, not a substring of the entire JSON document.
	if queryErr != nil || jsonErr != nil || quota["rpd"] != "0" || quota["tpd"] != "1000000" || quota["scope_kind"] != "MODEL_KEY_GROUP" || quota["origin"] != up.URL+"/v999/v1" {
		t.Fatalf("production connector used wrong model/group: query=%v json=%v facts=%s response=%s", queryErr, jsonErr, facts, sync.Body.String())
	}
	failed.Store(true)
	requireCode(t, req(t, s, "POST", "/api/v1/intelligence/sync", selection), 200)
	var good, bad int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM observations WHERE kind='QUOTA' AND status='SUCCESS'").Scan(&good)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM observations WHERE kind='QUOTA' AND status='FAILED'").Scan(&bad)
	if good != 1 || bad != 1 {
		t.Fatal("failed sync replaced or erased last-good")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": activation["action_id"], "confirm": true}), 200)
	proposal.Name = "catalog"
	proposal.Config = ConnectorConfig{Kind: "CATALOG", Path: "/custom-catalog", Role: "INFERENCE", Scope: "CATALOG", Root: "", Unit: "NONE", Fields: map[string]string{"model_id": "/model", "name": "/label"}}
	r = req(t, s, "POST", "/api/v1/connectors", proposal)
	requireCode(t, r, 201)
	json.Unmarshal(r.Body.Bytes(), &v)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/test", map[string]any{"id": v.ID, "selection": selection}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/connectors/activate", versionActivation{ID: v.ID, Confirm: true}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/models/sync", syncRequest{ProviderID: "p", AccountID: "a", CredentialID: "c"}), 200)
	var catalog, members int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM models WHERE id='catalog-only'").Scan(&catalog)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if catalog != 1 || members != 1 {
		t.Fatal("mapped catalog lost a row or implicitly added membership")
	}
	proposal.Config.Path = "https://elsewhere.example/private"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors", proposal), 422)
	proposal.Config.Path = "/metadata"
	proposal.Config.Fields["name"] = "/api_key"
	requireCode(t, req(t, s, "POST", "/api/v1/connectors", proposal), 422)
}

func TestResearchDoesNotReuseEarlierCredentialVersion(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer rotated-private-example" {
			t.Error("research sent previous credential version")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	s.Store.DB.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,created_at) VALUES('old','p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',?)", now())
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "rotated-private-example", "confirm": true}), 200)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{v}, MinimalInference: true, Budget: 4, ProbeBudget: 1})
	_, e := s.executeResearchTask(context.WithValue(context.Background(), researchBudgetKey{}, run), run, "MINIMAL_INFERENCE", taskInput{Selection: &v})
	if e != nil {
		t.Fatal(e)
	}
	if calls.Load() != 1 {
		t.Fatal("old key material's success was reused")
	}
	var version int
	s.Store.DB.QueryRow("SELECT credential_version FROM telemetry WHERE id!='old' ORDER BY rowid DESC LIMIT 1").Scan(&version)
	if version != 2 {
		t.Fatal("common engine did not persist credential version")
	}
}
