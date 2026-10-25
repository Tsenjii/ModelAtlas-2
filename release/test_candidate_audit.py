import io
import unittest
import zipfile
import audit_candidate as audit

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
