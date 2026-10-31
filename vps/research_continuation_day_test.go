package main

import (
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

type researchContinuationCharge struct {
	at    time.Time
	count int
}

func assertResearchContinuationDayReceipts(t *testing.T, s *Server, schedule, requestBudget string, charges ...researchContinuationCharge) {
	t.Helper()
	wantDay := map[string]int{}
	for _, charge := range charges {
		wantDay[charge.at.UTC().Format("2006-01-02")] += charge.count
	}
	want := map[string]int{}
	for day, used := range wantDay {
		want[day+"|global"] = used
		want[day+"|schedule:"+schedule] = used
	}
	rows, err := s.Store.DB.Query("SELECT day,scope,used FROM research_schedule_daily")
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]int{}
	for rows.Next() {
		var day, scope string
		var used int
		if err := rows.Scan(&day, &scope, &used); err != nil {
			rows.Close()
			t.Fatal(err)
		}
		got[day+"|"+scope] = used
	}
	err = rows.Err()
	rows.Close()
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("exact daily receipts: got %#v, want %#v, error %v", got, want, err)
	}
	if requestBudget == "" {
		return
	}
	rows, err = s.Store.DB.Query("SELECT date,used FROM request_budgets WHERE name=?", requestBudget)
	if err != nil {
		t.Fatal(err)
	}
	got = map[string]int{}
	for rows.Next() {
		var day string
		var used int
		if err := rows.Scan(&day, &used); err != nil {
			rows.Close()
			t.Fatal(err)
		}
		got[day] = used
	}
	err = rows.Err()
	rows.Close()
	if err != nil || !reflect.DeepEqual(got, wantDay) {
		t.Fatalf("exact %s daily request receipts: got %#v, want %#v, error %v", requestBudget, got, wantDay, err)
	}
}

// Same-day continuation checks need a deterministic time with enough room for
// every original Retry-After. A separate controlled case exercises midnight.
func researchContinuationAnchor(hour, minute int) time.Time {
	day := time.Now().UTC()
	return time.Date(day.Year(), day.Month(), day.Day(), hour, minute, 0, 0, time.UTC)
}

func traceResearchContinuationDays(t *testing.T, s *Server, clock *atomic.Int64, start time.Time) {
	t.Helper()
	if !t.Failed() {
		return
	}
	rows, err := s.Store.DB.Query("SELECT day,scope,used FROM research_schedule_daily ORDER BY day,scope")
	if err != nil {
		t.Log("daily evidence unavailable", err)
		return
	}
	defer rows.Close()
	t.Logf("controlled UTC start=%s final=%s", start.Format(time.RFC3339), time.UnixMilli(clock.Load()).UTC().Format(time.RFC3339))
	for rows.Next() {
		var day, scope string
		var used int
		if err := rows.Scan(&day, &scope, &used); err == nil {
			t.Logf("actual daily receipt day=%s scope=%s used=%d", day, scope, used)
		}
	}
}
