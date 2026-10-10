#!/usr/bin/env bash
# Capture only generated QA evidence from the selected Android test device.
set -euo pipefail

DIAGNOSTICS="${1:-app/build/diagnostics/device}"
PACKAGE="${2:-com.mangalens}"
[[ "$PACKAGE" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || { echo 'Invalid QA package identity.' >&2; exit 2; }
mkdir -p "$DIAGNOSTICS/screenshots"
COMMAND_TIMEOUT="${MANGALENS_EVIDENCE_ADB_TIMEOUT:-30}"
[[ "$COMMAND_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo 'Evidence timeout must be positive seconds.' >&2; exit 2; }
: > "$DIAGNOSTICS/capture-status.txt"
capture() {
  local output="$1" status=0
  shift
  timeout --signal=TERM --kill-after=5s "$COMMAND_TIMEOUT" adb "$@" > "$DIAGNOSTICS/$output" 2>&1 || status=$?
  printf '%s exit=%s\n' "$output" "$status" >> "$DIAGNOSTICS/capture-status.txt"
}
capture adb-devices.txt devices -l
capture device-properties.txt shell getprop
capture display-size.txt shell wm size
capture display-density.txt shell wm density
capture font-scale.txt shell settings get system font_scale
capture installed-package.txt shell dumpsys package "$PACKAGE"
capture activities.txt shell dumpsys activity activities
capture last-anr.txt shell dumpsys activity lastanr
capture memory.txt shell dumpsys meminfo "$PACKAGE"
capture crash-logcat.txt logcat -d -b crash -v threadtime
capture logcat.txt logcat -d -t 10000 -v threadtime
capture native-startup.json shell run-as "$PACKAGE" cat files/mangalens-qa/native-startup/outputs.json
capture speech-reference.json shell run-as "$PACKAGE" cat files/mangalens-qa/speech-reference/outputs.json
SCREEN_STATUS=0
timeout --signal=TERM --kill-after=5s "$COMMAND_TIMEOUT" adb exec-out screencap -p > "$DIAGNOSTICS/final-screen.png" 2> "$DIAGNOSTICS/screencap-errors.txt" || SCREEN_STATUS=$?
printf 'final-screen.png exit=%s\n' "$SCREEN_STATUS" >> "$DIAGNOSTICS/capture-status.txt"
capture window-dump.txt shell uiautomator dump /data/local/tmp/mangalens-acceptance-window.xml
capture window-pull.txt pull /data/local/tmp/mangalens-acceptance-window.xml "$DIAGNOSTICS/window-hierarchy.xml"
capture qa-public-pull.txt pull /sdcard/Download/mangalens-qa "$DIAGNOSTICS/screenshots/"
capture qa-private-pull.txt pull "/sdcard/Android/data/$PACKAGE/files/qa" "$DIAGNOSTICS/screenshots/private-qa"

# Exact internal JSON-result directories written by the four UserSourceOcr suites.
# Never collect transient original-source directories, files/, databases or media.
INTERNAL_OCR_DIRECTORIES=(
  files/qa/ocr-user-source
  files/qa/ocr-user-source-pixels
  files/qa/ocr-user-source-contextual
  files/qa/ocr-user-source-original-region
)
mkdir -p "$DIAGNOSTICS/internal-ocr"
for OCR_DIRECTORY in "${INTERNAL_OCR_DIRECTORIES[@]}"; do
  OCR_NAME="${OCR_DIRECTORY##*/}"
  # The unvalidated stream stays outside publishable diagnostics and is always deleted.
  OCR_ARCHIVE="$(mktemp "/tmp/mangalens-internal-ocr.XXXXXX")"
  OCR_PIPE_STATUS=()
  if timeout --signal=TERM --kill-after=5s "$COMMAND_TIMEOUT" \
      adb exec-out run-as "$PACKAGE" tar -cf - "$OCR_DIRECTORY" \
      2> "$DIAGNOSTICS/internal-ocr/$OCR_NAME.stderr.txt" \
      | head -c 16777217 > "$OCR_ARCHIVE"; then
    OCR_PIPE_STATUS=("${PIPESTATUS[@]}")
  else
    OCR_PIPE_STATUS=("${PIPESTATUS[@]}")
  fi
  OCR_VALIDATION_STATUS=not_run
  if [[ "${OCR_PIPE_STATUS[0]}" == 0 && "${OCR_PIPE_STATUS[1]}" == 0 ]]; then
    OCR_VALIDATION_STATUS=0
    python3 - "$OCR_ARCHIVE" "$DIAGNOSTICS/internal-ocr" "$OCR_DIRECTORY" \
        > "$DIAGNOSTICS/internal-ocr/$OCR_NAME.result.json" \
        2> "$DIAGNOSTICS/internal-ocr/$OCR_NAME.validation-stderr.txt" <<'OCR_PY' || OCR_VALIDATION_STATUS=$?
#!/usr/bin/env python3
"""Validate and manually extract only generated JSON for the four named OCR QA suites."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import os
import re
import sys
import tarfile
import tempfile

ALLOWED = {
    "files/qa/ocr-user-source",
    "files/qa/ocr-user-source-pixels",
    "files/qa/ocr-user-source-contextual",
    "files/qa/ocr-user-source-original-region",
}
MAX_ARCHIVE = 16 * 1024 * 1024
MAX_FILE = 2 * 1024 * 1024
MAX_TOTAL = 8 * 1024 * 1024
MAX_MEMBERS = 128
EXPECTED_FIXTURE = "f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16"


def extract(archive: Path, destination: Path, remote: str) -> dict:
    if remote not in ALLOWED:
        raise ValueError("Directory is outside the generated OCR result allowlist")
    if not archive.is_file() or not 0 < archive.stat().st_size <= MAX_ARCHIVE:
        raise ValueError("Empty or oversized OCR evidence archive")
    prefix = tuple(remote.split("/"))
    payloads = []
    seen = set()
    total = 0
    # r: accepts the bounded uncompressed Android tar; no compressed expansion.
    with tarfile.open(archive, mode="r:") as source:
        for index, member in enumerate(source):
            if index >= MAX_MEMBERS:
                raise ValueError("Too many OCR evidence entries")
            name = member.name[:-1] if member.isdir() and member.name.endswith("/") else member.name
            if not name or name.startswith("/") or "\\" in name or any(ord(c) < 32 or ord(c) == 127 for c in name):
                raise ValueError("Invalid OCR evidence path")
            parts = tuple(name.split("/"))
            if any(p in {"", ".", ".."} for p in parts) or tuple(PurePosixPath(name).parts) != parts:
                raise ValueError("Noncanonical OCR evidence path")
            if name in seen:
                raise ValueError("Duplicate OCR evidence path")
            seen.add(name)
            if parts[:3] != prefix:
                raise ValueError("OCR archive contains a different private directory")
            if member.isdir():
                if parts != prefix:
                    raise ValueError("Unexpected nested OCR evidence directory")
                continue
            # Reject symlinks, hardlinks, devices and FIFOs even with in-scope targets.
            if not member.isfile() or member.linkname or len(parts) != 4:
                raise ValueError("OCR evidence must contain only direct regular JSON files")
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.json", parts[-1]):
                raise ValueError("OCR evidence contains a non-result file")
            if not 0 < member.size <= MAX_FILE:
                raise ValueError("Oversized OCR result")
            total += member.size
            if total > MAX_TOTAL:
                raise ValueError("OCR result total exceeds the bounded output budget")
            stream = source.extractfile(member)
            if stream is None:
                raise ValueError("Missing OCR result bytes")
            with stream:
                payload = stream.read(MAX_FILE + 1)
            if len(payload) != member.size:
                raise ValueError("Truncated OCR result")
            report = json.loads(payload.decode("utf-8"))
            if not isinstance(report, dict) or report.get("fixture_sha256") != EXPECTED_FIXTURE:
                raise ValueError("OCR result does not belong to the pinned controlled fixture")
            payloads.append((parts[-1], payload))
        end = source.offset
    with archive.open("rb") as remainder:
        remainder.seek(end)
        if any(remainder.read()):
            raise ValueError("Unexpected data after the OCR tar terminator")
    if not payloads:
        raise ValueError("No generated OCR JSON reports were present")
    # Validate the whole archive before creating any extracted report. Each call
    # gets a fresh 0700 staging tree, never follows archive paths or overwrites evidence.
    destination.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=prefix[-1] + "-verified-", dir=destination))
    outputs = []
    try:
        for name, payload in payloads:
            file = staging / name
            descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as out:
                out.write(payload)
            outputs.append({"file": name, "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
    except BaseException:
        for file in staging.iterdir():
            file.unlink()
        staging.rmdir()
        raise
    return {"remote_directory": remote, "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "output_directory": staging.name, "files": outputs, "total_bytes": total}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: extract-internal-ocr.py archive destination exact-remote-directory")
    print(json.dumps(extract(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]), indent=2))
OCR_PY
  fi
  rm -f "$OCR_ARCHIVE"
  printf 'directory=%s\nadb_exit=%s\nbyte_reader_exit=%s\nvalidation_exit=%s\n' \
    "$OCR_DIRECTORY" "${OCR_PIPE_STATUS[0]}" "${OCR_PIPE_STATUS[1]}" "$OCR_VALIDATION_STATUS" \
    > "$DIAGNOSTICS/internal-ocr/$OCR_NAME.status.txt"
  printf 'internal-ocr/%s adb_exit=%s validation_exit=%s\n' "$OCR_NAME" \
    "${OCR_PIPE_STATUS[0]}" "$OCR_VALIDATION_STATUS" >> "$DIAGNOSTICS/capture-status.txt"
done
