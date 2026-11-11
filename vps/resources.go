package main

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
)

func initializeMemoryLimit() {
	if os.Getenv("GOMEMLIMIT") == "" {
		debug.SetMemoryLimit(192 << 20)
	}
}
func configuredFlights() int {
	n, e := strconv.Atoi(os.Getenv("MODELATLAS_MAX_FLIGHTS"))
	if e != nil || n < 2 || n > 4 {
		return 4
	}
	return n
}
func pageRange(r *http.Request, defaultLimit int) (int, int) {
	limit, offset := defaultLimit, 0
	if n, e := strconv.Atoi(r.URL.Query().Get("limit")); e == nil && n > 0 {
		limit = min(n, 200)
	}
	if n, e := strconv.Atoi(r.URL.Query().Get("offset")); e == nil && n > 0 {
		offset = min(n, 10000000)
	}
	return limit, offset
}

type staticAsset struct {
	etagOnce   sync.Once
	gzipOnce   sync.Once
	raw        string
	compressed []byte
	etag       string
	revalidate bool
}

func compressAsset(level int, parts ...string) []byte {
	var b bytes.Buffer
	z, _ := gzip.NewWriterLevel(&b, level)
	for _, part := range parts {
		_, _ = io.WriteString(z, part)
	}
	_ = z.Close()
	return b.Bytes()
}

func acceptsAssetGzip(header string) bool {
	for item := range strings.SplitSeq(header, ",") {
		coding, parameters, _ := strings.Cut(item, ";")
		if !strings.EqualFold(strings.TrimSpace(coding), "gzip") {
			continue
		}
		quality := 1.0
		for parameter := range strings.SplitSeq(parameters, ";") {
			key, value, ok := strings.Cut(parameter, "=")
			if ok && strings.EqualFold(strings.TrimSpace(key), "q") {
				quality, _ = strconv.ParseFloat(strings.TrimSpace(value), 64)
			}
		}
		if quality > 0 && quality <= 1 {
			return true
		}
	}
	return false
}

// HTML shells contain static markup. Keep authorization and cache headers
// from the caller and assemble a compressed copy only when a client wants it.
func staticHTML(parts ...string) http.HandlerFunc {
	gz := sync.OnceValue(func() []byte { return compressAsset(gzip.DefaultCompression, parts...) })
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Add("Vary", "Accept-Encoding")
		if acceptsAssetGzip(r.Header.Get("Accept-Encoding")) {
			w.Header().Set("Content-Encoding", "gzip")
			_, _ = w.Write(gz())
			return
		}
		for _, part := range parts {
			_, _ = io.WriteString(w, part)
		}
	}
}

func (a *staticAsset) serve(w http.ResponseWriter, r *http.Request, contentType string) {
	a.etagOnce.Do(func() {
		a.etag = fmt.Sprintf("\"%x\"", sha256.Sum256([]byte(a.raw)))
	})
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	if a.revalidate {
		// The HTML and application code must come from the same deployment.
		// Revalidation keeps the conditional/gzip cache without stale code after
		// a binary upgrade; immutable artwork retains its existing cache policy.
		w.Header().Set("Cache-Control", "private, no-cache")
	}
	w.Header().Set("ETag", a.etag)
	w.Header().Set("Vary", "Accept-Encoding")
	if r.Header.Get("If-None-Match") == a.etag {
		w.WriteHeader(304)
		return
	}
	if acceptsAssetGzip(r.Header.Get("Accept-Encoding")) {
		a.gzipOnce.Do(func() { a.compressed = compressAsset(gzip.BestSpeed, a.raw) })
		w.Header().Set("Content-Encoding", "gzip")
		_, _ = w.Write(a.compressed)
	} else {
		_, _ = io.WriteString(w, a.raw)
	}
}

var cssAsset = staticAsset{raw: appCSS, revalidate: true}
var jsAsset = staticAsset{raw: appJS + "\n" + metricPlotJS, revalidate: true}

var errConversationLimit = errors.New("conversation limit; history retained, start a new conversation")
