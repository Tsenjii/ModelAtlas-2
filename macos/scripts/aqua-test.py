#!/usr/bin/env python3
"""Two additional bounded native launches; preserve every original QA phase."""
import hashlib
import json
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
peaks = {"app_rss_bytes": 0, "engine_rss_bytes": 0, "samples": 0,
         "method": "two standalone packaged launches; production appearance controls, four local artworks, drafts and relaunch"}
deadline = time.monotonic() + 150
phases = []
for restart in (False, True):
    pid_file = out / "measurement-pids.json"
    if pid_file.exists():
        pid_file.unlink()
    command = [str(pathlib.Path(app) / "Contents" / "MacOS" / "ModelAtlas"),
               "--smoke-dir", str(out), "--aqua-smoke"]
    if restart:
        command.append("--aqua-reopen")
    with (root / ("aqua-relaunch.log" if restart else "aqua-app.log")).open("w") as log:
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
                raise RuntimeError("native Aqua process exceeded its additional deadline")
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
    phases.append({"app_pid": ids["app_pid"], "engine_pid": ids["engine_pid"], "exit": process.returncode})
    first = json.loads((out / ("native-aqua.json" if restart else "aqua-first.json")).read_text())
    assert first["result"] == "PASS"

report = json.loads((out / "native-aqua.json").read_text())
assert report["screenshots"] == len(list(out.glob("*.png"))) == 12
assert report["transparency_control"] == "actual app toggle; system preference read-only and never modified"
for name in ("actual_settings_buttons", "four_bundled_artworks", "same_original_bytes",
             "classic_default_and_invalid_fallback", "scope_and_draft_retained", "provider_form_retained",
             "relaunch_preference_retained", "reduced_transparency", "no_inference_or_research"):
    assert report[name] is True, name
assert {(item["id"], item["sha256"], item["bytes"]) for item in report["images"]} == {
    (item["id"], item["sha256"], item["bytes"]) for item in manifest["images"]}
assert peaks["app_rss_bytes"] > 0 and peaks["engine_rss_bytes"] > 0 and peaks["samples"] >= 3
assert len({phase["app_pid"] for phase in phases}) == 2 and len({phase["engine_pid"] for phase in phases}) == 2
peaks.update(phases=phases, result="PASS", owned_engines_exited=True)
(root / "aqua-footprint.json").write_text(json.dumps(peaks, indent=2))
print(json.dumps(report, ensure_ascii=False))
