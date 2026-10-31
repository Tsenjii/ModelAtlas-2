#!/usr/bin/env python3
"""Additive bounded native research UI and genuine TLS runner integration."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

app, qa = map(Path, sys.argv[1:])
repo = Path(__file__).resolve().parents[2]
out = qa / "research-ui"
out.mkdir(parents=True, exist_ok=True)
fixture_file = out / "fixture-private.json"
fixture_binary = qa / "research-fixture"
subprocess.run(["go", "test", "-c", "-o", str(fixture_binary)], cwd=repo / "vps", check=True, timeout=120)
env = dict(os.environ, MODELATLAS_RESEARCH_FIXTURE_OUT=str(fixture_file))
peaks = dict(app_rss_bytes=0, engine_rss_bytes=0, fixture_rss_bytes=0, combined_rss_bytes=0, samples=0,
             method="ps RSS for third standalone native launch, its owned packaged engine and separate synthetic TLS test fixture; original smoke/parity workloads and deadlines unchanged")
with (qa / "research-fixture.log").open("w") as fixture_log, (qa / "research-app.log").open("w") as app_log:
    fixture = subprocess.Popen([str(fixture_binary), "-test.run", "^TestResearchMacNativeFixture$", "-test.timeout=3m"], cwd=repo / "vps", env=env, stdout=fixture_log, stderr=fixture_log)
    process = None
    try:
        deadline = time.monotonic() + 15
        while not fixture_file.exists():
            assert fixture.poll() is None and time.monotonic() < deadline, "Native research TLS fixture failed readiness"
            time.sleep(.05)
        process = subprocess.Popen([str(app / "Contents/MacOS/ModelAtlas"), "--smoke-dir", str(out), "--research-smoke"], env=env, stdout=app_log, stderr=app_log)
        deadline = time.monotonic() + 120
        while process.poll() is None and time.monotonic() < deadline:
            pid_file = out / "measurement-pids.json"
            if pid_file.exists():
                ids = json.loads(pid_file.read_text())
                values = []
                for name in ("app_pid", "engine_pid", "fixture_pid"):
                    value = subprocess.run(["ps", "-o", "rss=", "-p", str(int(ids[name]))], capture_output=True, text=True)
                    values.append(int(value.stdout.strip() or 0) * 1024)
                for name, value in zip(("app_rss_bytes", "engine_rss_bytes", "fixture_rss_bytes"), values):
                    peaks[name] = max(peaks[name], value)
                peaks["combined_rss_bytes"] = max(peaks["combined_rss_bytes"], sum(values))
                peaks["samples"] += 1
            time.sleep(.15)
        assert process.poll() is not None, "Native research process exceeded its separate 120-second deadline"
        assert process.returncode == 0, process.returncode
        peaks["app_exit_code"] = process.returncode
        report = out / "native-research.json"
        assert report.exists(), (out / "FAILURE.txt").read_text() if (out / "FAILURE.txt").exists() else "Native research did not finish"
        value = json.loads(report.read_text())
        assert value["result"] == "PASS" and value["screenshots"] == 8
        for name in ("real_tls", "native_update_buttons", "live_run_state", "last_good_preserved", "saved_refresh_no_repost", "lost_ack_no_repost", "concurrent_control_guard", "reopen_same_run", "seven_exact_requests", "no_inference"):
            assert value[name] is True, name
        assert len(list(out.glob("*.png"))) == 8
        assert all(peaks[x] > 0 for x in ("app_rss_bytes", "engine_rss_bytes", "fixture_rss_bytes")) and peaks["samples"] >= 3
        ids = json.loads((out / "measurement-pids.json").read_text())
        remaining = subprocess.run(["ps", "-o", "pid=", "-p", str(int(ids["engine_pid"]))], capture_output=True, text=True)
        assert not remaining.stdout.strip(), "Native research app exited with its engine still alive"
        peaks["owned_engine_exited"] = True
        print(json.dumps(value, ensure_ascii=False))
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        fixture.terminate()
        try:
            fixture.wait(timeout=15)
        except subprocess.TimeoutExpired:
            fixture.kill()
            fixture.wait()
        # This private ready file contains only a synthetic fixture token, and
        # is excluded from uploaded QA artifacts regardless of failure path.
        fixture_file.unlink(missing_ok=True)
        peaks["fixture_exit_code"] = fixture.returncode
        (qa / "research-footprint.json").write_text(json.dumps(peaks, indent=2) + "\n")
    assert fixture.returncode == 0, "Synthetic TLS fixture detected a real protocol failure"
