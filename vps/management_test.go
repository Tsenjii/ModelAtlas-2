package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFullHistorySQLStatisticsAndPagedRetention(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	ctx := context.Background()
	tx, e := s.Store.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	for i := 1; i <= 1000; i++ {
		_, e = tx.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,ttft_ms,latency_ms,details_json,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND','SUCCESS',?,?,?,?)", fmt.Sprint(i), i, i*2, fmt.Sprintf(`{"tps":%d}`, i), now()-2000+int64(i))
		if e != nil {
			t.Fatal(e)
		}
	}
	for _, outcome := range []string{"CANCELLED", "INTERRUPTED", "NETWORK"} {
		_, e = tx.Exec("INSERT INTO telemetry(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,operation,source,outcome,details_json,created_at) VALUES(?,'p','a','c','m','','CHAT_COMPLETIONS','CHAT_COMPLETIONS','PLAYGROUND',?,'{}',?)", outcome, outcome, now()-1)
		if e != nil {
			t.Fatal(e)
		}
	}
	if tx.Commit() != nil {
		t.Fatal("commit")
	}
	stats := s.performanceBetween(ctx, v, 0, now()+1)
	if stats["samples"] != 1001 || stats["cancelled"] != 1 || stats["interrupted"] != 1 || stats["success_rate"] != float64(1000)/1001 {
		t.Fatalf("wrong complete history statistics: %v", stats)
	}
	for name, want := range map[string]float64{"mean": 500.5, "p50": 500.5, "p90": 900.1, "p95": 950.05} {
		got := stats["ttft"].(map[string]float64)[name]
		if got-want > 0.00001 || want-got > 0.00001 {
			t.Fatalf("%s=%v, want %v", name, got, want)
		}
	}
	if len(stats["history"].([]map[string]any)) != 400 || stats["history_total"] != 1001 {
		t.Fatal("chart cap polluted full statistics")
	}
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM telemetry").Scan(&count)
	if count != 1003 {
		t.Fatal("raw history lost")
	}
	var page []map[string]any
	_ = json.Unmarshal(req(t, s, "GET", "/api/v1/telemetry?limit=100&offset=900", nil).Body.Bytes(), &page)
	if len(page) != 100 {
		t.Fatal("retained history cannot be paged")
	}
	s.Store.DB.Exec("UPDATE telemetry SET details_json='invalid' WHERE id='1'")
	if s.performanceBetween(ctx, v, 0, now()+1)["tps"] == nil {
		t.Fatal("invalid details poisoned valid numeric samples")
	}
}

func TestScheduledSyncAndEvidenceBasedNotificationEpoch(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	ctx := context.Background()
	s.Store.DB.Exec("UPDATE sync_history SET status='SUCCESS' WHERE status='PENDING'")
	s.backgroundTick(ctx)
	var queued int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history WHERE status='PENDING'").Scan(&queued)
	if queued != 0 {
		t.Fatal("background work default enabled")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/settings", map[string]any{"background": BackgroundSettings{Enabled: true, IntervalMinutes: 15, MaxScopes: 1}, "notifications": true, "notification_rules": NotificationRules{BecameFree: true, PriceChanged: true, Outage: true, Recovery: true, QuotaRemainingBelow: 100}}), 200)
	s.backgroundTick(ctx)
	s.backgroundTick(ctx)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history WHERE status='PENDING'").Scan(&queued)
	if queued != 1 {
		t.Fatal("schedule duplicated pending job or ignored interval")
	}
	var catalogCalls int
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			catalogCalls++
			w.Write([]byte(`{"data":[{"id":"catalog-only"}]}`))
		} else {
			http.NotFound(w, r)
		}
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	s.runPending(ctx)
	if catalogCalls != 1 {
		t.Fatal("scheduled catalog runner is not real")
	}
	var members int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&members)
	if members != 1 {
		t.Fatal("background catalog added membership")
	}
	price := func(amount string) {
		_ = s.observe(ctx, v, "PRICING", "MANUAL", "SUCCESS", map[string]any{"price": PriceLegs{Input: &amount, Output: &amount, FixedAbsent: true, Unit: "USD/1K tokens"}})
	}
	price("1")
	s.checkNotificationRules(ctx)
	price("0")
	s.checkNotificationRules(ctx)
	s.checkNotificationRules(ctx)
	var free, changed int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM notification_events WHERE kind='BECAME_FREE'").Scan(&free)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM notification_events WHERE kind='PRICE_CHANGED'").Scan(&changed)
	if free != 1 || changed != 1 {
		t.Fatal("missing/doubled confirmed transition")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "synthetic-replacement", "confirm": true}), 200)
	if s.offerSummary(ctx, v)["price"] != nil {
		t.Fatal("old credential pricing borrowed")
	}
	s.checkNotificationRules(ctx)
	price("1")
	s.checkNotificationRules(ctx)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM notification_events WHERE kind='PRICE_CHANGED'").Scan(&changed)
	if changed != 1 {
		t.Fatal("rotation borrowed old transition")
	}
}

func TestSafeBackupV2RoundTripAndUnsafeAtomicRollback(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	_, e := s.Store.DB.Exec("INSERT INTO entity_notes VALUES('MODEL',?,'kept notes','[\"favorite-tag\"]')", scopeKey(v))
	if e != nil {
		t.Fatal(e)
	}
	s.Store.DB.Exec("INSERT INTO membership_state VALUES(?,'HIDDEN',1,1)", scopeKey(v))
	s.Store.DB.Exec("INSERT INTO aliases VALUES('p','m','canonical-m','MANUAL')")
	s.Store.DB.Exec("INSERT INTO recipe_versions VALUES('recipe','p',1,'{\"supports_tools\":true}','validated recipe',1)")
	requireCode(t, req(t, s, "POST", "/api/v1/settings", map[string]any{"background": BackgroundSettings{Enabled: true, IntervalMinutes: 60, MaxScopes: 2}, "notifications": true}), 200)
	response := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, response, 200)
	if strings.Contains(response.Body.String(), "top-secret") || strings.Contains(response.Body.String(), "encrypted_key") {
		t.Fatal("backup leaked vault")
	}
	var backup BackupDocument
	if json.Unmarshal(response.Body.Bytes(), &backup) != nil || backup.Version != 2 || len(backup.Notes) != 1 || len(backup.Recipes) != 1 {
		t.Fatal("incomplete backup")
	}
	restored := testServer(t)
	requireCode(t, req(t, restored, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 201)
	var notes, state string
	var enabled bool
	restored.Store.DB.QueryRow("SELECT notes FROM entity_notes").Scan(&notes)
	restored.Store.DB.QueryRow("SELECT state FROM membership_state").Scan(&state)
	restored.Store.DB.QueryRow("SELECT enabled FROM credentials").Scan(&enabled)
	if notes != "kept notes" || state != "HIDDEN" || enabled || loadSetting(restored, context.Background(), "notifications", true) || loadSetting(restored, context.Background(), "background", BackgroundSettings{}).Enabled {
		t.Fatal("restored state or secure defaults wrong")
	}
	backup.Recipes[0].Raw = `{"system_prompt":"unsafe"}`
	invalid := testServer(t)
	requireCode(t, req(t, invalid, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 409)
	var n int
	invalid.Store.DB.QueryRow("SELECT COUNT(*) FROM providers").Scan(&n)
	if n != 0 {
		t.Fatal("partial restore after invalid recipe")
	}
}
