package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type quotaPoolView struct {
	Revision int64  `json:"revision"`
	Day      string `json:"day"`
	Next     string `json:"next_after"`
	Pools    []struct {
		Definition researchQuotaPool `json:"definition"`
		Usage      resourceUsageView `json:"usage"`
		Requests   int               `json:"today_requests"`
		Older      int               `json:"older_unbudgeted_requests"`
	} `json:"pools"`
}

func poolsForTest(t *testing.T, s *Server) quotaPoolView {
	t.Helper()
	w := req(t, s, "GET", "/api/v1/research/quota-pools", nil)
	requireCode(t, w, 200)
	var v quotaPoolView
	if json.Unmarshal(w.Body.Bytes(), &v) != nil || v.Revision < 1 || strings.Contains(w.Body.String(), "top-secret-example-key") {
		t.Fatal("invalid/unsafe pools")
	}
	return v
}
func savePoolForTest(t *testing.T, s *Server, p researchQuotaPool) {
	t.Helper()
	raw, _ := json.Marshal(p)
	var v map[string]any
	json.Unmarshal(raw, &v)
	v["revision"] = poolsForTest(t, s).Revision
	requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", v), 200)
}
func accountPoolForTest() researchQuotaPool {
	return researchQuotaPool{ID: "shared-account", Label: "Shared account", Kind: "ACCOUNT", ProviderID: "p", AccountID: "a", Enabled: true, Members: []researchInferenceScope{}}
}
func addPoolKey(t *testing.T, s *Server, scope Selection, account, key string) Selection {
	t.Helper()
	if account != "a" {
		requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ProviderID: scope.ProviderID, ID: account, Label: account}), 201)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ProviderID: scope.ProviderID, AccountID: account, ID: key, Label: key, Secret: "top-secret-example-key"}), 201)
	scope.AccountID, scope.CredentialID = account, key
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	return scope
}
func expectPoolLimit(t *testing.T, s *Server, ctx context.Context, scope Selection, id, metric string) {
	t.Helper()
	result, code := inferBudgetAttempt(t, s, ctx, scope, "CRAWLER_VERIFY")
	limit, ok := result.Details["research_resource_budget"].(*researchResourceBudgetError)
	if code != 429 || !ok || limit.Scope != "POOL" || limit.PoolID != id || limit.Metric != metric {
		t.Fatalf("pool limit code=%d outcome=%s details=%#v", code, result.Outcome, limit)
	}
}

func TestResearchQuotaPoolFormalEntry(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	w := req(t, f.Server, "GET", "/api/v1/research/quota-pools", nil)
	requireCode(t, w, 200)
	var v struct {
		Revision int64             `json:"revision"`
		Pools    []json.RawMessage `json:"pools"`
	}
	if json.Unmarshal(w.Body.Bytes(), &v) != nil || v.Revision != 1 || v.Pools == nil || len(v.Pools) != 0 {
		t.Fatal("shared pool settings unavailable")
	}
}

func TestResearchQuotaPoolAccountAcrossKeysRotationRestartAndDay(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 20, 10)
	s := f.Server
	instant := time.Date(2026, 10, 24, 23, 59, 59, 0, time.UTC)
	s.scheduleClock = func() time.Time { return instant }
	limit := 1
	p := accountPoolForTest()
	p.Requests = &limit
	savePoolForTest(t, s, p)
	second := addPoolKey(t, s, f.Scope, "a", "second")
	if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal(code, r.Outcome)
	}
	expectPoolLimit(t, s, f.context(), second, p.ID, "REQUESTS")
	// New credentials are covered by account scope without a membership edit.
	third := addPoolKey(t, s, f.Scope, "a", "third")
	expectPoolLimit(t, s, f.context(), third, p.ID, "REQUESTS")
	requireCode(t, req(t, s, "POST", "/api/v1/credentials/rotate", map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c", "secret": "top-secret-example-key", "confirm": true}), 200)
	expectPoolLimit(t, s, f.context(), f.Scope, p.ID, "REQUESTS")
	other := addPoolKey(t, s, f.Scope, "b", "c")
	if r, code := inferBudgetAttempt(t, s, f.context(), other, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("unrelated account borrowed restriction", code, r.Outcome)
	}
	view := poolsForTest(t, s)
	if view.Pools[0].Requests != 1 || view.Pools[0].Usage.UnknownTokens != 1 {
		t.Fatal("wrong account union", view)
	}
	f.assertUsage(t, 2, 2)
	s = reopenScheduleStore(t, s)
	f.Server = s
	requireCode(t, req(t, s, "POST", "/api/v1/research/control", map[string]any{"id": f.RunID, "action": "RESUME"}), 200)
	expectPoolLimit(t, s, f.context(), second, p.ID, "REQUESTS")
	if f.Calls.Load() != 2 {
		t.Fatal("restart or rotation re-sent requests")
	}
	instant = instant.Add(time.Second)
	if v := poolsForTest(t, s); v.Day != "2026-10-25" || v.Pools[0].Requests != 0 {
		t.Fatal("UTC pool day", v)
	}
	if r, code := inferBudgetAttempt(t, s, f.context(), second, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("next day did not admit", code, r.Outcome)
	}
	assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_quota_pool_admissions")
	assertSQLInt(t, s, 3, "SELECT COUNT(*) FROM research_inference_admissions")
}

func TestResearchQuotaPoolMembershipRetainsSpendAndCountsBeforeAttachment(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 20, 10)
	s := f.Server
	second := addPoolKey(t, s, f.Scope, "a", "second")
	// A key's existing research is counted when attached later.
	if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal(code, r.Outcome)
	}
	limit := 2
	p := accountPoolForTest()
	p.ID, p.Kind, p.AccountID, p.Project = "project", "PROJECT", "", "same-project"
	p.Requests = &limit
	p.Members = []researchInferenceScope{{"p", "a", "c"}, {"p", "a", "second"}}
	savePoolForTest(t, s, p)
	if v := poolsForTest(t, s); v.Pools[0].Requests != 1 {
		t.Fatal("pre-attachment use was erased")
	}
	if r, code := inferBudgetAttempt(t, s, f.context(), second, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal(code, r.Outcome)
	}
	// Remove the first key, including usage from before the pool existed.
	p.Members = p.Members[1:]
	savePoolForTest(t, s, p)
	expectPoolLimit(t, s, f.context(), second, p.ID, "REQUESTS")
	if v := poolsForTest(t, s); v.Pools[0].Requests != 2 || v.Pools[0].Usage.Requests != 2 {
		t.Fatal("removed key erased pool use", v)
	}
	p.Enabled = false
	savePoolForTest(t, s, p)
	if r, code := inferBudgetAttempt(t, s, f.context(), second, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal("explicit disable failed")
	}
	p.Enabled = true
	savePoolForTest(t, s, p)
	expectPoolLimit(t, s, f.context(), second, p.ID, "REQUESTS")
	// The pool includes the disabled period for its current members.
	if v := poolsForTest(t, s); v.Pools[0].Requests != 3 {
		t.Fatal("disable reset the day")
	}
	f.assertUsage(t, 3, 3)
}

func TestResearchQuotaPoolAllApplicablePoolsAreAtomicAndUnknownRetainsReservation(t *testing.T) {
	parallelResearchBudgetTest(t)
	for _, metric := range []string{"TOKENS", "USD"} {
		t.Run(metric, func(t *testing.T) {
			f := newResearchAdmissionFixture(t, 10, 5)
			s := f.Server
			setResourcePolicyForTest(t, s, map[string]any{"reserve_tokens": 32, "reserve_usd": "0.01"})
			second := addPoolKey(t, s, f.Scope, "a", "second")
			first := accountPoolForTest()
			first.ID = "a-provider"
			first.Kind = "PROVIDER"
			first.AccountID = ""
			savePoolForTest(t, s, first)
			p := accountPoolForTest()
			tokens, usd := int64(32), "0.01"
			if metric == "TOKENS" {
				p.Tokens = &tokens
			} else {
				p.USD = &usd
			}
			savePoolForTest(t, s, p)
			if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
				t.Fatal(code, r.Outcome)
			}
			expectPoolLimit(t, s, f.context(), second, p.ID, metric)
			f.assertUsage(t, 1, 1)
			assertSQLInt(t, s, 2, "SELECT COUNT(*) FROM research_quota_pool_admissions")
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_inference_admissions")
			assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
			v := poolsForTest(t, s)
			if v.Pools[1].Usage.Tokens != "32" || v.Pools[1].Usage.USD != "0.01" || v.Pools[1].Usage.UnknownUSD != 1 {
				t.Fatal("unknown became zero", v)
			}
		})
	}
}

func TestResearchQuotaPoolExactSettlementAcrossKeys(t *testing.T) {
	parallelResearchBudgetTest(t)
	s := testServer(t)
	scope := seed(t, s, "https://openrouter.ai/v1")
	sessionForTest(t, s, scope)
	second := addPoolKey(t, s, scope, "a", "second")
	var calls atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := calls.Add(1)
		cost := "0.1"
		if n == 2 {
			cost = "0.2"
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "data: {\"choices\":[{\"delta\":{\"content\":\"Observed\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12,\"cost\":%s}}\n\ndata: [DONE]\n\n", cost)
	}))
	defer up.Close()
	s.Client = mappedFixture(up)
	run := researchRunID(t, s, ResearchOptions{Selections: []Selection{scope, second}, MinimalInference: true, Budget: 10, ProbeBudget: 5})
	ctx := context.WithValue(context.Background(), researchBudgetKey{}, run)
	setResourcePolicyForTest(t, s, map[string]any{"reserve_tokens": 32, "reserve_usd": "0.1"})
	p := accountPoolForTest()
	usd := "0.3"
	p.USD = &usd
	savePoolForTest(t, s, p)
	for _, v := range []Selection{scope, second} {
		if r, code := inferBudgetAttempt(t, s, ctx, v, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
			t.Fatal(code, r.Outcome)
		}
	}
	view := poolsForTest(t, s)
	if view.Pools[0].Usage.USD != "0.3" || view.Pools[0].Usage.Tokens != "24" || view.Pools[0].Usage.ReportedUSD == nil || *view.Pools[0].Usage.ReportedUSD != "0.3" {
		t.Fatal("pool lost exact settlement", view)
	}
	expectPoolLimit(t, s, ctx, scope, p.ID, "USD")
	if calls.Load() != 2 {
		t.Fatal("pool allowed extra wire request")
	}
}

func TestResearchQuotaPoolConcurrentInFlightKeys(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 20, 10)
	s := f.Server
	setInferencePolicyForTest(t, s, 100, 100)
	second := addPoolKey(t, s, f.Scope, "a", "second")
	limit := 1
	p := accountPoolForTest()
	p.Requests = &limit
	savePoolForTest(t, s, p)
	f.Mode.Store(3)
	var admitted, blocked atomic.Int32
	var group sync.WaitGroup
	slots := make(chan struct{}, s.flightLimit())
	release := sync.OnceFunc(func() { close(f.Release) })
	defer func() { release(); group.Wait() }()
	for i := 0; i < 20; i++ {
		scope := f.Scope
		if i%2 == 1 {
			scope = second
		}
		session := sessionForPoolConcurrency(t, s, scope)
		group.Add(1)
		go func(id string) {
			defer group.Done()
			slots <- struct{}{}
			defer func() { <-slots }()
			r, code := s.execute(f.context(), inferRequest{SessionID: id, Prompt: "one explicit pending request", MaxTokens: 16}, "CRAWLER_VERIFY", func(string, any) error { return nil })
			if code == 0 && r.Outcome == "SUCCESS" {
				admitted.Add(1)
			} else if b, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError); code == 429 && ok && b.PoolID == p.ID {
				blocked.Add(1)
			} else {
				t.Errorf("concurrent pool: %d %s", code, r.Outcome)
			}
		}(session)
	}
	select {
	case <-f.Started:
	case <-time.After(5 * time.Second):
		t.Fatal("no TLS request started")
	}
	// The first request has no measurement yet; its reservation must block peers.
	deadline := time.Now().Add(5 * time.Second)
	for blocked.Load() < 19 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if blocked.Load() != 19 {
		release()
		group.Wait()
		t.Fatal("pending request did not reserve common pool", blocked.Load())
	}
	view := poolsForTest(t, s)
	if view.Pools[0].Requests != 1 || view.Pools[0].Usage.Pending != 1 {
		t.Error("pending pool meter", view)
	}
	release()
	group.Wait()
	if admitted.Load() != 1 || f.Calls.Load() != 1 {
		t.Fatal("concurrent pool overspent", admitted.Load(), f.Calls.Load())
	}
	f.assertUsage(t, 1, 1)
}

func sessionForPoolConcurrency(t *testing.T, s *Server, scope Selection) string {
	t.Helper()
	id, err := s.createTestSession(context.Background(), scope)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestResearchQuotaPoolFaultRollbackPolicyCASAndValidation(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	p := accountPoolForTest()
	savePoolForTest(t, s, p)
	raw, _ := json.Marshal(p)
	var body map[string]any
	json.Unmarshal(raw, &body)
	body["revision"] = int64(1)
	requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", body), 409)
	for _, change := range []map[string]any{{"requests": -1}, {"requests": 2001}, {"tokens": -1}, {"usd": "NaN"}, {"usd": "-1"}, {"enabled": nil}, {"unexpected": "x"}, {"members": nil}, {"members": []any{map[string]any{"provider_id": "p", "account_id": "a", "credential_id": "c"}}}} {
		candidate := map[string]any{}
		for k, v := range body {
			candidate[k] = v
		}
		candidate["revision"] = poolsForTest(t, s).Revision
		for k, v := range change {
			candidate[k] = v
		}
		requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", candidate), 400)
	}
	body["revision"] = poolsForTest(t, s).Revision
	body["label"] = "top-secret-example-key"
	requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", body), 422)
	body["label"] = p.Label
	body["account_id"] = "other"
	requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", body), 422)
	body["account_id"] = "a"
	if _, err := s.Store.DB.Exec("CREATE TRIGGER pool_policy_fault BEFORE INSERT ON research_quota_pool_history BEGIN SELECT RAISE(ABORT,'injected pool policy failure'); END"); err != nil {
		t.Fatal(err)
	}
	body["requests"] = 0
	requireCode(t, req(t, s, "POST", "/api/v1/research/quota-pools", body), 503)
	if v := poolsForTest(t, s); v.Revision != 2 || v.Pools[0].Definition.Requests != nil {
		t.Fatal("partial pool policy")
	}
	for _, table := range []string{"research_quota_pool_admissions", "messages", "request_context"} {
		t.Run(table, func(t *testing.T) {
			if _, err := s.Store.DB.Exec("CREATE TRIGGER pool_admission_fault BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'injected pool admission failure'); END"); err != nil {
				t.Fatal(err)
			}
			r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
			if code == 0 || r.Outcome == "SUCCESS" || f.Calls.Load() != 0 {
				t.Fatal("fault sent request")
			}
			f.assertUsage(t, 0, 0)
			for _, name := range []string{"research_inference_admissions", "research_resource_admissions", "research_quota_pool_admissions", "telemetry", "messages"} {
				assertSQLInt(t, s, 0, "SELECT COUNT(*) FROM "+name)
			}
			s.Store.DB.Exec("DROP TRIGGER pool_admission_fault")
		})
	}
	if _, err := s.Store.DB.Exec("UPDATE research_quota_pools SET definition_json='{}'"); err != nil {
		t.Fatal(err)
	}
	requireCode(t, req(t, s, "GET", "/api/v1/research/quota-pools", nil), 503)
	r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY")
	if b, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError); code != 429 || !ok || b.Metric != "UNAVAILABLE" {
		t.Fatal("corrupt pool granted authority", code, r.Outcome)
	}
}

func TestResearchQuotaPoolCustomScopesLegacyUnknownAndOutputEnvelope(t *testing.T) {
	parallelResearchBudgetTest(t)
	t.Run("custom scopes", func(t *testing.T) {
		f := newResearchAdmissionFixture(t, 20, 10)
		s := f.Server
		other := addPoolKey(t, s, f.Scope, "b", "c")
		unbound := addPoolKey(t, s, f.Scope, "a", "unbound")
		p := accountPoolForTest()
		p.Kind, p.ProviderID, p.AccountID = "CUSTOM", "", ""
		p.Members = []researchInferenceScope{{"p", "a", "c"}, {"p", "b", "c"}}
		limit := 1
		p.Requests = &limit
		savePoolForTest(t, s, p)
		if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
			t.Fatal(code, r.Outcome)
		}
		expectPoolLimit(t, s, f.context(), other, p.ID, "REQUESTS")
		if r, code := inferBudgetAttempt(t, s, f.context(), unbound, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
			t.Fatal("custom scope overreach", code, r.Outcome)
		}
		if r, code := inferBudgetAttempt(t, s, context.Background(), other, "PLAYGROUND"); code != 0 || r.Outcome != "SUCCESS" {
			t.Fatal("pool blocked ordinary chat", code, r.Outcome)
		}
		if v := poolsForTest(t, s); v.Pools[0].Requests != 1 {
			t.Fatal("custom or chat counted twice", v)
		}
		f.assertUsage(t, 2, 2)
	})
	t.Run("legacy unknown", func(t *testing.T) {
		f := newResearchAdmissionFixture(t, 10, 5)
		s := f.Server
		if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
			t.Fatal(code, r.Outcome)
		}
		// Simulate a preserved pre-schema28 inference, which had no allocation.
		if _, err := s.Store.DB.Exec("DELETE FROM research_resource_settlements; DELETE FROM research_resource_admissions"); err != nil {
			t.Fatal(err)
		}
		p := accountPoolForTest()
		tokens := int64(1000)
		p.Tokens = &tokens
		savePoolForTest(t, s, p)
		expectPoolLimit(t, s, f.context(), f.Scope, p.ID, "UNAVAILABLE")
		if v := poolsForTest(t, s); v.Pools[0].Older != 1 || v.Pools[0].Requests != 1 {
			t.Fatal("legacy unknown became zero", v)
		}
		f.assertUsage(t, 1, 1)
	})
	t.Run("pool output envelope", func(t *testing.T) {
		f := newResearchAdmissionFixture(t, 10, 5)
		s := f.Server
		setResourcePolicyForTest(t, s, map[string]any{"reserve_tokens": 32})
		p := accountPoolForTest()
		tokens := int64(64)
		p.Tokens = &tokens
		savePoolForTest(t, s, p)
		r, code := s.execute(f.context(), inferRequest{SessionID: f.SessionID, Prompt: "explicit oversized output", MaxTokens: 33}, "CRAWLER_VERIFY", func(string, any) error { return nil })
		b, ok := r.Details["research_resource_budget"].(*researchResourceBudgetError)
		if code != 429 || !ok || b.Metric != "OUTPUT_ENVELOPE" || b.PoolID != p.ID || f.Calls.Load() != 0 {
			t.Fatal("pool-only token envelope", code, r.Outcome)
		}
		f.assertUsage(t, 0, 0)
	})
}

func TestResearchQuotaPoolLocalAuthorityMigrationAndPagedHistory(t *testing.T) {
	parallelResearchBudgetTest(t)
	f := newResearchAdmissionFixture(t, 10, 5)
	s := f.Server
	p := accountPoolForTest()
	savePoolForTest(t, s, p)
	if r, code := inferBudgetAttempt(t, s, f.context(), f.Scope, "CRAWLER_VERIFY"); code != 0 || r.Outcome != "SUCCESS" {
		t.Fatal(code, r.Outcome)
	}
	peer := testServer(t)
	host := httptest.NewTLSServer(s.Handler())
	defer host.Close()
	workspacePairForTest(t, s, peer, host, false)
	workspaceRunForTest(t, peer)
	for _, table := range []string{"research_quota_pool_policy", "research_quota_pools", "research_quota_pool_admissions", "research_quota_pool_history"} {
		if workspaceAllowed(table) {
			t.Fatal("pool authority sync", table)
		}
	}
	if v := poolsForTest(t, peer); len(v.Pools) != 0 || v.Revision != 1 {
		t.Fatal("sync imported pool authorization", v)
	}
	backup := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, backup, 200)
	if strings.Contains(backup.Body.String(), "research_quota_pool") {
		t.Fatal("portable pool authority")
	}
	var document BackupDocument
	json.Unmarshal(backup.Body.Bytes(), &document)
	restored := testServer(t)
	requireCode(t, req(t, restored, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": document}), 201)
	if len(poolsForTest(t, restored).Pools) != 0 {
		t.Fatal("portable imported pools")
	}
	for _, table := range []string{"research_quota_pool_admissions", "research_quota_pool_history", "research_quota_pools", "research_quota_pool_policy"} {
		if _, err := s.Store.DB.Exec("DROP TABLE " + table); err != nil {
			t.Fatal(err)
		}
	}
	s.Store.DB.Exec("PRAGMA user_version=28")
	s = reopenScheduleStore(t, s)
	assertSQLInt(t, s, schemaVersion, "PRAGMA user_version")
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM research_resource_admissions")
	files, err := filepath.Glob(filepath.Join(filepath.Dir(s.Store.path), "upgrade-backups", fmt.Sprintf("schema-28-to-%d-*.sqlite", schemaVersion)))
	if err != nil || len(files) != 1 || verifySnapshot(files[0]) != nil {
		t.Fatal("missing pre-pool snapshot", err)
	}
	for i := 0; i < 22; i++ {
		p.ID = fmt.Sprintf("pool-%03d", i)
		savePoolForTest(t, s, p)
	}
	v := poolsForTest(t, s)
	if len(v.Pools) != 20 || v.Next == "" {
		t.Fatal("unbounded pools")
	}
	var next quotaPoolView
	w := req(t, s, "GET", "/api/v1/research/quota-pools?after="+v.Next, nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &next)
	if len(next.Pools) != 2 || next.Next != "" {
		t.Fatal("pool page lost entries")
	}
	for i := 0; i < 31; i++ {
		savePoolForTest(t, s, p)
	}
	var history struct {
		History []json.RawMessage `json:"history"`
		Next    int64             `json:"next_before"`
	}
	w = req(t, s, "GET", "/api/v1/research/quota-pools?history=1", nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &history)
	if len(history.History) != 50 || history.Next == 0 {
		t.Fatal("unbounded pool history")
	}
	w = req(t, s, "GET", fmt.Sprintf("/api/v1/research/quota-pools?history=1&before=%d", history.Next), nil)
	requireCode(t, w, 200)
	json.Unmarshal(w.Body.Bytes(), &history)
	if len(history.History) != 3 || history.Next != 0 {
		t.Fatal("pool history lost entries")
	}
}
