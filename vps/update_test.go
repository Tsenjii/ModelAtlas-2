package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"testing"
	"time"
)

const fixtureReleaseTag = "v0.8.6-preview.1"
const fixtureReleaseCommit = "a8df4417a1ac615d81292f91fc6d2a6bdca00261"

type updateFixtureTransport func(*http.Request) (*http.Response, error)

func (f updateFixtureTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func fixtureUpdateClient(t *testing.T, archive []byte) *http.Client {
	t.Helper()
	if archive == nil {
		archive = []byte("version check fixture")
	}
	name := updateBundleName() + ".tar.gz"
	hash := fmt.Sprintf("%x", sha256.Sum256(archive))
	assets := map[string][]byte{name: archive, name + ".sha256": []byte(hash + "  " + name + "\n")}
	manifestAssets := map[string]any{}
	for path, data := range assets {
		manifestAssets[path] = map[string]any{"sha256": fmt.Sprintf("%x", sha256.Sum256(data)), "size": len(data)}
	}
	raw, err := json.Marshal(map[string]any{
		"schema": 1, "repository": updateRepository, "tag": fixtureReleaseTag,
		"commit": fixtureReleaseCommit, "prerelease": true, "assets": manifestAssets,
	})
	if err != nil {
		t.Fatal(err)
	}
	assets["RELEASE-MANIFEST.json"] = raw
	release := updateRelease{Tag: fixtureReleaseTag, Prerelease: true}
	for path, data := range assets {
		release.Assets = append(release.Assets, updateAsset{path, int64(len(data)), fmt.Sprintf("sha256:%x", sha256.Sum256(data))})
	}
	listing, err := json.Marshal([]updateRelease{release})
	if err != nil {
		t.Fatal(err)
	}
	return &http.Client{Transport: updateFixtureTransport(func(r *http.Request) (*http.Response, error) {
		var body []byte
		switch {
		case r.URL.String() == "https://api.github.com/repos/"+updateRepository+"/releases?per_page=20":
			body = listing
		case r.URL.Host == "github.com":
			prefix := "/" + updateRepository + "/releases/download/" + fixtureReleaseTag + "/"
			name, ok := strings.CutPrefix(r.URL.Path, prefix)
			if ok {
				body = assets[name]
			}
		}
		if body == nil {
			return nil, fmt.Errorf("unexpected release URL %s", r.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(body))}, nil
	})}
}

func fixtureUpdateArchive(t *testing.T, version binaryVersion, executable []byte) []byte {
	t.Helper()
	raw, err := json.Marshal(version)
	if err != nil {
		t.Fatal(err)
	}
	release, err := json.Marshal(map[string]any{
		"schema": 1, "version": "0.8.6", "tag": fixtureReleaseTag,
		"commit": version.Commit, "os": version.OS, "arch": version.Arch, "database_schema": version.Schema,
	})
	if err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{"VERSION.json": raw, "RELEASE.json": release, "bin/" + updateBundleName(): executable}
	for _, name := range []string{"deploy/install.sh", "deploy/update.sh", "deploy/manage.sh", "deploy/modelatlas-vps.service", "UPDATE.md", "README.md", "PLATFORM_DEPLOY.md"} {
		files[name] = []byte(name)
	}
	var checks strings.Builder
	for name, data := range files {
		fmt.Fprintf(&checks, "%x  %s\n", sha256.Sum256(data), name)
	}
	files["SHA256SUMS.txt"] = []byte(checks.String())
	var compressed bytes.Buffer
	zip := gzip.NewWriter(&compressed)
	archive := tar.NewWriter(zip)
	for name, data := range files {
		h := &tar.Header{Name: updateBundleName() + "/" + name, Mode: 0644, Size: int64(len(data))}
		if err := archive.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		if _, err := archive.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := zip.Close(); err != nil {
		t.Fatal(err)
	}
	return compressed.Bytes()
}

func checkGatewayUpdateWorkflow(t *testing.T, s *Server) {
	t.Helper()
	if runtime.GOOS != "linux" || !oneOf(runtime.GOARCH, "amd64", "arm64") {
		return
	}
	version := binaryVersion{fixtureReleaseCommit, "2026-11-09T08:00:00Z", "linux", runtime.GOARCH, schemaVersion}
	archive := fixtureUpdateArchive(t, version, []byte("archive validation fixture"))
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "releases"), 0700); err != nil {
		t.Fatal(err)
	}
	ready := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s.updates = &webUpdater{root: root, tag: "v0.8.5-preview.1", ctx: ctx, stop: func() { close(ready) }, client: fixtureUpdateClient(t, archive)}
	defer func() { s.updates = nil }()
	const prefix = "/api/v1/update/"
	requireCode(t, req(t, s, "POST", prefix+"install", map[string]string{"tag": fixtureReleaseTag}), 409)
	view := gatewayDecode[updateView](t, req(t, s, "POST", prefix+"check", map[string]string{}), 200)
	if !view.Enabled || view.Available != fixtureReleaseTag || view.Current != "v0.8.5-preview.1" {
		t.Fatalf("incorrect update selection: %+v", view)
	}
	requireCode(t, req(t, s, "POST", prefix+"install", map[string]string{"tag": "https://foreign.example/a"}), 409)
	requireCode(t, req(t, s, "POST", prefix+"install", map[string]string{"tag": fixtureReleaseTag}), 202)
	requireCode(t, req(t, s, "POST", prefix+"install", map[string]string{"tag": fixtureReleaseTag}), 409)
	select {
	case <-ready:
	case <-time.After(5 * time.Second):
		t.Fatal("verified update was not staged")
	}
	state, err := readUpdateJournal(root)
	if err != nil || state.Trial == nil || state.Trial.Version != version || state.Current != nil || state.Preparing {
		t.Fatalf("incorrect staged update journal: %+v %v", state, err)
	}
	binary := updateBinary(root, *state.Trial)
	if info, err := os.Stat(binary); err != nil || info.Mode().Perm() != 0700 {
		t.Fatalf("staged release did not include a private executable: %v", err)
	}
	if _, err := stageUpdateRelease(ctx, fixtureUpdateClient(t, []byte("not an archive")), root, *s.updates.selection); err == nil {
		t.Fatal("release asset substitution passed the digest check")
	}
}

// Compile a real candidate that adds one schema migration. Failure injection is
// confined to that test build, after it has opened and migrated the live DB.
func checkSupervisedPortableUpdate(t *testing.T, bootstrap, directory string, config deploymentConfig, checkData func(int, string)) {
	t.Helper()
	if runtime.GOOS != "linux" || !oneOf(runtime.GOARCH, "amd64", "arm64") {
		return
	}
	root := t.TempDir()
	nextSchema := schemaVersion + 1
	overlayFiles := make(map[string]string)
	for _, path := range []string{"migrations.go", "server.go"} {
		absolute, err := filepath.Abs(path)
		if err != nil {
			t.Fatal(err)
		}
		raw, err := os.ReadFile(absolute)
		if err != nil {
			t.Fatal(err)
		}
		original := string(raw)
		var source string
		switch path {
		case "migrations.go":
			source = strings.Replace(original, fmt.Sprintf("const schemaVersion = %d", schemaVersion), fmt.Sprintf("const schemaVersion = %d", nextSchema), 1)
			source = strings.Replace(source, "\treturn tx.Commit()", fmt.Sprintf(
				"\tif _, err = tx.Exec(\"PRAGMA user_version=%d\"); err != nil { return err }\n\treturn tx.Commit()", nextSchema), 1)
		case "server.go":
			source = strings.Replace(original, "\tdefer store.Close()\n\tif ctx.Err()", `	defer store.Close()
	if marker := os.Getenv("ATLAS_TEST_TRIAL_FAILURE"); marker != "" && config.updateTrialGate != "" {
		var migrated int
		if err := store.DB.QueryRow("PRAGMA user_version").Scan(&migrated); err != nil {
			return err
		}
		if err := os.WriteFile(marker, []byte(fmt.Sprint(migrated)), 0600); err != nil {
			return err
		}
		return errors.New("intentional trial failure after live migration")
	}
	if ctx.Err()`, 1)
		}
		if source == original {
			t.Fatalf("cannot inject the portable update fixture into %s", path)
		}
		replacement := filepath.Join(root, path)
		if err := os.WriteFile(replacement, []byte(source), 0600); err != nil {
			t.Fatal(err)
		}
		overlayFiles[absolute] = replacement
	}
	overlay, err := json.Marshal(map[string]any{"Replace": overlayFiles})
	if err != nil {
		t.Fatal(err)
	}
	overlayFile := filepath.Join(root, "overlay.json")
	if err := os.WriteFile(overlayFile, overlay, 0600); err != nil {
		t.Fatal(err)
	}
	binary := filepath.Join(root, "candidate")
	version := binaryVersion{fixtureReleaseCommit, "2026-11-09T08:00:00Z", "linux", runtime.GOARCH, nextSchema}
	build := exec.Command("go", "build", "-mod=readonly", "-p=1", "-overlay", overlayFile, "-trimpath",
		"-ldflags", "-s -w -X main.buildCommit="+version.Commit+" -X main.buildTime="+version.BuiltAt+" -X main.buildReleaseTag="+fixtureReleaseTag,
		"-o", binary, ".")
	build.Env = append(os.Environ(), "CGO_ENABLED=0")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build trial candidate: %v\n%s", err, out)
	}
	executable, err := os.ReadFile(binary)
	if err != nil {
		t.Fatal(err)
	}
	archive := fixtureUpdateArchive(t, version, executable)
	if err := os.Mkdir(filepath.Join(root, "releases"), 0700); err != nil {
		t.Fatal(err)
	}
	client := fixtureUpdateClient(t, archive)
	release, err := selectUpdate(context.Background(), client, "v0.8.5-preview.1")
	if err != nil || release == nil {
		t.Fatalf("select test release: %v", err)
	}
	trial, err := stageUpdateRelease(context.Background(), client, root, *release)
	if err != nil {
		t.Fatal(err)
	}
	var base binaryVersion
	if out, err := exec.Command(bootstrap, "version").Output(); err != nil || json.Unmarshal(out, &base) != nil || base.Schema != schemaVersion {
		t.Fatal("invalid bootstrap version")
	}

	boot := func(edition, marker, result string, expected binaryVersion, queue bool) {
		t.Helper()
		if queue {
			if err := saveUpdateJournal(root, updateJournal{Trial: &trial}); err != nil {
				t.Fatal(err)
			}
		}
		log, err := os.CreateTemp(root, "supervisor-*.log")
		if err != nil {
			t.Fatal(err)
		}
		defer log.Close()
		cmd := exec.Command(bootstrap, "portable", "run", "--directory", directory, "--update-root", root, "--edition", edition)
		cmd.Env = append(os.Environ(), "MODELATLAS_FACTORY_DEFAULTS=0", "ATLAS_TEST_TRIAL_FAILURE="+marker)
		cmd.Stdout, cmd.Stderr = log, log
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		done := make(chan struct{})
		var exit error
		go func() { exit = cmd.Wait(); close(done) }()
		defer func() {
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case <-done:
				if exit != nil {
					out, _ := os.ReadFile(log.Name())
					t.Errorf("portable supervisor stopped with %v\n%s", exit, out)
				}
			case <-time.After(20 * time.Second):
				_ = cmd.Process.Kill()
				<-done
				t.Error("portable supervisor did not stop")
			}
		}()
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		ticker := time.NewTicker(40 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				t.Fatal("portable supervisor exited before authenticated readiness")
			case <-ctx.Done():
				out, _ := os.ReadFile(log.Name())
				t.Fatalf("portable supervisor did not reach %s: %s", result, out)
			case <-ticker.C:
				state, err := readUpdateJournal(root)
				_, gateErr := os.Stat(filepath.Join(root, ".trial-gate"))
				if err != nil || state.Result != result || state.Trial != nil || state.Preparing || !os.IsNotExist(gateErr) ||
					verifyServiceVersion(ctx, config, expected, false) != nil {
					continue
				}
				if result == "updated" && (state.Current == nil || state.Current.Version != expected) {
					t.Fatal("verified version was not promoted")
				}
				checkData(expected.Schema, edition)
				return
			}
		}
	}

	marker := filepath.Join(root, "migrated")
	boot("workspace", marker, "rolled_back", base, true)
	if raw, err := os.ReadFile(marker); err != nil || string(raw) != fmt.Sprint(nextSchema) {
		t.Fatal("candidate failed without first migrating the real database")
	}
	boot("workspace", "", "updated", version, true)
	boot("lite", "", "updated", version, false)
}
