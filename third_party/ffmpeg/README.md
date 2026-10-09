# MangaLens app-owned original-copy tools

The corresponding-source archive in this directory contains the unchanged official FFmpeg 7.1.1 source tar, exact completed build recipe, full generated configuration and build logs for arm64-v8a and x86_64, NDK/compiler identity, ELF reports, binary receipts and LGPL 2.1 license. Archive SHA-256: `a324a0cfc51ecac7154d1fa96bc119c5501c7990249dd43886dce54ad4968476`. Official tar SHA-256: `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`.

Extract the archive and run its exact build recipe with the included tar path, Android NDK `29.0.13113456` path, and a new empty output directory. The recorded toolchain reports r29-beta1 / Clang 20. Android API 24 and 16 KiB ELF load alignment are retained. The CLI profile enables local original-stream copying and probing, with no network, encoders, decoders, GPL, nonfree or third-party codec components. Its only dynamic dependencies are Android libc and libm. Both fixed-name executables are packaged directly from these verified outputs.

The Android NDK is a separately distributed build tool. This source offer covers these owned FFmpeg binaries; bundled yt-dlp wrapper, Python, QuickJS and other components retain their own licenses and corresponding-source obligations. The immutable build receipt predates device execution; later runtime evidence is recorded separately.
