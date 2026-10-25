import unittest
from test_go import partition, verify


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


if __name__ == "__main__":
    unittest.main()
