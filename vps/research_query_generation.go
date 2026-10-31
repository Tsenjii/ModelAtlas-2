package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
)

var errQueryInput = errors.New("invalid query request")

func queryInput(message string) error { return fmt.Errorf("%w: %s", errQueryInput, message) }

const queryGeneratorVersion = "public-evidence-query-v1"
const pairedQueryPolicy = "research-paired-query-v1"
const researchQuerySchema = `
CREATE TABLE IF NOT EXISTS research_query_generations(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,version_id TEXT NOT NULL UNIQUE REFERENCES knowledge_versions(id),context_hash TEXT NOT NULL,automatic INTEGER NOT NULL,proof_json TEXT NOT NULL CHECK(json_valid(proof_json)),created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_query_generation_context ON research_query_generations(context_hash,automatic);
CREATE TABLE IF NOT EXISTS research_query_comparisons(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL,version_id TEXT NOT NULL REFERENCES knowledge_versions(id),version_hash TEXT NOT NULL,baseline_id TEXT NOT NULL,baseline_query TEXT NOT NULL,proposed_query TEXT NOT NULL,language TEXT NOT NULL,country TEXT NOT NULL,pages INTEGER NOT NULL,budget INTEGER NOT NULL,credential_stamp TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS research_query_comparison_version ON research_query_comparisons(version_id,created_at);
CREATE TABLE IF NOT EXISTS research_query_comparison_runs(comparison_id TEXT NOT NULL REFERENCES research_query_comparisons(id),run_id TEXT PRIMARY KEY REFERENCES research_runs(id),arm TEXT NOT NULL CHECK(arm IN ('BASELINE','PROPOSED')),options_hash TEXT NOT NULL,UNIQUE(comparison_id,arm));`

type queryGenerationSource struct {
	URL  string `json:"url"`
	Hash string `json:"sha256"`
}
type queryGenerationProof struct {
	ID            string                  `json:"id"`
	VersionID     string                  `json:"version_id"`
	Generator     string                  `json:"generator"`
	ReflectionID  string                  `json:"reflection_id"`
	ParentID      string                  `json:"parent_id,omitempty"`
	Language      string                  `json:"language"`
	Country       string                  `json:"country"`
	Pages         int                     `json:"pages"`
	ParentQuery   string                  `json:"observed_query"`
	BaselineID    string                  `json:"baseline_id"`
	BaselineQuery string                  `json:"baseline_query"`
	Query         string                  `json:"query"`
	ScopeHash     string                  `json:"scope_hash"`
	VersionHash   string                  `json:"version_hash"`
	Sources       []queryGenerationSource `json:"public_sources"`
	Notes         []string                `json:"notes"`
	Automatic     bool                    `json:"automatic_draft"`
	At            int64                   `json:"at"`
	HTTP          int                     `json:"additional_http_requests"`
	Inference     int                     `json:"model_inference_requests"`
	Cost          *string                 `json:"monetary_cost"`
}

func queryContext(v KnowledgeVersion) (taskInput, error) {
	in := taskInput{Language: v.Subject, Query: str(v.Data["query"]), SearchCountry: str(v.Data["search_country"])}
	if v.Kind != "RESEARCH_STRATEGY" || !researchLanguage(v.Subject) || str(v.Data["language"]) != v.Subject || in.Query == "" || safeText(in.Query, 300) != in.Query || !validBraveCountry(in.SearchCountry) {
		return in, queryInput("invalid query context")
	}
	if v.Data["search_pages"] != nil {
		n := quotaInteger(json.Number(numberText(v.Data["search_pages"])))
		if n == nil || *n < 1 || *n > 10 {
			return in, queryInput("invalid query pages")
		}
		in.SearchPages = int(*n)
	}
	in.SearchPages, in.SearchCountry = braveSearchOptions(in)
	return in, nil
}

func resolveQueryBaseline(ctx context.Context, q researchQueryer, in taskInput) (string, string, error) {
	plan, e := currentResearchPlan(ctx, q)
	if e != nil {
		return "", "", e
	}
	v := plan[knowledgeMetricKey("RESEARCH_STRATEGY", in.Language)]
	if text, ok := learnedResearchQuery(v, in); ok {
		return v.ID, text, nil
	}
	text, ok := searchQueries[in.Language]
	if !ok {
		return "", "", queryInput("unsupported language")
	}
	return "", text, nil
}

func readQueryGeneration(ctx context.Context, q researchQueryer, v KnowledgeVersion) (*queryGenerationProof, error) {
	id := str(v.Data["query_generation_id"])
	if id == "" {
		return nil, nil
	}
	var raw string
	if e := q.QueryRowContext(ctx, "SELECT proof_json FROM research_query_generations WHERE id=? AND version_id=?", id, v.ID).Scan(&raw); e != nil {
		return nil, e
	}
	var proof queryGenerationProof
	in, e := queryContext(v)
	if e != nil || json.Unmarshal([]byte(raw), &proof) != nil || proof.ID != id || proof.Generator != queryGeneratorVersion || proof.VersionID != v.ID || proof.VersionHash != snapshotHash(v.Data) || proof.Query != in.Query || proof.Language != in.Language || proof.Country != in.SearchCountry || proof.Pages != in.SearchPages || proof.ScopeHash != researchInputScope("SEARCH", in) {
		return nil, queryInput("query generation proof changed")
	}
	return &proof, nil
}

func saveQueryGeneration(ctx context.Context, tx *sql.Tx, proof *queryGenerationProof, v KnowledgeVersion, requestHash, contextHash string) error {
	proof.VersionID = v.ID
	proof.VersionHash = snapshotHash(v.Data)
	raw, _ := json.Marshal(proof)
	_, e := tx.ExecContext(ctx, "INSERT INTO research_query_generations VALUES(?,?,?,?,?,?,?)", proof.ID, requestHash, v.ID, contextHash, proof.Automatic, string(raw), proof.At)
	return e
}

func generatedQueryText(language, baseline string, sources []queryGenerationSource) string {
	templates := map[string]string{
		"en": "model API recurring free quota RPD TPD daily reset documentation",
		"zh": "模型 API 每日重置 免費額度 RPD TPD 官方文件 限制",
		"ja": "モデル API 毎日リセット 無料枠 RPD TPD 公式ドキュメント",
		"ko": "모델 API 매일 초기화 무료 할당량 RPD TPD 공식 문서",
		"ru": "API модели бесплатная ежедневная квота RPD TPD документация",
		"es": "API modelos cuota gratuita recurrente reinicio diario RPD TPD documentación",
		"pt": "API modelos cota gratuita recorrente redefinição diária RPD TPD documentação",
		"id": "API model kuota gratis berulang reset harian RPD TPD dokumentasi",
	}
	q := templates[language]
	if len(sources) > 0 {
		u, _ := url.Parse(sources[0].URL)
		q += " " + u.Hostname()
	}
	if q == baseline {
		q = "\"RPD\" \"TPD\" " + q
	}
	return excerpt(q, 300)
}

// Generate from actual locally attributed public search results, without HTTP
// or model calls. This is a tentative draft, never an improvement assessment.
func (s *Server) generateResearchQueryTx(ctx context.Context, tx *sql.Tx, id, reflection, language string, automatic bool, secrets []string) (KnowledgeVersion, error) {
	var out KnowledgeVersion
	if !validID(id) || !validID(reflection) || !researchLanguage(language) {
		return out, queryInput("invalid generation request")
	}
	requestHash := snapshotHash(map[string]any{"reflection": reflection, "language": language})
	var oldHash, oldVersion string
	e := tx.QueryRowContext(ctx, "SELECT request_hash,version_id FROM research_query_generations WHERE id=?", id).Scan(&oldHash, &oldVersion)
	if e == nil {
		if oldHash != requestHash {
			return out, queryInput("generation id reused")
		}
		v, err := readKnowledgeVersion(ctx, tx, oldVersion)
		if err == nil {
			v.QueryGeneration, err = readQueryGeneration(ctx, tx, v)
		}
		return v, err
	}
	if e != sql.ErrNoRows {
		return out, e
	}
	var raw, payload, state, taskKind string
	if e = tx.QueryRowContext(ctx, `SELECT o.observation_json,t.payload_json,r.status,t.kind FROM research_quality_observations o JOIN research_tasks t ON t.id=json_extract(o.observation_json,'$.task_id') AND t.run_id=o.run_id JOIN research_runs r ON r.id=o.run_id WHERE o.reflection_id=? AND o.metric_key=?`, reflection, knowledgeMetricKey("RESEARCH_STRATEGY", language)).Scan(&raw, &payload, &state, &taskKind); e != nil {
		return out, e
	}
	var quality researchQuality
	var in taskInput
	if json.Unmarshal([]byte(raw), &quality) != nil || json.Unmarshal([]byte(payload), &in) != nil || state != "COMPLETED" || taskKind != "SEARCH" || quality.Status != "SUCCESS" || !usableResearchQuality(quality) || quality.Subject != language || in.Language != language || quality.ScopeHash != researchInputScope("SEARCH", in) {
		return out, queryInput("completed local search evidence required")
	}
	bindings, e := researchBindings(ctx, tx, quality.RunID)
	if e != nil {
		return out, e
	}
	if b, ok := bindings[quality.MetricKey]; !ok || b.ScopeHash != quality.ScopeHash {
		return out, queryInput("local query binding required")
	}
	rows, e := tx.QueryContext(ctx, `SELECT e.source_url,json_extract(d.details_json,'$.content_hash') FROM research_local_evidence l JOIN research_evidence e ON e.id=l.evidence_id JOIN research_evidence_details d ON d.evidence_id=e.id WHERE l.run_id=? AND l.task_id=? AND e.source_type='SEARCH_DISCOVERY' ORDER BY e.rowid LIMIT 6`, quality.RunID, quality.TaskID)
	if e != nil {
		return out, e
	}
	sources := []queryGenerationSource{}
	for rows.Next() {
		var src queryGenerationSource
		if e = rows.Scan(&src.URL, &src.Hash); e != nil {
			rows.Close()
			return out, e
		}
		if publicURL(src.URL) && len(src.Hash) == 64 {
			safe := true
			for _, secret := range secrets {
				if jsonContainsSecret(src, secret) {
					safe = false
				}
			}
			if safe {
				sources = append(sources, src)
			}
		}
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return out, e
	}
	if len(sources) == 0 {
		return out, queryInput("public search source proof required")
	}
	in.SearchPages, in.SearchCountry = braveSearchOptions(in)
	baseID, baseQuery, e := resolveQueryBaseline(ctx, tx, in)
	if e != nil {
		return out, e
	}
	contextHash := snapshotHash(map[string]any{"language": language, "country": in.SearchCountry, "pages": in.SearchPages, "baseline_id": baseID, "baseline_query": baseQuery})
	if automatic {
		e = tx.QueryRowContext(ctx, "SELECT version_id FROM research_query_generations WHERE context_hash=? AND automatic=1 ORDER BY rowid LIMIT 1", contextHash).Scan(&oldVersion)
		if e == nil {
			v, err := readKnowledgeVersion(ctx, tx, oldVersion)
			if err == nil {
				v.QueryGeneration, err = readQueryGeneration(ctx, tx, v)
			}
			return v, err
		}
		if e != sql.ErrNoRows {
			return out, e
		}
		id = "auto-query-" + contextHash[:32]
	}
	proposed := generatedQueryText(language, baseQuery, sources)
	proof := &queryGenerationProof{ID: id, Generator: queryGeneratorVersion, ReflectionID: reflection, Language: language, Country: in.SearchCountry, Pages: in.SearchPages, ParentQuery: in.Query, BaselineID: baseID, BaselineQuery: baseQuery, Query: proposed, Sources: sources, Automatic: automatic, At: now(), Notes: []string{"只在本機從已保存的公開搜尋來源生成查詢，未發HTTP或模型推論；貨幣成本保持Unknown。", "以每日重置／RPD／TPD文件補足額度與一次性贈金的判讀；來源只是未驗證線索。", "這不是已測改善。須明確執行至少3組實際基線／提案對照，評估後才能確認試用。"}}
	if quality.NewCandidates == 0 || quality.Utility == 0 {
		proof.Notes = append(proof.Notes, "原查詢沒有產生新的有效品質分數；保留這個失敗／重複策略經驗，不以HTTP 200當成果。")
	}
	in.Query = proposed
	proof.ScopeHash = researchInputScope("SEARCH", in)
	out = KnowledgeVersion{Kind: "RESEARCH_STRATEGY", Subject: language, Data: map[string]any{"language": language, "query": proposed, "search_country": in.SearchCountry, "search_pages": in.SearchPages, "weight": 1, "priority": 1, "confidence": "TENTATIVE", "query_generation_id": id}, EvidenceIDs: []string{reflection}, Reason: "依本機公開搜尋證據產生具體查詢草稿；尚未實測，不自動啟用。", RunID: quality.RunID}
	for _, secret := range secrets {
		if jsonContainsSecret(out, secret) || jsonContainsSecret(proof, secret) {
			return KnowledgeVersion{}, queryInput("unsafe query metadata")
		}
	}
	out, e = appendKnowledge(ctx, tx, out)
	if e != nil {
		return out, e
	}
	e = saveQueryGeneration(ctx, tx, proof, out, requestHash, contextHash)
	out.QueryGeneration = proof
	return out, e
}

func (s *Server) generateReflectionQueriesTx(ctx context.Context, tx *sql.Tx, reflection string, quality []researchQuality, secrets []string) error {
	for _, q := range quality {
		if q.Kind != "RESEARCH_STRATEGY" || q.Status != "SUCCESS" || !usableResearchQuality(q) || q.Candidates == 0 {
			continue
		}
		if _, e := s.generateResearchQueryTx(ctx, tx, newID(), reflection, q.Subject, true, secrets); e != nil {
			return e
		}
	}
	return nil
}

func (s *Server) generateResearchQuery(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID           string `json:"id"`
		ReflectionID string `json:"reflection_id"`
		Language     string `json:"language"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	secrets, e := s.knownSecrets(r.Context())
	if e != nil {
		http.Error(w, "vault unavailable", 503)
		return
	}
	tx, e := s.Store.DB.BeginTx(r.Context(), nil)
	if e != nil {
		http.Error(w, "query generation unavailable", 503)
		return
	}
	defer tx.Rollback()
	v, e := s.generateResearchQueryTx(r.Context(), tx, in.ID, in.ReflectionID, in.Language, false, secrets)
	if e != nil {
		code := 503
		if errors.Is(e, errQueryInput) || errors.Is(e, sql.ErrNoRows) {
			code = 422
		}
		http.Error(w, "completed local public search evidence and an unused request id are required", code)
		return
	}
	if e = tx.Commit(); e != nil {
		http.Error(w, "query generation persistence unavailable", 503)
		return
	}
	jsonOK(w, 201, v)
}

func prepareQueryRevision(ctx context.Context, tx *sql.Tx, v *KnowledgeVersion) (*queryGenerationProof, error) {
	id := str(v.Data["query_generation_id"])
	if id == "" {
		return nil, nil
	}
	var parentID string
	if e := tx.QueryRowContext(ctx, "SELECT version_id FROM research_query_generations WHERE id=?", id).Scan(&parentID); e != nil {
		return nil, e
	}
	parent, e := readKnowledgeVersion(ctx, tx, parentID)
	if e != nil {
		return nil, e
	}
	saved, e := readQueryGeneration(ctx, tx, parent)
	if e != nil || saved == nil {
		return nil, queryInput("local parent generation required")
	}
	in, e := queryContext(*v)
	if e != nil || in.Language != saved.Language || in.SearchCountry != saved.Country || in.SearchPages != saved.Pages || numberText(v.Data["weight"]) != "1" || numberText(v.Data["priority"]) != "1" || str(v.Data["scope_hash"]) != "" {
		return nil, queryInput("query revision must retain the measured context and default priority")
	}
	proof := *saved
	proof.ID = newID()
	proof.ParentID = parentID
	proof.Query = in.Query
	proof.ScopeHash = researchInputScope("SEARCH", in)
	proof.At = now()
	proof.Automatic = false
	v.Data["query_generation_id"] = proof.ID
	return &proof, nil
}
