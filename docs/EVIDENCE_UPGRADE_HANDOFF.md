# Evidence-driven upgrade

This change addresses the supplied chapter, OREZ, local-video, download, and Settings evidence. Private screenshots, video, model files, and generated transcripts are excluded from the repository.

## Implemented

- Readers share one ordered page list across vertical, horizontal LTR, and RTL modes; preserve position; support tap navigation, pinch/double-tap zoom, and visible failed-page retry. Static hidden carousel slides and bounded rendered observations are collected without admitting explicit CAPTCHA, placeholder, or advertisement images. MangaDex uses its ordered public manifest.
- OREZ retrieval keeps bounded top matches, checks cancellation, caches a small number of queries, and caps foreground scanning. Native inference uses memory mapping, chunked prefill, conservative threads, and low-memory eviction. ML translation remains the fast default; optional local dialogue refinement has a timeout. Conversation imports stream to EOF rather than silently stopping at 100,000 records.
- Public video discovery supplies real title/channel/thumbnail metadata and Open/Play actions through the existing yt-dlp dependency.
- Audio subtitles use overlapping PCM windows, an energy activity gate, Whisper no-speech confidence, overlap deduplication, and measured inference status. Visual OCR is explicitly separate and initially disabled. Playback restores position, offers speed/lock tools, and suppresses app navigation on parameterized local-player routes.
- Downloads accept supported separate AVC/AAC tracks, locally mux without recompression, verify actual output resolution/audio, and expose stage/retry details. Known advertisement media candidates are excluded. Database migration preserves existing downloads.
- Settings consolidate module switches and collapse advanced imports and diagnostics. Shared surfaces, contrast, typography, and spacing are revised. A lightweight shortcut widget opens translation, OREZ, Library, or Downloads without polling.

## Validation and limits

Focused regressions cover chapter ordering and filtering, MangaDex manifests, speech-window policy, result metadata, separate-track parsing, Android muxing, and reader mode transitions. CI generates deterministic media fixtures; private media tests derive metadata from the staged sample rather than assuming the older video's dimensions or duration. The new supplied video is 758.689 seconds, 640×360, H.264/AAC; a private first-30-second excerpt was prepared for speech testing.

Local build/test results are recorded after execution below. Android device execution and semantic translation/subtitle accuracy must be distinguished from compilation and JVM tests. Existing older-device results do not validate this new revision.

No 2× performance claim is made. The gzip conversation corpus still has bounded front-of-file scanning rather than an index; comprehensive late-corpus recall and a smaller quantized model tier remain future work. Lazy carousels without exposed image manifests may still require source-specific adapters. Speech activity filtering is a heuristic plus Whisper confidence, not a separate neural VAD. Separate-stream muxing intentionally supports AVC/AAC only. Download heights are ceilings and can be lower when the source lacks the selected format. Widget shortcuts require the app's normal model/media setup.

## Executed validation

- 80 JVM tests passed: zero failures, errors, or skips.
- Final lint, ARM64/x86_64 debug APK assembly, and Android test APK assembly passed after replacing the image aspect-ratio producer with explicit Compose state.
- Both APK archives, required native runtimes, and v2 signatures verified successfully. ARM64 APK: 108,369,468 bytes; x86_64 APK: 108,341,215 bytes.
- Private device tests did not execute: the workspace software emulator never reached completed boot. No subtitle-quality or physical-device throughput result is claimed.
- Initial build setup failures (JDK jlink and dependency certificate trust) were resolved locally. Lint warnings remain.

ARM64 APK SHA-256: `f29892d4eadd905f82ac8859daa0117bd7949cff72c10f897cd26f4e31e0e798`.

## Exact changed files

- `.github/scripts/create-video-ocr-fixture.sh`
- `.github/workflows/build-apk.yml`
- `.gitignore`
- `app/src/androidTest/java/com/mangalens/LocalMediaMuxerTest.kt`
- `app/src/androidTest/java/com/mangalens/ReaderModesTest.kt`
- `app/src/androidTest/java/com/mangalens/UploadedMediaTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/mangalens/MainActivity.kt`
- `app/src/main/java/com/mangalens/MangaLensApplication.kt`
- `app/src/main/java/com/mangalens/acquisition/RenderedBrowserAcquirer.kt`
- `app/src/main/java/com/mangalens/core/acquisition/ChapterDiscoveryScript.kt`
- `app/src/main/java/com/mangalens/core/acquisition/ChapterImagePolicy.kt`
- `app/src/main/java/com/mangalens/core/acquisition/MangaDexChapterSource.kt`
- `app/src/main/java/com/mangalens/core/acquisition/MangaSourceAdapter.kt`
- `app/src/main/java/com/mangalens/core/acquisition/StaticChapterAcquirer.kt`
- `app/src/main/java/com/mangalens/core/adblock/AdBlockEngine.kt`
- `app/src/main/java/com/mangalens/core/adblock/AdBlockStatsStore.kt`
- `app/src/main/java/com/mangalens/core/adblock/AdBlockWebViewClient.kt`
- `app/src/main/java/com/mangalens/core/adblock/EnterpriseAdBlockEngine.kt`
- `app/src/main/java/com/mangalens/core/reader/ChapterLibrary.kt`
- `app/src/main/java/com/mangalens/core/reader/ChapterPage.kt`
- `app/src/main/java/com/mangalens/core/reader/ProgressiveChapterRepository.kt`
- `app/src/main/java/com/mangalens/core/translation/TranslationOrezRefiner.kt`
- `app/src/main/java/com/mangalens/download/DownloadModels.kt`
- `app/src/main/java/com/mangalens/download/DownloadRequestContextStore.kt`
- `app/src/main/java/com/mangalens/download/LocalMediaMuxer.kt`
- `app/src/main/java/com/mangalens/download/MediaDownloadManager.kt`
- `app/src/main/java/com/mangalens/download/MediaDownloadWorker.kt`
- `app/src/main/java/com/mangalens/download/MediaLinkResolver.kt`
- `app/src/main/java/com/mangalens/download/SiteMediaExtractor.kt`
- `app/src/main/java/com/mangalens/engine/MangaChapterScraper.kt`
- `app/src/main/java/com/mangalens/orez/HeavyweightDataVaultManager.kt`
- `app/src/main/java/com/mangalens/orez/OrezBrain.kt`
- `app/src/main/java/com/mangalens/orez/OrezConversationPackStore.kt`
- `app/src/main/java/com/mangalens/orez/OrezLocalModelService.kt`
- `app/src/main/java/com/mangalens/orez/OrezVideoSearch.kt`
- `app/src/main/java/com/mangalens/ui/MangaLensNavGraph.kt`
- `app/src/main/java/com/mangalens/ui/ai/OrezAiScreen.kt`
- `app/src/main/java/com/mangalens/ui/components/CinematicComponents.kt`
- `app/src/main/java/com/mangalens/ui/downloads/DownloadsScreen.kt`
- `app/src/main/java/com/mangalens/ui/reader/MangaContinuousReader.kt`
- `app/src/main/java/com/mangalens/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/mangalens/ui/theme/Theme.kt`
- `app/src/main/java/com/mangalens/ui/video/LiveAudioSubtitleControls.kt`
- `app/src/main/java/com/mangalens/ui/video/LocalVideoPlayerScreen.kt`
- `app/src/main/java/com/mangalens/ui/video/LocalVideoPlayerViewModel.kt`
- `app/src/main/java/com/mangalens/ui/video/NativeVideoPlayer.kt`
- `app/src/main/java/com/mangalens/ui/video/SpeechWindowPolicy.kt`
- `app/src/main/java/com/mangalens/ui/video/VideoSpeechEngine.kt`
- `app/src/main/java/com/mangalens/ui/web/AdBlockedWebScreen.kt`
- `app/src/main/java/com/mangalens/widget/MangaLensWidget.kt`
- `app/src/main/res/drawable/widget_background.xml`
- `app/src/main/res/layout/mangalens_widget.xml`
- `app/src/main/res/values/widget_strings.xml`
- `app/src/main/res/xml/mangalens_widget_info.xml`
- `app/src/test/java/com/mangalens/core/acquisition/ChapterIntegrityTest.kt`
- `app/src/test/java/com/mangalens/download/SiteMediaInfoParserTest.kt`
- `app/src/test/java/com/mangalens/orez/OrezVideoResultCodecTest.kt`
- `app/src/test/java/com/mangalens/ui/video/SpeechWindowPolicyTest.kt`
- `docs/EVIDENCE_UPGRADE_HANDOFF.md`
- `orez-native/src/main/cpp/orez_native.cpp`
- `orez-native/src/main/java/com/mangalens/oreznative/OrezNativeEngine.kt`
- `whisper-native/src/main/cpp/whisper_jni.cpp`
