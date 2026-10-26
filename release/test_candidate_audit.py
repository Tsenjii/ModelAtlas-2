import io
import unittest
import zipfile
from pathlib import Path
import tempfile
import audit_candidate as audit

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
