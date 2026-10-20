package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Opt-in CI fixture only. The Android client talks to the unchanged production
// Server.Handler and SQLite store, with a real trusted TLS certificate. No
// provider requests, live API quota, product bypass route or fixture key ships.
func TestWorkspaceAndroidFixture(t *testing.T) {
	out := os.Getenv("MODELATLAS_NATIVE_FIXTURE_OUT")
	if out == "" {
		t.Skip("Android cross-runtime fixture is started by device CI")
	}
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	serial, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if e != nil {
		t.Fatal(e)
	}
	cert := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "ModelAtlas CI workspace fixture"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true, IsCA: true, DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("10.0.2.2")}}
	der, e := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	rawKey, e := x509.MarshalECPrivateKey(key)
	if e != nil {
		t.Fatal(e)
	}
	pair, e := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: rawKey}))
	if e != nil {
		t.Fatal(e)
	}
	s := testServer(t)
	scope := seed(t, s, "https://provider.example/v1")
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	if _, e = s.Store.DB.Exec("INSERT INTO sessions(id,provider_id,account_id,credential_id,model_id,group_id,endpoint,title,created_at) VALUES('native-history-session','p','a','c','m','','CHAT_COMPLETIONS','Retained native history',?)", now()); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Store.DB.Exec("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('native-history-message','native-history-session','assistant',?,?)", strings.Repeat("r", 4<<20), now()); e != nil {
		t.Fatal(e)
	}
	listener, e := net.Listen("tcp", "0.0.0.0:17888")
	if e != nil {
		t.Fatal(e)
	}
	server := &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 10 * time.Second}
	go server.Serve(tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{pair}, MinVersion: tls.VersionTLS12}))
	defer server.Close()
	metadata := map[string]any{"url": "https://10.0.2.2:17888", "certificate": base64.StdEncoding.EncodeToString(der), "admin": s.Token}
	if e = os.WriteFile(out, []byte(workspaceJSON(metadata)), 0600); e != nil {
		t.Fatal(e)
	}
	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(done)
	<-done
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
}
