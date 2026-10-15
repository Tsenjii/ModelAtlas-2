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
	value, status, e := s.getStructured(ctx, p, c, rawURL, key, limit)
	if e != nil {
		return nil, status, e
	}
	raw, _ := json.Marshal(value)
	var root map[string]json.RawMessage
	if json.Unmarshal(raw, &root) != nil || root == nil {
		return nil, status, errors.New("malformed JSON object")
	}
	return root, status, nil
}
func (s *Server) getStructured(ctx context.Context, p Provider, c ProviderConfig, rawURL, key string, limit int64) (any, int, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", rawURL, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Accept", "application/json")
	if err = s.authorizeUpstream(req, p, key, c); err != nil {
		return nil, 0, err
	}
	// Never inherit permissive redirect policy from a caller-supplied HTTP client.
	selectedClient := s.Client
	if enabled, _ := ctx.Value(publicAnonymousOnboardKey{}).(bool); enabled {
		if c.AuthMode != "NONE" || key != "" || len(p.AuthFields) > 0 || !publicURL(rawURL) {
			return nil, 0, errors.New("public anonymous onboarding only")
		}
		s.flightMu.Lock()
		if s.PublicClient == nil {
			s.PublicClient = newPublicClient()
		}
		selectedClient = s.PublicClient
		s.flightMu.Unlock()
	}
	client := *selectedClient
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if err := s.chargeNetwork(ctx, false); err != nil {
		return nil, 0, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, errors.New("upstream network failure")
	}
	defer resp.Body.Close()
	if name, _ := ctx.Value(integrationHeaderKey{}).(string); name != "" {
		s.observeIntegrationHeaders(ctx, name, resp.Header)
	}
	if resp.StatusCode != 200 {
		return nil, resp.StatusCode, errors.New("upstream HTTP failure")
	}
	b, err := readBounded(resp.Body, limit)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	secrets := []string{key}
	for _, field := range p.AuthFields {
		secrets = append(secrets, field.Secret)
	}
	var decoded any
	decoder := json.NewDecoder(strings.NewReader(string(b)))
	decoder.UseNumber()
	if decoder.Decode(&decoded) != nil {
		return nil, resp.StatusCode, errors.New("malformed JSON")
	}
	if decoder.Decode(new(any)) != io.EOF {
		return nil, resp.StatusCode, errors.New("trailing JSON")
	}
	cleaned := cleanSecretEcho(decoded, secrets, 0)
	return cleaned, resp.StatusCode, nil
}

func cleanSecretEcho(value any, secrets []string, depth int) any {
	if depth > 64 {
		return nil
	}
	switch v := value.(type) {
	case string:
		if safeText(v, len(v), secrets...) == "" && v != "" {
			return nil
		}
		return v
	case map[string]any:
		out := map[string]any{}
		for key, value := range v {
			if key != "" && safeText(key, len(key), secrets...) == "" {
				continue
			}
			out[key] = cleanSecretEcho(value, secrets, depth+1)
		}
		return out
	case []any:
		out := make([]any, len(v))
		for i, value := range v {
			out[i] = cleanSecretEcho(value, secrets, depth+1)
		}
		return out
	default:
		return value
	}
}

type publicAnonymousOnboardKey struct{}
