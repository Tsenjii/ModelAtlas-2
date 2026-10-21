"""Regression tests for the release admission boundary and artifact integrity."""
import copy
import base64
import ssl
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
from android_signing import certificate_fingerprint

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
    def test_android_numbered_and_sdk_range_signers_match_one_identity(self):
        fingerprint = "f116873861598964874192dc5a40a1a4f9dbb5e3ce705ec4451a787d48955024"
        pem = (Path(__file__).parent / "fixtures/debug-signing-certificate.pem").read_text()
        for labels in [
            ["Signer #1"],
            ["V2 Signer:"],
            ["Signer (minSdkVersion=33, maxSdkVersion=2147483647)", "Signer (minSdkVersion=26, maxSdkVersion=32)"],
            ["Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)", "Signer (minSdkVersion=26, maxSdkVersion=32)"],
        ]:
            output = "Verifies\nNumber of signers: 1\n" + "\n".join(label + " certificate SHA-256 digest: " + fingerprint + "\n" + pem for label in labels)
            parsed, profiles = certificate_fingerprint(output)
            self.assertEqual(parsed, fingerprint)
            self.assertEqual(profiles, sorted(labels))

    def test_android_different_certificates_or_stamp_only_cannot_pass(self):
        first = "f116873861598964874192dc5a40a1a4f9dbb5e3ce705ec4451a787d48955024"
        pem = (Path(__file__).parent / "fixtures/debug-signing-certificate.pem").read_text()
        der = ssl.PEM_cert_to_DER_cert(pem)
        other_der = der[:-1] + bytes([der[-1] ^ 1])
        other_pem = "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(other_der).decode() + "\n-----END CERTIFICATE-----\n"
        other = hashlib.sha256(other_der).hexdigest()
        for output in [
            "Number of signers: 1\nSource Stamp Signer certificate SHA-256 digest: " + first + "\n" + pem,
            "Number of signers: 2\nSigner #1 certificate SHA-256 digest: " + first + "\n" + pem,
            "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + first + "\n" + pem + "\nV3 Signer: certificate SHA-256 digest: " + other + "\n" + other_pem,
            "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + first[:-1] + "\n" + pem,
            "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "b" * 64 + "\n" + pem,
            "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + first,
        ]:
            with self.assertRaises(ValueError):
                certificate_fingerprint(output)

    def test_exact_source_and_real_job_success_required(self):
        run = valid_run()
        jobs = [{"id": index, "name": name, "status": "completed", "conclusion": "success", "run_id": run["id"], "head_sha": COMMIT,
                 "started_at": "2026-10-21T03:00:00Z", "completed_at": "2026-10-21T03:10:00Z"} for index, name in enumerate(prepare.CHECKS[run["path"]])]
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

    def test_unpublished_draft_is_found_by_collection_not_published_tag_endpoint(self):
        draft = {"id": 418668468, "tag_name": "v0.8.0-preview.1", "draft": True}
        with patch.object(publish, "gh", return_value=[draft]) as api:
            self.assertEqual(publish.release_for_tag(draft["tag_name"]), draft)
            self.assertTrue(all("/releases?" in call.args[1] for call in api.call_args_list))
            self.assertIsNone(publish.release_for_tag("v0.8.0-absent"))

    def test_draft_lookup_paginates_and_refuses_duplicate_tag(self):
        filler = [{"id": index, "tag_name": "other-" + str(index)} for index in range(100)]
        draft = {"id": 101, "tag_name": "wanted", "draft": True}
        with patch.object(publish, "gh", side_effect=[filler, [draft]]) as api:
            self.assertEqual(publish.release_for_tag("wanted"), draft)
            self.assertIn("page=2", api.call_args_list[1].args[1])
        with patch.object(publish, "gh", return_value=[draft, dict(draft, id=102)]):
            with self.assertRaises(ValueError):
                publish.release_for_tag("wanted")

    def test_remote_release_requires_all_uploaded_digests(self):
        expected = {"a": {"sha256": "a" * 64, "size": 10}, "b": {"sha256": "b" * 64, "size": 20}}
        release = {"assets": [{"name": name, "size": value["size"], "digest": "sha256:" + value["sha256"]} for name, value in expected.items()]}
        publish.verify_uploaded(release, expected)
        release["assets"].pop()
        with self.assertRaises(ValueError):
            publish.verify_uploaded(release, expected)

    def test_created_draft_visibility_reads_are_bounded_without_recreating(self):
        draft = {"id": 419090377, "tag_name": "wanted", "draft": True, "target_commitish": COMMIT}
        with patch.object(publish, "release_for_tag", side_effect=[None, None, draft]) as lookup, patch.object(publish.time, "sleep") as sleep:
            self.assertEqual(publish.created_draft("wanted"), draft)
            self.assertEqual(lookup.call_count, 3)
            self.assertEqual([c.args[0] for c in sleep.call_args_list], [1, 2])
        with patch.object(publish, "release_for_tag", return_value=None) as lookup, patch.object(publish.time, "sleep") as sleep:
            with self.assertRaisesRegex(ValueError, "bounded read retries"):
                publish.created_draft("wanted")
            self.assertEqual(lookup.call_count, 6)
            self.assertEqual(sum(c.args[0] for c in sleep.call_args_list), 23)

    def test_new_draft_visibility_delay_preserves_complete_publish_lifecycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "release-output"
            out.mkdir()
            tag = prepare.properties()["tag"]
            assets = {}
            for name in publish.PAYLOADS:
                raw = name.encode()
                (out / name).write_bytes(raw)
                assets[name] = {"sha256": prepare.digest(raw), "size": len(raw)}
            checks = {workflow: {"jobs": [{"name": name, "conclusion": "success"} for name in expected]} for workflow, expected in prepare.CHECKS.items()}
            manifest = {"repository": prepare.REPOSITORY, "tag": tag, "commit": COMMIT, "prerelease": True, "checks": checks, "assets": assets}
            (out / "RELEASE-MANIFEST.json").write_text(json.dumps(manifest))
            checksums = {name: info["sha256"] for name, info in assets.items()}
            checksums["RELEASE-MANIFEST.json"] = prepare.digest((out / "RELEASE-MANIFEST.json").read_bytes())
            (out / "SHA256SUMS.txt").write_text("".join(value + "  " + name + "\n" for name, value in checksums.items()))
            (out / "RELEASE-NOTES.md").write_text("preview")
            _, expected = publish.validate_output(out, COMMIT)
            draft = {"id": 419090377, "tag_name": tag, "draft": True, "prerelease": True, "target_commitish": COMMIT}
            uploaded = dict(draft, assets=[{"name": name, "size": info["size"], "digest": "sha256:" + info["sha256"]} for name, info in expected.items()])
            final = dict(uploaded, draft=False, html_url="https://github.com/" + prepare.REPOSITORY + "/releases/tag/" + tag)
            calls = []
            def api(*args, **kwargs):
                calls.append(args)
                if args[:2] == ("release", "create"):
                    return {}
                if args[1:3] == ("--method", "PATCH"):
                    return final
                if args == ("api", "repos/" + prepare.REPOSITORY + "/releases/419090377"):
                    return uploaded
                self.fail("unexpected API operation: " + repr(args))
            with patch.dict(publish.os.environ, {"MODELATLAS_REPOSITORY": prepare.REPOSITORY, "MODELATLAS_SOURCE_COMMIT": COMMIT}), patch.object(publish, "ROOT", root), patch.object(publish, "tag_commit", return_value=COMMIT), patch.object(publish, "release_for_tag", side_effect=[None, None, None, draft]), patch.object(publish.time, "sleep"), patch.object(publish, "gh", side_effect=api), patch("builtins.print") as printed, patch.object(publish.subprocess, "run") as upload:
                publish.main()
            printed.assert_called_once_with("Published all three verified platforms: " + final["html_url"])
            self.assertEqual(sum(args[:2] == ("release", "create") for args in calls), 1)
            self.assertEqual(upload.call_count, 1)
            self.assertEqual({Path(name).name for name in upload.call_args.args[0][7:]}, set(expected))
            self.assertEqual(json.loads((out / "finalize-request.json").read_text()), {"draft": False, "prerelease": True, "make_latest": "false"})

    def test_delayed_new_draft_cannot_upload_a_different_source(self):
        tag = prepare.properties()["tag"]
        wrong = {"id": 12, "tag_name": tag, "draft": True, "prerelease": True, "target_commitish": "b" * 40}
        with patch.dict(publish.os.environ, {"MODELATLAS_REPOSITORY": prepare.REPOSITORY, "MODELATLAS_SOURCE_COMMIT": COMMIT}), patch.object(publish, "validate_output", return_value=({"tag": tag}, {})), patch.object(publish, "tag_commit", return_value=COMMIT), patch.object(publish, "release_for_tag", side_effect=[None, None, wrong]), patch.object(publish.time, "sleep"), patch.object(publish, "gh"), patch.object(publish.subprocess, "run") as upload:
            with self.assertRaisesRegex(ValueError, "created draft identity mismatch"):
                publish.main()
            upload.assert_not_called()

if __name__ == "__main__":
    unittest.main()
