package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func checkinConfig(t *testing.T, s *Server, id string) {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{"provider_id": id, "config": ProviderConfig{Adapter: "GENERIC", AuthMode: "BEARER", RequiresCheckin: true, CheckinURL: "https://example.com/checkin"}}), 200)
}
func accountCheckinBody(provider, account string) map[string]any {
	date, _ := checkinDate("Asia/Taipei", time.Now())
	return map[string]any{"provider_id": provider, "account_id": account, "date": date, "timezone": "Asia/Taipei"}
}
func TestAccountCheckinIsolatedIdempotentAndLegacyNeverCompletesAccounts(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); http.Error(w, "unexpected upstream", 500) }))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	seed(t, s, up.URL+"/v1")
	checkinConfig(t, s, "p")
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "B"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/providers", Provider{ID: "q", Name: "Q", BaseURL: up.URL + "/v1", AuthMode: "BEARER"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "a", ProviderID: "q", Label: "same ID"}), 201)
	checkinConfig(t, s, "q")
	requireCode(t, req(t, s, "POST", "/api/v1/checkins", map[string]any{"provider_id": "p", "timezone": "Asia/Taipei"}), 200)
	var response struct{ Accounts []accountCheckin }
	read := func() {
		w := req(t, s, "GET", "/api/v1/checkins/accounts?timezone=Asia%2FTaipei", nil)
		requireCode(t, w, 200)
		if e := json.Unmarshal(w.Body.Bytes(), &response); e != nil {
			t.Fatal(e)
		}
	}
	read()
	if len(response.Accounts) != 3 {
		t.Fatal("accounts missing")
	}
	for _, r := range response.Accounts {
		if r.CompletedAt != nil {
			t.Fatal("legacy provider completion leaked to account")
		}
	}
	first := req(t, s, "POST", "/api/v1/checkins/accounts", accountCheckinBody("p", "a"))
	requireCode(t, first, 200)
	again := req(t, s, "POST", "/api/v1/checkins/accounts", accountCheckinBody("p", "a"))
	requireCode(t, again, 200)
	if first.Body.String() != again.Body.String() {
		t.Fatal("duplicate completion changed receipt")
	}
	read()
	for _, r := range response.Accounts {
		if (r.CompletedAt != nil) != (r.ProviderID == "p" && r.AccountID == "a") {
			t.Fatal("foreign account changed")
		}
	}
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM account_checkins").Scan(&count)
	if count != 1 || calls.Load() != 0 {
		t.Fatal("duplicate row or unexpected upstream", count, calls.Load())
	}
}
func TestAccountCheckinRejectsMissingScopeWrongDateAndBadTimezone(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://example.com/v1")
	checkinConfig(t, s, "p")
	for _, tc := range []struct {
		field string
		value any
		code  int
	}{{"account_id", "", 400}, {"account_id", "missing", 422}, {"provider_id", "missing", 422}, {"date", "2000-01-01", 409}, {"timezone", "Invalid/Zone", 400}, {"secret", "not-accepted", 400}} {
		body := accountCheckinBody("p", "a")
		body[tc.field] = tc.value
		requireCode(t, req(t, s, "POST", "/api/v1/checkins/accounts", body), tc.code)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/checkins/accounts", nil), 400)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/checkins/accounts?timezone=UTC", nil))
	requireCode(t, w, 401)
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM account_checkins").Scan(&count)
	if count != 0 {
		t.Fatal("invalid request wrote completion")
	}
}
func TestCheckinCivilDateUsesLocalMidnightIncludingDST(t *testing.T) {
	before := time.Date(2026, 1, 1, 15, 59, 59, 0, time.UTC)
	after := before.Add(time.Second)
	a, _ := checkinDate("Asia/Taipei", before)
	b, _ := checkinDate("Asia/Taipei", after)
	c, _ := checkinDate("UTC", after)
	if a != "2026-01-01" || b != "2026-01-02" || c != "2026-01-01" {
		t.Fatal(a, b, c)
	}
	for _, hour := range []int{6, 7} {
		v, e := checkinDate("America/New_York", time.Date(2026, 3, 8, hour, 30, 0, 0, time.UTC))
		if e != nil || v != "2026-03-08" {
			t.Fatal(v, e)
		}
	}
}
func TestAccountCheckinMigrationPreservesLegacyHistoryAndEncryptedCredentials(t *testing.T) {
	path := filepath.Join(t.TempDir(), "upgrade.sqlite")
	old, e := OpenStore(path)
	if e != nil {
		t.Fatal(e)
	}
	s := &Server{Store: old, Token: "local-testing-long-admin-token", Key: make([]byte, 32), Client: http.DefaultClient}
	v := seed(t, s, "https://example.com/v1")
	_, e = old.DB.Exec("INSERT INTO checkins VALUES('p','2025-01-01',1234)")
	if e != nil {
		t.Fatal(e)
	}
	archiveInsert(t, s, v, "permanent", "MANUAL_PRICE", "USER", "SUCCESS", `{"price":{"input":"0.000000000123456789"}}`, 1000)
	session, e := s.createTestSession(context.Background(), v)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = old.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('kept-message',?,'user','保留對話與草稿',1000)", session); e != nil {
		t.Fatal(e)
	}
	if _, e = old.DB.Exec("INSERT INTO settings VALUES('timezone','\"Asia/Taipei\"')"); e != nil {
		t.Fatal(e)
	}
	var encrypted string
	old.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='a' AND id='c'").Scan(&encrypted)
	// Reconstruct an actual v16 fixture: sync tables/triggers did not exist yet.
	names := []string{}
	rows, queryErr := old.DB.Query("SELECT type,name FROM sqlite_master WHERE name LIKE 'workspace_%' AND type IN ('trigger','table') ORDER BY CASE type WHEN 'trigger' THEN 0 ELSE 1 END")
	if queryErr != nil {
		t.Fatal(queryErr)
	}
	for rows.Next() {
		var kind, name string
		if e = rows.Scan(&kind, &name); e != nil {
			t.Fatal(e)
		}
		names = append(names, "DROP "+strings.ToUpper(kind)+" "+name)
	}
	rows.Close()
	for _, q := range names {
		if _, e = old.DB.Exec(q); e != nil {
			t.Fatal(e)
		}
	}
	if _, e = old.DB.Exec("DROP TABLE account_checkins; PRAGMA user_version=16"); e != nil {
		t.Fatal(e)
	}
	if e = old.Close(); e != nil {
		t.Fatal(e)
	}
	updated, e := OpenStore(path)
	if e != nil {
		t.Fatal(e)
	}
	defer updated.Close()
	var version, count int
	var kept string
	updated.DB.QueryRow("PRAGMA user_version").Scan(&version)
	if version != schemaVersion {
		t.Fatal(version)
	}
	updated.DB.QueryRow("SELECT encrypted_key FROM credentials WHERE provider_id='p' AND account_id='a' AND id='c'").Scan(&kept)
	if kept != encrypted || kept == "" {
		t.Fatal("encrypted credential changed")
	}
	updated.DB.QueryRow("SELECT COUNT(*) FROM checkins WHERE provider_id='p' AND date='2025-01-01' AND completed_at=1234").Scan(&count)
	if count != 1 {
		t.Fatal("legacy check-in lost")
	}
	updated.DB.QueryRow("SELECT facts_json FROM observations WHERE id='permanent'").Scan(&kept)
	if kept != `{"price":{"input":"0.000000000123456789"}}` {
		t.Fatal("price history changed")
	}
	updated.DB.QueryRow("SELECT COUNT(*) FROM account_checkins").Scan(&count)
	if count != 0 {
		t.Fatal("legacy data was attributed to accounts")
	}
	updated.DB.QueryRow("SELECT content FROM messages WHERE id='kept-message' AND session_id=?", session).Scan(&kept)
	if kept != "保留對話與草稿" {
		t.Fatal("conversation changed")
	}
	updated.DB.QueryRow("SELECT value_json FROM settings WHERE key='timezone'").Scan(&kept)
	if kept != `"Asia/Taipei"` {
		t.Fatal("saved preference changed")
	}
}
func TestAccountCheckinNotificationUsesExactAccountAndDoesNotRepeat(t *testing.T) {
	s := testServer(t)
	a := seed(t, s, "https://example.com/v1")
	checkinConfig(t, s, "p")
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "b", ProviderID: "p", Label: "B"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "c", ProviderID: "p", AccountID: "b", Label: "C", Secret: "second-key"}), 201)
	b := a
	b.AccountID = "b"
	for _, v := range []Selection{a, b} {
		requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/settings", map[string]any{"timezone": "Asia/Taipei", "notification_rules": map[string]any{"checkin_pending": true, "quota_remaining_below": 100}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/checkins/accounts", accountCheckinBody("p", "a")), 200)
	s.checkNotificationRules(context.Background())
	s.checkNotificationRules(context.Background())
	rows, e := s.Store.DB.Query("SELECT scope_key FROM notification_events WHERE kind='CHECKIN_PENDING'")
	if e != nil {
		t.Fatal(e)
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		var scope string
		if e = rows.Scan(&scope); e != nil {
			t.Fatal(e)
		}
		if scope != scopeKey(b) {
			t.Fatal("completed account received pending notification")
		}
		count++
	}
	if count != 1 {
		t.Fatal("expected one deduplicated pending-account event", count)
	}
}

func TestDesktopAccountCheckinNotificationsUseMachineDate(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://example.com/v1")
	checkinConfig(t, s, "p")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", v), 201)
	current := time.Now()
	localDate := current.In(time.Local).Format("2006-01-02")
	otherZone := ""
	for _, name := range []string{"Pacific/Kiritimati", "Etc/GMT+12"} {
		date, err := checkinDate(name, current)
		if err == nil && date != localDate {
			otherZone = name
			break
		}
	}
	if otherZone == "" {
		t.Fatal("expected a different civil date")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/settings", map[string]any{"timezone": otherZone, "notification_rules": map[string]any{"checkin_pending": true, "quota_remaining_below": 100}}), 200)
	requireCode(t, req(t, s, "POST", "/api/v1/checkins/accounts", map[string]any{"provider_id": "p", "account_id": "a", "timezone": "Local", "date": localDate}), 200)
	s.desktop = true
	s.checkNotificationRules(context.Background())
	var count int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM notification_events WHERE kind='CHECKIN_PENDING'").Scan(&count)
	if count != 0 {
		t.Fatal("native completion compared with web timezone")
	}
	s.desktop = false
	s.checkNotificationRules(context.Background())
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM notification_events WHERE kind='CHECKIN_PENDING'").Scan(&count)
	if count != 1 {
		t.Fatal("standalone VPS did not retain its configured timezone")
	}
}
