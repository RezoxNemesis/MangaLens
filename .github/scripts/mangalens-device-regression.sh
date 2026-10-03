#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/diagnostics/screenshots
collect_regression() {
  adb pull /sdcard/Download/mangalens-qa app/build/diagnostics/screenshots || true
  adb logcat -d -v threadtime > app/build/diagnostics/regression-logcat.txt || true
  # Inline test-fixture images allow remote review when the execution workspace is offline.
  # These screens contain only generated QA data; source accounts are never used here.
  python3 - <<'PY'
import base64
from pathlib import Path
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
  'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q6_k.gguf?download=true' \
  --output "$MODEL_FIXTURE"
echo "526ade7343ce34f493ff03c6231e82d78f1eab59284541c3530fc4285911d641  $MODEL_FIXTURE" | sha256sum --check
test "$(wc -c < "$MODEL_FIXTURE")" -eq 505736512
adb push "$MODEL_FIXTURE" /data/local/tmp/mangalens-qa-model.gguf
adb shell run-as com.mangalens mkdir -p files/orez_models
adb shell run-as com.mangalens cp /data/local/tmp/mangalens-qa-model.gguf files/orez_models/qwen2.5-0.5b-q6_k.gguf
adb shell rm /data/local/tmp/mangalens-qa-model.gguf
rm -f "$MODEL_FIXTURE"
gradle --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.require_model=true --stacktrace
