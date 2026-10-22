package main

import (
	"context"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

func TestBraveResearchSlowInterruptedBodyBackoffStartsAfterFailure(t *testing.T) {
	var calls atomic.Int32
	clock := &atomic.Int64{}
	clock.Store(time.Now().UnixMilli())
	s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			clock.Add(120000)
			w.Header().Set("Content-Length", "10000")
			w.Write([]byte(`{"web":`))
			return
		}
		braveFixturePage(w, false)
	})
	s.scheduleClock = func() time.Time { return time.UnixMilli(clock.Load()) }
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "free API"}, Budget: 5})
	s.runResearchPending(context.Background())
	c := braveCheckpointForTest(t, s, run)
	if calls.Load() != 1 || c.NextAttemptAt < clock.Load()+30000 || retryTaskForTest(t, s, run).Status != "QUEUED" {
		t.Fatalf("slow response consumed backoff before failing: requests=%d progress=%+v", calls.Load(), c)
	}
	clock.Store(c.NextAttemptAt)
	s.runResearchPending(context.Background())
	if calls.Load() != 2 || !braveCheckpointForTest(t, s, run).Complete {
		t.Fatal("delayed page did not recover exactly once")
	}
}
