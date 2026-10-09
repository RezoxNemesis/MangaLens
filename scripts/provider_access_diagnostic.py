#!/usr/bin/env python3
"""Bounded host-only provider diagnostic; never publishes downloaded media."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time


VERSION = "2026.08.19"
ASSET_SIZE = 3_072_469
ASSET_SHA256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
BYTE_BUDGET = 1024 ** 3
METADATA_SECONDS = 90
DOWNLOAD_SECONDS = 300
PROBE_SECONDS = 90
LOG_BYTES = 16_384
COMMON_FLAGS = ["--ignore-config", "--no-plugin-dirs", "--no-remote-components", "--no-geo-bypass",
                "--no-playlist", "--no-cache-dir", "--no-colors", "--no-progress",
                "--socket-timeout", "15", "--retries", "0", "--fragment-retries", "0",
                "--extractor-retries", "0", "--concurrent-fragments", "1", "--js-runtimes", "node"]


class DiagnosticFailure(Exception):
    pass


@dataclass(frozen=True)
class Fixture:
    name: str
    url: str
    minimum_short_edge: int


FIXTURES = (
    Fixture("youtube", "https://youtu.be/RzasqVwpLOA?si=vn3VhAdIZKeUXgZE", 1080),
    Fixture("instagram", "https://www.instagram.com/reel/DeODl9XI0ex/?dlrf=MTljZnpjY21lbGh2Mw==", 0),
)


@dataclass
class CommandResult:
    returncode: int
    stdout: bytes
    stderr: bytes
    elapsed: float
    reason: str | None = None


def redact(value):
    value = re.sub(r"(?im)^.*(?:cookie|authorization|referer|origin)\s*[:=].*$", "[sensitive header redacted]", str(value))
    value = re.sub(r"(?i)\b(?:https?|ftp)://[^\s<>\"']+", "[URL redacted]", value)
    return re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", value)


def directory_bytes(directory):
    total = 0
    for parent, _, names in os.walk(directory, followlinks=False):
        for name in names:
            path = Path(parent, name)
            if not path.is_symlink():
                try:
                    total += path.stat().st_size
                except FileNotFoundError:
                    pass
    return total


def terminate_tree(process):
    # Kill the group even if its parent exited while a child still holds our pipe.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            break
        if sig == signal.SIGTERM:
            time.sleep(.1)
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired as failure:
        raise DiagnosticFailure("Process cleanup did not complete within its budget") from failure


def run_bounded(command, seconds, directory, *, output_limit=8_000_000, byte_budget=None):
    started = time.monotonic()
    def limit_file_size():
        if byte_budget is not None:
            resource.setrlimit(resource.RLIMIT_FSIZE, (byte_budget, byte_budget))
    process = subprocess.Popen(command, cwd=directory, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True, preexec_fn=limit_file_size)
    output = {"stdout": bytearray(), "stderr": bytearray()}
    reason = None
    selector = selectors.DefaultSelector()
    for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ, name)
    try:
        while selector.get_map() or process.poll() is None:
            if time.monotonic() - started >= seconds:
                reason = "timeout"
                break
            if byte_budget is not None and directory_bytes(directory) >= byte_budget:
                reason = "disk_budget"
                break
            for key, _ in selector.select(.05):
                chunk = os.read(key.fileobj.fileno(), 65_536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                room = output_limit - sum(len(value) for value in output.values())
                output[key.data].extend(chunk[:max(0, room)])
                if len(chunk) > room:
                    reason = "output_budget"
                    break
            if reason:
                break
        if byte_budget is not None and directory_bytes(directory) >= byte_budget:
            reason = reason or "disk_budget"
    finally:
        terminate_tree(process)
        selector.close()
        process.stdout.close()
        process.stderr.close()
    return CommandResult(process.returncode, bytes(output["stdout"]), bytes(output["stderr"]),
                         round(time.monotonic() - started, 3), reason)


def require_command(result, phase):
    if result.reason or result.returncode != 0:
        message = redact(result.stderr.decode("utf-8", "replace"))[-2000:]
        raise DiagnosticFailure(f"{phase} failed: {result.reason or 'exit ' + str(result.returncode)}; {message}")


def number(value):
    try:
        value = float(value)
        return value if math.isfinite(value) and value >= 0 else None
    except (TypeError, ValueError):
        return None


def metadata_receipt(metadata):
    def allowed(row):
        result = {}
        for key in ("id", "extractor_key", "format_id", "ext", "vcodec", "acodec"):
            value = row.get(key)
            if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.+/-]{1,100}", value):
                result[key] = value
        for key in ("duration", "width", "height", "fps", "tbr", "abr", "filesize", "filesize_approx"):
            value = number(row.get(key))
            if value is not None:
                result[key] = value
        for key in ("is_live", "has_drm"):
            if isinstance(row.get(key), bool):
                result[key] = row[key]
        return result
    result = allowed(metadata)
    formats = metadata.get("formats") or []
    result["formats"] = [allowed(row) for row in formats[:100] if isinstance(row, dict)]
    return result


def verify_probe(probe, metadata, minimum_short_edge):
    streams = probe.get("streams", [])
    video = [stream for stream in streams if stream.get("codec_type") == "video" and stream.get("codec_name") not in (None, "unknown", "none")]
    audio = [stream for stream in streams if stream.get("codec_type") == "audio" and stream.get("codec_name") not in (None, "unknown", "none")]
    if not video:
        raise DiagnosticFailure("Downloaded file has no decoded video stream")
    if not audio or not any((number(stream.get("nb_read_frames")) or 0) > 0 for stream in audio):
        raise DiagnosticFailure("Downloaded file has no decoded audio frames")
    stream = max(video, key=lambda row: (number(row.get("width")) or 0) * (number(row.get("height")) or 0))
    width, height = int(number(stream.get("width")) or 0), int(number(stream.get("height")) or 0)
    if min(width, height) < max(1, minimum_short_edge):
        raise DiagnosticFailure(f"Downloaded {width}x{height} video is below the required {minimum_short_edge}p quality")
    frames = int(number(stream.get("nb_read_frames")) or 0)
    if frames <= 0:
        raise DiagnosticFailure("Downloaded file has no decoded video frames")
    duration = number(probe.get("format", {}).get("duration"))
    if duration is None or duration <= 0:
        raise DiagnosticFailure("Downloaded file has no finite positive duration")
    expected = number(metadata.get("duration"))
    if expected and abs(duration - expected) > max(2, expected * .02):
        raise DiagnosticFailure(f"Downloaded duration {duration}s differs from provider duration {expected}s")
    return {"width": width, "height": height, "duration_seconds": duration, "video_frames_decoded": frames,
            "audio_present": True, "audio_streams": len(audio), "audio_frames_decoded": sum(int(number(row.get("nb_read_frames")) or 0) for row in audio)}


def attempt_all(attempt):
    results = []
    for fixture in FIXTURES:
        try:
            results.append(attempt(fixture))
        except Exception as failure:
            results.append({"name": fixture.name, "status": "failed", "error": redact(failure)[:3000], "host_only": True})
    return results


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def phase_receipt(result):
    return {"exit_code": result.returncode, "elapsed_seconds": result.elapsed, "stop_reason": result.reason}


def inspect_fixture(fixture, asset, evidence_dir):
    report = {"name": fixture.name, "status": "failed", "host_only": True,
              "minimum_short_edge": fixture.minimum_short_edge, "phases": {}}
    logs = []
    # Media and unredacted provider output never live in the artifact directory.
    with tempfile.TemporaryDirectory(prefix=f"mangalens-provider-{fixture.name}-") as scratch:
        directory = Path(scratch)
        base = [sys.executable, str(asset), *COMMON_FLAGS]
        metadata = {}
        metadata_error = None
        try:
            result = run_bounded([*base, "--skip-download", "--dump-single-json", fixture.url], METADATA_SECONDS, directory)
            report["phases"]["metadata"] = phase_receipt(result)
            logs.append("metadata:\n" + redact(result.stderr.decode("utf-8", "replace"))[-LOG_BYTES:])
            require_command(result, "metadata")
            metadata = json.loads(result.stdout)
            if not isinstance(metadata, dict):
                raise DiagnosticFailure("Provider returned invalid metadata")
            report["metadata"] = metadata_receipt(metadata)
        except Exception as failure:
            metadata_error = redact(failure)[:3000]
            report["metadata_error"] = metadata_error
            metadata = {}
        try:
            if metadata.get("has_drm"):
                raise DiagnosticFailure("Provider reports DRM; this diagnostic does not bypass it")
            if metadata.get("is_live"):
                raise DiagnosticFailure("Live media cannot satisfy a complete finite download check")
            selector = "bestvideo[height>=1080]+bestaudio/best[height>=1080]" if fixture.minimum_short_edge else "bestvideo+bestaudio/best"
            result = run_bounded([*base, "--no-simulate", "--format", selector, "--merge-output-format", "mkv",
                                  "--abort-on-unavailable-fragments", "--max-filesize", str(BYTE_BUDGET),
                                  "--output", str(directory / "media.%(ext)s"), fixture.url], DOWNLOAD_SECONDS, directory, byte_budget=BYTE_BUDGET)
            report["phases"]["download"] = phase_receipt(result)
            logs.append("download:\n" + redact(result.stderr.decode("utf-8", "replace"))[-LOG_BYTES:])
            require_command(result, "download")
            files = [path for path in directory.iterdir() if path.is_file()]
            if len(files) != 1 or files[0].suffix in (".part", ".ytdl", ".temp"):
                raise DiagnosticFailure("Download did not leave exactly one complete media file")
            media = files[0]
            if not 0 < media.stat().st_size < BYTE_BUDGET:
                raise DiagnosticFailure("Download exceeds the per-source size budget or is empty")
            result = run_bounded(["ffprobe", "-v", "error", "-count_frames", "-show_entries",
                                  "format=duration:stream=index,codec_type,codec_name,width,height,nb_read_frames", "-of", "json", str(media)],
                                 PROBE_SECONDS, directory)
            report["phases"]["ffprobe"] = phase_receipt(result)
            logs.append("ffprobe:\n" + redact(result.stderr.decode("utf-8", "replace"))[-LOG_BYTES:])
            require_command(result, "ffprobe")
            if result.stderr.strip():
                raise DiagnosticFailure("ffprobe reported decode errors; the download is not verified")
            report["verification"] = verify_probe(json.loads(result.stdout), metadata, fixture.minimum_short_edge)
            report["verification"].update({"sha256": file_hash(media), "bytes": media.stat().st_size,
                                            "complete_download_exit_zero": True})
            if metadata_error:
                raise DiagnosticFailure("Downloaded file verified, but provider metadata did not complete: " + metadata_error)
            report["status"] = "passed"
        except Exception as failure:
            report["error"] = redact(failure)[:3000]
        finally:
            (evidence_dir / f"{fixture.name}.log").write_text("\n\n".join(logs)[-LOG_BYTES * 3:], encoding="utf-8")
    report["temporary_media_removed"] = not Path(scratch).exists()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--output-dir", type=Path, default=Path("dist/provider-access"))
    args = parser.parse_args()
    evidence = args.output_dir.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    runtime = {"yt_dlp_version": VERSION, "asset_sha256": ASSET_SHA256, "source_sha": os.environ.get("MANGALENS_SOURCE_SHA", "local"),
               "assessment": "Host-only provider access and downloaded-file verification. This is not Android/app playback acceptance.",
               "artifact_visibility": "Public repository evidence; no media, raw provider metadata, URLs, cookies or headers retained.",
               "limits": {"metadata_seconds": METADATA_SECONDS, "download_seconds": DOWNLOAD_SECONDS, "ffprobe_seconds": PROBE_SECONDS,
                          "per_file_hard_bytes": BYTE_BUDGET, "aggregate_temp_abort_bytes": BYTE_BUDGET}}
    asset = (args.repo_root / "app/src/main/res/raw/ytdlp").resolve()
    initialization_error = None
    try:
        if not asset.is_file() or asset.stat().st_size != ASSET_SIZE or file_hash(asset) != ASSET_SHA256:
            raise DiagnosticFailure("Repository extractor does not match its reviewed size/SHA pin")
        if not shutil.which("ffprobe") or not shutil.which("ffmpeg") or not shutil.which("node"):
            raise DiagnosticFailure("ffprobe, ffmpeg and Node.js are required")
        with tempfile.TemporaryDirectory(prefix="mangalens-provider-version-") as scratch:
            version = run_bounded([sys.executable, str(asset), *COMMON_FLAGS, "--version"], 20, Path(scratch))
            require_command(version, "extractor version")
            if version.stdout.decode("utf-8", "strict").strip() != VERSION:
                raise DiagnosticFailure("Repository extractor version does not match its reviewed pin")
    except Exception as failure:
        initialization_error = redact(failure)[:3000]
        runtime["initialization_error"] = initialization_error
    def attempt(fixture):
        if initialization_error:
            raise DiagnosticFailure(initialization_error)
        return inspect_fixture(fixture, asset, evidence)
    results = attempt_all(attempt)
    payload = json.dumps({"runtime": runtime, "results": results}, indent=2, allow_nan=False)
    if len(payload.encode("utf-8")) > 256_000:
        raise DiagnosticFailure("Redacted evidence exceeded its size budget")
    (evidence / "status.json").write_text(payload + "\n", encoding="utf-8")
    for result in results:
        print(result["name"] + ": " + result["status"] + " (host diagnostic)")
    return 0 if all(result["status"] == "passed" for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
