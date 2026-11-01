package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
)

// Privileged in-process callers reuse the real authenticated API validations.
// They cannot choose a path outside a tool's static allowlist. This writer is a
// bounded production adapter, never a test recorder or alternate data store.
type localResponse struct {
	header http.Header
	body   bytes.Buffer
	code   int
}

func (w *localResponse) Header() http.Header { return w.header }
func (w *localResponse) WriteHeader(code int) {
	if w.code == 0 {
		w.code = code
	}
}
func (w *localResponse) Write(b []byte) (int, error) {
	if w.body.Len()+len(b) > 2<<20 {
		return 0, errors.New("local response limit")
	}
	if w.code == 0 {
		w.code = 200
	}
	return w.body.Write(b)
}
func (s *Server) localAPI(ctx context.Context, method, path string, value any) (any, error) {
	raw, e := json.Marshal(value)
	if e != nil {
		return nil, e
	}
	request, e := http.NewRequestWithContext(ctx, method, "http://127.0.0.1/api/v1"+path, bytes.NewReader(raw))
	if e != nil {
		return nil, e
	}
	request.Header.Set("Authorization", "Bearer "+s.Token)
	if method != "GET" {
		request.Header.Set("Content-Type", "application/json")
	}
	writer := &localResponse{header: make(http.Header)}
	s.Handler().ServeHTTP(writer, request)
	if writer.code < 200 || writer.code >= 300 {
		return nil, errors.New(excerpt(writer.body.String(), 300))
	}
	var result any
	decoder := json.NewDecoder(bytes.NewReader(writer.body.Bytes()))
	decoder.UseNumber()
	if decoder.Decode(&result) != nil {
		return nil, errors.New("local response invalid")
	}
	return result, nil
}
