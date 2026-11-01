package main

import (
	"net/url"
	"regexp"
	"strings"
)

const siteFamilyParser = "public-site-hints-v1"

type siteFamilyEvidence struct {
	SourceURL     string   `json:"source_url"`
	SourceHash    string   `json:"source_sha256"`
	Parser        string   `json:"parser_version"`
	Family        string   `json:"family_hint"`
	Version       string   `json:"version_hint"`
	VersionKind   string   `json:"version_kind"`
	Basis         []string `json:"evidence_pointers"`
	Confidence    string   `json:"confidence"`
	AuthRole      string   `json:"inference_auth_role"`
	PrivateStatus string   `json:"private_api_status"`
}

func siteReferenceURL(raw string) bool {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "https" || u.Host != "raw.githubusercontent.com" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || strings.ContainsAny(u.Path, "%\\") || strings.Contains(u.Path, "..") || len(raw) > 600 {
		return false
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) < 4 || !validID(parts[0]) || !validID(parts[1]) || !validID(parts[2]) {
		return false
	}
	for _, ext := range []string{".go", ".js", ".ts", ".json", ".md", ".yaml", ".yml"} {
		if strings.HasSuffix(strings.ToLower(u.Path), ext) {
			return true
		}
	}
	return false
}

func siteFamilyObservations(target, hash string, root any, body []byte) []siteFamilyEvidence {
	out := []siteFamilyEvidence{}
	add := func(family, version, versionKind, confidence string, basis ...string) {
		if version == "" || safeText(version, 64) != version || rawSecretPattern.MatchString(version) {
			version = "UNKNOWN"
		}
		out = append(out, siteFamilyEvidence{SourceURL: target, SourceHash: hash, Parser: siteFamilyParser, Family: family, Version: version, VersionKind: versionKind, Basis: basis, Confidence: confidence, AuthRole: "UNKNOWN", PrivateStatus: "NOT_TESTED"})
	}
	// This is a response-shape hint, not independent platform identification or
	// permission to use any private endpoint/version.
	if pointer(root, "/data/quota_per_unit") != nil && pointer(root, "/data/checkin_enabled") != nil {
		add("NEW_API", str(pointer(root, "/data/version")), "REPORTED_RUNTIME", "PATTERN_ONLY", "/data/quota_per_unit", "/data/checkin_enabled", "/data/version")
	}
	if str(pointer(root, "/data/0/object")) == "model" {
		add("OPENAI_COMPATIBLE", "UNKNOWN", "API_SHAPE", "PATTERN_ONLY", "/data/0/object")
	}
	for _, path := range []string{"/info/title", "/meta/generator", "/software/name", "/project/name"} {
		text := strings.ToLower(str(pointer(root, path)))
		family := ""
		if strings.Contains(text, "new-api") || strings.Contains(text, "new api") {
			family = "NEW_API"
		}
		if strings.Contains(text, "one-api") || strings.Contains(text, "one api") {
			family = "ONE_API"
		}
		if family != "" {
			version := "UNKNOWN"
			kind := "CLAIMED_SOFTWARE"
			if path == "/info/title" {
				version = str(pointer(root, "/info/version"))
				kind = "API_SCHEMA"
			}
			add(family, version, kind, "CLAIMED", path)
		}
	}
	// Literal project references are evidence that the text mentions a project.
	// They do not identify the running service, its auth role or its private API.
	lower := strings.ToLower(string(body))
	for _, name := range []string{"new-api", "one-api"} {
		pattern := regexp.MustCompile(`https://github\.com/[a-z0-9_.-]+/` + name + `(?:[\s"'<)/]|$)`)
		if pattern.MatchString(lower) {
			family := "NEW_API"
			if name == "one-api" {
				family = "ONE_API"
			}
			add(family, "UNKNOWN", "PROJECT_REFERENCE", "CLAIMED_REFERENCE", "literal public project URL")
		}
	}
	return out
}

func summarizeSiteFamily(items []siteFamilyEvidence) (string, string) {
	families := map[string]bool{}
	versions := map[string]bool{}
	for _, item := range items {
		if item.Confidence == "CLAIMED_REFERENCE" {
			continue
		}
		families[item.Family] = true
	}
	if len(families) != 1 {
		if len(families) > 1 {
			return "CONFLICTING", "UNKNOWN"
		}
		return "UNKNOWN", "UNKNOWN"
	}
	family := ""
	for k := range families {
		family = k
	}
	for _, item := range items {
		if item.Family == family && item.VersionKind == "REPORTED_RUNTIME" && item.Version != "UNKNOWN" {
			versions[item.Version] = true
		}
	}
	if len(versions) > 1 {
		return family, "CONFLICTING"
	}
	for version := range versions {
		return family, version
	}
	return family, "UNKNOWN"
}

var siteLiteralPath = regexp.MustCompile(`["'` + "`" + `](/[A-Za-z0-9_./{}-]{1,255})["'` + "`" + `]`)

func siteLiteralMetadataPaths(body []byte) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, match := range siteLiteralPath.FindAllStringSubmatch(string(body), 32) {
		path := match[1]
		if connectorDiscoveryPath(path) && !seen[path] {
			seen[path] = true
			out = append(out, path)
		}
	}
	return out
}
