# Original media runtime provenance and verification

The download resolver ranks reported video height, frame rate and original
bitrate before a codec or container preference. An explicit height ceiling
does not fall back above the ceiling. A higher combined video track may be
paired with better original audio. The provider's preferred/original audio
language remains ahead of audio bitrate, so forcing height does not substitute
a higher-bitrate dub. The complete selected video/audio/header/
duration tuple carries bounded format, codec, reported-height and client facts.
These facts are informational and never authorize an unrelated source. Transfer
admits the context only for its exact captured video URL and uses the same
snapshot for both tracks' headers; a partial source refresh fails admission.
Transfers reject missing required receipts, unreadable receipts, and JSON without
an explicit audio source shape. The atomic writer and reader share a one-MiB
bound; ordinary allowed video/audio headers above the former 128-KiB reader cap
round-trip. Unmarked migrated rows may omit a receipt only when the actual file
proves a combined video/audio source. Explicitly recorded silent sources remain
supported. An oversized replacement leaves the prior atomic snapshot intact.

The original stream assembler chooses MP4 for compatible AAC pairs, WebM for
VP8/VP9/AV1 with Opus/Vorbis, and Matroska for other supported original codec
pairs. Android MediaMuxer is used only for documented API/container pairs:
WebM Opus requires API 29, and MP4 AV1 requires API 34. A platform copy that
fails verification receives one FFmpeg copy retry with the same selected
streams. No lower representation or audio encoder is selected during retry.

The assembler verifies actual selected video height and codec, source and
output codec data, every selected encoded packet's size and SHA-256, sample
order/count, every packet timestamp and duration, relative audio/video start and
tail timing, and each declared track tail. Timestamps stream to attempt-owned
private binary receipts, using bounded memory and retaining no media payloads or
request information. A shared timestamp rebase may differ by two milliseconds
for WebM rounding; an interior timing change fails even if endpoints agree.
Original-tail admission allows one packet plus two milliseconds, with a 25-ms
floor and a 250-ms cap. It does not use the adaptive path's two-second floor.
The near-tail negative fixture preserves original AAC payloads while ending at
6.058 seconds against the declared eight seconds. Processing and cleanup use
the existing bounded resolution runner
and process guard. Each attempt has a unique output path known before the
suspend call; rejected old results cannot delete a newer result or resumable
input or a newer attempt's timing receipts. Timing receipts are removed by the
worker and caller finalizers. Publication records the actual final container MIME atomically with the
destination, guarded against paused/cancelled/completed tasks.

Process cleanup binds to the non-null child immediately after launch returns,
before checking cancellation. Cancellation during an uncooperative launch cannot
consume a stop while the child reference is still null. A deterministic regression
with an actual JVM child reproduced that race before the fix and verifies the
late child exits after cancellation.

Decoder advertisements are recorded separately from encoded-file validity.
An unavailable decoder is an actionable compatible-player state, not permission
to silently download a lower source. No successful decoder advertisement is
presented as actual playback. Completion does not claim the maximum available
across an entire provider or client inventory. Silent source files never claim
an audio track was verified.

## Exact packaged owned tools

FFmpeg 7.1.1 copy/probe executables are built directly from the official source tar with Android NDK `29.0.13113456` / Clang 20, API 24, for arm64-v8a and x86_64. Fixed `libmangalens_ffmpeg.so` and `libmangalens_ffprobe.so` files are extracted by normal APK native-library packaging. The bounded pin asset records exact bytes, SHA-256, recipe and per-ABI receipt hashes. Admission checks both actual ELF64 ET_DYN files, linker64, regular canonical paths, bounded program/dynamic/string tables, hashes and exact system dependencies. RPATH/RUNPATH are rejected. No publisher ZIP or Python library is installed by this copy path. Child environments clear loader and proxy overrides and use the Android system PATH. Late-launch cancellation and original packet auditing are retained.

The minimal profile disables autodetection, networking, GPL, nonfree, version3, encoders, decoders and third-party codec libraries. Actual configure and CLI license output identify LGPL-2.1-or-later. Both ELF files require only Android libc and libm; every PT_LOAD alignment is 16 KiB. The app removes only the separate publisher FFmpeg AAR. Its library/common site extractors and Python/QuickJS remain. A verified raw `--ffmpeg-location` argument follows the wrapper's normal legacy option, choosing the owned executable and its sibling probe.

## Corresponding source and licenses

[The exact corresponding-source offer](../third_party/ffmpeg/README.md) includes the unchanged official source tar, completed two-ABI recipe, generated configuration and logs, compiler/NDK identity, LGPL text, ELF reports and binary receipts. Archive SHA-256: `a324a0cfc51ecac7154d1fa96bc119c5501c7990249dd43886dce54ad4968476`. Source tar SHA-256: `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`. APK assets retain exact pins and LGPL license text.

The site-extractor wrapper retains its GPL-3.0 license and pinned upstream release identity. Python/common/QuickJS and other bundled component build correspondence and distribution obligations remain separate verification work. The owned source offer does not establish correspondence for those unrelated components. Historical publisher FFmpeg evidence remains in Git and the QA workspace; its unused runtime pins and separate dependency are retired from the app.

## Verification boundaries

The staged source compiles against the real Android 35 SDK and existing project
dependencies. Focused JVM tests cover selection/parser, exact containers and
API limits, payload/timing/tail verification, pinned nested/symlink archive
activation, truthful notes and cancelled-attempt output isolation. Existing
parser and completeness regressions also run. A socket-disabled invocation of
the installed yt-dlp selector verifies that a higher WebM representation wins
over lower MP4 and that a higher combined video is not discarded.

An offline host probe executes the **production** copy arguments, native process
wrapper and packet verifier against four pinned synthetic fixture pairs. This
checks actual ffprobe output and encoded fidelity on the recorded host FFmpeg
version; it does not validate APK executable activation or Android codecs.

`OriginalMediaRuntimeTest` contains ten installed-Android methods for those
codec pairs, gross/near-tail and different-height rejection, unknown legacy
video-only rejection, intentional silence, proven legacy combined admission,
and real saved-file audio decode. `OriginalMediaPersistenceTest` contains five
methods for complete tuple replacement, large/truncated/missing/oversized
receipt admission, partial-refresh rejection and real Room MIME/destination
admission. The focused JVM gate contains 45 tests.
The saved-file audit decodes audio and scans video packets; video decode is
explicitly not evaluated. The synthetic fixtures and their generation commands,
hashes, sizes and source stream facts are retained separately. Method outcomes
and safe codec/hash/tail evidence are stored under app-private
`files/mangalens-qa/original-media` without URLs, cookies or provider tokens.

Those Android methods require execution of the integrated candidate. Original
YouTube/Instagram extraction, full download, playback and replay acceptance
remain independent real-provider gates; existing bot/audience access denials
have not been claimed fixed. This slice does not replace the adaptive manifest
download path, which needs its own selected-stream/full-tail acceptance.
