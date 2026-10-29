#!/usr/bin/env python3
"""Read-only candidate audit: inspect actual exact-source CI bytes; never publish."""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import prepare as p

ROOT = Path(__file__).resolve().parents[1]
require = p.require

def go_test_functions(directory):
    functions = set()
    for path in directory.glob("*_test.go"):
        functions.update(re.findall(r"^func ((?:Test|Example|Fuzz)\w*)\(", path.read_text(), re.M))
    functions.discard("TestMain")  # Go's package entry point is not a test case.
    return functions

def browser_image_scopes(config):
    current = config["new_browser_images"]
    frozen = config["rejected_aa_browser"]["required_images"]
    require(isinstance(current, list) and isinstance(frozen, list) and
            all(isinstance(name, str) for name in current+frozen), "invalid browser image scope")
    require(len(set(current)) == len(current) and len(set(frozen)) == len(frozen), "duplicate browser image scope")
    require(len(frozen) == 14 and set(frozen) <= set(current), "original private browser views missing")
    preserved = config["accepted_browser_views"]
    require(len(preserved) == 18 and len(set(preserved)) == 18 and set(preserved) <= set(current), "accepted browser views missing")
    execution = config["accepted_execution_views"]
    require(len(execution) == 4 and len(set(execution)) == 4 and set(execution) <= set(current), "accepted execution views missing")
    aqua = config["accepted_aqua_views"]
    require(len(aqua) == 12 and len(set(aqua)) == 12 and set(aqua) <= set(current), "accepted Aqua browser views missing")
    history = config["accepted_history_views"]
    require(len(history) == 48 and len(set(history)) == 48 and set(history) <= set(current), "accepted history browser views missing")
    # The rejected source's bytes/hash are immutable. New candidate views must
    # never be demanded from an archive produced before those views existed.
    return {"candidate": (509+len(current), set(current)), "rejected_aa": (523, set(frozen))}

def visual_review_count(config):
    return len(browser_image_scopes(config)["candidate"][1])+8+2*(6+len(config["new_mac_research_images"])+len(native_aqua_image_scope(config)))

def android_aqua_image_scope(config):
    names = config["new_android_aqua_images"]
    require(isinstance(names, list) and len(names) == len(set(names)) == 15 and
            set(names) == set(config["initial_android_aqua_images"]) and
            all(isinstance(name, str) and re.fullmatch(r"[0-9]{2}-[a-z0-9-]+\.png", name) and "failure" not in name for name in names) and
            {name[:2] for name in names} == {str(index).zfill(2) for index in range(1,16)}, "Android Aqua image scope changed")
    return set(names)

def candidate_visual_review_count(config):
    return visual_review_count(config)+len(android_aqua_image_scope(config))

def native_aqua_image_scope(config):
    names = config["new_mac_aqua_images"]
    require(isinstance(names, list) and len(names) == 13 and len(set(names)) == 13 and
            all(isinstance(name, str) and re.fullmatch(r"[0-9]{2}-[a-z0-9-]+\.png", name) and "failure" not in name for name in names) and
            {name[:2] for name in names} == {str(index).zfill(2) for index in range(1,14)} and set(config["initial_mac_aqua_images"]) <= set(names), "invalid native Aqua screenshot scope")
    return set(names)

def validate_native_transparency_fixture(resource, fixture):
    requested = fixture.get("requested")
    observed = fixture.get("observed")
    require(requested == observed == [False,False,True] and all(type(value) is bool for value in requested+observed), "actual system transparency observations missing")
    require(fixture.get("restored") is True and resource.get("system_fixture_restored") is True and fixture.get("before") == fixture.get("after"), "system transparency preference not restored")
    before = fixture["before"]
    require(set(before) == {"present","value"} and type(before["present"]) is bool and (type(before["value"]) is bool if before["present"] else before["value"] is None), "system transparency original state missing")
    phases = resource.get("phases",[])
    require(len(phases) == 3 and [phase.get("system_transparency") for phase in phases] == observed and all(type(phase.get("system_transparency")) is bool for phase in phases), "native process did not observe actual system states")

def validate_browser_screenshot_names(names):
    require(all(name.startswith(("connector-private-mapping-", "research-query-cost-", "knowledge-evaluation-cost-", "research-execution-", "aqua-", "research-history-")) and "failure" not in name for name in names), "unexpected new screenshot")

def linux_inventory(directory, arch, config):
    require(arch in ("amd64", "arm64"), "unsupported Linux architecture")
    baseline = config["linux_baseline_documents"]
    require(isinstance(baseline, list) and len(baseline) == 37 and
            all(isinstance(name, str) and Path(name).name == name and name.endswith(".md") for name in baseline) and
            len(set(baseline)) == 37,
            "invalid original Linux document inventory")
    documents = {path.name for path in directory.glob("*.md") if path.is_file()}
    require(set(baseline) <= documents, "original Linux document missing")
    # package.sh includes each public top-level Markdown document. Require the
    # exact current source inventory while retaining the accepted originals.
    return documents | {"VERSION.json", "RELEASE.json", "SHA256SUMS.txt",
                        "bin/modelatlas-vps-linux-"+arch, "deploy/install.sh",
                        "deploy/update.sh", "deploy/manage.sh", "deploy/download.sh",
                        "deploy/modelatlas-vps.service"}

def xml_cases(archive):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    cases = {}
    for name in archive.namelist():
        if not (name.rsplit("/", 1)[-1].startswith("TEST-") and name.endswith(".xml")):
            continue
        root = ET.fromstring(archive.read(name))
        require(root.tag == "testsuite", "unexpected test XML root")
        local = dict(tests=0, failures=0, errors=0, skipped=0)
        for case in root.iter("testcase"):
            key = case.attrib["classname"] + "#" + case.attrib["name"]
            require(key not in cases, "duplicate device test identity")
            action = next((tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None), "pass")
            cases[key] = action
            local["tests"] += 1
            if action != "pass":
                local[{"failure": "failures", "error": "errors", "skipped": "skipped"}[action]] += 1
        require(all(int(root.attrib.get(k, 0)) == v for k, v in local.items()), "test XML summary disagrees with actual cases")
        for key, value in local.items():
            counts[key] += value
    require(bool(cases), "missing test XML")
    return counts, cases

def validate_device_case_extension(counts, cases, old_cases, config):
    added = config["new_device_cases"]
    require(added == {"dev.modelatlas.app.data.MyModelsMetadataRetryStateTest#c1PublicationContentionKeepsAllFiveCallersInOneFlight": "pass"}, "metadata publication regression must pass")
    require(len(old_cases) == 996, "accepted device case inventory changed")
    require(counts == dict(tests=997, failures=0, errors=0, skipped=5), "Android device outcomes changed")
    require(all(cases.get(name) == action for name, action in old_cases.items()), "accepted device case removed or regressed")
    require({name: action for name, action in cases.items() if name not in old_cases} == added, "unexpected or missing device regression case")
    return sorted(added)

def validate_android_aqua_case_extension(counts, cases, old_cases, accepted_cases, config):
    added = config["new_android_aqua_cases"]
    require(isinstance(added, dict) and len(added) == 8 and all(action == "pass" for action in added.values()), "eight Android Aqua cases must pass")
    require(counts == dict(tests=1005, failures=0, errors=0, skipped=5), "Android Aqua device outcomes changed")
    preserved = {name: action for name, action in cases.items() if name not in added}
    validate_device_case_extension(dict(tests=997, failures=0, errors=0, skipped=5), preserved, old_cases, config)
    require(len(accepted_cases) == 997 and preserved == accepted_cases, "accepted997 device outcomes changed")
    require({name: action for name, action in cases.items() if name not in accepted_cases} == added, "Android Aqua regression missing or replaced")
    return sorted(added)

def validate_android_aqua_reports(light, dark, expected_images):
    reports = (light,dark)
    for report in reports:
        require(report.get("result") == "PASS" and report.get("pid",0) > 0, "Android Aqua UI did not finish")
        require(all(report.get(key) is True for key in ("system_settings_restored","preferences_restored","production_secure_before","secure_restored","no_inference")), "Android Aqua restoration/inference guard failed")
        require(report.get("night_before") == report.get("night_after") and report.get("font_before") == report.get("font_after"), "Android Aqua system state not restored")
        require(isinstance(report.get("resolved_font_before"), (int,float)) and report["resolved_font_before"] > 0 and report["resolved_font_before"] == report.get("resolved_font_after"), "Android Aqua effective font configuration not restored")
        require(0 <= report.get("cache_bytes",-1) <= 16*1024*1024, "Android Aqua cache exceeded bound")
    pictures = light["pictures"]+dark["pictures"]
    require(len(light["pictures"]) == 10 and len(dark["pictures"]) == 5 and {item["file"] for item in pictures} == expected_images, "Android Aqua screenshot receipts missing")
    for item in pictures:
        require(item.get("capture_method") == "UiAutomation.takeScreenshot" and
                type(item.get("capture_attempts")) is int and item["capture_attempts"] >= 1 and
                type(item.get("capture_null_results")) is int and item["capture_null_results"] == item["capture_attempts"]-1 and
                type(item.get("capture_ms")) is int and 0 <= item["capture_ms"] < 15_000 and
                item.get("capture_budget_ms") == 15_000, "Android Aqua bounded actual display capture receipt missing")
    large = next(item for item in pictures if item["file"] == "12-playground-320-large-dark.png")
    require(large["screen_width_dp"] == 320 and large["font_scale"] >= 1.99 and large["dark"] is True, "actual Android narrow/large/dark configuration missing")
    require(all(item["dark"] is False for item in light["pictures"]) and all(item["dark"] is True for item in dark["pictures"]), "actual Android color modes missing")
    return pictures

def apk_certificate(apk):
    eocd = apk.rfind(b"PK\x05\x06")
    require(eocd >= 0, "APK EOCD absent")
    cd = struct.unpack_from("<I", apk, eocd + 16)[0]
    require(apk[cd-16:cd] == b"APK Sig Block 42", "APK signing block absent")
    size = struct.unpack_from("<Q", apk, cd-24)[0]
    start = cd-size-8
    require(start >= 0 and struct.unpack_from("<Q", apk, start)[0] == size, "invalid APK block size")
    def lp(raw, offset=0):
        length = struct.unpack_from("<I", raw, offset)[0]
        require(offset+4+length <= len(raw), "invalid APK length")
        return raw[offset+4:offset+4+length], offset+4+length
    pos = start+8
    while pos < cd-24:
        length = struct.unpack_from("<Q", apk, pos)[0]
        require(length >= 4 and pos+8+length <= cd-24, "invalid APK signing entry")
        if struct.unpack_from("<I", apk, pos+8)[0] == 0x7109871a:
            signers, _ = lp(apk[pos+12:pos+8+length])
            signer, _ = lp(signers)
            signed, _ = lp(signer)
            _, offset = lp(signed)
            certificates, _ = lp(signed, offset)
            certificate, _ = lp(certificates)
            return p.digest(certificate)
        pos += 8+length
    raise ValueError("APK v2 certificate absent")

def read_job_log(api, job):
    with api.opener.open(api.request("/repos/"+p.REPOSITORY+"/actions/jobs/"+str(job["id"])+"/logs"), timeout=90) as response:
        raw = response.read(16*1024*1024+1)
    require(len(raw) <= 16*1024*1024, "oversized job log")
    return raw.decode("utf-8")

def download_baseline(api, value, directory):
    metadata = api.get("/repos/"+p.REPOSITORY+"/actions/artifacts/"+str(value["id"]))
    require(metadata["size_in_bytes"] == value["bytes"] and metadata["digest"] == value["digest"], "baseline artifact metadata changed")
    require(metadata["workflow_run"]["head_sha"] == value["source"], "baseline artifact source mismatch")
    path = directory/(str(value["id"])+".zip")
    api.download(metadata, path)
    with p.read_zip(path) as archive:
        require(archive.testzip() is None, "baseline archive CRC failed")
    return path

def binary_smoke(binary, output):
    with tempfile.TemporaryDirectory(prefix="modelatlas-audit-") as temp:
        directory = Path(temp)
        database = directory/"workspace.db"
        ready = directory/"ready.json"
        env = dict(os.environ)
        for key in ("MODELATLAS_LISTEN", "MODELATLAS_PUBLIC_ORIGIN", "MODELATLAS_MAINTENANCE_FILE"):
            env.pop(key, None)
        token = secrets.token_urlsafe(36)
        env.update(MODELATLAS_DEPLOYMENT="desktop", MODELATLAS_DB=str(database),
                   MODELATLAS_DESKTOP_READY=str(ready), MODELATLAS_ADMIN_TOKEN=token,
                   MODELATLAS_MASTER_KEY=base64.b64encode(os.urandom(32)).decode(),
                   MODELATLAS_FACTORY_DEFAULTS="1",
                   HTTP_PROXY="http://127.0.0.1:9", HTTPS_PROXY="http://127.0.0.1:9", NO_PROXY="127.0.0.1,localhost")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def start():
            began = time.monotonic()
            process = subprocess.Popen([str(binary)], env=env, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                while not ready.exists():
                    require(process.poll() is None and time.monotonic()-began < 30, "packaged binary failed readiness")
                    time.sleep(0.02)
                address = json.loads(ready.read_text())["url"]
                require(address.startswith("http://127.0.0.1:"), "non-loopback binary listener")
                return process, address, time.monotonic()-began
            except BaseException:
                if process.stdin:
                    process.stdin.close()
                process.wait(timeout=20)
                raise
        def stop(process):
            process.stdin.close()
            require(process.wait(timeout=20) == 0 and not ready.exists(), "packaged parent-pipe shutdown failed")
        def call(address, path, method="GET", data=None):
            payload = None if data is None else json.dumps(data).encode()
            request = urllib.request.Request(address+"/api/v1"+path, data=payload, method=method,
                headers={"Authorization": "Bearer "+token, "Content-Type": "application/json"})
            with opener.open(request, timeout=20) as response:
                return json.load(response)
        process, address, seconds = start()
        try:
            rows = call(address, "/my-models")
            active = [row for row in rows if row["state"] == "ACTIVE"]
            require(len(active) == 15 and all(row["provider_id"] == "builtin-kilo-anonymous" for row in active), "packaged factory defaults missing")
            selected = next(row for row in active if row["model_id"] == "nvidia/nemotron-3.5-lightning:free")
            scope = {key:selected[key] for key in ("provider_id", "account_id", "credential_id", "model_id", "group", "endpoint")}
            call(address, "/my-models", "DELETE", scope)
        finally:
            stop(process)
        process, address, _ = start()
        try:
            require(len([x for x in call(address, "/my-models") if x["state"] == "ACTIVE"]) == 14, "removed default resurrected after restart")
            call(address, "/defaults/kilo", "POST", {"confirm":True})
            require(len([x for x in call(address, "/my-models") if x["state"] == "ACTIVE"]) == 14, "default addition resurrected member")
        finally:
            stop(process)
        with sqlite3.connect(database) as connection:
            empty = {table:connection.execute("SELECT COUNT(*) FROM "+table).fetchone()[0] for table in ("telemetry", "sessions", "research_runs")}
            require(all(value == 0 for value in empty.values()), "factory startup performed user work")
            require(connection.execute("SELECT COUNT(*) FROM credentials WHERE encrypted_key!=''").fetchone()[0] == 0, "factory startup wrote a secret")
            require(connection.execute("SELECT COUNT(*) FROM factory_defaults").fetchone()[0] == 1, "factory install ledger duplicated")
        return dict(startup_seconds=seconds, first_members=15, after_restart=14, after_explicit_add=14,
                    empty=empty, secret_count=0, install_ledgers=1, parent_pipe_shutdown="PASS")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wait-minutes", type=int, default=32)
    args = parser.parse_args()
    sha = os.environ["MODELATLAS_SOURCE_COMMIT"]
    require(os.environ["MODELATLAS_REPOSITORY"] == p.REPOSITORY and re.fullmatch("[a-f0-9]{40}", sha), "explicit repository/source required")
    require(subprocess.check_output(["git","rev-parse","HEAD"], text=True).strip() == sha, "audit checkout mismatch")
    config = json.loads((ROOT/"release/candidate_audit_scope.json").read_text())
    browser_scopes = browser_image_scopes(config)
    validate_browser_screenshot_names(browser_scopes["candidate"][1])
    native_aqua_views = native_aqua_image_scope(config)
    out = ROOT/"candidate-audit-output"
    out.mkdir()
    actual = out/"actual"
    actual.mkdir()
    baselines = out/"baselines"
    baselines.mkdir()
    api = p.GitHub(os.environ["GH_TOKEN"])
    checks = p.wait_for_ci(api, sha, args.wait_minutes)
    state = {"sha":sha, "tree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"], text=True).strip(), "runs":[]}
    metadata = {}
    provenance = []
    for workflow, run in checks.items():
        run_raw = api.get("/repos/"+p.REPOSITORY+"/actions/runs/"+str(run["id"]))
        jobs_raw = api.pages("/repos/"+p.REPOSITORY+"/actions/runs/"+str(run["id"])+"/jobs?filter=latest", "jobs")
        artifacts_raw = api.pages("/repos/"+p.REPOSITORY+"/actions/runs/"+str(run["id"])+"/artifacts", "artifacts")
        state["runs"].append({"run":run_raw,"jobs":jobs_raw,"artifacts":artifacts_raw})
        selected = p.select_artifacts(workflow, run, artifacts_raw, sha)
        for name, item in selected.items():
            api.download(item, actual/(name+".zip"))
            with p.read_zip(actual/(name+".zip")) as archive:
                require(archive.testzip() is None, "current artifact CRC failed")
            metadata[name] = item
            provenance.append(p.artifact_provenance(workflow, run, item))
    require(len(metadata) == 9, "all nine actual archives required")
    (out/"source-state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2)+"\n")
    class Downloaded:
        def pages(self, path, key):
            runid = int(path.split("/runs/")[1].split("/")[0])
            return next(row["artifacts"] for row in state["runs"] if row["run"]["id"] == runid)
        def download(self, item, path):
            shutil.copyfile(actual/(item["name"]+".zip"), path)
    package = out/"package-check"
    package.mkdir()
    p.collect(Downloaded(), sha, checks, package)
    report = {"status":"BYTE_AUDIT_PASS_PENDING_VISUAL_REVIEW", "slice":config["slice"], "source":sha,
              "tree":state["tree"], "checks":checks, "artifacts":provenance, "whole_next_release":"PARTIAL"}
    def archive(name):
        return p.read_zip(actual/(name+".zip"))
    images = []
    def pngs(z, count):
        from PIL import Image
        names = [n for n in z.namelist() if n.endswith(".png")]
        require(len(names) == count and len({Path(n).name for n in names}) == count, "screenshot inventory mismatch")
        for name in names:
            with Image.open(io.BytesIO(z.read(name))) as image:
                image.verify()
        return names
    baseline_browser = download_baseline(api, config["browser_baseline"], baselines)
    accepted_cost = download_baseline(api, config["accepted_cost_audit"], baselines)
    with zipfile.ZipFile(accepted_cost) as z:
        cost_events = json.loads(p.member(z,"go-case-events.json"))
        cost_report = json.loads(p.member(z,"audit.json"))
        require(cost_report["source"] == config["accepted_cost_audit"]["source"] and len(cost_events) == 641, "accepted cost baseline missing")
        require(set(cost_report["browser_images"]["added"]) == set(config["accepted_browser_views"]), "accepted cost browser inventory changed")
    accepted_execution = download_baseline(api, config["accepted_execution_audit"], baselines)
    with zipfile.ZipFile(accepted_execution) as z:
        execution_events = json.loads(p.member(z,"go-case-events.json"))
        execution_report = json.loads(p.member(z,"audit.json"))
        require(execution_report["source"] == config["accepted_execution_audit"]["source"] and len(execution_events) == 650, "accepted execution baseline missing")
        require(set(execution_report["browser_images"]["added"]) == set(config["accepted_browser_views"])|set(config["accepted_execution_views"]), "accepted execution inventory changed")
    accepted_aqua = download_baseline(api, config["accepted_aqua_audit"], baselines)
    with zipfile.ZipFile(accepted_aqua) as z:
        aqua_events = json.loads(p.member(z,"go-case-events.json"))
        aqua_report = json.loads(p.member(z,"audit.json"))
        require(aqua_report["source"] == config["accepted_aqua_audit"]["source"] and len(aqua_events) == 655, "accepted Aqua baseline missing")
        require(set(aqua_report["browser_images"]["added"]) == set(config["accepted_browser_views"])|set(config["accepted_execution_views"])|set(config["accepted_aqua_views"]), "accepted Aqua inventory changed")
    accepted_history = download_baseline(api, config["accepted_history_audit"], baselines)
    with zipfile.ZipFile(accepted_history) as z:
        history_events = json.loads(p.member(z,"go-case-events.json"))
        history_report = json.loads(p.member(z,"audit.json"))
        require(history_report["source"] == config["accepted_history_audit"]["source"] and len(history_events) == 660, "accepted history baseline missing")
        require(set(history_report["browser_images"]["added"]) == set(config["accepted_history_views"]), "accepted history inventory changed")
        require({Path(name).name for name in history_report["visual_review_required"] if name.startswith("mac-arm64/research/")} == set(config["new_mac_research_images"]), "accepted native research views changed")
    with zipfile.ZipFile(baseline_browser) as z:
        prior_names = {Path(n).name for n in z.namelist() if n.endswith(".png")}
    with archive("modelatlas-vps-browser") as z:
        expected_count, expected_views = browser_scopes["candidate"]
        names = pngs(z, expected_count)
        added = {Path(n).name for n in names}-prior_names
        require(len(prior_names) == 509 and not prior_names-{Path(n).name for n in names} and added == expected_views, "original browser images were removed or private mapping views changed")
        validate_browser_screenshot_names(added)
        for name in names:
            if Path(name).name in added or Path(name).name.startswith("workspace-chunks-"):
                images.append(("browser/"+Path(name).name, z.read(name)))
        report["browser_images"] = {"count":expected_count,"preserved":509,"added":sorted(added)}
    baseline = json.loads((ROOT/"docs/verification/20261026/workspace-chunks-baseline.json").read_text())
    original_events = baseline["go_case_events"]
    require(len(original_events) == 604, "accepted Go baseline missing")
    new_functions = go_test_functions(ROOT/"vps") - {name for name in original_events if "/" not in name}
    require(new_functions == {name for name in config["go_new_events"] if "/" not in name}, "research test registry changed; review audit scope")
    expected_registry = {name for name in original_events if "/" not in name}|new_functions
    expected_events = {**original_events, **config["go_new_events"]}
    require(all(expected_events.get(name) == action for name,action in cost_events.items()), "accepted cost case removed or changed")
    require(all(expected_events.get(name) == action for name,action in execution_events.items()), "accepted execution case removed or changed")
    require(all(expected_events.get(name) == action for name,action in aqua_events.items()), "accepted Aqua case removed or changed")
    require(all(expected_events.get(name) == action for name,action in history_events.items()), "accepted history case removed or changed")
    with archive("modelatlas-vps-footprint") as z:
        footprint = json.loads(p.member(z,"footprint.json"))
        expected = dict(export_messages=11000,export_requests=100008,long_context_bytes_each=31*1024*1024,
                        long_context_http=4,image_pixels_each=2048**2,speech_bytes_each=4*1024*1024,media_http=8)
        require(all(footprint[k] == value for k,value in expected.items()), "original resource workload changed")
        require(footprint["peak_rss_bytes"] <= footprint["rss_target_bytes"] == 512*1024*1024, "resource RSS target exceeded")
        report["footprint"] = footprint
        suite = json.loads(p.member(z,"go-suite.json"))
        require(suite["result"] == "PASS" and suite["source"] == sha and suite["working_tree_dirty"] is False and suite["race"] is True, "Go suite source/result mismatch")
        require(suite["suite_deadline_seconds"] == 600 and suite["seconds"] < 600, "original suite deadline changed")
        require(set(suite["registry"]) == expected_registry and len(suite["registry"]) == len(expected_registry), "original Go registry changed")
        require(p.digest("\n".join(sorted(suite["registry"])).encode()) == suite["registry_sha256"], "registry hash mismatch")
        sys.path.insert(0, str(ROOT/"vps"))
        from test_go import verify
        verify(suite["registry"], suite["processes"])
        require(sorted(len(x["selected"]) for x in suite["processes"]) == [len(expected_registry)//2,(len(expected_registry)+1)//2], "original two-process registry partition changed")
        actions = [value for process in suite["processes"] for value in process["completed"].values()]
        require(all(actions.count(action) == sum(expected_events[name] == action for name in expected_registry) for action in ("pass","skip")), "Go terminal registry outcomes changed")
        report["go_suite"] = {k:v for k,v in suite.items() if k not in ("processes","registry")}
    vps_jobs = checks[".github/workflows/vps.yml"]["jobs"]
    go_log = read_job_log(api, next(job for job in vps_jobs if job["name"] == "vps-test"))
    events = {}
    for line in go_log.splitlines():
        index = line.find("{")
        if index < 0:
            continue
        try:
            item = json.loads(line[index:])
        except json.JSONDecodeError:
            continue
        if item.get("Test") and item.get("Action") in ("pass","skip","fail"):
            require(item["Test"] not in events, "duplicate terminal Go case")
            events[item["Test"]] = item["Action"]
    require(events == expected_events, "complete Go case outcomes missing")
    require(all(events.get(name) == action for name,action in original_events.items()), "accepted Go case outcome regressed")
    (out/"go-case-events.json").write_text(json.dumps(events, indent=2)+"\n")
    browser_log = read_job_log(api, next(job for job in vps_jobs if job["name"] == "vps-browser"))
    wait_cases = re.findall(r"\bok \d+ - ([^\r\n]+)", browser_log)
    expected_wait_cases = re.findall(r"^test\('([^']+)'", (ROOT/"vps/e2e/async-wait.test.cjs").read_text(), re.M)
    require(len(expected_wait_cases) == 8 and sorted(wait_cases) == sorted(expected_wait_cases) and all(re.search(r"# "+key+r" 0\s*$",browser_log,re.M) for key in ("fail","cancelled","skipped","todo")), "async Browser wait regressions missing")
    report["async_wait_cases"] = wait_cases
    browser_cases = re.findall(r"--- PASS: (TestBrowser\w+) \(([0-9.]+)s\)", browser_log)
    require(len(browser_cases) == 44 and len({x[0] for x in browser_cases}) == 44, "complete browser results missing")
    require({x[0] for x in browser_cases} == {name for name in expected_registry if name.startswith("TestBrowser")}, "browser registry mismatch")
    report["browser_cases"] = browser_cases
    prior_core = download_baseline(api, config["core_inventory"], baselines)
    with zipfile.ZipFile(prior_core) as z:
        old_core_counts, old_core_cases = xml_cases(z)
        require(old_core_counts == dict(tests=594,failures=0,errors=0,skipped=0), "accepted core baseline changed")
    with archive("modelatlas-debug-and-reports") as z:
        counts, core_cases = xml_cases(z)
        require(counts == dict(tests=594,failures=0,errors=0,skipped=0), "Android core JVM outcomes changed")
        require(core_cases == old_core_cases, "accepted core case removed or regressed")
        html = p.member(z,"reports/tests/testDebugUnitTest/index.html").decode()
        actual_counts = {key:int(re.search(r'id="'+key+r'">\s*<div class="counter">(\d+)</div>', html).group(1)) for key in ("tests","failures","ignored")}
        require(actual_counts == dict(tests=423,failures=0,ignored=0), "Android app JVM outcomes changed")
        meta = json.loads(p.member(z,"android.json"))
        apk = p.member(z,"debug/app-debug.apk")
        require(apk_certificate(apk) == meta["certificate_sha256"], "actual APK certificate differs from metadata")
        with zipfile.ZipFile(io.BytesIO(apk)) as app:
            require(app.read("assets/kilo-anonymous.json") == (ROOT/"vps/defaults/kilo-anonymous.json").read_bytes(), "APK factory snapshot mismatch")
            require(app.read("assets/wallpapers/manifest.json") == (ROOT/"vps/assets/aqua-wallpapers-v1.json").read_bytes(), "APK artwork manifest changed")
            for image in json.loads((ROOT/"vps/assets/aqua-wallpapers-v1.json").read_text())["images"]:
                require(app.read("assets/wallpapers/"+image["file"]) == (ROOT/"vps/assets"/image["file"]).read_bytes(), "APK artwork bytes changed")
        report["android_build"] = {"core":counts,"app":actual_counts,"certificate_sha256":meta["certificate_sha256"],"production_signing":False}
    prior_device = download_baseline(api, config["device_inventory"], baselines)
    with zipfile.ZipFile(prior_device) as z:
        old_counts, old_cases = xml_cases(z)
        require(old_counts == dict(tests=996,failures=0,errors=0,skipped=5), "original test inventory source changed")
    with archive("modelatlas-device-test-reports") as z:
        counts, cases = xml_cases(z)
        accepted_metadata = json.loads((ROOT/"docs/verification/20261028/android-metadata-baseline.json").read_text())
        require(accepted_metadata["source"] == "3c37fd07b0f7b0dc4935bc16db1a5f901025b338" and accepted_metadata["status"] == "ACCEPTED_LISTED_SCOPE", "accepted Android metadata baseline missing")
        added_device_cases = validate_android_aqua_case_extension(counts, cases, old_cases, accepted_metadata["device_case_events"], config)
        (out/"device-case-events.json").write_text(json.dumps(cases, indent=2)+"\n")
        (out/"core-case-events.json").write_text(json.dumps(core_cases, indent=2)+"\n")
        runtime = json.loads(p.member(z,"instrumentation-runtime.json"))
        preflight = json.loads(p.member(z,"emulator-preflight.json"))
        require(runtime["max_heap_bytes"] == runtime["expected_max_heap_bytes"] == 536870912 and runtime["stages"] == ["start","finish"] and runtime["pid"] > 0, "actual ART runtime heap/PID proof absent")
        require(preflight["configured_heap"] == "512m" and preflight["sdk"] == "29" and preflight["zygote_before"] != preflight["zygote_after"], "cold-boot proof absent")
        report["android_device"] = {"counts":counts,"preserved_cases":997,"added":added_device_cases,"runtime":runtime,"preflight":preflight}
        expected_android_images = android_aqua_image_scope(config)
        android_names = pngs(z,15)
        require({Path(name).name for name in android_names} == expected_android_images, "Android Aqua actual images missing")
        light = json.loads(p.member(z,"aqua-ui-light.json"))
        dark = json.loads(p.member(z,"aqua-ui-dark-large.json"))
        receipts = validate_android_aqua_reports(light,dark,expected_android_images)
        for item in receipts:
            raw = p.member(z,"aqua-ui/"+item["file"])
            require(p.digest(raw) == item["sha256"], "Android Aqua image receipt mismatch")
            images.append(("android/aqua/"+item["file"],raw))
        report["android_aqua"] = {"light":light,"dark_large":dark,"screenshots":15,"original_asset_bytes":True}
    failed = download_baseline(api, config["rejected_device"], baselines)
    with zipfile.ZipFile(failed) as z:
        negative_counts, negative_cases = xml_cases(z)
        require(negative_counts == dict(tests=985,failures=1,errors=0,skipped=5), "rejected candidate XML changed")
        failed_name = "dev.modelatlas.app.ui.PendingConnectionHandoffTest#disabledPendingIsEvictedAndNeverResurrectsAsUsable"
        require(negative_cases[failed_name] == "failure", "original handoff counterexample absent")
        report["rejected_candidate"] = {"source":config["rejected_device"]["source"],"actual_sha256":config["rejected_device"]["digest"],"counts":negative_counts,"failure":failed_name}
    rejected_browser = download_baseline(api, config["rejected_aa_browser"], baselines)
    with zipfile.ZipFile(rejected_browser) as z:
        expected_count, expected_views = browser_scopes["rejected_aa"]
        rejected_names = pngs(z, expected_count)
        require(expected_views <= {Path(n).name for n in rejected_names}, "rejected private reader images missing")
        aa_names = {Path(n).name for n in rejected_names if Path(n).name.startswith("research-aa-")}
        require(len(aa_names) == 8 and "research-aa-recovered-375.png" in aa_names, "rejected AA image inventory changed")
        report["rejected_aa_candidate"] = {"source":config["rejected_aa_browser"]["source"],"actual_sha256":config["rejected_aa_browser"]["digest"],"png":523,"aa_images":sorted(aa_names),"failure":"TestBrowserAAResearchPageRecoveryWorkflow: page1=3/page2=3, expected 3/4; original task failure cause unavailable, not inferred"}
    for arch in ("arm64","x86_64"):
        prior_mac = download_baseline(api, config["mac_inventory"][arch], baselines)
        with p.read_zip(prior_mac) as old:
            old_images = {Path(n).name for n in pngs(old, 96)}
            original_mac_cases = {x[2] for x in re.findall(r"\[(\d+)/(\d+)\] Testing ([^\n]+)", p.member(old,"swift-tests.log").decode())}
            require(len(original_mac_cases) == 85, "accepted Mac test inventory changed")
        with archive("ModelAtlas-macOS-QA-"+arch) as z:
            actual_images = {Path(n).name for n in pngs(z, 104+len(native_aqua_views))}
            require(old_images <= actual_images and actual_images-old_images == set(config["new_mac_research_images"])|native_aqua_views, "original Mac images removed or new native views missing")
            smoke = json.loads(p.member(z,"ui/native-smoke.json"))
            parity = json.loads(p.member(z,"parity-ui/native-parity.json"))
            require(smoke["result"] == parity["result"] == "PASS" and smoke["screenshots"] == 25 and parity["screenshots"] == 57, "native Mac proof missing")
            require(parity["kilo_factory_defaults"] is True and parity["kilo_no_automatic_inference"] is True, "native factory caller missing")
            transfer = json.loads(p.member(z,"parity-ui/workspace-transfer.diagnostics.json"))
            require(parity["workspace_transfer_controls"] is True and transfer["result"] == "PASS" and transfer["destination_models"] == 15 and transfer["paused_progress_bytes"] > 0 and transfer["outgoing_acked"] > 0, "native transfer receipts missing")
            require(all(transfer[x] is True for x in ("same_transfer_after_restart","pause_during_busy","actual_keychain_restart","source_product_retained","no_inference")), "native transfer controls/restart failed")
            cases = re.findall(r"\[(\d+)/(\d+)\] Testing ([^\n]+)", p.member(z,"swift-tests.log").decode())
            require(len(cases) == 89 and {int(x[0]) for x in cases} == set(range(1,90)) and {int(x[1]) for x in cases} == {89} and len({x[2] for x in cases}) == 89, "Mac core cases missing")
            actual_mac_cases = {x[2] for x in cases}
            require(original_mac_cases <= actual_mac_cases and len(actual_mac_cases-original_mac_cases) == 4 and actual_mac_cases-original_mac_cases == set(config["accepted_native_research_cases"]), "original Mac core case removed or replaced")
            require(sum("KiloFactoryTests/" in x[2] for x in cases) == 3, "native factory tests missing")
            selected = [n for n in z.namelist() if n.endswith(".png") and Path(n).name.startswith(("52-kilo-","53-kilo-","54-workspace-","55-workspace-","56-workspace-","57-workspace-"))]
            require(len(selected) == 6, "Mac factory screenshot inventory changed")
            images.extend(("mac-"+arch+"/"+Path(n).name,z.read(n)) for n in selected)
            research = json.loads(p.member(z,"research-ui/native-research.json"))
            require(research["result"] == "PASS" and research["screenshots"] == 8, "native research UI proof missing")
            require(all(research[x] is True for x in ("real_tls","native_update_buttons","live_run_state","last_good_preserved","saved_refresh_no_repost","lost_ack_no_repost","concurrent_control_guard","reopen_same_run","seven_exact_requests","no_inference")), "native research recovery failed")
            require(research["wire"] == dict(first=3,second=4,starts=3,controls=3,resumes=2,direct_sync=0,held=False,used=7,runs=3,telemetry=0), "native AA request or mutation counts changed")
            resource = json.loads(p.member(z,"research-footprint.json"))
            require(all(resource[x] > 0 for x in ("app_rss_bytes","engine_rss_bytes","fixture_rss_bytes")) and resource["samples"] >= 3 and resource["app_exit_code"] == resource["fixture_exit_code"] == 0 and resource["owned_engine_exited"] is True, "native research process lifecycle proof missing")
            native_images = [n for n in z.namelist() if "/research-ui/" in "/"+n and n.endswith(".png")]
            require({Path(n).name for n in native_images} == set(config["new_mac_research_images"]), "native research views changed")
            images.extend(("mac-"+arch+"/research/"+Path(n).name,z.read(n)) for n in native_images)
            aqua = json.loads(p.member(z,"aqua-ui/native-aqua.json"))
            require(aqua["result"] == "PASS" and aqua["screenshots"] == 13 and all(aqua[x] is True for x in ("actual_settings_buttons","four_bundled_artworks","same_original_bytes","classic_default_and_invalid_fallback","scope_and_draft_retained","provider_form_retained","relaunch_preference_retained","reduced_transparency","system_reduced_transparency","no_inference_or_research")), "native Aqua controls or persistence proof missing")
            require(aqua["transparency_control"] == "actual app toggle and separately launched system preference fixture", "native transparency control is not exercised")
            artwork = json.loads((ROOT/"macos/Resources/Wallpapers/manifest.json").read_text())["images"]
            require({(item["id"],item["sha256"],item["bytes"]) for item in aqua["images"]} == {(item["id"],item["sha256"],item["bytes"]) for item in artwork}, "native selected artwork differs from source")
            aqua_resource = json.loads(p.member(z,"aqua-footprint.json"))
            require(aqua_resource["result"] == "PASS" and aqua_resource["owned_engines_exited"] is True and len(aqua_resource["phases"]) == 3 and len({x["app_pid"] for x in aqua_resource["phases"]}) == len({x["engine_pid"] for x in aqua_resource["phases"]}) == 3 and all(x["exit"] == 0 for x in aqua_resource["phases"]) and aqua_resource["samples"] >= 3 and aqua_resource["app_rss_bytes"] > 0 and aqua_resource["engine_rss_bytes"] > 0, "native Aqua relaunch process lifecycle missing")
            validate_native_transparency_fixture(aqua_resource, json.loads(p.member(z,"aqua-ui/system-transparency-fixture.json")))
            aqua_images = [n for n in z.namelist() if "/aqua-ui/" in "/"+n and n.endswith(".png")]
            require({Path(n).name for n in aqua_images} == native_aqua_views, "native Aqua views missing or replaced")
            images.extend(("mac-"+arch+"/aqua/"+Path(n).name,z.read(n)) for n in aqua_images)
            report["mac_"+arch] = {"png":104+len(native_aqua_views),"preserved_png":104,"core_tests":89,"preserved_core_tests":89,"smoke":smoke,"parity":parity,"workspace_transfer":transfer,"research":research,"research_resource":resource,"aqua":aqua,"aqua_resource":aqua_resource}
    with archive("ModelAtlas-macOS-Universal") as z:
        with zipfile.ZipFile(io.BytesIO(p.member(z,"ModelAtlas-macOS-Universal.zip"))) as app:
            for item in json.loads((ROOT/"macos/Resources/Wallpapers/manifest.json").read_text())["images"]:
                raw = app.read("ModelAtlas.app/Contents/Resources/Wallpapers/"+item["file"])
                require(len(raw) == item["bytes"] and p.digest(raw) == item["sha256"], "packaged native artwork changed")
            for name in ("ModelAtlas","ModelAtlasEngine"):
                raw = app.read("ModelAtlas.app/Contents/MacOS/"+name)
                require(raw[:4] == b"\xca\xfe\xba\xbe", "non-universal Mac binary")
                count = struct.unpack_from(">I",raw,4)[0]
                require({struct.unpack_from(">I",raw,8+20*i)[0] for i in range(count)} == {0x1000007,0x100000c}, "missing Mac binary architecture")
                if name == "ModelAtlasEngine":
                    require(raw.count((ROOT/"vps/defaults/kilo-anonymous.json").read_bytes()) >= 2, "Mac engine factory snapshot absent")
    for arch in ("amd64","arm64"):
        files, version = p.linux_bundle((package/("modelatlas-vps-linux-"+arch+".tar.gz")).read_bytes(), arch, sha, p.properties()["tag"])
        require(version["schema"] == 38 and set(files) == linux_inventory(ROOT/"vps", arch, config), "Linux bundle scope mismatch")
        report["linux_"+arch] = {"schema":38,"files":len(files),"version":version}
    report["actual_binary"] = binary_smoke(package/"linux-check/bin/modelatlas-vps-linux-amd64", out)
    frozen = config["frozen"]
    release = api.get("/repos/"+p.REPOSITORY+"/releases/"+str(frozen["release_id"]))
    tag = api.get("/repos/"+p.REPOSITORY+"/git/ref/tags/v0.8.5-preview.1")
    tuples = lambda rows: sorted([[x["id"],x["name"],x["size"],x["digest"]] for x in rows], key=lambda row:row[1])
    require(release["id"] == frozen["release_id"] and tag["object"]["sha"] == frozen["tag"] and tuples(release["assets"]) == sorted(frozen["assets"],key=lambda row:row[1]), "frozen public release changed")
    main_sha = api.get("/repos/"+p.REPOSITORY+"/branches/main")["commit"]["sha"]
    main_runs = api.pages("/repos/"+p.REPOSITORY+"/actions/runs?head_sha="+main_sha, "workflow_runs")
    main_run = p.select_run(main_runs, ".github/workflows/android.yml", main_sha)
    require(main_run is not None, "latest main has no Android CI")
    main_jobs = api.pages("/repos/"+p.REPOSITORY+"/actions/runs/"+str(main_run["id"])+"/jobs?filter=latest", "jobs")
    report["main"] = {"sha":main_sha,"run":main_run["id"],"jobs":p.verified_jobs(main_run,main_jobs,{"build","device-tests"},main_sha)}
    report["frozen"] = frozen
    from PIL import Image, ImageDraw, ImageOps
    contacts = out/"contacts"
    contacts.mkdir()
    require(len(images) == candidate_visual_review_count(config), "all original views plus Android Aqua views required")
    report["visual_review_required"] = [name for name,_ in images]
    for offset in range(0,len(images),4):
        batch = images[offset:offset+4]
        canvas = Image.new("RGB",(1040,1160),"#ddd")
        draw = ImageDraw.Draw(canvas)
        for index,(name,data) in enumerate(batch):
            left = (index%2)*520
            top = (index//2)*580
            draw.text((left+8,top+8),name,fill="black")
            with Image.open(io.BytesIO(data)) as original:
                thumb = ImageOps.contain(original.convert("RGB"),(504,542))
                canvas.paste(thumb,(left+8,top+30))
        path = contacts/("review-"+str(offset//4+1)+".jpg")
        canvas.save(path,"JPEG",quality=82,optimize=True)
        print("MODELATLAS_AUDIT_CONTACT "+path.name+" "+base64.b64encode(path.read_bytes()).decode(), flush=True)
    (out/"audit.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    # Emit the same complete artifacts-derived evidence for read-only review when
    # the interactive workspace is offline. No tests or archive checks are skipped.
    for name, value in (("REPORT", report), ("SOURCE_STATE", state), ("GO_CASE_EVENTS", events), ("DEVICE_CASE_EVENTS", json.loads((out/"device-case-events.json").read_text())), ("CORE_CASE_EVENTS", core_cases)):
        print("MODELATLAS_AUDIT_"+name+" "+json.dumps(value, ensure_ascii=False, separators=(",",":")), flush=True)
    print("MODELATLAS_AUDIT_RESULT "+json.dumps({"source":sha,"tree":state["tree"],"status":report["status"],"artifacts":len(metadata),"device_tests":report["android_device"]["counts"]["tests"],"go_cases":len(events),"browser_cases":44,"visual_views":len(images),"public_assets":11,"whole_next_release":"PARTIAL"}), flush=True)

if __name__ == "__main__":
    main()
