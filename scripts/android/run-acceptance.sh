#!/usr/bin/env bash
# Run already-built APKs on one booted emulator; never build or erase app data.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

SUITE=core
APP_APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
DIAGNOSTICS=app/build/diagnostics/device-acceptance
TEST_TIMEOUT=1800
INSTALL_TIMEOUT=600
REQUIRE_MODEL=false
CLASSES=""
CORE_CLASSES=com.mangalens.ProductSmokeTest,com.mangalens.WatchPermissionSmokeTest,com.mangalens.WebNavigationSmokeTest,com.mangalens.AppearanceSmokeTest,com.mangalens.ReaderRestorationSmokeTest
while (($#)); do
  case "$1" in
    --suite) SUITE="$2"; shift 2 ;;
    --app-apk) APP_APK="$2"; shift 2 ;;
    --test-apk) TEST_APK="$2"; shift 2 ;;
    --evidence-dir) DIAGNOSTICS="$2"; shift 2 ;;
    --timeout) TEST_TIMEOUT="$2"; shift 2 ;;
    --install-timeout) INSTALL_TIMEOUT="$2"; shift 2 ;;
    --classes) CLASSES="$2"; shift 2 ;;
    --require-model) REQUIRE_MODEL=true; shift ;;
    --help)
      echo "Usage: $0 [--suite core|full] [--require-model] [--classes class1,class2] [--app-apk path] [--test-apk path] [--evidence-dir path] [--timeout seconds] [--install-timeout seconds]"
      echo 'Set ANDROID_SERIAL to select a dedicated QA emulator. Full native-model coverage downloads the pinned free 491 MB Qwen Lite fixture.'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$SUITE" == core || "$SUITE" == full ]] || { echo 'Suite must be core or full.' >&2; exit 2; }
[[ "$TEST_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo 'Timeout must be positive seconds.' >&2; exit 2; }
[[ "$INSTALL_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo 'Install timeout must be positive seconds.' >&2; exit 2; }
REQUIRED_CLASSES="$CORE_CLASSES"
if [[ "$REQUIRE_MODEL" == true ]]; then REQUIRED_CLASSES+=,com.mangalens.NativeModelTest; fi
if [[ "$SUITE" == full ]]; then
  REQUIRED_CLASSES+=,com.mangalens.ui.video.SpeechReferenceAcceptanceTest,com.mangalens.ui.video.LiveSpeechSampleTest,com.mangalens.UploadedMediaTest,com.mangalens.UserSuppliedChapterAcceptanceTest
fi
if [[ -n "$CLASSES" ]]; then
  REQUIRED_CLASSES="$CLASSES"
elif [[ "$SUITE" == core ]]; then
  CLASSES="$REQUIRED_CLASSES"
fi
test -s "$APP_APK"
test -s "$TEST_APK"
mkdir -p "$DIAGNOSTICS"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
AAPT="$(find "$SDK_ROOT/build-tools" -maxdepth 2 -name aapt -type f | sort -V | tail -n 1)"
test -x "$AAPT"
"$AAPT" dump badging "$APP_APK" > "$DIAGNOSTICS/apk-badging.txt"
PACKAGE="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$DIAGNOSTICS/apk-badging.txt")"
ACTIVITY="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" "$DIAGNOSTICS/apk-badging.txt")"
[[ "$PACKAGE" =~ ^[a-zA-Z0-9_.]+$ && "$ACTIVITY" =~ ^[a-zA-Z0-9_.]+$ ]]
SOURCE_SHA="${MANGALENS_GIT_SHA:-$(git rev-parse HEAD)}"
test "$SOURCE_SHA" = "$(git rev-parse HEAD)"
export MANGALENS_ACCEPTANCE_SHA="$SOURCE_SHA" MANGALENS_ACCEPTANCE_SUITE="$SUITE"
export MANGALENS_ACCEPTANCE_APK="$APP_APK" MANGALENS_ACCEPTANCE_TEST_APK="$TEST_APK"
export MANGALENS_ACCEPTANCE_PACKAGE="$PACKAGE"
export MANGALENS_ACCEPTANCE_CLASSES="$CLASSES" MANGALENS_ACCEPTANCE_REQUIRED_CLASSES="$REQUIRED_CLASSES"
python3 - "$DIAGNOSTICS" <<'PY'
import hashlib, json, os, platform, subprocess, sys
from pathlib import Path
def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()
def checksum(name):
    digest = hashlib.sha256()
    with Path(name).open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()
record = {
    'source_sha': os.environ['MANGALENS_ACCEPTANCE_SHA'],
    'dirty_source': bool(git('status', '--porcelain')),
    'source_status': git('status', '--porcelain').splitlines(),
    'suite': os.environ['MANGALENS_ACCEPTANCE_SUITE'],
    'test_classes': os.environ['MANGALENS_ACCEPTANCE_CLASSES'].split(',') if os.environ['MANGALENS_ACCEPTANCE_CLASSES'] else 'all discovered instrumentation tests',
    'required_classes': os.environ['MANGALENS_ACCEPTANCE_REQUIRED_CLASSES'].split(','),
    'app_apk': os.environ['MANGALENS_ACCEPTANCE_APK'],
    'app_apk_sha256': checksum(os.environ['MANGALENS_ACCEPTANCE_APK']),
    'test_apk_sha256': checksum(os.environ['MANGALENS_ACCEPTANCE_TEST_APK']),
    'package': os.environ['MANGALENS_ACCEPTANCE_PACKAGE'],
    'runner': os.environ.get('RUNNER_NAME', platform.platform()),
    'ci_run': os.environ.get('GITHUB_RUN_ID'),
    'android_serial': os.environ.get('ANDROID_SERIAL'),
    'acceleration_mode': os.environ.get('MANGALENS_EMULATOR_ACCELERATION', 'not recorded by launcher'),
    'hardware_only_checks': ['ARM64 runtime/performance', 'physical phone thermal behavior', 'hardware codec/GPU/NPU behavior'],
}
Path(sys.argv[1], 'candidate.json').write_text(json.dumps(record, indent=2) + '\n')
PY
adb version > "$DIAGNOSTICS/adb-version.txt"
java -version > "$DIAGNOSTICS/java-version.txt" 2>&1
if [[ -x "$SDK_ROOT/emulator/emulator" ]]; then
  "$SDK_ROOT/emulator/emulator" -version > "$DIAGNOSTICS/emulator-version.txt" 2>&1
fi

trap 'bash scripts/android/capture-device-evidence.sh "$DIAGNOSTICS" "$PACKAGE"' EXIT
test "$(adb get-state)" = device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
timeout --signal=TERM --kill-after=10s "$INSTALL_TIMEOUT" adb install -r -t "$APP_APK" | tee "$DIAGNOSTICS/install-app.txt"
timeout --signal=TERM --kill-after=10s "$INSTALL_TIMEOUT" adb install -r -t "$TEST_APK" | tee "$DIAGNOSTICS/install-test.txt"
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\([^ ]*\) (target=$PACKAGE)$/\1/p")"
test -n "$INSTRUMENTATION"
test "$(printf '%s\n' "$INSTRUMENTATION" | wc -l)" -eq 1
# The initial permission-denial UI case is deliberate; only this QA package is affected.
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if ((API >= 33)); then
  adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
  MEDIA_PERMISSION=android.permission.READ_MEDIA_VIDEO
else
  MEDIA_PERMISSION=android.permission.READ_EXTERNAL_STORAGE
fi
adb shell pm revoke "$PACKAGE" "$MEDIA_PERMISSION"
if ((API >= 33)); then
  # PackageManagerShellCommand added these scoped flag commands in Android 13.
  adb shell pm clear-permission-flags "$PACKAGE" "$MEDIA_PERMISSION" user-set user-fixed
  printf 'api=%s\npermission=%s\nrevoked=true\nuser_flags=cleared\n' "$API" "$MEDIA_PERMISSION" > "$DIAGNOSTICS/permission-setup.txt"
else
  # Older APIs support revoke but expose no scoped permission-flag reset command.
  # Keep their existing flags and app data; a fresh QA AVD has no user-fixed denial.
  printf 'api=%s\npermission=%s\nrevoked=true\nuser_flags=retained (scoped clear-permission-flags requires API 33)\n' "$API" "$MEDIA_PERMISSION" > "$DIAGNOSTICS/permission-setup.txt"
fi
adb shell dumpsys package "$PACKAGE" > "$DIAGNOSTICS/permission-package-state.txt"
# These directories contain regenerable QA evidence, never imported user documents.
adb shell rm -rf /sdcard/Download/mangalens-qa "/sdcard/Android/data/$PACKAGE/files/qa"
adb logcat -c
adb shell am force-stop "$PACKAGE"
timeout --signal=TERM --kill-after=10s 180 adb shell am start -W -n "$PACKAGE/$ACTIVITY" | tee "$DIAGNOSTICS/startup-timing.txt"
if ! grep -q '^Status: ok' "$DIAGNOSTICS/startup-timing.txt"; then
  # ActivityManager's own 10-second wait can expire while a TCG-only emulator
  # is still verifying/drawing the APK. Keep that timing, and require its real
  # first-frame event within a separate bounded software-emulator allowance.
  [[ "${MANGALENS_EMULATOR_ACCELERATION:-}" == software ]]
  grep -q '^Status: timeout' "$DIAGNOSTICS/startup-timing.txt"
  STARTUP_DEADLINE=$((SECONDS + 165))
  FIRST_FRAME=false
  SHORT_ACTIVITY="${ACTIVITY#"$PACKAGE"}"
  while ((SECONDS < STARTUP_DEADLINE)); do
    timeout --signal=TERM --kill-after=2s 10 adb logcat -d -v brief \
      ActivityManager:I ActivityTaskManager:I '*:S' > "$DIAGNOSTICS/startup-first-frame-log.txt"
    if grep -F -q -e "Displayed $PACKAGE/$ACTIVITY:" -e "Displayed $PACKAGE/$SHORT_ACTIVITY:" \
        "$DIAGNOSTICS/startup-first-frame-log.txt"; then
      FIRST_FRAME=true
      break
    fi
    sleep 1
  done
  printf 'mode=software\nfirst_frame_observed=%s\n' "$FIRST_FRAME" > "$DIAGNOSTICS/startup-software-wait.txt"
  [[ "$FIRST_FRAME" == true ]]
fi
adb shell pidof "$PACKAGE" > "$DIAGNOSTICS/startup-pid.txt"
adb shell dumpsys meminfo "$PACKAGE" > "$DIAGNOSTICS/startup-memory.txt"

if [[ "$REQUIRE_MODEL" == true ]]; then
  MODEL_FIXTURE="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mangalens-qa-model.gguf"
  MODEL_URL='https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/872f8a96064a1242ac3a3359cad77c3042548405/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true'
  MODEL_SHA=74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db
  curl --fail --location --retry 3 --connect-timeout 30 --max-time 600 "$MODEL_URL" --output "$MODEL_FIXTURE"
  test "$(wc -c < "$MODEL_FIXTURE")" -eq 491400032
  printf '%s  %s\n' "$MODEL_SHA" "$MODEL_FIXTURE" | sha256sum --check
  adb push "$MODEL_FIXTURE" /data/local/tmp/mangalens-qa-model.gguf
  adb shell run-as "$PACKAGE" mkdir -p files/orez_models
  adb shell run-as "$PACKAGE" cp /data/local/tmp/mangalens-qa-model.gguf files/orez_models/qwen2.5-0.5b-q4_k_m.gguf
  adb shell rm /data/local/tmp/mangalens-qa-model.gguf
  rm -f "$MODEL_FIXTURE"
  printf 'source=%s\nsha256=%s\nbytes=491400032\nlicense=Apache-2.0\n' "$MODEL_URL" "$MODEL_SHA" > "$DIAGNOSTICS/native-model-fixture.txt"
fi

ARGS=(-w -r -e expected_source_sha "$SOURCE_SHA" -e require_model "$REQUIRE_MODEL")
if [[ "$SUITE" == full ]]; then
  bash scripts/android/stage-speech-reference.sh "$PACKAGE" "$DIAGNOSTICS"
  ARGS+=(-e whisper_model_path "/data/user/0/$PACKAGE/files/privateqa/speech/ggml-tiny.bin")
  ARGS+=(-e reference_audio_path "/data/user/0/$PACKAGE/files/privateqa/speech/jfk.wav")
  ARGS+=(-e sample_video "/data/user/0/$PACKAGE/files/privateqa/video/jfk-two-pass-720p.mp4")
fi
if [[ -n "$CLASSES" ]]; then ARGS+=(-e class "$CLASSES"); fi
set +e
timeout --signal=TERM --kill-after=10s "$TEST_TIMEOUT" adb shell am instrument "${ARGS[@]}" "$INSTRUMENTATION" 2>&1 | tee "$DIAGNOSTICS/instrumentation.txt"
DEVICE_STATUS=${PIPESTATUS[0]}
set -e
RESULT_STATUS=0
python3 scripts/android/verify-instrumentation.py "$DIAGNOSTICS/instrumentation.txt" \
  --json "$DIAGNOSTICS/results.json" --junit "$DIAGNOSTICS/TEST-device-acceptance.xml" \
  --required-classes "$REQUIRED_CLASSES" || RESULT_STATUS=$?
if ((DEVICE_STATUS != 0)); then
  echo "Instrumentation transport failed or exceeded its bounded deadline (exit $DEVICE_STATUS)." >&2
  exit "$DEVICE_STATUS"
fi
exit "$RESULT_STATUS"
