import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest


DRIVER = Path(__file__).with_name("provider_access_diagnostic.py")


def load_driver():
    if not DRIVER.is_file():
        return None
    spec = importlib.util.spec_from_file_location("provider_diagnostic", DRIVER)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ProviderDiagnosticTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.driver = load_driver()

    def implementation(self):
        self.assertIsNotNone(self.driver, "The provider diagnostic has no implementation yet")
        return self.driver

    def probe(self, width=1920, height=1080, duration="30.0", audio=True):
        streams = [{"index": 0, "codec_type": "video", "codec_name": "h264", "width": width,
                    "height": height, "nb_read_frames": "900"}]
        if audio:
            streams.append({"index": 1, "codec_type": "audio", "codec_name": "aac", "nb_read_frames": "1400"})
        return {"format": {"duration": duration}, "streams": streams}

    def test_actual_1080p_video_audio_and_duration_produce_measurements(self):
        d = self.implementation()
        receipt = d.verify_probe(self.probe(), {"duration": 30}, 1080)
        self.assertEqual((1920, 1080), (receipt["width"], receipt["height"]))
        self.assertTrue(receipt["audio_present"])
        self.assertEqual(30.0, receipt["duration_seconds"])

    def test_720p_cannot_be_reported_as_the_requested_youtube_quality(self):
        d = self.implementation()
        with self.assertRaisesRegex(d.DiagnosticFailure, "1080"):
            d.verify_probe(self.probe(1280, 720), {"duration": 30}, 1080)

    def test_portrait_720_by_1280_is_not_1080p(self):
        d = self.implementation()
        with self.assertRaises(d.DiagnosticFailure):
            d.verify_probe(self.probe(720, 1280), {"duration": 30}, 1080)

    def test_silent_or_truncated_download_cannot_pass(self):
        d = self.implementation()
        with self.assertRaisesRegex(d.DiagnosticFailure, "audio"):
            d.verify_probe(self.probe(audio=False), {"duration": 30}, 1080)
        with self.assertRaisesRegex(d.DiagnosticFailure, "duration"):
            d.verify_probe(self.probe(duration="2"), {"duration": 30}, 1080)

    def test_zero_decoded_frames_and_nonfinite_duration_cannot_pass(self):
        d = self.implementation()
        probe = self.probe()
        probe["streams"][0]["nb_read_frames"] = "0"
        with self.assertRaises(d.DiagnosticFailure):
            d.verify_probe(probe, {"duration": 30}, 1080)
        with self.assertRaises(d.DiagnosticFailure):
            d.verify_probe(self.probe(duration="nan"), {}, 0)

    def test_signed_urls_headers_and_tracking_values_never_enter_evidence(self):
        d = self.implementation()
        text = "ERROR https://user:password@cdn.example/video?token=private&sig=secret\nCookie: session=private\nAuthorization: Bearer hidden\nReferer: https://private.example/account\n"
        redacted = d.redact(text)
        for private in ("password", "private", "secret", "hidden", "session", "Bearer", "account"):
            self.assertNotIn(private, redacted)
        summary = d.metadata_receipt({"id": "public-id", "duration": 30, "url": "https://cdn.example/signed?token=private",
            "http_headers": {"Cookie": "private"}, "formats": [{"format_id": "137", "height": 1080, "url": "secret", "http_headers": {"Authorization": "hidden"}}]})
        encoded = json.dumps(summary)
        self.assertIn("137", encoded)
        for private in ("url", "http_headers", "private", "secret", "hidden"):
            self.assertNotIn(private, encoded)

    def test_timeout_kills_real_child_tree_instead_of_waiting_for_it(self):
        d = self.implementation()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child_pid = root / "child.pid"
            code = "import subprocess,sys,time; p=subprocess.Popen([sys.executable,'-c','import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)']); open(sys.argv[1],'w').write(str(p.pid)); time.sleep(60)"
            started = time.monotonic()
            result = d.run_bounded([sys.executable, "-c", code, str(child_pid)], .4, root)
            self.assertEqual("timeout", result.reason)
            self.assertLess(time.monotonic() - started, 3)
            pid = int(child_pid.read_text())
            state = Path(f"/proc/{pid}/stat")
            # A killed child can be a zombie until its parent/reaper collects it.
            deadline = time.monotonic() + 1
            while state.exists() and state.read_text().split()[2] != "Z" and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(not state.exists() or state.read_text().split()[2] == "Z")

    def test_output_flood_and_disk_growth_are_bounded_failures(self):
        d = self.implementation()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = d.run_bounded([sys.executable, "-c", "import os; os.write(1,b'x'*100000);"], 3, root, output_limit=1024)
            self.assertEqual("output_budget", result.reason)
            result = d.run_bounded([sys.executable, "-c", "import pathlib,time; pathlib.Path('large.part').write_bytes(b'x'*100000); time.sleep(3)"], 2, root, byte_budget=1024)
            self.assertEqual("disk_budget", result.reason)

    def test_second_fixture_is_attempted_after_first_failure(self):
        d = self.implementation()
        attempted = []
        def probe(fixture):
            attempted.append(fixture.name)
            if fixture.name == "youtube":
                raise d.DiagnosticFailure("provider denied access")
            return {"name": fixture.name, "status": "passed", "host_only": True}
        results = d.attempt_all(probe)
        self.assertEqual(["youtube", "instagram"], attempted)
        self.assertEqual(["failed", "passed"], [result["status"] for result in results])

    def test_nonzero_process_exit_remains_a_failure_even_with_json_output(self):
        d = self.implementation()
        with tempfile.TemporaryDirectory() as directory:
            result = d.run_bounded([sys.executable, "-c", "import sys; print('{}'); sys.exit(7)"], 2, Path(directory))
            self.assertEqual(7, result.returncode)
            with self.assertRaises(d.DiagnosticFailure):
                d.require_command(result, "download")

    def test_wrong_repo_asset_pin_fails_both_sources_without_executing_it(self):
        d = self.implementation()
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = root / "app/src/main/res/raw/ytdlp"
            asset.parent.mkdir(parents=True)
            asset.write_text("raise SystemExit('untrusted asset executed')")
            evidence = root / "evidence"
            result = subprocess.run([sys.executable, str(DRIVER), "--repo-root", str(root), "--output-dir", str(evidence)], capture_output=True, timeout=5)
            self.assertEqual(1, result.returncode)
            report = json.loads((evidence / "status.json").read_text())
            self.assertEqual(["failed", "failed"], [row["status"] for row in report["results"]])
            self.assertIn("size/SHA", report["runtime"]["initialization_error"])
            self.assertNotIn("untrusted asset executed", (evidence / "status.json").read_text())

    def test_real_synthetic_media_runs_all_phases_and_only_redacted_text_remains(self):
        d = self.implementation()
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            media = root / "synthetic.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=1920x1080:r=10:d=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest", str(media)], check=True, timeout=30)
            fake = root / "fake-extractor.py"
            fake.write_text("import json,pathlib,shutil,sys\n"
                "if '--dump-single-json' in sys.argv:\n"
                " print(json.dumps({'duration':2,'url':'https://cdn.example/video?token=private','http_headers':{'Cookie':'session=private'}}))\n"
                "else:\n"
                f" shutil.copyfile({str(media)!r},pathlib.Path.cwd()/'media.mp4')\n"
                " print('Cookie: session=private',file=sys.stderr)\n")
            evidence = root / "evidence"
            evidence.mkdir()
            result = d.inspect_fixture(d.FIXTURES[0], fake, evidence)
            self.assertEqual("passed", result["status"])
            self.assertEqual(1080, result["verification"]["height"])
            self.assertTrue(result["verification"]["audio_present"])
            self.assertEqual(d.file_hash(media), result["verification"]["sha256"])
            self.assertTrue(result["temporary_media_removed"])
            self.assertEqual(["youtube.log"], [path.name for path in evidence.iterdir()])
            self.assertNotIn("private", json.dumps(result) + (evidence / "youtube.log").read_text())
            fake.write_text("import json,pathlib,shutil,sys\n"
                "if 'youtu.be' in sys.argv[-1]:\n"
                " print('ERROR provider denied access https://cdn.example/stream?token=private',file=sys.stderr); sys.exit(7)\n"
                "if '--dump-single-json' in sys.argv:\n"
                " print(json.dumps({'duration':2}))\n"
                "else:\n"
                f" shutil.copyfile({str(media)!r},pathlib.Path.cwd()/'media.mp4')\n")
            results = d.attempt_all(lambda fixture: d.inspect_fixture(fixture, fake, evidence))
            self.assertEqual(["failed", "passed"], [row["status"] for row in results])
            self.assertEqual(7, results[0]["phases"]["metadata"]["exit_code"])
            self.assertEqual(7, results[0]["phases"]["download"]["exit_code"])
            self.assertTrue(all(row["temporary_media_removed"] for row in results))
            self.assertEqual({"youtube.log", "instagram.log"}, {path.name for path in evidence.iterdir()})
            self.assertNotIn("private", json.dumps(results) + (evidence / "youtube.log").read_text())


if __name__ == "__main__":
    unittest.main()
