#!/usr/bin/env python3
"""Create a draft, upload all platforms, verify every byte digest, then publish."""
import json
import os
from pathlib import Path
import subprocess
import time

from prepare import CHECKS, REPOSITORY, ROOT, digest, properties, require, sums

PAYLOADS = {
    "modelatlas-vps-linux-amd64.tar.gz", "modelatlas-vps-linux-amd64.tar.gz.sha256",
    "modelatlas-vps-linux-arm64.tar.gz", "modelatlas-vps-linux-arm64.tar.gz.sha256",
    "ModelAtlas-macOS-Universal.zip", "ModelAtlas-macOS-Universal.dmg",
    "ModelAtlas-Android-preview.apk", "ModelAtlas-Android.json",
}
ADDITIONAL = {"RELEASE-MANIFEST.json", "SHA256SUMS.txt", "RELEASE-NOTES.md"}

def gh(*args, optional=False):
    result = subprocess.run(["gh", *args], text=True, capture_output=True)
    if result.returncode:
        if optional and "HTTP 404" in result.stderr:
            return None
        raise ValueError("GitHub release operation failed; publication stopped")
    if not result.stdout.strip():
        return {}
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {"output": result.stdout.strip()}

def validate_output(out, commit):
    manifest = json.loads((out / "RELEASE-MANIFEST.json").read_text())
    require(manifest["repository"] == REPOSITORY and manifest["commit"] == commit and manifest["tag"] == properties()["tag"] and manifest["prerelease"] is True, "release identity mismatch")
    require(set(manifest["checks"]) == set(CHECKS), "incomplete platform CI evidence")
    for workflow, expected in CHECKS.items():
        jobs = manifest["checks"][workflow]["jobs"]
        require({job["name"] for job in jobs} == expected and all(job["conclusion"] == "success" for job in jobs), "incomplete nine-job evidence")
    require(set(manifest["assets"]) == PAYLOADS, "incomplete three-platform release")
    checksums = sums((out / "SHA256SUMS.txt").read_bytes())
    require(set(checksums) == PAYLOADS | {"RELEASE-MANIFEST.json"}, "release checksum coverage mismatch")
    for name, expected in checksums.items():
        path = out / name
        require(path.is_file() and not path.is_symlink(), "release asset is missing or linked")
        raw = path.read_bytes()
        require(digest(raw) == expected, "release asset checksum mismatch")
        if name in PAYLOADS:
            require(manifest["assets"][name] == {"sha256": expected, "size": len(raw)}, "release asset metadata mismatch")
    return manifest, {name: {"sha256": digest((out / name).read_bytes()), "size": (out / name).stat().st_size} for name in sorted(PAYLOADS | ADDITIONAL)}

def release_for_tag(tag):
    matches = []
    for page in range(1, 11):
        values = gh("api", "repos/" + REPOSITORY + "/releases?per_page=100&page=" + str(page))
        require(isinstance(values, list), "invalid release collection")
        matches.extend(value for value in values if value["tag_name"] == tag)
        require(len(matches) <= 1, "multiple releases use this tag; publication stopped")
        if len(values) < 100:
            return matches[0] if matches else None
    raise ValueError("release collection exceeds the verified pagination bound")

def created_draft(tag):
    # Creation can succeed before the collection exposes the new draft. Retry
    # only this read (six reads, at most 23 seconds of backoff); never repeat creation or relax the
    # source/draft/digest checks. Existing drafts retain their exact release ID.
    for attempt in range(6):
        value = release_for_tag(tag)
        if value is not None:
            return value
        if attempt < 5:
            time.sleep(min(1 << attempt, 8))
    raise ValueError("created draft is still unavailable after bounded read retries")

def tag_commit(tag):
    ref = gh("api", "repos/" + REPOSITORY + "/git/ref/tags/" + tag, optional=True)
    if ref is None:
        return None
    obj = ref["object"]
    for _ in range(5):
        if obj["type"] == "commit":
            return obj["sha"]
        require(obj["type"] == "tag", "release tag does not reference a commit")
        obj = gh("api", "repos/" + REPOSITORY + "/git/tags/" + obj["sha"])["object"]
    raise ValueError("release tag chain is too deep")

def verify_uploaded(release, expected):
    actual = {item["name"]: item for item in release["assets"]}
    require(set(actual) == set(expected), "uploaded release does not contain exactly all verified assets")
    for name, info in expected.items():
        require(actual[name]["size"] == info["size"] and actual[name].get("digest") == "sha256:" + info["sha256"], "uploaded asset digest mismatch")

def main():
    require(os.environ.get("MODELATLAS_REPOSITORY") == REPOSITORY, "unexpected repository")
    commit = os.environ["MODELATLAS_SOURCE_COMMIT"]
    out = ROOT / "release-output"
    manifest, expected = validate_output(out, commit)
    tag = manifest["tag"]
    resolved = tag_commit(tag)
    require(resolved is None or resolved == commit, "release tag belongs to another source; choose a new tag")
    existing = release_for_tag(tag)
    if existing and not existing["draft"]:
        require(resolved == commit and existing["prerelease"], "existing release identity mismatch")
        verify_uploaded(existing, expected)
        print("This exact complete release is already published.")
        return
    if existing:
        require(existing["target_commitish"] == commit and existing["prerelease"], "draft belongs to another source")
    if resolved is None:
        gh("api", "--method", "POST", "repos/" + REPOSITORY + "/git/refs",
           "--raw-field", "ref=refs/tags/" + tag, "--raw-field", "sha=" + commit)
    if not existing:
        gh("release", "create", tag, "--repo", REPOSITORY, "--target", commit,
           "--title", "ModelAtlas " + tag + "｜VPS · Mac · Android",
           "--draft", "--prerelease", "--verify-tag", "--notes-file", str(out / "RELEASE-NOTES.md"))
        existing = created_draft(tag)
    require(existing is not None and existing["draft"] and existing["target_commitish"] == commit, "created draft identity mismatch")
    endpoint = "repos/" + REPOSITORY + "/releases/" + str(existing["id"])
    require(tag_commit(tag) == commit, "created release tag source mismatch")
    subprocess.run(["gh", "release", "upload", tag, "--repo", REPOSITORY, "--clobber", *[str(out / name) for name in sorted(expected)]], check=True)
    uploaded = gh("api", endpoint)
    require(uploaded["draft"] and uploaded["target_commitish"] == commit, "draft changed during upload")
    verify_uploaded(uploaded, expected)
    final = out / "finalize-request.json"
    final.write_text(json.dumps({"draft": False, "prerelease": True, "make_latest": "false"}))
    published = gh("api", "--method", "PATCH", "repos/" + REPOSITORY + "/releases/" + str(uploaded["id"]), "--input", str(final))
    require(not published["draft"] and published["prerelease"], "release finalization failed")
    verify_uploaded(published, expected)
    print("Published all three verified platforms: " + published["html_url"])

if __name__ == "__main__":
    main()
