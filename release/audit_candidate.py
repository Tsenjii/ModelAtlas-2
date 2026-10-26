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
    with zipfile.ZipFile(baseline_browser) as z:
        prior_names = {Path(n).name for n in z.namelist() if n.endswith(".png")}
    with archive("modelatlas-vps-browser") as z:
        names = pngs(z, 509)
        added = {Path(n).name for n in names}-prior_names
        require(len(prior_names) == 501 and not prior_names-{Path(n).name for n in names} and len(added) == 8, "original browser images were removed")
        require(all(name.startswith("workspace-chunks-") and "failure" not in name for name in added), "unexpected new screenshot")
        for name in names:
            if Path(name).name in added:
                images.append(("browser/"+Path(name).name, z.read(name)))
        report["browser_images"] = {"count":509,"preserved":501,"added":sorted(added)}
    baseline = json.loads((ROOT/"docs/verification/20261025/field-mapping-baseline.json").read_text())
    original_events = {row["test"]:row["action"] for row in baseline["verification"]["go_case_events"]}
    require(len(original_events) == 586, "accepted Go baseline missing")
    new_functions = set()
    for path in (ROOT/"vps").glob("workspace_chunks*test.go"):
        new_functions.update(re.findall(r"^func (Test\w+)\(", path.read_text(), re.M))
    require(len(new_functions) == 12, "chunk test registry changed; review audit scope")
    expected_registry = {name for name in original_events if "/" not in name}|new_functions
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
        require(set(suite["registry"]) == expected_registry and len(suite["registry"]) == 407, "original Go registry changed")
        require(p.digest("\n".join(sorted(suite["registry"])).encode()) == suite["registry_sha256"], "registry hash mismatch")
        sys.path.insert(0, str(ROOT/"vps"))
        from test_go import verify
        verify(suite["registry"], suite["processes"])
        require(sorted(len(x["selected"]) for x in suite["processes"]) == [203,204], "original registry partition changed")
        actions = [value for process in suite["processes"] for value in process["completed"].values()]
        require(actions.count("pass") == 359 and actions.count("skip") == 48, "Go terminal registry outcomes changed")
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
    require(len(events) == 604 and list(events.values()).count("pass") == 556 and list(events.values()).count("skip") == 48, "complete Go case outcomes missing")
    require(all(events.get(name) == action for name,action in original_events.items()), "accepted Go case outcome regressed")
    (out/"go-case-events.json").write_text(json.dumps(events, indent=2)+"\n")
    browser_log = read_job_log(api, next(job for job in vps_jobs if job["name"] == "vps-browser"))
    browser_cases = re.findall(r"--- PASS: (TestBrowser\w+) \(([0-9.]+)s\)", browser_log)
    require(len(browser_cases) == 41 and len({x[0] for x in browser_cases}) == 41, "complete browser results missing")
    require({x[0] for x in browser_cases} == {name for name in expected_registry if name.startswith("TestBrowser")}, "browser registry mismatch")
    report["browser_cases"] = browser_cases
    prior_core = download_baseline(api, config["core_inventory"], baselines)
    with zipfile.ZipFile(prior_core) as z:
        old_core_counts, old_core_cases = xml_cases(z)
        require(old_core_counts == dict(tests=585,failures=0,errors=0,skipped=0), "accepted core baseline changed")
    with archive("modelatlas-debug-and-reports") as z:
        counts, core_cases = xml_cases(z)
        expected_core = {"dev.modelatlas.core.workspace.WorkspaceChunksTest#"+name for name in re.findall(r"@Test fun (\w+)\(", (ROOT/"core/src/test/kotlin/dev/modelatlas/core/workspace/WorkspaceChunksTest.kt").read_text())}
        require(len(expected_core)==9 and counts == dict(tests=594,failures=0,errors=0,skipped=0), "Android core JVM outcomes changed")
        require(set(core_cases)-set(old_core_cases)==expected_core and all(core_cases.get(key)==action for key,action in old_core_cases.items()), "accepted core case removed or regressed")
        html = p.member(z,"reports/tests/testDebugUnitTest/index.html").decode()
        actual_counts = {key:int(re.search(r'id="'+key+r'">\s*<div class="counter">(\d+)</div>', html).group(1)) for key in ("tests","failures","ignored")}
        require(actual_counts == dict(tests=423,failures=0,ignored=0), "Android app JVM outcomes changed")
        meta = json.loads(p.member(z,"android.json"))
        apk = p.member(z,"debug/app-debug.apk")
        require(apk_certificate(apk) == meta["certificate_sha256"], "actual APK certificate differs from metadata")
        with zipfile.ZipFile(io.BytesIO(apk)) as app:
            require(app.read("assets/kilo-anonymous.json") == (ROOT/"vps/defaults/kilo-anonymous.json").read_bytes(), "APK factory snapshot mismatch")
        report["android_build"] = {"core":counts,"app":actual_counts,"certificate_sha256":meta["certificate_sha256"],"production_signing":False}
    prior_device = download_baseline(api, config["device_inventory"], baselines)
    with zipfile.ZipFile(prior_device) as z:
        old_counts, old_cases = xml_cases(z)
        require(old_counts == dict(tests=985,failures=0,errors=0,skipped=5), "original test inventory source changed")
    with archive("modelatlas-device-test-reports") as z:
        counts, cases = xml_cases(z)
        require(counts == dict(tests=996,failures=0,errors=0,skipped=5), "Android device outcomes changed")
        expected_new = set()
        for name in ("WorkspaceChunkRuntimeTest","WorkspaceChunkStorageTest"):
            methods = re.findall(r"@Test fun (\w+)\(", (ROOT/("app/src/androidTest/kotlin/dev/modelatlas/app/data/"+name+".kt")).read_text())
            expected_new.update("dev.modelatlas.app.data."+name+"#"+method for method in methods)
        require(len(expected_new)==11,"device chunk test inventory changed")
        require(set(cases)-set(old_cases) == expected_new and not set(old_cases)-set(cases), "original device cases removed")
        require(all(cases[key] == action for key,action in old_cases.items()), "original device outcome regressed")
        runtime = json.loads(p.member(z,"instrumentation-runtime.json"))
        preflight = json.loads(p.member(z,"emulator-preflight.json"))
        require(runtime["max_heap_bytes"] == runtime["expected_max_heap_bytes"] == 536870912 and runtime["stages"] == ["start","finish"] and runtime["pid"] > 0, "actual ART runtime heap/PID proof absent")
        require(preflight["configured_heap"] == "512m" and preflight["sdk"] == "29" and preflight["zygote_before"] != preflight["zygote_after"], "cold-boot proof absent")
        report["android_device"] = {"counts":counts,"preserved_cases":985,"added":sorted(expected_new),"runtime":runtime,"preflight":preflight}
    failed = download_baseline(api, config["rejected_device"], baselines)
    with zipfile.ZipFile(failed) as z:
        negative_counts, negative_cases = xml_cases(z)
        require(negative_counts == dict(tests=985,failures=1,errors=0,skipped=5), "rejected candidate XML changed")
        failed_name = "dev.modelatlas.app.ui.PendingConnectionHandoffTest#disabledPendingIsEvictedAndNeverResurrectsAsUsable"
        require(negative_cases[failed_name] == "failure", "original handoff counterexample absent")
        report["rejected_candidate"] = {"source":config["rejected_device"]["source"],"actual_sha256":config["rejected_device"]["digest"],"counts":negative_counts,"failure":failed_name}
    for arch in ("arm64","x86_64"):
        with archive("ModelAtlas-macOS-QA-"+arch) as z:
            pngs(z, 96)
            smoke = json.loads(p.member(z,"ui/native-smoke.json"))
            parity = json.loads(p.member(z,"parity-ui/native-parity.json"))
            require(smoke["result"] == parity["result"] == "PASS" and smoke["screenshots"] == 25 and parity["screenshots"] == 57, "native Mac proof missing")
            require(parity["kilo_factory_defaults"] is True and parity["kilo_no_automatic_inference"] is True, "native factory caller missing")
            transfer = json.loads(p.member(z,"parity-ui/workspace-transfer.diagnostics.json"))
            require(parity["workspace_transfer_controls"] is True and transfer["result"] == "PASS" and transfer["destination_models"] == 15 and transfer["paused_progress_bytes"] > 0 and transfer["outgoing_acked"] > 0, "native transfer receipts missing")
            require(all(transfer[x] is True for x in ("same_transfer_after_restart","pause_during_busy","actual_keychain_restart","source_product_retained","no_inference")), "native transfer controls/restart failed")
            cases = re.findall(r"\[(\d+)/(\d+)\] Testing ([^\n]+)", p.member(z,"swift-tests.log").decode())
            require(len(cases) == 85 and {int(x[0]) for x in cases} == set(range(1,86)) and {int(x[1]) for x in cases} == {85} and len({x[2] for x in cases}) == 85, "Mac original core cases changed")
            require(sum("KiloFactoryTests/" in x[2] for x in cases) == 3, "native factory tests missing")
            selected = [n for n in z.namelist() if n.endswith(".png") and Path(n).name.startswith(("52-kilo-","53-kilo-","54-workspace-","55-workspace-","56-workspace-","57-workspace-"))]
            require(len(selected) == 6, "Mac factory screenshot inventory changed")
            images.extend(("mac-"+arch+"/"+Path(n).name,z.read(n)) for n in selected)
            report["mac_"+arch] = {"png":96,"core_tests":85,"smoke":smoke,"parity":parity,"workspace_transfer":transfer}
    with archive("ModelAtlas-macOS-Universal") as z:
        with zipfile.ZipFile(io.BytesIO(p.member(z,"ModelAtlas-macOS-Universal.zip"))) as app:
            for name in ("ModelAtlas","ModelAtlasEngine"):
                raw = app.read("ModelAtlas.app/Contents/MacOS/"+name)
                require(raw[:4] == b"\xca\xfe\xba\xbe", "non-universal Mac binary")
                count = struct.unpack_from(">I",raw,4)[0]
                require({struct.unpack_from(">I",raw,8+20*i)[0] for i in range(count)} == {0x1000007,0x100000c}, "missing Mac binary architecture")
                if name == "ModelAtlasEngine":
                    require(raw.count((ROOT/"vps/defaults/kilo-anonymous.json").read_bytes()) >= 2, "Mac engine factory snapshot absent")
    for arch in ("amd64","arm64"):
        files, version = p.linux_bundle((package/("modelatlas-vps-linux-"+arch+".tar.gz")).read_bytes(), arch, sha, p.properties()["tag"])
        require(version["schema"] == 37 and len(files) == 46 and {"FIELD_MAPPING.md","WORKSPACE_CHUNKS.md"} <= set(files), "Linux bundle scope mismatch")
        report["linux_"+arch] = {"schema":37,"files":46,"version":version}
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
    require(len(images) == 18, "all new/browser and native factory views required")
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
    print("MODELATLAS_AUDIT_RESULT "+json.dumps({"source":sha,"tree":state["tree"],"status":report["status"],"artifacts":len(metadata),"device_tests":996,"go_cases":604,"browser_cases":41,"visual_views":len(images),"public_assets":11,"whole_next_release":"PARTIAL"}), flush=True)

if __name__ == "__main__":
    main()
