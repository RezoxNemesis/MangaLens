# MangaLens engineering handoff

4 October 2026. Tested application commit: `b23d5f9522f44a1701240bfb962e10113b7a34cb`. [PR #6](https://github.com/RezoxNemesis/MangaLens/pull/6) incorporates the supplied branding from PR #5 and the latest dark red/black design. This handoff and README are a documentation-only follow-up. Debug engineering build; production readiness is not claimed.

## COMPLETED

Persistent offline chapter/library workflows, chapter acquisition and browser fallback, OCR/translation, media downloads/player, optional local OREZ generation, public retrieval and the supplied visual assets are implemented on `engineering/mangalens-production`. This continuation fixed partial chapter translation failures/retries, bounded HTML media discovery, paused/deleted adaptive-download state races, encyclopedia fallback attribution and native-model ownership/cleanup. The latest continuation adds a chat Stop button and request-scoped cooperative CPU decode cancellation. Adaptive resume now also rejects delayed stopped callbacks that would incorrectly re-pause a resumed row.

## VERIFIED

- **55 JVM tests passed**, with zero failures, errors or skips; counts checked from the final XML reports.
- **21 API 35 x86_64 instrumentation tests passed**, with zero failures or skips; the optional model was staged and actual native generation ran.
- Unit tests, lint, debug/test APK assembly and both ARM64/x86_64 native builds passed. CI verified both APK archives and signatures.
- Real Latin/Japanese/Chinese/Korean/Devanagari OCR and English-to-Hindi translation, damaged-page chapter translation recovery, persisted offline chapters/progress/bookmarks, Room migration, secure WebView policy and restricted download grants passed.
- Original AAC HLS fixture downloaded into the durable media cache and played to completion after its HTTPS server shut down. Adaptive state updates/resume ignore deleted or stopped rows. A real Media3 recovery test verifies an HTTP 404 failure followed by successful retry, pause/resume while a segment request is active, rejection of a delayed pause update, and deletion from the Media3 index without row resurrection.
- OREZ tests cover attributed encyclopedia fallback, malformed responses, primary search precedence and cancellation of a stalled request. Runtime fallback does not present encyclopedia snippets as a verified current gold price.
- Shared native ownership test confirms that closing an unused engine cannot unload a loaded engine, and that a remaining owner can generate after another owner closes. Active CPU decoding cancelled in 647 ms; pre-cancelled requests returned no output, unrelated request cancellation did not stop the active request, and subsequent generation succeeded. A chat test stopped a pending reply, then sent another message without creating an error reply.

## MANGA

Selected-mode routing, reader-focused image discovery, naturally ordered catalogs, progressive acquisition, atomic manifests and saved position are implemented. Offline persistence, bookmarks/status, safe shared-image deletion and Library-to-Reader navigation have runtime coverage. Broad source compatibility and representative complete manga remain device/source validation work; verification-heavy sources retain ordinary Web fallback.

## OCR / TRANSLATION

Five bundled script recognizers and on-device translation are implemented. Actual Latin, Japanese, Chinese, Korean and Devanagari OCR and a two-page English-to-Hindi translation/rendering fixture have runtime coverage. Chapter processing preserves successful overlays while reporting failed page numbers; repairing a corrupt middle page and retrying retains the other pages. Reader Retry now chooses translation recovery when appropriate. Cancellation is not swallowed by translation-memory operations. Real manga layouts, long chapters, custom styles and overlay quality still need representative evaluation; initial language-model download requires internet.

## WEB / VERIFICATION

Back/forward/reload/stop, URL/progress, Manga handoff and site-data clearing are implemented. Cookies follow actual request hosts, including redirects. WebView policy and verification/routing fixtures have test coverage. Login/CAPTCHA completion is user-controlled; protected-site account/session reuse remains untested with user accounts.

## OREZ AI

The pinned optional Qwen model loaded and generated an answer in the final emulator run. Search uses a public general provider with bounded extraction and linked sources. When it produces no usable results, official Wikipedia REST search supplies attributed background excerpts; those excerpts bypass model synthesis and explicitly cannot verify current news, prices or schedules. Direct HTTP checks observed a general-provider verification response and usable Wikipedia results; this is not a guarantee of live-provider reliability on phones.

JSON/HTML reads and result counts are bounded, cancellation closes active HTTP requests, and model ownership prevents one feature from unloading another feature's model. Cleanup runs off the UI thread. CPU decode now uses the pinned native abort callback with a separate cancellation flag per request; cancelled partial output is discarded. Stop cancels the reply coroutine, which signals its native request without waiting for the model lock. Initial model loading/context allocation and some cleanup still run as synchronous background operations and may need to finish before cancellation completes. Resource packs do not train or update model weights. Large-corpus generation/profiling was not run in this PR validation.

## DOWNLOADS / VIDEO

Direct downloads retain durable partial files with validator-based resume, bounded transfers and cancellation checks. HTML media discovery now reads the actual bounded body rather than requiring exactly 8 MiB; short and chunked HTML fixtures pass. Adaptive listener updates are serialized and conditionally persisted, preventing pause/delete resurrection. Failed adaptive resume re-adds the Media3 request. A controlled HTTPS HLS fixture now verifies failed download retry through the app manager, pause/resume, stale stopped-callback rejection and active removal. The test uses the production manager/listener construction with a fixture-specific transport and direct Media3 commands; Android foreground-service dispatch/lifetime and broader failure types remain separate coverage gaps.

Completed adaptive media uses `files/media_download_cache`, explicit removal and the same cache in the in-app player. HLS audio offline playback was verified with a test-only original fixture. Video navigation, locking, error/retry display and background pause are implemented. DASH, video renditions, long/background transfers, adverse networks and physical-device playback require broader checks. Protected/DRM media is unsupported.

## VISUAL

The supplied neon lens logo, supplied artwork, crimson actions and dark surfaces are retained. All six final captures (Home, Library, Reader, OREZ, Downloads, Settings) were inspected locally. The selected Download Room chip now reads “Media” and fits at 320 dp. The Reader/Library screenshots use an intentionally plain QA chapter fixture, and the OREZ capture contains controlled cancellation-test messages; they do not establish full manga overlay quality or all-screen responsiveness. No fabricated rankings or reading progress are shipped.

## PERFORMANCE / MEMORY

Final isolated emulator sample: cold activity start **4,858 ms**, startup PSS **133,247 KiB** (~130 MiB). Native model sample: **650,379,104-byte model**, first generation/load test interval **31,939 ms**, process PSS **929,035 KiB** (~907 MiB), output “Hello! 😊”. These are individual x86_64 emulator observations, not phone benchmarks or pure token throughput. Heavy-reader/corpus workloads and ARM64 memory/latency remain unprofiled.

## SECURITY / PRIVACY

WebView file/content access is disabled, mixed content is blocked, URLs are validated, cookies are scoped per host, backup is disabled and private-file sharing grants are restricted. Tests cover WebView policy and download-provider rejection of unrelated files. HLS fixture trust overrides exist only in instrumentation tests. No mandatory paid OCR/search/translation API is introduced. Debug signing is not a substitute for private production signing.

## CI

[Final application workflow: success](https://github.com/RezoxNemesis/MangaLens/actions/runs/37170326281); model-provenance workflow also passed. Validation includes JVM reports, lint, ABI builds, archive/signature checks, startup, instrumentation, screenshots and native-model evidence. Corpus jobs were deliberately skipped for the PR and are not counted as verified corpus builds. Previous green batch `37168726532` had 55 JVM/20 runtime tests; final totals above supersede it.

- [ARM64 and x86_64 APK archive](https://github.com/RezoxNemesis/MangaLens/actions/runs/37170326281/artifacts/11291187264)
- [Screenshots, test reports and runtime diagnostics](https://github.com/RezoxNemesis/MangaLens/actions/runs/37170326281/artifacts/11291890353)
- CI artifacts expire on 11 October 2026; committed source and documentation persist.

The downloaded artifact archive was checked against SHA-256 `2a740c0a425b4811e1db724e7aec0d265b4b1e82414534852e30b23107479833`. The exported phone APK, `MangaLens-arm64-debug-b23d5f95.apk`, was checked against its included checksum: `e587135fe2c1fe54406e74a580dfefce9de59810f6f2e2ff813b3f002b88d9d4`.

## REMAINING LIMITATIONS

Physical ARM64/OEM tests; representative long chapters, multilingual/custom-style quality; protected-site sessions and wider source coverage; DASH/video/adverse-network/background-download behavior; broader adaptive failure/retry and foreground-service lifetime; interruptible initial model setup; large-corpus generation/profiling; and private stable release signing remain outstanding. Tests establish the listed fixtures and workflows, not universal source/model quality. No physical device or release credentials were available in this workspace.

## RELEASE STATUS

PR #6 remains a draft; `main` is unchanged. The verified ARM64 debug APK is exported for installation/testing. It is not a signed production release. Debug signing keys can differ between build environments, so in-place upgrades across artifacts are not guaranteed. Preserve existing data before replacing an installation signed with a different key. Build/toolchain commands and operational details are in the root README.

Next validation: install the ARM64 build on a representative physical device; evaluate full manga translation, native memory/latency and interrupted/background media; then configure private release signing.
