#!/usr/bin/env bash
# Create/launch a task-owned AVD, including explicit software mode when KVM is absent.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"
API=35
PORT=5554
BOOT_TIMEOUT=900
INSTALL=false
while (($#)); do
  case "$1" in
    --api) API="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --boot-timeout) BOOT_TIMEOUT="$2"; shift 2 ;;
    --install) INSTALL=true; shift ;;
    --help) echo "Usage: $0 [--api 35] [--port 5554] [--boot-timeout 900] [--install]"; echo 'Install requires a configured free Android SDK. Source the emitted device.env before acceptance; adb emu kill stops the selected emulator.'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$API" =~ ^[0-9]+$ && "$PORT" =~ ^[0-9]+$ && "$BOOT_TIMEOUT" =~ ^[1-9][0-9]*$ ]]
((PORT >= 5554 && PORT <= 5682 && PORT % 2 == 0)) || { echo 'Emulator port must be even, from 5554 through 5682.' >&2; exit 2; }
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
SDKMANAGER="$(find "$SDK_ROOT/cmdline-tools" -maxdepth 3 -name sdkmanager -type f | sort -V | tail -n 1)"
AVDMANAGER="$(dirname "$SDKMANAGER")/avdmanager"
EMULATOR="$SDK_ROOT/emulator/emulator"
ADB="$SDK_ROOT/platform-tools/adb"
IMAGE="system-images;android-$API;default;x86_64"
AVD="mangalens-qa-api-$API"
SERIAL="emulator-$PORT"
DIAGNOSTICS="app/build/diagnostics/emulator-api-$API"
mkdir -p "$DIAGNOSTICS"
if [[ "$INSTALL" == true ]]; then
  test -x "$SDKMANAGER"
  "$SDKMANAGER" --install platform-tools emulator "$IMAGE"
fi
test -x "$EMULATOR"
test -x "$ADB"
test -x "$AVDMANAGER"
if "$ADB" devices | awk '{print $1}' | grep -Fxq "$SERIAL"; then
  echo "Device $SERIAL already exists. Select another port or stop your existing emulator explicitly." >&2
  exit 1
fi
if ! "$AVDMANAGER" list avd -c | grep -Fxq "$AVD"; then
  printf 'no\n' | "$AVDMANAGER" create avd --name "$AVD" --package "$IMAGE" --device pixel_4
fi
ACCEL=off
if [[ -r /dev/kvm && -w /dev/kvm ]] && "$EMULATOR" -accel-check > "$DIAGNOSTICS/acceleration-check.txt" 2>&1; then
  ACCEL=on
else
  "$EMULATOR" -accel-check > "$DIAGNOSTICS/acceleration-check.txt" 2>&1 || true
fi
printf 'Launching %s on %s with acceleration %s.\n' "$AVD" "$SERIAL" "$ACCEL"
nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -accel "$ACCEL" -memory 3072 -cores 2 \
  -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim -camera-back none -camera-front none \
  > "$DIAGNOSTICS/emulator.txt" 2>&1 < /dev/null &
PID=$!
printf '%s\n' "$PID" > "$DIAGNOSTICS/emulator.pid"
MODE=software
if [[ "$ACCEL" == on ]]; then MODE=kvm; fi
printf 'export ANDROID_SERIAL=%q\nexport MANGALENS_EMULATOR_ACCELERATION=%q\n' "$SERIAL" "$MODE" > "$DIAGNOSTICS/device.env"
DEADLINE=$((SECONDS + BOOT_TIMEOUT))
while ((SECONDS < DEADLINE)); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "Emulator exited before boot. See $DIAGNOSTICS/emulator.txt." >&2
    exit 1
  fi
  if [[ "$(timeout --signal=TERM --kill-after=2s 10 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && \
      timeout --signal=TERM --kill-after=2s 10 "$ADB" -s "$SERIAL" shell pm path android >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=2s 10 "$ADB" -s "$SERIAL" shell input keyevent 82
    echo "Android API $API booted. Source $DIAGNOSTICS/device.env and run scripts/android/run-acceptance.sh."
    exit 0
  fi
  sleep 2
done
timeout --signal=TERM --kill-after=5s 30 "$ADB" -s "$SERIAL" logcat -d -t 1000 > "$DIAGNOSTICS/boot-logcat.txt" 2>&1 || true
kill "$PID" 2>/dev/null || true
echo "Emulator did not finish boot within $BOOT_TIMEOUT seconds. Evidence: $DIAGNOSTICS." >&2
exit 1
