#!/usr/bin/env python3
"""Turn AndroidJUnitRunner's raw status stream into blocking, reviewable results."""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_results(raw: str, required_classes: list[str]) -> dict:
    pending: dict[str, str] = {}
    active: dict[str, str] = {}
    cases = []
    runner_codes = []
    declared_tests = set()
    sequence_errors = []
    continuation = None
    for line in raw.splitlines():
        field = re.match(r"INSTRUMENTATION_STATUS: ([^=]+)=(.*)", line)
        status = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", line)
        runner = re.match(r"INSTRUMENTATION_CODE: (-?\d+)\s*$", line)
        if field:
            continuation, value = field.groups()
            pending[continuation] = value
        elif status:
            code = int(status.group(1))
            details = {**active, **pending}
            if details.get("numtests", "").isdigit():
                declared_tests.add(int(details["numtests"]))
            if code == 1:
                if active:
                    sequence_errors.append("A new test started before the previous test finished.")
                active = details
            elif code in (0, -1, -2, -3, -4) and details.get("class") and details.get("test"):
                cases.append({
                    "class": details["class"], "test": details["test"],
                    "status_code": code,
                    "status": "passed" if code == 0 else "skipped" if code in (-3, -4) else "failed",
                    "detail": details.get("stack", details.get("stream", "")).strip(),
                })
                active = {}
            pending = {}
            continuation = None
        elif runner:
            runner_codes.append(int(runner.group(1)))
            continuation = None
        elif line.startswith("INSTRUMENTATION_"):
            continuation = None
        elif continuation:
            pending[continuation] += "\n" + line

    errors = list(sequence_errors)
    if runner_codes != [-1]:
        errors.append(f"Runner did not finish normally (completion codes: {runner_codes}).")
    if active:
        errors.append("Runner stopped with an unfinished test.")
    if not cases:
        errors.append("No completed tests were reported.")
    if declared_tests and declared_tests != {len(cases)}:
        errors.append(f"Expected {sorted(declared_tests)} tests but observed {len(cases)} completions.")
    if re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|FAILURES!!!", raw):
        errors.append("Instrumentation reported a failure or abort.")
    for name in required_classes:
        matching = [case for case in cases if case["class"] == name]
        if not matching:
            errors.append(f"Required Android test class did not execute: {name}")
        elif any(case["status"] != "passed" for case in matching):
            errors.append(f"Required Android test class did not fully pass: {name}")
    counts = {state: sum(case["status"] == state for case in cases) for state in ("passed", "failed", "skipped")}
    if counts["failed"]:
        errors.append(f"{counts['failed']} Android test(s) failed.")
    return {"passed": not errors, "counts": counts, "declared_test_counts": sorted(declared_tests),
            "runner_codes": runner_codes, "errors": errors, "tests": cases}


def write_junit(result: dict, destination: Path) -> None:
    suite = ET.Element("testsuite", name="MangaLens device acceptance",
                       tests=str(len(result["tests"]) + len(result["errors"])),
                       failures=str(result["counts"]["failed"] + len(result["errors"])),
                       skipped=str(result["counts"]["skipped"]))
    for case in result["tests"]:
        element = ET.SubElement(suite, "testcase", classname=case["class"], name=case["test"])
        if case["status"] == "failed":
            ET.SubElement(element, "failure", message="Android instrumentation failed").text = case["detail"]
        elif case["status"] == "skipped":
            ET.SubElement(element, "skipped", message=case["detail"] or "Optional fixture was not supplied")
    for error in result["errors"]:
        element = ET.SubElement(suite, "testcase", classname="AndroidJUnitRunner", name=error)
        ET.SubElement(element, "failure", message=error)
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(destination, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--required-classes", default="")
    args = parser.parse_args()
    required = [name for name in args.required_classes.split(",") if name]
    result = parse_results(args.log.read_text(errors="replace"), required)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(result, indent=2) + "\n")
    write_junit(result, args.junit)
    print(json.dumps({key: value for key, value in result.items() if key != "tests"}, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
