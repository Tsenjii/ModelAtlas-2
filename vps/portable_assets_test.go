package main

import (
	"compress/gzip"
	"io"
	"net/http/httptest"
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
		fresh := httptest.NewRecorder()
		upgraded.serve(fresh, r, "text/plain")
		if fresh.Code != 200 || fresh.Header().Get("ETag") == cached.Header().Get("ETag") {
			t.Fatal("old binary validator reused after a code change")
		}
	}
}
