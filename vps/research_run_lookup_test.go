package main

import (
	"encoding/json"
	"fmt"
	"testing"
)

func TestResearchRunLookupKeepsExactOlderRecordAndScheduleScope(t *testing.T) {
	s := testServer(t)
	for i := 0; i < 105; i++ {
		if _, err := s.Store.DB.Exec("INSERT INTO research_runs(id,status,checkpoint,started_at) VALUES(?,'COMPLETED','',?)", fmt.Sprintf("lookup-%03d", i), i+1); err != nil {
			t.Fatal(err)
		}
	}
	read := func(path string) []ResearchRun {
		t.Helper()
		w := req(t, s, "GET", path, nil)
		requireCode(t, w, 200)
		var runs []ResearchRun
		if err := json.Unmarshal(w.Body.Bytes(), &runs); err != nil {
			t.Fatal(err)
		}
		return runs
	}
	if rows := read("/api/v1/research/runs"); len(rows) != 100 || rows[0].ID != "lookup-104" {
		t.Fatal("default bounded history changed", len(rows))
	}
	if rows := read("/api/v1/research/runs?id=lookup-000"); len(rows) != 1 || rows[0].ID != "lookup-000" {
		t.Fatalf("exact historical run must remain readable after 100 newer runs; count=%d", len(rows))
	}
	if rows := read("/api/v1/research/runs?id=lookup-000&schedule_id=unrelated"); len(rows) != 0 {
		t.Fatal("exact lookup escaped the requested schedule")
	}
	if rows := read("/api/v1/research/runs?id=missing"); len(rows) != 0 {
		t.Fatal("missing ID fell back to recent runs")
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/runs?id=bad%20id", nil), 400)
}
