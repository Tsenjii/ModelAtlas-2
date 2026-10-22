package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestReflectionDoesNotRewardRepeatedEmptyHTTP200(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[]}`))
	}))
	defer up.Close()
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	src := ResearchSource{ID: "empty-source", Name: "Empty", Kind: "JSON", URL: "https://empty.example/policy", Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data"}}
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	for i := 0; i < 3; i++ {
		researchRunID(t, s, ResearchOptions{SourceIDs: []string{src.ID}, Budget: 2})
		s.runResearchPending(context.Background())
	}
	var raw string
	if e := s.Store.DB.QueryRow("SELECT data_json FROM knowledge_versions WHERE kind='SOURCE_RELIABILITY' AND subject=? ORDER BY version DESC LIMIT 1", src.ID).Scan(&raw); e != nil {
		t.Fatal(e)
	}
	var data map[string]any
	json.Unmarshal([]byte(raw), &data)
	if data["weight"].(float64) >= 1 {
		t.Fatalf("three empty HTTP 200 runs still receive the default research priority: %s", raw)
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
}

func qualityFixture(t *testing.T) (*Server, *atomic.Bool, *atomic.Int32) {
	t.Helper()
	empty := &atomic.Bool{}
	calls := &atomic.Int32{}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/empty" || empty.Load() {
			w.Write([]byte(`{"data":[]}`))
			return
		}
		w.Write([]byte(`{"data":[{"model_id":"m","rpd":100,"tpd":1000000,"taiwan":"YES","free":true,"recurring":"DAILY_RESET"}]}`))
	}))
	t.Cleanup(up.Close)
	s := testServer(t)
	s.PublicClient = mappedFixture(up)
	seed(t, s, "https://quality.example/v1")
	for _, id := range []string{"empty", "useful"} {
		src := ResearchSource{ID: id, Name: id, Kind: "JSON", URL: "https://quality.example/" + id, Language: "en", Enabled: true, Config: SourceConfig{ItemsPointer: "/data", ProviderID: "p", Official: true}}
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	}
	return s, empty, calls
}

func qualityRounds(t *testing.T, s *Server, n int) []string {
	t.Helper()
	ids := []string{}
	for i := 0; i < n; i++ {
		id := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 4})
		s.runResearchPending(context.Background())
		ids = append(ids, id)
		assertSQLInt(t, s, 2, "SELECT used FROM research_run_options WHERE run_id=?", id)
	}
	return ids
}

func latestQualityVersion(t *testing.T, s *Server, subject string) KnowledgeVersion {
	t.Helper()
	response := req(t, s, "GET", "/api/v1/research/knowledge?kind=SOURCE_RELIABILITY", nil)
	requireCode(t, response, 200)
	var all []KnowledgeVersion
	if err := json.Unmarshal(response.Body.Bytes(), &all); err != nil {
		t.Fatal(err)
	}
	for _, v := range all {
		if v.Subject == subject {
			return v
		}
	}
	t.Fatal("missing quality version", subject)
	return KnowledgeVersion{}
}

func qualityEvaluation(t *testing.T, s *Server, v KnowledgeVersion) knowledgeEvaluation {
	t.Helper()
	r := req(t, s, "POST", "/api/v1/research/knowledge/evaluate", map[string]string{"id": v.ID})
	requireCode(t, r, 201)
	var e knowledgeEvaluation
	if err := json.Unmarshal(r.Body.Bytes(), &e); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestKnowledgeMeasuredTrialUsesFrozenVersionMonitorsAndRollsBackAfterRestart(t *testing.T) {
	s, empty, calls := qualityFixture(t)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "useful")
	e := qualityEvaluation(t, s, v)
	if e.Status != "READY_FOR_TRIAL" || e.ComparableRuns != 3 || e.Target.HTTP != 3 || e.Target.RecurringClaims != 3 || e.Proposed.Utility <= e.Baseline.Utility || e.Target.NewCandidates != 1 {
		t.Fatalf("incorrect measured comparison: %+v", e)
	}
	queued := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 4})
	activation := versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}
	r := req(t, s, "POST", "/api/v1/research/knowledge/activate", activation)
	requireCode(t, r, 200)
	var action map[string]any
	json.Unmarshal(r.Body.Bytes(), &action)
	var raw string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 1", queued).Scan(&raw)
	var task taskInput
	json.Unmarshal([]byte(raw), &task)
	if task.SourceID != "empty" || task.Strategy == nil || task.Strategy.VersionID != "" {
		t.Fatal("activation rewrote an already queued plan", raw)
	}
	s.runResearchPending(context.Background())
	// A mutable candidate review or metadata update cannot rewrite a saved
	// observation; all scored claims are frozen at the actual evidence write.
	s.Store.DB.Exec(`UPDATE research_evidence_details SET details_json='{}'`)
	before := e.Target.RecurringClaims
	if current := qualityEvaluation(t, s, v); current.Target.RecurringClaims < before {
		t.Fatal("current metadata erased prior local evidence receipts")
	}
	empty.Store(true)
	for i := 0; i < 3; i++ {
		id := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 4})
		s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 1", id).Scan(&raw)
		json.Unmarshal([]byte(raw), &task)
		if task.SourceID != "useful" || task.Strategy.VersionID != v.ID {
			t.Fatal("actual next run did not use the confirmed version", raw)
		}
		s.runResearchPending(context.Background())
	}
	s = reopenScheduleStore(t, s)
	r = req(t, s, "GET", "/api/v1/research/knowledge?kind=SOURCE_RELIABILITY", nil)
	requireCode(t, r, 200)
	var all []KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &all)
	found := false
	for _, current := range all {
		if current.ID != v.ID {
			continue
		}
		found = true
		if current.Monitor == nil || current.Monitor.Status != "DEGRADED" || current.Monitor.Observed.Runs != 3 || current.ActivationActionID != str(action["action_id"]) {
			t.Fatalf("monitor/rollback did not survive restart: %+v", current)
		}
	}
	if !found {
		t.Fatal("active version not present")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": action["action_id"], "confirm": true}), 200)
	if s.activeKnowledge(context.Background(), v.Kind, v.Subject) != nil {
		t.Fatal("persistent rollback did not restore builtin priority")
	}
	if calls.Load() != 14 {
		t.Fatal("evaluation/monitor/restart/rollback sent extra requests", calls.Load())
	}
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM my_models")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM action_ledger WHERE state='UNDONE'")
}

func TestKnowledgeEvaluationRejectsInsufficientStaleRegressionAndChangedSource(t *testing.T) {
	t.Run("insufficient-and-new-data", func(t *testing.T) {
		s, _, calls := qualityFixture(t)
		qualityRounds(t, s, 1)
		v := latestQualityVersion(t, s, "useful")
		e := qualityEvaluation(t, s, v)
		if e.Status != "INSUFFICIENT" {
			t.Fatal(e.Status)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}), 409)
		qualityRounds(t, s, 2)
		v = latestQualityVersion(t, s, "useful")
		e = qualityEvaluation(t, s, v)
		qualityRounds(t, s, 1)
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}), 409)
		if calls.Load() != 8 {
			t.Fatal("rejected activation sent HTTP")
		}
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
	})
	t.Run("changed-source-and-active-plan", func(t *testing.T) {
		s, _, _ := qualityFixture(t)
		qualityRounds(t, s, 3)
		v := latestQualityVersion(t, s, "useful")
		e := qualityEvaluation(t, s, v)
		// Even an unrelated baseline priority change invalidates the replay.
		manual := KnowledgeVersion{Kind: "SOURCE_RELIABILITY", Subject: "empty", Data: map[string]any{"source_id": "empty", "weight": 2}, EvidenceIDs: v.EvidenceIDs, Reason: "manual priority trial"}
		r := req(t, s, "POST", "/api/v1/research/knowledge", manual)
		requireCode(t, r, 201)
		json.Unmarshal(r.Body.Bytes(), &manual)
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: manual.ID, Confirm: true}), 200)
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}), 409)
		src, _ := s.source(context.Background(), "useful")
		src.URL = "https://quality.example/new-schema"
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
		if e = qualityEvaluation(t, s, v); e.Status != "SOURCE_CHANGED" {
			t.Fatal("old source observations accepted", e)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}), 409)
	})
	t.Run("measured-regression", func(t *testing.T) {
		s, _, _ := qualityFixture(t)
		qualityRounds(t, s, 3)
		v := latestQualityVersion(t, s, "useful")
		// Make the empty path the active comparison baseline; lowering useful
		// priority further would keep it behind and cannot fake improvement.
		r := req(t, s, "POST", "/api/v1/research/knowledge", KnowledgeVersion{Kind: v.Kind, Subject: v.Subject, Data: map[string]any{"source_id": v.Subject, "weight": 4}, EvidenceIDs: v.EvidenceIDs, Reason: "manual comparison baseline"})
		requireCode(t, r, 201)
		var active KnowledgeVersion
		json.Unmarshal(r.Body.Bytes(), &active)
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: active.ID, Confirm: true}), 200)
		r = req(t, s, "POST", "/api/v1/research/knowledge", KnowledgeVersion{Kind: v.Kind, Subject: v.Subject, Data: map[string]any{"source_id": v.Subject, "weight": .25, "scope_hash": v.Data["scope_hash"]}, EvidenceIDs: v.EvidenceIDs, Reason: "counterexample priority"})
		requireCode(t, r, 201)
		json.Unmarshal(r.Body.Bytes(), &v)
		e := qualityEvaluation(t, s, v)
		if e.Status != "REGRESSION" || e.Proposed.Utility >= e.Baseline.Utility {
			t.Fatal("regression passed", e)
		}
		requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, ExpectedID: active.ID, EvaluationID: e.ID, Confirm: true}), 409)
	})
}

func TestKnowledgeQualityAdmissionCancellationSyncAndV21Migration(t *testing.T) {
	s, _, calls := qualityFixture(t)
	qualityRounds(t, s, 1)
	// Cancellation contains no retrieval failure and cannot become a completed
	// independent sample merely because it has a reflection report.
	id := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 4})
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": id, "action": "CANCEL"}), 200)
	s.runResearchPending(context.Background())
	v := latestQualityVersion(t, s, "useful")
	e := qualityEvaluation(t, s, v)
	if e.Target.Runs != 1 || e.Target.Failures != 0 || calls.Load() != 2 {
		t.Fatal("cancel polluted quality", e, calls.Load())
	}
	// All budget counters roll back when the task usage receipt cannot be saved.
	id = researchRunID(t, s, ResearchOptions{SourceIDs: []string{"useful"}, Budget: 4})
	var task string
	s.Store.DB.QueryRow("SELECT id FROM research_tasks WHERE run_id=?", id).Scan(&task)
	ctx := context.WithValue(context.WithValue(context.Background(), researchBudgetKey{}, id), researchTaskBudgetKey{}, "missing-task")
	if err := s.chargeNetwork(ctx, false); err == nil {
		t.Fatal("missing task receipt accepted")
	}
	assertSQLInt(t, s, 0, "SELECT used FROM research_run_options WHERE run_id=?", id)
	ctx = context.WithValue(ctx, researchTaskBudgetKey{}, task)
	if err := s.chargeNetwork(ctx, false); err != nil {
		t.Fatal(err)
	}
	assertSQLInt(t, s, 1, "SELECT http_requests FROM research_task_usage WHERE task_id=?", task)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	peer := testServer(t)
	workspacePairForTest(t, s, peer, host, false)
	workspaceRunForTest(t, peer)
	for _, table := range []string{"research_plan_bindings", "research_task_usage", "research_local_evidence", "research_quality_observations", "knowledge_evaluations", "knowledge_activation_evaluations", "knowledge_activation_epochs"} {
		assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM "+table)
		for _, synced := range workspaceTables {
			if table == synced {
				t.Fatal("local authority sync-enabled", table)
			}
		}
	}
	peer.retryMissingReflections(context.Background())
	peer.runResearchPending(context.Background())
	assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM research_quality_observations")
	// Upgrade the actual prior schema, including history and a vault credential.
	legacy := testServer(t)
	seed(t, legacy, "https://quality.example/v1")
	for _, table := range []string{"knowledge_activation_epochs", "knowledge_activation_evaluations", "knowledge_evaluations", "research_quality_observations", "research_local_evidence", "research_task_usage", "research_plan_bindings"} {
		if _, err := legacy.Store.DB.Exec("DROP TABLE " + table); err != nil {
			t.Fatal(err)
		}
	}
	legacy.Store.DB.Exec("PRAGMA user_version=21")
	legacy = reopenScheduleStore(t, legacy)
	assertSQLInt(t, legacy, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, legacy, 1, "SELECT COUNT(*) FROM credentials")
	backups, err := filepath.Glob(filepath.Join(filepath.Dir(legacy.Store.path), "upgrade-backups", fmt.Sprintf("schema-21-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(backups) != 1 || verifySnapshot(backups[0]) != nil {
		t.Fatal("missing prior-schema snapshot", backups, err)
	}
	for _, table := range []string{"research_plan_bindings", "research_task_usage", "research_local_evidence", "research_quality_observations", "knowledge_evaluations"} {
		assertSQLInt(t, legacy, 0, "SELECT COUNT(*) FROM "+table)
	}
	if strings.Contains(req(t, s, "GET", "/api/v1/research/knowledge", nil).Body.String(), "top-secret-example-key") {
		t.Fatal("secret entered research assessment")
	}
}

func TestKnowledgeRestoredVersionStartsNewMonitoringEpoch(t *testing.T) {
	s, _, calls := qualityFixture(t)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "useful")
	e := qualityEvaluation(t, s, v)
	r := req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true})
	requireCode(t, r, 200)
	v = latestQualityVersion(t, s, "useful")
	if v.ActiveEpoch == "" {
		t.Fatal("activation has no local epoch")
	}
	originalEpoch := v.ActiveEpoch
	old := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 4})
	manual := KnowledgeVersion{Kind: v.Kind, Subject: v.Subject, Data: map[string]any{"source_id": v.Subject, "weight": 2}, EvidenceIDs: v.EvidenceIDs, Reason: "temporary explicit comparison trial"}
	r = req(t, s, "POST", "/api/v1/research/knowledge", manual)
	requireCode(t, r, 201)
	json.Unmarshal(r.Body.Bytes(), &manual)
	r = req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: manual.ID, ExpectedID: v.ID, Confirm: true})
	requireCode(t, r, 200)
	var action map[string]any
	json.Unmarshal(r.Body.Bytes(), &action)
	requireCode(t, req(t, s, "POST", "/api/v1/versions/undo", map[string]any{"id": action["action_id"], "confirm": true}), 200)
	// Restoring the same ID is a new observation period. The old queued task
	// still executes its approved version/epoch, but cannot inflate this trial.
	s.runResearchPending(context.Background())
	var raw string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? ORDER BY rowid LIMIT 1", old).Scan(&raw)
	var input taskInput
	json.Unmarshal([]byte(raw), &input)
	if input.Strategy == nil || input.Strategy.EpochID != originalEpoch {
		t.Fatal("pending task epoch was rewritten", raw)
	}
	s = reopenScheduleStore(t, s)
	r = req(t, s, "GET", "/api/v1/research/knowledge", nil)
	requireCode(t, r, 200)
	var all []KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &all)
	for _, current := range all {
		if current.ID != v.ID {
			continue
		}
		if !current.Active || current.ActiveEpoch == originalEpoch || current.ActiveEpoch == "" || current.Monitor == nil || current.Monitor.Observed.Runs != 0 {
			t.Fatal("old activation results mixed into restored trial", current)
		}
	}
	qualityRounds(t, s, 3)
	r = req(t, s, "GET", "/api/v1/research/knowledge", nil)
	requireCode(t, r, 200)
	json.Unmarshal(r.Body.Bytes(), &all)
	for _, current := range all {
		if current.ID == v.ID && (current.Monitor == nil || current.Monitor.Observed.Runs != 3) {
			t.Fatal("new epoch lost actual results", current)
		}
	}
	if calls.Load() != 14 {
		t.Fatal("epoch tracking triggered HTTP", calls.Load())
	}
	// Upgrade an internal v22 candidate with an active version and old receipts.
	if _, err := s.Store.DB.Exec("DROP TABLE knowledge_activation_epochs; PRAGMA user_version=22"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM knowledge_activation_epochs")
	assertSQLInt(t, s, 14, "SELECT COUNT(*) FROM research_quality_observations")
}

func TestKnowledgeEvaluationKeepsAgedHistoryAndExplainsChangedActiveSource(t *testing.T) {
	s, _, _ := qualityFixture(t)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "useful")
	evaluation := qualityEvaluation(t, s, v)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: evaluation.ID, Confirm: true}), 200)
	src, _ := s.source(context.Background(), "useful")
	src.URL = "https://quality.example/changed"
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	id := researchRunID(t, s, ResearchOptions{SourceIDs: []string{"useful"}, Budget: 2})
	var raw string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=?", id).Scan(&raw)
	var input taskInput
	json.Unmarshal([]byte(raw), &input)
	if input.Strategy == nil || input.Strategy.VersionID != "" || input.Strategy.IgnoredVersionID != v.ID || input.Strategy.Reason == "" {
		t.Fatal("changed source silently borrowed old strategy", raw)
	}
	r := req(t, s, "GET", "/api/v1/research/knowledge", nil)
	requireCode(t, r, 200)
	var all []KnowledgeVersion
	json.Unmarshal(r.Body.Bytes(), &all)
	for _, item := range all {
		if item.ID == v.ID && (item.Monitor == nil || item.Monitor.Status != "SOURCE_CHANGED") {
			t.Fatal("inactive strategy effect hidden from review", item)
		}
	}
	// Expiry changes comparison eligibility; it does not purge source history.
	src.URL = "https://quality.example/useful"
	requireCode(t, req(t, s, "POST", "/api/v1/research/sources", src), 200)
	if _, err := s.Store.DB.Exec("UPDATE research_quality_observations SET observation_json=json_set(observation_json,'$.observed_at',?)", now()-31*24*60*60000); err != nil {
		t.Fatal(err)
	}
	e := qualityEvaluation(t, s, v)
	if e.Status != "INSUFFICIENT" || e.Target.Runs != 0 {
		t.Fatal("expired history treated as fresh", e)
	}
	assertSQLInt(t, s, 6, "SELECT COUNT(*) FROM research_quality_observations")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/research/knowledge/evaluate", strings.NewReader(`{"id":"x"}`)))
	requireCode(t, w, 401)
}

func TestKnowledgeScheduledContinuationRetainsItsPlannedVersion(t *testing.T) {
	s, _, calls := qualityFixture(t)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "useful")
	e := qualityEvaluation(t, s, v)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, EvaluationID: e.ID, Confirm: true}), 200)
	clock := &atomic.Int64{}
	clock.Store(now())
	s.scheduleClock = func() time.Time { return time.UnixMilli(clock.Load()) }
	saveScheduleForTest(t, s, "quality-schedule", ResearchOptions{SourceIDs: []string{"empty", "useful"}, Budget: 1}, 10)
	controlScheduleForTest(t, s, "quality-schedule", "ENABLE")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	first := scheduleForTest(t, s, "quality-schedule")
	if first.RunUsed != 1 || first.RunStatus != "BUDGET_EXHAUSTED" {
		t.Fatal("round budget did not stop pending work", first)
	}
	manual := KnowledgeVersion{Kind: "SOURCE_RELIABILITY", Subject: "empty", Data: map[string]any{"source_id": "empty", "weight": 4}, EvidenceIDs: v.EvidenceIDs, Reason: "explicit priority trial between scheduled rounds"}
	r := req(t, s, "POST", "/api/v1/research/knowledge", manual)
	requireCode(t, r, 201)
	json.Unmarshal(r.Body.Bytes(), &manual)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: manual.ID, Confirm: true}), 200)
	clock.Add(15 * 60000)
	s.researchScheduleTick(context.Background())
	second := scheduleForTest(t, s, "quality-schedule")
	if second.LastRunID == first.LastRunID {
		t.Fatal("continuation not created")
	}
	var raw string
	s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=?", second.LastRunID).Scan(&raw)
	var input taskInput
	json.Unmarshal([]byte(raw), &input)
	if input.SourceID != "empty" || input.Strategy == nil || input.Strategy.VersionID != "" || input.Strategy.Weight != 1 {
		t.Fatal("continuation borrowed a later global activation", raw)
	}
	s.runResearchPending(context.Background())
	assertSQLInt(t, s, 1, "SELECT http_requests FROM research_task_usage u JOIN research_tasks t ON t.id=u.task_id WHERE t.run_id=?", second.LastRunID)
	if calls.Load() != 8 {
		t.Fatal("continuation repeated completed work", calls.Load())
	}
}

func TestKnowledgeQualityRetainsFalseFreeCounterexamplesWithoutReward(t *testing.T) {
	s, _, _ := qualityFixture(t)
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/empty" {
			w.Write([]byte(`{"data":[{"model_id":"trial","free":true,"recurring":"ONE_TIME_TRIAL","rpd":9999,"tpd":9000000},{"model_id":"paid","free":false,"recurring":"DAILY_RESET","rpd":9999,"tpd":9000000},{"model_id":"conflict","free":true,"recurring":"DAILY_RESET","rpd":9999,"tpd":9000000},{"model_id":"conflict","free":false,"recurring":"DAILY_RESET","rpd":9999,"tpd":9000000}]}`))
			return
		}
		w.Write([]byte(`{"data":[{"model_id":"m","free":true,"recurring":"DAILY_RESET","rpd":100,"tpd":1000000}]}`))
	}))
	defer up.Close()
	s.PublicClient = mappedFixture(up)
	qualityRounds(t, s, 3)
	v := latestQualityVersion(t, s, "empty")
	e := qualityEvaluation(t, s, v)
	if e.Target.RecurringClaims != 0 || e.Target.NegativeClaims != 9 || e.Target.Utility != 0 || e.Target.NewCandidates != 3 || v.Data["weight"].(float64) >= 1 {
		t.Fatal("false-free counterexamples rewarded as positive discoveries", e, v.Data)
	}
	var raw string
	s.Store.DB.QueryRow("SELECT observation_json FROM research_quality_observations WHERE metric_key=? ORDER BY rowid DESC LIMIT 1", knowledgeMetricKey(v.Kind, v.Subject)).Scan(&raw)
	var q researchQuality
	json.Unmarshal([]byte(raw), &q)
	if q.DuplicateHits != 1 || q.Candidates != 3 || q.RecurringClaims != 0 || q.NegativeClaims != 3 {
		t.Fatal("conflicting duplicate inflated research quality", raw)
	}
	assertSQLInt(t, s, 36, "SELECT COUNT(*) FROM research_local_evidence WHERE json_extract(record_json,'$.source_id')='empty'")
	assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM knowledge_active")
}
