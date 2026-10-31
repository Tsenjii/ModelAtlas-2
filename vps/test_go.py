#!/usr/bin/env python3
"""Run the complete race-instrumented Go suite in two isolated processes.

Every registered test/example/fuzz seed runs exactly once. Both processes share
the original ten-minute deadline; no migration, fixture, workload or assertion
is cached or removed. The normal opt-in Browser job keeps its own harness.
"""
import asyncio
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent
LIMIT_SECONDS = 600


def partition(cases):
    ordered = sorted(cases)
    if not ordered or len(set(ordered)) != len(ordered):
        raise ValueError("empty or duplicate Go registry")
    groups = [ordered[::2], ordered[1::2]]
    if not all(groups):
        raise ValueError("both Go processes need registered cases")
    return groups


def verify(registry, reports):
    planned = [name for report in reports for name in report["selected"]]
    if Counter(planned) != Counter(registry):
        raise ValueError("Go process selections omit or repeat registered cases")
    for report in reports:
        expected = Counter(report["selected"])
        if report["returncode"] != 0 or report["errors"]:
            raise ValueError("Go process failed or emitted invalid events")
        if Counter(report["started"]) != expected or Counter(report["completed"].keys()) != expected:
            raise ValueError("Go process did not run and finish each selected case exactly once")
        if any(action not in ("pass", "skip") for action in report["completed"].values()):
            raise ValueError("Go case failed")


def new_report(index, selected):
    return {"index": index, "selected": selected, "started": [], "completed": {},
            "case_seconds": {}, "errors": [], "returncode": None,
            "unfinished": sorted(selected), "running": []}


async def read_events(process, report):
    selected = set(report["selected"])
    async for line in process.stdout:
        # Preserve the real Go events, including all diagnostics and races.
        sys.stdout.buffer.write(line)
        sys.stdout.buffer.flush()
        try:
            event = json.loads(line)
        except (ValueError, UnicodeDecodeError):
            report["errors"].append("non-JSON Go tool diagnostic")
            continue
        case = event.get("Test", "")
        if not case or "/" in case:
            continue
        if case not in selected:
            report["errors"].append("unselected top-level case: " + case)
        if event.get("Action") == "run":
            report["started"].append(case)
        if event.get("Action") in ("pass", "skip", "fail"):
            if case in report["completed"]:
                report["errors"].append("duplicate terminal event: " + case)
            report["completed"][case] = event["Action"]
            report["case_seconds"][case] = event.get("Elapsed")


def signal_process_group(process, sig):
    # start_new_session gives this exact child its own group. Its leader may
    # already have exited while a descendant still owns the stdout pipe.
    try:
        if os.getpgid(process.pid) != process.pid:
            raise RuntimeError("unexpected Go process group")
    except ProcessLookupError:
        pass
    try:
        os.killpg(process.pid, sig)
    except ProcessLookupError:
        pass


async def run_process(index, package, binary, selected, report):
    pattern = "^(" + "|".join(re.escape(case) for case in selected) + ")$"
    started = time.monotonic()
    process = await asyncio.create_subprocess_exec(
        "go", "tool", "test2json", "-t", "-p", package, str(binary),
        "-test.v=test2json", "-test.run=" + pattern, "-test.count=1", "-test.timeout=10m",
        cwd=ROOT, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        start_new_session=True, limit=64 * 1024 * 1024,
    )
    report["pid"] = process.pid
    reader = asyncio.create_task(read_events(process, report))
    waiter = asyncio.create_task(process.wait())
    drained = asyncio.gather(waiter, reader, return_exceptions=True)
    try:
        # Keep draining final Go diagnostics during suite cancellation instead
        # of abandoning a live pipe when the event loop is about to close.
        await asyncio.shield(reader)
        await asyncio.shield(waiter)
    finally:
        if not drained.done():
            signal_process_group(process, signal.SIGTERM)
            try:
                await asyncio.wait_for(asyncio.shield(drained), 3)
            except asyncio.TimeoutError:
                signal_process_group(process, signal.SIGKILL)
                await drained
        # No fixture in this owned session may outlive a finished test binary,
        # including descendants which closed or redirected their stdout.
        signal_process_group(process, signal.SIGKILL)
        report["returncode"] = process.returncode
        report["seconds"] = round(time.monotonic() - started, 3)
        report["unfinished"] = sorted(set(selected) - report["completed"].keys())
        report["running"] = sorted(set(report["started"]) - report["completed"].keys())
    return report


async def execute(package, binary, groups):
    # Partial reports must survive cancellation and process startup failures.
    reports = [new_report(index, group) for index, group in enumerate(groups)]
    tasks = [asyncio.create_task(run_process(index, package, binary, group, reports[index]))
             for index, group in enumerate(groups)]
    completion = asyncio.gather(*tasks)
    failure = None
    try:
        # Only this function cancels child tasks. wait_for(gather(...)) would
        # cancel every child on timeout, then finish as soon as the first child
        # completes cancellation. Cancelling the remaining siblings again in
        # finally interrupts their process/pipe cleanup and loses the report.
        await asyncio.wait_for(asyncio.shield(completion), timeout=LIMIT_SECONDS)
    except asyncio.TimeoutError:
        failure = "TIMEOUT"
    except Exception as error:
        failure = type(error).__name__ + ": " + str(error)
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        # The shielded aggregate can finish with a child cancellation after the
        # timeout. Retrieve it so no unobserved future survives loop shutdown.
        if completion.done() and not completion.cancelled():
            completion.exception()
    return reports, failure


def main():
    os.chdir(ROOT)
    if os.environ.get("MODELATLAS_BROWSER_TEST") == "1":
        raise ValueError("use the existing opt-in Browser workflow")
    packages = subprocess.check_output(["go", "list", "-mod=readonly", "./..."], text=True).splitlines()
    if len(packages) != 1:
        raise ValueError("Go package registry changed; extend the runner before proceeding")
    package = packages[0]
    output = ROOT / "test-results" / "go-suite.json"
    output.parent.mkdir(exist_ok=True)
    if output.exists():
        output.unlink()
    with tempfile.TemporaryDirectory(prefix="modelatlas-go-race-") as directory:
        binary = Path(directory) / "suite.test"
        subprocess.run(["go", "test", "-mod=readonly", "-race", "-c", "-o", str(binary), package], check=True)
        listing = subprocess.check_output([str(binary), "-test.list=."], text=True)
        registry = [line for line in listing.splitlines() if re.fullmatch(r"(?:Test|Example|Fuzz)\w*", line)]
        groups = partition(registry)
        started = time.monotonic()
        instant = datetime.now(timezone.utc).isoformat()
        reports, failure = asyncio.run(execute(package, binary, groups))
        elapsed = time.monotonic() - started
        if elapsed >= LIMIT_SECONDS and failure is None:
            failure = "TIMEOUT"
        if failure is None:
            try:
                verify(registry, reports)
            except ValueError as error:
                failure = str(error)
        source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"]))
        result = {"result": "FAIL" if failure else "PASS", "source": source, "working_tree_dirty": dirty, "race": True,
                  "suite_deadline_seconds": LIMIT_SECONDS, "started_at": instant, "seconds": round(elapsed, 3),
                  "package": package, "registry": sorted(registry),
                  "registry_sha256": hashlib.sha256("\n".join(sorted(registry)).encode()).hexdigest(), "processes": reports}
        if failure is not None:
            result["failure"] = failure
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"result": result["result"], "complete_go_registry": len(registry), "processes": 2,
                          "race": True, "seconds": result["seconds"], "original_suite_deadline": LIMIT_SECONDS}))
        if failure is not None:
            raise RuntimeError("Go suite failed; see test-results/go-suite.json: " + failure)


if __name__ == "__main__":
    main()
