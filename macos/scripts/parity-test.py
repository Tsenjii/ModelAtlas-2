#!/usr/bin/env python3
"""Second bounded native launch; never replaces the original acceptance phase."""
import json
import pathlib
import subprocess
import sys
import time

app, qa, fixture = sys.argv[1:]
root = pathlib.Path(qa)
out = root / "parity-ui"
out.mkdir(parents=True, exist_ok=True)
peaks = {"app_rss_bytes": 0, "engine_rss_bytes": 0, "combined_rss_bytes": 0, "additional_fixture_rss_bytes": 0, "samples": 0,
         "method": "ps RSS; separate packaged native app and owned engine; real TLS media, management, versions, evidence, safe refs, adaptive composer, sidebar, scoped prices, quota pools, telemetry trends, live comparison, appearance choices and fifty-seven native captures including durable transfer pause/restart/resume; all additional owned engines included"}
with (root / "parity-app.log").open("w") as log:
    process = subprocess.Popen([str(pathlib.Path(app) / "Contents/MacOS/ModelAtlas"), "--smoke-dir", str(out),
                                "--fixture-url", fixture, "--parity-smoke"], stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 120
        while process.poll() is None and time.monotonic() < deadline:
            pid_file = out / "measurement-pids.json"
            if pid_file.exists():
                ids = json.loads(pid_file.read_text())
                values = []
                for key in ["app_pid", "engine_pid"]:
                    result = subprocess.run(["ps", "-o", "rss=", "-p", str(int(ids[key]))], capture_output=True, text=True)
                    values.append(int(result.stdout.strip() or 0) * 1024)
                extra = 0
                for pid in ids.get("additional_engine_pids", []):
                    result = subprocess.run(["ps", "-o", "rss=", "-p", str(int(pid))], capture_output=True, text=True)
                    extra += int(result.stdout.strip() or 0) * 1024
                peaks["additional_fixture_rss_bytes"] = max(peaks["additional_fixture_rss_bytes"], extra)
                peaks["app_rss_bytes"] = max(peaks["app_rss_bytes"], values[0])
                peaks["engine_rss_bytes"] = max(peaks["engine_rss_bytes"], values[1])
                peaks["combined_rss_bytes"] = max(peaks["combined_rss_bytes"], sum(values) + extra)
                peaks["samples"] += 1
            time.sleep(.15)
        if process.poll() is None:
            process.kill()
            process.wait()
            raise RuntimeError("Native parity smoke timed out")
        assert process.returncode == 0, process.returncode
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        (root / "parity-footprint.json").write_text(json.dumps(peaks, indent=2))
report = out / "native-parity.json"
assert report.exists(), (out / "FAILURE.txt").read_text() if (out / "FAILURE.txt").exists() else "Native parity did not finish"
value = json.loads(report.read_text())
assert value["result"] == "PASS" and value["screenshots"] == 57, value
for field in ["workspace_transfer_controls", "kilo_factory_defaults", "kilo_no_automatic_inference", "real_tls_media", "batch_confirm_undo", "stale_guards", "connector_caller", "knowledge_versions", "manual_evidence", "safe_references", "retained_history", "native_composer", "adaptive_composer", "composer_focus", "sidebar_toggle", "price_scope_guards"]:
    assert value[field], field
for field in ["native_intelligence", "telemetry_trends", "quota_scope_guards", "read_only_no_inference"]:
    assert value[field], field
for field in ["native_comparison", "comparison_receipts", "comparison_scope_guards", "appearance_choices"]:
    assert value[field], field
for field in ["chart_keyboard_selection", "observation_archive", "chart_receipt_identity", "chart_export_exact"]:
    assert value[field], field
for field in ["account_checkin_scope", "checkin_retry_cancel", "native_motion", "reduced_motion", "public_site_auto_read", "provider_series_scope", "site_partial_retry", "connection_actions", "provider_window_restored"]:
    assert value[field], field
for field in ["provider_form_feedback", "provider_account_write_guards", "provider_next_draft", "credential_restore_scope", "saved_refresh_no_repost", "workspace_sync_entry"]:
    assert value[field], field
assert len(list(out.glob("*.png"))) == 57
assert len(list((out / "motion").glob("*.png"))) == 14
management = json.loads((out / "provider-management.diagnostics.log").read_text())
assert management["result"] == "PASS" and all(v for v in management.values() if type(v) is bool)
motion = json.loads((out / "motion.diagnostics.log").read_text())
assert motion["result"] == "PASS" and motion["reduced_settled"] and motion["normal_frames"] == 12 and motion["reduced_frames"] == 2
assert peaks["app_rss_bytes"] > 0 and peaks["engine_rss_bytes"] > 0 and peaks["samples"] >= 3
ids = json.loads((out / "measurement-pids.json").read_text())
child = subprocess.run(["ps", "-o", "pid=", "-p", str(int(ids["engine_pid"]))], capture_output=True, text=True)
assert not child.stdout.strip(), "Native parity exited but its owned engine is still running"
print(json.dumps(value, ensure_ascii=False))

transfer = json.loads((out / "workspace-transfer.diagnostics.json").read_text())
assert transfer["result"] == "PASS" and transfer["destination_models"] == 15 and transfer["paused_progress_bytes"] > 0 and transfer["outgoing_acked"] > 0
for field in ["same_transfer_after_restart", "pause_during_busy", "actual_keychain_restart", "source_product_retained", "no_inference"]:
    assert transfer[field] is True, field
assert len(ids["additional_engine_pids"]) == 3 and peaks["additional_fixture_rss_bytes"] > 0
for pid in ids["additional_engine_pids"]:
    child = subprocess.run(["ps", "-o", "pid=", "-p", str(int(pid))], capture_output=True, text=True)
    assert not child.stdout.strip(), "Workspace transfer fixture engine still running"
