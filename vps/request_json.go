package main

import (
	"encoding/json"
	"errors"
	"io"
	"sort"
	"strings"
	"unicode/utf8"
)

var errRequestJSONLimit = errors.New("request JSON exceeds conversation limit")

// Keep immutable conversation strings in place instead of allocating a second
// complete wire body and a third, pooled encoding/json buffer. All encoding,
// size checks and secret checks finish before the first upstream byte is sent.
// Escaped strings use bounded chunks; no temporary file or writer goroutine is
// needed. Other scalar/struct types retain encoding/json's exact semantics.
type requestJSON struct {
	parts []string
	size  int64
	limit int64
}

func encodeRequestJSON(value any, limit int64) (*requestJSON, error) {
	body := &requestJSON{limit: limit}
	if err := body.encode(value, 0); err != nil {
		return nil, err
	}
	return body, nil
}

func (b *requestJSON) add(part string) error {
	if int64(len(part)) > b.limit-b.size {
		return errRequestJSONLimit
	}
	b.size += int64(len(part))
	if part != "" {
		b.parts = append(b.parts, part)
	}
	return nil
}

func jsonUnescaped(value string) bool {
	for i := 0; i < len(value); {
		c := value[i]
		if c < 0x20 || c == '\\' || c == '"' || c == '<' || c == '>' || c == '&' {
			return false
		}
		if c < utf8.RuneSelf {
			i++
			continue
		}
		r, width := utf8.DecodeRuneInString(value[i:])
		if r == utf8.RuneError && width == 1 || r == '\u2028' || r == '\u2029' {
			return false
		}
		i += width
	}
	return true
}

func (b *requestJSON) quoted(value string) error {
	if err := b.add("\""); err != nil {
		return err
	}
	if jsonUnescaped(value) {
		if err := b.add(value); err != nil {
			return err
		}
	} else {
		for start := 0; start < len(value); {
			end := min(start+32*1024, len(value))
			// Never turn a valid rune at a chunk boundary into replacement runes.
			if end < len(value) {
				for end > start && !utf8.RuneStart(value[end]) {
					end--
				}
				if end == start {
					end = min(start+32*1024, len(value))
				}
			}
			part := value[start:end]
			if jsonUnescaped(part) {
				if err := b.add(part); err != nil {
					return err
				}
			} else {
				encoded, err := json.Marshal(part)
				if err != nil {
					return err
				}
				if err = b.add(string(encoded[1 : len(encoded)-1])); err != nil {
					return err
				}
			}
			start = end
		}
	}
	return b.add("\"")
}

func (b *requestJSON) encode(value any, depth int) error {
	if depth > 10000 {
		return errors.New("request JSON exceeds nesting limit")
	}
	switch v := value.(type) {
	case string:
		return b.quoted(v)
	case map[string]any:
		if v == nil {
			return b.add("null")
		}
		if err := b.add("{"); err != nil {
			return err
		}
		keys := make([]string, 0, len(v))
		for key := range v {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for i, key := range keys {
			if i > 0 {
				if err := b.add(","); err != nil {
					return err
				}
			}
			if err := b.quoted(key); err != nil {
				return err
			}
			if err := b.add(":"); err != nil {
				return err
			}
			if err := b.encode(v[key], depth+1); err != nil {
				return err
			}
		}
		return b.add("}")
	case []map[string]any:
		return b.array(len(v), v == nil, func(i int) any { return v[i] }, depth)
	case []any:
		return b.array(len(v), v == nil, func(i int) any { return v[i] }, depth)
	default:
		encoded, err := json.Marshal(value)
		if err != nil {
			return err
		}
		return b.add(string(encoded))
	}
}

func (b *requestJSON) array(length int, isNil bool, at func(int) any, depth int) error {
	if isNil {
		return b.add("null")
	}
	if err := b.add("["); err != nil {
		return err
	}
	for i := 0; i < length; i++ {
		if i > 0 {
			if err := b.add(","); err != nil {
				return err
			}
		}
		if err := b.encode(at(i), depth+1); err != nil {
			return err
		}
	}
	return b.add("]")
}

// Separate read cursors allow HTTP replay to use the identical, prevalidated
// body without another encoding or any mutation of the captured request.
type requestJSONReader struct {
	parts         []string
	index, offset int
}

func (b *requestJSON) reader() io.ReadCloser {
	return io.NopCloser(&requestJSONReader{parts: b.parts})
}

func (r *requestJSONReader) Read(out []byte) (int, error) {
	if len(out) == 0 {
		return 0, nil
	}
	written := 0
	for written < len(out) && r.index < len(r.parts) {
		n := copy(out[written:], r.parts[r.index][r.offset:])
		written += n
		r.offset += n
		if r.offset == len(r.parts[r.index]) {
			r.index++
			r.offset = 0
		}
	}
	if written == 0 {
		return 0, io.EOF
	}
	return written, nil
}

func (b *requestJSON) containsSecret(secrets []string) bool {
	seen := make(map[string]bool)
	for _, secret := range secrets {
		if secret == "" {
			continue
		}
		for depth, needle := 0, secret; depth < 4; depth++ {
			if !seen[needle] {
				seen[needle] = true
				if b.contains(needle) {
					return true
				}
			}
			encoded, _ := json.Marshal(needle)
			next := string(encoded[1 : len(encoded)-1])
			if next == needle {
				break
			}
			needle = next
		}
	}
	return false
}

func (b *requestJSON) contains(needle string) bool {
	if int64(len(needle)) > b.size {
		return false
	}
	tail, overlap := "", len(needle)-1
	for _, part := range b.parts {
		if strings.Contains(part, needle) || tail != "" && strings.Contains(tail+part[:min(overlap, len(part))], needle) {
			return true
		}
		if overlap > 0 {
			if len(part) >= overlap {
				tail = part[len(part)-overlap:]
			} else {
				tail += part
				tail = tail[max(0, len(tail)-overlap):]
			}
		}
	}
	return false
}
