#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/diagnostics/screenshots
collect_regression() {
  adb pull /sdcard/Download/mangalens-qa app/build/diagnostics/screenshots || true
  adb pull /sdcard/Android/data/com.mangalens/files app/build/diagnostics/screenshots/lettering-fixtures || true
  adb logcat -d -v threadtime > app/build/diagnostics/regression-logcat.txt || true
  adb shell run-as com.mangalens cat files/mangalens-qa/native-startup/outputs.json > app/build/diagnostics/native-startup.json || true
  adb shell run-as com.mangalens cat files/mangalens-qa/speech-reference/outputs.json > app/build/diagnostics/speech-reference.json || true
  # Inline test-fixture images allow remote review when the execution workspace is offline.
  # Evidence can include the explicitly supplied public chapter fixture; no source account is used here.
  python3 - <<'PY'
import base64
import xml.etree.ElementTree as ET
from pathlib import Path
for root_dir in ("app/build/test-results/testDebugUnitTest", "app/build/outputs/androidTest-results"):
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for xml in Path(root_dir).rglob("TEST-*.xml"):
        try:
            suite = ET.parse(xml).getroot()
            for key in counts:
                counts[key] += int(suite.attrib.get(key, 0))
            for case in suite.iter("testcase"):
                for failure in list(case):
                    if failure.tag in ("failure", "error"):
                        print("MANGALENS_TEST_FAILURE", case.attrib.get("classname"), case.attrib.get("name"), (failure.text or failure.attrib.get("message", ""))[:5000])
        except ET.ParseError:
            pass
    print("MANGALENS_TEST_COUNTS", root_dir, counts)
for path in sorted(Path("app/build/diagnostics/screenshots").rglob("*.png")):
    if path.stat().st_size <= 1_000_000:
        print("MANGALENS_QA_PNG " + path.name + " " + base64.b64encode(path.read_bytes()).decode("ascii"))
for path in Path("app/build/diagnostics/screenshots").rglob("native-model.txt"):
    print("MANGALENS_NATIVE_QA " + path.read_text())
PY
}
trap collect_regression EXIT
MODEL_FIXTURE="${RUNNER_TEMP:-/tmp}/mangalens-qa-model.gguf"
curl --fail --location --retry 3 --connect-timeout 30 --max-time 600 \
  'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q6_k.gguf?download=true' \
  --output "$MODEL_FIXTURE"
echo "2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5  $MODEL_FIXTURE" | sha256sum --check
test "$(wc -c < "$MODEL_FIXTURE")" -eq 650379104
adb push "$MODEL_FIXTURE" /data/local/tmp/mangalens-qa-model.gguf
adb shell run-as com.mangalens mkdir -p files/orez_models
adb shell run-as com.mangalens cp /data/local/tmp/mangalens-qa-model.gguf files/orez_models/qwen2.5-0.5b-q6_k.gguf
adb shell rm /data/local/tmp/mangalens-qa-model.gguf
rm -f "$MODEL_FIXTURE"
bash scripts/android/stage-speech-reference.sh com.mangalens app/build/diagnostics
gradle --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.require_model=true \
  -Pandroid.testInstrumentationRunnerArguments.whisper_model_path=/data/user/0/com.mangalens/files/privateqa/speech/ggml-tiny.bin \
  -Pandroid.testInstrumentationRunnerArguments.reference_audio_path=/data/user/0/com.mangalens/files/privateqa/speech/jfk.wav \
  -Pandroid.testInstrumentationRunnerArguments.sample_video=/data/user/0/com.mangalens/files/privateqa/video/jfk-two-pass-720p.mp4 \
  --stacktrace
