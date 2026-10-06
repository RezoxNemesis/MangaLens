#!/usr/bin/env bash
# Run private media acceptance on an already booted Android device/emulator.
# Fixtures and derived speech/image output must remain outside Git/public CI artifacts.
set -euo pipefail
if [ "$#" -ne 5 ]; then
  echo 'Usage: bash mangalens-private-sample-acceptance.sh VIDEO MODEL MANGA_DIR APP_APK TEST_APK' >&2
  exit 2
fi
video=$1
model=$2
manga=$3
app_apk=$4
test_apk=$5
for source in "$video" "$model" "$manga/1.jpg" "$manga/2.jpg" "$manga/3.jpg" "$app_apk" "$test_apk"; do
  test -s "$source" || { echo "Missing fixture/APK: $source" >&2; exit 2; }
done
evidence="app/build/private-sample-evidence"
mkdir -p "$evidence"
adb get-state
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb install -r "$app_apk"
adb install -r "$test_apk"
adb shell run-as com.mangalens mkdir -p files/privateqa/manga
stage() {
  adb push "$1" /data/local/tmp/mangalens-private-fixture
  adb shell run-as com.mangalens cp /data/local/tmp/mangalens-private-fixture "files/privateqa/$2"
}
stage "$video" video.mp4
stage "$model" whisper.bin
stage "$manga/1.jpg" manga/1.jpg
stage "$manga/2.jpg" manga/2.jpg
stage "$manga/3.jpg" manga/3.jpg
adb shell rm /data/local/tmp/mangalens-private-fixture
for class in \
  com.mangalens.ui.video.SpeechAudioProcessorTest \
  com.mangalens.UploadedMediaTest \
  com.mangalens.ui.video.LiveSpeechSampleTest \
  com.mangalens.WebPlaybackCaptureTest \
  com.mangalens.UploadedMangaTest; do
  result="$evidence/${class##*.}.txt"
  timeout 900 adb shell am instrument -w -r -e class "$class" \
    -e sample_video /data/user/0/com.mangalens/files/privateqa/video.mp4 \
    -e whisper_model_path /data/user/0/com.mangalens/files/privateqa/whisper.bin \
    -e sample_manga_dir /data/user/0/com.mangalens/files/privateqa/manga \
    com.mangalens.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
  # am instrument may return shell exit zero even when startup or tests failed.
  if ! rg -q '^OK \([1-9][0-9]* tests?\)' "$result" ||
      rg -q 'Process crashed|FAILURES|INSTRUMENTATION_STATUS_CODE: -[234]|AssumptionViolatedException' "$result"; then
    adb logcat -d -v threadtime > "$evidence/failure-logcat.txt"
    echo "Private sample acceptance failed at $class; inspect $evidence" >&2
    exit 1
  fi
done
adb shell run-as com.mangalens cat files/sample-English.srt > "$evidence/sample-English.srt"
adb shell run-as com.mangalens cat files/sample-Web-English.srt > "$evidence/sample-Web-English.srt"
adb shell run-as com.mangalens cat files/sample-download-playback.txt > "$evidence/sample-download-playback.txt"
adb pull /sdcard/Android/data/com.mangalens/files "$evidence/rendered"
echo "Runtime checks passed; inspect the player and Hindi manga images in $evidence/rendered."
