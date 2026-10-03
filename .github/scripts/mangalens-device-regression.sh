#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/diagnostics/screenshots
collect_regression() {
  adb pull /sdcard/Android/data/com.mangalens/files/qa app/build/diagnostics/screenshots || true
  adb logcat -d -v threadtime > app/build/diagnostics/regression-logcat.txt || true
}
trap collect_regression EXIT
gradle --no-daemon connectedDebugAndroidTest --stacktrace
