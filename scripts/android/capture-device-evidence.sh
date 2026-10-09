#!/usr/bin/env bash
# Capture only generated QA evidence from the selected Android test device.
set -euo pipefail

DIAGNOSTICS="${1:-app/build/diagnostics/device}"
PACKAGE="${2:-com.mangalens}"
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
