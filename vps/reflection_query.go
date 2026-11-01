package main

import "encoding/json"

// Check the context before replacing a query, including legacy unscoped rules.
func researchStrategyContextMatches(v KnowledgeVersion, in taskInput) bool {
	if v.Subject != in.Language || (str(v.Data["language"]) != "" && str(v.Data["language"]) != in.Language) {
		return false
	}
	pages := 0
	if v.Data["search_pages"] != nil {
		n := quotaInteger(json.Number(numberText(v.Data["search_pages"])))
		if n == nil || *n < 1 || *n > 10 {
			return false
		}
		pages = int(*n)
	}
	vp, vc := braveSearchOptions(taskInput{SearchPages: pages, SearchCountry: str(v.Data["search_country"])})
	p, c := braveSearchOptions(in)
	return validBraveCountry(vc) && vp == p && vc == c
}

func learnedResearchQuery(v KnowledgeVersion, in taskInput) (string, bool) {
	q := str(v.Data["query"])
	if !researchStrategyContextMatches(v, in) || q == "" || safeText(q, 300) != q {
		return "", false
	}
	in.Query = q
	if h := str(v.Data["scope_hash"]); h != "" && h != researchInputScope("SEARCH", in) {
		return "", false
	}
	return q, true
}
