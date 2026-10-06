# Feature verification and screenshots — 4 October 2026

Source app: `de703579d5b101cacd2ed5dbcc97252a6d29289c`; production app code is unchanged by the follow-up test adjustment.
[Device CI evidence](https://github.com/RezoxNemesis/MangaLens/actions/runs/37202813493): 74 JVM tests passed; 37 Android tests reported, zero failures/errors, five private-fixture skips; **32 Android tests executed successfully**. This is 106 executed tests, not a claim that every setting or website has been exhaustively tested.

[View the actual screenshots](qa/SCREENSHOTS.md). Images are original Android captures or original renderer/frame outputs from CI; synthetic fixtures are labeled. No private uploaded video, manga image, model or transcript is published here.

| Feature / function | Result | Evidence and scope |
| --- | --- | --- |
| Home, Library, Reader, Downloads, Settings, Orez navigation | Pass in device CI | ProductSmokeTest opens destinations and captures screens. Reader content is a generated QA chapter. |
| Offline library, reading progress, bookmarks and restart persistence | Pass in device CI | OfflineLibraryTest; retained pages, restored progress, shared-image deletion protection. |
| PDF import and safe file access | Pass in device CI / JVM | PDF pages persisted; provider access scoped to downloads; WebView cannot read app files. Archive and URL validation covered by JVM tests. |
| English, Japanese, Chinese, Korean and Hindi OCR | Pass in device CI | Latin bitmap test, script-specific recognizers, tall-page boundary/bottom fixtures. These are fixtures, not universal OCR accuracy. |
| Actual English-to-Hindi chapter translation | Pass in device CI | ChapterTranslationTest recognizes, downloads the model, translates and renders two generated pages. |
| Replacement lettering and background restoration | Pass in device CI; synthetic image inspected | Source removed on tinted paper; gradient, dark background, italic/condensed styles and long Hindi bounded. Images are synthetic fixtures. |
| Offline adaptive video playback | Pass in device CI | HLS downloads and plays after its fixture server stops. |
| Download retry, pause, resume and removal | Pass in device CI / JVM | AdaptiveDownloadRecoveryTest and progressive transfer fixtures; late worker updates do not resurrect stopped downloads. |
| Redirect/segment cookies and request context | Pass in device CI / JVM | Actual per-URL cookie path/redirect checks; credentials are not blindly forwarded. |
| Bundled yt-dlp native runtime | Pass in device CI | Installed-app extractor fixture executes. Live arbitrary-host coverage remains unverified. |
| Native video frame capture and video OCR | Pass in device CI | Captured generated video frame and real OCR. This is not the uploaded video. |
| Orez native model generation and cancellation | Pass in device CI | Real pinned Qwen model generates, cancels and preserves ownership. The Orez UI screenshot uses a cancellation fixture reply, not a claimed natural-language model response. |
| Orez recovery, migration and sourced fallback | Pass in device CI / JVM | Next chat works after cancellation; scope/style migration and sourced fallback fixtures. |
| Decoded audio empty-buffer/passthrough regression | Pass in device CI and local retry | Real processor handles empty Media3 drains, preserves PCM and resets/EOS without a model. |
| Supplied video HTTPS download | **Passed transfer phase locally** | 19,810,518 bytes; device and original file SHA-256 both `fe45df7d69c2e8528ce5a0fe0e199d799ac5cca9869e60e6a389fb828d130276`. Controlled local HTTPS transfer, not a test of its original website. |
| Supplied video native playback / seek / frame | **Not accepted: seek check failed** | Player reached READY with 23–24-second duration. Fixed 1.5-second post-seek assertion failed. The test now polls READY plus advancement for up to 60 seconds; a passing retry has not been obtained. No verified rendered sample frame is available. |
| Supplied clean manga screenshots 4–6 | **Failed acceptance: 240-second timeout** | Real OCR/translation/replacement test timed out; no completed translated chapter image was recovered. This does not establish the cause or translation accuracy. |
| Real Whisper terminal shutdown and queued reloads | **Unverified final result** | Test began, but no completion was recovered before the execution environment went offline. |
| Supplied video live English speech captions | **Unverified** | No accepted Android cue/UI capture result recovered. Earlier host transcription is separate evidence. |
| Android-consented Web playback capture plus English inference | **Unverified** | No accepted private-sample completion/capture recovered. |
| Physical ARM64 runtime and on-device throughput | **Unverified** | ARM64 APK archive/signature/checksum/native payload verified previously; runtime CI is x86_64. |

## Local follow-up environment and correction

The software-only emulator initially failed process startup/attach. On the follow-up retry, Android's test-environment hardware timeout multiplier was raised to 20 and the framework restarted; SELinux was permissive on the test emulator. This allowed the playback-buffer regression and private sample tests to start. These environment changes are not production app changes or evidence for production security/throughput.

The sample transfer and READY/duration phases completed. A fixed sleep after seek is not a reliable way to await asynchronous rebuffering; UploadedMediaTest now waits for actual READY plus timeline movement, bounded to 60 seconds. The local rebuild/retry did not produce a confirmed result before the execution service disconnected and reported the environment offline. The reason for that disconnect is not established. The correction is submitted for clean CI compilation/regression validation, not represented as a passing sample retry.

The latest observed manga case returned TimeoutCancellationException at 240 seconds. The next observed case was native recognizer shutdown starting. Final speech/Web/shutdown outcomes could not be retrieved. Do not report them as passed or invent screenshots.

The screenshot gallery preserves the originals from the successful CI device suite. The private video, chapters and transcripts remain outside Git. Full completion still requires usable Android acceptance of the outstanding private cases; website support, semantic accuracy and every possible setting combination are not established by this suite.
