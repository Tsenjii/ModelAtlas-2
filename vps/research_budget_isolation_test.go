package main

import (
	"context"
	"testing"
	"time"
)

var researchBudgetTestSlots = make(chan struct{}, 2)

// These flows own their stores, TLS fixtures, policies and clocks. Run at most
// two together; retain every original migration, request, workload and timeout.
// No empty database, response, observation or result is cached between tests.
func parallelResearchBudgetTest(t *testing.T) {
	t.Helper()
	t.Parallel()
	researchBudgetTestSlots <- struct{}{}
	t.Cleanup(func() { <-researchBudgetTestSlots })
}

func TestResearchBudgetFixtureIsolationAcrossPoliciesClocksAndRestart(t *testing.T) {
	parallelResearchBudgetTest(t)
	a, b := newResearchAdmissionFixture(t, 10, 5), newResearchAdmissionFixture(t, 10, 5)
	a.Server.runPending(context.Background())
	b.Server.runPending(context.Background())
	a.Calls.Store(0)
	b.Calls.Store(0)
	a.Server.scheduleClock = func() time.Time { return time.Date(2026, 10, 23, 12, 0, 0, 0, time.UTC) }
	b.Server.scheduleClock = func() time.Time { return time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC) }
	setInferencePolicyForTest(t, a.Server, 0, 0)
	setInferencePolicyForTest(t, b.Server, 5, 5)
	if result, code := inferBudgetAttempt(t, a.Server, a.context(), a.Scope, "CRAWLER_VERIFY"); code != 429 || result.Outcome != "RESEARCH_BUDGET_EXHAUSTED" {
		t.Fatal("first fixture did not retain its independent refusal", code, result.Outcome)
	}
	if result, code := inferBudgetAttempt(t, b.Server, b.context(), b.Scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("first fixture policy leaked into a different real TLS engine", code, result.Outcome)
	}
	assertSQLInt(t, a.Server, 0, "SELECT COUNT(*) FROM research_inference_admissions")
	assertSQLInt(t, b.Server, 1, "SELECT COUNT(*) FROM research_inference_admissions WHERE day='2026-10-24'")
	a.Server = reopenScheduleStore(t, a.Server)
	if result, code := inferBudgetAttempt(t, b.Server, b.context(), b.Scope, "CRAWLER_VERIFY"); code != 0 || result.Outcome != "SUCCESS" {
		t.Fatal("another fixture's restart affected this engine", code, result.Outcome)
	}
	assertSQLInt(t, b.Server, 2, "SELECT COUNT(*) FROM research_resource_admissions")
	if a.Calls.Load() != 0 || b.Calls.Load() != 2 {
		t.Fatal("fixture transport or request state crossed engines", a.Calls.Load(), b.Calls.Load())
	}
}
