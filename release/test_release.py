"""Regression tests for the release admission boundary and artifact integrity."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.request
import zipfile

import prepare
import publish

COMMIT = "a" * 40

def valid_run(workflow=".github/workflows/vps.yml", **changes):
    value = {"id": 42, "head_sha": COMMIT, "path": workflow, "event": "pull_request",
             "head_repository": {"full_name": prepare.REPOSITORY}, "status": "completed", "conclusion": "success"}
    value.update(changes)
    return value

def tar_bytes(files, extra=None):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for name, data in files.items():
            info = tarfile.TarInfo("modelatlas-vps-linux-amd64/" + name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        if extra:
            archive.addfile(extra)
    return output.getvalue()

def linux_files():
    binary = bytearray(64)
    binary[:6] = b"\x7fELF\x02\x01"
    binary[18:20] = (62).to_bytes(2, "little")
    files = {
        "VERSION.json": json.dumps({"commit": COMMIT, "arch": "amd64", "os": "linux", "schema": 19}).encode(),
        "RELEASE.json": json.dumps({"commit": COMMIT, "tag": "v0.8.0-preview.1", "arch": "amd64", "database_schema": 19}).encode(),
        "bin/modelatlas-vps-linux-amd64": bytes(binary),
        "deploy/install.sh": b"install", "deploy/update.sh": b"update",
        "deploy/manage.sh": b"manage", "deploy/download.sh": b"download",
        "UPDATE.md": b"update guide", "README.md": b"guide",
    }
    files["SHA256SUMS.txt"] = "".join(prepare.digest(raw) + "  " + name + "\n" for name, raw in files.items()).encode()
    return files

class ReleaseBoundaryTests(unittest.TestCase):
    def test_exact_source_and_real_job_success_required(self):
        run = valid_run()
        jobs = [{"id": index, "name": name, "status": "completed", "conclusion": "success"} for index, name in enumerate(prepare.CHECKS[run["path"]])]
        self.assertEqual(len(prepare.verified_jobs(run, jobs, prepare.CHECKS[run["path"]], COMMIT)), 4)
        for conclusion in ["skipped", "cancelled", "failure", None]:
            broken = copy.deepcopy(jobs)
            broken[0]["conclusion"] = conclusion
            with self.assertRaises(ValueError):
                prepare.verified_jobs(run, broken, prepare.CHECKS[run["path"]], COMMIT)
        with self.assertRaises(ValueError):
            prepare.verified_jobs(run, jobs, prepare.CHECKS[run["path"]], "b" * 40)
        with self.assertRaises(ValueError):
            prepare.verified_jobs(run, jobs[:-1], prepare.CHECKS[run["path"]], COMMIT)

    def test_new_failure_cannot_borrow_an_older_green(self):
        older, current = valid_run(id=40), valid_run(id=41, conclusion="failure")
        selected = prepare.select_run([older, current, valid_run(id=99, head_sha="b" * 40)], older["path"], COMMIT)
        self.assertEqual(selected["id"], 41)
        with self.assertRaises(ValueError):
            prepare.verified_jobs(selected, [], prepare.CHECKS[older["path"]], COMMIT)

    def test_foreign_repo_or_workflow_cannot_supply_checks(self):
        run = valid_run(head_repository={"full_name": "another/repo"})
        self.assertIsNone(prepare.select_run([run], run["path"], COMMIT))
        self.assertIsNone(prepare.select_run([valid_run()], ".github/workflows/other.yml", COMMIT))

    def test_redirect_does_not_forward_github_token(self):
        request = urllib.request.Request("https://api.github.com/test", headers={"Authorization": "Bearer never-forward", "User-Agent": "test"})
        redirect = prepare.PublicRedirect().redirect_request(request, None, 302, "", {}, "https://artifact.example/file")
        self.assertIsNone(redirect.get_header("Authorization"))
        self.assertEqual(redirect.get_header("User-agent"), "test")
        with self.assertRaises(ValueError):
            prepare.PublicRedirect().redirect_request(request, None, 302, "", {}, "http://artifact.example/file")

    def test_real_tar_validates_full_hashes_source_and_elf(self):
        files = linux_files()
        checked, version = prepare.linux_bundle(tar_bytes(files), "amd64", COMMIT, "v0.8.0-preview.1")
        self.assertEqual(checked, files)
        self.assertEqual(version["schema"], 19)
        files["README.md"] = b"tampered"
        with self.assertRaises(ValueError):
            prepare.linux_bundle(tar_bytes(files), "amd64", COMMIT, "v0.8.0-preview.1")

    def test_archive_traversal_links_and_wrong_architecture_refused(self):
        files = linux_files()
        for name in ["../escape", "/absolute", "unsafe\\windows"]:
            extra = tarfile.TarInfo(name)
            with self.assertRaises(ValueError):
                prepare.linux_bundle(tar_bytes(files, extra), "amd64", COMMIT, "v0.8.0-preview.1")
        linked = tarfile.TarInfo("modelatlas-vps-linux-amd64/link")
        linked.type, linked.linkname = tarfile.SYMTYPE, "/etc/passwd"
        with self.assertRaises(ValueError):
            prepare.linux_bundle(tar_bytes(files, linked), "amd64", COMMIT, "v0.8.0-preview.1")
        with self.assertRaises(ValueError):
            prepare.linux_bundle(tar_bytes(files), "arm64", COMMIT, "v0.8.0-preview.1")
        with self.assertRaises(ValueError):
            prepare.linux_bundle(tar_bytes(files), "amd64", "b" * 40, "v0.8.0-preview.1")

    def test_unhashed_bundle_members_are_not_ignored(self):
        files = linux_files()
        files["unlisted"] = b"not in manifest"
        with self.assertRaises(ValueError):
            prepare.linux_bundle(tar_bytes(files), "amd64", COMMIT, "v0.8.0-preview.1")

    def test_zip_ambiguous_apk_and_unsafe_members_refused(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("app/debug/app-debug.apk", b"one")
            archive.writestr("other/debug/app-debug.apk", b"two")
        with zipfile.ZipFile(io.BytesIO(data.getvalue())) as archive:
            with self.assertRaises(ValueError):
                prepare.member(archive, "debug/app-debug.apk")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../escape", b"bad")
            with self.assertRaises(ValueError):
                prepare.read_zip(path)

    def test_checksum_manifest_rejects_ambiguous_or_unsafe_paths(self):
        checksum = "a" * 64
        for value in [checksum + "  ../escape\n", checksum + "  x\n" + checksum + "  x\n", "invalid\n"]:
            with self.assertRaises(ValueError):
                prepare.sums(value.encode())

    def test_release_cannot_publish_missing_platform_or_changed_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            assets = {}
            for name in publish.PAYLOADS:
                data = name.encode()
                (out / name).write_bytes(data)
                assets[name] = {"sha256": prepare.digest(data), "size": len(data)}
            checks = {workflow: {"jobs": [{"name": name, "conclusion": "success"} for name in expected]} for workflow, expected in prepare.CHECKS.items()}
            manifest = {"repository": prepare.REPOSITORY, "tag": prepare.properties()["tag"], "commit": COMMIT, "prerelease": True, "checks": checks, "assets": assets}
            (out / "RELEASE-MANIFEST.json").write_text(json.dumps(manifest))
            checksums = {name: info["sha256"] for name, info in assets.items()}
            checksums["RELEASE-MANIFEST.json"] = prepare.digest((out / "RELEASE-MANIFEST.json").read_bytes())
            (out / "SHA256SUMS.txt").write_text("".join(value + "  " + name + "\n" for name, value in checksums.items()))
            (out / "RELEASE-NOTES.md").write_text("preview")
            _, verified = publish.validate_output(out, COMMIT)
            self.assertEqual(set(verified), publish.PAYLOADS | publish.ADDITIONAL)
            (out / "ModelAtlas-Android-preview.apk").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                publish.validate_output(out, COMMIT)
            (out / "ModelAtlas-Android-preview.apk").unlink()
            with self.assertRaises(ValueError):
                publish.validate_output(out, COMMIT)

    def test_remote_release_requires_all_uploaded_digests(self):
        expected = {"a": {"sha256": "a" * 64, "size": 10}, "b": {"sha256": "b" * 64, "size": 20}}
        release = {"assets": [{"name": name, "size": value["size"], "digest": "sha256:" + value["sha256"]} for name, value in expected.items()]}
        publish.verify_uploaded(release, expected)
        release["assets"].pop()
        with self.assertRaises(ValueError):
            publish.verify_uploaded(release, expected)

if __name__ == "__main__":
    unittest.main()
