"""Release admission against real, public GitHub rerun metadata and bad inputs."""
import copy
import itertools
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import prepare


class ArtifactProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = json.loads((Path(__file__).parent / "fixtures/ci-rerun-provenance.json").read_text())
        self.mac = self.fixtures[2]
        self.commit = self.mac["run"]["head_sha"]
        # Keep the real, immutable 2026-10-21 nine-job rerun counterexample
        # under its original admission contract. It predates portable jobs;
        # never fabricate new successful jobs/artifacts in this fixture.
        checks = copy.deepcopy(prepare.CHECKS)
        checks[".github/workflows/vps.yml"] = {"vps-test", "vps-browser", "vps-linux-amd64", "vps-linux-arm64"}
        artifacts = copy.deepcopy(prepare.ARTIFACT_JOBS)
        for arch in ("amd64", "arm64"):
            artifacts[".github/workflows/vps.yml"].pop("modelatlas-vps-portable-"+arch)
        scope = patch.multiple(prepare, CHECKS=checks, ARTIFACT_JOBS=artifacts)
        scope.start()
        self.addCleanup(scope.stop)

    def checked(self, fixture):
        run = fixture["run"]
        return {"id": run["id"], "attempt": run["run_attempt"], "jobs": prepare.verified_jobs(
            run, fixture["jobs"], prepare.CHECKS[run["path"]], self.commit)}

    def select(self, fixture):
        return prepare.select_artifacts(fixture["run"]["path"], self.checked(fixture), fixture["artifacts"], self.commit)

    def test_real_rerun_chooses_successful_qa_in_every_api_order(self):
        # Failed QA has a *larger* ID than the successful rerun. Neither ID nor
        # result ordering proves which artifact belongs to the accepted job.
        expected = {"ModelAtlas-macOS-Universal": 12318016625,
                    "ModelAtlas-macOS-QA-arm64": 12318231780,
                    "ModelAtlas-macOS-QA-x86_64": 12317604113}
        for order in itertools.permutations(self.mac["artifacts"]):
            fixture = dict(self.mac, artifacts=list(order))
            self.assertEqual({name: item["id"] for name, item in self.select(fixture).items()}, expected)

    def test_real_all_nine_jobs_retain_producer_source_and_execution_times(self):
        self.assertEqual(sum(len(self.checked(f)["jobs"]) for f in self.fixtures), 9)
        selected = [self.select(f) for f in self.fixtures]
        self.assertEqual(sum(map(len, selected)), 9)
        for fixture in self.fixtures:
            checked = self.checked(fixture)
            for original in fixture["jobs"]:
                job = next(j for j in checked["jobs"] if j["id"] == original["id"])
                for key in ["head_sha", "run_id", "started_at", "completed_at"]:
                    self.assertEqual(job[key], original[key])
        # A carried-over successful build keeps its original time window even
        # when only Intel is rerun in attempt 2.
        self.assertEqual(self.checked(self.mac)["attempt"], 2)
        self.assertEqual(self.select(self.mac)["ModelAtlas-macOS-Universal"]["id"], 12318016625)

    def test_manifest_provenance_links_selected_upload_to_successful_job(self):
        checked = self.checked(self.mac)
        item = self.select(self.mac)["ModelAtlas-macOS-QA-x86_64"]
        evidence = prepare.artifact_provenance(self.mac["run"]["path"], checked, item)
        self.assertEqual(evidence, {
            "name": "ModelAtlas-macOS-QA-x86_64", "id": 12317604113,
            "digest": item["digest"], "run_id": 39536170097,
            "producer_job_id": 119019403269,
            "producer_job_name": "macos-test (macos-15-intel, x86_64)",
            "created_at": "2026-10-21T03:28:35Z"})

    def test_failed_attempt_artifact_cannot_replace_missing_successful_qa(self):
        self.mac["artifacts"] = [a for a in self.mac["artifacts"] if a["id"] != 12317604113]
        with self.assertRaisesRegex(ValueError, "missing or ambiguous.*ModelAtlas-macOS-QA-x86_64"):
            self.select(self.mac)

    def test_artifact_must_match_exact_run_source_and_successful_producer_window(self):
        good = next(a for a in self.mac["artifacts"] if a["id"] == 12317604113)
        variants = []
        for field, value in [("id", 1), ("head_sha", "b" * 40)]:
            artifact = copy.deepcopy(good)
            artifact["workflow_run"][field] = value
            variants.append(artifact)
        variants.extend(dict(good, **change) for change in [
            {"workflow_run": None}, {"expired": True},
            {"created_at": "2026-10-21T03:21:35Z"},  # one second before Intel
            {"created_at": "2026-10-21T03:28:43Z"},  # one second after Intel
            {"created_at": "2026-10-21T03:12:14Z"},  # ARM's successful window
        ])
        for artifact in variants:
            with self.subTest(artifact=artifact):
                fixture = dict(self.mac, artifacts=[a for a in self.mac["artifacts"] if a["id"] != good["id"]] + [artifact])
                with self.assertRaises(ValueError):
                    self.select(fixture)

    def test_ambiguous_uploads_are_rejected_instead_of_guessing_latest(self):
        good = next(a for a in self.mac["artifacts"] if a["id"] == 12317604113)
        self.mac["artifacts"].append(dict(good, id=12317604114, created_at="2026-10-21T03:28:36Z"))
        with self.assertRaisesRegex(ValueError, "missing or ambiguous"):
            self.select(self.mac)

    def test_job_source_time_and_duplicate_job_names_fail_closed(self):
        variants = []
        for change in [{"run_id": 1}, {"head_sha": "b" * 40},
                       {"started_at": None}, {"completed_at": "2026-10-20T00:00:00Z"},
                       {"completed_at": "2026-10-21T03:28:42"}]:
            fixture = copy.deepcopy(self.mac)
            fixture["jobs"][0].update(change)
            variants.append(fixture)
        duplicate = copy.deepcopy(self.mac)
        duplicate["jobs"].append(dict(duplicate["jobs"][0], id=1, conclusion="failure"))
        variants.append(duplicate)
        for fixture in variants:
            with self.subTest(jobs=fixture["jobs"]):
                with self.assertRaises(ValueError):
                    self.select(fixture)

    def test_invalid_artifact_timestamp_and_digest_are_rejected(self):
        for change in [{"created_at": None}, {"created_at": "2026-10-21T03:28:35"},
                       {"created_at": "2026-13-21T03:28:35Z"}, {"digest": None},
                       {"digest": "sha256:truncated"}]:
            fixture = copy.deepcopy(self.mac)
            next(a for a in fixture["artifacts"] if a["id"] == 12317604113).update(change)
            with self.subTest(change=change):
                with self.assertRaises(ValueError):
                    self.select(fixture)

    def test_collect_rejects_stale_evidence_before_downloading_any_package(self):
        checks = {f["run"]["path"]: self.checked(f) for f in self.fixtures}
        api = Mock()
        artifacts = {str(f["run"]["id"]): f["artifacts"] for f in self.fixtures}
        artifacts[str(self.mac["run"]["id"])] = [a for a in self.mac["artifacts"] if a["id"] != 12317604113]
        api.pages.side_effect = lambda path, key: artifacts[path.split("/")[-2]]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "missing or ambiguous"):
                prepare.collect(api, self.commit, checks, Path(directory))
            api.download.assert_not_called()
            self.assertEqual(list(Path(directory).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
