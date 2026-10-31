package main

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"errors"
	"fmt"
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
	once       sync.Once
	raw        string
	compressed []byte
	etag       string
	revalidate bool
}

func (a *staticAsset) serve(w http.ResponseWriter, r *http.Request, contentType string) {
	a.once.Do(func() {
		var b bytes.Buffer
		z, _ := gzip.NewWriterLevel(&b, gzip.BestSpeed)
		_, _ = z.Write([]byte(a.raw))
		_ = z.Close()
		a.compressed = b.Bytes()
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
	if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		w.Header().Set("Content-Encoding", "gzip")
		_, _ = w.Write(a.compressed)
	} else {
		_, _ = w.Write([]byte(a.raw))
	}
}

var cssAsset = staticAsset{raw: appCSS, revalidate: true}
var jsAsset = staticAsset{raw: appJS, revalidate: true}

var errConversationLimit = errors.New("conversation limit; history retained, start a new conversation")
