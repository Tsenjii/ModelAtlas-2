package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"time"
)

// Local-only synthetic provider. No production credentials or external HTTP.
func main() {
	dir := os.Args[1]
	if err := os.MkdirAll(dir, 0700); err != nil {
		panic(err)
	}
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	root := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "ModelAtlas ephemeral CI root " + serial.String()}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	rootDER, _ := x509.CreateCertificate(rand.Reader, root, root, &key.PublicKey, key)
	rootCert, _ := x509.ParseCertificate(rootDER)
	leafKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "localhost"}, DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, NotBefore: root.NotBefore, NotAfter: root.NotAfter, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leaf, rootCert, &leafKey.PublicKey, key)
	privateDER, _ := x509.MarshalECPrivateKey(leafKey)
	cert, _ := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER}), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: privateDER}))
	if err := os.WriteFile(filepath.Join(dir, "ca.pem"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: rootDER}), 0600); err != nil {
		panic(err)
	}
	var cancelled, redirected atomic.Int64
	spy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1); io.WriteString(w, `{}`) }))
	defer spy.Close()
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer native-fixture-provider-key" {
			http.Error(w, "fixture auth required", 401)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"data":[{"id":"native-fixture-chat"},{"id":"native-fixture-code"},{"id":"native-fixture-slow"}]}`)
	})
	mux.HandleFunc("/v1/chat/completions", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer native-fixture-provider-key" {
			http.Error(w, "fixture auth required", 401)
			return
		}
		var body struct {
			Model  string `json:"model"`
			Stream bool   `json:"stream"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			http.Error(w, "bad fixture body", 400)
			return
		}
		const reply = "這是原生 Mac 工作區。你的模型、對話和研究資料都保存在本機，並保留清楚的連線與來源。"
		if !body.Stream {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]any{"model": body.Model, "choices": []any{map[string]any{"message": map[string]string{"content": reply}, "finish_reason": "stop"}}, "usage": map[string]int{"prompt_tokens": 12, "completion_tokens": 48, "total_tokens": 60}})
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		f := w.(http.Flusher)
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
		f.Flush()
		if body.Model == "native-fixture-slow" {
			<-r.Context().Done()
			cancelled.Add(1)
			return
		}
		for _, rune := range reply {
			chunk, _ := json.Marshal(map[string]any{"model": body.Model, "choices": []any{map[string]any{"delta": map[string]string{"content": string(rune)}}}})
			fmt.Fprintf(w, "data: %s\n\n", chunk)
			f.Flush()
			time.Sleep(3 * time.Millisecond)
		}
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":48,\"total_tokens\":60}}\n\ndata: [DONE]\n\n")
		f.Flush()
	})
	secure := httptest.NewUnstartedServer(mux)
	secure.TLS = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
	secure.StartTLS()
	defer secure.Close()
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/redirect" {
			http.Redirect(w, r, spy.URL, http.StatusFound)
			return
		}
		if r.URL.Path == "/api/v1/truncated" {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "event: delta\ndata: {\"text\":\"partial\"}\n\n")
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"cancelled": cancelled.Load(), "redirected": redirected.Load()})
	}))
	defer plain.Close()
	data, _ := json.Marshal(map[string]string{"url": secure.URL, "plain": plain.URL})
	if err := os.WriteFile(filepath.Join(dir, "fixture.json"), data, 0600); err != nil {
		panic(err)
	}
	select {}
}
