"""Restart the API 29 framework after its configured heap property is ready.

Android 10 cold boots can create zygote with ART's 16 MiB default before the
emulator's 512 MiB setting arrives. This runs once, before any instrumentation;
it never changes a VM property or retries a failed test.
"""
import json
from pathlib import Path
import subprocess
import sys
import time


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True,
                          text=True, timeout=15).stdout.strip()


def prepare(run=adb, clock=time.monotonic, pause=time.sleep):
    run("root")
    run("wait-for-device")
    sdk = run("shell", "getprop", "ro.build.version.sdk")
    heap = run("shell", "getprop", "dalvik.vm.heapsize")
    growth = run("shell", "getprop", "dalvik.vm.heapgrowthlimit")
    if sdk != "29" or heap != "512m" or growth not in ("", "512m"):
        raise RuntimeError(f"Unexpected emulator VM configuration: sdk={sdk}, heap={heap}, growth={growth}")
    before = run("shell", "pidof", "zygote64")
    if not before.isdecimal():
        raise RuntimeError("Expected one API 29 zygote64")
    run("shell", "stop")
    run("shell", "start")
    deadline = clock() + 90
    while clock() < deadline:
        try:
            after = run("shell", "pidof", "zygote64")
            booted = run("shell", "getprop", "sys.boot_completed")
            package = run("shell", "cmd", "package", "path", "android")
            if after.isdecimal() and after != before and booted == "1" and package.startswith("package:"):
                if run("shell", "getprop", "dalvik.vm.heapsize") != heap:
                    raise RuntimeError("Emulator heap property changed during framework restart")
                return {"sdk": sdk, "configured_heap": heap, "growth_limit": growth,
                        "zygote_before": before, "zygote_after": after,
                        "expected_runtime_max_heap_bytes": 512 * 1024 * 1024}
        except subprocess.CalledProcessError:
            pass  # Package manager can be absent while the framework starts.
        pause(1)
    raise TimeoutError("API 29 framework did not restart within 90 seconds")


def capture_runtime(run=adb):
    # AGP removes the installed APK after connected tests. Read the final runner
    # log, which survives uninstall, instead of trying to read its deleted cache.
    raw = run("logcat", "-d", "-s", "ModelAtlasTestRuntime:I")
    marker = "MODELATLAS_TEST_RUNTIME "
    records = [json.loads(line.split(marker, 1)[1]) for line in raw.splitlines() if marker in line]
    if len(records) != 2 or [r.get("stage") for r in records] != ["start", "finish"]:
        raise ValueError("Missing or ambiguous instrumentation runtime evidence")
    if len({r.get("pid") for r in records}) != 1 or not isinstance(records[0].get("pid"), int) or records[0]["pid"] <= 0:
        raise ValueError("Instrumentation runtime evidence belongs to different processes")
    if any(r.get("max_heap_bytes") != 536870912 or r.get("expected_max_heap_bytes") != 536870912 for r in records):
        raise ValueError("Actual instrumentation heap differs from configured 512 MiB")
    return {"max_heap_bytes": records[0]["max_heap_bytes"],
            "expected_max_heap_bytes": records[0]["expected_max_heap_bytes"],
            "pid": records[0]["pid"], "stages": [r["stage"] for r in records]}


if __name__ == "__main__":
    capture = sys.argv[1:] == ["--capture-runtime"]
    if sys.argv[1:] and not capture:
        raise ValueError("Unsupported emulator preflight argument")
    result = json.dumps(capture_runtime() if capture else prepare(), sort_keys=True)
    name = "instrumentation-runtime" if capture else "emulator-preflight"
    output = Path(f"app/build/outputs/androidTest-results/{name}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(result + "\n")
    print("MODELATLAS_" + name.upper().replace("-", "_") + " " + result)
