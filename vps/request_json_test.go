package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"strings"
	"testing"
)

func TestRequestJSONWireAndReplay(t *testing.T) {
	var nilMaps []map[string]any
	var nilValues []any
	values := []any{
		map[string]any{"model": "模型", "messages": []map[string]any{{"role": "user", "content": "完整上下文"}}, "stream": true, "max_tokens": 512},
		map[string]any{"nil": nil, "nilMaps": nilMaps, "nilValues": nilValues, "empty": []any{}, "decimal": json.Number("0.000000000123456789"), "raw": json.RawMessage(`{"nested":"<>&"}`)},
		map[string]any{"struct": struct {
			Value string `json:"value"`
			Empty string `json:"empty,omitempty"`
		}{Value: "tool-call"}},
		"\"\\\b\f\n\r\t\x00\x1f<>&\u2028\u2029" + string([]byte{0xff, 0xc0, 0x80}),
		strings.Repeat("中", 10922) + "\"中文跨界\n" + strings.Repeat("😀", 12000),
		strings.Repeat("保留完整 Unicode 對話", 100000),
	}
	for i, value := range values {
		expected, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		body, err := encodeRequestJSON(value, int64(len(expected)))
		if err != nil {
			t.Fatalf("case %d: %v", i, err)
		}
		first := body.reader()
		prefix := make([]byte, min(7, len(expected)))
		if _, err = io.ReadFull(first, prefix); err != nil {
			t.Fatal(err)
		}
		second, err := io.ReadAll(body.reader())
		if err != nil || !bytes.Equal(second, expected) || body.size != int64(len(expected)) {
			t.Fatalf("case %d changed wire or content length", i)
		}
		rest, err := io.ReadAll(first)
		if err != nil || !bytes.Equal(append(prefix, rest...), expected) {
			t.Fatalf("case %d replay changed the original reader", i)
		}
		if _, err = encodeRequestJSON(value, int64(len(expected)-1)); !errors.Is(err, errRequestJSONLimit) {
			t.Fatalf("case %d lost exact encoded-byte size gate: %v", i, err)
		}
	}
	if _, err := encodeRequestJSON(map[string]any{"invalid": math.Inf(1)}, 32<<20); err == nil {
		t.Fatal("unsupported JSON scalar accepted")
	}
}

func TestRequestJSONSecretBoundaries(t *testing.T) {
	secret := "cross-boundary-secret"
	escapedSecret := "key\"<>&\\\n中文"
	variant, _ := json.Marshal(escapedSecret)
	encodedSecret := string(variant[1 : len(variant)-1])
	nested := escapedSecret
	for depth := 0; depth < 4; depth++ {
		cases := []any{
			map[string]any{"messages": []map[string]any{{"role": "user", "content": "\"" + strings.Repeat("a", 32760) + secret}}},
			map[string]any{"messages": []map[string]any{{"role": "user", "content": nested}}},
			map[string]any{"safe": "ordinary content", "empty": ""},
		}
		for _, value := range cases {
			raw, _ := json.Marshal(value)
			body, err := encodeRequestJSON(value, 32<<20)
			if err != nil {
				t.Fatal(err)
			}
			for _, secrets := range [][]string{{"", secret, secret}, {escapedSecret}, {escapedSecret, encodedSecret}, {`","`}, {"not-present"}, {strings.Repeat("x", len(raw)+1)}} {
				if body.containsSecret(secrets) != encodedJSONContainsSecret(raw, secrets) {
					t.Fatalf("secret guard differs at nesting depth %d", depth)
				}
			}
		}
		raw, _ := json.Marshal(nested)
		nested = string(raw)
	}
}

// Same 31-MiB context, same fully consumed wire. Run with -benchmem to compare
// encoding allocations without mistaking test-runner RSS for server memory.
func BenchmarkInferenceJSONBody(b *testing.B) {
	chunk := strings.Repeat("完整上下文 context ", (1<<20)/len("完整上下文 context "))
	messages := make([]map[string]any, 31)
	for i := range messages {
		messages[i] = map[string]any{"role": "user", "content": chunk}
	}
	payload := map[string]any{"model": "m", "messages": messages, "max_tokens": 512, "stream": true}
	b.Run("contiguous", func(b *testing.B) {
		b.ReportAllocs()
		for range b.N {
			raw, err := json.Marshal(payload)
			if err != nil {
				b.Fatal(err)
			}
			if _, err = io.Copy(io.Discard, bytes.NewReader(raw)); err != nil {
				b.Fatal(err)
			}
		}
	})
	b.Run("segmented", func(b *testing.B) {
		b.ReportAllocs()
		for range b.N {
			body, err := encodeRequestJSON(payload, 32<<20)
			if err != nil {
				b.Fatal(err)
			}
			if _, err = io.Copy(io.Discard, body.reader()); err != nil {
				b.Fatal(err)
			}
		}
	})
}
