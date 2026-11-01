package main

import (
	"encoding/json"
	"math"
	"sort"
	"strings"
)

type VectorResult struct {
	Index     int       `json:"index"`
	Embedding []float64 `json:"embedding"`
}
type RerankResult struct {
	Index int     `json:"index"`
	Score float64 `json:"relevance_score"`
}

func validVectorRequest(v MediaRequest, op string) bool {
	if v.Image != nil || v.Audio != nil || v.Size != "" || v.Dimensions < 0 || v.Dimensions > 8192 || v.TopN < 0 {
		return false
	}
	inputs := v.Inputs
	if op == "RERANK" {
		inputs = v.Documents
		if v.Dimensions != 0 || len(v.Inputs) > 0 || v.TopN > len(inputs) {
			return false
		}
	} else if len(v.Documents) > 0 || v.Prompt != "" || v.TopN != 0 {
		return false
	}
	if len(inputs) < 1 || len(inputs) > 32 {
		return false
	}
	n := 0
	for _, t := range inputs {
		n += len(t)
		if strings.TrimSpace(t) == "" || len(t) > 32000 {
			return false
		}
	}
	return n <= 128<<10
}
func parseVectors(root map[string]json.RawMessage, v MediaRequest, op string, result *MediaResponse) bool {
	if op == "EMBEDDINGS" {
		if json.Unmarshal(root["data"], &result.Vectors) != nil || len(result.Vectors) != len(v.Inputs) {
			return false
		}
		seen := map[int]bool{}
		dims := 0
		for _, vec := range result.Vectors {
			if vec.Index < 0 || vec.Index >= len(v.Inputs) || seen[vec.Index] || len(vec.Embedding) < 1 || len(vec.Embedding) > 8192 {
				return false
			}
			seen[vec.Index] = true
			if dims == 0 {
				dims = len(vec.Embedding)
			}
			if dims != len(vec.Embedding) || v.Dimensions != 0 && dims != v.Dimensions {
				return false
			}
			for _, f := range vec.Embedding {
				if math.IsNaN(f) || math.IsInf(f, 0) {
					return false
				}
			}
		}
		sort.Slice(result.Vectors, func(i, j int) bool { return result.Vectors[i].Index < result.Vectors[j].Index })
		return true
	}
	if json.Unmarshal(root["results"], &result.Rankings) != nil || len(result.Rankings) < 1 || len(result.Rankings) > len(v.Documents) || v.TopN > 0 && len(result.Rankings) > v.TopN {
		return false
	}
	seen := map[int]bool{}
	for _, r := range result.Rankings {
		if r.Index < 0 || r.Index >= len(v.Documents) || seen[r.Index] || math.IsNaN(r.Score) || math.IsInf(r.Score, 0) {
			return false
		}
		seen[r.Index] = true
	}
	return true
}
