#!/usr/bin/env bash
# Stage pinned public speech fixtures on one owned QA device, without publishing media.
set -euo pipefail
umask 077
SPEECH_QA_PACKAGE="${1:-com.mangalens}"
SPEECH_QA_EVIDENCE="${2:-app/build/diagnostics/device-acceptance}"
[[ "$SPEECH_QA_PACKAGE" =~ ^[a-zA-Z0-9_.]+$ ]]
command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
mkdir -p "$SPEECH_QA_EVIDENCE"
SPEECH_QA_TMP="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mangalens-speech-reference.XXXXXX")"
SPEECH_QA_DEVICE_TMP="/data/local/tmp/mangalens-speech-reference-$$"
cleanup_speech_fixture() {
  adb shell rm -rf "$SPEECH_QA_DEVICE_TMP" >/dev/null 2>&1 || true
  rm -rf -- "$SPEECH_QA_TMP"
}
trap cleanup_speech_fixture EXIT
curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 20 --max-time 300 \
  'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.bin' \
  --output "$SPEECH_QA_TMP/ggml-tiny.bin"
printf '%s  %s\n' be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 "$SPEECH_QA_TMP/ggml-tiny.bin" | sha256sum --check
test "$(wc -c < "$SPEECH_QA_TMP/ggml-tiny.bin")" -eq 77691713
curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 20 --max-time 60 \
  'https://raw.githubusercontent.com/ggml-org/whisper.cpp/a8d002cfd879315632a579e73f0148d06959de36/samples/jfk.wav' \
  --output "$SPEECH_QA_TMP/jfk.wav"
printf '%s  %s\n' 59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e "$SPEECH_QA_TMP/jfk.wav" | sha256sum --check
test "$(wc -c < "$SPEECH_QA_TMP/jfk.wav")" -eq 352078
# Repeat the 11s reference to exercise live inference beyond an 18s player position.
# Generated test-pattern video is explicitly separate from the supplied social links.
ffmpeg -nostdin -hide_banner -loglevel error -y -stream_loop 1 -i "$SPEECH_QA_TMP/jfk.wav" \
  -f lavfi -i testsrc2=size=1280x720:rate=12 -t 22 -map 1:v:0 -map 0:a:0 \
  -c:v libx264 -threads 2 -preset ultrafast -crf 28 -pix_fmt yuv420p \
  -c:a aac -ar 16000 -ac 1 -b:a 48k -movflags +faststart "$SPEECH_QA_TMP/jfk-two-pass-720p.mp4"
ffprobe -v error -show_entries format=duration,size:stream=codec_name,codec_type,width,height,sample_rate,channels \
  -of json "$SPEECH_QA_TMP/jfk-two-pass-720p.mp4" > "$SPEECH_QA_EVIDENCE/live-speech-fixture-probe.json"
(
  cd "$SPEECH_QA_TMP"
  sha256sum ggml-tiny.bin jfk.wav jfk-two-pass-720p.mp4
) > "$SPEECH_QA_EVIDENCE/speech-fixture-sha256.txt"
printf '%s\n' \
  'scope=controlled local video/live ASR and decoded public speech; supplied YouTube/Instagram acceptance is separate' \
  'audio=whisper.cpp/a8d002cfd879315632a579e73f0148d06959de36/samples/jfk.wav;11s repeated twice' \
  'model=ggerganov/whisper.cpp/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.bin;MIT' \
  'video=generated720p12fps H264 with reference mono16kAAC;22s' > "$SPEECH_QA_EVIDENCE/speech-fixture-provenance.txt"
adb shell mkdir -p "$SPEECH_QA_DEVICE_TMP"
adb push "$SPEECH_QA_TMP/ggml-tiny.bin" "$SPEECH_QA_TMP/jfk.wav" "$SPEECH_QA_TMP/jfk-two-pass-720p.mp4" "$SPEECH_QA_DEVICE_TMP/"
adb shell run-as "$SPEECH_QA_PACKAGE" mkdir -p files/privateqa/speech files/privateqa/video
adb shell run-as "$SPEECH_QA_PACKAGE" cp "$SPEECH_QA_DEVICE_TMP/ggml-tiny.bin" files/privateqa/speech/ggml-tiny.bin
adb shell run-as "$SPEECH_QA_PACKAGE" cp "$SPEECH_QA_DEVICE_TMP/jfk.wav" files/privateqa/speech/jfk.wav
adb shell run-as "$SPEECH_QA_PACKAGE" cp "$SPEECH_QA_DEVICE_TMP/jfk-two-pass-720p.mp4" files/privateqa/video/jfk-two-pass-720p.mp4
