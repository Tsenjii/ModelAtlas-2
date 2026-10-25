package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestFactoryKiloOfflineInstallCreatesUsableAnonymousSelections(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	r := req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true})
	requireCode(t, r, 201)
	r = req(t, s, "GET", "/api/v1/my-models", nil)
	requireCode(t, r, 200)
	var rows []map[string]any
	if json.Unmarshal(r.Body.Bytes(), &rows) != nil || len(rows) != 15 {
		t.Fatal("factory models missing", r.Body.String())
	}
	for _, row := range rows {
		if row["provider_id"] != "builtin-kilo-anonymous" || row["credential_id"] != "builtin-kilo-anonymous" || row["price_state"] != "UNKNOWN" {
			t.Fatal("factory defaults borrowed credentials or asserted live/free state", row)
		}
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_runs")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM observations")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM credentials WHERE encrypted_key!=''")
}

func TestFactoryKiloFirstStartExistingWorkspaceAndPersistentRemoval(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	created, e := s.installKiloFactory(context.Background(), true)
	if e != nil || !created {
		t.Fatal("new workspace did not receive defaults", e)
	}
	selected := Selection{ProviderID: kiloFactoryID, AccountID: kiloFactoryID, CredentialID: kiloFactoryID, ModelID: "nvidia/nemotron-3.5-lightning:free", Endpoint: "CHAT_COMPLETIONS"}
	requireCode(t, req(t, s, "DELETE", "/api/v1/my-models", selected), 200)
	s = reopenScheduleStore(t, s)
	created, e = s.installKiloFactory(context.Background(), true)
	if e != nil || created {
		t.Fatal("restart reinstalled defaults", e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 200)
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models WHERE model_id=?", selected.ModelID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE action='INSTALL_FACTORY_DEFAULTS'")
	assertSQLInt(t, s, 15, "SELECT COUNT(*) FROM models")
	legacy := testServer(t)
	original := seed(t, legacy, "https://existing.example/v1")
	created, e = legacy.installKiloFactory(context.Background(), true)
	if e != nil || created {
		t.Fatal("existing workspace changed automatically", e)
	}
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM providers")
	requireCode(t, req(t, legacy, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM credentials WHERE provider_id=? AND id=?", original.ProviderID, original.CredentialID)
	assertSQLInt(t, legacy, 2, "SELECT COUNT(*) FROM providers")
	assertSQLInt(t, legacy, 15, "SELECT COUNT(*) FROM my_models")
}

func TestFactoryKiloInstallIsAtomicAndCannotOverwriteAnExistingProfile(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	if _, e := s.Store.DB.Exec("CREATE TRIGGER reject_factory_model BEFORE INSERT ON models WHEN NEW.id='recursive/flint-v1.0:free' BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END"); e != nil {
		t.Fatal(e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 503)
	for _, table := range []string{"providers", "accounts", "credentials", "models", "my_models", "factory_defaults", "action_ledger"} {
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+table)
	}
	s.Store.DB.Exec("DROP TRIGGER reject_factory_model")
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
	other := testServer(t)
	requireCode(t, req(t, other, "POST", "/api/v1/providers", Provider{ID: kiloFactoryID, Name: "My existing private profile", BaseURL: "https://personal.example/v1", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, other, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 409)
	assertSQLInt(t, other, 1, "SELECT COUNT(*) FROM providers WHERE name='My existing private profile' AND base_url='https://personal.example/v1'")
	assertSQLInt(t, other, 0, "SELECT COUNT(*) FROM my_models")
}

func factoryConversation(t *testing.T, s *Server) Session {
	t.Helper()
	selection := Selection{ProviderID: kiloFactoryID, AccountID: kiloFactoryID, CredentialID: kiloFactoryID, ModelID: "nvidia/nemotron-3.5-lightning:free", Endpoint: "CHAT_COMPLETIONS"}
	r := req(t, s, "POST", "/api/v1/sessions", Session{Selection: selection, Title: "Factory model test"})
	requireCode(t, r, 201)
	var session Session
	json.Unmarshal(r.Body.Bytes(), &session)
	return session
}

func TestFactoryKiloActualEngineAnonymousPriceCheckAndPersistentTelemetry(t *testing.T) {
	parallelResearchBudgetTest(t)
	var catalogs, chats atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("X-Api-Key") != "" {
			t.Error("factory connection borrowed a credential")
		}
		if r.URL.Path == "/api/v1/models" {
			catalogs.Add(1)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":[{"id":"nvidia/nemotron-3.5-lightning:free","isFree":true,"pricing":{"prompt":"0","completion":"0"}}]}`))
			return
		}
		if r.URL.Path != "/api/v1/chat/completions" {
			t.Error("unexpected default endpoint", r.URL.Path)
			w.WriteHeader(404)
			return
		}
		chats.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"KILO_FACTORY_OK\"}}]}\n\ndata: [DONE]\n\n"))
	})
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
	if catalogs.Load() != 0 || chats.Load() != 0 {
		t.Fatal("installation sent an automatic request")
	}
	session := factoryConversation(t, s)
	result, status := s.execute(context.Background(), inferRequest{SessionID: session.ID, Prompt: "Only synthetic test content", MaxTokens: 32}, "PLAYGROUND", func(string, any) error { return nil })
	if status != 0 || result.Outcome != "SUCCESS" || !strings.Contains(result.Text, "KILO_FACTORY_OK") || catalogs.Load() != 1 || chats.Load() != 1 {
		t.Fatal("default connection did not use the real guarded engine", status, result, catalogs.Load(), chats.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE provider_id=? AND outcome='SUCCESS'", kiloFactoryID)
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM messages WHERE session_id=?", session.ID)
	assertSQLInt(t, s, 15, "SELECT COUNT(*) FROM my_models")
}

func TestFactoryKiloChangedMissingAmbiguousOrUnavailableCatalogCannotSendModelRequest(t *testing.T) {
	parallelResearchBudgetTest(t)
	base := `{"id":"nvidia/nemotron-3.5-lightning:free","isFree":true,"pricing":{"prompt":"0","completion":"0"}}`
	for _, tc := range []struct {
		name, body string
		status     int
	}{
		{"paid", `{"data":[` + strings.Replace(base, `"completion":"0"`, `"completion":"1"`, 1) + `]}`, 200},
		{"extra-fee", `{"data":[` + strings.Replace(base, `"completion":"0"`, `"completion":"0","request":"1"`, 1) + `]}`, 200},
		{"oversized-price", `{"data":[` + strings.Replace(base, `"prompt":"0"`, `"prompt":"`+strings.Repeat("0", 81)+`"`, 1) + `]}`, 200},
		{"unpriced", `{"data":[{"id":"nvidia/nemotron-3.5-lightning:free","isFree":true}]}`, 200},
		{"missing", `{"data":[]}`, 200},
		{"duplicate", `{"data":[` + base + `,` + base + `]}`, 200},
		{"expired", `{"data":[` + strings.TrimSuffix(base, "}") + `,"expiration_date":"2020-01-01"}]}`, 200},
		{"auth", `{"error":"fixture body must not become a success"}`, 401},
		{"offline", `{"error":"fixture outage"}`, 503},
		{"malformed", `not JSON`, 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var catalogs, chats atomic.Int32
			s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "GET" {
					catalogs.Add(1)
					w.Header().Set("Content-Type", "application/json")
					w.WriteHeader(tc.status)
					w.Write([]byte(tc.body))
				} else {
					chats.Add(1)
					t.Error("guard issued an inference after invalid current catalog")
				}
			})
			requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
			session := factoryConversation(t, s)
			result, status := s.execute(context.Background(), inferRequest{SessionID: session.ID, Prompt: "Synthetic test", MaxTokens: 32}, "PLAYGROUND", func(string, any) error { return nil })
			if status != 503 || !strings.HasPrefix(result.Outcome, "KILO_") || catalogs.Load() != 1 || chats.Load() != 0 {
				t.Fatal("guard invented availability or selected a different model", status, result, catalogs.Load(), chats.Load())
			}
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
			assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM messages")
			assertSQLInt(t, s, 15, "SELECT COUNT(*) FROM my_models")
		})
	}
}

func TestFactoryKiloSchema33UpgradeDoesNotChangeExistingModelsOrPreferences(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	selected := seed(t, s, "https://upgrade.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", selected), 201)
	s.Store.DB.Exec("DROP TABLE factory_defaults")
	s.Store.DB.Exec("PRAGMA user_version=33")
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	created, e := s.installKiloFactory(context.Background(), true)
	if e != nil || created {
		t.Fatal("upgrade replaced existing preferences", e)
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM providers")
	if workspaceAllowed("factory_defaults") {
		t.Fatal("device bootstrap state entered sync")
	}
	files, e := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-33-to-%d-*.sqlite", schemaVersion)))
	if e != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("consistent upgrade snapshot missing", e)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": false}), 422)
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
	assertSQLInt(t, s, 16, "SELECT COUNT(*) FROM my_models")
}

func TestFactoryKiloResearchCatalogBudgetRejectionAndExplicitResume(t *testing.T) {
	parallelResearchBudgetTest(t)
	var catalogs, chats atomic.Int32
	s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("factory research borrowed a private key")
		}
		if r.URL.Path == "/api/v1/models" {
			catalogs.Add(1)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"data":[{"id":"nvidia/nemotron-3.5-lightning:free","isFree":true,"pricing":{"prompt":"0","completion":"0"}}]}`))
			return
		}
		chats.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Synthetic factory research answer\"}}]}\n\ndata: [DONE]\n\n"))
	})
	requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
	selection := factoryConversation(t, s).Selection
	f := &researchAdmissionFixture{Server: s, Scope: selection}
	f.RunID = researchRunID(t, s, ResearchOptions{Selections: []Selection{selection}, MinimalInference: true, Budget: 1, ProbeBudget: 1})
	if err := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='MINIMAL_INFERENCE'", f.RunID).Scan(&f.TaskID); err != nil {
		t.Fatal(err)
	}
	// Model a genuinely exhausted run; no extra HTTP is authorized by this
	// call. The price preflight must retain the formal budget wait reason.
	if _, err := s.Store.DB.Exec("UPDATE research_run_options SET used=1 WHERE run_id=?", f.RunID); err != nil {
		t.Fatal(err)
	}
	_, err := s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &selection})
	var limit *researchRunBudgetError
	if !errors.As(err, &limit) || limit.Metric != "HTTP" || catalogs.Load() != 0 || chats.Load() != 0 {
		t.Fatal("factory price preflight lost the budget wait or sent unauthorized HTTP", err, catalogs.Load(), chats.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM messages")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM research_evidence WHERE source_type='SCOPED_INFERENCE'")
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME", "additional_budget": 2}), 200)
	result, err := s.executeResearchTask(f.context(), f.RunID, "MINIMAL_INFERENCE", taskInput{Selection: &selection})
	if err != nil || result["outcome"] != "SUCCESS" || catalogs.Load() != 1 || chats.Load() != 1 {
		t.Fatal("factory research could not resume through the real guarded engine", result, err, catalogs.Load(), chats.Load())
	}
	f.assertRunUsage(t, 3, 1)
	assertSQLInt(t, s, 2, "SELECT http_requests FROM research_task_usage WHERE task_id=?", f.TaskID)
	assertSQLInt(t, s, 1, "SELECT inference_requests FROM research_task_usage WHERE task_id=?", f.TaskID)
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE outcome='SUCCESS'")
	assertSQLInt(t, s, 15, "SELECT COUNT(*) FROM my_models")
	// A deeper explicitly reviewed task also accounts for its price lookup;
	// it must not report just the model POST while hiding the catalog GET.
	f.RunID = researchRunID(t, s, ResearchOptions{Selections: []Selection{selection}, DeeperSelections: []Selection{selection}, MinimalInference: true, ObservationMinutes: 15, Budget: 10, ProbeBudget: 5})
	if err := s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=? AND kind='SEMANTIC_PROBE'", f.RunID).Scan(&f.TaskID); err != nil {
		t.Fatal(err)
	}
	report, err := s.executeResearchTask(f.context(), f.RunID, "SEMANTIC_PROBE", taskInput{Selection: &selection})
	if err != nil || report["additional_http_requests"] != float64(2) || report["additional_inference_requests"] != float64(1) || report["identity_status"] != "UNPROVEN" || catalogs.Load() != 2 || chats.Load() != 2 {
		t.Fatal("factory probe report did not account for both actual requests", report, err, catalogs.Load(), chats.Load())
	}
	f.assertRunUsage(t, 2, 1)
	assertSQLInt(t, s, 2, "SELECT http_requests FROM research_task_usage WHERE task_id=?", f.TaskID)
}

func TestFactoryKiloCatalogCancellationAndTimeoutDoNotBecomeBudgetOrInference(t *testing.T) {
	parallelResearchBudgetTest(t)
	t.Run("real-TLS-cancel", func(t *testing.T) {
		started := make(chan struct{})
		var catalogs, chats atomic.Int32
		s, _ := connectorDiscoveryFixture(t, func(w http.ResponseWriter, r *http.Request) {
			if r.Method != "GET" {
				chats.Add(1)
				t.Error("cancelled preflight sent model inference")
				return
			}
			catalogs.Add(1)
			close(started)
			<-r.Context().Done()
		})
		requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
		session := factoryConversation(t, s)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		finished := make(chan struct{})
		var result InferenceResult
		var code int
		go func() {
			result, code = s.execute(ctx, inferRequest{SessionID: session.ID, Prompt: "Synthetic cancelled input"}, "PLAYGROUND", func(string, any) error { return nil })
			close(finished)
		}()
		select {
		case <-started:
		case <-time.After(5 * time.Second):
			t.Fatal("catalog did not start")
		}
		cancel()
		select {
		case <-finished:
		case <-time.After(3 * time.Second):
			t.Fatal("catalog cancellation did not finish")
		}
		if code != 499 || result.Outcome != "CANCELLED" || catalogs.Load() != 1 || chats.Load() != 0 {
			t.Fatal("cancellation became an upstream failure or model request", code, result.Outcome, catalogs.Load(), chats.Load())
		}
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM messages")
		assertSQLInt(t, s, 15, "SELECT COUNT(*) FROM my_models")
	})
	t.Run("catalog-client-timeout", func(t *testing.T) {
		s := testServer(t)
		var calls atomic.Int32
		s.PublicClient = &http.Client{Transport: originsRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls.Add(1)
			if r.Method != "GET" || r.URL.Path != "/api/v1/models" {
				t.Error("timeout sent an unexpected request")
			}
			return nil, context.DeadlineExceeded
		})}
		requireCode(t, req(t, s, "POST", "/api/v1/defaults/kilo", map[string]bool{"confirm": true}), 201)
		session := factoryConversation(t, s)
		result, code := s.execute(context.Background(), inferRequest{SessionID: session.ID, Prompt: "Synthetic timed-out input"}, "PLAYGROUND", func(string, any) error { return nil })
		if code != 503 || result.Outcome != "KILO_CATALOG_UNAVAILABLE" || calls.Load() != 1 || result.Details["research_preflight_budget"] != nil {
			t.Fatal("catalog timeout was mistaken for a local budget wait", code, result.Outcome, calls.Load())
		}
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM telemetry")
	})
}
