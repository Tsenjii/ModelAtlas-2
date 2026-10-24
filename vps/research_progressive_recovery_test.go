package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func TestResearchProgressiveLocalAuthoritySyncPortableAndSchema30Upgrade(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	run := researchRunID(t, s, f.options())
	s.runResearchPending(context.Background())
	peer := testServer(t)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	workspacePairForTest(t, s, peer, host, false)
	workspaceRunForTest(t, peer)
	tables := []string{"research_probe_observations", "research_probe_scopes", "research_probe_plans", "research_candidate_revalidations"}
	for _, name := range tables {
		if workspaceAllowed(name) {
			t.Fatal("local progressive authority entered sync", name)
		}
		assertSQLInt(t, peer, 0, "SELECT COUNT(*) FROM "+name)
	}
	before := f.Calls.Load()
	peer.runResearchPending(context.Background())
	if f.Calls.Load() != before {
		t.Fatal("foreign progressive history executed")
	}
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	var document BackupDocument
	if json.Unmarshal(backup.Body.Bytes(), &document) != nil {
		t.Fatal("invalid portable backup")
	}
	portable := testServer(t)
	requireCode(t, req(t, portable, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	for _, name := range tables {
		assertSQLInt(t, portable, 0, "SELECT COUNT(*) FROM "+name)
	}
	for _, name := range tables {
		if _, err := s.Store.DB.Exec("DROP TABLE " + name); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Store.DB.Exec("PRAGMA user_version=30"); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, 31, "PRAGMA user_version")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM telemetry")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_runs WHERE id=? AND status='COMPLETED'", run)
	s.workerTick(context.Background())
	if f.Calls.Load() != before {
		t.Fatal("upgrade invented progressive authority or replayed completed work")
	}
	for _, name := range tables {
		assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+name)
	}
	files, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-30-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("schema30 upgrade lost its consistent original snapshot", err)
	}
}

func TestResearchProgressiveCancelRetainsAdmissionAndUngradedObservation(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	f.BlockSemantic.Store(true)
	defer close(f.Release)
	run := researchRunID(t, s, f.options())
	done := make(chan struct{})
	go func() { defer close(done); s.runResearchPending(context.Background()) }()
	select {
	case <-f.Started:
	case <-time.After(3 * time.Second):
		t.Fatal("real semantic request did not start")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": run, "action": "CANCEL"}), 200)
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not stop the active progressive request")
	}
	reports := probeReportsForTest(t, s, run)
	if len(reports) != 1 || reports[0].State != "REQUEST_CANCELLED" || reports[0].Grade != nil || reports[0].AdditionalProbes != 1 || reports[0].ReportedUSD != nil || reports[0].SampleID == "" {
		t.Fatal("cancel lost its ungraded, unknown-cost audit trail", reports)
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_resource_admissions")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE id=? AND outcome='CANCELLED'", reports[0].SampleID)
	for i := 0; i < 3; i++ {
		s.workerTick(context.Background())
	}
	if f.Calls.Load() != 3 {
		t.Fatal("cancel automatically replayed paid work", f.Calls.Load())
	}
}

func TestResearchProgressiveInterruptedPrivateDepthNeverAutomaticallyReplays(t *testing.T) {
	parallelProgressiveTest(t)
	f := newResearchProbeFixture(t, true)
	s := f.Server
	requireCode(t, req(t, s, "POST", "/api/v1/research/schedules", map[string]any{"id": "progressive", "name": "Progressive interruption", "interval_minutes": 15, "daily_http_limit": 20, "options": f.options(), "private": schedulePrivatePolicy{DailyInferences: 5}}), 200)
	enablePrivateSchedule(t, s, "progressive")
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	v := scheduleForTest(t, s, "progressive")
	if f.Calls.Load() != 4 {
		t.Fatal("actual original probes missing", f.Calls.Load())
	}
	// Simulate losing the task-completion write after both requests and their
	// admissions/report receipts were already durably saved.
	if _, err := s.Store.DB.Exec("UPDATE research_runs SET status='RUNNING' WHERE id=?", v.LastRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Store.DB.Exec("UPDATE research_tasks SET status='RUNNING' WHERE run_id=? AND kind IN ('SEMANTIC_PROBE','TOOL_PROBE')", v.LastRunID); err != nil {
		t.Fatal(err)
	}
	s = reopenScheduleStore(t, s)
	s.researchScheduleTick(context.Background())
	s.runResearchPending(context.Background())
	if f.Calls.Load() != 4 {
		t.Fatal("interrupted private depth was automatically sent again", f.Calls.Load())
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_tasks WHERE run_id=? AND kind IN ('SEMANTIC_PROBE','TOOL_PROBE') AND status='FAILED' AND json_extract(result_json,'$.reason')='interrupted private inference is never automatically replayed'", v.LastRunID)
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_schedule_inference_admissions")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_resource_admissions")
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_probe_observations")
	if len(probeReportsForTest(t, s, v.LastRunID)) != 2 {
		t.Fatal("interruption removed original observations")
	}
}
