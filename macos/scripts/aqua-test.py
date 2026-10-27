#!/usr/bin/env python3
"""Actual artwork, app transparency, relaunch and system accessibility fixture."""
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

app, qa = sys.argv[1:]
root = pathlib.Path(qa)
out = root / "aqua-ui"
out.mkdir(parents=True, exist_ok=True)
source = pathlib.Path(__file__).resolve().parents[1] / "Resources" / "Wallpapers"
manifest = json.loads((source / "manifest.json").read_text())
assert len(manifest["images"]) == 4
for item in manifest["images"]:
    raw = (pathlib.Path(app) / "Contents" / "Resources" / "Wallpapers" / item["file"]).read_bytes()
    assert len(raw) == item["bytes"] and hashlib.sha256(raw).hexdigest() == item["sha256"]

# Official runner images enable reduceTransparency. Exercise real system states
# in disposable CI, restore exactly, and never override SwiftUI's read-only value.
assert os.environ.get("GITHUB_ACTIONS") == "true" or os.environ.get("MODELATLAS_ALLOW_AQUA_SYSTEM_TEST") == "1", "Use disposable CI, or explicitly opt into the reversible system transparency fixture."

def read_system():
    result = subprocess.run(["defaults", "read", "com.apple.universalaccess", "reduceTransparency"], capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        raw = result.stdout.strip().lower()
        assert raw in ("0", "1", "true", "false")
        return {"present": True, "value": raw in ("1", "true")}
    assert "does not exist" in result.stderr.lower(), "Cannot read system transparency preference"
    return {"present": False, "value": None}

def write_system(value):
    subprocess.run(["defaults", "write", "com.apple.universalaccess", "reduceTransparency", "-bool", "true" if value else "false"], capture_output=True, check=True, timeout=10)
    assert read_system() == {"present": True, "value": value}

before = read_system()
fixture = {"before": before, "requested": [False, False, True], "observed": [], "restored": False}
peaks = {"app_rss_bytes": 0, "engine_rss_bytes": 0, "samples": 0,
         "method": "three standalone packaged launches; exact artworks, actual app toggle and real system accessibility states"}
deadline = time.monotonic() + 150
phases = []
try:
    for phase in range(3):
        write_system(phase == 2)
        pid_file = out / "measurement-pids.json"
        if pid_file.exists():
            pid_file.unlink()
        command = [str(pathlib.Path(app) / "Contents" / "MacOS" / "ModelAtlas"), "--smoke-dir", str(out), "--aqua-smoke"]
        if phase == 1:
            command.append("--aqua-reopen")
        elif phase == 2:
            command.append("--aqua-system-opaque")
        with (root / ("aqua-app.log" if phase == 0 else "aqua-relaunch.log" if phase == 1 else "aqua-system.log")).open("w") as log:
            process = subprocess.Popen(command, stdout=log, stderr=log)
            try:
                while process.poll() is None and time.monotonic() < deadline:
                    if pid_file.exists():
                        ids = json.loads(pid_file.read_text())
                        for field, key in (("app_pid", "app_rss_bytes"), ("engine_pid", "engine_rss_bytes")):
                            result = subprocess.run(["ps", "-o", "rss=", "-p", str(int(ids[field]))], capture_output=True, text=True)
                            peaks[key] = max(peaks[key], int(result.stdout.strip() or 0) * 1024)
                        peaks["samples"] += 1
                    time.sleep(0.15)
                if process.poll() is None:
                    process.kill(); process.wait()
                    raise RuntimeError("native Aqua process exceeded its unchanged additional deadline")
                assert process.returncode == 0, process.returncode
            finally:
                if process.poll() is None:
                    process.kill(); process.wait()
                (root / "aqua-footprint.json").write_text(json.dumps(peaks, indent=2))
        failure = out / "FAILURE.txt"
        assert not failure.exists(), failure.read_text() if failure.exists() else ""
        ids = json.loads(pid_file.read_text())
        result = subprocess.run(["ps", "-o", "pid=", "-p", str(int(ids["engine_pid"]))], capture_output=True, text=True)
        assert not result.stdout.strip(), "native Aqua app left its owned engine running"
        report = json.loads((out / ("aqua-first.json" if phase == 0 else "aqua-relaunch.json" if phase == 1 else "native-aqua.json")).read_text())
        assert report["result"] == "PASS" and report["system_transparency"] is (phase == 2)
        fixture["observed"].append(report["system_transparency"])
        phases.append({"app_pid": ids["app_pid"], "engine_pid": ids["engine_pid"], "exit": process.returncode, "system_transparency": report["system_transparency"]})
finally:
    if before["present"]:
        write_system(before["value"])
    else:
        subprocess.run(["defaults", "delete", "com.apple.universalaccess", "reduceTransparency"], capture_output=True, check=True, timeout=10)
    fixture["after"] = read_system()
    fixture["restored"] = fixture["after"] == before
    (out / "system-transparency-fixture.json").write_text(json.dumps(fixture, indent=2))
    assert fixture["restored"], "system transparency preference was not restored"

report = json.loads((out / "native-aqua.json").read_text())
assert report["screenshots"] == len(list(out.glob("*.png"))) == 13
assert report["transparency_control"] == "actual app toggle and separately launched system preference fixture"
for name in ("actual_settings_buttons", "four_bundled_artworks", "same_original_bytes",
             "classic_default_and_invalid_fallback", "scope_and_draft_retained", "provider_form_retained",
             "relaunch_preference_retained", "reduced_transparency", "system_reduced_transparency", "no_inference_or_research"):
    assert report[name] is True, name
assert {(item["id"], item["sha256"], item["bytes"]) for item in report["images"]} == {
    (item["id"], item["sha256"], item["bytes"]) for item in manifest["images"]}
assert peaks["app_rss_bytes"] > 0 and peaks["engine_rss_bytes"] > 0 and peaks["samples"] >= 3
assert len({phase["app_pid"] for phase in phases}) == 3 and len({phase["engine_pid"] for phase in phases}) == 3
assert fixture["observed"] == fixture["requested"] == [False, False, True]
peaks.update(phases=phases, result="PASS", owned_engines_exited=True, system_fixture_restored=True)
(root / "aqua-footprint.json").write_text(json.dumps(peaks, indent=2))
print(json.dumps(report, ensure_ascii=False))
