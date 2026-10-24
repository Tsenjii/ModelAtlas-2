package main

import (
	"encoding/json"
	"net/url"
	"strconv"
	"testing"
)

func TestPublicSnapshotLastGoodFiltersBeforeLimit(t *testing.T) {
	s := testServer(t)
	seed(t, s, "https://example.com/v1")
	insert := func(id, account, credential, role, endpoint, facts string, at int) {
		t.Helper()
		if _, e := s.Store.DB.Exec("INSERT INTO newapi_snapshots(id,provider_id,account_id,credential_id,credential_role,endpoint,facts_json,provider_version,source,observed_at) VALUES(?,'p',?,?,?,?,?,'v0.0.0','NEW_API_OFFICIAL',?)", id, account, credential, role, endpoint, facts, at); e != nil {
			t.Fatal(e)
		}
	}
	insert("old-notice", "", "", "ANONYMOUS", "/api/notice", `{"origin":"https://example.com/v1","text":"last good"}`, 1)
	insert("other-origin", "", "", "ANONYMOUS", "/api/notice", `{"origin":"https://other.example","text":"foreign"}`, 2)
	insert("private-notice", "a", "c", "ACCOUNT_PAT", "/api/notice", `{"origin":"https://example.com/v1","text":"private"}`, 3)
	insert("model-24", "", "", "ANONYMOUS", "/api/perf-metrics", `{"origin":"https://example.com/v1","model_name":"m","hours":24}`, 4)
	for i := 0; i < 237; i++ {
		insert("noisy-"+strconv.Itoa(i), "", "", "ANONYMOUS", "/api/perf-metrics", `{"origin":"https://example.com/v1","model_name":"other","hours":168}`, 10+i)
	}
	q := url.Values{"provider_id": {"p"}, "public": {"1"}, "operation": {"notice"}, "origin": {"https://example.com/v1"}}
	read := func() []map[string]any {
		t.Helper()
		w := req(t, s, "GET", "/api/v1/newapi/snapshots?"+q.Encode(), nil)
		requireCode(t, w, 200)
		var rows []map[string]any
		if json.Unmarshal(w.Body.Bytes(), &rows) != nil {
			t.Fatal("invalid snapshots")
		}
		return rows
	}
	rows := read()
	if len(rows) != 1 || rows[0]["facts"].(map[string]any)["text"] != "last good" {
		t.Fatal("public notice last-good lost to limit, private scope or origin")
	}
	q.Set("operation", "performance")
	q.Set("model_id", "m")
	q.Set("hours", "24")
	rows = read()
	if len(rows) != 1 || rows[0]["facts"].(map[string]any)["hours"] != float64(24) {
		t.Fatal("performance scope borrowed another model/window")
	}
	q.Set("hours", "168")
	if len(read()) != 0 {
		t.Fatal("wrong window borrowed")
	}
	q.Set("hours", "721")
	requireCode(t, req(t, s, "GET", "/api/v1/newapi/snapshots?"+q.Encode(), nil), 400)
	q.Set("operation", "private")
	requireCode(t, req(t, s, "GET", "/api/v1/newapi/snapshots?"+q.Encode(), nil), 400)
	// Unfiltered callers retain the existing 200-row history contract.
	w := req(t, s, "GET", "/api/v1/newapi/snapshots?provider_id=p", nil)
	requireCode(t, w, 200)
	var legacy []map[string]any
	json.Unmarshal(w.Body.Bytes(), &legacy)
	if len(legacy) != 200 {
		t.Fatal("legacy snapshot history changed")
	}
}
