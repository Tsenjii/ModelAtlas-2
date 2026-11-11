package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

type providerNetwork struct {
	Mode     string `json:"mode"`
	Revision int64  `json:"revision,omitempty"`
	Route    string `json:"route,omitempty"`
}

type providerTransportKey struct {
	provider string
	public   bool
}

type providerTransport struct {
	source     *http.Transport
	encrypted  string
	publicHost string
	transport  *http.Transport
}

type providerHTTPState struct {
	mu         sync.Mutex
	transports map[providerTransportKey]providerTransport
}

type providerProxyTransport struct {
	transport  *http.Transport
	publicHost string
}

type proxyRequestError struct{ timeout bool }

func (e *proxyRequestError) Error() string   { return "provider proxy request failed" }
func (e *proxyRequestError) Timeout() bool   { return e.timeout }
func (e *proxyRequestError) Temporary() bool { return false }

func (t *providerProxyTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	if t.publicHost != "" {
		if request.URL.Scheme != "https" || !strings.EqualFold(request.URL.Host, t.publicHost) {
			return nil, &proxyRequestError{}
		}
		port := request.URL.Port()
		if port == "" {
			port = "443"
		}
		address, err := resolvePublicAddress(request.Context(), net.JoinHostPort(request.URL.Hostname(), port))
		if err != nil {
			return nil, &proxyRequestError{}
		}
		original := request
		request = request.Clone(request.Context())
		target := *original.URL
		target.Host = address
		request.URL, request.Host = &target, original.URL.Host
	}
	response, err := t.transport.RoundTrip(request)
	if err != nil {
		var network net.Error
		return nil, &proxyRequestError{timeout: errors.Is(err, context.DeadlineExceeded) || errors.As(err, &network) && network.Timeout()}
	}
	return response, nil
}

func (s *Server) closeProviderTransport(provider string) {
	s.providerHTTP.mu.Lock()
	defer s.providerHTTP.mu.Unlock()
	for key, cached := range s.providerHTTP.transports {
		if provider == "" || key.provider == provider {
			cached.transport.CloseIdleConnections()
			delete(s.providerHTTP.transports, key)
		}
	}
}

func (s *Server) providerRoundTripper(base *http.Client, p Provider, proxy providerProxyStatus, public bool) (http.RoundTripper, error) {
	source := base.Transport
	if source == nil {
		source = http.DefaultTransport
	}
	if p.ID == "" && proxy.Mode == "DIRECT" {
		return source, nil
	}
	transport, standard := source.(*http.Transport)
	if !standard {
		if proxy.Mode == "PROXY" {
			return nil, errors.New("provider proxy transport unavailable")
		}
		return source, nil
	}
	if proxy.Mode == "DIRECT" && transport.Proxy == nil {
		return transport, nil
	}
	key := providerTransportKey{}
	publicHost := ""
	if proxy.Mode == "PROXY" {
		key = providerTransportKey{provider: p.ID, public: public}
		if public {
			origin, err := url.Parse(p.BaseURL)
			if err != nil {
				return nil, err
			}
			publicHost = origin.Host
		}
	}
	s.providerHTTP.mu.Lock()
	defer s.providerHTTP.mu.Unlock()
	if s.providerHTTP.transports == nil {
		s.providerHTTP.transports = map[providerTransportKey]providerTransport{}
	}
	cached, exists := s.providerHTTP.transports[key]
	if !exists || cached.source != transport || cached.encrypted != proxy.encrypted || cached.publicHost != publicHost {
		if exists {
			cached.transport.CloseIdleConnections()
		}
		next := transport.Clone()
		next.Proxy = nil
		if proxy.Mode == "PROXY" {
			next.Proxy = http.ProxyURL(proxy.target)
			if public {
				origin, _ := url.Parse(p.BaseURL)
				dial := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
				next.DialContext = dial.DialContext
				tlsConfig := next.TLSClientConfig
				if tlsConfig == nil {
					tlsConfig = &tls.Config{}
				}
				next.TLSClientConfig = tlsConfig.Clone()
				next.TLSClientConfig.ServerName = origin.Hostname()
				// Authenticate an HTTPS proxy against its own hostname before
				// the transport verifies the pinned destination inside CONNECT.
				if proxy.target.Scheme == "https" {
					proxyTLS := tlsConfig.Clone()
					proxyTLS.ServerName = proxy.target.Hostname()
					next.DialTLS, next.DialTLSContext = nil, func(ctx context.Context, network, address string) (net.Conn, error) {
						return (&tls.Dialer{NetDialer: dial, Config: proxyTLS}).DialContext(ctx, network, address)
					}
				}
			}
		}
		cached = providerTransport{source: transport, encrypted: proxy.encrypted, publicHost: publicHost, transport: next}
		s.providerHTTP.transports[key] = cached
	}
	if proxy.Mode == "DIRECT" {
		return cached.transport, nil
	}
	return &providerProxyTransport{transport: cached.transport, publicHost: publicHost}, nil
}

func (s *Server) doProviderRequest(request *http.Request, p Provider, cfg ProviderConfig, base *http.Client) (*http.Response, providerNetwork, error) {
	network := providerNetwork{Mode: "DIRECT"}
	status := providerProxyStatus{Mode: "DIRECT"}
	var err error
	if cfg.ProxyRequired {
		network.Mode = "PROXY"
		status, err = s.providerProxy(request.Context(), p.ID, true)
		if err != nil || !status.Configured || status.target == nil {
			return nil, network, &providerReadError{Code: "PROXY_NOT_CONFIGURED"}
		}
		network.Revision = status.Revision
		hash := sha256.Sum256([]byte(status.encrypted))
		network.Route = hex.EncodeToString(hash[:8])
	}
	if base == nil {
		base = http.DefaultClient
	}
	client := *base
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	public, _ := request.Context().Value(publicAnonymousOnboardKey{}).(bool)
	client.Transport, err = s.providerRoundTripper(base, p, status, public)
	if err != nil {
		return nil, network, err
	}
	response, err := client.Do(request)
	return response, network, err
}
