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

## Exact packaged dependency

- Maven artifact: `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`.
- AAR bytes: `139371444`.
- AAR SHA-256: `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49`.
- Published source JAR SHA-256: `fb95701c2697a501ebaf39c02112c58983dc3b855a4d957d825b7c6942b7afec`.
- [Exact upstream release](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760): tag `0.18.1`, commit `d725d5c9a18c3a99a13ee0308bf78275dc310760`.
- All twelve executable, probe and dependency-ZIP Git blob hashes match that
  release tree across `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- Root's Android CLI version/build-configuration probes identified FFmpeg
  `7.1.1` and Termux NDK `r28c`, API `24`. These CLI probes are distinct from
  the integrated application's runtime/decoder acceptance.

`assets/media/ffmpeg-0.18.1-pins.json` pins the packaged executables, full FFmpeg
library archive and its members for each ABI. A separate checksum-pinned
Python archive contributes only the recursively required ELF dependencies:
Android POSIX semaphore/support, C++ runtime, crypto and Expat. No Python code
or scripts are installed or executed by this copy path. The dependency closure
was derived from actual ELF `DT_NEEDED` records for every ABI.

Extraction uses a private version directory and atomically replaces a small
activation pointer. Existing mapped/executed files retain their inode and are
never overwritten. The runtime uses the APK's executable directly and fixed
local-file arguments with protocol/format allowlists. It does not call the
wrapper's mutable installer or any self-updater.

## Source and licenses

The wrappers retain their upstream GPL-3.0 license; the exact unchanged release
license and source links ship in `assets/media`. FFmpeg was configured with
`--enable-gpl --enable-version3`. The upstream release's
[build instructions](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_FFMPEG.md)
use Termux but do not identify a corresponding Termux recipe commit. The
earlier Junkfood fork master did **not** match the release's native blobs and
is not used as release authority.

The official FFmpeg 7.1.1 source archive is pinned by SHA-256
`733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`
(11019500 bytes); its unchanged `COPYING.GPLv3` and `LICENSE.md` ship in assets.
The upstream native update commit
`f2280ae7d8bc8d59590c8727a89f047983867c10` identifies the FFmpeg 7.1.1 bump.
Root also identified a Termux 7.1.1 revision-6 recipe compatible with the recorded
configuration. That is compatible recipe evidence, not proof that the publisher
used the same Termux commit; no exact-build correspondence is invented.

Complete native reconstruction and a complete corresponding-source/dependency
notice bundle remain distribution work until the actual release recipes,
patches, library sources and license notices are collected. A source link or
binary-tree match alone is not described as completing that bundle. Existing
project licensing and third-party notices are preserved.

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
