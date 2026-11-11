package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const fixtureProxyUser = "atlas-proxy-user"
const fixtureProxyPassword = "atlas-proxy-private-password"

func proxyTunnel(client, target net.Conn) {
	defer client.Close()
	defer target.Close()
	finished := make(chan struct{})
	go func() {
		_, _ = io.Copy(target, client)
		_ = target.Close()
		close(finished)
	}()
	_, _ = io.Copy(client, target)
	_ = client.Close()
	<-finished
}

func newProviderHTTPProxy(t *testing.T, target, upstreamTarget string, secure bool) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	calls := &atomic.Int32{}
	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte(fixtureProxyUser+":"+fixtureProxyPassword))
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "CONNECT" || r.Host != target || r.Header.Get("Proxy-Authorization") != auth {
			http.Error(w, "proxy authentication or destination rejected", 407)
			return
		}
		upstream, err := net.DialTimeout("tcp", upstreamTarget, 3*time.Second)
		if err != nil {
			http.Error(w, "fixture upstream unavailable", 502)
			return
		}
		client, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			upstream.Close()
			return
		}
		calls.Add(1)
		_, _ = io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n")
		proxyTunnel(client, upstream)
	})
	var server *httptest.Server
	if secure {
		server = httptest.NewTLSServer(handler)
	} else {
		server = httptest.NewServer(handler)
	}
	t.Cleanup(server.Close)
	return server, calls
}

func newProviderSOCKSProxy(t *testing.T, target string) (string, *atomic.Int32) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	calls := &atomic.Int32{}
	go func() {
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer connection.Close()
				_ = connection.SetDeadline(time.Now().Add(8 * time.Second))
				reader := bufio.NewReader(connection)
				header := make([]byte, 2)
				if _, err := io.ReadFull(reader, header); err != nil || header[0] != 5 {
					return
				}
				methods := make([]byte, int(header[1]))
				if _, err := io.ReadFull(reader, methods); err != nil {
					return
				}
				_, _ = connection.Write([]byte{5, 2})
				if _, err := io.ReadFull(reader, header); err != nil || header[0] != 1 {
					return
				}
				user := make([]byte, int(header[1]))
				if _, err := io.ReadFull(reader, user); err != nil {
					return
				}
				size, err := reader.ReadByte()
				if err != nil {
					return
				}
				password := make([]byte, int(size))
				if _, err := io.ReadFull(reader, password); err != nil || string(user) != fixtureProxyUser || string(password) != fixtureProxyPassword {
					_, _ = connection.Write([]byte{1, 1})
					return
				}
				_, _ = connection.Write([]byte{1, 0})
				command := make([]byte, 4)
				if _, err := io.ReadFull(reader, command); err != nil || command[0] != 5 || command[1] != 1 {
					return
				}
				var host string
				switch command[3] {
				case 1:
					ip := make([]byte, 4)
					if _, err := io.ReadFull(reader, ip); err != nil {
						return
					}
					host = net.IP(ip).String()
				case 4:
					ip := make([]byte, 16)
					if _, err := io.ReadFull(reader, ip); err != nil {
						return
					}
					host = net.IP(ip).String()
				case 3:
					size, err := reader.ReadByte()
					if err != nil {
						return
					}
					name := make([]byte, int(size))
					if _, err := io.ReadFull(reader, name); err != nil {
						return
					}
					host = string(name)
				default:
					return
				}
				port := make([]byte, 2)
				if _, err := io.ReadFull(reader, port); err != nil || net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port)))) != target {
					return
				}
				upstream, err := net.DialTimeout("tcp", target, 3*time.Second)
				if err != nil {
					return
				}
				calls.Add(1)
				_, _ = connection.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
				_ = connection.SetDeadline(time.Time{})
				proxyTunnel(connection, upstream)
			}()
		}
	}()
	return "socks5h://" + listener.Addr().String(), calls
}

func proxyFixtureURL(t *testing.T, raw string) string {
	t.Helper()
	target, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	target.User = url.UserPassword(fixtureProxyUser, fixtureProxyPassword)
	return target.String()
}

func saveProxyFixture(t *testing.T, s *Server, id, raw string, revision int64) {
	t.Helper()
	requireCode(t, req(t, s, "POST", "/api/v1/providers/proxy", map[string]any{
		"provider_id": id, "mode": "PROXY", "proxy_url": raw, "revision": revision,
	}), 200)
}

func TestProviderProxyRoutesTLSRequestsIndependentlyAndFailsClosed(t *testing.T) {
	for _, scheme := range []string{"http", "https", "socks5h"} {
		t.Run(scheme, func(t *testing.T) {
			s := testServer(t)
			f := newGatewayFixture(t, s, [2]string{"proxy-model-a", "proxy-model-b"})
			t.Cleanup(func() { s.closeProviderTransport("") })
			target, _ := url.Parse(f.URL)
			var proxyURL string
			var proxyCalls *atomic.Int32
			if scheme == "socks5h" {
				proxyURL, proxyCalls = newProviderSOCKSProxy(t, target.Host)
			} else {
				proxy, calls := newProviderHTTPProxy(t, target.Host, target.Host, scheme == "https")
				proxyURL, proxyCalls = proxy.URL, calls
			}
			s.distribution = true
			upstream := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", "/api/v1/gateway/upstreams", map[string]string{
				"name": "Proxy fixture", "base_url": f.URL + "/a/v1", "api_key": gatewayTestKeyA,
			}), 201)
			saveProxyFixture(t, s, upstream.ProviderID, proxyFixtureURL(t, proxyURL), 0)
			requireCode(t, req(t, s, "POST", "/api/v1/gateway/sync", upstream.syncRequest), 200)
			if proxyCalls.Load() == 0 {
				t.Fatal("catalog bypassed the selected proxy")
			}
			p, key, err := s.endpointAuth(context.Background(), upstream.ProviderID, upstream.AccountID, upstream.CredentialID)
			if err != nil {
				t.Fatal(err)
			}
			payload, _ := json.Marshal(gatewayTestChat("proxy-model-a", "hello", false))
			for _, path := range []string{"/api/status", "/api/usage/token/"} {
				s.closeProviderTransport(p.ID)
				before := proxyCalls.Load()
				token := ""
				if path != "/api/status" {
					token = key
				}
				if _, err := s.newApiGet(context.Background(), p, path, token); err != nil || proxyCalls.Load() <= before {
					t.Fatalf("station metadata or quota bypassed the proxy: path=%s error=%v", path, err)
				}
			}
			r, _ := http.NewRequest("POST", f.URL+"/a/v1/chat/completions", bytes.NewReader(payload))
			r.Header.Set("Content-Type", "application/json")
			cfg := s.config(r.Context(), p)
			if err := s.authorizeUpstream(r, p, key, cfg); err != nil {
				t.Fatal(err)
			}
			response, network, err := s.doProviderRequest(r, p, cfg, s.Client)
			if err != nil || response.StatusCode != 200 || network.Mode != "PROXY" || network.Revision != 1 || network.Route == "" {
				t.Fatalf("proxy inference failed: %v %v", network, err)
			}
			_, _ = io.Copy(io.Discard, response.Body)
			response.Body.Close()
			before := proxyCalls.Load()
			p.ID = "unconfigured-other-site"
			cfg.ProxyRequired = false
			r, _ = http.NewRequest("GET", f.URL+"/a/v1/models", nil)
			_ = s.authorizeUpstream(r, p, key, cfg)
			globalClient := *s.Client
			globalTransport := s.Client.Transport.(*http.Transport).Clone()
			configuredProxy, _ := url.Parse(proxyFixtureURL(t, proxyURL))
			globalTransport.Proxy = http.ProxyURL(configuredProxy)
			globalClient.Transport = globalTransport
			response, network, err = s.doProviderRequest(r, p, cfg, &globalClient)
			if err != nil || network.Mode != "DIRECT" || proxyCalls.Load() != before {
				t.Fatalf("direct provider inherited another provider's proxy: %v", err)
			}
			response.Body.Close()
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			unavailable := "http://" + listener.Addr().String()
			listener.Close()
			saveProxyFixture(t, s, upstream.ProviderID, unavailable, 1)
			beforeInference := f.a.Load()
			p.ID = upstream.ProviderID
			cfg.ProxyRequired = true
			r, _ = http.NewRequest("GET", f.URL+"/a/v1/models", nil)
			_ = s.authorizeUpstream(r, p, key, cfg)
			if response, _, err = s.doProviderRequest(r, p, cfg, s.Client); err == nil {
				response.Body.Close()
				t.Fatal("unavailable proxy silently fell back to direct")
			}
			if f.a.Load() != beforeInference {
				t.Fatal("proxy failure sent another inference")
			}
		})
	}
}

func TestProviderProxyVaultConflictExportAndMissingDeviceBinding(t *testing.T) {
	s := testServer(t)
	f := newGatewayFixture(t, s, [2]string{"model-a", "model-b"})
	s.distribution = true
	upstream := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", "/api/v1/gateway/upstreams", map[string]string{
		"name": "Proxy fixture", "base_url": f.URL + "/a/v1", "api_key": gatewayTestKeyA,
	}), 201)
	proxyURL := proxyFixtureURL(t, "http://127.0.0.1:7890")
	saveProxyFixture(t, s, upstream.ProviderID, proxyURL, 0)
	if _, err := s.Store.DB.Exec("ALTER TABLE provider_proxies DROP COLUMN mode; PRAGMA user_version=44"); err != nil {
		t.Fatal(err)
	}
	if err := migrate(s.Store.DB); err != nil {
		t.Fatal("existing encrypted proxy migration failed", err)
	}
	status := req(t, s, "GET", "/api/v1/providers/proxy?provider_id="+upstream.ProviderID, nil)
	requireCode(t, status, 200)
	if strings.Contains(status.Body.String(), fixtureProxyPassword) || strings.Contains(status.Body.String(), fixtureProxyUser) {
		t.Fatal("proxy status exposed authentication")
	}
	var encrypted string
	if err := s.Store.DB.QueryRow("SELECT encrypted_url FROM provider_proxies WHERE provider_id=?", upstream.ProviderID).Scan(&encrypted); err != nil || strings.Contains(encrypted, fixtureProxyPassword) {
		t.Fatal("proxy URL was not encrypted", err)
	}
	requireCode(t, req(t, s, "POST", "/api/v1/providers/proxy", map[string]any{
		"provider_id": upstream.ProviderID, "mode": "DIRECT", "revision": 0,
	}), 409)
	s.distribution = false
	provider, _ := s.provider(context.Background(), upstream.ProviderID)
	legacy := s.config(context.Background(), provider)
	legacy.ProxyRequired = false
	legacy.Notes = "updated by an existing configuration editor"
	requireCode(t, req(t, s, "POST", "/api/v1/providers/config", map[string]any{
		"provider_id": upstream.ProviderID, "config": legacy,
	}), 200)
	if !s.config(context.Background(), provider).ProxyRequired {
		t.Fatal("an existing configuration editor silently disabled the proxy")
	}
	export := req(t, s, "GET", "/api/v1/backup", nil)
	requireCode(t, export, 200)
	if strings.Contains(export.Body.String(), fixtureProxyPassword) || strings.Contains(export.Body.String(), "127.0.0.1:7890") || strings.Contains(export.Body.String(), encrypted) {
		t.Fatal("safe export included a device proxy or authentication")
	}
	var backup BackupDocument
	if err := json.Unmarshal(export.Body.Bytes(), &backup); err != nil {
		t.Fatal(err)
	}
	destination := testServer(t)
	requireCode(t, req(t, destination, "POST", "/api/v1/backup/restore", map[string]any{"confirm": true, "backup": backup}), 201)
	p, err := destination.provider(context.Background(), upstream.ProviderID)
	if err != nil || !destination.config(context.Background(), p).ProxyRequired {
		t.Fatal("restore lost the proxy requirement", err)
	}
	r, _ := http.NewRequest("GET", f.URL+"/a/v1/models", nil)
	if response, network, err := destination.doProviderRequest(r, p, destination.config(r.Context(), p), destination.Client); err == nil || response != nil || network.Mode != "PROXY" {
		t.Fatal("new device bypassed a required unconfigured proxy")
	}
	secrets, err := s.knownSecrets(context.Background())
	if err != nil || !slices.Contains(secrets, fixtureProxyPassword) {
		t.Fatal("proxy authentication was missing from secret filtering", err)
	}
}

func TestProviderProxyPublicConnectorRetainsPrivateDestinationProtection(t *testing.T) {
	s := testServer(t)
	f := newGatewayFixture(t, s, [2]string{"model-a", "model-b"})
	target, _ := url.Parse(f.URL)
	proxy, calls := newProviderHTTPProxy(t, target.Host, target.Host, false)
	s.distribution = true
	upstream := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", "/api/v1/gateway/upstreams", map[string]string{
		"name": "Proxy fixture", "base_url": f.URL + "/a/v1", "api_key": gatewayTestKeyA,
	}), 201)
	saveProxyFixture(t, s, upstream.ProviderID, proxyFixtureURL(t, proxy.URL), 0)
	p, err := s.provider(context.Background(), upstream.ProviderID)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.WithValue(context.Background(), publicAnonymousOnboardKey{}, true)
	r, _ := http.NewRequestWithContext(ctx, "GET", f.URL+"/api/status", nil)
	cfg := s.config(ctx, p)
	if response, _, err := s.doProviderRequest(r, p, cfg, f.Client()); err == nil || response != nil || calls.Load() != 0 {
		t.Fatal("public connector used the proxy to reach a private address")
	}
	t.Cleanup(func() { s.closeProviderTransport("") })
}

func TestProviderProxyPublicHTTPSIsPinnedInsideAuthenticatedProxy(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{
		SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IPAddresses: []net.IP{net.ParseIP("8.8.8.8")}, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage: x509.KeyUsageDigitalSignature, BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	upstream := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "8.8.8.8" || r.Header.Get("Authorization") != "" || r.Header.Get("Proxy-Authorization") != "" {
			t.Error("public request lost its Host or exposed private authentication")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":[]}`)
	}))
	upstream.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	upstream.StartTLS()
	t.Cleanup(upstream.Close)
	destination, _ := url.Parse(upstream.URL)
	for _, secure := range []bool{false, true} {
		t.Run(strconv.FormatBool(secure), func(t *testing.T) {
			s := testServer(t)
			t.Cleanup(func() { s.closeProviderTransport("") })
			s.distribution = true
			proxy, calls := newProviderHTTPProxy(t, "8.8.8.8:443", destination.Host, secure)
			base := upstream.Client()
			if secure {
				base.Transport.(*http.Transport).TLSClientConfig.RootCAs.AddCert(proxy.Certificate())
			}
			s.PublicClient = base
			connection := gatewayDecode[gatewayUpstream](t, req(t, s, "POST", "/api/v1/gateway/upstreams", map[string]string{
				"name": "Public proxy fixture", "base_url": "https://8.8.8.8",
			}), 201)
			saveProxyFixture(t, s, connection.ProviderID, proxyFixtureURL(t, proxy.URL), 0)
			p, err := s.provider(context.Background(), connection.ProviderID)
			if err != nil {
				t.Fatal(err)
			}
			ctx := context.WithValue(context.Background(), publicAnonymousOnboardKey{}, true)
			if _, status, err := s.getStructured(ctx, p, s.config(ctx, p), "https://8.8.8.8/models", "", 1024); err != nil || status != 200 || calls.Load() != 1 {
				t.Fatalf("pinned public TLS through proxy failed: status=%d calls=%d error=%v", status, calls.Load(), err)
			}
		})
	}
}

func TestProviderProxySyncKeepsDeviceBindingsSeparate(t *testing.T) {
	hub, peer := testServer(t), testServer(t)
	selection := seed(t, hub, "https://provider.example/v1")
	saveProxyFixture(t, hub, selection.ProviderID, "http://127.0.0.1:7890", 0)
	host := httptest.NewTLSServer(hub.Handler())
	defer host.Close()
	workspacePairForTest(t, hub, peer, host, false)
	workspaceRunForTest(t, peer)
	p, err := peer.provider(context.Background(), selection.ProviderID)
	if err != nil || !peer.config(context.Background(), p).ProxyRequired {
		t.Fatal("paired device lost the provider's proxy requirement", err)
	}
	var count int
	if err := peer.Store.DB.QueryRow("SELECT COUNT(*) FROM provider_proxies").Scan(&count); err != nil || count != 0 {
		t.Fatal("workspace sync copied a device proxy binding", err)
	}
	saveProxyFixture(t, peer, selection.ProviderID, "socks5://127.0.0.1:1080", 0)
	workspaceRunForTest(t, peer)
	local, err := hub.providerProxy(context.Background(), selection.ProviderID, true)
	if err != nil || local.URL != "http://127.0.0.1:7890" {
		t.Fatal("peer configuration replaced the hub's local proxy", err)
	}
	remote, err := peer.providerProxy(context.Background(), selection.ProviderID, true)
	if err != nil || remote.URL != "socks5://127.0.0.1:1080" {
		t.Fatal("peer proxy binding was not retained", err)
	}
	requireCode(t, req(t, peer, "POST", "/api/v1/providers/proxy", map[string]any{
		"provider_id": selection.ProviderID, "mode": "DIRECT", "revision": remote.Revision,
	}), 200)
	workspaceRunForTest(t, peer)
	local, err = hub.providerProxy(context.Background(), selection.ProviderID, true)
	if err != nil || local.Mode != "PROXY" {
		t.Fatal("peer direct mode disabled the hub proxy", err)
	}
	remote, err = peer.providerProxy(context.Background(), selection.ProviderID, true)
	if err != nil || remote.Mode != "DIRECT" {
		t.Fatal("peer did not retain its explicit direct route", err)
	}
}
