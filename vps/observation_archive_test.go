package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"sync/atomic"
	"testing"
)

type archiveResponse struct {
	Selection      Selection     `json:"selection"`
	History        []Observation `json:"history"`
	Total          int           `json:"total"`
	Next           string        `json:"next_cursor"`
	Sources, Kinds []string
}

func archivePath(v Selection, extra url.Values) string {
	q := url.Values{"provider_id": {v.ProviderID}, "account_id": {v.AccountID}, "credential_id": {v.CredentialID}, "model_id": {v.ModelID}, "group": {v.Group}, "endpoint": {v.Endpoint}}
	for k, values := range extra {
		q[k] = values
	}
	return "/api/v1/models/observations?" + q.Encode()
}
func archiveGet(t *testing.T, s *Server, v Selection, extra url.Values) archiveResponse {
	t.Helper()
	w := req(t, s, "GET", archivePath(v, extra), nil)
	requireCode(t, w, 200)
	var out archiveResponse
	if json.Unmarshal(w.Body.Bytes(), &out) != nil {
		t.Fatal("invalid archive JSON")
	}
	return out
}
func archiveInsert(t *testing.T, s *Server, v Selection, id, kind, source, status, facts string, at int64) {
	t.Helper()
	if _, e := s.Store.DB.Exec("INSERT INTO observations VALUES(?,?,?,?,?,?,?)", id, scopeKey(v), kind, source, status, facts, at); e != nil {
		t.Fatal(e)
	}
}
func TestObservationArchiveFrozenPagesKeepAllRowsAndExactScope(t *testing.T) {
	var requests atomic.Int32
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		http.Error(w, "unexpected upstream request", 500)
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	v := seed(t, s, up.URL+"/v1")
	for i := 0; i < 237; i++ {
		archiveInsert(t, s, v, "saved-"+strconv.Itoa(i), "MANUAL_PRICE", "USER", "SUCCESS", `{"price":{"input":"0.000000000123456789","output":"0","unit":"USD/1K tokens"}}`, 1000)
	}
	for i := 0; i < 6; i++ {
		other := v
		switch i {
		case 0:
			other.ProviderID = "other"
		case 1:
			other.AccountID = "other"
		case 2:
			other.CredentialID = "other"
		case 3:
			other.ModelID = "other"
		case 4:
			other.Group = "other"
		case 5:
			other.Endpoint = "IMAGES_GENERATION"
		}
		archiveInsert(t, s, other, "foreign-"+strconv.Itoa(i), "MANUAL_PRICE", "USER", "SUCCESS", `{"price":{"input":"999"}}`, 1000)
	}
	first := archiveGet(t, s, v, nil)
	if first.Total != 237 || len(first.History) != 200 || first.Next == "" || first.Selection != v {
		t.Fatalf("bad first page: %d/%d", len(first.History), first.Total)
	}
	archiveInsert(t, s, v, "new-after-first-page", "PRICE", "OFFICIAL", "SUCCESS", `{"price":{"input":"77"}}`, 999)
	second := archiveGet(t, s, v, url.Values{"cursor": {first.Next}})
	if second.Total != 237 || len(second.History) != 37 || second.Next != "" {
		t.Fatal("insertion shifted frozen archive")
	}
	seen := map[string]bool{}
	for _, page := range []archiveResponse{first, second} {
		for _, row := range page.History {
			if seen[row.ID] || row.Selection != v || row.ID == "new-after-first-page" {
				t.Fatal("duplicate or foreign observation")
			}
			seen[row.ID] = true
			price := row.Facts["price"].(map[string]any)
			if price["input"] != "0.000000000123456789" || price["output"] != "0" {
				t.Fatal("decimal text or zero lost")
			}
		}
	}
	if len(seen) != 237 || archiveGet(t, s, v, nil).Total != 238 || requests.Load() != 0 {
		t.Fatal("archive was truncated, not refreshed, or caused upstream traffic")
	}
}
func TestObservationArchiveFiltersApplyBeforePagingAndExposeWholeRangeFacets(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://example.com/v1")
	for i := 0; i < 235; i++ {
		archiveInsert(t, s, v, "other-"+strconv.Itoa(i), "QUOTA", "OFFICIAL", "SUCCESS", `{"remaining":0}`, 100)
	}
	archiveInsert(t, s, v, "at-start", "PRICE", "USER", "SUCCESS", `{"price":{"input":"0"}}`, 200)
	archiveInsert(t, s, v, "failed", "PRICE", "USER", "FAILED", `{"reason":"timeout"}`, 250)
	archiveInsert(t, s, v, "at-end", "PRICE", "USER", "SUCCESS", `{"price":{"input":"9"}}`, 300)
	page := archiveGet(t, s, v, url.Values{"source": {"USER"}, "kind": {"PRICE"}, "start": {"200"}, "end": {"300"}, "limit": {"1"}})
	if page.Total != 2 || len(page.History) != 1 || page.History[0].ID != "failed" || page.Next == "" {
		t.Fatal("filtered history used only recent records or lost failure")
	}
	next := archiveGet(t, s, v, url.Values{"source": {"USER"}, "kind": {"PRICE"}, "start": {"200"}, "end": {"300"}, "limit": {"1"}, "cursor": {page.Next}})
	if len(next.History) != 1 || next.History[0].ID != "at-start" || next.Next != "" {
		t.Fatal("half-open window or stable continuation broken")
	}
	prices := archiveGet(t, s, v, url.Values{"category": {"PRICE"}})
	if prices.Total != 2 || len(prices.History) != 2 {
		t.Fatal("price category mixed quota or invented failed-price values")
	}
	filtered := archiveGet(t, s, v, url.Values{"source": {"USER"}, "limit": {"1"}})
	if len(filtered.Sources) != 2 || len(filtered.Kinds) != 2 {
		t.Fatal("facets reflect only first page/filter")
	}
	empty := archiveGet(t, s, v, url.Values{"source": {"MISSING"}})
	if empty.Total != 0 || len(empty.History) != 0 || empty.Next != "" {
		t.Fatal("empty archive is not empty")
	}
}
func TestObservationArchiveRejectsInvalidOrForeignContinuations(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://example.com/v1")
	for i := 0; i < 3; i++ {
		archiveInsert(t, s, v, "r"+strconv.Itoa(i), "PRICE", "USER", "SUCCESS", `{"price":{}}`, 1)
	}
	page := archiveGet(t, s, v, url.Values{"limit": {"1"}})
	for _, q := range []url.Values{{"limit": {"0"}}, {"limit": {"201"}}, {"limit": {"text"}}, {"start": {"-1"}}, {"end": {"text"}}, {"start": {"5"}, "end": {"5"}}, {"category": {"ALL_RAW"}}, {"cursor": {"not-json!"}}, {"cursor": {page.Next}, "source": {"OTHER"}}, {"cursor": {page.Next}, "category": {"PRICE"}}, {"cursor": {page.Next}, "start": {"0"}}} {
		requireCode(t, req(t, s, "GET", archivePath(v, q), nil), 400)
	}
	for i := 0; i < 6; i++ {
		other := v
		switch i {
		case 0:
			other.ProviderID = "x"
		case 1:
			other.AccountID = "x"
		case 2:
			other.CredentialID = "x"
		case 3:
			other.ModelID = "x"
		case 4:
			other.Group = "x"
		case 5:
			other.Endpoint = "IMAGES_GENERATION"
		}
		requireCode(t, req(t, s, "GET", archivePath(other, url.Values{"cursor": {page.Next}}), nil), 400)
	}
	missing := v
	missing.AccountID = ""
	requireCode(t, req(t, s, "GET", archivePath(missing, nil), nil), 400)
}
func TestObservationArchiveFailureDoesNotEraseOldGoodFact(t *testing.T) {
	s := testServer(t)
	v := seed(t, s, "https://example.com/v1")
	archiveInsert(t, s, v, "old-good", "QUOTA", "USER", "SUCCESS", `{"remaining":10}`, 1)
	for i := 0; i < 201; i++ {
		archiveInsert(t, s, v, "failed-"+strconv.Itoa(i), "QUOTA", "USER", "FAILED", `{"reason":"timeout"}`, 2)
	}
	page := archiveGet(t, s, v, nil)
	next := archiveGet(t, s, v, url.Values{"cursor": {page.Next}})
	if page.Total != 202 || next.History[len(next.History)-1].ID != "old-good" {
		t.Fatal("older good observation lost")
	}
	w := req(t, s, "GET", "/api/v1/models/detail?"+archivePath(v, nil)[len("/api/v1/models/observations?"):], nil)
	requireCode(t, w, 200)
	var detail map[string]json.RawMessage
	var good map[string]Observation
	if json.Unmarshal(w.Body.Bytes(), &detail) != nil || json.Unmarshal(detail["last_good"], &good) != nil || good["QUOTA:USER"].ID != "old-good" {
		t.Fatal("read changed last-good semantics")
	}
}
