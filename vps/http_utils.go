package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"unicode"
)

func readBounded(r io.Reader, n int64) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, n+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > n {
		return nil, errors.New("response limit")
	}
	return b, nil
}
func safeText(value string, max int, secrets ...string) string {
	if len(value) > max || strings.IndexFunc(value, func(r rune) bool { return unicode.IsControl(r) || unicode.Is(unicode.Cf, r) }) >= 0 {
		return ""
	}
	for _, secret := range secrets {
		if secret == "" {
			continue
		}
		for _, form := range []string{secret, url.QueryEscape(secret), base64.StdEncoding.EncodeToString([]byte(secret))} {
			if strings.Contains(value, form) {
				return ""
			}
		}
	}
	return value
}
func (s *Server) getJSON(ctx context.Context, p Provider, c ProviderConfig, rawURL, key string, limit int64) (map[string]json.RawMessage, int, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", rawURL, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Accept", "application/json")
	if err = s.authorizeUpstream(req, p, key, c); err != nil {
		return nil, 0, err
	}
	// Never inherit permissive redirect policy from a caller-supplied HTTP client.
	client := *s.Client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, errors.New("upstream network failure")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, resp.StatusCode, errors.New("upstream HTTP failure")
	}
	b, err := readBounded(resp.Body, limit)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	var root map[string]json.RawMessage
	if json.Unmarshal(b, &root) != nil || root == nil {
		return nil, resp.StatusCode, errors.New("malformed JSON")
	}
	return root, resp.StatusCode, nil
}
