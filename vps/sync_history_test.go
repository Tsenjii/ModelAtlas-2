package main

import (
	"encoding/json"
	"fmt"
	"testing"
)

func TestSyncHistoryPollingPageRetainsHistory(t *testing.T) {
	s := testServer(t)
	for i := 0; i < 7; i++ {
		if _, err := s.Store.DB.Exec("INSERT INTO sync_history VALUES(?,?,'SUCCESS',?,?)", fmt.Sprint(i), scopeKey(Selection{ProviderID: "p"}), `{"catalog":"SUCCESS"}`, i); err != nil {
			t.Fatal(err)
		}
	}
	for _, check := range []struct {
		query string
		ids   []string
	}{
		{"", []string{"6", "5", "4", "3", "2", "1", "0"}},
		{"?limit=2", []string{"6", "5"}},
		{"?limit=2&offset=2", []string{"4", "3"}},
		{"?limit=0", []string{"6", "5", "4", "3", "2", "1", "0"}},
	} {
		response := req(t, s, "GET", "/api/v1/intelligence/history"+check.query, nil)
		requireCode(t, response, 200)
		var rows []struct{ ID string }
		if err := json.Unmarshal(response.Body.Bytes(), &rows); err != nil || len(rows) != len(check.ids) {
			t.Fatalf("history page %s: %v %s", check.query, err, response.Body)
		}
		for i, id := range check.ids {
			if rows[i].ID != id {
				t.Fatalf("history page %s: unexpected record at %d", check.query, i)
			}
		}
	}
	var retained int
	if err := s.Store.DB.QueryRow("SELECT COUNT(*) FROM sync_history").Scan(&retained); err != nil || retained != 7 {
		t.Fatal("polling pruned history", retained, err)
	}
}
