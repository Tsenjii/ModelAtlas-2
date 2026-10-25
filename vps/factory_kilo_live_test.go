package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type factoryKiloLiveTransport struct{ calls atomic.Int32 }

func (t *factoryKiloLiveTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Scheme != "https" || r.URL.Host != "ai-gateway.kilo.ai" || !oneOf(r.URL.Path, "/api/v1/models", "/api/v1/chat/completions") || r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || t.calls.Add(1) > 2 {
		return nil, fmt.Errorf("factory live fixture only permits two exact anonymous requests")
	}
	return http.DefaultTransport.RoundTrip(r)
}

// Explicit opt-in, disposable state and synthetic text only. A provider
// success proves current connectivity, not identity or recurring eligibility.
func TestLiveFactoryKiloDefaultProfileAndConversation(t *testing.T) {
	if os.Getenv("MODELATLAS_LIVE_FACTORY_KILO") != "1" {
		t.Skip("explicit live factory Kilo opt-in")
	}
	s := testServer(t)
	transport := &factoryKiloLiveTransport{}
	client := &http.Client{Transport: transport, Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	s.Client = client
	s.PublicClient = client
	if _, e := s.installKiloFactory(context.Background(), true); e != nil {
		t.Fatal(e)
	}
	session := factoryConversation(t, s)
	ctx, stop := context.WithTimeout(context.Background(), 100*time.Second)
	defer stop()
	result, code := s.execute(ctx, inferRequest{SessionID: session.ID, Prompt: "Synthetic connectivity check: reply exactly FACTORY_KILO_OK in your final answer. No analysis needed.", MaxTokens: 1024}, "LIVE_FACTORY_KILO", func(string, any) error { return nil })
	if code != 0 || result.Outcome != "SUCCESS" || !strings.Contains(result.Text, "FACTORY_KILO_OK") {
		t.Fatalf("actual default factory connection failed: code=%d outcome=%s requests=%d", code, result.Outcome, transport.calls.Load())
	}
	if transport.calls.Load() != 2 {
		t.Fatal("unexpected anonymous wire workload", transport.calls.Load())
	}
	assertSQLInt(t, s, 1, "SELECT COUNT(*) FROM telemetry WHERE provider_id=? AND outcome='SUCCESS'", kiloFactoryID)
	t.Log("PASS: real official anonymous catalog price gate -> one synthetic default-profile inference -> durable common-engine telemetry; 2 total HTTP, no Key, no automatic signup or paid fallback. Identity/recurring eligibility unproven.")
}
