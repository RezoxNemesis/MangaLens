#!/usr/bin/env bash
# Pin the user's original dialogue JPEG for real OCR quality tests, without adding media to git.
set -euo pipefail
umask 077
OCR_QA_PACKAGE="${1:-com.mangalens}"
OCR_QA_EVIDENCE="${2:-app/build/diagnostics/device-acceptance}"
[[ "$OCR_QA_PACKAGE" =~ ^[a-zA-Z0-9_.]+$ ]]
mkdir -p "$OCR_QA_EVIDENCE"
OCR_QA_TMP="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mangalens-user-source-ocr.XXXXXX")"
OCR_QA_DEVICE_TMP="/data/local/tmp/mangalens-user-source-ocr-$$"
cleanup_user_source_ocr() {
  adb shell rm -rf "$OCR_QA_DEVICE_TMP" >/dev/null 2>&1 || true
  rm -rf -- "$OCR_QA_TMP"
}
trap cleanup_user_source_ocr EXIT
OCR_QA_URL='https://cdn.demoniclibs.com/Kidnapped%20Dragons/63/1.jpg?v=1791383844'
OCR_QA_SHA=f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16
curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 \
  --connect-timeout 20 --max-time 90 --max-filesize 839788 \
  --referer 'https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1' \
  "$OCR_QA_URL" --output "$OCR_QA_TMP/reader002.jpg"
test "$(wc -c < "$OCR_QA_TMP/reader002.jpg")" -eq 839788
printf '%s  %s\n' "$OCR_QA_SHA" "$OCR_QA_TMP/reader002.jpg" | sha256sum --check
printf '%s\n' \
  'scope=original user-supplied Manhwa OCR; no generated source substitute or quality pass claim' \
  'chapter=https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1' \
  "jpeg=$OCR_QA_URL" "sha256=$OCR_QA_SHA" 'bytes=839788' 'width=720' 'height=9170' \
  > "$OCR_QA_EVIDENCE/user-source-ocr-fixture.txt"
adb shell mkdir -p "$OCR_QA_DEVICE_TMP"
adb push "$OCR_QA_TMP/reader002.jpg" "$OCR_QA_DEVICE_TMP/reader002.jpg"
adb shell run-as "$OCR_QA_PACKAGE" mkdir -p files/privateqa/manhwa
adb shell run-as "$OCR_QA_PACKAGE" cp "$OCR_QA_DEVICE_TMP/reader002.jpg" files/privateqa/manhwa/reader002.jpg
