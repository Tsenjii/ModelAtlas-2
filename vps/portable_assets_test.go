package main

import (
	"bytes"
	"compress/gzip"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPortableAssetsRevalidateAcrossBinaryUpgrade(t *testing.T) {
	for _, asset := range []*staticAsset{&cssAsset, &jsAsset} {
		r := httptest.NewRequest("GET", "/asset", nil)
		r.Header.Set("Accept-Encoding", "gzip")
		w := httptest.NewRecorder()
		asset.serve(w, r, "text/plain")
		if w.Code != 200 || w.Header().Get("Cache-Control") != "private, no-cache" || w.Header().Get("Content-Encoding") != "gzip" || w.Header().Get("ETag") == "" {
			t.Fatal("application code may be stale after an upgrade", w.Header())
		}
		z, err := gzip.NewReader(w.Body)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(z)
		z.Close()
		if err != nil || string(body) != asset.raw {
			t.Fatal("compressed application code changed", err)
		}
		r.Header.Set("If-None-Match", w.Header().Get("ETag"))
		cached := httptest.NewRecorder()
		asset.serve(cached, r, "text/plain")
		if cached.Code != 304 || cached.Body.Len() != 0 {
			t.Fatal("unchanged code lost its conditional cache")
		}
		upgraded := staticAsset{raw: asset.raw + "\n", revalidate: true}
		r.Header.Set("Accept-Encoding", "gzip;q=0, identity")
		fresh := httptest.NewRecorder()
		upgraded.serve(fresh, r, "text/plain")
		if fresh.Code != 200 || fresh.Header().Get("ETag") == cached.Header().Get("ETag") {
			t.Fatal("old binary validator reused after a code change")
		}
		if fresh.Header().Get("Content-Encoding") != "" || fresh.Body.String() != upgraded.raw || len(upgraded.compressed) != 0 {
			t.Fatal("identity request changed or eagerly compressed application code")
		}
	}
	for _, tool := range []string{"models", "providers", "requests", "data", "prices", "verification"} {
		r := httptest.NewRequest("GET", "/tools/"+tool, nil)
		r.SetPathValue("tool", tool)
		r.Header.Set("Accept-Encoding", "gzip;q=0, identity")
		plain := httptest.NewRecorder()
		security(plain)
		gatewayToolPage(plain, r)
		if plain.Code != 200 || plain.Header().Get("Content-Encoding") != "" ||
			!strings.Contains(plain.Body.String(), `data-lite-tool="`+tool+`"`) {
			t.Fatal("tool page lost its identity markup", tool, plain.Code)
		}
		r.Header.Set("Accept-Encoding", "gzip")
		compressed := httptest.NewRecorder()
		security(compressed)
		gatewayToolPage(compressed, r)
		if compressed.Code != 200 || compressed.Header().Get("Content-Encoding") != "gzip" ||
			compressed.Header().Get("Vary") != "Accept-Encoding" || compressed.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("tool page lost its response policy", tool, compressed.Header())
		}
		z, err := gzip.NewReader(compressed.Body)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(z)
		_ = z.Close()
		if err != nil || !bytes.Equal(body, plain.Body.Bytes()) {
			t.Fatal("tool page changed when compressed", tool, err)
		}
	}
}
