import subprocess
import json
import unittest

from android_emulator_preflight import prepare, capture_runtime


class EmulatorPreflightTests(unittest.TestCase):
    def fake(self, *, heap="512m", growth="", restarted=True, package_ready=True):
        calls = []
        def run(*args):
            calls.append(args)
            if args == ("shell", "getprop", "ro.build.version.sdk"): return "29"
            if args == ("shell", "getprop", "dalvik.vm.heapsize"): return heap
            if args == ("shell", "getprop", "dalvik.vm.heapgrowthlimit"): return growth
            if args == ("shell", "pidof", "zygote64"):
                return "200" if restarted and ("shell", "start") in calls else "100"
            if args == ("shell", "getprop", "sys.boot_completed"): return "1"
            if args == ("shell", "cmd", "package", "path", "android"):
                if not package_ready: raise subprocess.CalledProcessError(1, "adb")
                return "package:/system/framework/framework-res.apk"
            return ""
        return run, calls

    def test_one_framework_restart_before_tests_without_property_overrides(self):
        run, calls = self.fake()
        result = prepare(run)
        self.assertEqual("100", result["zygote_before"])
        self.assertEqual("200", result["zygote_after"])
        self.assertEqual(536870912, result["expected_runtime_max_heap_bytes"])
        self.assertEqual(1, calls.count(("shell", "stop")))
        self.assertEqual(1, calls.count(("shell", "start")))
        self.assertFalse(any("setprop" in call or "am" in call for call in calls))

    def test_unexpected_heap_is_rejected_without_restart(self):
        for options in ({"heap": "16m"}, {"growth": "16m"}):
            run, calls = self.fake(**options)
            with self.assertRaises(RuntimeError): prepare(run)
            self.assertNotIn(("shell", "stop"), calls)

    def test_unchanged_zygote_cannot_pass_even_when_boot_completed(self):
        run, _ = self.fake(restarted=False)
        ticks = iter((0, 0, 91))
        with self.assertRaises(TimeoutError): prepare(run, lambda: next(ticks), lambda _: None)

    def test_missing_package_manager_cannot_pass(self):
        run, _ = self.fake(package_ready=False)
        ticks = iter((0, 0, 91))
        with self.assertRaises(TimeoutError): prepare(run, lambda: next(ticks), lambda _: None)

    def test_initial_zygote_waits_for_unique_pid_then_restarts_only_once(self):
        original, calls = self.fake()
        initial = iter(("", "100 101", "100"))
        now = [0]
        def run(*args):
            value = original(*args)
            if args == ("shell", "pidof", "zygote64") and ("shell", "start") not in calls:
                return next(initial)
            return value
        def pause(seconds): now[0] += seconds
        result = prepare(run, lambda: now[0], pause)
        self.assertEqual(["missing", "100 101", "100"], result["initial_zygote_observations"])
        self.assertEqual("100", result["zygote_before"])
        self.assertEqual("200", result["zygote_after"])
        self.assertEqual(2, now[0])
        self.assertEqual(1, calls.count(("shell", "stop")))
        self.assertEqual(1, calls.count(("shell", "start")))
        self.assertFalse(any("setprop" in call or "am" in call for call in calls))

    def test_missing_initial_zygote_fails_without_restart_in_original_deadline(self):
        original, calls = self.fake()
        def run(*args):
            value = original(*args)
            return "" if args == ("shell", "pidof", "zygote64") else value
        ticks = iter((0, 0, 91))
        with self.assertRaisesRegex(TimeoutError, "shared 90-second"):
            prepare(run, lambda: next(ticks), lambda _: None)
        self.assertNotIn(("shell", "stop"), calls)
        self.assertNotIn(("shell", "start"), calls)

    def logs(self, *, finish=True, end_heap=536870912, end_pid=123):
        records = [{"stage":"start", "pid":123, "max_heap_bytes":536870912, "expected_max_heap_bytes":536870912}]
        if finish: records.append({"stage":"finish", "pid":end_pid, "max_heap_bytes":end_heap, "expected_max_heap_bytes":536870912})
        return lambda *args: "\n".join("I/ModelAtlasTestRuntime: MODELATLAS_TEST_RUNTIME "+json.dumps(r) for r in records)

    def test_actual_start_and_finish_heap_is_read_without_installed_package(self):
        result = capture_runtime(self.logs())
        self.assertEqual(["start", "finish"], result["stages"])
        self.assertEqual(123, result["pid"])
        self.assertEqual(536870912, result["max_heap_bytes"])

    def test_missing_finish_cannot_supply_heap_evidence(self):
        with self.assertRaises(ValueError): capture_runtime(self.logs(finish=False))

    def test_low_runtime_heap_cannot_supply_green_evidence(self):
        with self.assertRaises(ValueError): capture_runtime(self.logs(end_heap=16777216))

    def test_another_process_cannot_supply_finish_evidence(self):
        with self.assertRaises(ValueError): capture_runtime(self.logs(end_pid=456))
