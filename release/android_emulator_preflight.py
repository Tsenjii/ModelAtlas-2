"""Restart the API 29 framework after its configured heap property is ready.

Android 10 cold boots can create zygote with ART's 16 MiB default before the
emulator's 512 MiB setting arrives. This runs once, before any instrumentation;
it never changes a VM property or retries a failed test.
"""
import json
from pathlib import Path
import subprocess
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


if __name__ == "__main__":
    result = json.dumps(prepare(), sort_keys=True)
    output = Path("app/build/outputs/androidTest-results/emulator-preflight.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(result + "\n")
    print("MODELATLAS_EMULATOR_PREFLIGHT " + result)
