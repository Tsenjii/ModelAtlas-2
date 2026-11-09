package main

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"
)

const (
	updateRepository    = "Tsenjii/ModelAtlas-2"
	updateArchiveLimit  = 64 << 20
	updateExpandedLimit = 128 << 20
	updateManifestLimit = 256 << 10
)

var buildReleaseTag string
var updateTagPattern = regexp.MustCompile(`^v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})(?:-preview\.([1-9][0-9]{0,5}))?$`)

func updateTagParts(tag string) []int {
	m := updateTagPattern.FindStringSubmatch(tag)
	if m == nil {
		return nil
	}
	parts := make([]int, 4)
	for i := range parts {
		parts[i], _ = strconv.Atoi(m[i+1])
	}
	if m[4] == "" {
		parts[3] = 1_000_000
	}
	return parts
}

func updateCommit(commit string) bool {
	_, err := hex.DecodeString(commit)
	return len(commit) == 40 && err == nil && commit == strings.ToLower(commit)
}

type updateAsset struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Digest string `json:"digest"`
}

type updateRelease struct {
	Tag        string        `json:"tag_name"`
	Draft      bool          `json:"draft"`
	Prerelease bool          `json:"prerelease"`
	Assets     []updateAsset `json:"assets"`
}

func (r updateRelease) asset(name string, limit int64) (updateAsset, bool) {
	var selected updateAsset
	for _, asset := range r.Assets {
		if asset.Name != name {
			continue
		}
		digest := strings.TrimPrefix(asset.Digest, "sha256:")
		_, err := hex.DecodeString(digest)
		if selected.Name != "" || asset.Size <= 0 || asset.Size > limit || !strings.HasPrefix(asset.Digest, "sha256:") || len(digest) != 64 || err != nil {
			return updateAsset{}, false
		}
		selected = asset
	}
	return selected, selected.Name != ""
}

func updateBundleName() string {
	return "modelatlas-vps-linux-" + runtime.GOARCH
}

func updateReleaseClient() *http.Client {
	return &http.Client{
		Timeout: 90 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 4 || req.URL.Scheme != "https" || req.URL.User != nil || req.URL.Port() != "" ||
				!oneOf(req.URL.Hostname(), "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") {
				return errors.New("unexpected release download redirect")
			}
			return nil
		},
	}
}

func updateGET(ctx context.Context, client *http.Client, target string) (io.ReadCloser, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "ModelAtlas-Updates")
	res, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	if res.StatusCode != http.StatusOK {
		res.Body.Close()
		return nil, errors.New("release service did not return the requested file")
	}
	return res.Body, nil
}

func selectUpdate(ctx context.Context, client *http.Client, currentTag string) (*updateRelease, error) {
	current := updateTagParts(currentTag)
	if current == nil {
		return nil, errors.New("this build has no release channel version")
	}
	body, err := updateGET(ctx, client, "https://api.github.com/repos/"+updateRepository+"/releases?per_page=20")
	if err != nil {
		return nil, err
	}
	defer body.Close()
	var releases []updateRelease
	if err := json.NewDecoder(io.LimitReader(body, 2<<20)).Decode(&releases); err != nil {
		return nil, err
	}
	var selected *updateRelease
	best := current
	for _, r := range releases {
		parts := updateTagParts(r.Tag)
		if r.Draft || parts == nil || slices.Compare(parts, best) <= 0 ||
			r.Prerelease != strings.Contains(r.Tag, "-preview.") ||
			(r.Prerelease && !strings.Contains(currentTag, "-preview.")) {
			continue
		}
		if _, ok := r.asset("RELEASE-MANIFEST.json", updateManifestLimit); !ok {
			continue
		}
		archive := updateBundleName() + ".tar.gz"
		if _, ok := r.asset(archive, updateArchiveLimit); !ok {
			continue
		}
		if _, ok := r.asset(archive+".sha256", 1024); !ok {
			continue
		}
		selected, best = &r, parts
	}
	return selected, nil
}

func downloadUpdateAsset(ctx context.Context, client *http.Client, tag string, asset updateAsset, out io.Writer) error {
	target := "https://github.com/" + updateRepository + "/releases/download/" + url.PathEscape(tag) + "/" + url.PathEscape(asset.Name)
	body, err := updateGET(ctx, client, target)
	if err != nil {
		return err
	}
	defer body.Close()
	hash := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, hash), io.LimitReader(body, asset.Size+1))
	if err != nil {
		return err
	}
	if n != asset.Size || "sha256:"+hex.EncodeToString(hash.Sum(nil)) != asset.Digest {
		return errors.New("release asset digest mismatch")
	}
	return nil
}

func unpackUpdateArchive(file *os.File, directory, root string) error {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return err
	}
	stream, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer stream.Close()
	stream.Multistream(false)
	archive := tar.NewReader(io.LimitReader(stream, updateExpandedLimit+1))
	seen := make(map[string]bool)
	for {
		h, err := archive.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		name := strings.TrimSuffix(h.Name, "/")
		if name == root && h.Typeflag == tar.TypeDir {
			continue
		}
		rel, ok := strings.CutPrefix(name, root+"/")
		if !ok || rel == "" || rel == "." || !filepath.IsLocal(rel) || path.Clean(rel) != rel ||
			strings.ContainsAny(rel, "\\\r\n\x00") || seen[rel] || len(seen) >= 256 {
			return errors.New("unsafe update archive entry")
		}
		seen[rel] = true
		dest := filepath.Join(directory, rel)
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(dest, 0700); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(dest), 0700); err != nil {
				return err
			}
			mode := os.FileMode(0600)
			if rel == "bin/"+root {
				mode = 0700
			}
			out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
			if err != nil {
				return err
			}
			_, copyErr := io.Copy(out, archive)
			err = errors.Join(copyErr, out.Sync(), out.Close())
			if err != nil {
				return err
			}
		default:
			return errors.New("update archive contains a link or special file")
		}
	}
}

type installedUpdate struct {
	Tag     string        `json:"tag"`
	Version binaryVersion `json:"version"`
}

func validInstalledUpdate(v installedUpdate) bool {
	return updateTagParts(v.Tag) != nil && updateCommit(v.Version.Commit) &&
		v.Version.OS == "linux" && v.Version.Arch == runtime.GOARCH && v.Version.Schema > 0 && v.Version.BuiltAt != ""
}

func verifyUpdateBundle(directory string, update installedUpdate) error {
	if err := verifyBundleFiles(directory, update.Version, "RELEASE.json"); err != nil {
		return err
	}
	raw, err := os.ReadFile(filepath.Join(directory, "RELEASE.json"))
	var release struct {
		Schema         int    `json:"schema"`
		Version        string `json:"version"`
		Tag            string `json:"tag"`
		Commit         string `json:"commit"`
		OS             string `json:"os"`
		Arch           string `json:"arch"`
		DatabaseSchema int    `json:"database_schema"`
	}
	series, _, _ := strings.Cut(strings.TrimPrefix(update.Tag, "v"), "-")
	if err != nil || len(raw) > 4096 || json.Unmarshal(raw, &release) != nil ||
		release.Schema != 1 || release.Version != series ||
		release.Tag != update.Tag || release.Commit != update.Version.Commit ||
		release.OS != update.Version.OS || release.Arch != update.Version.Arch ||
		release.DatabaseSchema != update.Version.Schema {
		return errors.New("update bundle release identity mismatch")
	}
	return nil
}

func stageUpdateRelease(ctx context.Context, client *http.Client, root string, release updateRelease) (installedUpdate, error) {
	var installed installedUpdate
	archiveName := updateBundleName() + ".tar.gz"
	manifestAsset, manifestOK := release.asset("RELEASE-MANIFEST.json", updateManifestLimit)
	checksumAsset, checksumOK := release.asset(archiveName+".sha256", 1024)
	archiveAsset, archiveOK := release.asset(archiveName, updateArchiveLimit)
	if !manifestOK || !checksumOK || !archiveOK || release.Draft || updateTagParts(release.Tag) == nil {
		return installed, errors.New("release is missing its published verification files")
	}
	var manifestText, checksumText strings.Builder
	if err := downloadUpdateAsset(ctx, client, release.Tag, manifestAsset, &manifestText); err != nil {
		return installed, err
	}
	var manifest struct {
		Schema     int    `json:"schema"`
		Repository string `json:"repository"`
		Tag        string `json:"tag"`
		Commit     string `json:"commit"`
		Prerelease bool   `json:"prerelease"`
		Assets     map[string]struct {
			SHA256 string `json:"sha256"`
			Size   int64  `json:"size"`
		} `json:"assets"`
	}
	if json.Unmarshal([]byte(manifestText.String()), &manifest) != nil || manifest.Schema != 1 ||
		manifest.Repository != updateRepository || manifest.Tag != release.Tag || manifest.Prerelease != release.Prerelease || !updateCommit(manifest.Commit) {
		return installed, errors.New("release manifest identity mismatch")
	}
	for _, asset := range []updateAsset{archiveAsset, checksumAsset} {
		item := manifest.Assets[asset.Name]
		if item.Size != asset.Size || "sha256:"+item.SHA256 != asset.Digest {
			return installed, errors.New("release manifest asset mismatch")
		}
	}
	if err := downloadUpdateAsset(ctx, client, release.Tag, checksumAsset, &checksumText); err != nil {
		return installed, err
	}
	line := strings.Fields(checksumText.String())
	if len(line) != 2 || "sha256:"+line[0] != archiveAsset.Digest || line[1] != archiveName {
		return installed, errors.New("release checksum file mismatch")
	}

	releases := filepath.Join(root, "releases")
	staging, err := os.MkdirTemp(releases, ".download-")
	if err != nil {
		return installed, err
	}
	defer os.RemoveAll(staging)
	file, err := os.CreateTemp(staging, "archive-")
	if err != nil {
		return installed, err
	}
	defer file.Close()
	if err = downloadUpdateAsset(ctx, client, release.Tag, archiveAsset, file); err != nil {
		return installed, err
	}
	bundle := filepath.Join(staging, "bundle")
	if err = os.Mkdir(bundle, 0700); err != nil {
		return installed, err
	}
	if err = unpackUpdateArchive(file, bundle, updateBundleName()); err != nil {
		return installed, err
	}
	installed.Tag = release.Tag
	version, err := os.ReadFile(filepath.Join(bundle, "VERSION.json"))
	if err != nil || len(version) > 4096 || json.Unmarshal(version, &installed.Version) != nil ||
		!validInstalledUpdate(installed) || installed.Version.Commit != manifest.Commit {
		return installed, errors.New("update binary identity mismatch")
	}
	if err := verifyUpdateBundle(bundle, installed); err != nil {
		return installed, err
	}
	target := filepath.Join(releases, installed.Version.Commit)
	if _, err = os.Lstat(target); err == nil {
		if err = verifyUpdateBundle(target, installed); err != nil {
			return installed, errors.New("existing release files do not match this release")
		}
		existing, err := fileDigest(updateBinary(root, installed))
		incoming, newErr := fileDigest(filepath.Join(bundle, "bin", updateBundleName()))
		if err != nil || newErr != nil || existing != incoming {
			return installed, errors.New("existing binary differs from the published release")
		}
		return installed, nil
	} else if !os.IsNotExist(err) {
		return installed, err
	}
	if err = filepath.WalkDir(bundle, func(name string, entry os.DirEntry, err error) error {
		if err == nil && entry.IsDir() {
			return syncDirectory(name)
		}
		return err
	}); err != nil {
		return installed, err
	}
	if err = os.Rename(bundle, target); err != nil {
		return installed, err
	}
	return installed, syncDirectory(releases)
}
