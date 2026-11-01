package main

import (
	"encoding/json"
	"sort"
	"strings"
	"unicode/utf8"
)

func workspaceRecordDigest(value WorkspaceRecord) (string, error) {
	body, e := workspaceRecordBody(value, workspaceChunkRecordBytes+(1<<20))
	if e != nil {
		return "", e
	}
	hash, _, e := workspaceReaderHash(body.reader())
	return hash, e
}
func workspaceDecodeRecord(raw string) (WorkspaceRecord, error) {
	type fields WorkspaceRecord
	var value WorkspaceRecord
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	e := decoder.Decode((*fields)(&value))
	return value, e
}

// Presentation only. Full records remain in their original product/history
// tables, and compare-and-set always uses the complete live-record digest.
func workspacePreviewRecord(value WorkspaceRecord) (WorkspaceRecord, bool) {
	remaining := 8192
	limited := false
	var visit func(any, int) any
	visit = func(v any, depth int) any {
		if remaining <= 0 || depth > 16 {
			limited = true
			return nil
		}
		switch x := v.(type) {
		case string:
			n := min(len(x), remaining)
			if n < len(x) {
				for n > 0 && !utf8.RuneStart(x[n]) {
					n--
				}
				limited = true
			}
			remaining -= n
			if n < len(x) {
				return x[:n] + "…"
			}
			return x
		case json.Number:
			if len(x) > min(128, remaining) {
				limited = true
				return "（長數值摘要）"
			}
			remaining -= len(x)
			return x
		case map[string]any:
			out := map[string]any{}
			keys := make([]string, 0, len(x))
			for key := range x {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				if remaining <= 0 || len(key) > 256 || len(out) >= 128 {
					limited = true
					continue
				}
				remaining -= len(key) + 4
				out[key] = visit(x[key], depth+1)
			}
			return out
		case []any:
			out := []any{}
			for i, item := range x {
				if remaining <= 0 || i >= 128 {
					limited = true
					break
				}
				remaining--
				out = append(out, visit(item, depth+1))
			}
			return out
		default:
			remaining -= 32
			return x
		}
	}
	preview := visit(value.Data, 0)
	if preview == nil {
		value.Data = map[string]any{}
	} else {
		var ok bool
		value.Data, ok = preview.(map[string]any)
		if !ok {
			return value, true
		}
	}
	return value, limited
}
