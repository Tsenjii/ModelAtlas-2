package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type publicResearchLiveTransport struct {
	base     http.RoundTripper
	calls    atomic.Int32
	mu       sync.Mutex
	attempts []map[string]any
}

func (t *publicResearchLiveTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.Method != "GET" || r.URL.Scheme != "https" || !oneOf(r.URL.Hostname(), "console.groq.com", "inference-docs.cerebras.ai", "openrouter.ai") || r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
		return nil, fmt.Errorf("only selected anonymous public provider docs permitted")
	}
	if t.calls.Add(1) > 12 {
		return nil, fmt.Errorf("public research request budget exhausted")
	}
	response, e := t.base.RoundTrip(r)
	attempt := map[string]any{"host": r.URL.Hostname(), "path": r.URL.Path}
	if response != nil {
		attempt["status"] = response.StatusCode
	}
	if e != nil {
		attempt["error"] = e.Error()
	}
	t.mu.Lock()
	t.attempts = append(t.attempts, attempt)
	t.mu.Unlock()
	return response, e
}

// Uses actual Agent tools and worker against public pages. No private accounts,
// purchases, signup, provider credential entry or automatic membership changes.
func TestLiveKiloProviderDiscovery(t *testing.T) {
	if os.Getenv("MODELATLAS_LIVE_KILO_RESEARCH") != "1" {
		t.Skip("explicit live Provider discovery opt-in")
	}
	s := testServer(t)
	ctx, stop := context.WithTimeout(context.Background(), 5*time.Minute)
	defer stop()
	inference := &anonymousLiveTransport{base: http.DefaultTransport}
	s.Client = &http.Client{Transport: inference, Timeout: 120 * time.Second}
	public := newPublicClient()
	fetch := &publicResearchLiveTransport{base: public.Transport}
	public.Transport = fetch
	s.PublicClient = public
	p := Provider{ID: "kilo-research", Name: "Kilo anonymous research session", BaseURL: "https://api.kilo.ai/api/gateway", AuthMode: "NONE"}
	requireCode(t, req(t, s, "POST", "/api/v1/providers", p), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/accounts", Account{ID: "disposable", ProviderID: p.ID, Label: "Public research only"}), 201)
	requireCode(t, req(t, s, "POST", "/api/v1/credentials", Credential{ID: "anonymous", ProviderID: p.ID, AccountID: "disposable", Label: "Anonymous"}), 201)
	model := os.Getenv("MODELATLAS_KILO_MODEL")
	if model == "" {
		model = "nvidia/nemotron-3.5-lightning:free"
	}
	root, _, e := s.getJSON(ctx, p, ProviderConfig{Adapter: "GENERIC", AuthMode: "NONE"}, p.BaseURL+"/models", "", 4<<20)
	if e != nil {
		t.Fatal("actual anonymous catalog unavailable")
	}
	var catalog []map[string]json.RawMessage
	if json.Unmarshal(root["data"], &catalog) != nil {
		t.Fatal("catalog shape")
	}
	free := false
	for _, row := range catalog {
		if stringField(row, "id") == model {
			var pricing map[string]json.RawMessage
			var supported []string
			_ = json.Unmarshal(row["pricing"], &pricing)
			_ = json.Unmarshal(row["supported_parameters"], &supported)
			input, output := scalePrice(pricing["prompt"], 1), scalePrice(pricing["completion"], 1)
			free = strings.HasSuffix(model, ":free") && input != nil && output != nil && *input == "0" && *output == "0" && oneOf("tools", supported...)
		}
	}
	if !free {
		t.Fatal("selected model no longer zero-priced and tool capable")
	}
	requireCode(t, req(t, s, "POST", "/api/v1/models", Model{ProviderID: p.ID, ID: model, DisplayName: model}), 201)
	selection := Selection{ProviderID: p.ID, AccountID: "disposable", CredentialID: "anonymous", ModelID: model, Endpoint: "CHAT_COMPLETIONS"}
	configureAssistant(t, s, selection)
	sources := []ResearchSource{
		{ID: "groq-public", Name: "Groq free plan limits", Kind: "HTML", URL: "https://console.groq.com/docs/rate-limits", Language: "en", Enabled: true},
		{ID: "cerebras-public", Name: "Cerebras pricing", Kind: "HTML", URL: "https://inference-docs.cerebras.ai/support/pricing", Language: "en", Enabled: true},
		{ID: "openrouter-public", Name: "OpenRouter public pricing", Kind: "HTML", URL: "https://openrouter.ai/pricing", Language: "en", Enabled: true},
	}
	for _, source := range sources {
		requireCode(t, req(t, s, "POST", "/api/v1/research/sources", source), 200)
	}
	// Catalog auto-enrichment after explicit membership is separately tested;
	// keep this live run's request budget focused on the requested discovery.
	_, _ = s.Store.DB.Exec("UPDATE sync_history SET status='SKIPPED_LIVE_RESEARCH_SCOPE' WHERE status='PENDING'")
	sourceData, _ := json.Marshal(sources)
	message := "請用工具實際研究這三個 Provider 的公開官方頁面。先 inspect_website 查看頁面（最多三次），再 start_research 使用 source_ids groq-public、cerebras-public、openrouter-public 保存來源、候選與 evidence。目標是尋找可以繼續驗證的免費 API Provider，不是宣稱通過篩選。不要登入、註冊、測試需 key 的模型、修改任何 Key 或 My Models。不要猜台灣可用、recurring free、RPD/TPD 或模型身分；缺證據必須 Unknown。最後簡短列出來源連結、已證實的部分、仍待驗證的部分。實際來源定義：" + string(sourceData)
	response := req(t, s, "POST", "/api/v1/assistant/run", assistantRequest{Message: message})
	requireCode(t, response, 200)
	if !strings.Contains(response.Body.String(), "event: done") {
		t.Fatal("research Assistant did not complete")
	}
	var run string
	fetch.mu.Lock()
	diagnostic, _ := json.Marshal(fetch.attempts)
	fetch.mu.Unlock()
	t.Logf("Public page transport attempts: %s", diagnostic)
	if path := os.Getenv("MODELATLAS_LIVE_RESEARCH_OUT"); path != "" {
		var resultRaw string
		s.Store.DB.QueryRow("SELECT result_json FROM assistant_runs ORDER BY rowid DESC LIMIT 1").Scan(&resultRaw)
		raw, _ := json.MarshalIndent(map[string]any{"public_attempts": fetch.attempts, "assistant_result": json.RawMessage(resultRaw)}, "", "  ")
		_ = os.WriteFile(path+".diagnostic.json", raw, 0600)
	}
	if s.Store.DB.QueryRow("SELECT id FROM research_runs ORDER BY rowid DESC LIMIT 1").Scan(&run) != nil {
		t.Fatal("Kilo did not start real research")
	}
	s.runResearchPending(ctx)
	var tasks, success, candidates, evidence int
	s.Store.DB.QueryRow("SELECT COUNT(*),SUM(status='SUCCESS') FROM research_tasks WHERE run_id=?", run).Scan(&tasks, &success)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_candidates").Scan(&candidates)
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM research_evidence").Scan(&evidence)
	var memberCount int
	s.Store.DB.QueryRow("SELECT COUNT(*) FROM my_models").Scan(&memberCount)
	if memberCount != 1 {
		t.Fatal("discovery implicitly added My Models")
	}
	report := map[string]any{"observed_at": time.Now().UTC().Format(time.RFC3339), "model": model, "anonymous": true, "run_id": run, "research_tasks": tasks, "successful_tasks": success, "candidate_count": candidates, "evidence_count": evidence, "inference_http": inference.calls.Load(), "public_http": fetch.calls.Load(), "sources": sources, "scope": "public primary docs only; disposable app state; no credentials or signup; discovered leads are not recurring-free/Taiwan/model identity verification"}
	var resultRaw string
	s.Store.DB.QueryRow("SELECT result_json FROM assistant_runs ORDER BY rowid DESC LIMIT 1").Scan(&resultRaw)
	var result any
	_ = json.Unmarshal([]byte(resultRaw), &result)
	report["assistant_result"] = result
	rows, e := s.Store.DB.Query("SELECT name,status,result_json FROM assistant_tool_events ORDER BY rowid")
	if e != nil {
		t.Fatal(e)
	}
	events := []map[string]any{}
	for rows.Next() {
		var name, status, raw string
		rows.Scan(&name, &status, &raw)
		var result any
		_ = json.Unmarshal([]byte(raw), &result)
		events = append(events, map[string]any{"name": name, "status": status, "result": result})
	}
	rows.Close()
	report["tool_events"] = events
	fetch.mu.Lock()
	report["public_attempts"] = fetch.attempts
	fetch.mu.Unlock()
	raw, _ := json.MarshalIndent(report, "", "  ")
	if path := os.Getenv("MODELATLAS_LIVE_RESEARCH_OUT"); path != "" {
		if e := os.WriteFile(path, raw, 0600); e != nil {
			t.Fatal(e)
		}
	}
	t.Logf("Actual Kilo Provider research: tasks=%d successful=%d candidates=%d evidence=%d inference_http=%d public_http=%d", tasks, success, candidates, evidence, inference.calls.Load(), fetch.calls.Load())
	if success < 2 || candidates < 2 || evidence < 2 {
		t.Fatal("public research did not obtain enough actual evidence; see retained report")
	}
}
