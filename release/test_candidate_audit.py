import io
import unittest
import zipfile
from pathlib import Path
import tempfile
import json
import copy
import audit_candidate as audit

class CandidateLinuxInventoryTest(unittest.TestCase):
    def config(self):
        return json.loads((Path(__file__).parent/"candidate_audit_scope.json").read_text())

    def test_public_documents_and_architecture_have_exact_inventory(self):
        config = self.config()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in config["linux_baseline_documents"]:
                (root/name).write_text("original public document\n")
            original = audit.linux_inventory(root, "amd64", config)
            self.assertEqual(len(original), 46)
            (root/"RESEARCH_COST.md").write_text("new public document\n")
            (root/"private.key").write_text("must not be packaged\n")
            (root/"implementation.go").write_text("package main\n")
            current = audit.linux_inventory(root, "amd64", config)
            self.assertEqual(current-original, {"RESEARCH_COST.md"})
            self.assertEqual(len(current), 47)
            self.assertNotIn("private.key", current)
            self.assertNotIn("implementation.go", current)
            arm = audit.linux_inventory(root, "arm64", config)
            self.assertEqual(current-arm, {"bin/modelatlas-vps-linux-amd64"})
            self.assertEqual(arm-current, {"bin/modelatlas-vps-linux-arm64"})
            with self.assertRaisesRegex(ValueError, "unsupported"):
                audit.linux_inventory(root, "arm32", config)

    def test_same_count_replacement_cannot_hide_a_missing_original_document(self):
        config = self.config()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in config["linux_baseline_documents"]:
                (root/name).write_text("original public document\n")
            (root/"README.md").unlink()
            (root/"replacement.md").write_text("same count is insufficient\n")
            with self.assertRaisesRegex(ValueError, "original Linux document missing"):
                audit.linux_inventory(root, "amd64", config)

class CandidateBrowserArchiveIsolationTest(unittest.TestCase):
    def config(self):
        return json.loads((Path(__file__).parent/"candidate_audit_scope.json").read_text())

    def test_declared_views_pass_the_same_name_guard_before_waiting_for_ci(self):
        audit.validate_browser_screenshot_names(self.config()["new_browser_images"])

    def test_crash_captures_remain_forbidden_in_declared_and_actual_views(self):
        for name in ("research-history-failure.png", "research-history-scope-failure-390-dark.png", "aqua-failure.png", "unreviewed-view.png"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "unexpected new screenshot"):
                audit.validate_browser_screenshot_names([name])

    def test_new_candidate_views_do_not_rewrite_a_frozen_counterexample(self):
        config = self.config()
        before = audit.browser_image_scopes(config)
        config["new_browser_images"].append("future-candidate-only.png")
        after = audit.browser_image_scopes(config)
        self.assertEqual(after["candidate"][0], before["candidate"][0]+1)
        self.assertEqual(after["candidate"][1]-before["candidate"][1], {"future-candidate-only.png"})
        self.assertEqual(before["rejected_aa"], after["rejected_aa"])
        self.assertEqual(after["rejected_aa"][0], 523)
        self.assertEqual(len(after["rejected_aa"][1]), 14)
        self.assertFalse(any("cost-" in name for name in after["rejected_aa"][1]))

    def test_new_views_cannot_replace_or_duplicate_an_original_reader_view(self):
        original = self.config()
        missing = copy.deepcopy(original)
        missing["new_browser_images"].remove(original["rejected_aa_browser"]["required_images"][0])
        missing["new_browser_images"].append("cost-view-cannot-replace-a-reader.png")
        with self.assertRaisesRegex(ValueError, "original private"):
            audit.browser_image_scopes(missing)
        duplicated = copy.deepcopy(original)
        duplicated["new_browser_images"].append(duplicated["new_browser_images"][0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            audit.browser_image_scopes(duplicated)

    def test_accepted_cost_view_cannot_be_replaced_at_the_same_image_count(self):
        config = self.config()
        config["new_browser_images"].remove("research-query-cost-375-light.png")
        config["new_browser_images"].append("research-execution-replacement.png")
        with self.assertRaisesRegex(ValueError, "accepted browser"):
            audit.browser_image_scopes(config)

    def test_accepted_execution_view_cannot_be_replaced_at_the_same_count(self):
        config = self.config()
        config["new_browser_images"].remove("research-execution-375-light.png")
        config["new_browser_images"].append("aqua-cannot-replace-execution.png")
        with self.assertRaisesRegex(ValueError, "accepted execution"):
            audit.browser_image_scopes(config)

    def test_accepted_aqua_view_cannot_be_replaced_at_the_same_count(self):
        config = self.config()
        config["new_browser_images"].remove("aqua-playground-390-light.png")
        config["new_browser_images"].append("research-history-cannot-replace-aqua.png")
        with self.assertRaisesRegex(ValueError, "accepted Aqua"):
            audit.browser_image_scopes(config)

    def test_accepted_history_view_cannot_be_replaced_at_the_same_count(self):
        config = self.config()
        config["new_browser_images"].remove(next(name for name in config["accepted_history_views"] if name.startswith("research-history-")))
        config["new_browser_images"].append("research-history-replacement.png")
        with self.assertRaisesRegex(ValueError, "accepted history"):
            audit.browser_image_scopes(config)

    def test_native_aqua_scope_keeps_all_unique_noncrash_captures(self):
        original = self.config()
        self.assertEqual(len(audit.native_aqua_image_scope(original)),13)
        for replacement in ("01-failure.png", original["new_mac_aqua_images"][1]):
            config = copy.deepcopy(original)
            config["new_mac_aqua_images"][0] = replacement
            with self.assertRaisesRegex(ValueError, "native Aqua"):
                audit.native_aqua_image_scope(config)

    def test_system_transparency_requires_real_observations_and_exact_restoration(self):
        resource = {"system_fixture_restored": True, "phases": [{"system_transparency": value} for value in (False,False,True)]}
        fixture = {"requested": [False,False,True], "observed": [False,False,True], "restored": True, "before": {"present": True, "value": True}, "after": {"present": True, "value": True}}
        audit.validate_native_transparency_fixture(resource,fixture)
        for field,value in (("observed",[False,False,False]),("restored",False),("after",{"present":True,"value":False})):
            changed = copy.deepcopy(fixture); changed[field] = value
            with self.assertRaisesRegex(ValueError,"transparency"):
                audit.validate_native_transparency_fixture(resource,changed)
        resource["phases"][2]["system_transparency"] = False
        with self.assertRaisesRegex(ValueError,"system states"):
            audit.validate_native_transparency_fixture(resource,fixture)

    def test_contact_inventory_grows_without_rewriting_frozen_views(self):
        config = self.config()
        self.assertEqual(audit.visual_review_count(config),110)
        config["new_browser_images"].append("future-new-view.png")
        self.assertEqual(audit.visual_review_count(config),111)
        self.assertEqual(audit.browser_image_scopes(config)["rejected_aa"][0],523)

class CandidateDeviceExtensionTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).parent/"candidate_audit_scope.json").read_text())
        self.old = {"old#"+str(i): "pass" if i < 991 else "skipped" for i in range(996)}
        self.added = "dev.modelatlas.app.data.MyModelsMetadataRetryStateTest#c1PublicationContentionKeepsAllFiveCallersInOneFlight"
        self.cases = dict(self.old, **{self.added: "pass"})
        self.counts = dict(tests=997, failures=0, errors=0, skipped=5)

    def test_new_pass_keeps_all_original_outcomes_and_five_skips(self):
        self.assertEqual(audit.validate_device_case_extension(self.counts, self.cases, self.old, self.config), [self.added])
        for original, action in (("old#0", "skipped"), ("old#995", "pass")):
            changed = dict(self.cases); changed[original] = action
            with self.subTest(original=original), self.assertRaisesRegex(ValueError, "removed or regressed"):
                audit.validate_device_case_extension(self.counts, changed, self.old, self.config)

    def test_missing_replaced_or_skipped_regression_cannot_pass_at_the_same_count(self):
        for replacement in (None, "replacement#same-count", self.added):
            changed = dict(self.old)
            if replacement is not None:
                changed[replacement] = "skipped" if replacement == self.added else "pass"
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, "regression case"):
                audit.validate_device_case_extension(self.counts, changed, self.old, self.config)
        changed_config = copy.deepcopy(self.config)
        changed_config["new_device_cases"][self.added] = "skipped"
        with self.assertRaisesRegex(ValueError, "must pass"):
            audit.validate_device_case_extension(self.counts, self.cases, self.old, changed_config)

class CandidateRegistryIntegrityTest(unittest.TestCase):
    def test_new_research_files_cannot_disappear_from_the_audited_registry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = {
                "connector_private_mapping_test.go": "TestConnectorPrivateMappingExisting",
                "research_run_lookup_test.go": "TestResearchRunLookupKeepsExactOlderRecordAndScheduleScope",
                "research_native_fixture_test.go": "TestResearchMacNativeFixture",
            }
            for file, name in cases.items():
                (root/file).write_text("package main\nfunc "+name+"(t *testing.T) {}\n")
            (root/"production.go").write_text("func TestNotARegisteredTest() {}\n")
            (root/"main_test.go").write_text("func TestMain(m *testing.M) {}\n")
            self.assertEqual(audit.go_test_functions(root), set(cases.values()))

class CandidateXMLIntegrityTest(unittest.TestCase):
    def archive(self, body):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("TEST-device.xml", body)
        return zipfile.ZipFile(io.BytesIO(stream.getvalue()))

    def test_reports_actual_pass_and_skip(self):
        body = '<testsuite tests="2" failures="0" errors="0" skipped="1"><testcase classname="A" name="first"/><testcase classname="A" name="second"><skipped/></testcase></testsuite>'
        with self.archive(body) as archive:
            counts, cases = audit.xml_cases(archive)
        self.assertEqual(counts, dict(tests=2, failures=0, errors=0, skipped=1))
        self.assertEqual(cases, {"A#first":"pass", "A#second":"skipped"})

    def test_duplicate_case_cannot_replace_a_missing_original(self):
        body = '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="A" name="same"/><testcase classname="A" name="same"/></testsuite>'
        with self.archive(body) as archive:
            with self.assertRaisesRegex(ValueError, "duplicate"):
                audit.xml_cases(archive)

    def test_declared_count_cannot_hide_missing_case(self):
        body = '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="A" name="one"/></testsuite>'
        with self.archive(body) as archive:
            with self.assertRaisesRegex(ValueError, "disagrees"):
                audit.xml_cases(archive)

    def test_green_summary_cannot_hide_failed_case(self):
        body = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="A" name="one"><failure>original case failed</failure></testcase></testsuite>'
        with self.archive(body) as archive:
            with self.assertRaisesRegex(ValueError, "disagrees"):
                audit.xml_cases(archive)
