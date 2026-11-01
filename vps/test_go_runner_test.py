import asyncio
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch
import test_go
from test_go import partition, verify


def fixture_process_stopped(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().split()[2] == "Z"
    except (FileNotFoundError, ProcessLookupError):
        # The kernel may reap a process while its procfs file is being read.
        return True


class CompleteGoRegistryTest(unittest.TestCase):
    def report(self, selected, completed=None, returncode=0):
        return {"selected": selected, "started": selected[:], "completed": completed if completed is not None else {case: "pass" for case in selected}, "errors": [], "returncode": returncode}

    def test_examples_fuzz_seeds_and_opt_in_skips_all_keep_their_registry_slot(self):
        cases = ["TestOne", "TestTwo", "ExamplePublic", "FuzzDecoder"]
        groups = partition(cases)
        reports = [self.report(group) for group in groups]
        reports[0]["completed"][groups[0][0]] = "skip"
        verify(cases, reports)

    def test_successful_exit_cannot_hide_a_missing_or_repeated_case(self):
        for reports in [
            [self.report(["TestOne"]), self.report(["TestOne"])],
            [self.report(["TestOne"]), self.report(["TestTwo"], {})],
        ]:
            with self.assertRaises(ValueError):
                verify(["TestOne", "TestTwo"], reports)

    def test_child_failure_or_invalid_event_is_never_accepted(self):
        for key, value in [("returncode", 1), ("errors", ["duplicate terminal event"]), ("started", [])]:
            reports = [self.report(["TestOne"]), self.report(["TestTwo"])]
            reports[0][key] = value
            with self.assertRaises(ValueError):
                verify(["TestOne", "TestTwo"], reports)

    def test_duplicate_or_empty_registry_is_rejected(self):
        for cases in [[], ["TestOne"], ["TestOne", "TestOne"]]:
            with self.assertRaises(ValueError):
                partition(cases)


class GoProcessLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, script, groups, deadline=5, start_after_spawn=False):
        # Only the runner's subprocess boundary is redirected. These are real
        # OS sessions, pipes and signals, not simulated process handles.
        create_process = asyncio.create_subprocess_exec
        wait_for = asyncio.wait_for
        ready = asyncio.Event()
        spawned = []

        async def spawn(*args, **kwargs):
            source = script(args) if callable(script) else script
            process = await create_process(sys.executable, "-u", "-c", source, **kwargs)
            spawned.append(process)
            if len(spawned) == len(groups):
                ready.set()
            return process

        async def fixture_wait_for(awaitable, timeout):
            if start_after_spawn and timeout == deadline:
                # This particular fixture tests cleanup of two running groups,
                # not whether a loaded host can launch them within 0.5s. The
                # real suite still starts its 600s clock before child launch.
                await wait_for(ready.wait(), 5)
            return await wait_for(awaitable, timeout)

        raw = io.BytesIO()
        output = io.TextIOWrapper(raw, encoding="utf-8")
        with patch.object(test_go.asyncio, "create_subprocess_exec", side_effect=spawn), \
                patch.object(test_go.asyncio, "wait_for", side_effect=fixture_wait_for), \
                patch.object(test_go, "LIMIT_SECONDS", deadline), \
                patch.object(test_go.sys, "stdout", output):
            reports, failure = await test_go.execute("fixture", Path("unused"), groups)
        output.flush()
        return reports, failure, raw.getvalue().decode()

    async def test_real_child_completion_preserves_skip_and_case_elapsed(self):
        script = """
import json
for action in ('run', 'skip'):
 print(json.dumps({'Action':action, 'Test':'TestFixture', 'Elapsed':0.125}), flush=True)
"""
        reports, failure, raw = await self.run_fixture(script, [["TestFixture"]])
        self.assertIsNone(failure)
        verify(["TestFixture"], reports)
        self.assertEqual(reports[0]["case_seconds"], {"TestFixture": 0.125})
        self.assertEqual(reports[0]["unfinished"], [])
        self.assertEqual(reports[0]["running"], [])
        self.assertIn('"Action": "skip"', raw)

    async def test_timeout_keeps_partial_results_and_stops_descendant_after_leader_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            child_pid = Path(directory) / "child.pid"
            # A descendant ignores TERM and owns the stdout pipe after its
            # session leader exits. Previously this lost both progress and
            # process ownership, then closed the event loop with a live pipe.
            child = ("import os,signal,time;from pathlib import Path;"
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                     f"Path({str(child_pid)!r}).write_text(str(os.getpid()));time.sleep(60)")
            script = f"""
import json,subprocess,sys,time
from pathlib import Path
subprocess.Popen([sys.executable,'-u','-c',{child!r}])
p=Path({str(child_pid)!r})
while not p.exists():time.sleep(0.005)
for action,case in [('run','TestFinished'),('pass','TestFinished'),('run','TestRunning')]:
 print(json.dumps({{'Action':action,'Test':case,'Elapsed':0.25}}),flush=True)
"""
            started = time.monotonic()
            reports, failure, raw = await self.run_fixture(
                script, [["TestFinished", "TestRunning", "TestQueued"]], deadline=1)
            self.assertEqual(failure, "TIMEOUT")
            self.assertLess(time.monotonic() - started, 6)
            self.assertEqual(reports[0]["returncode"], 0)
            self.assertEqual(reports[0]["completed"], {"TestFinished": "pass"})
            self.assertEqual(reports[0]["running"], ["TestRunning"])
            self.assertEqual(reports[0]["unfinished"], ["TestQueued", "TestRunning"])
            self.assertIn('"Test": "TestRunning"', raw)
            with self.assertRaises(ValueError):
                verify(["TestFinished", "TestRunning", "TestQueued"], reports)
            pid = int(child_pid.read_text())
            # An exited orphan can briefly remain a zombie until PID 1 reaps
            # it; it must never remain an executing or sleeping process.
            for _ in range(100):
                if fixture_process_stopped(pid):
                    break
                await asyncio.sleep(0.01)
            self.assertTrue(fixture_process_stopped(pid))

    async def test_suite_timeout_cleans_both_real_process_groups(self):
        script = "import time;time.sleep(60)"
        reports, failure, _ = await self.run_fixture(
            script, [["TestOne"], ["TestTwo"]], deadline=0.5, start_after_spawn=True)
        self.assertEqual(failure, "TIMEOUT")
        for report in reports:
            self.assertEqual(report["returncode"], -15)
            self.assertEqual(report["completed"], {})
            with self.assertRaises(ProcessLookupError):
                os.kill(report["pid"], 0)

    async def test_timeout_does_not_cancel_a_slower_siblings_cleanup_twice(self):
        # One real process exits immediately on TERM. Its sibling deliberately
        # takes longer to handle TERM, so the first task finishes while the
        # second is still draining and collecting its return code.
        slow = """
import os,signal,time
def stop(signum, frame):
 time.sleep(0.2)
 signal.signal(signal.SIGTERM,signal.SIG_DFL)
 os.kill(os.getpid(),signal.SIGTERM)
signal.signal(signal.SIGTERM,stop)
time.sleep(60)
"""
        def source(args):
            return slow if any("TestSlow" in str(arg) for arg in args) else "import time;time.sleep(60)"

        reports, failure, _ = await self.run_fixture(
            source, [["TestFast"], ["TestSlow"]], deadline=0.5, start_after_spawn=True)
        self.assertEqual(failure, "TIMEOUT")
        for report in reports:
            self.assertEqual(report["returncode"], -15)
            self.assertEqual(report["completed"], {})
            self.assertEqual(report["unfinished"], report["selected"])
            with self.assertRaises(ProcessLookupError):
                os.kill(report["pid"], 0)

    async def test_successful_parent_does_not_leave_a_detached_pipe_descendant_running(self):
        with tempfile.TemporaryDirectory() as directory:
            child_pid = Path(directory) / "child.pid"
            child = ("import os,time;from pathlib import Path;"
                     f"Path({str(child_pid)!r}).write_text(str(os.getpid()));time.sleep(60)")
            script = f"""
import json,subprocess,sys,time
from pathlib import Path
subprocess.Popen([sys.executable,'-u','-c',{child!r}],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
while not Path({str(child_pid)!r}).exists():time.sleep(0.005)
for action in ('run','pass'):
 print(json.dumps({{'Action':action,'Test':'TestFixture'}}),flush=True)
"""
            reports, failure, _ = await self.run_fixture(script, [["TestFixture"]])
            self.assertIsNone(failure)
            verify(["TestFixture"], reports)
            pid = int(child_pid.read_text())
            for _ in range(100):
                if fixture_process_stopped(pid):
                    break
                await asyncio.sleep(0.01)
            self.assertTrue(fixture_process_stopped(pid))

    async def test_nonzero_exit_and_invalid_json_cannot_become_pass(self):
        for script in ["print('not JSON')", "import sys;sys.exit(7)"]:
            reports, failure, _ = await self.run_fixture(script, [["TestFixture"]])
            self.assertIsNone(failure)
            with self.assertRaises(ValueError):
                verify(["TestFixture"], reports)

    async def test_reader_failure_stops_owned_process_and_returns_diagnostics(self):
        script = "import time;print('[]',flush=True);time.sleep(60)"
        reports, failure, _ = await self.run_fixture(script, [["TestFixture"]])
        self.assertIn("AttributeError", failure)
        self.assertEqual(reports[0]["returncode"], -15)
        self.assertEqual(reports[0]["unfinished"], ["TestFixture"])

    async def test_spawn_failure_keeps_both_registered_selections(self):
        with patch.object(test_go.asyncio, "create_subprocess_exec", side_effect=FileNotFoundError("fixture")):
            reports, failure = await test_go.execute("fixture", Path("unused"), [["TestOne"], ["TestTwo"]])
        self.assertIn("FileNotFoundError", failure)
        self.assertEqual([r["unfinished"] for r in reports], [["TestOne"], ["TestTwo"]])
        self.assertTrue(all(r["returncode"] is None for r in reports))


class FailedSuiteReportTest(unittest.TestCase):
    def test_fixture_accepts_a_process_reaped_during_procfs_read_and_rejects_a_live_one(self):
        for error in (ProcessLookupError(), FileNotFoundError()):
            with patch.object(Path, "read_text", side_effect=error):
                self.assertTrue(fixture_process_stopped(123))
        with patch.object(Path, "read_text", return_value="123 (fixture) S"):
            self.assertFalse(fixture_process_stopped(123))

    def test_main_writes_failed_report_and_raises_instead_of_reusing_stale_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            results = root / "test-results"
            results.mkdir()
            output = results / "go-suite.json"
            output.write_text('{"result":"PASS","source":"stale"}')
            reports = [test_go.new_report(0, ["TestOne"]), test_go.new_report(1, ["TestTwo"])]
            reports[0]["started"] = ["TestOne"]
            cwd = Path.cwd()
            try:
                with patch.object(test_go, "ROOT", root), \
                        patch.dict(os.environ, {"MODELATLAS_BROWSER_TEST": "0"}), \
                        patch.object(test_go.subprocess, "run"), \
                        patch.object(test_go.subprocess, "check_output", side_effect=["package\n", "TestOne\nTestTwo\n", "source\n", ""]), \
                        patch.object(test_go, "execute", new=AsyncMock(return_value=(reports, "TIMEOUT"))), \
                        patch.object(test_go.sys, "stdout", io.StringIO()):
                    with self.assertRaisesRegex(RuntimeError, "TIMEOUT"):
                        test_go.main()
            finally:
                os.chdir(cwd)
            actual = json.loads(output.read_text())
            self.assertEqual(actual["result"], "FAIL")
            self.assertEqual(actual["failure"], "TIMEOUT")
            self.assertEqual(actual["source"], "source")
            self.assertEqual(actual["suite_deadline_seconds"], 600)
            self.assertEqual(actual["processes"], reports)


if __name__ == "__main__":
    unittest.main()
