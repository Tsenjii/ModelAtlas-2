#!/usr/bin/env python3
"""Publish only intact artifacts produced and tested from one exact Git commit."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Tsenjii/ModelAtlas-2"
CHECKS = {
    ".github/workflows/vps.yml": {"vps-test", "vps-browser", "vps-linux-amd64", "vps-linux-arm64"},
    ".github/workflows/android.yml": {"build", "device-tests"},
    ".github/workflows/macos-native.yml": {"macos-build", "macos-test (macos-15, arm64)", "macos-test (macos-15-intel, x86_64)"},
}
MAX_ARCHIVE = 512 * 1024 * 1024

def require(condition, message):
    if not condition:
        raise ValueError(message)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def safe_name(name):
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and "\\" not in name and ".." not in path.parts and "\x00" not in name, "unsafe archive member")
    return path

def properties():
    lines = (ROOT / "release/version.properties").read_text().splitlines()
    return dict(line.split("=", 1) for line in lines if line and not line.startswith("#"))

class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        require(urllib.parse.urlsplit(newurl).scheme == "https", "insecure artifact redirect")
        redirected = super().redirect_request(request, fp, code, msg, headers, newurl)
        if urllib.parse.urlsplit(request.full_url).netloc != urllib.parse.urlsplit(newurl).netloc:
            redirected.remove_header("Authorization")
        return redirected

class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(PublicRedirect())
    def request(self, path):
        require(path.startswith("/") and not path.startswith("//"), "invalid GitHub API path")
        return urllib.request.Request("https://api.github.com" + path, headers={
            "Accept": "application/vnd.github+json", "Authorization": "Bearer " + self.token,
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "ModelAtlas-release"
        })
    def get(self, path):
        with self.opener.open(self.request(path), timeout=60) as response:
            data = response.read(16 * 1024 * 1024 + 1)
        require(len(data) <= 16 * 1024 * 1024, "oversized GitHub API response")
        return json.loads(data)
    def pages(self, path, key):
        result = []
        separator = "&" if "?" in path else "?"
        for page in range(1, 11):
            data = self.get(path + separator + "per_page=100&page=" + str(page))[key]
            result.extend(data)
            if len(data) < 100:
                return result
        raise ValueError("GitHub result exceeds the verified pagination bound")
    def download(self, artifact, output):
        require(not artifact.get("expired"), "expired artifact")
        expected = artifact.get("digest", "")
        require(re.fullmatch(r"sha256:[a-f0-9]{64}", expected) is not None, "artifact has no verified SHA256 digest")
        require(0 < artifact["size_in_bytes"] <= MAX_ARCHIVE, "artifact size out of bounds")
        request = self.request("/repos/" + REPOSITORY + "/actions/artifacts/" + str(artifact["id"]) + "/zip")
        count = 0
        checksum = hashlib.sha256()
        with self.opener.open(request, timeout=90) as response, output.open("xb") as target:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                count += len(chunk)
                require(count <= MAX_ARCHIVE, "artifact exceeds download bound")
                checksum.update(chunk)
                target.write(chunk)
        require(count == artifact["size_in_bytes"], "artifact size mismatch")
        require("sha256:" + checksum.hexdigest() == expected, "artifact digest mismatch")

def select_run(runs, workflow, commit):
    matching = [run for run in runs if run.get("head_sha") == commit and run.get("path", "").split("@")[0] == workflow and run.get("event") in {"pull_request", "push", "workflow_dispatch"} and (run.get("head_repository") or {}).get("full_name") == REPOSITORY]
    return max(matching, key=lambda run: (run["id"], run.get("run_attempt", 1)), default=None)

def verified_jobs(run, jobs, expected, commit):
    require(run.get("head_sha") == commit, "CI source mismatch")
    require(run["status"] == "completed" and run["conclusion"] == "success", "CI workflow is not successful")
    actual = {job["name"]: job for job in jobs}
    require(expected <= actual.keys(), "missing mandatory CI job")
    for name in expected:
        require(actual[name]["status"] == "completed" and actual[name]["conclusion"] == "success", "mandatory CI job did not pass")
    return [{"id": actual[name]["id"], "name": name, "conclusion": "success"} for name in sorted(expected)]

def wait_for_ci(api, commit, minutes):
    deadline = time.monotonic() + minutes * 60
    previous = None
    while True:
        runs = api.pages("/repos/" + REPOSITORY + "/actions/runs?head_sha=" + commit, "workflow_runs")
        result = {}
        state = []
        for workflow, expected in CHECKS.items():
            run = select_run(runs, workflow, commit)
            state.append(workflow + ":" + (run["status"] + "/" + str(run["conclusion"]) if run else "awaiting exact-source run"))
            if run and run["status"] == "completed":
                jobs = api.pages("/repos/" + REPOSITORY + "/actions/runs/" + str(run["id"]) + "/jobs?filter=latest", "jobs")
                result[workflow] = {"id": run["id"], "attempt": run.get("run_attempt", 1), "url": run["html_url"], "jobs": verified_jobs(run, jobs, expected, commit)}
        message = " | ".join(state)
        if message != previous:
            print(message, flush=True)
            previous = message
        if len(result) == len(CHECKS):
            return result
        require(time.monotonic() < deadline, "timed out waiting for all nine exact-source checks")
        time.sleep(20)

def read_zip(path):
    archive = zipfile.ZipFile(path)
    infos = archive.infolist()
    require(len(infos) < 10000, "too many artifact entries")
    require(sum(info.file_size for info in infos) <= 1024 * 1024 * 1024, "expanded artifact too large")
    require(len({info.filename for info in infos}) == len(infos), "duplicate artifact member")
    for info in infos:
        safe_name(info.filename)
    return archive

def member(archive, suffix, max_size=MAX_ARCHIVE):
    matches = [info for info in archive.infolist() if not info.is_dir() and (info.filename == suffix or info.filename.endswith("/" + suffix))]
    require(len(matches) == 1, "missing or ambiguous artifact file: " + suffix)
    require(matches[0].file_size <= max_size, "artifact file too large")
    return archive.read(matches[0])

def sums(data):
    values = {}
    for line in data.decode("utf-8").splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9_./-]+)", line)
        require(match is not None, "invalid checksum manifest")
        name = match[2]
        safe_name(name)
        require(name not in values, "duplicate checksum member")
        values[name] = match[1]
    require(bool(values), "empty checksum manifest")
    return values

def linux_bundle(raw, arch, commit, tag):
    root = "modelatlas-vps-linux-" + arch
    files = {}
    total = 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        entries = archive.getmembers()
        require(len(entries) <= 200, "too many Linux bundle entries")
        for info in entries:
            safe_name(info.name)
            require(info.name == root or info.name.startswith(root + "/"), "foreign Linux bundle path")
            require(info.isdir() or info.isfile(), "linked or special Linux bundle member")
            if info.isfile():
                relative = info.name[len(root) + 1:]
                require(relative not in files, "duplicate Linux file")
                total += info.size
                require(total <= 128 * 1024 * 1024, "Linux bundle too large")
                files[relative] = archive.extractfile(info).read()
    expected = sums(files["SHA256SUMS.txt"])
    require(set(expected) == set(files) - {"SHA256SUMS.txt"}, "Linux checksum coverage mismatch")
    for name, checksum in expected.items():
        require(digest(files[name]) == checksum, "Linux file checksum mismatch")
    version = json.loads(files["VERSION.json"])
    release = json.loads(files["RELEASE.json"])
    require(version["commit"] == commit and version["arch"] == arch and version["os"] == "linux", "Linux binary source/architecture mismatch")
    require(release["commit"] == commit and release["tag"] == tag and release["arch"] == arch and release["database_schema"] == version["schema"], "Linux release identity mismatch")
    binary = files["bin/" + root]
    require(binary[:4] == b"\x7fELF" and binary[4:6] == b"\x02\x01" and int.from_bytes(binary[18:20], "little") == {"amd64": 62, "arm64": 183}[arch], "wrong Linux executable architecture")
    for required in ["deploy/install.sh", "deploy/update.sh", "deploy/manage.sh", "deploy/download.sh", "UPDATE.md", "README.md"]:
        require(required in files, "incomplete Linux installation bundle")
    return files, version

def collect(api, commit, checks, out):
    props = properties()
    artifacts = {}
    for workflow, run in checks.items():
        values = api.pages("/repos/" + REPOSITORY + "/actions/runs/" + str(run["id"]) + "/artifacts", "artifacts")
        artifacts[workflow] = {item["name"]: item for item in values if not item.get("expired")}
    expected = {
        ".github/workflows/vps.yml": ["modelatlas-vps-linux-amd64", "modelatlas-vps-linux-arm64", "modelatlas-vps-footprint", "modelatlas-vps-browser"],
        ".github/workflows/android.yml": ["modelatlas-debug-and-reports", "modelatlas-device-test-reports"],
        ".github/workflows/macos-native.yml": ["ModelAtlas-macOS-Universal", "ModelAtlas-macOS-QA-arm64", "ModelAtlas-macOS-QA-x86_64"],
    }
    for workflow, names in expected.items():
        require(set(names) <= artifacts[workflow].keys(), "missing platform package or QA evidence")
    assets = {}
    provenance = []
    archives = out / "downloaded"
    archives.mkdir()
    def download(workflow, name):
        item = artifacts[workflow][name]
        path = archives / (name + ".zip")
        api.download(item, path)
        provenance.append({"name": name, "id": item["id"], "digest": item["digest"], "run_id": checks[workflow]["id"]})
        return read_zip(path)
    def asset(name, data):
        require("/" not in name and name not in assets, "invalid or duplicate release asset")
        (out / name).write_bytes(data)
        assets[name] = {"sha256": digest(data), "size": len(data)}
    for arch in ["amd64", "arm64"]:
        name = "modelatlas-vps-linux-" + arch
        with download(".github/workflows/vps.yml", name) as archive:
            raw = member(archive, name + ".tar.gz")
            checksum = member(archive, name + ".tar.gz.sha256", 1024)
            require(sums(checksum) == {name + ".tar.gz": digest(raw)}, "Linux outer checksum mismatch")
            files, version = linux_bundle(raw, arch, commit, props["tag"])
            asset(name + ".tar.gz", raw)
            asset(name + ".tar.gz.sha256", checksum)
            if arch == "amd64":
                verified = out / "linux-check"
                verified.mkdir()
                for filename, data in files.items():
                    path = verified / filename
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                binary = verified / "bin" / name
                binary.chmod(0o755)
                result = subprocess.check_output([str(binary), "version"], text=True)
                require(json.loads(result) == version, "actual Linux binary version mismatch")
                subprocess.run([str(binary), "ops", "bundle-check", "--directory", str(verified)], check=True)
    with download(".github/workflows/macos-native.yml", "ModelAtlas-macOS-Universal") as archive:
        raw = member(archive, "ModelAtlas-macOS-Universal.zip")
        dmg = member(archive, "ModelAtlas-macOS-Universal.dmg")
        checksum = sums(member(archive, "SHA256SUMS.txt", 4096))
        require(checksum == {"ModelAtlas-macOS-Universal.zip": digest(raw), "ModelAtlas-macOS-Universal.dmg": digest(dmg)}, "macOS inner checksum mismatch")
        with zipfile.ZipFile(io.BytesIO(raw)) as app:
            metadata = json.loads(app.read("ModelAtlas.app/Contents/Resources/BuildInfo.json"))
            plist = plistlib.loads(app.read("ModelAtlas.app/Contents/Info.plist"))
        require(metadata["commit"] == commit and metadata["tag"] == props["tag"] and metadata["architectures"] == ["arm64", "x86_64"], "macOS source mismatch")
        require(plist["CFBundleShortVersionString"] == props["version"] and plist["CFBundleVersion"] == props["macBuild"], "macOS version mismatch")
        asset("ModelAtlas-macOS-Universal.zip", raw)
        asset("ModelAtlas-macOS-Universal.dmg", dmg)
    with download(".github/workflows/android.yml", "modelatlas-debug-and-reports") as archive:
        apk = member(archive, "debug/app-debug.apk")
        metadata = json.loads(member(archive, "android.json", 16384))
        require(metadata["commit"] == commit and metadata["tag"] == props["tag"], "Android source mismatch")
        require(metadata["apk_sha256"] == digest(apk) and metadata["version_code"] == int(props["androidVersionCode"]) and metadata["version_name"] == props["androidVersionName"], "Android APK identity mismatch")
        require(re.fullmatch(r"[a-f0-9]{64}", metadata["certificate_sha256"]) is not None and metadata["variant"] == "debug-preview" and metadata["production_signing"] is False, "Android preview signer metadata mismatch")
        asset("ModelAtlas-Android-preview.apk", apk)
        asset("ModelAtlas-Android.json", (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode())
    # Keep QA artifacts bound to their successful source runs in the manifest.
    evidence = []
    for workflow, names in expected.items():
        for name in names:
            item = artifacts[workflow][name]
            require(re.fullmatch(r"sha256:[a-f0-9]{64}", item.get("digest", "")) is not None, "QA artifact digest is missing")
            evidence.append({"name": name, "id": item["id"], "digest": item["digest"], "run_id": checks[workflow]["id"]})
    manifest = {"schema": 1, "repository": REPOSITORY, "tag": props["tag"], "commit": commit, "prerelease": True, "platform_order": ["vps", "macos", "android"], "checks": checks, "source_artifacts": evidence, "downloaded_artifacts": provenance, "assets": assets}
    (out / "RELEASE-MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    checksums = dict(assets)
    checksums["RELEASE-MANIFEST.json"] = {"sha256": digest((out / "RELEASE-MANIFEST.json").read_bytes())}
    (out / "SHA256SUMS.txt").write_text("".join(info["sha256"] + "  " + name + "\n" for name, info in sorted(checksums.items())))
    notes = (ROOT / "release/NOTES.md").read_text()
    notes += "\n來源 commit：" + chr(96) + commit + chr(96) + "。三平台均來自此 SHA。\n\n"
    notes += "\n".join("- [" + path.rsplit("/", 1)[-1] + "](" + run["url"] + ")：同 SHA 全部必要工作成功。" for path, run in checks.items()) + "\n"
    (out / "RELEASE-NOTES.md").write_text(notes)
    print("Verified all Linux, macOS and Android release assets.", flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wait-minutes", type=int, default=32)
    args = parser.parse_args()
    require(os.environ.get("MODELATLAS_REPOSITORY") == REPOSITORY, "unexpected repository")
    commit = os.environ["MODELATLAS_SOURCE_COMMIT"]
    require(re.fullmatch(r"[a-f0-9]{40}", commit) is not None, "explicit source commit required")
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == commit, "release checkout mismatch")
    request = json.loads((ROOT / "release/request.json").read_text())
    require(request == {"schema": 1, "tag": properties()["tag"], "prerelease": True, "publish": True, "notes": "release/NOTES.md"}, "invalid release request")
    api = GitHub(os.environ["GH_TOKEN"])
    checks = wait_for_ci(api, commit, args.wait_minutes)
    out = ROOT / "release-output"
    out.mkdir(exist_ok=True)
    collect(api, commit, checks, out)

if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, TimeoutError) as error:
        raise SystemExit("Release verification network failure: " + type(error).__name__)
