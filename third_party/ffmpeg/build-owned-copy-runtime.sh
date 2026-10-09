#!/usr/bin/env bash
# App-owned FFmpeg7.1.1 original-copy build; actual receipts recorded separately.
set -euo pipefail
TASK_SOURCE_TAR=${1:?verified ffmpeg-7.1.1.tar.xz path required}
TASK_ANDROID_NDK=${2:?installed Android NDK path required}
TASK_BUILD_ROOT=${3:?isolated output directory required}
TASK_BUILD_JOBS=${MANGALENS_COPY_BUILD_JOBS:-2}
TASK_SOURCE_SHA=733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1
TASK_ACTUAL_SHA=$(sha256sum -- "$TASK_SOURCE_TAR" | cut -d ' ' -f 1)
[[ "$TASK_ACTUAL_SHA" == "$TASK_SOURCE_SHA" ]]
TASK_TOOLCHAIN="$TASK_ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
[[ -x "$TASK_TOOLCHAIN/bin/llvm-ar" ]]
mkdir -p -- "$TASK_BUILD_ROOT/source"
tar -xJf "$TASK_SOURCE_TAR" --strip-components=1 -C "$TASK_BUILD_ROOT/source"
TASK_BUILD_ROOT=$(realpath -- "$TASK_BUILD_ROOT")
TASK_COMMON_FLAGS=(
  --target-os=android --enable-cross-compile
  --disable-autodetect --disable-everything --disable-network
  --disable-gpl --disable-nonfree --disable-version3
  --disable-shared --enable-static --disable-doc --disable-debug
  --disable-avdevice --disable-postproc --disable-x86asm
  --enable-ffmpeg --enable-ffprobe
  --enable-protocol=file,pipe
  --enable-demuxer=mov,matroska,ogg,aac,mp3,flac
  --enable-muxer=mov,mp4,matroska,webm
  --enable-parser=h264,hevc,av1,vp8,vp9,aac,opus,vorbis,mpegaudio,flac,ac3,mpeg4video,h263,mpegvideo
  --enable-bsf=aac_adtstoasc,vp9_superframe,extract_extradata,av1_frame_merge
  "--ar=$TASK_TOOLCHAIN/bin/llvm-ar" "--ranlib=$TASK_TOOLCHAIN/bin/llvm-ranlib"
  "--nm=$TASK_TOOLCHAIN/bin/llvm-nm" "--strip=$TASK_TOOLCHAIN/bin/llvm-strip"
  --extra-cflags=-fPIE "--extra-ldflags=-pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)
for TASK_ABI in arm64-v8a x86_64; do
  if [[ "$TASK_ABI" == arm64-v8a ]]; then
    TASK_ARCH=aarch64
    TASK_CLANG=aarch64-linux-android24-clang
  else
    TASK_ARCH=x86_64
    TASK_CLANG=x86_64-linux-android24-clang
  fi
  TASK_ABI_BUILD="$TASK_BUILD_ROOT/build-$TASK_ABI"
  TASK_ABI_PACKAGE="$TASK_BUILD_ROOT/package/$TASK_ABI"
  mkdir -p -- "$TASK_ABI_BUILD" "$TASK_ABI_PACKAGE"
  (
    cd -- "$TASK_ABI_BUILD"
    "$TASK_BUILD_ROOT/source/configure" "${TASK_COMMON_FLAGS[@]}" "--arch=$TASK_ARCH" "--cc=$TASK_TOOLCHAIN/bin/$TASK_CLANG"
    make -j"$TASK_BUILD_JOBS" ffmpeg ffprobe
    cp -- ffmpeg "$TASK_ABI_PACKAGE/libmangalens_ffmpeg.so"
    cp -- ffprobe "$TASK_ABI_PACKAGE/libmangalens_ffprobe.so"
    "$TASK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$TASK_ABI_PACKAGE/libmangalens_ffmpeg.so" "$TASK_ABI_PACKAGE/libmangalens_ffprobe.so"
    "$TASK_TOOLCHAIN/bin/llvm-readelf" -h -l -d "$TASK_ABI_PACKAGE/libmangalens_ffmpeg.so" > "$TASK_ABI_PACKAGE/ffmpeg-elf.txt"
    "$TASK_TOOLCHAIN/bin/llvm-readelf" -h -l -d "$TASK_ABI_PACKAGE/libmangalens_ffprobe.so" > "$TASK_ABI_PACKAGE/ffprobe-elf.txt"
    sha256sum -- "$TASK_ABI_PACKAGE/libmangalens_ffmpeg.so" "$TASK_ABI_PACKAGE/libmangalens_ffprobe.so" > "$TASK_ABI_PACKAGE/binary-sha256.txt"
  )
done
