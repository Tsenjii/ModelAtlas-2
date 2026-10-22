package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"html"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"
)

func publicURL(raw string) bool {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Opaque != "" || u.Fragment != "" || len(raw) > 4096 {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") {
		return false
	}
	if ip := net.ParseIP(host); ip != nil && !publicIP(ip) {
		return false
	}
	for k := range u.Query() {
		if oneOf(strings.ToLower(k), "key", "api_key", "apikey", "token", "access_token", "authorization", "cookie") {
			return false
		}
	}
	return true
}
func publicIP(ip net.IP) bool {
	if ip == nil || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified() || ip.IsMulticast() || !ip.IsGlobalUnicast() {
		return false
	}
	address, ok := netip.AddrFromSlice(ip)
	if !ok {
		return false
	}
	address = address.Unmap()
	if publicNAT64.Contains(address) {
		raw := address.As16()
		return publicIP(net.IP(raw[12:]))
	}
	if address == netip.MustParseAddr("192.0.0.9") || address == netip.MustParseAddr("192.0.0.10") {
		return true
	}
	for _, prefix := range nonPublicRanges {
		if prefix.Contains(address) {
			return false
		}
	}
	return true
}

var publicNAT64 = netip.MustParsePrefix("64:ff9b::/96")

// IANA special-use ranges, plus tunnel mechanisms whose embedded destination
// cannot be treated as a pinned public web address. No DNS/proxy bypass.
var nonPublicRanges = func() []netip.Prefix {
	values := []string{"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "::/96", "fec0::/10", "64:ff9b:1::/48", "100::/64", "100:0:0:1::/64", "2001::/32", "2001:2::/48", "2001:10::/28", "2001:db8::/32", "2002::/16", "3fff::/20", "5f00::/16"}
	out := []netip.Prefix{}
	for _, value := range values {
		out = append(out, netip.MustParsePrefix(value))
	}
	return out
}()

func newPublicClient() *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.MaxIdleConns = 4
	transport.MaxIdleConnsPerHost = 1
	transport.IdleConnTimeout = 30 * time.Second
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, e := net.SplitHostPort(address)
		if e != nil {
			return nil, errors.New("invalid public address")
		}
		ips, e := net.DefaultResolver.LookupIP(ctx, "ip", host)
		if e != nil {
			return nil, errors.New("DNS unavailable")
		}
		if len(ips) == 0 {
			return nil, errors.New("DNS empty")
		}
		for _, ip := range ips {
			if !publicIP(ip) {
				return nil, errors.New("private address denied")
			}
		}
		dial := net.Dialer{Timeout: 10 * time.Second}
		return dial.DialContext(ctx, network, net.JoinHostPort(ips[0].String(), port))
	}
	return &http.Client{Transport: transport, Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}
func (s *Server) publicFetch(ctx context.Context, raw string) ([]byte, string, int, error) {
	origin := researchPublicOrigin(raw)
	if origin == "" {
		return nil, "", 0, errors.New("public HTTPS URL required")
	}
	request, e := http.NewRequestWithContext(ctx, "GET", raw, nil)
	if e != nil {
		return nil, "", 0, e
	}
	request.Header.Set("User-Agent", "ModelAtlas/1.0 (+research; bounded fetch)")
	request.Header.Set("Accept", "application/json,text/html,text/plain;q=0.8")
	s.flightMu.Lock()
	if s.PublicClient == nil {
		s.PublicClient = newPublicClient()
	}
	client := s.PublicClient
	s.flightMu.Unlock()
	copyClient := *client
	copyClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if e = s.chargeResearchNetwork(ctx, false, origin); e != nil {
		return nil, "", 0, e
	}
	if meter, ok := ctx.Value(connectorDiscoveryMeterKey{}).(*int); ok {
		*meter++
	}
	admittedAt := s.scheduleTime().UnixMilli()
	resp, e := copyClient.Do(request)
	if e != nil {
		return nil, "", 0, errors.New("public fetch failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		failure := publicHTTPFailure(resp.StatusCode, resp.Header, s.scheduleTime())
		var transient *researchHTTPFailure
		if errors.As(failure, &transient) {
			failure = errors.Join(failure, s.recordResearchOriginFailure(origin, transient))
		}
		return nil, "", resp.StatusCode, failure
	}
	rawBody, e := readBounded(resp.Body, 1<<20)
	if e == nil {
		s.researchOriginSucceeded(origin, admittedAt)
	}
	return rawBody, resp.Header.Get("Content-Type"), resp.StatusCode, e
}
func contentHash(b []byte) string { sum := sha256.Sum256(b); return hex.EncodeToString(sum[:]) }

var hiddenHTML = regexp.MustCompile(`(?is)<(script|style|noscript)\b[^>]*>.*?</(script|style|noscript)\s*>`)
var htmlTags = regexp.MustCompile(`(?s)<[^>]*>`)
var spaceText = regexp.MustCompile(`\s+`)
var hrefPattern = regexp.MustCompile(`(?i)(?:href|src)\s*=\s*["']([^"']+)["']`)
var endpointPattern = regexp.MustCompile(`["'](/(?:api|v1|v1beta)/[A-Za-z0-9_./{}:-]{1,180})["']`)

func inspectHTML(raw []byte, base string) (string, []string, []string) {
	text := spaceText.ReplaceAllString(html.UnescapeString(htmlTags.ReplaceAllString(hiddenHTML.ReplaceAllString(string(raw), " "), " ")), " ")
	if len(text) > 16000 {
		text = text[:16000]
	}
	links, paths := []string{}, []string{}
	seen := map[string]bool{}
	u, _ := url.Parse(base)
	for _, match := range hrefPattern.FindAllStringSubmatch(string(raw), 100) {
		v, e := url.Parse(html.UnescapeString(match[1]))
		if e != nil {
			continue
		}
		v = u.ResolveReference(v)
		v.Fragment = ""
		href := v.String()
		if publicURL(href) && !seen[href] {
			links = append(links, href)
			seen[href] = true
		}
	}
	for _, m := range endpointPattern.FindAllStringSubmatch(string(raw), 40) {
		if safePath(m[1]) && !seen[m[1]] {
			paths = append(paths, m[1])
			seen[m[1]] = true
		}
	}
	return strings.TrimSpace(text), links, paths
}
