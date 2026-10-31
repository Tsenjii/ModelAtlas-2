package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"testing"
)

func TestReflectionQueryDoesNotBorrowDifferentCountryOrPageScope(t *testing.T) {
	parallelResearchBudgetTest(t)
	var mu sync.Mutex
	var seen []map[string]any
	s, _ := braveFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("unexpected Brave method %s", r.Method)
		}
		var body map[string]any
		if e := json.NewDecoder(r.Body).Decode(&body); e != nil {
			t.Error(e)
		}
		mu.Lock()
		seen = append(seen, body)
		mu.Unlock()
		braveFixturePage(w, false)
	})
	run := researchRunID(t, s, ResearchOptions{Search: true, Queries: map[string]string{"en": "observed US query"}, SearchCountry: "US", SearchPages: 2, Budget: 2})
	s.runResearchPending(context.Background())
	var reflection string
	if e := s.Store.DB.QueryRow("SELECT id FROM research_reflections WHERE run_id=?", run).Scan(&reflection); e != nil {
		t.Fatal(e)
	}
	draft := KnowledgeVersion{Kind: "RESEARCH_STRATEGY", Subject: "en", Data: map[string]any{"language": "en", "query": "learned US two page query", "search_country": "US", "search_pages": 2, "priority": 1, "weight": 1}, EvidenceIDs: []string{reflection}, Reason: "Explicit manual strategy for US, two pages only."}
	response := req(t, s, "POST", "/api/v1/research/knowledge", draft)
	requireCode(t, response, 201)
	var v KnowledgeVersion
	json.Unmarshal(response.Body.Bytes(), &v)
	requireCode(t, req(t, s, "POST", "/api/v1/research/knowledge/activate", versionActivation{ID: v.ID, Confirm: true}), 200)
	for _, tc := range []struct {
		country string
		pages   int
		match   bool
	}{{"TW", 2, false}, {"US", 1, false}, {"US", 2, true}} {
		run = researchRunID(t, s, ResearchOptions{Search: true, SearchCountry: tc.country, SearchPages: tc.pages, Budget: 1})
		s.runResearchPending(context.Background())
		var raw string
		s.Store.DB.QueryRow("SELECT payload_json FROM research_tasks WHERE run_id=? AND kind='SEARCH' AND dedupe='search:en'", run).Scan(&raw)
		var in taskInput
		json.Unmarshal([]byte(raw), &in)
		expected := searchQueries["en"]
		if tc.match {
			expected = "learned US two page query"
		}
		if in.Query != expected || in.Strategy == nil || (in.Strategy.VersionID == v.ID) != tc.match {
			t.Fatalf("strategy crossed query context %s/%d: %s", tc.country, tc.pages, raw)
		}
		mu.Lock()
		wire := seen[len(seen)-1]
		mu.Unlock()
		if wire["q"] != expected || wire["country"] != tc.country {
			t.Fatalf("actual Brave body crossed context: %#v", wire)
		}
	}
}
