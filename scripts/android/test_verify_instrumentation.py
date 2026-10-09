"""Regression cases for Android's exit-zero failure and partial-result streams."""

import importlib.util
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("instrumentation_results", Path(__file__).with_name("verify-instrumentation.py"))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


def test_event(code: int, name: str = "opensReader", count: int = 1, classname: str = "com.mangalens.ProductSmokeTest") -> str:
    return (f"INSTRUMENTATION_STATUS: class={classname}\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            f"INSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


class InstrumentationResultsTest(unittest.TestCase):
    def test_accepts_completed_required_device_test(self):
        report = results.parse_results(test_event(1) + test_event(0) + "INSTRUMENTATION_CODE: -1\n", ["com.mangalens.ProductSmokeTest"])
        self.assertTrue(report["passed"])
        self.assertEqual({"passed": 1, "failed": 0, "skipped": 0}, report["counts"])

    def test_android_normal_finish_code_does_not_hide_assertion_failure(self):
        stream = test_event(1) + test_event(-2) + "INSTRUMENTATION_CODE: -1\n"
        self.assertFalse(results.parse_results(stream, [])["passed"])

    def test_process_crash_does_not_count_as_completion(self):
        stream = test_event(1) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertFalse(results.parse_results(stream, [])["passed"])

    def test_empty_run_cannot_pass(self):
        self.assertFalse(results.parse_results("INSTRUMENTATION_CODE: -1\n", [])["passed"])

    def test_missing_required_core_screen_cannot_pass(self):
        stream = test_event(1) + test_event(0) + "INSTRUMENTATION_CODE: -1\n"
        self.assertFalse(results.parse_results(stream, ["com.mangalens.WebNavigationSmokeTest"])["passed"])

    def test_required_core_screen_cannot_be_skipped(self):
        stream = test_event(1) + test_event(-4) + "INSTRUMENTATION_CODE: -1\n"
        self.assertFalse(results.parse_results(stream, ["com.mangalens.ProductSmokeTest"])["passed"])

    def test_optional_fixture_skip_is_reported_separately(self):
        stream = test_event(1, count=2) + test_event(0, count=2)
        stream += test_event(1, "optionalSpeechSample", count=2, classname="com.mangalens.LiveSpeechSampleTest")
        stream += test_event(-4, "optionalSpeechSample", count=2, classname="com.mangalens.LiveSpeechSampleTest")
        report = results.parse_results(stream + "INSTRUMENTATION_CODE: -1\n", ["com.mangalens.ProductSmokeTest"])
        self.assertTrue(report["passed"])
        self.assertEqual(1, report["counts"]["skipped"])

    def test_truncated_stream_cannot_pass_as_smaller_suite(self):
        stream = test_event(1, count=2) + test_event(0, count=2) + "INSTRUMENTATION_CODE: -1\n"
        self.assertFalse(results.parse_results(stream, [])["passed"])

    def test_new_test_cannot_overwrite_unfinished_test(self):
        stream = test_event(1, count=2) + test_event(1, "nextTest", count=2) + test_event(0, "nextTest", count=2)
        self.assertFalse(results.parse_results(stream + "INSTRUMENTATION_CODE: -1\n", [])["passed"])


if __name__ == "__main__":
    unittest.main()
