#!/usr/bin/env bash
set -euo pipefail
if ! command -v ffmpeg >/dev/null; then
  sudo apt-get update
  sudo apt-get install -y ffmpeg fonts-dejavu-core
fi
mkdir -p app/src/androidTest/assets/video
qa_video_file="$(mktemp --suffix=.mp4)"
trap 'rm -f "$qa_video_file"' EXIT
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=white:s=640x360:r=10:d=5 \
  -vf "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='Hello MangaLens':fontcolor=black:fontsize=48:x=(w-text_w)/2:y=(h-text_h)/2" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart -y "$qa_video_file"
base64 -w 0 "$qa_video_file" > app/src/androidTest/assets/video/ocr-frame.mp4.base64
qa_audio_file="$(mktemp --suffix=.m4a)"
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=44100:duration=5 -c:a aac -y "$qa_audio_file"
base64 -w 0 "$qa_audio_file" > app/src/androidTest/assets/video/mux-audio.m4a.base64
rm -f "$qa_audio_file"
