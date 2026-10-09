# MangaLens requirements ledger

Audit date: 2026-10-09 UTC. Contract: the complete user-supplied **MangaLens — full autonomous engineering mission and continuity**, prepared 2026-10-09, including Appendices A, B and C. Input SHA-256: `b5be5af2725e02a327871b01a4bcb613de6ba7c5652fcde6805f0c07c7d71c43`.

This is a source audit of the restored mature continuation identified by the brief as `engineering/mangalens-next-orez-foundation` at `b08b9ced12f08cf980194afb7d940bd24c8950c6`. The parent engineering task owns live ancestry, checkout restoration, subsequent code changes and execution evidence. At the beginning of this audit the source snapshot had no local Git metadata. Treat concurrent changes as candidate work until the parent binds them to a source identity and reports.

**No Android instrumentation, emulator acceptance, model-quality evaluation or build was run by this audit. No product requirement is marked implemented/verified here.** The historical CI run [37723586749](https://github.com/RezoxNemesis/MangaLens/actions/runs/37723586749) was reported successful for the baseline in the supplied brief; it compiled Android tests and explicitly skipped emulator execution. Its reported APK identity/size/checksum is historical evidence, not a current candidate artifact.

| Status | Meaning |
|---|---|
| implemented/verified | Requested real behavior and meaningful failure/recovery/quality checks passed for an identified current candidate; relevant emulator gate passed. No rows currently meet this standard. |
| implemented/unverified | A concrete source/UI/runtime path exists; its current candidate runtime, output quality or full acceptance has not been executed. |
| partial | Some requirements in the contract exist, but the complete behavior, schema, execution provider, coverage or quality gate is incomplete. |
| planned | No complete source path was located for the requested capability; related source indicates an extension point. |
| blocked | Necessary artifact, environment, model, permission or external evidence is unavailable; the blocker is named. |

A source filename or a compiled test is not proof of behavior. Related tests listed below describe existing coverage and have not been executed by this audit. A table cell sharing a status across several individually identified clauses applies that status to **each** listed clause. Repeated clauses are deliberately retained to cover the entire brief; prose-only sections receive a contract row. Code examples describe intended schemas and are covered by the explicit schema rows. Historical implementation/process snapshots are retained as requirements/continuity records, not asserted as fresh results.

## Highest-value remaining work

| Priority | Gap | Concrete next acceptance |
|---|---|
| P0 | Exact checkout/source provenance and actual cloud emulator baseline; current PR workflow skips emulator. | Update major-upgrade gate, install matching x86_64 APK, run core screen actions and retain reports/screenshots/logcat/source/APK identity. |
| P0 | Durable chapter translation prototype and private recording fixtures are not supplied in this workspace. Baseline chapter translation is UI-owned and retains overlay patches. | Build the durable disk-backed design from actual source; prove generation fences, OCR cancellation, pause/resume/replacement, source/config invalidation, partial work and process restart through UI. |
| P0 | Playback resolution needs a bounded, cancellable native extraction path and explicit Cancel/Back/Open source UX. | Stall native/fixture extraction and verify cancellation terminates the real process; retry with preserved user mode/session. |
| P1 | Orez durability supports sequential downloads only; other tools are handoffs. | Add genuine typed chapter/vision/translation/subtitle/research completion providers with output identities, bounded recovery and dependencies while preserving download replay/cancel behavior. |
| P1 | Region OCR fusion, selective crop retry, vertical Japanese, panel/bubble/SFX semantics and semantic Hindi/glossary quality are incomplete. | Held-out geometry/text/meaning/register/name tests, real OCR/inference and in-reader correction persistence. |
| P1 | Full-video captions are a UI-owned English SRT coroutine, not a resumable background task. | Durable window journal, foreground ownership, target/style identity, cancellation/restart, timestamps and actual export. |
| P2 | Model manifests/signatures/compatibility/rollback, Max and specialists, thermal/battery scheduler and empirical routing are incomplete. | Transactional corrupt/low-disk/incompatible pack tests and real feasible inference/resource comparisons. |
| P2 | Home customization, advanced reader modes, richer library/global semantic search, browser tabs/profiles/DOM agent and unified storage remain incomplete. | Data migrations, real UI effects, safe import/export and source-aware end-to-end flows. |
| P3 | Training/evaluation lab and frontier competitive claims lack held-out performance/quality evidence. | Licensed data/splits/hashes/seeds; reproducible before/after model reports using available free compute. |

## Source and test evidence index

### Evidence ui

Source: [HomeScreen.kt](../app/src/main/java/com/mangalens/ui/home/HomeScreen.kt); [MangaLensBottomNav.kt](../app/src/main/java/com/mangalens/ui/components/MangaLensBottomNav.kt); [MangaLensNavGraph.kt](../app/src/main/java/com/mangalens/ui/MangaLensNavGraph.kt); [SettingsScreen.kt](../app/src/main/java/com/mangalens/ui/settings/SettingsScreen.kt); [Appearance.kt](../app/src/main/java/com/mangalens/ui/theme/Appearance.kt); [Theme.kt](../app/src/main/java/com/mangalens/ui/theme/Theme.kt); [mangalens_approved_logo.png](../app/src/main/res/drawable-nodpi/mangalens_approved_logo.png).

Related tests/tooling (not run by this audit): [ProductSmokeTest.kt](../app/src/androidTest/java/com/mangalens/ProductSmokeTest.kt).

### Evidence reader

Source: [MangaContinuousReader.kt](../app/src/main/java/com/mangalens/ui/reader/MangaContinuousReader.kt); [MangaTranslationOverlay.kt](../app/src/main/java/com/mangalens/ui/reader/MangaTranslationOverlay.kt); [DocumentImporter.kt](../app/src/main/java/com/mangalens/core/reader/DocumentImporter.kt); [ProgressiveChapterRepository.kt](../app/src/main/java/com/mangalens/core/reader/ProgressiveChapterRepository.kt).

Related tests/tooling (not run by this audit): [ReaderModesTest.kt](../app/src/androidTest/java/com/mangalens/ReaderModesTest.kt); [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt); [SafeChapterArchiveTest.kt](../app/src/test/java/com/mangalens/core/imports/SafeChapterArchiveTest.kt); [BoundedTransferTest.kt](../app/src/test/java/com/mangalens/core/reader/BoundedTransferTest.kt).

### Evidence library

Source: [ChapterLibrary.kt](../app/src/main/java/com/mangalens/core/reader/ChapterLibrary.kt); [LibraryScreen.kt](../app/src/main/java/com/mangalens/ui/library/LibraryScreen.kt); [OrezRoomDatabase.kt](../app/src/main/java/com/mangalens/orez/OrezRoomDatabase.kt).

Related tests/tooling (not run by this audit): [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt); [OrezRoomMigrationTest.kt](../app/src/androidTest/java/com/mangalens/OrezRoomMigrationTest.kt).

### Evidence resolver

Source: [ContentType.kt](../app/src/main/java/com/mangalens/core/model/ContentType.kt); [UrlEngineRouter.kt](../app/src/main/java/com/mangalens/core/router/UrlEngineRouter.kt); [MangaLensViewModel.kt](../app/src/main/java/com/mangalens/ui/MangaLensViewModel.kt); [MediaLinkResolver.kt](../app/src/main/java/com/mangalens/download/MediaLinkResolver.kt); [MediaRequestContext.kt](../app/src/main/java/com/mangalens/ui/video/MediaRequestContext.kt).

Related tests/tooling (not run by this audit): [UrlEngineRouterTest.kt](../app/src/test/java/com/mangalens/core/router/UrlEngineRouterTest.kt); [UrlSafetyTest.kt](../app/src/test/java/com/mangalens/core/router/UrlSafetyTest.kt); [MediaRequestContextTest.kt](../app/src/test/java/com/mangalens/ui/video/MediaRequestContextTest.kt); [MediaLinkResolverTest.kt](../app/src/test/java/com/mangalens/download/MediaLinkResolverTest.kt).

### Evidence acquisition

Source: [StaticChapterAcquirer.kt](../app/src/main/java/com/mangalens/core/acquisition/StaticChapterAcquirer.kt); [MangaSourceAdapter.kt](../app/src/main/java/com/mangalens/core/acquisition/MangaSourceAdapter.kt); [RenderedBrowserAcquirer.kt](../app/src/main/java/com/mangalens/acquisition/RenderedBrowserAcquirer.kt); [MangaChapterCatalogScraper.kt](../app/src/main/java/com/mangalens/engine/MangaChapterCatalogScraper.kt); [ProgressiveChapterRepository.kt](../app/src/main/java/com/mangalens/core/reader/ProgressiveChapterRepository.kt); [ReaderPromoPolicy.kt](../app/src/main/java/com/mangalens/core/reader/ReaderPromoPolicy.kt).

Related tests/tooling (not run by this audit): [MangaSourceAdapterTest.kt](../app/src/test/java/com/mangalens/core/acquisition/MangaSourceAdapterTest.kt); [ReaderPromoPolicyTest.kt](../app/src/test/java/com/mangalens/core/reader/ReaderPromoPolicyTest.kt); [UploadedMangaTest.kt](../app/src/androidTest/java/com/mangalens/UploadedMangaTest.kt).

### Evidence ocr

Source: [AdvancedTranslationEngine.kt](../app/src/main/java/com/mangalens/engine/AdvancedTranslationEngine.kt); [AdvancedOcrTranslationEngine.kt](../app/src/main/java/com/mangalens/engine/AdvancedOcrTranslationEngine.kt); [OcrTextProcessor.kt](../app/src/main/java/com/mangalens/engine/OcrTextProcessor.kt); [TranslationOcrEngine.kt](../app/src/main/java/com/mangalens/core/translation/TranslationOcrEngine.kt).

Related tests/tooling (not run by this audit): [MultilingualOcrTest.kt](../app/src/androidTest/java/com/mangalens/MultilingualOcrTest.kt); [OcrDialogueMergeTest.kt](../app/src/androidTest/java/com/mangalens/OcrDialogueMergeTest.kt); [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt); [V12SubsystemTest.kt](../app/src/test/java/com/mangalens/core/V12SubsystemTest.kt).

### Evidence translation

Source: [TranslationService.kt](../app/src/main/java/com/mangalens/core/translation/TranslationService.kt); [TranslationQualityPolicy.kt](../app/src/main/java/com/mangalens/core/translation/TranslationQualityPolicy.kt); [TranslationOrezRefiner.kt](../app/src/main/java/com/mangalens/core/translation/TranslationOrezRefiner.kt); [TranslationStyleProfile.kt](../app/src/main/java/com/mangalens/core/translation/TranslationStyleProfile.kt); [MangaLensViewModel.kt](../app/src/main/java/com/mangalens/ui/MangaLensViewModel.kt); [OrezRoomDatabase.kt](../app/src/main/java/com/mangalens/orez/OrezRoomDatabase.kt).

Related tests/tooling (not run by this audit): [TranslationQualityPolicyTest.kt](../app/src/test/java/com/mangalens/core/translation/TranslationQualityPolicyTest.kt); [TranslationDialogueNormalizationTest.kt](../app/src/test/java/com/mangalens/core/translation/TranslationDialogueNormalizationTest.kt); [TranslationStyleProfileTest.kt](../app/src/test/java/com/mangalens/core/translation/TranslationStyleProfileTest.kt); [ChapterTranslationTest.kt](../app/src/androidTest/java/com/mangalens/ChapterTranslationTest.kt); [ReaderTranslationRecoveryTest.kt](../app/src/androidTest/java/com/mangalens/ReaderTranslationRecoveryTest.kt).

### Evidence lettering

Source: [MangaLettering.kt](../app/src/main/java/com/mangalens/core/translation/MangaLettering.kt); [OcrInpaintingEngine.kt](../app/src/main/java/com/mangalens/core/translation/OcrInpaintingEngine.kt); [AdvancedTranslationEngine.kt](../app/src/main/java/com/mangalens/engine/AdvancedTranslationEngine.kt); [MangaTranslationOverlay.kt](../app/src/main/java/com/mangalens/ui/reader/MangaTranslationOverlay.kt).

Related tests/tooling (not run by this audit): [MangaLetteringTest.kt](../app/src/androidTest/java/com/mangalens/MangaLetteringTest.kt); [ChapterTranslationTest.kt](../app/src/androidTest/java/com/mangalens/ChapterTranslationTest.kt); [UploadedMangaTest.kt](../app/src/androidTest/java/com/mangalens/UploadedMangaTest.kt).

### Evidence agent

Source: [OrezAgentModels.kt](../app/src/main/java/com/mangalens/orez/agent/OrezAgentModels.kt); [OrezAgentPlanner.kt](../app/src/main/java/com/mangalens/orez/agent/OrezAgentPlanner.kt); [OrezAgentRuntime.kt](../app/src/main/java/com/mangalens/orez/agent/OrezAgentRuntime.kt); [OrezToolRegistry.kt](../app/src/main/java/com/mangalens/orez/agent/OrezToolRegistry.kt); [OrezModelPlanDecoder.kt](../app/src/main/java/com/mangalens/orez/agent/OrezModelPlanDecoder.kt); [OrezPolicyEngine.kt](../app/src/main/java/com/mangalens/orez/agent/OrezPolicyEngine.kt); [OrezBrain.kt](../app/src/main/java/com/mangalens/orez/OrezBrain.kt).

Related tests/tooling (not run by this audit): [OrezAgentRuntimeTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezAgentRuntimeTest.kt); [OrezModelPlanDecoderTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezModelPlanDecoderTest.kt); [OrezFallbackAnswerTest.kt](../app/src/androidTest/java/com/mangalens/OrezFallbackAnswerTest.kt).

### Evidence tasks

Source: [OrezTaskExecutor.kt](../app/src/main/java/com/mangalens/orez/agent/OrezTaskExecutor.kt); [OrezTaskStore.kt](../app/src/main/java/com/mangalens/orez/agent/OrezTaskStore.kt); [OrezDownloadTaskWorker.kt](../app/src/main/java/com/mangalens/orez/agent/OrezDownloadTaskWorker.kt); [OrezDownloadTaskLink.kt](../app/src/main/java/com/mangalens/orez/agent/OrezDownloadTaskLink.kt); [OrezRoomDatabase.kt](../app/src/main/java/com/mangalens/orez/OrezRoomDatabase.kt); [OrezAiScreen.kt](../app/src/main/java/com/mangalens/ui/orez/OrezAiScreen.kt).

Related tests/tooling (not run by this audit): [OrezTaskExecutorTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezTaskExecutorTest.kt); [OrezTaskStoreTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezTaskStoreTest.kt); [OrezDownloadTaskLinkTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezDownloadTaskLinkTest.kt); [OrezExecutorRecoveryTest.kt](../app/src/androidTest/java/com/mangalens/OrezExecutorRecoveryTest.kt); [OrezTaskMigrationTest.kt](../app/src/androidTest/java/com/mangalens/OrezTaskMigrationTest.kt); [MediaDownloadRequestIdentityTest.kt](../app/src/androidTest/java/com/mangalens/download/MediaDownloadRequestIdentityTest.kt).

### Evidence models

Source: [OrezModelCatalog.kt](../app/src/main/java/com/mangalens/orez/OrezModelCatalog.kt); [OrezModelManager.kt](../app/src/main/java/com/mangalens/orez/OrezModelManager.kt); [OrezModelDownloadWorker.kt](../app/src/main/java/com/mangalens/orez/OrezModelDownloadWorker.kt); [OrezLocalModelService.kt](../app/src/main/java/com/mangalens/orez/OrezLocalModelService.kt); [orez_native.cpp](../orez-native/src/main/cpp/orez_native.cpp); [OrezNativeEngine.kt](../orez-native/src/main/java/com/mangalens/oreznative/OrezNativeEngine.kt).

Related tests/tooling (not run by this audit): [OrezModelCatalogTest.kt](../app/src/test/java/com/mangalens/orez/OrezModelCatalogTest.kt); [NativeModelTest.kt](../app/src/androidTest/java/com/mangalens/NativeModelTest.kt); [verify_model_catalog.py](../orez-evals/verify_model_catalog.py).

### Evidence memory

Source: [OrezRoomDatabase.kt](../app/src/main/java/com/mangalens/orez/OrezRoomDatabase.kt); [OrezBrain.kt](../app/src/main/java/com/mangalens/orez/OrezBrain.kt); [OrezConversationPackStore.kt](../app/src/main/java/com/mangalens/orez/OrezConversationPackStore.kt); [OrezCorpusWindowPlanner.kt](../app/src/main/java/com/mangalens/orez/OrezCorpusWindowPlanner.kt); [HeavyweightDataVaultManager.kt](../app/src/main/java/com/mangalens/orez/HeavyweightDataVaultManager.kt).

Related tests/tooling (not run by this audit): [OrezRoomMigrationTest.kt](../app/src/androidTest/java/com/mangalens/OrezRoomMigrationTest.kt); [OrezCorpusWindowPlannerTest.kt](../app/src/test/java/com/mangalens/orez/OrezCorpusWindowPlannerTest.kt); [OrezFallbackAnswerTest.kt](../app/src/androidTest/java/com/mangalens/OrezFallbackAnswerTest.kt).

### Evidence research

Source: [OrezLiveSearchConnector.kt](../app/src/main/java/com/mangalens/engine/OrezLiveSearchConnector.kt); [OrezSearchParser.kt](../app/src/main/java/com/mangalens/engine/OrezSearchParser.kt); [OrezVideoSearch.kt](../app/src/main/java/com/mangalens/orez/OrezVideoSearch.kt); [OrezDiscoveryPolicy.kt](../app/src/main/java/com/mangalens/orez/OrezDiscoveryPolicy.kt); [OrezBrain.kt](../app/src/main/java/com/mangalens/orez/OrezBrain.kt).

Related tests/tooling (not run by this audit): [OrezSearchParserTest.kt](../app/src/test/java/com/mangalens/engine/OrezSearchParserTest.kt); [OrezLiveSearchConnectorTest.kt](../app/src/test/java/com/mangalens/engine/OrezLiveSearchConnectorTest.kt); [OrezDiscoveryPolicyTest.kt](../app/src/test/java/com/mangalens/orez/OrezDiscoveryPolicyTest.kt); [OrezVideoResultCodecTest.kt](../app/src/test/java/com/mangalens/orez/OrezVideoResultCodecTest.kt).

### Evidence web

Source: [AdBlockedWebScreen.kt](../app/src/main/java/com/mangalens/ui/web/AdBlockedWebScreen.kt); [BrowserAddress.kt](../app/src/main/java/com/mangalens/ui/web/BrowserAddress.kt); [SafeWebView.kt](../app/src/main/java/com/mangalens/core/web/SafeWebView.kt); [OnlineMediaSniffer.kt](../app/src/main/java/com/mangalens/ui/video/OnlineMediaSniffer.kt); [WebTranslationScript.kt](../app/src/main/java/com/mangalens/core/translation/WebTranslationScript.kt).

Related tests/tooling (not run by this audit): [BrowserAddressTest.kt](../app/src/test/java/com/mangalens/ui/web/BrowserAddressTest.kt); [WebTranslationScriptTest.kt](../app/src/test/java/com/mangalens/core/translation/WebTranslationScriptTest.kt); [WebPlaybackCaptureTest.kt](../app/src/androidTest/java/com/mangalens/WebPlaybackCaptureTest.kt); [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt).

### Evidence protection

Source: [AdBlockEngine.kt](../app/src/main/java/com/mangalens/core/adblock/AdBlockEngine.kt); [AdBlockWebViewClient.kt](../app/src/main/java/com/mangalens/core/adblock/AdBlockWebViewClient.kt); [EnterpriseAdBlockEngine.kt](../app/src/main/java/com/mangalens/core/adblock/EnterpriseAdBlockEngine.kt); [AdBlockStatsStore.kt](../app/src/main/java/com/mangalens/core/adblock/AdBlockStatsStore.kt); [SettingsScreen.kt](../app/src/main/java/com/mangalens/ui/settings/SettingsScreen.kt).

Related tests/tooling (not run by this audit): [AdBlockEngineTest.kt](../app/src/test/java/com/mangalens/core/adblock/AdBlockEngineTest.kt); [WebPlaybackCaptureTest.kt](../app/src/androidTest/java/com/mangalens/WebPlaybackCaptureTest.kt).

### Evidence media

Source: [MediaLinkResolver.kt](../app/src/main/java/com/mangalens/download/MediaLinkResolver.kt); [SiteMediaExtractor.kt](../app/src/main/java/com/mangalens/download/SiteMediaExtractor.kt); [NativeVideoPlayer.kt](../app/src/main/java/com/mangalens/ui/video/NativeVideoPlayer.kt); [LocalVideoPlayerScreen.kt](../app/src/main/java/com/mangalens/ui/video/LocalVideoPlayerScreen.kt); [LocalVideoPlayerViewModel.kt](../app/src/main/java/com/mangalens/ui/video/LocalVideoPlayerViewModel.kt); [AdvancedVideoEngine.kt](../app/src/main/java/com/mangalens/ui/video/AdvancedVideoEngine.kt); [MediaPlaybackDataSource.kt](../app/src/main/java/com/mangalens/ui/video/MediaPlaybackDataSource.kt); [VideoSourcePolicy.kt](../app/src/main/java/com/mangalens/ui/video/VideoSourcePolicy.kt).

Related tests/tooling (not run by this audit): [MediaLinkResolverTest.kt](../app/src/test/java/com/mangalens/download/MediaLinkResolverTest.kt); [SiteMediaInfoParserTest.kt](../app/src/test/java/com/mangalens/download/SiteMediaInfoParserTest.kt); [MediaExtractorCancellationTest.kt](../app/src/test/java/com/mangalens/download/MediaExtractorCancellationTest.kt); [VideoSourcePolicyTest.kt](../app/src/test/java/com/mangalens/ui/video/VideoSourcePolicyTest.kt); [SiteExtractorRuntimeTest.kt](../app/src/androidTest/java/com/mangalens/SiteExtractorRuntimeTest.kt); [MediaPlaybackHeadersTest.kt](../app/src/androidTest/java/com/mangalens/MediaPlaybackHeadersTest.kt); [UploadedMediaTest.kt](../app/src/androidTest/java/com/mangalens/UploadedMediaTest.kt).

### Evidence captions

Source: [LiveVideoOcrTranslation.kt](../app/src/main/java/com/mangalens/ui/video/LiveVideoOcrTranslation.kt); [LiveAudioSubtitleControls.kt](../app/src/main/java/com/mangalens/ui/video/LiveAudioSubtitleControls.kt); [VideoSpeechEngine.kt](../app/src/main/java/com/mangalens/ui/video/VideoSpeechEngine.kt); [SpeechWindowPolicy.kt](../app/src/main/java/com/mangalens/ui/video/SpeechWindowPolicy.kt); [FullVideoSubtitleGenerator.kt](../app/src/main/java/com/mangalens/ui/video/FullVideoSubtitleGenerator.kt); [VideoSubtitleTranslator.kt](../app/src/main/java/com/mangalens/ui/video/VideoSubtitleTranslator.kt); [WebAudioCaptureService.kt](../app/src/main/java/com/mangalens/ui/web/WebAudioCaptureService.kt); [whisper_jni.cpp](../whisper-native/src/main/cpp/whisper_jni.cpp).

Related tests/tooling (not run by this audit): [SpeechWindowPolicyTest.kt](../app/src/test/java/com/mangalens/ui/video/SpeechWindowPolicyTest.kt); [FullVideoSubtitleGeneratorTest.kt](../app/src/test/java/com/mangalens/ui/video/FullVideoSubtitleGeneratorTest.kt); [LiveSpeechSampleTest.kt](../app/src/androidTest/java/com/mangalens/ui/video/LiveSpeechSampleTest.kt); [SpeechAudioProcessorTest.kt](../app/src/androidTest/java/com/mangalens/ui/video/SpeechAudioProcessorTest.kt); [VideoFrameCaptureTest.kt](../app/src/androidTest/java/com/mangalens/VideoFrameCaptureTest.kt); [WebPlaybackCaptureTest.kt](../app/src/androidTest/java/com/mangalens/WebPlaybackCaptureTest.kt).

### Evidence downloads

Source: [MediaDownloadManager.kt](../app/src/main/java/com/mangalens/download/MediaDownloadManager.kt); [MediaDownloadWorker.kt](../app/src/main/java/com/mangalens/download/MediaDownloadWorker.kt); [ResumableMediaTransfer.kt](../app/src/main/java/com/mangalens/download/ResumableMediaTransfer.kt); [DownloadRequestContextStore.kt](../app/src/main/java/com/mangalens/download/DownloadRequestContextStore.kt); [LocalMediaMuxer.kt](../app/src/main/java/com/mangalens/download/LocalMediaMuxer.kt); [MediaCompletenessPolicy.kt](../app/src/main/java/com/mangalens/download/MediaCompletenessPolicy.kt); [DownloadModels.kt](../app/src/main/java/com/mangalens/download/DownloadModels.kt); [AdaptiveDownloadBridge.kt](../app/src/main/java/com/mangalens/download/AdaptiveDownloadBridge.kt); [MangaLensDownloadService.kt](../app/src/main/java/com/mangalens/download/MangaLensDownloadService.kt); [DownloadsScreen.kt](../app/src/main/java/com/mangalens/ui/downloads/DownloadsScreen.kt).

Related tests/tooling (not run by this audit): [ResumableMediaTransferTest.kt](../app/src/test/java/com/mangalens/download/ResumableMediaTransferTest.kt); [MediaCompletenessPolicyTest.kt](../app/src/test/java/com/mangalens/download/MediaCompletenessPolicyTest.kt); [ScopedDownloadHeadersTest.kt](../app/src/test/java/com/mangalens/download/ScopedDownloadHeadersTest.kt); [DownloadModelsTest.kt](../app/src/test/java/com/mangalens/download/DownloadModelsTest.kt); [LocalMediaMuxerTest.kt](../app/src/androidTest/java/com/mangalens/LocalMediaMuxerTest.kt); [AdaptiveDownloadRecoveryTest.kt](../app/src/androidTest/java/com/mangalens/AdaptiveDownloadRecoveryTest.kt); [AdaptiveOfflinePlaybackTest.kt](../app/src/androidTest/java/com/mangalens/AdaptiveOfflinePlaybackTest.kt); [DownloadRequestContextStoreTest.kt](../app/src/androidTest/java/com/mangalens/download/DownloadRequestContextStoreTest.kt); [MediaDownloadRequestIdentityTest.kt](../app/src/androidTest/java/com/mangalens/download/MediaDownloadRequestIdentityTest.kt).

### Evidence android

Source: [MainActivity.kt](../app/src/main/java/com/mangalens/MainActivity.kt); [AndroidManifest.xml](../app/src/main/AndroidManifest.xml); [MangaLensWidget.kt](../app/src/main/java/com/mangalens/widget/MangaLensWidget.kt); [LocalVideoCatalog.kt](../app/src/main/java/com/mangalens/ui/video/LocalVideoCatalog.kt); [LocalVideoGalleryScreen.kt](../app/src/main/java/com/mangalens/ui/video/LocalVideoGalleryScreen.kt); [download_paths.xml](../app/src/main/res/xml/download_paths.xml).

Related tests/tooling (not run by this audit): [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt); [ProductSmokeTest.kt](../app/src/androidTest/java/com/mangalens/ProductSmokeTest.kt).

### Evidence security

Source: [SafeChapterArchive.kt](../app/src/main/java/com/mangalens/core/imports/SafeChapterArchive.kt); [UrlEngineRouter.kt](../app/src/main/java/com/mangalens/core/router/UrlEngineRouter.kt); [SafeWebView.kt](../app/src/main/java/com/mangalens/core/web/SafeWebView.kt); [OrezPromptBoundary.kt](../app/src/main/java/com/mangalens/orez/OrezPromptBoundary.kt); [OrezPolicyEngine.kt](../app/src/main/java/com/mangalens/orez/agent/OrezPolicyEngine.kt); [MediaRequestContext.kt](../app/src/main/java/com/mangalens/ui/video/MediaRequestContext.kt); [ChapterLibrary.kt](../app/src/main/java/com/mangalens/core/reader/ChapterLibrary.kt).

Related tests/tooling (not run by this audit): [SafeChapterArchiveTest.kt](../app/src/test/java/com/mangalens/core/imports/SafeChapterArchiveTest.kt); [UrlSafetyTest.kt](../app/src/test/java/com/mangalens/core/router/UrlSafetyTest.kt); [OrezPromptBoundaryTest.kt](../app/src/test/java/com/mangalens/orez/OrezPromptBoundaryTest.kt); [OrezAgentRuntimeTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezAgentRuntimeTest.kt); [ScopedDownloadHeadersTest.kt](../app/src/test/java/com/mangalens/download/ScopedDownloadHeadersTest.kt); [OfflineLibraryTest.kt](../app/src/androidTest/java/com/mangalens/OfflineLibraryTest.kt).

### Evidence resources

Source: [MangaLensApplication.kt](../app/src/main/java/com/mangalens/MangaLensApplication.kt); [OrezLocalModelService.kt](../app/src/main/java/com/mangalens/orez/OrezLocalModelService.kt); [OrezModelManager.kt](../app/src/main/java/com/mangalens/orez/OrezModelManager.kt); [AdvancedTranslationEngine.kt](../app/src/main/java/com/mangalens/engine/AdvancedTranslationEngine.kt); [MangaLensViewModel.kt](../app/src/main/java/com/mangalens/ui/MangaLensViewModel.kt); [VideoSpeechEngine.kt](../app/src/main/java/com/mangalens/ui/video/VideoSpeechEngine.kt); [FullVideoSubtitleGenerator.kt](../app/src/main/java/com/mangalens/ui/video/FullVideoSubtitleGenerator.kt).

Related tests/tooling (not run by this audit): [OrezCorpusWindowPlannerTest.kt](../app/src/test/java/com/mangalens/orez/OrezCorpusWindowPlannerTest.kt); [SpeechWindowPolicyTest.kt](../app/src/test/java/com/mangalens/ui/video/SpeechWindowPolicyTest.kt); [NativeModelTest.kt](../app/src/androidTest/java/com/mangalens/NativeModelTest.kt); [SpeechAudioProcessorTest.kt](../app/src/androidTest/java/com/mangalens/ui/video/SpeechAudioProcessorTest.kt).

### Evidence lab

Source: [build_conversation_pack.py](../orez-pack/build_conversation_pack.py); [stream_conversation_source.py](../orez-pack/stream_conversation_source.py); [conversation-sources.json](../orez-pack/conversation-sources.json); [README.md](../orez-evals/README.md); [verify_model_catalog.py](../orez-evals/verify_model_catalog.py).

Related tests/tooling (not run by this audit): [model-provenance.yml](../.github/workflows/model-provenance.yml); [OrezModelPlanDecoderTest.kt](../app/src/test/java/com/mangalens/orez/agent/OrezModelPlanDecoderTest.kt).

### Evidence build

Source: [build.gradle.kts](../app/build.gradle.kts); [build.gradle.kts](../build.gradle.kts); [build.gradle.kts](../orez-native/build.gradle.kts); [build.gradle.kts](../whisper-native/build.gradle.kts); [build-apk.yml](../.github/workflows/build-apk.yml); [verify-debug-apks.py](../.github/scripts/verify-debug-apks.py); [mangalens-startup-smoke.sh](../.github/scripts/mangalens-startup-smoke.sh); [mangalens-device-regression.sh](../.github/scripts/mangalens-device-regression.sh); [mangalens-private-sample-acceptance.sh](../.github/scripts/mangalens-private-sample-acceptance.sh); [create-video-ocr-fixture.sh](../.github/scripts/create-video-ocr-fixture.sh).

Related tests/tooling (not run by this audit): [ProductSmokeTest.kt](../app/src/androidTest/java/com/mangalens/ProductSmokeTest.kt); [verify_model_catalog.py](../orez-evals/verify_model_catalog.py).

## Complete Appendix A contract coverage

Every numbered original section and subsection is included below. Clause IDs retain source line identity (L numbers refer to the supplied 2026-10-09 brief); extra prose/schema contracts use `.X` IDs. Read grouped requirements as individually status-bearing items. The evidence link gives exact source and related test paths; the audit note identifies what remains missing or untested.

### A 0 — HOW TO USE THIS FILE IN A NEW CHAT

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A0.C · Full section contract, including prose and nested example context (brief L723). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A0.L727** When the original conversation becomes too long or unavailable: Start a new high-thinking ChatGPT conversation.<br>**A0.L728** When the original conversation becomes too long or unavailable: Attach this file.<br>**A0.L729** When the original conversation becomes too long or unavailable: Also attach the approved MangaLens logo PNG if available.<br>**A0.L730** When the original conversation becomes too long or unavailable: If available, attach the latest approved UI reference image.<br>**A0.L731** 5. Tell the new chat: Tell the new chat:<br>**A0.L733** 5. Tell the new chat: The new engineering session must inspect the live repository and CI state before modifying code. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A0.L734** 5. Tell the new chat: All commit hashes, PR states, workflow states and branch heads written in this file are a dated snapshot, not permission to assume that GitHub has not changed. Query GitHub live before editing. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 1 — NON-NEGOTIABLE PROJECT DIRECTIVES

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.C · Full section contract, including prose and nested example context (brief L740). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 1.1 — Protect the mature application

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.1.C · Full section contract, including prose and nested example context (brief L744). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A1.1.L752** Examples: If Library persistence is working, preserve it.<br>**A1.1.L753** Examples: If Reader functionality is mature, extend it rather than replacing it with a tiny reader.<br>**A1.1.L754** Examples: If the ad blocker works well, regression-test and improve it rather than deleting it and starting again.<br>**A1.1.L755** Examples: If the mature Downloads screen exposes more information than a new experimental implementation, preserve the mature UI and integrate the new backend beneath it.<br>**A1.1.L756** Examples: If the mature Navigation graph contains Library, Web, Orez, Settings, local video, reader and other routes, do not substitute a smaller navigation graph.<br>**A1.1.L757** Examples: Do not confuse "cleaner" with "remove half the product." | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 1.2 — UI references are references, not screenshots-as-UI

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.2.C · Full section contract, including prose and nested example context (brief L763). | implemented/unverified | [UI](#evidence-ui) | Real Compose components and approved logo resources are present; inspect actual rendered routes and interactions on the candidate. |
| **A1.2.L771** All real application elements must be native/real interactive UI: navigation,<br>**A1.2.L772** All real application elements must be native/real interactive UI: cards,<br>**A1.2.L773** All real application elements must be native/real interactive UI: horizontal carousels,<br>**A1.2.L774** All real application elements must be native/real interactive UI: menus,<br>**A1.2.L775** All real application elements must be native/real interactive UI: bottom sheets,<br>**A1.2.L776** All real application elements must be native/real interactive UI: lists, | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A1.2.L777** All real application elements must be native/real interactive UI: sliders,<br>**A1.2.L778** All real application elements must be native/real interactive UI: progress,<br>**A1.2.L779** All real application elements must be native/real interactive UI: buttons,<br>**A1.2.L780** All real application elements must be native/real interactive UI: text,<br>**A1.2.L781** All real application elements must be native/real interactive UI: state,<br>**A1.2.L782** All real application elements must be native/real interactive UI: animations, | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A1.2.L783** All real application elements must be native/real interactive UI: gestures,<br>**A1.2.L784** All real application elements must be native/real interactive UI: scroll,<br>**A1.2.L785** All real application elements must be native/real interactive UI: toggles,<br>**A1.2.L786** All real application elements must be native/real interactive UI: toolbars,<br>**A1.2.L787** All real application elements must be native/real interactive UI: player controls. | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 1.3 — The APK must be traceable to source

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.3.C · Full section contract, including prose and nested example context (brief L797). | partial | [BUILD](#evidence-build) | Version/channel/SHA and ABI checks are implemented. The old PR gate skips emulator execution; exact candidate reports, signed artifacts and runtime identity are still required. |
| **A1.3.L803** Every candidate build should expose: application version,<br>**A1.3.L804** Every candidate build should expose: channel,<br>**A1.3.L805** Every candidate build should expose: source commit SHA,<br>**A1.3.L806** Every candidate build should expose: build timestamp or CI run identifier where appropriate.<br>**A1.3.L810** CI must: build the intended branch/head,<br>**A1.3.L811** CI must: verify APK existence, | partial | [BUILD](#evidence-build) | Version/channel/SHA and ABI checks are implemented. The old PR gate skips emulator execution; exact candidate reports, signed artifacts and runtime identity are still required. |
| **A1.3.L812** CI must: verify architecture/native libraries,<br>**A1.3.L813** CI must: calculate SHA-256,<br>**A1.3.L814** CI must: upload the APK artifact,<br>**A1.3.L815** CI must: make artifact provenance obvious. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 1.4 — Do not artificially shrink the product ambition

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.4.C · Full section contract, including prose and nested example context (brief L821). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A1.4.L829** The system should be designed as a universal visual-media platform with: reader,<br>**A1.4.L830** The system should be designed as a universal visual-media platform with: browser,<br>**A1.4.L831** The system should be designed as a universal visual-media platform with: media engine,<br>**A1.4.L832** The system should be designed as a universal visual-media platform with: universal resolver/downloader,<br>**A1.4.L833** The system should be designed as a universal visual-media platform with: vision translation,<br>**A1.4.L834** The system should be designed as a universal visual-media platform with: Orez autonomous intelligence, | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A1.4.L835** The system should be designed as a universal visual-media platform with: library,<br>**A1.4.L836** The system should be designed as a universal visual-media platform with: privacy/security,<br>**A1.4.L837** The system should be designed as a universal visual-media platform with: tooling,<br>**A1.4.L838** The system should be designed as a universal visual-media platform with: local + hybrid AI. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 1.5 — No mandatory paid-service dependency

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.5.C · Full section contract, including prose and nested example context (brief L842). | implemented/unverified | [SECURITY](#evidence-security) | Core pipelines use on-device/free providers; verify offline operation and absence of paid fallback for the installed candidate. |

### A 1.6 — User-facing manual data-pack import is NOT the primary model distribution strategy

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A1.6.C · Full section contract, including prose and nested example context (brief L848). | partial | [MODELS](#evidence-models) | Managed resumable GGUF delivery exists; full signed/versioned packs, compatibility, update/rollback and broader specialist catalog are incomplete. |
| **A1.6.L854** Preferred strategy: Keep base APK reasonably sized and compatible with repository/release workflow constraints.<br>**A1.6.L855** Preferred strategy: Ship only core runtime/assets in APK.<br>**A1.6.L856** Preferred strategy: MangaLens Model Manager downloads large Orez model packs after installation.<br>**A1.6.L857** Preferred strategy: Downloads are automatic, resumable, versioned, integrity-checked and managed by the app.<br>**A1.6.L858** Preferred strategy: Model installation should feel like downloading an offline language pack, not like manually operating a developer data pipeline.<br>**A1.6.L863** For large binary models, a ZIP or custom pack containing: manifest JSON, | partial | [MODELS](#evidence-models) | Managed resumable GGUF delivery exists; full signed/versioned packs, compatibility, update/rollback and broader specialist catalog are incomplete. |
| **A1.6.L864** For large binary models, a ZIP or custom pack containing: binary shards,<br>**A1.6.L865** For large binary models, a ZIP or custom pack containing: hashes,<br>**A1.6.L866** For large binary models, a ZIP or custom pack containing: metadata is more appropriate than encoding raw weights into giant JSON. | partial | [MODELS](#evidence-models) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 2 — REPOSITORY CONTINUITY AND KNOWN HISTORY

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A2.C · Full section contract, including prose and nested example context (brief L873). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 2.1 — Protected mature line

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A2.1.C · Full section contract, including prose and nested example context (brief L878). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A2.1.L882** The mature product line identified during the recovery is: Branch: `engineering/mangalens-production`<br>**A2.1.L883** The mature product line identified during the recovery is: PR #6: `MangaLens 2.1: OREZ hybrid AI, best-quality downloads, OCR translation and UI upgrade`<br>**A2.1.L884** The mature product line identified during the recovery is: Snapshot head observed on 2026-10-06: `625b7d6efc602737ee3e53e7f4a3e459de816bba`<br>**A2.1.L887** This line contains the richer MangaLens 2.x application: mature branding,<br>**A2.1.L888** This line contains the richer MangaLens 2.x application: Home,<br>**A2.1.L889** This line contains the richer MangaLens 2.x application: Library, | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A2.1.L890** This line contains the richer MangaLens 2.x application: Orez,<br>**A2.1.L891** This line contains the richer MangaLens 2.x application: Downloads,<br>**A2.1.L892** This line contains the richer MangaLens 2.x application: Settings / Protection Center,<br>**A2.1.L893** This line contains the richer MangaLens 2.x application: Reader,<br>**A2.1.L894** This line contains the richer MangaLens 2.x application: Video,<br>**A2.1.L895** This line contains the richer MangaLens 2.x application: Web, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A2.1.L896** This line contains the richer MangaLens 2.x application: saved chapters,<br>**A2.1.L897** This line contains the richer MangaLens 2.x application: reading progress,<br>**A2.1.L898** This line contains the richer MangaLens 2.x application: stronger OCR/translation,<br>**A2.1.L899** This line contains the richer MangaLens 2.x application: adaptive downloading,<br>**A2.1.L900** This line contains the richer MangaLens 2.x application: media resolution,<br>**A2.1.L901** This line contains the richer MangaLens 2.x application: live subtitle work, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A2.1.L902** This line contains the richer MangaLens 2.x application: ad-block improvements,<br>**A2.1.L903** This line contains the richer MangaLens 2.x application: native/AI infrastructure. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 2.2 — Known regression incident

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A2.2.C · Full section contract, including prose and nested example context (brief L907). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A2.2.L916** Regression PR: PR #11<br>**A2.2.L917** Regression PR: branch: `fix/ocr-subtitles-social-downloads-v2`<br>**A2.2.L918** Regression PR: this line must NOT become the product base. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 2.3 — Recovery branch

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A2.3.C · Full section contract, including prose and nested example context (brief L922). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 2.4 — Starting procedure for any new engineering session

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A2.4.C · Full section contract, including prose and nested example context (brief L933). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A2.4.L954** If using GitHub connector tools instead of CLI: fetch PR #6 metadata,<br>**A2.4.L955** If using GitHub connector tools instead of CLI: fetch current head SHA,<br>**A2.4.L956** If using GitHub connector tools instead of CLI: compare branches/commits,<br>**A2.4.L957** If using GitHub connector tools instead of CLI: inspect workflow state,<br>**A2.4.L958** If using GitHub connector tools instead of CLI: inspect changed files,<br>**A2.4.L959** If using GitHub connector tools instead of CLI: only then modify code. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 3 — PRODUCT NORTH STAR

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A3.C · Full section contract, including prose and nested example context (brief L965). | partial | [RESOLVER](#evidence-resolver) | Reader/Web/Video routing, imports, native downloads and Orez exist; the full universal integrated contract is not complete. |
| **A3.L980** Paste or open almost any ordinary accessible content URL. / Examples: Manga chapter -> Reader<br>**A3.L981** Paste or open almost any ordinary accessible content URL. / Examples: Manga series page -> chapter/catalog discovery<br>**A3.L982** Paste or open almost any ordinary accessible content URL. / Examples: YouTube/Instagram/video page -> Media Resolver -> native player where possible<br>**A3.L983** Paste or open almost any ordinary accessible content URL. / Examples: HLS/DASH/direct stream -> native player<br>**A3.L984** Paste or open almost any ordinary accessible content URL. / Examples: Ordinary site -> Web<br>**A3.L985** Paste or open almost any ordinary accessible content URL. / Examples: Image -> Vision/OCR | partial | [RESOLVER](#evidence-resolver) | Reader/Web/Video routing, imports, native downloads and Orez exist; the full universal integrated contract is not complete. |
| **A3.L986** Paste or open almost any ordinary accessible content URL. / Examples: PDF/CBZ/ZIP -> Reader/import<br>**A3.L987** Paste or open almost any ordinary accessible content URL. / Examples: Media file -> Player<br>**A3.L988** Paste or open almost any ordinary accessible content URL. / Examples: Downloadable media -> Download Manager<br>**A3.L994** Paste or open almost any ordinary accessible content URL. / The product pillars are: Reader & Library<br>**A3.L995** Paste or open almost any ordinary accessible content URL. / The product pillars are: Vision / OCR / Reconstruction<br>**A3.L996** Paste or open almost any ordinary accessible content URL. / The product pillars are: Language / Translation | partial | [RESOLVER](#evidence-resolver) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A3.L997** Paste or open almost any ordinary accessible content URL. / The product pillars are: Universal Media / Streaming<br>**A3.L998** Paste or open almost any ordinary accessible content URL. / The product pillars are: Web Workspace<br>**A3.L999** Paste or open almost any ordinary accessible content URL. / The product pillars are: Universal Downloader<br>**A3.L1000** Paste or open almost any ordinary accessible content URL. / The product pillars are: Protection Center<br>**A3.L1001** Paste or open almost any ordinary accessible content URL. / The product pillars are: Orez Intelligence<br>**A3.L1002** Paste or open almost any ordinary accessible content URL. / The product pillars are: Offline/local model ecosystem | partial | [RESOLVER](#evidence-resolver) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A3.L1003** Paste or open almost any ordinary accessible content URL. / The product pillars are: Reliability and regression protection | partial | [RESOLVER](#evidence-resolver) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 4 — NEW VISUAL IDENTITY AND UI DIRECTION

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.C · Full section contract, including prose and nested example context (brief L1007). | implemented/unverified | [UI](#evidence-ui) | Graphite/slate/blue theme tokens and real compact Compose screens are present; visual, large-font and interaction acceptance remains unrun. |

### A 4.1 — Approved logo

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.1.C · Full section contract, including prose and nested example context (brief L1009). | implemented/unverified | [UI](#evidence-ui) | Approved logo resource and launcher assets exist. Historical approved blob identity and current APK pixels must be checked for the exact candidate. |
| **A4.1.L1014** Description: metallic/silver MangaLens "M" mark,<br>**A4.1.L1015** Description: dark/graphite premium appearance,<br>**A4.1.L1016** Description: modern geometry,<br>**A4.1.L1017** Description: restrained blue highlight,<br>**A4.1.L1018** Description: intended for app launcher and in-app brand identity. | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 4.2 — Visual philosophy

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.2.C · Full section contract, including prose and nested example context (brief L1022). | implemented/unverified | [UI](#evidence-ui) | Graphite/slate/blue theme tokens and real compact Compose screens are present; visual, large-font and interaction acceptance remains unrun. |
| **A4.2.L1028** The accepted direction is more restrained: near-black / graphite background,<br>**A4.2.L1029** The accepted direction is more restrained: deep slate surfaces,<br>**A4.2.L1030** The accepted direction is more restrained: white and soft-grey text,<br>**A4.2.L1031** The accepted direction is more restrained: subtle blue accent,<br>**A4.2.L1032** The accepted direction is more restrained: occasional violet only where meaningful,<br>**A4.2.L1033** The accepted direction is more restrained: limited glow, | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A4.2.L1034** The accepted direction is more restrained: limited gradients,<br>**A4.2.L1035** The accepted direction is more restrained: thin borders,<br>**A4.2.L1036** The accepted direction is more restrained: polished depth,<br>**A4.2.L1037** The accepted direction is more restrained: premium media artwork,<br>**A4.2.L1038** The accepted direction is more restrained: clean typography. | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 4.3 — Compact systematic layout

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.3.C · Full section contract, including prose and nested example context (brief L1042). | partial | [UI](#evidence-ui) | Horizontal modules, status rows, sheets and contextual actions exist; the entire requested menu/expand/swipe pattern set has not been implemented everywhere. |
| **A4.3.L1047** Use: compact horizontal carousels,<br>**A4.3.L1048** Use: small feature cards,<br>**A4.3.L1049** Use: one-line status rows,<br>**A4.3.L1051** Use: bottom sheets,<br>**A4.3.L1053** Use: contextual actions,<br>**A4.3.L1055** Use: menus, | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A4.3.L1050** Use: nested detail screens,<br>**A4.3.L1052** Use: expandable sections,<br>**A4.3.L1054** Use: collapsible advanced controls, | partial | [UI](#evidence-ui) | Horizontal modules, status rows, sheets and contextual actions exist; the entire requested menu/expand/swipe pattern set has not been implemented everywhere. |
| **A4.3.L1056** Use: swipe actions. | planned | [UI](#evidence-ui) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 4.4 — Layout density modes

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.4.C · Full section contract, including prose and nested example context (brief L1060). | implemented/unverified | [UI](#evidence-ui) | Three persisted LayoutDensity values are wired to Compose spacing; test viewport differences and usable touch targets. |
| **A4.4.L1063** Implement user-selectable density: Compact<br>**A4.4.L1064** Implement user-selectable density: Balanced<br>**A4.4.L1065** Implement user-selectable density: Comfortable | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 4.5 — Home customisation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.5.C · Full section contract, including prose and nested example context (brief L1069). | planned | [UI](#evidence-ui) | Home modules are fixed in HomeScreen; no persisted show/hide/reorder module model or customization UI was located. |
| **A4.5.L1072** Users should be able to show/hide/reorder modules: Continue Reading<br>**A4.5.L1073** Users should be able to show/hide/reorder modules: Continue Watching<br>**A4.5.L1074** Users should be able to show/hide/reorder modules: Recent Manga<br>**A4.5.L1075** Users should be able to show/hide/reorder modules: Recent Video<br>**A4.5.L1076** Users should be able to show/hide/reorder modules: Downloads<br>**A4.5.L1077** Users should be able to show/hide/reorder modules: Browser History | planned | [UI](#evidence-ui) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A4.5.L1078** Users should be able to show/hide/reorder modules: Orez Suggestions<br>**A4.5.L1079** Users should be able to show/hide/reorder modules: Translation Queue<br>**A4.5.L1080** Users should be able to show/hide/reorder modules: Recent Sites<br>**A4.5.L1081** Users should be able to show/hide/reorder modules: Bookmarks | planned | [UI](#evidence-ui) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 4.6 — Theme customisation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.6.C · Full section contract, including prose and nested example context (brief L1085). | partial | [UI](#evidence-ui) | System/Dark/Light/AMOLED/High Contrast, accents and reduced motion exist. Custom Dark and the remaining advanced appearance controls are absent. |
| **A4.6.L1088** Target options: System<br>**A4.6.L1089** Target options: Dark<br>**A4.6.L1090** Target options: AMOLED<br>**A4.6.L1091** Target options: Light<br>**A4.6.L1092** Target options: High Contrast<br>**A4.6.L1096** Accent choices: Blue | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A4.6.L1097** Accent choices: Cyan<br>**A4.6.L1098** Accent choices: Violet<br>**A4.6.L1099** Accent choices: Rose<br>**A4.6.L1100** Accent choices: Amber<br>**A4.6.L1101** Accent choices: Green<br>**A4.6.L1102** Accent choices: Monochrome | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A4.6.L1108** Additional settings: reduced motion,<br>**A4.6.L1113** Additional settings: compactness. | implemented/unverified | [UI](#evidence-ui) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A4.6.L1093** Target options: Custom Dark<br>**A4.6.L1105** Additional settings: corner radius,<br>**A4.6.L1106** Additional settings: blur/transparency,<br>**A4.6.L1107** Additional settings: animation intensity,<br>**A4.6.L1109** Additional settings: typography scale,<br>**A4.6.L1110** Additional settings: artwork prominence, | planned | [UI](#evidence-ui) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A4.6.L1111** Additional settings: navigation labels,<br>**A4.6.L1112** Additional settings: haptics, | planned | [UI](#evidence-ui) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 4.7 — Navigation philosophy

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A4.7.C · Full section contract, including prose and nested example context (brief L1115). | implemented/unverified | [UI](#evidence-ui) | Home/Library/Watch/Web/Orez primary navigation and retained Downloads/Settings/Reader/player routes exist; navigation smoke and accessibility remain unrun. |

### A 5 — SHARED CONTENT ORCHESTRATOR

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A5.C · Full section contract, including prose and nested example context (brief L1127). | partial | [RESOLVER](#evidence-resolver) | ContentType has IMAGE_CHAPTER/VIDEO_STREAM/GENERIC_WEB. Mode retention and scoped media headers exist, but no full typed content union/shared session/provider registry spans every subsystem. |
| **A5.L1158** Resolution should consider: URL pattern,<br>**A5.L1159** Resolution should consider: MIME,<br>**A5.L1160** Resolution should consider: HTTP metadata,<br>**A5.L1161** Resolution should consider: provider adapter,<br>**A5.L1162** Resolution should consider: page DOM,<br>**A5.L1163** Resolution should consider: media manifests, | partial | [RESOLVER](#evidence-resolver) | ContentType has IMAGE_CHAPTER/VIDEO_STREAM/GENERIC_WEB. Mode retention and scoped media headers exist, but no full typed content union/shared session/provider registry spans every subsystem. |
| **A5.L1164** Resolution should consider: browser-observed requests,<br>**A5.L1165** Resolution should consider: chapter signals,<br>**A5.L1166** Resolution should consider: session state.<br>**A5.X1** Typed resolved content: MangaChapter, MangaSeries, VideoPage, DirectVideo, AdaptiveStream, Image, Document, GenericWeb, DownloadableMedia; ResolutionResult preserves explicit user intent and shared session/source identity. | partial | [RESOLVER](#evidence-resolver) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 6 — READER & LIBRARY

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.C · Full section contract, including prose and nested example context (brief L1172). | implemented/unverified | [READER](#evidence-reader) | Image chapters, explicitly selected images, PDFs and ZIP/CBZ import have functioning source paths with bounded copying; actual import/render/restart acceptance is pending. |

### A 6.1 — Supported reading forms

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.1.C · Full section contract, including prose and nested example context (brief L1174). | implemented/unverified | [READER](#evidence-reader) | Image chapters, explicitly selected images, PDFs and ZIP/CBZ import have functioning source paths with bounded copying; actual import/render/restart acceptance is pending. |
| **A6.1.L1177** Target: manga,<br>**A6.1.L1178** Target: manhwa,<br>**A6.1.L1179** Target: manhua,<br>**A6.1.L1180** Target: webtoons,<br>**A6.1.L1181** Target: western comics,<br>**A6.1.L1182** Target: image chapters, | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.1.L1183** Target: PDF,<br>**A6.1.L1184** Target: ZIP,<br>**A6.1.L1185** Target: CBZ,<br>**A6.1.L1186** Target: local images,<br>**A6.1.L1187** Target: website-hosted chapters. | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 6.2 — Reading modes

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.2.C · Full section contract, including prose and nested example context (brief L1189). | partial | [READER](#evidence-reader) | Reader mode selector implements vertical/LTR/RTL with HorizontalPager; single/two-page/guided and continuous-horizontal special modes require explicit implementation/acceptance. |
| **A6.2.L1192** Implement and preserve: Vertical / webtoon<br>**A6.2.L1193** Implement and preserve: Paged LTR<br>**A6.2.L1194** Implement and preserve: Paged RTL | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.2.L1195** Implement and preserve: Single page<br>**A6.2.L1196** Implement and preserve: Continuous horizontal<br>**A6.2.L1197** Implement and preserve: Two-page landscape<br>**A6.2.L1198** Implement and preserve: future guided panel mode | planned | [READER](#evidence-reader) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 6.3 — Reader interactions

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.3.C · Full section contract, including prose and nested example context (brief L1200). | partial | [READER](#evidence-reader) | Pinch/pan/double tap, paging, auto-scroll/speed and HUD exist. Rotation lock, brightness, crop/margins, page spacing and control lock are not all implemented in the reader. |
| **A6.3.L1202** pinch zoom,<br>**A6.3.L1203** pan,<br>**A6.3.L1204** double-tap zoom,<br>**A6.3.L1206** swipe navigation,<br>**A6.3.L1207** auto-scroll,<br>**A6.3.L1208** speed control, | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.3.L1215** HUD show/hide. | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.3.L1205** tap zones,<br>**A6.3.L1209** rotation lock,<br>**A6.3.L1210** keep screen awake,<br>**A6.3.L1211** brightness,<br>**A6.3.L1212** page spacing,<br>**A6.3.L1213** margin crop, | planned | [READER](#evidence-reader) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A6.3.L1214** screen/control lock, | planned | [READER](#evidence-reader) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 6.4 — Large image handling

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.4.C · Full section contract, including prose and nested example context (brief L1219). | partial | [RESOURCES](#evidence-resources) | Progressive disk pages, Coil loading, bounded decode and memory callbacks exist. OCR tiling is not true region-tiled viewer decoding; large-chapter memory/performance evidence is missing. |
| **A6.4.L1224** Use: tiled decoding,<br>**A6.4.L1225** Use: bounded cache,<br>**A6.4.L1226** Use: prefetch,<br>**A6.4.L1227** Use: offscreen recycling,<br>**A6.4.L1228** Use: progressive page loading,<br>**A6.4.L1229** Use: memory-pressure callbacks. | partial | [RESOURCES](#evidence-resources) | Progressive disk pages, Coil loading, bounded decode and memory callbacks exist. OCR tiling is not true region-tiled viewer decoding; large-chapter memory/performance evidence is missing. |

### A 6.5 — Chapter extraction

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.5.C · Full section contract, including prose and nested example context (brief L1233). | partial | [ACQUISITION](#evidence-acquisition) | DOM containers, dimensions, lazy attributes and URL chrome filtering exist. Multi-signal confidence, unusual art and promo/footer recall need stronger held-out fixtures. |
| **A6.5.L1238** Signals: DOM ancestry,<br>**A6.5.L1239** Signals: known reader containers,<br>**A6.5.L1240** Signals: image dimensions,<br>**A6.5.L1241** Signals: aspect ratio,<br>**A6.5.L1242** Signals: position,<br>**A6.5.L1243** Signals: filenames, | partial | [ACQUISITION](#evidence-acquisition) | DOM containers, dimensions, lazy attributes and URL chrome filtering exist. Multi-signal confidence, unusual art and promo/footer recall need stronger held-out fixtures. |
| **A6.5.L1244** Signals: CSS classes,<br>**A6.5.L1245** Signals: lazy attributes,<br>**A6.5.L1246** Signals: sequential similarity,<br>**A6.5.L1247** Signals: repeated assets,<br>**A6.5.L1248** Signals: ad/banner/logo signals,<br>**A6.5.L1249** Signals: viewport behaviour. | partial | [ACQUISITION](#evidence-acquisition) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A6.5.L1252** Filter likely: logos,<br>**A6.5.L1253** Filter likely: favicons,<br>**A6.5.L1254** Filter likely: icons,<br>**A6.5.L1255** Filter likely: avatars,<br>**A6.5.L1256** Filter likely: banners,<br>**A6.5.L1257** Filter likely: ads, | partial | [ACQUISITION](#evidence-acquisition) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A6.5.L1258** Filter likely: social promos,<br>**A6.5.L1259** Filter likely: cookie graphics,<br>**A6.5.L1260** Filter likely: challenge/captcha assets,<br>**A6.5.L1261** Filter likely: navigation art,<br>**A6.5.L1262** Filter likely: recommendations. | partial | [ACQUISITION](#evidence-acquisition) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 6.6 — Lazy-loaded chapters

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.6.C · Full section contract, including prose and nested example context (brief L1266). | partial | [ACQUISITION](#evidence-acquisition) | Rendered discovery has a bounded WebView pass and bottom-scroll acquisition; stable multi-pass mutation-aware lazy chapter collection is incomplete. |
| **A6.6.L1271** A hidden/embedded browser can: load,<br>**A6.6.L1272** A hidden/embedded browser can: wait,<br>**A6.6.L1273** A hidden/embedded browser can: collect reader assets,<br>**A6.6.L1274** A hidden/embedded browser can: scroll,<br>**A6.6.L1275** A hidden/embedded browser can: wait for mutations,<br>**A6.6.L1276** A hidden/embedded browser can: collect again, | partial | [ACQUISITION](#evidence-acquisition) | Rendered discovery has a bounded WebView pass and bottom-scroll acquisition; stable multi-pass mutation-aware lazy chapter collection is incomplete. |
| **A6.6.L1277** A hidden/embedded browser can: stop when stable or bounded maximum reached. | partial | [ACQUISITION](#evidence-acquisition) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 6.7 — Session-aware page downloading

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.7.C · Full section contract, including prose and nested example context (brief L1279). | implemented/unverified | [ACQUISITION](#evidence-acquisition) | Per-page CookieManager headers, referrer, bounded transfer and page-error isolation exist; verify session redirects, retry and valid-page retention on emulator. |
| **A6.7.L1282** Chapter image downloads should reuse: Cookie,<br>**A6.7.L1283** Chapter image downloads should reuse: User-Agent,<br>**A6.7.L1284** Chapter image downloads should reuse: Referer,<br>**A6.7.L1285** Chapter image downloads should reuse: source-page context | implemented/unverified | [ACQUISITION](#evidence-acquisition) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 6.8 — Library

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A6.8.C · Full section contract, including prose and nested example context (brief L1293). | partial | [LIBRARY](#evidence-library) | Atomic SavedChapter manifests persist titles/sources/pages/positions/offsets/bookmarks and three statuses. Series/cover/collections/glossary/notes and two requested statuses need schema/UI work. |
| **A6.8.L1296** Persist: series,<br>**A6.8.L1298** Persist: cover,<br>**A6.8.L1306** Persist: translation metadata,<br>**A6.8.L1307** Persist: glossary identity,<br>**A6.8.L1308** Persist: notes,<br>**A6.8.L1313** Statuses: Plan to Read | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A6.8.L1316** Statuses: Dropped | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A6.8.L1297** Persist: chapter,<br>**A6.8.L1299** Persist: source,<br>**A6.8.L1300** Persist: pages,<br>**A6.8.L1301** Persist: reading position,<br>**A6.8.L1302** Persist: scroll offset,<br>**A6.8.L1303** Persist: bookmarks, | implemented/unverified | [LIBRARY](#evidence-library) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.8.L1305** Persist: last read,<br>**A6.8.L1309** Persist: local/offline availability.<br>**A6.8.L1312** Statuses: Reading<br>**A6.8.L1314** Statuses: Completed<br>**A6.8.L1315** Statuses: On Hold | implemented/unverified | [LIBRARY](#evidence-library) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A6.8.L1304** Persist: status, | partial | [LIBRARY](#evidence-library) | Atomic SavedChapter manifests persist titles/sources/pages/positions/offsets/bookmarks and three statuses. Series/cover/collections/glossary/notes and two requested statuses need schema/UI work. |

### A 7 — MANGALENS VISION ENGINE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.C · Full section contract, including prose and nested example context (brief L1322). | partial | [OCR](#evidence-ocr) | Five-script OCR, plausibility scoring, tile deduplication and page-space balloon merging exist. AUTO selects a whole-tile recognizer winner rather than region-level fusion. |

### A 7.1 — OCR scripts/languages

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.1.C · Full section contract, including prose and nested example context (brief L1334). | implemented/unverified | [OCR](#evidence-ocr) | ML Kit Latin/Devanagari/Japanese/Korean/Chinese recognizers are configured; multilingual bitmap tests exist but have not been executed for this candidate. |
| **A7.1.L1337** At minimum: Latin<br>**A7.1.L1338** At minimum: Devanagari<br>**A7.1.L1339** At minimum: Japanese<br>**A7.1.L1340** At minimum: Korean<br>**A7.1.L1341** At minimum: Chinese | implemented/unverified | [OCR](#evidence-ocr) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 7.2 — Multi-recognizer fusion

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.2.C · Full section contract, including prose and nested example context (brief L1345). | partial | [OCR](#evidence-ocr) | Five-script OCR, plausibility scoring, tile deduplication and page-space balloon merging exist. AUTO selects a whole-tile recognizer winner rather than region-level fusion. |
| **A7.2.L1348** For AUTO mode: run appropriate candidate recognizers,<br>**A7.2.L1349** For AUTO mode: compare recognition confidence,<br>**A7.2.L1352** For AUTO mode: remove duplicates,<br>**A7.2.L1353** For AUTO mode: merge likely same-bubble blocks,<br>**A7.2.L1354** For AUTO mode: reject obvious hallucinated glyphs. | implemented/unverified | [OCR](#evidence-ocr) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A7.2.L1350** For AUTO mode: compare script plausibility,<br>**A7.2.L1351** For AUTO mode: compare geometry, | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 7.3 — Adaptive high-resolution retry

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.3.C · Full section contract, including prose and nested example context (brief L1358). | partial | [OCR](#evidence-ocr) | Retry currently scales a whole bounded tile and maps coordinates back; crop-only low-confidence region selection and tool-specific retry are incomplete. |
| **A7.3.L1361** If OCR confidence is weak: crop the region,<br>**A7.3.L1362** If OCR confidence is weak: upscale only that region,<br>**A7.3.L1363** If OCR confidence is weak: re-run one or more recognizers, | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A7.3.L1364** If OCR confidence is weak: map geometry back. | implemented/unverified | [OCR](#evidence-ocr) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 7.4 — Reading order

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.4.C · Full section contract, including prose and nested example context (brief L1368). | partial | [OCR](#evidence-ocr) | Default order is top then left; webtoon reading works in principle. Panel-aware RTL and Japanese vertical ordering are not implemented as dedicated algorithms. |
| **A7.4.L1371** Reading-order algorithms should be aware of: western horizontal dialogue, | partial | [OCR](#evidence-ocr) | Default order is top then left; webtoon reading works in principle. Panel-aware RTL and Japanese vertical ordering are not implemented as dedicated algorithms. |
| **A7.4.L1372** Reading-order algorithms should be aware of: Japanese manga right-to-left,<br>**A7.4.L1373** Reading-order algorithms should be aware of: vertical Japanese,<br>**A7.4.L1375** Reading-order algorithms should be aware of: panel boundaries. | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A7.4.L1374** Reading-order algorithms should be aware of: webtoon top-to-bottom, | implemented/unverified | [OCR](#evidence-ocr) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 7.5 — Japanese vertical text

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.5.C · Full section contract, including prose and nested example context (brief L1377). | planned | [OCR](#evidence-ocr) | Geometric balloon merging is present, but dedicated vertical-text/furigana semantics, bubble/tail/type segmentation, SFX options and diagnostic UI were not located. |
| **A7.5.L1382** Detect: orientation,<br>**A7.5.L1383** Detect: column order,<br>**A7.5.L1384** Detect: punctuation direction,<br>**A7.5.L1385** Detect: furigana-like small text,<br>**A7.5.L1386** Detect: mixed horizontal/vertical regions. | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 7.6 — Speech bubble segmentation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.6.C · Full section contract, including prose and nested example context (brief L1388). | planned | [OCR](#evidence-ocr) | Geometric balloon merging is present, but dedicated vertical-text/furigana semantics, bubble/tail/type segmentation, SFX options and diagnostic UI were not located. |
| **A7.6.L1391** Detect: bubble interior,<br>**A7.6.L1392** Detect: boundary,<br>**A7.6.L1393** Detect: tail if possible,<br>**A7.6.L1394** Detect: caption boxes,<br>**A7.6.L1395** Detect: thought bubbles,<br>**A7.6.L1396** Detect: dialogue, | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A7.6.L1397** Detect: narration,<br>**A7.6.L1398** Detect: SFX. | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 7.7 — SFX

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.7.C · Full section contract, including prose and nested example context (brief L1402). | planned | [OCR](#evidence-ocr) | Geometric balloon merging is present, but dedicated vertical-text/furigana semantics, bubble/tail/type segmentation, SFX options and diagnostic UI were not located. |
| **A7.7.L1407** User options: keep original,<br>**A7.7.L1408** User options: translate alongside,<br>**A7.7.L1409** User options: replace,<br>**A7.7.L1410** User options: annotate. | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 7.8 — OCR diagnostics

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A7.8.C · Full section contract, including prose and nested example context (brief L1412). | planned | [OCR](#evidence-ocr) | Geometric balloon merging is present, but dedicated vertical-text/furigana semantics, bubble/tail/type segmentation, SFX options and diagnostic UI were not located. |
| **A7.8.L1415** Developer mode can render: region boxes,<br>**A7.8.L1416** Developer mode can render: recognizer source,<br>**A7.8.L1417** Developer mode can render: confidence,<br>**A7.8.L1418** Developer mode can render: script,<br>**A7.8.L1419** Developer mode can render: reading order,<br>**A7.8.L1420** Developer mode can render: bubble grouping, | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A7.8.L1421** Developer mode can render: rejected regions. | planned | [OCR](#evidence-ocr) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 8 — LANGUAGE & TRANSLATION ENGINE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.C · Full section contract, including prose and nested example context (brief L1427). | partial | [TRANSLATION](#evidence-translation) | ML Kit drafts, bounded prior translated context, optional local refinement and style-scoped translation cache exist; speaker/series/glossary/RAG semantic context is incomplete. |

### A 8.1 — Meaning-aware translation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.1.C · Full section contract, including prose and nested example context (brief L1429). | partial | [TRANSLATION](#evidence-translation) | ML Kit drafts, bounded prior translated context, optional local refinement and style-scoped translation cache exist; speaker/series/glossary/RAG semantic context is incomplete. |
| **A8.1.L1432** Translation should use: local bubble context,<br>**A8.1.L1433** Translation should use: neighbouring bubbles,<br>**A8.1.L1434** Translation should use: previous page,<br>**A8.1.L1435** Translation should use: chapter context,<br>**A8.1.L1438** Translation should use: tone, | partial | [TRANSLATION](#evidence-translation) | ML Kit drafts, bounded prior translated context, optional local refinement and style-scoped translation cache exist; speaker/series/glossary/RAG semantic context is incomplete. |
| **A8.1.L1436** Translation should use: series glossary,<br>**A8.1.L1437** Translation should use: character relationships,<br>**A8.1.L1439** Translation should use: honorifics,<br>**A8.1.L1440** Translation should use: terminology. | planned | [TRANSLATION](#evidence-translation) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 8.2 — Translation styles

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.2.C · Full section contract, including prose and nested example context (brief L1444). | partial | [TRANSLATION](#evidence-translation) | Natural/Faithful/Casual/Formal/Webtoon/Custom profiles exist. Manga/Literal and persisted per-series custom rules are incomplete. |
| **A8.2.L1447** Profiles: Natural<br>**A8.2.L1448** Profiles: Faithful<br>**A8.2.L1449** Profiles: Casual<br>**A8.2.L1450** Profiles: Formal<br>**A8.2.L1452** Profiles: Webtoon<br>**A8.2.L1454** Profiles: Custom | implemented/unverified | [TRANSLATION](#evidence-translation) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A8.2.L1451** Profiles: Manga<br>**A8.2.L1453** Profiles: Literal | planned | [TRANSLATION](#evidence-translation) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 8.3 — Translation memory

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.3.C · Full section contract, including prose and nested example context (brief L1461). | planned | [MEMORY](#evidence-memory) | Style/scope translation memoization is not a series terminology store; durable names/aliases/honorific choices and automatic relevant retrieval need implementation. |
| **A8.3.L1464** Store series-specific: names,<br>**A8.3.L1465** Store series-specific: aliases,<br>**A8.3.L1466** Store series-specific: places,<br>**A8.3.L1467** Store series-specific: powers,<br>**A8.3.L1468** Store series-specific: organisations,<br>**A8.3.L1469** Store series-specific: attack names, | planned | [MEMORY](#evidence-memory) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A8.3.L1470** Store series-specific: honorific choices,<br>**A8.3.L1471** Store series-specific: preferred spellings,<br>**A8.3.L1472** Store series-specific: recurring phrases. | planned | [MEMORY](#evidence-memory) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 8.4 — Multi-candidate translation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.4.C · Full section contract, including prose and nested example context (brief L1476). | partial | [TRANSLATION](#evidence-translation) | Draft/refined candidates pass deterministic language/length/leakage gates. Independent semantic candidate ranking and measured selection quality are absent. |
| **A8.4.L1479** Difficult regions may use: fast deterministic/ML draft,<br>**A8.4.L1480** Difficult regions may use: local LLM candidate,<br>**A8.4.L1481** Difficult regions may use: Orez contextual rewrite,<br>**A8.4.L1482** Difficult regions may use: quality evaluator. | implemented/unverified | [TRANSLATION](#evidence-translation) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 8.5 — Quality gates

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.5.C · Full section contract, including prose and nested example context (brief L1486). | partial | [TRANSLATION](#evidence-translation) | Script, leakage, unchanged/empty output, loop, length and punctuation checks exist. Hindi register rules are lexical heuristics; semantic terminology/register/nonsense validation needs held-out evaluation. |
| **A8.5.L1489** Reject or retry translations with: wrong target script,<br>**A8.5.L1490** Reject or retry translations with: excessive source leakage,<br>**A8.5.L1491** Reject or retry translations with: unchanged source when translation expected,<br>**A8.5.L1492** Reject or retry translations with: runaway length,<br>**A8.5.L1493** Reject or retry translations with: repeated loops,<br>**A8.5.L1494** Reject or retry translations with: empty output, | implemented/unverified | [TRANSLATION](#evidence-translation) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A8.5.L1495** Reject or retry translations with: nonsense fragments,<br>**A8.5.L1497** Reject or retry translations with: accidental politeness/register distortion where context disagrees.<br>**A8.5.L1500** For Hindi, preserve social register intelligently: तुम,<br>**A8.5.L1501** For Hindi, preserve social register intelligently: तू,<br>**A8.5.L1502** For Hindi, preserve social register intelligently: आप should be contextual choices, not arbitrary defaults. | partial | [TRANSLATION](#evidence-translation) | Script, leakage, unchanged/empty output, loop, length and punctuation checks exist. Hindi register rules are lexical heuristics; semantic terminology/register/nonsense validation needs held-out evaluation. |
| **A8.5.L1496** Reject or retry translations with: terminology violation, | planned | [TRANSLATION](#evidence-translation) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 8.6 — Context packet

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A8.6.C · Full section contract, including prose and nested example context (brief L1505). | partial | [TRANSLATION](#evidence-translation) | Refinement prompt includes target/style/source/draft/context/glossary parameters; runtime does not populate a full series/chapter/page/region/speaker/neighbor structured context packet. |
| **A8.6.X1** Structured translation context: series, chapter, page, region, source_language, target_language, speaker_hint, previous_dialogue, next_dialogue_hint, glossary, style and source_text; custom instructions persist per series. | partial | [TRANSLATION](#evidence-translation) | Refinement prompt includes target/style/source/draft/context/glossary parameters; runtime does not populate a full series/chapter/page/region/speaker/neighbor structured context packet. |

### A 9 — ARTWORK RECONSTRUCTION AND LETTERING

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.C · Full section contract, including prose and nested example context (brief L1530). | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |

### A 9.1 — Do not cover text with crude rectangles

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.1.C · Full section contract, including prose and nested example context (brief L1532). | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |
| **A9.1.L1537** Steps: identify glyph mask,<br>**A9.1.L1538** Steps: estimate bubble/art background,<br>**A9.1.L1539** Steps: erase original glyphs,<br>**A9.1.L1540** Steps: reconstruct surface,<br>**A9.1.L1542** Steps: typeset translated text. | implemented/unverified | [LETTERING](#evidence-lettering) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A9.1.L1541** Steps: determine available shape, | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |

### A 9.2 — Reconstruction methods

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.2.C · Full section contract, including prose and nested example context (brief L1544). | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |
| **A9.2.L1547** Use a hierarchy: surrounding color sample,<br>**A9.2.L1548** Use a hierarchy: multi-directional interpolation,<br>**A9.2.L1549** Use a hierarchy: gradient continuation,<br>**A9.2.L1551** Use a hierarchy: neighbouring surface reconstruction, | implemented/unverified | [LETTERING](#evidence-lettering) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A9.2.L1550** Use a hierarchy: texture synthesis,<br>**A9.2.L1552** Use a hierarchy: optional vision/inpainting model for difficult artwork. | planned | [LETTERING](#evidence-lettering) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 9.3 — Progressive cleaning

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.3.C · Full section contract, including prose and nested example context (brief L1554). | implemented/unverified | [LETTERING](#evidence-lettering) | ViewModel reconstruction and two-pass renderer progressively clean surfaces before lettering; overlapping-region artwork fixtures must execute. |

### A 9.4 — Typesetting

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.4.C · Full section contract, including prose and nested example context (brief L1558). | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |
| **A9.4.L1561** Infer: text color,<br>**A9.4.L1562** Infer: font size,<br>**A9.4.L1563** Infer: weight,<br>**A9.4.L1564** Infer: alignment,<br>**A9.4.L1565** Infer: line spacing,<br>**A9.4.L1567** Infer: max width, | implemented/unverified | [LETTERING](#evidence-lettering) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A9.4.L1573** Use text measurement to: choose font size,<br>**A9.4.L1574** Use text measurement to: wrap, | implemented/unverified | [LETTERING](#evidence-lettering) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A9.4.L1566** Infer: orientation,<br>**A9.4.L1568** Infer: bubble shape.<br>**A9.4.L1576** Use text measurement to: choose alternate wording if required. | planned | [LETTERING](#evidence-lettering) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A9.4.L1575** Use text measurement to: expand within safe bubble boundaries, | partial | [LETTERING](#evidence-lettering) | Glyph-oriented masking, sampled/interpolated surfaces, inferred style and fitted StaticLayout exist. Difficult-artwork texture/inpainting, true bubble shape/orientation and semantic alternate wording remain incomplete. |

### A 9.5 — Compare modes

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.5.C · Full section contract, including prose and nested example context (brief L1578). | partial | [READER](#evidence-reader) | Original/Translated toggle and scalable lettering exist; side-by-side, split slider and hold-to-peek comparison modes were not located. |
| **A9.5.L1581** Reader should support: Original<br>**A9.5.L1582** Reader should support: Translated | implemented/unverified | [READER](#evidence-reader) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A9.5.L1583** Reader should support: Side-by-side<br>**A9.5.L1584** Reader should support: Split slider<br>**A9.5.L1585** Reader should support: Hold to peek original | planned | [READER](#evidence-reader) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 9.6 — Manual correction

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A9.6.C · Full section contract, including prose and nested example context (brief L1587). | planned | [TRANSLATION](#evidence-translation) | Long press starts page translation, but no bubble OCR/translation editor with source crop, alternatives, glossary and personal correction persistence was located. |
| **A9.6.L1590** Tap a bubble: original OCR,<br>**A9.6.L1591** Tap a bubble: source crop,<br>**A9.6.L1592** Tap a bubble: current translation,<br>**A9.6.L1593** Tap a bubble: alternatives,<br>**A9.6.L1594** Tap a bubble: edit OCR,<br>**A9.6.L1595** Tap a bubble: edit translation, | planned | [TRANSLATION](#evidence-translation) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A9.6.L1596** Tap a bubble: regenerate,<br>**A9.6.L1597** Tap a bubble: ask Orez,<br>**A9.6.L1598** Tap a bubble: add glossary term. | planned | [TRANSLATION](#evidence-translation) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10 — OREZ AI: THE MOST IMPORTANT FUTURE SUBSYSTEM

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.C · Full section contract, including prose and nested example context (brief L1604). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.L1617** Orez consists of: planner,<br>**A10.L1618** Orez consists of: model router,<br>**A10.L1619** Orez consists of: tool runtime,<br>**A10.L1620** Orez consists of: task executor,<br>**A10.L1621** Orez consists of: policy engine,<br>**A10.L1622** Orez consists of: memory, | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.L1623** Orez consists of: retrieval,<br>**A10.L1624** Orez consists of: web research,<br>**A10.L1625** Orez consists of: vision,<br>**A10.L1626** Orez consists of: speech,<br>**A10.L1627** Orez consists of: evaluator/critic,<br>**A10.L1628** Orez consists of: scheduler, | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A10.L1629** Orez consists of: event bus,<br>**A10.L1630** Orez consists of: persistent task state,<br>**A10.L1631** Orez consists of: audit log,<br>**A10.L1632** Orez consists of: developer diagnostics. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.1 — Orez product examples

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.1.C · Full section contract, including prose and nested example context (brief L1634). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.1.X1** Translate a whole chapter into natural Hindi; keep honorifics; repair awkward bubbles.<br>**A10.1.X2** Find next unread chapter, open it, and prepare an offline translated copy.<br>**A10.1.X3** Correct a subtitle using previous conversation context.<br>**A10.1.X4** Download highest accessible quality with audio.<br>**A10.1.X5** Diagnose failed download and retry a justified alternative source.<br>**A10.1.X6** Find chapter 54 on the active site. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.1.X7** Explain the current panel without spoilers after the current chapter.<br>**A10.1.X8** Persist preferred character spelling for this series.<br>**A10.1.X9** Generate English subtitles for the whole video.<br>**A10.1.X10** Research and cite the meaning of a historical term in current context.<br>**A10.1.X11** Search saved OCR/dialogue for the first occurrence of a technique. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.2 — Orez agent loop

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.2.C · Full section contract, including prose and nested example context (brief L1662). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.2.L1666** Conceptual execution: Parse user intent.<br>**A10.2.L1667** Conceptual execution: Read current MangaLens context.<br>**A10.2.L1668** Conceptual execution: Determine risk/permissions.<br>**A10.2.L1669** Conceptual execution: Create structured plan.<br>**A10.2.L1670** Conceptual execution: Select model(s).<br>**A10.2.L1671** Conceptual execution: Select tool(s). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.2.L1672** Conceptual execution: Execute one step.<br>**A10.2.L1673** Conceptual execution: Validate tool result.<br>**A10.2.L1674** Conceptual execution: Update task state.<br>**A10.2.L1675** Conceptual execution: Recover/replan on failure.<br>**A10.2.L1676** Conceptual execution: Continue until completion/cancellation.<br>**A10.2.L1677** Conceptual execution: Produce concise user result and action log. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.3 — Structured task state

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.3.C · Full section contract, including prose and nested example context (brief L1714). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.3.L1719** Persist fields such as: task id,<br>**A10.3.L1720** Persist fields such as: user objective,<br>**A10.3.L1721** Persist fields such as: current step,<br>**A10.3.L1722** Persist fields such as: completed steps,<br>**A10.3.L1723** Persist fields such as: active chapter,<br>**A10.3.L1724** Persist fields such as: page index, | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.3.L1725** Persist fields such as: source URL,<br>**A10.3.L1726** Persist fields such as: media candidate,<br>**A10.3.L1727** Persist fields such as: translation target,<br>**A10.3.L1728** Persist fields such as: model,<br>**A10.3.L1729** Persist fields such as: retries,<br>**A10.3.L1730** Persist fields such as: failures, | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A10.3.L1731** Persist fields such as: output files,<br>**A10.3.L1732** Persist fields such as: approvals,<br>**A10.3.L1733** Persist fields such as: checkpoints.<br>**A10.3.X1** Versioned durable plan/DAG with dependencies/preconditions, stable result identities, partial invalidation, retries and crash-safe replay across all relevant tool types; navigation is a handoff, not task completion. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.4 — Orez tools

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.4.C · Full section contract, including prose and nested example context (brief L1739). | planned | [AGENT](#evidence-agent) | Tool examples are target contracts, not an implemented catalog. Current registry has navigation/translation handoffs and durable enqueue_download only; verify real completion providers when adding tools. |
| **A10.4.L1746** Reader tools: openChapter<br>**A10.4.L1747** Reader tools: openSavedChapter<br>**A10.4.L1748** Reader tools: findNextChapter<br>**A10.4.L1749** Reader tools: changeReaderMode<br>**A10.4.L1750** Reader tools: bookmark<br>**A10.4.L1751** Reader tools: savePosition | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1752** Reader tools: importChapter<br>**A10.4.L1755** Vision tools: inspectPage<br>**A10.4.L1756** Vision tools: runOcr<br>**A10.4.L1757** Vision tools: retryOcrRegion<br>**A10.4.L1758** Vision tools: detectBubble<br>**A10.4.L1759** Vision tools: reconstructRegion | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1760** Vision tools: compareOriginal<br>**A10.4.L1763** Translation tools: translateBubble<br>**A10.4.L1764** Translation tools: translatePage<br>**A10.4.L1765** Translation tools: translateChapter<br>**A10.4.L1766** Translation tools: updateGlossary<br>**A10.4.L1767** Translation tools: regenerateTranslation | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1768** Translation tools: evaluateTranslation<br>**A10.4.L1771** Media tools: resolveMedia<br>**A10.4.L1772** Media tools: playMedia<br>**A10.4.L1773** Media tools: changeQuality<br>**A10.4.L1774** Media tools: inspectTracks<br>**A10.4.L1775** Media tools: selectSubtitle | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1776** Media tools: createSubtitles<br>**A10.4.L1780** Download tools: pauseDownload<br>**A10.4.L1781** Download tools: resumeDownload<br>**A10.4.L1782** Download tools: retryDownload<br>**A10.4.L1783** Download tools: resolveExpiredSource<br>**A10.4.L1784** Download tools: verifyMedia | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1787** Web tools: openUrl<br>**A10.4.L1788** Web tools: navigate<br>**A10.4.L1789** Web tools: findText<br>**A10.4.L1790** Web tools: inspectDom<br>**A10.4.L1791** Web tools: extractLinks<br>**A10.4.L1792** Web tools: searchWeb | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1793** Web tools: openDetectedChapter<br>**A10.4.L1794** Web tools: openDetectedMedia<br>**A10.4.L1797** Library tools: searchLibrary<br>**A10.4.L1798** Library tools: searchOcrText<br>**A10.4.L1799** Library tools: getSeriesMemory<br>**A10.4.L1800** Library tools: updateSeriesMemory | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1801** Library tools: listRecent<br>**A10.4.L1804** App tools: inspectDiagnostics<br>**A10.4.L1805** App tools: clearSafeCache<br>**A10.4.L1806** App tools: checkModel<br>**A10.4.L1807** App tools: installModelPack<br>**A10.4.L1808** App tools: changeSetting | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.4.L1779** Download tools: enqueueDownload | implemented/unverified | [AGENT](#evidence-agent) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 10.5 — Tool contract

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.5.C · Full section contract, including prose and nested example context (brief L1812). | implemented/unverified | [AGENT](#evidence-agent) | Trusted registry owns capabilities/routes/risks and validates URL/quality/language arguments; policy blocks untrusted mutation and requires approval for high-risk categories. No arbitrary model shell/file/network tool exists. |

### A 10.6 — Autonomous execution

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.6.C · Full section contract, including prose and nested example context (brief L1839). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.6.L1850** It should: plan,<br>**A10.6.L1851** It should: process,<br>**A10.6.L1852** It should: checkpoint,<br>**A10.6.L1853** It should: retry,<br>**A10.6.L1854** It should: report only meaningful blocks or completion. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.7 — Orez model hierarchy

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.7.C · Full section contract, including prose and nested example context (brief L1860). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.7.L1868** Lite / Jobs: routing,<br>**A10.7.L1869** Lite / Jobs: classification,<br>**A10.7.L1870** Lite / Jobs: short rewriting,<br>**A10.7.L1871** Lite / Jobs: basic Orez chat,<br>**A10.7.L1872** Lite / Jobs: simple translation correction,<br>**A10.7.L1873** Lite / Jobs: low-cost tool selection. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.7.L1879** Core / Jobs: contextual translation,<br>**A10.7.L1880** Core / Jobs: dialogue editing,<br>**A10.7.L1881** Core / Jobs: tool planning,<br>**A10.7.L1882** Core / Jobs: chapter reasoning,<br>**A10.7.L1883** Core / Jobs: subtitle cleanup,<br>**A10.7.L1884** Core / Jobs: richer Orez conversation. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A10.7.L1890** Max / Jobs: hard reasoning,<br>**A10.7.L1891** Max / Jobs: long-context chapter assistance,<br>**A10.7.L1892** Max / Jobs: difficult translation,<br>**A10.7.L1893** Max / Jobs: complex tool planning,<br>**A10.7.L1894** Max / Jobs: richer multimodal coordination.<br>**A10.7.X1** Max is optional for capable phones; Lite/Core/Max model quality and domain routing must be measured on common held-out tasks and actual resource budgets. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.8 — Specialised models

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.8.C · Full section contract, including prose and nested example context (brief L1898). | partial | [MODELS](#evidence-models) | OCR, ML Kit translation, llama.cpp LLM and Whisper ASR are integrated; no separate evaluated vision encoder, embeddings/reranker or neural inpainting provider was located. |
| **A10.8.L1901** Potential components: OCR models,<br>**A10.8.L1903** Potential components: speech-to-text model,<br>**A10.8.L1904** Potential components: language model,<br>**A10.8.L1908** Potential components: voice activity detection. | implemented/unverified | [MODELS](#evidence-models) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A10.8.L1902** Potential components: vision encoder,<br>**A10.8.L1905** Potential components: embedding model,<br>**A10.8.L1906** Potential components: optional inpainting model,<br>**A10.8.L1907** Potential components: optional reranker, | planned | [MODELS](#evidence-models) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.9 — Model router

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.9.C · Full section contract, including prose and nested example context (brief L1912). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.9.L1917** Examples: "change reader to RTL" -> no LLM or Lite.<br>**A10.9.L1918** Examples: "translate simple English bubble" -> translation model + quality policy.<br>**A10.9.L1919** Examples: "translate nuanced Japanese sarcasm with prior context" -> Core/Max.<br>**A10.9.L1920** Examples: "find current information online" -> research subsystem.<br>**A10.9.L1921** Examples: "transcribe two-hour video" -> speech model pipeline, not chatbot. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.10 — Hardware-aware runtime

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.10.C · Full section contract, including prose and nested example context (brief L1923). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.10.L1926** Detect: RAM,<br>**A10.10.L1927** Detect: available RAM,<br>**A10.10.L1928** Detect: storage,<br>**A10.10.L1929** Detect: architecture,<br>**A10.10.X1** Select based on both total/available RAM, storage, ABI, CPU/GPU/NPU capabilities, thermal/battery/load and sustained duration; Fast/Balanced/Maximum must report honest resource/latency/quality tradeoffs. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.10.L1930** Detect: CPU capability,<br>**A10.10.L1931** Detect: GPU/NPU capabilities when available,<br>**A10.10.L1932** Detect: thermal state,<br>**A10.10.L1933** Detect: battery state.<br>**A10.10.L1936** Modes: Fast<br>**A10.10.L1937** Modes: Balanced | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.10.L1938** Modes: Maximum | planned | [AGENT](#evidence-agent) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.11 — Model Manager

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.11.C · Full section contract, including prose and nested example context (brief L1943). | partial | [MODELS](#evidence-models) | Lite/Core catalog, download/pause/resume, disk headroom, expected size/SHA-256, atomic promotion and memory-based fallback exist. Signed manifests, removal/update/rollback, ABI/min-app compatibility and empirical routing remain incomplete. |
| **A10.11.L1948** Responsibilities: catalog,<br>**A10.11.L1949** Responsibilities: download,<br>**A10.11.L1950** Responsibilities: pause/resume,<br>**A10.11.L1951** Responsibilities: checksum,<br>**A10.11.L1953** Responsibilities: install,<br>**A10.11.L1957** Responsibilities: disk-space check, | implemented/unverified | [MODELS](#evidence-models) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A10.11.L1959** Responsibilities: model version display. | planned | [MODELS](#evidence-models) | The raw GGUF catalog has IDs/labels and pinned download revisions; no explicit installed semantic pack-version UI/manifest was located. |
| **A10.11.L1952** Responsibilities: signature/manifest verification,<br>**A10.11.L1954** Responsibilities: remove,<br>**A10.11.L1955** Responsibilities: update,<br>**A10.11.L1956** Responsibilities: rollback,<br>**A10.11.L1958** Responsibilities: compatibility check, | planned | [MODELS](#evidence-models) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.12 — Model-pack format

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.12.C · Full section contract, including prose and nested example context (brief L1965). | planned | [MODELS](#evidence-models) | Catalog currently distributes raw GGUF files. Versioned multi-component manifests, shards/tokenizers/templates/licenses/signatures and pack compatibility/rollback are target work. |
| **A10.12.X1** Model pack contains manifest, binary shards, tokenizer, templates, licenses, hash inventory and signature. Manifest carries pack_id/version/min_app_version/architecture/required_ram_mb/disk_bytes/components. | planned | [MODELS](#evidence-models) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.13 — Manual pack fallback

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.13.C · Full section contract, including prose and nested example context (brief L2000). | partial | [MODELS](#evidence-models) | Advanced structured conversation/resource and Whisper imports exist; verified all-component recovery pack import is incomplete and must remain secondary to managed delivery. |
| **A10.13.L2005** If automatic delivery becomes impossible: user downloads one external pack,<br>**A10.13.L2006** If automatic delivery becomes impossible: MangaLens verifies manifest/hashes,<br>**A10.13.L2007** If automatic delivery becomes impossible: extracts/install automatically. | partial | [MODELS](#evidence-models) | Advanced structured conversation/resource and Whisper imports exist; verified all-component recovery pack import is incomplete and must remain secondary to managed delivery. |

### A 10.14 — Internet Research Engine

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.14.C · Full section contract, including prose and nested example context (brief L2013). | partial | [RESEARCH](#evidence-research) | Bounded web search/result extraction and source links exist; provider-neutral registry, explicit freshness/source-quality evaluation and durable sourced research tasks are incomplete. |
| **A10.14.L2018** Capabilities: search,<br>**A10.14.L2019** Capabilities: open result,<br>**A10.14.L2020** Capabilities: extract visible/relevant content,<br>**A10.14.L2023** Capabilities: cite/source results internally and to user where applicable,<br>**A10.14.L2024** Capabilities: ignore navigation/login/boilerplate,<br>**A10.14.L2025** Capabilities: time out, | implemented/unverified | [RESEARCH](#evidence-research) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A10.14.L2026** Capabilities: cancel, | implemented/unverified | [RESEARCH](#evidence-research) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A10.14.L2021** Capabilities: compare sources,<br>**A10.14.L2022** Capabilities: rank source quality,<br>**A10.14.L2027** Capabilities: retry other sources. | partial | [RESEARCH](#evidence-research) | Bounded web search/result extraction and source links exist; provider-neutral registry, explicit freshness/source-quality evaluation and durable sourced research tasks are incomplete. |

### A 10.15 — Web research security

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.15.C · Full section contract, including prose and nested example context (brief L2035). | implemented/unverified | [SECURITY](#evidence-security) | Web excerpts are labeled untrusted; chat role delimiters are escaped and runtime tool metadata is trusted. Execute hostile-page/tool-effect tests in addition to unit policy checks. |

### A 10.16 — Browser agent

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.16.C · Full section contract, including prose and nested example context (brief L2057). | planned | [WEB](#evidence-web) | Native WebView interactions exist; Orez has no typed inspect/click/fill/find DOM API, element identity/preconditions or browser-task completion provider. |
| **A10.16.L2062** It can: inspect DOM,<br>**A10.16.L2063** It can: click identified element,<br>**A10.16.L2064** It can: open link,<br>**A10.16.L2065** It can: fill non-sensitive text,<br>**A10.16.L2066** It can: navigate,<br>**A10.16.L2067** It can: find media, | planned | [WEB](#evidence-web) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.16.L2068** It can: find chapter links.<br>**A10.16.L2071** For sensitive actions: account changes,<br>**A10.16.L2072** For sensitive actions: purchases,<br>**A10.16.L2073** For sensitive actions: sending messages,<br>**A10.16.L2074** For sensitive actions: destructive actions, require explicit user approval. | planned | [WEB](#evidence-web) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.17 — Orez memory architecture

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.17.C · Full section contract, including prose and nested example context (brief L2079). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.17.X1** Separate session memory, app preferences, series memory, knowledge/RAG and task state; bound context and expose inspect/remove controls. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.18 — Series RAG

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.18.C · Full section contract, including prose and nested example context (brief L2100). | planned | [MEMORY](#evidence-memory) | No semantic series index for prior dialogue/glossary/summaries/corrections or relevant retrieval was located. |
| **A10.18.L2103** Index: prior translated dialogue,<br>**A10.18.L2104** Index: glossary,<br>**A10.18.L2105** Index: chapter summaries,<br>**A10.18.L2106** Index: corrected names,<br>**A10.18.L2107** Index: user corrections. | planned | [MEMORY](#evidence-memory) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.19 — MangaLens internal knowledge

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.19.C · Full section contract, including prose and nested example context (brief L2113). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.19.L2116** Create a developer-authored knowledge corpus describing: subsystem architecture,<br>**A10.19.L2117** Create a developer-authored knowledge corpus describing: tool schemas,<br>**A10.19.L2118** Create a developer-authored knowledge corpus describing: common errors,<br>**A10.19.L2119** Create a developer-authored knowledge corpus describing: supported media patterns,<br>**A10.19.L2120** Create a developer-authored knowledge corpus describing: OCR behaviours,<br>**A10.19.L2121** Create a developer-authored knowledge corpus describing: recovery procedures, | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.19.L2122** Create a developer-authored knowledge corpus describing: model capabilities. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.20 — Orez critic/evaluator

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.20.C · Full section contract, including prose and nested example context (brief L2126). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.20.L2131** Examples: target language correct?<br>**A10.20.L2132** Examples: terminology consistent?<br>**A10.20.L2133** Examples: output too long?<br>**A10.20.L2134** Examples: media file has audio?<br>**A10.20.L2135** Examples: downloaded resolution satisfies request?<br>**A10.20.L2136** Examples: OCR low confidence? | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.20.L2137** Examples: tool result complete? | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.21 — Recovery engine

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.21.C · Full section contract, including prose and nested example context (brief L2143). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.21.L2148** Examples: network offline,<br>**A10.21.L2149** Examples: timeout,<br>**A10.21.L2150** Examples: HTTP 403,<br>**A10.21.L2151** Examples: expired signed media,<br>**A10.21.L2152** Examples: authentication required,<br>**A10.21.L2153** Examples: provider extractor stale, | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.21.L2154** Examples: no compatible stream,<br>**A10.21.L2155** Examples: DRM/protected source,<br>**A10.21.L2156** Examples: low OCR confidence,<br>**A10.21.L2157** Examples: OOM,<br>**A10.21.L2158** Examples: model unavailable,<br>**A10.21.L2159** Examples: subtitle surface inaccessible, | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A10.21.L2160** Examples: chapter empty,<br>**A10.21.L2161** Examples: site challenge. | partial | [AGENT](#evidence-agent) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 10.22 — Orez event bus

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.22.C · Full section contract, including prose and nested example context (brief L2167). | planned | [TASKS](#evidence-tasks) | StateFlow/Room/WorkManager observations are present; there is no typed cross-subsystem event bus with the requested named events and task-scoped subscriptions. |
| **A10.22.L2170** Subsystems can emit: CHAPTER_LOADED<br>**A10.22.L2171** Subsystems can emit: OCR_LOW_CONFIDENCE<br>**A10.22.L2172** Subsystems can emit: DOWNLOAD_FAILED<br>**A10.22.L2173** Subsystems can emit: DOWNLOAD_COMPLETE<br>**A10.22.L2174** Subsystems can emit: MODEL_READY<br>**A10.22.L2175** Subsystems can emit: STREAM_EXPIRED | planned | [TASKS](#evidence-tasks) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.22.L2176** Subsystems can emit: SUBTITLE_TRACK_CHANGED<br>**A10.22.L2177** Subsystems can emit: MEMORY_PRESSURE | planned | [TASKS](#evidence-tasks) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.23 — Task scheduler

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.23.C · Full section contract, including prose and nested example context (brief L2181). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.23.L2184** Long jobs: chapter translation,<br>**A10.23.L2185** Long jobs: model download,<br>**A10.23.L2186** Long jobs: subtitle generation,<br>**A10.23.L2187** Long jobs: library indexing,<br>**A10.23.L2188** Long jobs: media download. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.24 — Background execution

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.24.C · Full section contract, including prose and nested example context (brief L2192). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.24.L2195** Use proper Android: WorkManager,<br>**A10.24.L2196** Use proper Android: foreground services when required,<br>**A10.24.L2197** Use proper Android: persistent notifications for long visible tasks. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.25 — Orez audit trail

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.25.C · Full section contract, including prose and nested example context (brief L2201). | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |
| **A10.25.L2204** Record: user request,<br>**A10.25.L2205** Record: plan,<br>**A10.25.L2206** Record: tool calls,<br>**A10.25.L2207** Record: errors,<br>**A10.25.L2208** Record: recovery,<br>**A10.25.L2209** Record: final outputs. | partial | [AGENT](#evidence-agent) | Typed deterministic planning and constrained one-tool model proposals exist. Durable execution is restricted to at most eight explicit enqueue_download steps; broader tools, full context, dependencies, recovery and scheduler are incomplete. |

### A 10.26 — Orez development mode

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.26.C · Full section contract, including prose and nested example context (brief L2217). | planned | [LAB](#evidence-lab) | Separate evaluation scripts exist; a Development Orez diagnostics/test/GitHub workflow product is not implemented. Installed production code must continue through reviewed builds. |
| **A10.26.L2224** Development Orez may: analyse diagnostics,<br>**A10.26.L2225** Development Orez may: inspect test failures,<br>**A10.26.L2226** Development Orez may: examine OCR samples,<br>**A10.26.L2227** Development Orez may: run benchmark suites,<br>**A10.26.L2228** Development Orez may: produce bug reports,<br>**A10.26.L2229** Development Orez may: interact with GitHub if explicitly configured with appropriate connector/auth, | planned | [LAB](#evidence-lab) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A10.26.L2230** Development Orez may: propose code changes. | planned | [LAB](#evidence-lab) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 10.27 — Orez autonomy target

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A10.27.C · Full section contract, including prose and nested example context (brief L2237). | planned | [LAB](#evidence-lab) | Domain usefulness is an evaluation goal; no held-out comparative success/latency/resource report establishes frontier parity or complete MangaLens task competence. |

### A 11 — OREZ TRAINING / AI LAB SUBSYSTEM

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.C · Full section contract, including prose and nested example context (brief L2249). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |

### A 11.1 — Python responsibilities

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.1.C · Full section contract, including prose and nested example context (brief L2283). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.1.L2286** Use Python heavily for: dataset generation,<br>**A11.1.L2287** Use Python heavily for: cleaning,<br>**A11.1.L2288** Use Python heavily for: deduplication,<br>**A11.1.L2289** Use Python heavily for: token analysis,<br>**A11.1.L2290** Use Python heavily for: synthetic traces,<br>**A11.1.L2291** Use Python heavily for: fine-tuning, | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.1.L2292** Use Python heavily for: LoRA/QLoRA where compatible,<br>**A11.1.L2293** Use Python heavily for: evaluation,<br>**A11.1.L2294** Use Python heavily for: model conversion,<br>**A11.1.L2295** Use Python heavily for: quantisation experiments,<br>**A11.1.L2296** Use Python heavily for: embedding index building,<br>**A11.1.L2297** Use Python heavily for: benchmark reports, | partial | [LAB](#evidence-lab) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A11.1.L2298** Use Python heavily for: regression analysis,<br>**A11.1.L2299** Use Python heavily for: packaging manifests. | partial | [LAB](#evidence-lab) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 11.2 — Production language mix

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.2.C · Full section contract, including prose and nested example context (brief L2303). | implemented/unverified | [BUILD](#evidence-build) | Kotlin/Compose/Room orchestration, native C++ llama/Whisper, Python offline tooling and Gradle/CI are present; language choice alone is not a performance result. |
| **A11.2.L2306** Recommended: Kotlin: Android/application orchestration.<br>**A11.2.L2307** Recommended: C++ and/or Rust: performance-critical inference/media/vision utilities.<br>**A11.2.L2308** Recommended: Python: training/evaluation/data/research tooling.<br>**A11.2.L2309** Recommended: SQL/Room: persistent task/memory state.<br>**A11.2.L2310** Recommended: shell/Gradle/GitHub Actions: reproducible builds. | implemented/unverified | [BUILD](#evidence-build) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 11.3 — Training data categories

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.3.C · Full section contract, including prose and nested example context (brief L2312). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |

### A 11.4 — Synthetic agent traces

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.4.C · Full section contract, including prose and nested example context (brief L2337). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |

### A 11.5 — Training quality over volume

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.5.C · Full section contract, including prose and nested example context (brief L2368). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.5.L2373** Prefer: legally usable data,<br>**A11.5.L2374** Prefer: synthetic data,<br>**A11.5.L2375** Prefer: developer-authored examples,<br>**A11.5.L2376** Prefer: public/licensed datasets,<br>**A11.5.L2377** Prefer: user-provided samples with permission,<br>**A11.5.L2378** Prefer: deterministic transformations. | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |

### A 11.6 — Translation dataset emphasis

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.6.C · Full section contract, including prose and nested example context (brief L2382). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.6.L2385** Include: Japanese manga,<br>**A11.6.L2386** Include: Korean webtoon dialogue,<br>**A11.6.L2387** Include: Chinese dialogue,<br>**A11.6.L2388** Include: English,<br>**A11.6.L2389** Include: Hindi register,<br>**A11.6.L2390** Include: slang, | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.6.L2391** Include: sarcasm,<br>**A11.6.L2392** Include: insults,<br>**A11.6.L2393** Include: respectful address,<br>**A11.6.L2394** Include: fantasy terminology,<br>**A11.6.L2395** Include: SFX,<br>**A11.6.L2396** Include: fragmented speech. | partial | [LAB](#evidence-lab) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 11.7 — Evaluation-driven model choice

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.7.C · Full section contract, including prose and nested example context (brief L2398). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.7.L2401** Before adopting a model, compare: translation quality,<br>**A11.7.L2402** Before adopting a model, compare: tool accuracy,<br>**A11.7.L2403** Before adopting a model, compare: memory,<br>**A11.7.L2404** Before adopting a model, compare: speed,<br>**A11.7.L2405** Before adopting a model, compare: RAM,<br>**A11.7.L2406** Before adopting a model, compare: token/s, | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.7.L2407** Before adopting a model, compare: battery,<br>**A11.7.L2408** Before adopting a model, compare: crash rate,<br>**A11.7.L2409** Before adopting a model, compare: context size,<br>**A11.7.L2410** Before adopting a model, compare: quantised quality. | partial | [LAB](#evidence-lab) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 11.8 — Target command interface for AI lab

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A11.8.C · Full section contract, including prose and nested example context (brief L2414). | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.8.L2429** Every run should record: base model,<br>**A11.8.L2430** Every run should record: dataset versions,<br>**A11.8.L2431** Every run should record: git commit,<br>**A11.8.L2432** Every run should record: hyperparameters,<br>**A11.8.L2433** Every run should record: seed,<br>**A11.8.L2434** Every run should record: metrics, | partial | [LAB](#evidence-lab) | Python corpus streaming/seed packing and provenance validation exist. Full licensed task/translation/OCR/subtitle/recovery datasets, held-out suites and reproducible training/conversion/quantization commands are incomplete; no training success is claimed. |
| **A11.8.L2435** Every run should record: output hash.<br>**A11.8.X1** Implement proposed build_dataset, generate_tool_traces, train_lora, merge_adapter, quantize, run_suite and package_model interfaces where licensed data and free compute permit; record base/dataset versions, Git SHA, hyperparameters, seed, metrics and output hashes. | partial | [LAB](#evidence-lab) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 12 — WEB WORKSPACE / BROWSER

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A12.C · Full section contract, including prose and nested example context (brief L2439). | partial | [WEB](#evidence-web) | Address/search/back/forward/refresh, WebView session cookies and contextual media/translation exist. Tabs/bookmarks/history/find/desktop/private/profiles, durable session restoration and full upload/download integration are incomplete. |

### A 12.1 — Features

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A12.1.C · Full section contract, including prose and nested example context (brief L2443). | partial | [WEB](#evidence-web) | Address/search/back/forward/refresh, WebView session cookies and contextual media/translation exist. Tabs/bookmarks/history/find/desktop/private/profiles, durable session restoration and full upload/download integration are incomplete. |
| **A12.1.L2445** tabs,<br>**A12.1.L2450** bookmarks,<br>**A12.1.L2451** history,<br>**A12.1.L2452** find on page,<br>**A12.1.L2453** desktop/mobile mode,<br>**A12.1.L2454** file upload, | planned | [WEB](#evidence-web) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A12.1.L2457** site permissions,<br>**A12.1.L2458** private mode,<br>**A12.1.L2459** session persistence. | planned | [WEB](#evidence-web) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A12.1.L2446** back,<br>**A12.1.L2447** forward,<br>**A12.1.L2448** refresh,<br>**A12.1.L2449** URL/search bar, | implemented/unverified | [WEB](#evidence-web) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A12.1.L2455** file download,<br>**A12.1.L2456** external open, | partial | [WEB](#evidence-web) | Address/search/back/forward/refresh, WebView session cookies and contextual media/translation exist. Tabs/bookmarks/history/find/desktop/private/profiles, durable session restoration and full upload/download integration are incomplete. |

### A 12.2 — Authentication/session

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A12.2.C · Full section contract, including prose and nested example context (brief L2461). | implemented/unverified | [WEB](#evidence-web) | WebView/CookieManager own login/session data and scoped resolver/playback/download header handoff; exercise legitimate login, revocation and cross-origin credential leakage tests. |
| **A12.2.L2466** Secure session handling: cookies remain in browser/session subsystem,<br>**A12.2.L2467** Secure session handling: Orez does not receive plaintext passwords,<br>**A12.2.L2468** Secure session handling: cookies are shared with resolver/downloader only when required and authorized by the active session. | implemented/unverified | [WEB](#evidence-web) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 12.3 — Contextual detection

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A12.3.C · Full section contract, including prose and nested example context (brief L2470). | partial | [WEB](#evidence-web) | Contextual Reader/player/download/text-translation controls exist; image OCR/Ask Orez and complete content-aware actions need implementation and UI tests. |
| **A12.3.L2473** When a page contains: manga -> "Open in Reader"<br>**A12.3.L2474** When a page contains: video -> "Play in MangaLens"<br>**A12.3.L2475** When a page contains: media -> "Download"<br>**A12.3.L2476** When a page contains: text/image -> "Translate"<br>**A12.3.L2477** When a page contains: image -> "OCR / Ask Orez" | partial | [WEB](#evidence-web) | Contextual Reader/player/download/text-translation controls exist; image OCR/Ask Orez and complete content-aware actions need implementation and UI tests. |

### A 12.4 — Browser profiles

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A12.4.C · Full section contract, including prose and nested example context (brief L2481). | planned | [WEB](#evidence-web) | Independent Normal/Private/Work/Custom cookie/history profiles were not located. |
| **A12.4.L2484** Future: Normal<br>**A12.4.L2485** Future: Private<br>**A12.4.L2486** Future: Work<br>**A12.4.L2487** Future: Custom | planned | [WEB](#evidence-web) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 13 — PROTECTION CENTER AND AD BLOCKER

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A13.C · Full section contract, including prose and nested example context (brief L2493). | partial | [PROTECTION](#evidence-protection) | Mature host/request/tracker/navigation/cosmetic/popup and provider script protections are retained; maintained real-media and distinguishable-ad fixture coverage remains required. |

### A 13.1 — Layers

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A13.1.C · Full section contract, including prose and nested example context (brief L2497). | partial | [PROTECTION](#evidence-protection) | Mature host/request/tracker/navigation/cosmetic/popup and provider script protections are retained; maintained real-media and distinguishable-ad fixture coverage remains required. |
| **A13.1.L2499** host/domain blocking,<br>**A13.1.L2500** request filtering,<br>**A13.1.L2501** tracker filtering,<br>**A13.1.L2502** third-party script rules,<br>**A13.1.L2503** popup/pop-under blocking,<br>**A13.1.L2504** redirect filtering, | implemented/unverified | [PROTECTION](#evidence-protection) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A13.1.L2505** cosmetic DOM rules,<br>**A13.1.L2506** known ad endpoints,<br>**A13.1.L2507** suspicious beacon/fetch/XHR patterns,<br>**A13.1.L2508** site-specific scriptlets where justified. | implemented/unverified | [PROTECTION](#evidence-protection) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 13.2 — Media-safe design

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A13.2.C · Full section contract, including prose and nested example context (brief L2510). | implemented/unverified | [PROTECTION](#evidence-protection) | Media-safe exemptions and ad-host/media tests exist; execute playback fixtures to prove normal first-party/shared CDN media remains available. |

### A 13.3 — YouTube/site ads

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A13.3.C · Full section contract, including prose and nested example context (brief L2518). | partial | [PROTECTION](#evidence-protection) | Mature host/request/tracker/navigation/cosmetic/popup and provider script protections are retained; maintained real-media and distinguishable-ad fixture coverage remains required. |
| **A13.3.L2521** Use layered techniques: network patterns where distinguishable,<br>**A13.3.L2522** Use layered techniques: DOM ad slot removal,<br>**A13.3.L2523** Use layered techniques: overlay removal,<br>**A13.3.L2524** Use layered techniques: visible Skip Ad interaction,<br>**A13.3.L2525** Use layered techniques: provider-specific rules. | implemented/unverified | [PROTECTION](#evidence-protection) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 13.4 — Protection Center UI

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A13.4.C · Full section contract, including prose and nested example context (brief L2531). | partial | [PROTECTION](#evidence-protection) | Blocked requests, known saved bytes and recent host events are exposed. Full tracker/popup accounting, per-site Strict/Standard/Allow settings and diagnostics are incomplete. |
| **A13.4.L2534** Show: requests blocked,<br>**A13.4.L2537** Show: estimated bytes saved,<br>**A13.4.L2538** Show: recent events, | implemented/unverified | [PROTECTION](#evidence-protection) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A13.4.L2535** Show: trackers,<br>**A13.4.L2536** Show: popups, | partial | [PROTECTION](#evidence-protection) | Blocked requests, known saved bytes and recent host events are exposed. Full tracker/popup accounting, per-site Strict/Standard/Allow settings and diagnostics are incomplete. |
| **A13.4.L2539** Show: per-site setting.<br>**A13.4.L2542** Modes: Strict<br>**A13.4.L2543** Modes: Standard<br>**A13.4.L2544** Modes: Allow | planned | [PROTECTION](#evidence-protection) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 14 — UNIVERSAL MEDIA ENGINE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.C · Full section contract, including prose and nested example context (brief L2550). | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |

### A 14.1 — Formats

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.1.C · Full section contract, including prose and nested example context (brief L2552). | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.1.L2555** Target: MP4<br>**A14.1.L2556** Target: WebM<br>**A14.1.L2557** Target: MKV<br>**A14.1.L2558** Target: MOV where supported<br>**A14.1.L2559** Target: HLS<br>**A14.1.L2560** Target: MPEG-DASH | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.1.L2563** Codecs: H.264/AVC<br>**A14.1.L2564** Codecs: H.265/HEVC<br>**A14.1.L2565** Codecs: VP9<br>**A14.1.L2566** Codecs: AV1<br>**A14.1.L2567** Codecs: AAC<br>**A14.1.L2568** Codecs: Opus depending on device/runtime support. | partial | [MEDIA](#evidence-media) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 14.2 — Provider architecture

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.2.C · Full section contract, including prose and nested example context (brief L2571). | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.2.L2576** Examples: YouTubeProvider<br>**A14.2.L2577** Examples: InstagramProvider<br>**A14.2.L2578** Examples: VimeoProvider<br>**A14.2.L2579** Examples: XProvider<br>**A14.2.L2580** Examples: TikTokProvider<br>**A14.2.L2581** Examples: FacebookProvider | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.2.L2582** Examples: GenericHtml5Provider<br>**A14.2.L2583** Examples: GenericHlsProvider<br>**A14.2.L2584** Examples: GenericDashProvider | partial | [MEDIA](#evidence-media) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 14.3 — Resolution paths

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.3.C · Full section contract, including prose and nested example context (brief L2588). | implemented/unverified | [MEDIA](#evidence-media) | Direct/provider/HTML/browser-observed/signed media paths and separate audio/source/context headers exist; controlled fixtures and real accessible provider acceptance must execute. |
| **A14.3.L2592** A media page can be resolved using multiple strategies: Direct URL/MIME<br>**A14.3.L2593** A media page can be resolved using multiple strategies: Provider extractor<br>**A14.3.L2594** A media page can be resolved using multiple strategies: Page metadata<br>**A14.3.L2595** A media page can be resolved using multiple strategies: HTML video/source tags<br>**A14.3.L2596** A media page can be resolved using multiple strategies: embedded manifests<br>**A14.3.L2597** A media page can be resolved using multiple strategies: JS/player metadata where accessible | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A14.3.L2598** A media page can be resolved using multiple strategies: browser-observed network requests<br>**A14.3.L2599** A media page can be resolved using multiple strategies: HLS/DASH manifest parsing<br>**A14.3.L2600** A media page can be resolved using multiple strategies: signed source/session reuse | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 14.4 — Session handoff

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.4.C · Full section contract, including prose and nested example context (brief L2604). | implemented/unverified | [MEDIA](#evidence-media) | Direct/provider/HTML/browser-observed/signed media paths and separate audio/source/context headers exist; controlled fixtures and real accessible provider acceptance must execute. |
| **A14.4.L2607** Web -> Player/Downloader should preserve: source page,<br>**A14.4.L2608** Web -> Player/Downloader should preserve: Cookie,<br>**A14.4.L2609** Web -> Player/Downloader should preserve: Referer,<br>**A14.4.L2610** Web -> Player/Downloader should preserve: User-Agent,<br>**A14.4.L2611** Web -> Player/Downloader should preserve: allowed headers,<br>**A14.4.L2612** Web -> Player/Downloader should preserve: audio URL where separate, | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A14.4.L2613** Web -> Player/Downloader should preserve: provider metadata. | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 14.5 — Player

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A14.5.C · Full section contract, including prose and nested example context (brief L2615). | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.5.L2618** Features: quality,<br>**A14.5.L2619** Features: tracks, | partial | [MEDIA](#evidence-media) | Media3 handles direct/HLS/DASH and supported device containers/codecs; yt-dlp broad extraction and native controls exist. Provider interface registry, PiP/background audio and hardware acceptance remain incomplete. |
| **A14.5.L2620** Features: subtitles,<br>**A14.5.L2621** Features: audio track,<br>**A14.5.L2622** Features: playback speed,<br>**A14.5.L2623** Features: gestures,<br>**A14.5.L2624** Features: brightness,<br>**A14.5.L2625** Features: volume, | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A14.5.L2626** Features: seek,<br>**A14.5.L2627** Features: crop,<br>**A14.5.L2628** Features: fit,<br>**A14.5.L2629** Features: zoom,<br>**A14.5.L2630** Features: rotate,<br>**A14.5.L2633** Features: screen lock, | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A14.5.L2634** Features: position memory. | implemented/unverified | [MEDIA](#evidence-media) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A14.5.L2631** Features: PiP,<br>**A14.5.L2632** Features: background audio, | planned | [MEDIA](#evidence-media) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 15 — LIVE SUBTITLES AND VIDEO TRANSLATION

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.C · Full section contract, including prose and nested example context (brief L2640). | partial | [CAPTIONS](#evidence-captions) | Embedded cues, PixelCopy OCR, bounded PCM windows, local Whisper, heuristic activity detection and English generated cues exist; multilingual quality, context cleanup and durable long-video jobs are incomplete. |
| **A15.L2644** Priority order: Embedded text subtitles<br>**A15.L2645** Priority order: External SRT/VTT<br>**A15.L2646** Priority order: Burned-in subtitle visual OCR<br>**A15.L2647** Priority order: Spoken audio transcription | partial | [CAPTIONS](#evidence-captions) | Embedded cues, PixelCopy OCR, bounded PCM windows, local Whisper, heuristic activity detection and English generated cues exist; multilingual quality, context cleanup and durable long-video jobs are incomplete. |

### A 15.1 — Embedded captions

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.1.C · Full section contract, including prose and nested example context (brief L2649). | implemented/unverified | [CAPTIONS](#evidence-captions) | Cue translation and visual subtitle OCR/sampling/dedup/cache source paths are present; validate priority, duplicates, confidence/timing and protected-surface UX on emulator. |
| **A15.1.L2652** If Media3 provides text cues: translate cues directly,<br>**A15.1.L2653** If Media3 provides text cues: do not OCR them from pixels,<br>**A15.1.L2654** If Media3 provides text cues: suppress duplicate original overlay if translated view replaces it. | implemented/unverified | [CAPTIONS](#evidence-captions) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 15.2 — Burned-in captions

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.2.C · Full section contract, including prose and nested example context (brief L2656). | implemented/unverified | [CAPTIONS](#evidence-captions) | Cue translation and visual subtitle OCR/sampling/dedup/cache source paths are present; validate priority, duplicates, confidence/timing and protected-surface UX on emulator. |
| **A15.2.L2659** Use visual OCR: stable frame sampling,<br>**A15.2.L2660** Use visual OCR: subtitle-region detection,<br>**A15.2.L2661** Use visual OCR: temporal deduplication,<br>**A15.2.L2662** Use visual OCR: confidence,<br>**A15.2.L2663** Use visual OCR: translation cache. | implemented/unverified | [CAPTIONS](#evidence-captions) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 15.3 — Audio transcription

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.3.C · Full section contract, including prose and nested example context (brief L2667). | partial | [CAPTIONS](#evidence-captions) | Embedded cues, PixelCopy OCR, bounded PCM windows, local Whisper, heuristic activity detection and English generated cues exist; multilingual quality, context cleanup and durable long-video jobs are incomplete. |
| **A15.3.L2670** Long-term real solution for no-caption video: audio decode/playback capture where permitted,<br>**A15.3.L2671** Long-term real solution for no-caption video: voice activity detection,<br>**A15.3.L2672** Long-term real solution for no-caption video: local ASR,<br>**A15.3.L2673** Long-term real solution for no-caption video: timestamping,<br>**A15.3.L2674** Long-term real solution for no-caption video: fragment stitching,<br>**A15.3.L2675** Long-term real solution for no-caption video: Orez cleanup, | partial | [CAPTIONS](#evidence-captions) | Embedded cues, PixelCopy OCR, bounded PCM windows, local Whisper, heuristic activity detection and English generated cues exist; multilingual quality, context cleanup and durable long-video jobs are incomplete. |
| **A15.3.L2676** Long-term real solution for no-caption video: translation. | partial | [CAPTIONS](#evidence-captions) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 15.4 — Full-video subtitle generation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.4.C · Full section contract, including prose and nested example context (brief L2680). | partial | [CAPTIONS](#evidence-captions) | FullVideoSubtitleGenerator decodes bounded audio, writes an English SRT and caches completed output in a UI-owned coroutine. It lacks checkpointed foreground/WorkManager ownership and true interrupted-job resume; VTT export is incomplete. |
| **A15.4.L2683** Background job: segment audio,<br>**A15.4.L2684** Background job: transcribe,<br>**A15.4.L2687** Background job: write SRT/VTT. | partial | [CAPTIONS](#evidence-captions) | Generated English SRT exists in a UI-owned coroutine; resumable background job and VTT remain incomplete. |
| **A15.4.L2685** Background job: translate,<br>**A15.4.L2686** Background job: validate, | partial | [CAPTIONS](#evidence-captions) | FullVideoSubtitleGenerator decodes bounded audio, writes an English SRT and caches completed output in a UI-owned coroutine. It lacks checkpointed foreground/WorkManager ownership and true interrupted-job resume; VTT export is incomplete. |

### A 15.5 — Dual subtitle mode

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A15.5.C · Full section contract, including prose and nested example context (brief L2691). | planned | [CAPTIONS](#evidence-captions) | Simultaneously visible original/translated dual captions were not located as a completed mode. |
| **A15.5.L2694** Optional: original<br>**A15.5.L2695** Optional: translated | planned | [CAPTIONS](#evidence-captions) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 16 — UNIVERSAL DOWNLOAD MANAGER

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.C · Full section contract, including prose and nested example context (brief L2701). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |

### A 16.1 — Input

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.1.C · Full section contract, including prose and nested example context (brief L2707). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.1.L2710** Paste/share/open: YouTube<br>**A16.1.L2711** Paste/share/open: Shorts<br>**A16.1.L2712** Paste/share/open: Instagram/Reels<br>**A16.1.L2713** Paste/share/open: generic websites<br>**A16.1.L2714** Paste/share/open: HLS<br>**A16.1.L2715** Paste/share/open: DASH | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.1.L2716** Paste/share/open: direct media<br>**A16.1.L2717** Paste/share/open: images<br>**A16.1.L2718** Paste/share/open: chapter pages<br>**A16.1.L2719** Paste/share/open: other provider pages. | partial | [DOWNLOADS](#evidence-downloads) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 16.2 — Broad resolution strategy

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.2.C · Full section contract, including prose and nested example context (brief L2721). | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Generic/provider resolution, BEST/480/720/1080/1440/2160 ceilings, stored source context and signed-source refresh exist, without DRM bypass. Verify exact accessible representations and source-change resume behavior. |
| **A16.2.L2724** For a URL: resolve provider,<br>**A16.2.L2725** For a URL: attempt best extractor,<br>**A16.2.L2726** For a URL: reuse authorized web session,<br>**A16.2.L2727** For a URL: inspect page/media metadata,<br>**A16.2.L2728** For a URL: inspect adaptive manifests,<br>**A16.2.L2729** For a URL: inspect browser-observed playable media, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.2.L2730** For a URL: select best candidate,<br>**A16.2.L2731** For a URL: refresh stale extractor if supported,<br>**A16.2.L2732** For a URL: retry with alternative compatible path. | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 16.3 — Quality

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.3.C · Full section contract, including prose and nested example context (brief L2736). | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Generic/provider resolution, BEST/480/720/1080/1440/2160 ceilings, stored source context and signed-source refresh exist, without DRM bypass. Verify exact accessible representations and source-change resume behavior. |
| **A16.3.L2739** Offer: Best Available<br>**A16.3.L2740** Offer: 4K<br>**A16.3.L2741** Offer: 1440p<br>**A16.3.L2742** Offer: 1080p<br>**A16.3.L2743** Offer: 720p<br>**A16.3.L2744** Offer: 480p | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.3.L2745** Offer: optional Audio Only later | planned | [DOWNLOADS](#evidence-downloads) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 16.4 — Adaptive split streams

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.4.C · Full section contract, including prose and nested example context (brief L2749). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.4.L2752** High-quality providers often expose: video-only,<br>**A16.4.L2753** High-quality providers often expose: audio-only. | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.4.L2756** Downloader must: select quality,<br>**A16.4.L2757** Downloader must: download video,<br>**A16.4.L2758** Downloader must: download audio,<br>**A16.4.L2759** Downloader must: verify components,<br>**A16.4.L2760** Downloader must: mux,<br>**A16.4.L2761** Downloader must: verify final media, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.4.L2762** Downloader must: save. | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 16.5 — Performance

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.5.C · Full section contract, including prose and nested example context (brief L2764). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.5.L2767** Where sources support it: concurrent fragment downloads,<br>**A16.5.L2772** Where sources support it: adaptive buffer sizing.<br>**A16.5.L2776** Show: average speed,<br>**A16.5.L2781** Show: codec, | planned | [DOWNLOADS](#evidence-downloads) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A16.5.L2768** Where sources support it: range requests, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.5.L2769** Where sources support it: connection reuse, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.5.L2770** Where sources support it: resumable transfer, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.5.L2771** Where sources support it: bounded parallelism, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.5.L2775** Show: current speed, | planned | [DOWNLOADS](#evidence-downloads) | DownloadsScreen has bytes/progress/stage/output height, but no current-speed telemetry or display. |
| **A16.5.L2777** Show: ETA, | planned | [DOWNLOADS](#evidence-downloads) | No ETA calculation/display was located in DownloadsScreen or the persisted transfer model. |
| **A16.5.L2778** Show: bytes,<br>**A16.5.L2779** Show: total,<br>**A16.5.L2780** Show: resolution,<br>**A16.5.L2782** Show: stage. | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 16.6 — Stages

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.6.C · Full section contract, including prose and nested example context (brief L2784). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.6.L2787** States: Resolving<br>**A16.6.L2790** States: Downloading Audio<br>**A16.6.L2791** States: Downloading Video<br>**A16.6.L2792** States: Merging<br>**A16.6.L2793** States: Verifying | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.6.L2788** States: Queued<br>**A16.6.L2789** States: Downloading<br>**A16.6.L2794** States: Completed<br>**A16.6.L2795** States: Paused<br>**A16.6.L2796** States: Failed<br>**A16.6.L2797** States: Cancelled | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 16.7 — Signed URL refresh

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.7.C · Full section contract, including prose and nested example context (brief L2799). | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Generic/provider resolution, BEST/480/720/1080/1440/2160 ceilings, stored source context and signed-source refresh exist, without DRM bypass. Verify exact accessible representations and source-change resume behavior. |
| **A16.7.L2802** If a source expires: retain original page and session context,<br>**A16.7.L2803** If a source expires: re-resolve,<br>**A16.7.L2804** If a source expires: get refreshed media URL,<br>**A16.7.L2805** If a source expires: invalidate incompatible partials if representation changed,<br>**A16.7.L2806** If a source expires: resume/restart intelligently. | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 16.8 — Retry intelligence

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.8.C · Full section contract, including prose and nested example context (brief L2808). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.8.L2811** Classify errors: timeout -> retry/backoff,<br>**A16.8.L2812** Classify errors: 403 signed URL -> refresh source,<br>**A16.8.L2813** Classify errors: stale extractor -> update/retry,<br>**A16.8.L2814** Classify errors: format unavailable -> select next matching representation,<br>**A16.8.L2815** Classify errors: audio missing -> choose alternate mux pair,<br>**A16.8.L2816** Classify errors: storage low -> pause with actionable message, | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.8.L2817** Classify errors: HTML instead of media -> re-resolve page. | partial | [DOWNLOADS](#evidence-downloads) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 16.9 — Verification

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.9.C · Full section contract, including prose and nested example context (brief L2821). | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |
| **A16.9.L2824** After download: parse container, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.9.L2825** After download: confirm playable duration, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.9.L2826** After download: confirm audio for video unless intentionally silent, | partial | [DOWNLOADS](#evidence-downloads) | MediaDownloadWorker requires audio only when a separate audioUrl is present. General video audio expectation/intentional-silence metadata is incomplete; actual present track samples are checked. |
| **A16.9.L2827** After download: confirm resolution, | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.9.L2829** After download: confirm mux success. | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A16.9.L2828** After download: confirm output non-trivial, | partial | [DOWNLOADS](#evidence-downloads) | Native direct/resumable transfer, separate AAC mux, publication/tail/audio checks and Media3 adaptive cache exist; universal standalone adaptive export, all recovery categories/stage telemetry and runtime faults need completion. |

### A 16.10 — Limits/security

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A16.10.C · Full section contract, including prose and nested example context (brief L2831). | implemented/unverified | [DOWNLOADS](#evidence-downloads) | Generic/provider resolution, BEST/480/720/1080/1440/2160 ceilings, stored source context and signed-source refresh exist, without DRM bypass. Verify exact accessible representations and source-change resume behavior. |

### A 17 — LIBRARY / STORAGE / OFFLINE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A17.C · Full section contract, including prose and nested example context (brief L2839). | planned | [LIBRARY](#evidence-library) | Subsystem files are retained, but no unified Manga/Video/Models/Cache/Other storage inventory/policy UI was located; never silently remove valuable files. |
| **A17.L2842** Unify storage management for: manga,<br>**A17.L2843** Unify storage management for: chapters,<br>**A17.L2844** Unify storage management for: translated copies,<br>**A17.L2845** Unify storage management for: video,<br>**A17.L2846** Unify storage management for: subtitles,<br>**A17.L2847** Unify storage management for: models, | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A17.L2848** Unify storage management for: cache,<br>**A17.L2849** Unify storage management for: downloads.<br>**A17.L2852** Storage UI: Manga<br>**A17.L2853** Storage UI: Video<br>**A17.L2854** Storage UI: AI Models<br>**A17.L2855** Storage UI: Cache | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A17.L2856** Storage UI: Other<br>**A17.L2859** Policies: clear cache,<br>**A17.L2860** Policies: delete failed partials,<br>**A17.L2861** Policies: keep favourites,<br>**A17.L2862** Policies: optional watched-video cleanup.<br>**A17.X1** Safe storage/import/export policies must retain originals, favourites, translations and completed transfers; delete only proven regenerable cache by default and preserve sole copies during migration. | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 18 — GLOBAL SEARCH

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A18.C · Full section contract, including prose and nested example context (brief L2868). | planned | [LIBRARY](#evidence-library) | Library has local title filtering and chat has lexical corpus retrieval; no global OCR/bookmark/download/video/history/glossary/conversation or semantic index was located. |
| **A18.L2871** Search: manga,<br>**A18.L2872** Search: chapter titles,<br>**A18.L2873** Search: OCR text,<br>**A18.L2874** Search: bookmarks,<br>**A18.L2875** Search: downloads,<br>**A18.L2876** Search: video, | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |
| **A18.L2877** Search: browser history,<br>**A18.L2878** Search: glossary,<br>**A18.L2879** Search: Orez conversations where appropriate.<br>**A18.X1** Semantic first-occurrence search uses a local embeddings/index provider when feasible; inspectable series glossary/corrections are distinct from generic conversation retrieval. | planned | [LIBRARY](#evidence-library) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 19 — ANDROID INTEGRATION

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A19.C · Full section contract, including prose and nested example context (brief L2888). | partial | [ANDROID](#evidence-android) | Shares, document pickers, permission-gated native Watch and shortcut widget exist; richer widgets and mangalens:// deep links remain incomplete. |

### A 19.1 — Share target

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A19.1.C · Full section contract, including prose and nested example context (brief L2890). | implemented/unverified | [ANDROID](#evidence-android) | SEND/SEND_MULTIPLE supports URLs, images, videos, PDF and ZIP/CBZ with native routing/import; verify grants, denial/revocation and target behavior. |
| **A19.1.L2894** Share -> MangaLens: URL -> resolver<br>**A19.1.L2895** Share -> MangaLens: image -> OCR<br>**A19.1.L2896** Share -> MangaLens: manga URL -> Reader<br>**A19.1.L2897** Share -> MangaLens: video URL -> Watch/Download<br>**A19.1.L2898** Share -> MangaLens: document -> import. | implemented/unverified | [ANDROID](#evidence-android) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 19.2 — Widgets

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A19.2.C · Full section contract, including prose and nested example context (brief L2900). | partial | [ANDROID](#evidence-android) | Four shortcut widget actions exist; current-progress Continue Reading/Watching and Download progress widgets are not completed. |
| **A19.2.L2903** Potential: Continue Reading<br>**A19.2.L2904** Potential: Continue Watching<br>**A19.2.L2905** Potential: Orez<br>**A19.2.L2906** Potential: Download progress | partial | [ANDROID](#evidence-android) | Four shortcut widget actions exist; current-progress Continue Reading/Watching and Download progress widgets are not completed. |

### A 19.3 — Deep links

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A19.3.C · Full section contract, including prose and nested example context (brief L2908). | planned | [ANDROID](#evidence-android) | No mangalens:// ACTION_VIEW intent filter/router exists in the inspected manifest/MainActivity. |
| **A19.3.L2911** Internal routes: `mangalens://reader/...`<br>**A19.3.L2912** Internal routes: `mangalens://video/...`<br>**A19.3.L2913** Internal routes: `mangalens://orez/...` | planned | [ANDROID](#evidence-android) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 20 — PERFORMANCE AND RESOURCE DISCIPLINE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A20.C · Full section contract, including prose and nested example context (brief L2917). | partial | [RESOURCES](#evidence-resources) | Streaming IO, tile/window bounds, model idle release and trim-memory hooks exist. Adaptive thermal/battery/load governor, playback prioritization and disk-backed all-chapter translation are incomplete. |
| **A20.L2922** Use: lazy initialization,<br>**A20.L2923** Use: bounded concurrency,<br>**A20.L2924** Use: streaming IO,<br>**A20.L2925** Use: model unloading,<br>**A20.L2926** Use: bitmap tiling,<br>**A20.L2927** Use: cache limits, | implemented/unverified | [RESOURCES](#evidence-resources) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A20.L2928** Use: cancellation,<br>**A20.L2930** Use: memory-pressure responses,<br>**A20.L2931** Use: foreground workers. | implemented/unverified | [RESOURCES](#evidence-resources) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A20.L2929** Use: thermal awareness,<br>**A20.X1** Disk-backed active workspace; bounded image/audio/frame windows; phase-ending buffer/model release; thermal/battery/memory/load governor reduces work with hysteresis, using hard pauses only for critical pressure. | partial | [RESOURCES](#evidence-resources) | Streaming IO, tile/window bounds, model idle release and trim-memory hooks exist. Adaptive thermal/battery/load governor, playback prioritization and disk-backed all-chapter translation are incomplete. |

### A 20.1 — Thermal/battery

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A20.1.C · Full section contract, including prose and nested example context (brief L2933). | planned | [RESOURCES](#evidence-resources) | No shared thermal/battery-duration/load scheduler with hysteresis or the requested playback-first priority policy was located. |
| **A20.1.L2936** If the device is hot: reduce OCR concurrency,<br>**A20.1.L2937** If the device is hot: delay deep AI refinement,<br>**A20.1.L2938** If the device is hot: preserve playback/UI responsiveness. | planned | [RESOURCES](#evidence-resources) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 20.2 — Resource priority

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A20.2.C · Full section contract, including prose and nested example context (brief L2940). | planned | [RESOURCES](#evidence-resources) | No shared thermal/battery-duration/load scheduler with hysteresis or the requested playback-first priority policy was located. |
| **A20.2.L2943** Priority order during video playback: playback/audio<br>**A20.2.L2944** Priority order during video playback: UI<br>**A20.2.L2945** Priority order during video playback: subtitle task<br>**A20.2.L2946** Priority order during video playback: background translation<br>**A20.2.L2947** Priority order during video playback: indexing/training-like tasks | planned | [RESOURCES](#evidence-resources) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 21 — SECURITY MODEL FOR POWERFUL OREZ

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.C · Full section contract, including prose and nested example context (brief L2953). | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |

### A 21.1 — Principle

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.1.C · Full section contract, including prose and nested example context (brief L2957). | implemented/unverified | [SECURITY](#evidence-security) | Policy owns authorization, tools validate effects, and untrusted content has no direct privilege. Runtime test execution is still required; model output cannot itself prove completion/security. |

### A 21.2 — Capability isolation

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.2.C · Full section contract, including prose and nested example context (brief L2961). | implemented/unverified | [SECURITY](#evidence-security) | Policy owns authorization, tools validate effects, and untrusted content has no direct privilege. Runtime test execution is still required; model output cannot itself prove completion/security. |
| **A21.2.L2964** LLM cannot directly: read arbitrary files,<br>**A21.2.L2965** LLM cannot directly: access raw cookies,<br>**A21.2.L2966** LLM cannot directly: execute shell,<br>**A21.2.L2967** LLM cannot directly: send network requests,<br>**A21.2.L2968** LLM cannot directly: delete data. | implemented/unverified | [SECURITY](#evidence-security) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 21.3 — Permission categories

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.3.C · Full section contract, including prose and nested example context (brief L2972). | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |
| **A21.3.L2975** Example: READ_ONLY<br>**A21.3.L2976** Example: LOCAL_SAFE_WRITE<br>**A21.3.L2977** Example: NETWORK_NAVIGATION<br>**A21.3.L2978** Example: ACCOUNT_MUTATION<br>**A21.3.L2979** Example: DESTRUCTIVE<br>**A21.3.L2980** Example: EXTERNAL_PUBLISH | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |

### A 21.4 — Credentials

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.4.C · Full section contract, including prose and nested example context (brief L2986). | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |
| **A21.4.L2988** passwords remain in secure browser/password manager,<br>**A21.4.L2990** tool layer can use authenticated session without exposing password to model.<br>**A21.4.X1** Native credentials/passwords remain out of model prompts, screenshots/logs/training/exports; revoke picker grants cleanly and prohibit credential/cookie exfiltration, DRM/paywall bypass and silent uploads. | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |
| **A21.4.L2989** tokens encrypted, | planned | [SECURITY](#evidence-security) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 21.5 — Prompt injection

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.5.C · Full section contract, including prose and nested example context (brief L2992). | implemented/unverified | [SECURITY](#evidence-security) | Policy owns authorization, tools validate effects, and untrusted content has no direct privilege. Runtime test execution is still required; model output cannot itself prove completion/security. |
| **A21.5.L2995** Treat: web text,<br>**A21.5.L2996** Treat: subtitles,<br>**A21.5.L2997** Treat: manga dialogue,<br>**A21.5.L2998** Treat: imported documents as untrusted content. | implemented/unverified | [SECURITY](#evidence-security) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |

### A 21.6 — Model-pack supply chain

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A21.6.C · Full section contract, including prose and nested example context (brief L3003). | partial | [SECURITY](#evidence-security) | Trusted typed tools, scoped cookies/imports/private paths and fixed HTTPS/hash model delivery exist. All future permissions/token encryption, signed versioned packs and whole-runtime adversarial acceptance are incomplete. |
| **A21.6.L3006** Model manager must verify: HTTPS,<br>**A21.6.L3008** Model manager must verify: hash,<br>**A21.6.L3010** Model manager must verify: expected size/version. | implemented/unverified | [SECURITY](#evidence-security) | Concrete related source path exists. Current candidate execution, relevant output checks and fault/recovery acceptance are pending. |
| **A21.6.L3007** Model manager must verify: manifest,<br>**A21.6.L3009** Model manager must verify: optional signature, | planned | [SECURITY](#evidence-security) | No completed source path for these clauses was located; related source is an extension point, not implementation evidence. |

### A 22 — TESTING STRATEGY

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.C · Full section contract, including prose and nested example context (brief L3016). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 22.1 — Unit tests

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.1.C · Full section contract, including prose and nested example context (brief L3018). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.1.L3020** URL classification<br>**A22.1.L3021** provider selection<br>**A22.1.L3022** media format selection<br>**A22.1.L3023** quality ceiling<br>**A22.1.L3024** subtitle deduplication<br>**A22.1.L3025** translation quality policy | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.1.L3026** OCR grouping<br>**A22.1.L3027** glossary retrieval<br>**A22.1.L3028** task state transitions<br>**A22.1.L3029** security policy. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 22.2 — Golden OCR tests

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.2.C · Full section contract, including prose and nested example context (brief L3031). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.2.L3034** Maintain reference manga/manhwa images with expected: text,<br>**A22.2.L3035** Maintain reference manga/manhwa images with expected: regions,<br>**A22.2.L3036** Maintain reference manga/manhwa images with expected: script,<br>**A22.2.L3037** Maintain reference manga/manhwa images with expected: bubble grouping. | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 22.3 — Translation regression

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.3.C · Full section contract, including prose and nested example context (brief L3041). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.3.L3044** Expected characteristics: target script,<br>**A22.3.L3045** Expected characteristics: terms,<br>**A22.3.L3046** Expected characteristics: names,<br>**A22.3.L3047** Expected characteristics: social register,<br>**A22.3.L3048** Expected characteristics: punctuation,<br>**A22.3.L3049** Expected characteristics: no source leakage. | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 22.4 — Download tests

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.4.C · Full section contract, including prose and nested example context (brief L3051). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.4.L3054** Fixtures: direct MP4,<br>**A22.4.L3055** Fixtures: HLS,<br>**A22.4.L3056** Fixtures: DASH,<br>**A22.4.L3057** Fixtures: split audio/video,<br>**A22.4.L3058** Fixtures: expired source simulation,<br>**A22.4.L3059** Fixtures: resumable range, | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.4.L3060** Fixtures: provider resolution. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 22.5 — Reader tests

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.5.C · Full section contract, including prose and nested example context (brief L3062). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.5.L3064** RTL/LTR,<br>**A22.5.L3065** reading position,<br>**A22.5.L3066** saved chapter,<br>**A22.5.L3067** failed page,<br>**A22.5.L3068** long webtoon,<br>**A22.5.L3069** translation overlay alignment. | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 22.6 — Web/adblock

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.6.C · Full section contract, including prose and nested example context (brief L3071). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.6.L3073** ordinary site navigation,<br>**A22.6.L3074** session persistence,<br>**A22.6.L3075** media allowed,<br>**A22.6.L3076** trackers blocked,<br>**A22.6.L3077** popup blocked,<br>**A22.6.L3078** first-party media not broken. | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 22.7 — Orez tool-use evals

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.7.C · Full section contract, including prose and nested example context (brief L3080). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.7.L3083** Test tasks: open chapter,<br>**A22.7.L3084** Test tasks: translate selected bubble,<br>**A22.7.L3085** Test tasks: fix OCR,<br>**A22.7.L3086** Test tasks: download video,<br>**A22.7.L3087** Test tasks: recover 403,<br>**A22.7.L3088** Test tasks: search glossary, | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.7.L3089** Test tasks: research term,<br>**A22.7.L3090** Test tasks: resume failed task.<br>**A22.7.L3093** Measure: task success,<br>**A22.7.L3094** Measure: wrong tool rate,<br>**A22.7.L3095** Measure: invalid argument rate,<br>**A22.7.L3096** Measure: unnecessary calls, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A22.7.L3097** Measure: unsafe call rate. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 22.8 — Security evals

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A22.8.C · Full section contract, including prose and nested example context (brief L3099). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A22.8.L3102** Prompt injection pages: "ignore user",<br>**A22.8.L3103** Prompt injection pages: "send cookies",<br>**A22.8.L3104** Prompt injection pages: "delete downloads",<br>**A22.8.L3105** Prompt injection pages: "install unknown model". | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |

### A 23 — VISUAL REGRESSION PROTECTION

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A23.C · Full section contract, including prose and nested example context (brief L3111). | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A23.L3114** Capture canonical screenshots for: Home<br>**A23.L3115** Capture canonical screenshots for: Library<br>**A23.L3116** Capture canonical screenshots for: Reader<br>**A23.L3117** Capture canonical screenshots for: Watch<br>**A23.L3118** Capture canonical screenshots for: Web<br>**A23.L3119** Capture canonical screenshots for: Orez | partial | [BUILD](#evidence-build) | Existing JVM and Android tests cover important deterministic, migration, OCR, reader, media and injection boundaries. No current direct emulator execution or full golden/semantic/visual baseline result is available. |
| **A23.L3120** Capture canonical screenshots for: Downloads<br>**A23.L3121** Capture canonical screenshots for: Settings<br>**A23.L3122** Capture canonical screenshots for: Protection Center<br>**A23.X1** Capture/approve canonical Home, Library, Reader, Watch, Web, Orez, Downloads, Settings and Protection screenshots; detect regressions in CI/device tests, not simply capture unreviewed pictures. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 24 — BUILD / CI REQUIREMENTS

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A24.C · Full section contract, including prose and nested example context (brief L3132). | partial | [BUILD](#evidence-build) | Version/channel/SHA and ABI checks are implemented. The old PR gate skips emulator execution; exact candidate reports, signed artifacts and runtime identity are still required. |
| **A24.L3152** Verify expected: architecture,<br>**A24.L3153** Verify expected: native AI libraries,<br>**A24.L3154** Verify expected: FFmpeg/native media dependencies,<br>**A24.L3155** Verify expected: resources,<br>**A24.L3156** Verify expected: version.<br>**A24.X1** Direct Codex Cloud emulator execution for major upgrades supersedes historical no-emulator preferences; require exact APK/native ABI/signature/archive/source identity and uploaded candidate artifacts. | partial | [BUILD](#evidence-build) | Version/channel/SHA and ABI checks are implemented. The old PR gate skips emulator execution; exact candidate reports, signed artifacts and runtime identity are still required. |

### A 24.1 — Source identity

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A24.1.C · Full section contract, including prose and nested example context (brief L3160). | partial | [BUILD](#evidence-build) | Version/channel/SHA and ABI checks are implemented. The old PR gate skips emulator execution; exact candidate reports, signed artifacts and runtime identity are still required. |

### A 25 — RELEASE CHANNELS

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A25.C · Full section contract, including prose and nested example context (brief L3177). | partial | [BUILD](#evidence-build) | Current build is 2.3.0-preview, channel preview, code 9; no complete Stable/Preview/Experimental release/feature-flag policy exists. |
| **A25.L3180** Eventually: Stable<br>**A25.L3181** Eventually: Preview<br>**A25.L3182** Eventually: Experimental<br>**A25.L3189** Examples: Max Orez<br>**A25.L3190** Examples: audio transcription<br>**A25.L3191** Examples: experimental provider | partial | [BUILD](#evidence-build) | Current build is 2.3.0-preview, channel preview, code 9; no complete Stable/Preview/Experimental release/feature-flag policy exists. |
| **A25.L3192** Examples: panel-guided reading<br>**A25.L3193** Examples: neural inpainting<br>**A25.X1** Stable/Preview/Experimental channels and feature flags isolate Max, speech, experimental providers, panel guidance and neural inpainting from reliable daily builds. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 26 — IMPLEMENTATION PHASES

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A26.C · Full section contract, including prose and nested example context (brief L3197). | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A26.L3203** PHASE A: Protect and consolidate the mature 2.1 baseline: verify branch,<br>**A26.L3204** PHASE A: Protect and consolidate the mature 2.1 baseline: capture UI references,<br>**A26.L3205** PHASE A: Protect and consolidate the mature 2.1 baseline: ensure logo asset,<br>**A26.L3206** PHASE A: Protect and consolidate the mature 2.1 baseline: restore provenance,<br>**A26.L3207** PHASE A: Protect and consolidate the mature 2.1 baseline: CI artifact,<br>**A26.L3208** PHASE A: Protect and consolidate the mature 2.1 baseline: regression tests, | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A26.L3209** PHASE A: Protect and consolidate the mature 2.1 baseline: no UI regression.<br>**A26.L3213** PHASE B: New compact design system: theme tokens,<br>**A26.L3214** PHASE B: New compact design system: density,<br>**A26.L3215** PHASE B: New compact design system: Home,<br>**A26.L3216** PHASE B: New compact design system: bottom nav,<br>**A26.L3217** PHASE B: New compact design system: horizontal carousels, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3218** PHASE B: New compact design system: Library,<br>**A26.L3219** PHASE B: New compact design system: Watch,<br>**A26.L3220** PHASE B: New compact design system: Web,<br>**A26.L3221** PHASE B: New compact design system: Orez,<br>**A26.L3222** PHASE B: New compact design system: Downloads,<br>**A26.L3223** PHASE B: New compact design system: Settings. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3229** PHASE C: Shared resolver/session architecture: ContentResolver,<br>**A26.L3230** PHASE C: Shared resolver/session architecture: WebSession,<br>**A26.L3231** PHASE C: Shared resolver/session architecture: provider registry,<br>**A26.L3232** PHASE C: Shared resolver/session architecture: media/chapter routing.<br>**A26.L3236** PHASE D: Vision/translation upgrade: OCR fusion,<br>**A26.L3237** PHASE D: Vision/translation upgrade: vertical text, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3238** PHASE D: Vision/translation upgrade: bubble grouping,<br>**A26.L3239** PHASE D: Vision/translation upgrade: quality policies,<br>**A26.L3240** PHASE D: Vision/translation upgrade: reconstruction,<br>**A26.L3241** PHASE D: Vision/translation upgrade: translation memory,<br>**A26.L3242** PHASE D: Vision/translation upgrade: manual bubble editor.<br>**A26.L3246** PHASE E: Universal media/downloader: resolver paths, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3247** PHASE E: Universal media/downloader: session reuse,<br>**A26.L3248** PHASE E: Universal media/downloader: yt-dlp/provider adapters,<br>**A26.L3249** PHASE E: Universal media/downloader: HLS/DASH,<br>**A26.L3250** PHASE E: Universal media/downloader: split mux,<br>**A26.L3251** PHASE E: Universal media/downloader: resume,<br>**A26.L3252** PHASE E: Universal media/downloader: verification, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3253** PHASE E: Universal media/downloader: provider regression library.<br>**A26.L3257** PHASE F: Orez Runtime v1: tool registry,<br>**A26.L3258** PHASE F: Orez Runtime v1: context collector,<br>**A26.L3259** PHASE F: Orez Runtime v1: model router,<br>**A26.L3260** PHASE F: Orez Runtime v1: task state,<br>**A26.L3261** PHASE F: Orez Runtime v1: planner, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3262** PHASE F: Orez Runtime v1: policy,<br>**A26.L3263** PHASE F: Orez Runtime v1: executor,<br>**A26.L3264** PHASE F: Orez Runtime v1: event bus,<br>**A26.L3265** PHASE F: Orez Runtime v1: audit.<br>**A26.L3269** PHASE G: Orez Core/Max model system: Model Manager,<br>**A26.L3270** PHASE G: Orez Core/Max model system: large pack download, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3271** PHASE G: Orez Core/Max model system: local inference,<br>**A26.L3272** PHASE G: Orez Core/Max model system: hardware routing,<br>**A26.L3273** PHASE G: Orez Core/Max model system: model eval.<br>**A26.L3277** PHASE H: Orez Research + Browser Agent: web search,<br>**A26.L3278** PHASE H: Orez Research + Browser Agent: DOM tools,<br>**A26.L3279** PHASE H: Orez Research + Browser Agent: source retrieval, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3280** PHASE H: Orez Research + Browser Agent: injection defence,<br>**A26.L3281** PHASE H: Orez Research + Browser Agent: citations/evidence.<br>**A26.L3285** PHASE I: Training Lab: Python infrastructure,<br>**A26.L3286** PHASE I: Training Lab: synthetic traces,<br>**A26.L3287** PHASE I: Training Lab: evaluation suites,<br>**A26.L3288** PHASE I: Training Lab: fine-tuning experiments, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.L3289** PHASE I: Training Lab: model packaging.<br>**A26.L3293** PHASE J: Advanced multimodal: audio transcription,<br>**A26.L3294** PHASE J: Advanced multimodal: visual understanding,<br>**A26.L3295** PHASE J: Advanced multimodal: panel detection,<br>**A26.L3296** PHASE J: Advanced multimodal: inpainting model,<br>**A26.L3297** PHASE J: Advanced multimodal: semantic library search. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A26.X1** All phases A-J are sequencing only and remain required: preserve baseline/design/resolver/vision/media/runtime/models/research/lab/advanced multimodal. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 27 — FRESH CHAT OPERATING INSTRUCTIONS

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A27.C · Full section contract, including prose and nested example context (brief L3301). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A27.L3314** Step 2 / Report: current mature branch head,<br>**A27.L3315** Step 2 / Report: current recovery branch head,<br>**A27.L3316** Step 2 / Report: open PRs,<br>**A27.L3317** Step 2 / Report: workflow state,<br>**A27.L3318** Step 2 / Report: last successful APK artifact.<br>**A27.L3322** Step 3 / Inspect actual mature code for: Home, | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A27.L3323** Step 3 / Inspect actual mature code for: NavGraph,<br>**A27.L3324** Step 3 / Inspect actual mature code for: Reader,<br>**A27.L3325** Step 3 / Inspect actual mature code for: Library,<br>**A27.L3326** Step 3 / Inspect actual mature code for: Downloads,<br>**A27.L3327** Step 3 / Inspect actual mature code for: Web,<br>**A27.L3328** Step 3 / Inspect actual mature code for: Video, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A27.L3329** Step 3 / Inspect actual mature code for: Orez,<br>**A27.L3330** Step 3 / Inspect actual mature code for: Settings,<br>**A27.L3331** Step 3 / Inspect actual mature code for: AdBlock,<br>**A27.L3332** Step 3 / Inspect actual mature code for: OCR,<br>**A27.L3333** Step 3 / Inspect actual mature code for: Media resolver.<br>**A27.L3348** Step 7 / After every major change: compile, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A27.L3349** Step 7 / After every major change: tests,<br>**A27.L3350** Step 7 / After every major change: lint,<br>**A27.L3351** Step 7 / After every major change: artifact.<br>**A27.X1** Complete-read/live ancestry/source inspection precedes changes; preserve correct mature branch, coherent commits and artifact identity; major changes require compile/tests/lint/artifacts and actual emulator acceptance. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 28 — ENGINEERING STYLE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.C · Full section contract, including prose and nested example context (brief L3364). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 28.1 — Prefer architecture over hacks

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.1.C · Full section contract, including prose and nested example context (brief L3366). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 28.2 — Prefer robust state machines

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.2.C · Full section contract, including prose and nested example context (brief L3374). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 28.3 — Prefer bounded work

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.3.C · Full section contract, including prose and nested example context (brief L3380). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A28.3.L3383** Every loop: max retries,<br>**A28.3.L3384** Every loop: timeout,<br>**A28.3.L3385** Every loop: cancellation.<br>**A28.3.L3388** Every cache: size bound.<br>**A28.3.L3391** Every concurrency pool: limit.<br>**A28.3.X1** Every retry loop has a maximum, timeout and cancellation; caches and concurrency pools are bounded; observable errors identify actual stage and recovery. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 28.4 — Prefer observable failures

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.4.C · Full section contract, including prose and nested example context (brief L3393). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 28.5 — Do not confuse code quantity with intelligence

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A28.5.C · Full section contract, including prose and nested example context (brief L3399). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A28.5.L3406** But every large subsystem must have: purpose,<br>**A28.5.L3407** But every large subsystem must have: interfaces,<br>**A28.5.L3408** But every large subsystem must have: tests,<br>**A28.5.L3409** But every large subsystem must have: metrics. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |

### A 29 — ACCEPTANCE TARGETS

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A29.C · Full section contract, including prose and nested example context (brief L3415). | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A29.L3418** Reader: No chrome/ad images in chapter.<br>**A29.L3419** Reader: Long chapters load.<br>**A29.L3420** Reader: Saved position works.<br>**A29.L3421** Reader: RTL/LTR/vertical correct.<br>**A29.L3422** Reader: Offline chapters survive restart.<br>**A29.L3425** OCR: Multiple scripts. | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A29.L3426** OCR: Bubble grouping.<br>**A29.L3427** OCR: Vertical Japanese improved.<br>**A29.L3428** OCR: Weak regions retried.<br>**A29.L3429** OCR: No floating garbage regions.<br>**A29.L3432** Translation: Correct target language.<br>**A29.L3433** Translation: Natural dialogue. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3434** Translation: consistent names.<br>**A29.L3435** Translation: context-aware register.<br>**A29.L3436** Translation: no runaway output.<br>**A29.L3439** Reconstruction: original glyphs largely removed,<br>**A29.L3440** Reconstruction: no crude rectangles,<br>**A29.L3441** Reconstruction: text fitted, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3442** Reconstruction: overlay aligns.<br>**A29.L3445** Web: real browsing,<br>**A29.L3446** Web: login session,<br>**A29.L3447** Web: tabs/history/bookmarks eventually,<br>**A29.L3448** Web: content detection.<br>**A29.L3451** AdBlock: strong blocking, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3452** AdBlock: normal media works,<br>**A29.L3453** AdBlock: Protection Center reporting.<br>**A29.L3456** Video: HLS/DASH/direct,<br>**A29.L3457** Video: high-quality track selection,<br>**A29.L3458** Video: embedded subtitles,<br>**A29.L3459** Video: live translation, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3460** Video: stable gestures/player.<br>**A29.L3463** Downloader: generic + providers,<br>**A29.L3464** Downloader: best available,<br>**A29.L3465** Downloader: split AV,<br>**A29.L3466** Downloader: resume,<br>**A29.L3467** Downloader: refresh expired URLs, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3468** Downloader: verification.<br>**A29.L3471** Orez: knows current app context,<br>**A29.L3472** Orez: can call tools,<br>**A29.L3473** Orez: persistent autonomous tasks,<br>**A29.L3474** Orez: local models,<br>**A29.L3475** Orez: Hybrid research, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A29.L3476** Orez: memory,<br>**A29.L3477** Orez: safe execution,<br>**A29.L3478** Orez: measurable evals.<br>**A29.X1** Acceptance means actual real UI outputs, meaningful failure/recovery and quality tests; no runtime behavior has passed this audit. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 30 — PRODUCT EXPERIENCE EXAMPLE

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A30.C · Full section contract, including prose and nested example context (brief L3482). | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A30.L3487** MangaLens: classifies it,<br>**A30.L3488** MangaLens: loads through Web/session acquisition,<br>**A30.L3489** MangaLens: filters ads/page chrome,<br>**A30.L3490** MangaLens: discovers lazy pages,<br>**A30.L3491** MangaLens: opens Reader,<br>**A30.L3492** MangaLens: saves chapter. | partial | [BUILD](#evidence-build) | Historical phases/scenarios remain the full contract, not completed milestones. See section-level rows and ACCEPTANCE_MATRIX for precise implemented paths and missing runtime/quality coverage. |
| **A30.L3498** Orez: loads series memory,<br>**A30.L3499** Orez: analyses pages,<br>**A30.L3500** Orez: runs script-aware OCR,<br>**A30.L3501** Orez: groups bubbles,<br>**A30.L3502** Orez: translates with context,<br>**A30.L3503** Orez: verifies Hindi output, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A30.L3504** Orez: reconstructs source lettering,<br>**A30.L3505** Orez: publishes pages progressively,<br>**A30.L3506** Orez: checkpoints every page,<br>**A30.L3507** Orez: saves result.<br>**A30.L3512** MangaLens: resolves provider/page,<br>**A30.L3513** MangaLens: reuses logged-in web session if required, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A30.L3514** MangaLens: detects HLS/DASH/adaptive streams,<br>**A30.L3515** MangaLens: chooses best accessible compatible representation,<br>**A30.L3516** MangaLens: opens native player.<br>**A30.L3522** Orez/Subtitle Engine: uses embedded cues if available,<br>**A30.L3523** Orez/Subtitle Engine: otherwise visual OCR for burned-in subtitles,<br>**A30.L3524** Orez/Subtitle Engine: otherwise audio transcription when implemented, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A30.L3525** Orez/Subtitle Engine: keeps context,<br>**A30.L3526** Orez/Subtitle Engine: displays translated captions.<br>**A30.L3532** Downloader: resolves source,<br>**A30.L3533** Downloader: selects best video,<br>**A30.L3534** Downloader: selects audio,<br>**A30.L3535** Downloader: downloads efficiently, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A30.L3536** Downloader: refreshes signed URLs if necessary,<br>**A30.L3537** Downloader: muxes,<br>**A30.L3538** Downloader: verifies,<br>**A30.L3539** Downloader: stores in Library/Downloads.<br>**A30.X1** One integrated chapter-to-translated-offline flow and video-to-live-caption-to-verified-download flow must execute with actual source/session/context/output continuity. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 31 — FINAL NORTH-STAR DIRECTIVE TO FUTURE ENGINEERING AGENTS

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A31.C · Full section contract, including prose and nested example context (brief L3545). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A31.L3548** Do not downgrade MangaLens Next into: a toy reader,<br>**A31.L3549** Do not downgrade MangaLens Next into: a WebView wrapper,<br>**A31.L3550** Do not downgrade MangaLens Next into: a single-site downloader,<br>**A31.L3551** Do not downgrade MangaLens Next into: a simple OCR demo,<br>**A31.L3552** Do not downgrade MangaLens Next into: a local chatbot,<br>**A31.L3553** Do not downgrade MangaLens Next into: a screenshot-based UI mockup. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A31.L3572** Orez should combine: specialised models,<br>**A31.L3573** Orez should combine: local LLMs,<br>**A31.L3574** Orez should combine: optional large model packs,<br>**A31.L3575** Orez should combine: online research,<br>**A31.L3576** Orez should combine: RAG,<br>**A31.L3577** Orez should combine: tool use, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A31.L3578** Orez should combine: planner,<br>**A31.L3579** Orez should combine: persistent task state,<br>**A31.L3580** Orez should combine: application context,<br>**A31.L3581** Orez should combine: vision,<br>**A31.L3582** Orez should combine: speech,<br>**A31.L3583** Orez should combine: evaluator, | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A31.L3584** Orez should combine: recovery,<br>**A31.L3585** Orez should combine: permissions,<br>**A31.L3586** Orez should combine: security.<br>**A31.X1** Protect the mature integrated app, approved logo, restrained native UI, broad accessible non-DRM media and independent safe Orez orchestration; no paid dependencies or unmeasured frontier parity. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

### A 32 — QUICK CONTINUITY SUMMARY

| Requirement IDs and complete clause text | Status | Source / tests | Audit / remaining acceptance |
|---|---|---|---|
| A32.C · Full section contract, including prose and nested example context (brief L3598). | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A32.L3602** If a future agent reads nothing else, remember: Repo: `RezoxNemesis/MangaLens`<br>**A32.L3603** If a future agent reads nothing else, remember: Mature baseline: `engineering/mangalens-production` / PR #6, live-query before editing.<br>**A32.L3604** If a future agent reads nothing else, remember: Old `main` caused a serious UI/function regression and must not be used blindly.<br>**A32.L3605** If a future agent reads nothing else, remember: Regression PR #11 was closed.<br>**A32.L3606** If a future agent reads nothing else, remember: Recovery branch was created from mature 2.1.<br>**A32.L3607** If a future agent reads nothing else, remember: Preserve mature UI/features. | partial | [BUILD](#evidence-build) | Correct mature continuation is specified; full current-source runtime, recovery, bounded-work and artifact evidence must accompany changes. Historical repository snapshots are not current validation. |
| **A32.L3608** If a future agent reads nothing else, remember: New UI: restrained dark graphite/silver/blue, compact, horizontal slides, systematic spacing.<br>**A32.L3609** If a future agent reads nothing else, remember: Approved silver "M" logo is a direct production asset.<br>**A32.L3610** If a future agent reads nothing else, remember: Generated UI images are references, not screenshot UI.<br>**A32.L3611** If a future agent reads nothing else, remember: Reader + Web + Video + Downloader + OCR + Orez are one integrated platform.<br>**A32.L3612** If a future agent reads nothing else, remember: Universal resolver and shared session layer are core architecture.<br>**A32.L3613** If a future agent reads nothing else, remember: Downloader must be broad, high-quality, adaptive, resumable and verified. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A32.L3614** If a future agent reads nothing else, remember: Orez is the highest-priority future subsystem.<br>**A32.L3615** If a future agent reads nothing else, remember: Orez gets models + tools + memory + web research + planner + evaluator + security + persistent tasks.<br>**A32.L3616** If a future agent reads nothing else, remember: Use Python heavily in separate Orez training/eval tooling.<br>**A32.L3617** If a future agent reads nothing else, remember: Keep Python out of Android hot paths unless measured need.<br>**A32.L3618** If a future agent reads nothing else, remember: Large AI packs download after install; do not bloat APK.<br>**A32.L3619** If a future agent reads nothing else, remember: Manual JSON/pack import is only advanced fallback. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |
| **A32.L3620** If a future agent reads nothing else, remember: Default experience should not require paid services.<br>**A32.L3621** If a future agent reads nothing else, remember: CI green != phone-tested.<br>**A32.L3622** If a future agent reads nothing else, remember: Every candidate APK needs source SHA + checksum + artifact.<br>**A32.L3623** If a future agent reads nothing else, remember: Do not destroy good systems while improving other ones. | partial | [BUILD](#evidence-build) | Same section audit applies; full output/UI/quality acceptance remains required. |

## Current mission additions and precedence (Sections 1–13)

These clauses add to or supersede the historical appendix. Policies are ongoing engineering obligations, not claims that a shipped app has passed acceptance.

### Mission 1

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M1.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L35 · Treat “continue,” “implement,” “improve” and “make it work” as requests to execute. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L36 · Do not stop at a plan, acknowledgment, scaffold, UI mockup or a single happy path. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L37 · Carry each selected upgrade through meaningful validation and a reviewable result. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L38 · Choose routine technical details yourself. Reuse preferences and prior authorization. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L39 · Do not ask “Should I continue?”, “Can I test?”, “Which file should I read?” or “Should I fix this error?” when the answer follows from this mission. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L41 · Diagnose failures, repair them and rerun the relevant checks without repeated approval. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L42 · Work on independent authorized tasks while waiting for builds or a genuinely necessary answer. Avoid repeatedly polling, regenerating plans or opening approval loops. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L44 · Provide brief progress updates describing findings and concrete progress, roughly once a minute during active work. Updates are not permission requests. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L46 · Ask only when essential information cannot be discovered, or when an action is destructive, changes accounts, sends messages, exposes private data, incurs cost, requires unavailable credentials, or has an unresolved material product tradeoff. Batch necessary questions and explain the exact blocker. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L50 · For external release, merge or production deployment, complete implementation, tests and a concrete candidate first. Use existing authorization if it covers that action; otherwise approval is the final step, not an excuse to defer development. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L53 · Use specialized parallel agents when the environment permits and their tasks are genuinely independent. This document authorizes that future workflow; avoid conflicting edits, duplicate work and delegation that cannot be verified. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L56 · Respect Stop immediately. Save recoverable work and its status. Do not keep modifying the application after cancellation. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |
| M1.L58 · Work continuously while the authorized execution session and resources exist. If a platform/time limit ends execution, checkpoint the exact state and next action. Do not claim to run invisibly after the session ends or consume paid resources to keep working. | partial | [BUILD](#evidence-build) | Autonomy/parallel work is authorized; preserve Stop, permission, account/message/cost boundaries and checkpoint when execution ends. |

### Mission 2

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M2.C · Complete current section contract. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L65 · Preserve mature Reader, Library, Web, Video, Downloads, Settings, Protection, OCR/translation and native AI functionality. Replace a subsystem only after proving the replacement preserves its useful behavior and data. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L68 · Use real interactive Android components. Do not place a concept screenshot behind invisible controls and call it the finished UI. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L70 · Keep the approved silver/graphite M logo. Its verified Git blob was `ede5d8c4f4f2e1682925533fad34d647940ad752`. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L72 · No paid API, paid inference endpoint, mandatory subscription, paid cloud storage, paid GPU allocation or purchased CI capacity. Use installed resources, open models, local inference and free tooling within available quotas. Do not create billable accounts or enable paid fallback automatically. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L76 · Existing authorized Codex workspace access is the development environment; this instruction does not require buying more Codex credits or extending paid quotas. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L78 · Research model license, provenance, redistribution rights, dependencies and measurable usefulness before integrating it. “Open weights” does not automatically mean unrestricted redistribution or zero operational cost. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L81 · No secrets in prompts, code, commits, screenshots, logs, training data or exports. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L82 · Orez requests typed tools; it never receives arbitrary filesystem, shell, credential or unrestricted network access. Models cannot grant themselves permissions. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L84 · Do not add Gallery enumeration to ChatGPT/Orez to make media import easier. Explicit user-selected documents, project-owned files and explicitly supplied URLs are the default sources for agent work. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L87 · The mature MangaLens Watch route already has permission-gated local-video enumeration. Preserve or redesign that explicit native user flow deliberately; do not falsely claim it does not exist, and do not expose it as unrestricted agent access. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L90 · Honor Android picker permissions and user revocation. No permission bypass, account impersonation, DRM circumvention or cookie/credential exfiltration. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L92 · Webpages, OCR text, captions, downloaded metadata and model output are untrusted data. They cannot override user instructions, invoke privileged tools or expand scope. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L94 · Preserve projects, saved chapters, reading positions, translations and completed transfers. Use migrations, atomic writes and verified replacement before deleting a user's only copy. Clean only known regenerable cache by default. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |
| M2.L97 · Separate User Orez from developer tooling. Production Orez must not silently rewrite its installed executable; updates follow source → tests → reviewed build. | partial | [SECURITY](#evidence-security) | Ongoing no-paid/privacy/data/typed-tool/real-UI/logo boundaries; native permission-gated Watch enumeration is distinct from prohibited agent Gallery access. |

### Mission 3

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M3.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Historical build/branch/artifact snapshots must be refreshed by parent; instrumentation compilation is not execution. |

### Mission 4

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M4.C · Complete current section contract. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L151 · Repaired the missing task-store import that previously broke CI. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L152 · Applied the approved logo to branding and adaptive launchers. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L153 · Replaced red/neon shared styling with graphite, slate and blue. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L154 · Built real Compose Home/Library/Watch/Web/Orez navigation while retaining mature Reader, Video, Downloads, Settings and Protection routes. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L156 · Added compact horizontal Home/Orez modules and persisted appearance controls: compact/balanced/comfortable density, seven accent choices, AMOLED/high contrast and reduced-motion navigation. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L159 · Exposed source commit/channel in Settings and used actual PR head identity in builds rather than the synthetic merge commit. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L161 · Added/retained explicit Android share handling for links, images, PDF, ZIP/CBZ and video content URIs. Native device-video scans run off the UI thread and handle permission denial. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L164 · Web has validated address/search entry, active-page context synchronization and app back navigation. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L169 · Both draft and refined translations pass quality checks; stored translations are revalidated instead of bypassing the current policy. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L171 · Hindi output containing copied English clauses such as “BEATEN UP” is rejected. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L172 · English OCR line breaks no longer bypass dialogue/idiom normalization. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L173 · Adjacent OCR word merging retains both words, geometry, confidence and input immutability. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L175 · Failed retries remove stale invalid overlays; successful neighboring bubbles remain visible. Rejected translations preserve original source text. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L177 · Terminal punctuation handling no longer turns a final statement into a question merely because a question appeared earlier in the source. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L179 · Two-pass rendering releases background bitmaps after erasure and retains placement metadata instead of retaining every patch until all text is drawn. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L181 · Lettering uses the original OCR paper reference, filters reconstruction samples and preserves paper pixels without retaining antialiased source glyphs. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L183 · OCR tile deduplication is followed by page-space balloon grouping so tile boundaries can no longer prevent otherwise compatible blocks from merging. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L185 · These structural checks do not prove semantic translation correctness. Hindi meaning, anger, register, names and other languages still need held-out evaluation. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L190 · Explicit Web/Video/Reader choice survives URL changes and is passed into ingestion/navigation. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L192 · Bare creator/video searches trigger discovery; unspecified platforms use the existing YouTube search path. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L194 · Website/channel landing pages are treated as source links without a misleading Play action. Source links open inside MangaLens Web and are labeled WEB SOURCES. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L196 · Direct downloads verify real video/audio samples and the tail of both declared and extractor-provided duration before publication. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L198 · Request metadata and source duration survive interruption through bounded, atomic persistence. Legitimate short media remains allowed; no arbitrary minimum file size was introduced. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L204 · Runtime-owned tool descriptors validate arguments, routes, capabilities and risk. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L205 · Optional installed Lite/Core models can suggest one structured tool for a direct action request. Invalid suggestions fall back to conversation, without granting new permissions or changing an Open request into a Download request. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L208 · Orez gets active-chapter availability independently of whether translated text already exists. Requested translation language survives routing. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L210 · Persisted task plans have decoding and terminal handoff states. Navigation is distinguished from actual work completion. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L212 · Model transfers check disk headroom, verify hashes on IO, preserve paused partials, resume completed partial files locally and atomically promote verified files. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L214 · WorkManager model state is reconciled and failures are visible to Orez. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L215 · Chat role delimiters in source material are escaped before local inference. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L223 · Validate the whole plan before any transfer: unknown operations, invalid later URLs, conflicting quality requests and oversized batches fail before effects. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L225 · Checkpoint each step before dispatch. Retain structured verified outputs. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L226 · Stable transfer IDs survive interruption between native enqueue and journal update. Resume observes the same transfer instead of creating duplicates. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L228 · Skip already completed steps; retry only unfinished work. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L229 · Native mature download workers continue owning extraction, large-file transfer, resume validators, source refresh, separate audio, muxing and publication checks. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L231 · Direct-file results require readable published media; adaptive results retain the existing Media3 cache representation, which is not a universal standalone file. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L233 · Paused tasks become WAITING. Failed tasks retain completed steps and errors. Task cards show verified step counts and resume controls. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L235 · Native transfer Cancel/Remove cancels its owning plan before row deletion; cancellation guards prevent stale workers from resurrecting it. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L237 · Dismiss Task stops monitoring/future steps while the current native transfer stays available in Downloads. Do not silently change this behavior without aligning UI, runtime and tests. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L240 · Verified completion remains authoritative if chat message delivery fails. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L241 · Journal schema remains compatible with older entries lacking output metadata. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L245 · `0a7feb503fa0b199d6d5153078a0f18ecebed87e`: checkpointed batch execution/resume. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L246 · `c34f7caafb445f871a1f9d1aaed60db66cb4a355`: native cancellation/removal propagation. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |
| M4.L247 · `b08b9ced12f08cf980194afb7d940bd24c8950c6`: preserve verified completion on chat failure. | implemented/unverified | [TASKS](#evidence-tasks) | Baseline source implements these dated increments; do not infer fresh runtime or semantic acceptance from the supplied inventory. |

### Mission 5

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M5.C · Complete current section contract. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L258 · `1000067087_720p.mp4`: approximately 133.4 seconds, reader/translation behavior. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L259 · `1000067086_720p.mp4`: approximately 43.5 seconds, online-video resolution/playback. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L273 · `ChapterTranslationStore.kt`: AtomicFile task journal, per-page signatures, generation tokens, bounded metadata, private managed source paths and saved lettering metadata. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L276 · `ChapterTranslationWorker.kt`: foreground WorkManager chapter processing, sequential pages, disk-backed cleaned PNG surfaces, successful-bubble retention, source checks and cancellation fencing. A serialized chapter compute lane is intended to limit simultaneous heavy work. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L280 · `EnglishDialoguePolicy.kt`: lexical English hints for short clauses without classifying every Latin-script name or foreign phrase as English. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L282 · ViewModel/reader integration: restore saved results, show processed-page counts, retain Original and scalable lettering while loading backgrounds through Coil. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L284 · Promo-page classification: preserve substantial story dialogue sharing a slice with a promotional footer. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L286 · OCR memory changes: open and close script recognizers sequentially; reduce the page decode budget under reported memory pressure. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L288 · Capture OCR/refinement options with a chapter task to avoid mixed configuration. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L289 · Playback: try installed extraction before attempting updates; proposed 45-second static-resolution coroutine budget and Cancel/Back/Open source controls. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L291 · Added/updated policy and Android journal/recovery tests; none of these prototype tests have run yet. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L296 · Compile it against a complete checkout of the verified source. Fix actual failures. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L297 · Review cancellation, rapid pause/resume, same-task replacement, chapter switching, language/style changes, configuration identity and generation fences. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L299 · Confirm global scheduling cannot cancel an unrelated newer task or misreport an old task's errors in Video/Web screens. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L301 · Verify AtomicFile crash recovery, source-change invalidation, missing/corrupt output handling, journal-size limits and safe cleanup of orphaned intermediates. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L303 · Exercise pause at a page boundary and during OCR; preserve completed pages and retry the interrupted page. Verify native callbacks cannot access prematurely recycled bitmaps after cancellation. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L306 · Check saved lettering geometry, visible-page loading, original comparison, text scaling and all reading modes against the existing implementation. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L308 · Confirm partial translations are labeled correctly and quality errors do not mark empty or corrupt work as completed. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L310 · Ensure a coroutine timeout actually bounds native extraction; subprocess/update behavior must not ignore cancellation and continue indefinitely. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L312 · Run the real emulator suite, inspect screenshots/logcat and perform controlled large-chapter, low-memory and process-restart tests. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L314 · Decide whether to finish this design or replace defective portions with a stronger implementation that preserves its intended behavior. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L325 · Working source: `/workspace/MangaLens` — partial checkout with local modifications. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L326 · Full earlier blueprint: `/workspace/MangaLens-continuity/MangaLens_Next_Master_Blueprint_and_Orez_Continuity.md`. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L327 · Verified earlier result: `/workspace/MangaLens-continuity/orez-execution-manifest.json`. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L328 · Prototype list: `/workspace/recordings-round2-changes.json` — 18 changed paths. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L329 · Older prototype manifest: `/workspace/MangaLens-continuity/recordings-round2-in-progress.json`. Its 11-file hashes and pending list were recorded before subsequent local edits; use the fresh inventory appended to this file rather than assuming it is current. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L332 · Approved logo: `/workspace/MangaLens-continuity/assets/APPROVED_MangaLens_Logo.png`. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L333 · Approved UI reference: `/workspace/MangaLens-continuity/references/APPROVED_UI_DIRECTION_REFERENCE.png`. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |
| M5.L334 · Reviewed recordings: `/workspace/MangaLens-recordings`, with round 2 in `new/`. | blocked | [TRANSLATION](#evidence-translation) | Stopped prototype and private recordings are unavailable here; source-identical recovery is blocked. A reviewed new implementation from baseline is authorized. |

### Mission 6

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M6.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Full application scope is represented by the Appendix A contract; all areas still need final acceptance. |

### Mission 7

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M7.C · Complete current section contract. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L387 · Context collector: current chapter/page/panel, browser source, playback state, selected media, task, permissions, model availability and device budget. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L389 · Intent classifier and planner: deterministic routing for simple actions; constrained model planning for complex tasks; explicit user objectives. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L391 · Tool registry and policy: typed inputs/outputs, preconditions, trusted risk, timeouts, cancellation, idempotency, private-data scope and completion predicates. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L393 · Durable executor: plan/DAG, dependency states, checkpoints, output identities, retries, versioned serialization, partial invalidation and crash-safe replay. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L395 · Specialist router: select the smallest adequate installed model/provider for reasoning, OCR, translation, embeddings, reranking, speech, vision or inpainting. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L397 · Memory: separate session, preferences, series glossary, corrections, knowledge retrieval and task state. Bound context and make memories inspectable/removable. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L399 · Research agent: source-tagged evidence, freshness, citations and restricted browser tools. Never use a webpage as instruction authority. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L401 · Evaluator/critic: deterministic checks first, semantic models only when needed; accept real output evidence, not a model's own “done” message. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L403 · Recovery: classify faults, select a different justified strategy and retain successful work. Bound retries; never loop the same failing action indefinitely. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L405 · Event bus and scheduler: observable state changes, priorities, background continuation and playback/UI protection. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L407 · Multimodal interaction: understand explicit images/panels/screenshots, accept voice commands, transcribe/translate speech and provide optional offline speech output where suitable free licensed providers exist. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L410 · Evaluation and model distribution: measured routing, validated pack catalog, integrity/signature/compatibility tests, atomic activation and rollback. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L421 · Model size alone is not intelligence. Compare licensed candidates on the same held-out tasks and actual device resource limits. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L423 · Allow optional stronger packs on capable devices, but keep the default APK practical. Large weights live outside the APK in verified managed packs. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L425 · Add embeddings/rerankers, vision, speech and translation providers independently rather than forcing the LLM to do every task. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L427 · Prefer memory mapping, quantization, model swapping and selective high-resolution passes; storage does not remove compute requirements. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L429 · Keep fast/Balanced/Maximum modes honest about latency, quality and resources. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L430 · Fine-tune only when suitable licensed data and free available compute support it. Record dataset/model hashes, splits, seeds, quantization and before/after metrics. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L432 · Keep private media/corrections local unless the user explicitly authorizes an export. Do not secretly upload training data. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |
| M7.L434 · Cloud-connected research can enhance Orez; it must not make core offline reading, installed inference or already queued local work dependent on paid services. | partial | [AGENT](#evidence-agent) | Independent local-first architecture exists in part; DAG/providers/context/memory/specialists/critic/recovery/events/multimodal/pack evaluation remain incomplete. |

### Mission 8

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M8.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L450 · Inspect the selected cloud environment, installed JDK/Gradle/SDK, storage, memory, `adb`, emulator binaries/system images, KVM availability and permissions. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L452 · Obtain a complete correct source checkout. Preserve prototypes/user changes. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L453 · Install only necessary free SDK/build/emulator components through permitted mechanisms. Use existing Android/JDK versions where compatible and record them. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L455 · Prefer a hardware-accelerated headless x86_64 AVD matching the app's native ABI. Check `/dev/kvm`; do not infer acceleration from the host architecture alone. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L457 · If acceleration is unavailable, evaluate a software/headless emulator within realistic time/resource limits. Use free authorized CI runners for complementary emulator jobs if available; do not purchase runner/GPU capacity. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L460 · Run a primary target-SDK image and compatibility coverage on the minimum or a representative older supported API. Validate native phone ARM64 packaging too. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L462 · Put repeatable launch/install/test/capture scripts in the repository. Do not rely on undocumented interactive setup in one terminal. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L488 · Use appropriate Android instrumentation, UIAutomator/Espresso/Compose tests and `adb` actions. Test real UI state transitions and artifacts, not just view presence. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L490 · Import user-supplied regression images/recordings through permitted explicit flows. Also generate reproducible local manga/media/audio fixtures. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L492 · Inspect screenshots/contact sheets and logcat for each core screen after major navigation/UI/runtime upgrades. Capture failure videos when a static image is insufficient. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L494 · Verify output content: readable pages, reconstructed bubbles, meaning/register, actual media samples, audio tracks, duration, subtitle timestamps and saved files. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L496 · Exercise the exact shipped UI action paths; invoking a worker directly does not prove the button/navigation route starts it correctly. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L498 · Use controlled local servers for deterministic transfer/web fixtures. Keep genuine provider smoke tests distinct from mocks and handle site changes honestly. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L500 · Test real installed inference for representative feasible tasks. Mocked model output is useful for fault injection but is not evidence of model quality. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L547 · Exact source SHA, dirty-source status if applicable, version/channel and CI run. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L548 · Build/test reports, failure logs, lint results, native ABI/archive/signature checks. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L549 · Emulator API/ABI/device profile, acceleration mode, installed APK SHA-256 and runner. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L550 · Feature/fixture matrix with passed/failed/blocked statuses and reasons. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L551 · Screenshots/UI recordings and sampled output artifacts where appropriate. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L552 · Measured startup, memory, processing, transfer/playback and inference timing. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L553 · Model/provider versions, hashes and licenses for the tested paths. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |
| M8.L554 · Crash/ANR findings and a concise explanation of remaining hardware-only coverage. | partial | [BUILD](#evidence-build) | Direct major-upgrade emulator/core regression/output-quality/pressure/restart evidence is mandatory and not supplied by this audit. |

### Mission 9

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M9.C · Complete current section contract. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L567 · Android integration: Kotlin/Java, Compose, Room/SQLite, appropriate foreground services/WorkManager, lifecycle-aware state and native Media3 paths. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L569 · Native inference: measured C++/NDK or other suitable runtime where it improves compute/memory. Do not rewrite working subsystems just to use a “stronger language.” | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L571 · Python belongs in evaluation, fixtures, datasets, model conversion and training tooling, not arbitrary Android hot paths without a measured reason. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L573 · Serialize durable work with versioned schemas, stable IDs, explicit dependencies, preconditions, retries and verifiable result artifacts. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L575 · Separate interactive UI from heavy compute ownership. Evaluate isolated-process inference services where native faults could kill the editor/reader process. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L577 · Use bounded frames/tiles/windows and disk-backed intermediates. Release models, bitmaps and buffers when the phase ends; avoid all-chapter bitmap residency. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L579 · Adaptive resource governance uses thermal state, sustained duration, battery, available memory and recent load. Reduce workload at elevated conditions; reserve hard pauses for justified critical pressure and use hysteresis. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L582 · Make migrations and corruption recovery explicit. Never delete a database to make a new schema compile. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L584 · Model catalogs and provider capabilities should be versioned independently of APK UI, with tested compatibility and rollback. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L586 · Large imports/downloads should stream and resume. Test meaningful sizes such as 10 MB, 100 MB and 1 GB when free workspace storage permits; avoid loading entire files into memory. Keep justified image/archive security limits and document them. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L589 · Use source-context-aware session headers, refresh signed links appropriately, preserve legitimate short content and verify duration/audio before publishing. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L591 · Prefer generic provider interfaces to brittle site-specific patches, while maintaining tested provider-specific behaviors when necessary. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |
| M9.L593 · Record failures in useful stages. A spinner without cancellation, reason or eventual bounded outcome is not a sufficient long-running UX. | partial | [RESOURCES](#evidence-resources) | Existing Kotlin/Room/native/WorkManager and bounded IO paths need fuller durable schemas/isolated compute/governor/compatibility migrations and measured limits. |

### Mission 10

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M10.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Milestones are priorities, not complete claims or approval checkpoints. |

### Mission 11

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M11.C · Complete current section contract. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L670 · Its real UI/runtime path works and produces the requested verifiable result. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L671 · Happy paths, important failures, cancellation and recovery are tested. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L672 · Persistence/permissions/security and mature neighboring features remain intact. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L673 · Required JVM/lint/build/package and direct emulator gates pass. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L674 · Output quality matches the acceptance criteria, not merely valid serialization. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L675 · Source, artifact and test evidence are traceable. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |
| M11.L676 · Remaining physical-device or provider limitations are explicitly labeled. | partial | [LAB](#evidence-lab) | Metrics and completion gates require real output evidence, held-out quality and traceable source/runtime artifacts. |

### Mission 12

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M12.C · Complete current section contract. | partial | [BUILD](#evidence-build) | Parent is reconciling correct source/setup; this audit produces the requirements ledger. Baseline and reliability-slice runtime gates remain pending. |

### Mission 13

| Requirement / brief line | Status | Source / tests | Audit / blocker |
|---|---|---|---|
| M13.C · Complete current section contract. | implemented/unverified | [BUILD](#evidence-build) | Current autonomy/emulator/no-paid/prototype labels supersede historical conflicting preferences; all historical product capabilities remain in scope. |

### Mission 6 complete application areas

| Required area and full behavior | Status | Acceptance |
|---|---|---|
| **Identity/UI** · Approved logo; compact native design; density/theme/accessibility; customizable modules and navigation; complete loading/error/empty states. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Shared orchestration** · Typed content resolver, explicit mode retention, consistent sessions, source identity and context across Reader/Web/Video/Orez. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Reader** · Images, manga/manhwa/webtoons, CBZ/ZIP/PDF; vertical/LTR/RTL reading; zoom, gestures, position restore, bookmarks, offline and panel-guided reading. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Acquisition** · Robust static/rendered extraction, lazy pages, session headers, chapter catalogs/next chapters, conservative filtering and isolated page retries. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Library** · Durable series/chapters/covers/progress/collections/status/notes/translations/glossaries; semantic and OCR-text search; safe import/export/offline. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Vision** · Script-aware OCR, region-level fusion, crop-specific retry, reading order, vertical Japanese, speech/caption/SFX segmentation and diagnostics. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Translation** · Meaning-aware multilingual localization, contextual register, series terminology, style profiles, multiple candidates, quality/semantic evaluation and corrections. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Reconstruction** · Glyph masks and surface recovery; difficult-artwork inpainting providers; shape-aware fitted lettering; original/translated/compare modes. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Orez runtime** · Controlled multi-step planning, persistent execution, evaluator, recovery, event bus, scheduling, memory, tool schemas and result evidence. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Orez models** · Evaluated Lite/Core/Max and specialist packs, hardware routing, transactional installation, checksums/signatures, rollback and no paid dependency. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Research/browser** · Search-provider abstraction, contextual source extraction, citations, controlled DOM tools, real browser context and injection defenses. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Media/player** · Broad accessible formats/providers, native playback, quality/tracks/subtitles, session handoff, split-stream audio and resilient playback recovery. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Captions/speech** · Embedded/burned-in captions, local ASR, VAD, live and full-video subtitle jobs, translation, timing, dual captions and real export. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Downloads** · Direct/adaptive transfers, quality ceilings, stable IDs, resume, mux, expired-source repair, integrity/duration/audio checks, publication and cancellation. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Protection** · Mature network/cosmetic/navigation protections, media-safe blocking, per-site controls, diagnostics and regression fixtures. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Android** · Shares/pickers/deep links/widgets, foreground work/notifications, lifecycle recovery, permission denial and accessible interaction. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Resources** · Disk-backed active workspace, bounded image/audio/frame windows, model unloading, adaptive memory/thermal/battery scheduling and cache retention. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **AI lab** · Licensed datasets, corrections, tool traces, LoRA/quantization experiments where resources permit, held-out evals, reproducible model reports. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Security** · Trusted policy boundaries, private scoped data, secure model supply chain, safe URLs/imports, no arbitrary agent privilege or silent uploads. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |

### Mission 8 mandatory direct emulator areas

| Required area and full behavior | Status | Acceptance |
|---|---|---|
| **Home/navigation/UI** · Open every retained route; scroll/tap modules; theme/density/accent/reduced motion; large fonts/rotation/accessibility; loading/error/empty states. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Reader/Library** · Explicit image/CBZ/PDF fixtures; vertical/LTR/RTL switching; zoom/pan; positions/bookmarks; close/reopen; offline; migration/retention. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Acquisition** · Static and lazy chapter fixtures; page ordering; source headers; malformed/missing page isolation; next-chapter navigation; no automatic fake CAPTCHA requirement. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **OCR** · Latin/Hindi/Japanese/Korean/Chinese; mixed scripts; vertical text; low contrast/small glyphs; tile borders; correct geometry/reading order; real sample regression. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Translation/lettering** · Requested language/style; names/register/idioms; partial failure; no source leakage accepted as translated; original toggle/text size; preserve artwork; fit text; retry affected regions/pages. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Durability** · Background/foreground; activity recreation; simulated process death/relaunch; worker recovery; pause/resume/cancel; rapid repeated commands; completed-output retention. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Web/protection** · Address/search/back; sessions; chapters/media detection; navigation/ad/cosmetic protections; allowed real media; injection fixtures; safe browser actions. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Player** · Local/online fixtures; split audio/video; rotate/fullscreen; tracks/quality/seeking; subtitles; error recovery; accessible controls; cancel resolution. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Captions/speech** · Embedded and burned-in cues; VAD/ASR real speech sample; timing/translation; long-file windows; background subtitle job; readable export; model-unavailable UX. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Downloads** · Direct/HLS/DASH; audio/mux; requested quality; known duration/tail; interrupted/retried transfer; expiry/403; chunked body; disk-full; native and Orez cancellation; actual offline play. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Orez** · Simple actions, explicit eight-URL batches, durable replay, failed/paused step resume, rejection before effects, contextual chapter commands, tool-result verification and honest task states. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Models** · Install/pause/resume; checksum mismatch; insufficient storage; incompatible ABI; corrupted pack; atomic promotion; rollback; real inference; missing-model state. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Permissions/security** · Denial/revocation; explicit picker access; private-path escape; archive traversal; malicious URLs/redirects; no agent Gallery access; no logged secrets or unintended uploads. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |
| **Resource pressure** · Large images/chapters/audio/video; slow IO/network; bounded memory; responsive navigation during AI; low storage; lifecycle failures; recorded performance baseline. | partial | [Acceptance matrix](ACCEPTANCE_MATRIX.md); no direct runtime pass recorded by this audit. |

## Appendix B — all 18 stopped prototype inventory entries

Historical checksums are copied from the supplied contract. Current comparison was computed during this source audit; concurrent changes may alter current files. A mismatch or absent file means the *historical prototype version* is unavailable, even if a mature baseline or new implementation uses the same path. This blocks byte-identical restoration, not implementation from the verified mature baseline.

| Original prototype path | Historical SHA-256 | Current audit identity | Status / next action |
|---|---|---|---|
| `app/src/main/java/com/mangalens/core/translation/ChapterTranslationStore.kt` | `9787ce1c558909fbf17679b349f8331851aaba3b7741a4895053a8e939f66abd` | Absent from supplied/restored baseline | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/core/translation/ChapterTranslationWorker.kt` | `f79b25af6abd843c35e7aaecda4ffe6435b2796fef6e960e9f9d5b6c92acba4c` | Absent from supplied/restored baseline | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/core/translation/EnglishDialoguePolicy.kt` | `18a63c070a5e2f429e8c0283cc18b7d423af0b9a3f1983d63707eb77f6a755dc` | `463eb79ae172b694d4a7e4c96b693080dfcc3c205b2ab2104cce59ccf0cab5e9` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/ui/MangaLensViewModel.kt` | `e3795134113b5371f03adcdf4f91f39db88789f78a0968fd86ce7b84be38448d` | `6d250bab06e65adeed3ff169fa9ddcaee116ebe76bc60a89a30e4c1edd144874` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/ui/MangaLensNavGraph.kt` | `c645c4f269d33c1f656610419033de7a7eed15229d02e2c60a32fc425497810f` | `e95b314eff5eebbbc23e4a385ddcc5f5ae421d3bc6e987535be198861eb0b64d` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/ui/reader/MangaTranslationOverlay.kt` | `306f7ff1cc5439985c93a0739efac0ffe08e0ebe700e0872e6672c064ef2e796` | `735563cb066df043db006635c899acbe23694eb162f633ef378a202819ef467b` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/ui/reader/MangaContinuousReader.kt` | `efad3fce7c5add03675776fbbcca7d78bf0064e2f59cedad4ea799a64ef36672` | `ef29a0f593d923afbf5cb831baa5a2c7dc087c458f3726ceb60087c352c34bca` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/core/reader/ReaderPromoPolicy.kt` | `81a9a493f1ff2249d34bfae598548264aa76ccd923fbc0b0d9d74117342c23f5` | `ca8aef0aa6767ad4de430372cf62182239fcf8f47d03222037078aaaedd2dfb6` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/engine/AdvancedTranslationEngine.kt` | `9ce36b751fa36defd55f0c951bbbc0edd8535457ac6e8517a80b937040a0f169` | `9af60463093613c382a4ff1159f1bc074c728a5d61cb5e62463e9c7862773ea4` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/core/translation/TranslationOrezRefiner.kt` | `77eb891bf6ffd583e257253c4a9f6b09dab2f8b7475e22ccbdbdae228a46ca41` | `41fa631dd79859a475525456c221b9d39eb4f3f978476890e60f5fd6fbfefc03` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/download/SiteMediaExtractor.kt` | `1f11b575a5d15c6620a5e72369f888ed315c9d40094dbbc091515ff2add149b8` | `96aa22e5857e905744328b0c69c99e5be78aed62766f80f4d859bf496fb7f73c` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/main/java/com/mangalens/MainActivity.kt` | `780b6699117cabb5bfeab8e7f5ad8257f1f4212cf4510b8580b77dba4d2b8ed1` | `3e8fc9758ce11bf9999445f2ce2d14e679c74fd37f719cca8ae906ae0c7f95b1` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/test/java/com/mangalens/core/reader/ReaderPromoPolicyTest.kt` | `e2219e8248f4fd6777a8e04f1568b262e0908d8168349a8b046ea9b345689494` | `f0b842ddff847c150a4d545f03d1ef30a546e709cd15b07e2fab7ac5a9c839bc` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/test/java/com/mangalens/core/translation/EnglishDialoguePolicyTest.kt` | `e4fce4fc7fc490d22ca324b7b0fbff6c3f9a957bce6716e77e21a6ac39bd8d24` | `1e72322d9a7469c6f953121e4280e19a50652ca91ab7c4399f9a36fdc9aecd2a` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/androidTest/java/com/mangalens/ChapterTranslationCheckpointTest.kt` | `4d7f5f513f983f1ee785bf35732e51366a812952c7d1c8e97cc6998fa8309426` | Absent from supplied/restored baseline | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `app/src/androidTest/java/com/mangalens/ReaderTranslationRecoveryTest.kt` | `75e9289aa5c348d54a8f7844843e45ef4ceb7dc8de7120728207aafc4e867a80` | `4e99cfa95bc601db86980e78c42967be7c5f95c0c5609280acc8c279a8e20482` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `docs/RECORDINGS_ROUND2_AND_DURABLE_TRANSLATION.md` | `d0d91df0164bb849a399111bf60bd1b70c6cb14c2dfcfca08930a8f4686575e8` | Absent from supplied/restored baseline | blocked · historical version unavailable; finish reviewed implementation from baseline |
| `docs/MANGALENS_NEXT_CURRENT_ENGINEERING.md` | `4d5240aced0f5d2357c8e126a6ada3d042dbea4b960a3d6b339cfa4219e2c87d` | `cbb53d8e2f09c2ef6a026bb913de774c6c76d933aacfb5128176b731e364d46f` · different source version | blocked · historical version unavailable; finish reviewed implementation from baseline |

## Appendix C — invocation

| Requirement | Status | Evidence / next action |
|---|---|---|
| Read complete contract; continue latest correct mature source autonomously; complete requirements ledger; prioritize Orez/stability; use permitted zero-cost resources; preserve privacy/data; directly emulator-test major upgrades; never label untested work complete. | partial | Full brief read and this ledger created. Parent owns implementation, candidate artifacts and execution. Missing historical prototype/private recordings are explicitly blocked; direct acceptance is pending. |

## Detailed current reliability and orchestration contracts

| ID / original section | Full behavior still required | Status | Source / tests and remaining work |
|---|---|---|---|
| M5.RECOVERY | Page-level journal signatures/generation/config identity; sequential foreground WorkManager ownership; disk-backed cleaned surfaces and saved lettering; retry interrupted page and retain successful neighboring bubbles. | partial | [TRANSLATION](#evidence-translation), [RESOURCES](#evidence-resources); historical prototype source unavailable. Baseline UI-owned translation must be extended and emulator-tested. |
| M5.FENCES | Rapid pause/resume and same-task replacement; chapter/language/style/OCR/refinement changes; source mutation; cancellation cannot reuse recycled bitmaps or report an old task's error in a newer/unrelated Video/Web task. | partial | [TRANSLATION](#evidence-translation); DUR01–DUR05 in acceptance matrix. |
| M5.STORAGE | AtomicFile crash recovery, bounded journal, missing/corrupt cleaned output, safe orphan cleanup, source invalidation, visible-page restoration and preserved originals/text scale/all existing reading modes. | partial | [LIBRARY](#evidence-library), [LETTERING](#evidence-lettering); historical prototype's page checkpoint does not prove bubble-level resume or native process isolation. |
| M5.PLAYBACK | Try installed extraction before updates; bound static/native resolution; Cancel/Back/Open source must actually stop native/process work; no indefinite spinner and no stale callback publishing. | partial | [MEDIA](#evidence-media); V06 tests actual UI/native bounds. Proposed historical 45-second timeout is not validated shipped behavior. |
| M7.CONTEXT | Snapshot active chapter/page/panel, browser source, playback/media, task/permissions, installed models and device budget; deterministic simple routes and constrained complex planning preserve explicit objectives. | partial | [AGENT](#evidence-agent); current context is mainly chapter/library booleans and active URL. |
| M7.EXECUTOR | Typed tools own preconditions, private scope, timeouts, cancellation, idempotency, output identities and completion predicates. General dependencies/retries/versioned replay/partial invalidation must retain current download stable IDs and cancellation guarantees. | partial | [TASKS](#evidence-tasks); current durable plan is ordered download-only prefix. |
| M7.SPECIALISTS | Smallest adequate evaluated reasoning/OCR/translation/embedding/reranker/speech/vision/inpainting provider; bounded inspectable/removable session/preferences/series/correction/knowledge/task memories. | partial | [MODELS](#evidence-models), [MEMORY](#evidence-memory); broad specialists/series RAG unavailable. |
| M7.RESEARCH | Source-tagged fresh research, restricted actual browser context/DOM tools, deterministic evaluator first, semantic critic only where needed, justified alternate recovery strategies and bounded retries. | partial | [RESEARCH](#evidence-research), [WEB](#evidence-web); general research/DOM completion/recovery providers incomplete. |
| M7.MULTIMODAL | Explicit images/panels/screenshots, voice commands, speech transcription/translation and optional free licensed offline speech output. | partial | [CAPTIONS](#evidence-captions), [OCR](#evidence-ocr); semantic visual understanding/voice command/TTS providers planned, not established by OCR/ASR alone. |
| M9.GOVERNOR | Thermal state, sustained duration, battery, available memory and recent load reduce work adaptively with hysteresis; only justified critical pressure causes hard pause, preserving playback/UI priority. | planned | [RESOURCES](#evidence-resources); memory callbacks alone do not implement the shared governor. |
| M9.ISOLATION | Evaluate an isolated Android process for native inference faults while preserving real model cancellation/ownership; separate interactive UI from heavy compute. | planned | [MODELS](#evidence-models), app manifest currently uses same-process native inference. |
| M10.0 | Refresh correct ancestry/PR/source, preserve available local changes, restore full checkout, boot cloud emulator and run identity/core-screen baseline before building on it. | partial | [BUILD](#evidence-build); parent owns exact checkout/runtime evidence. |
| M10.1 | Finish durable recorded reader/translation/playback defects; inspect supplied visual regressions and restart/pressure tests; produce reviewable candidate. | partial | [TRANSLATION](#evidence-translation), [MEDIA](#evidence-media); private regression recordings are blocked as unavailable, controlled fixtures can proceed. |
| M10.2 | General durable Orez reader/vision/translation/subtitle/research execution with dependencies, evaluator/recovery/events and stable real output completion. | partial | [AGENT](#evidence-agent), [TASKS](#evidence-tasks); downloads only currently durable. |
| M10.3 | Region multi-script fusion/crop retry/vertical Japanese/panel/bubble/SFX, series glossary/RAG, difficult-art reconstruction and persistent in-reader corrections; measure multilingual meaning/register/names. | partial | [OCR](#evidence-ocr), [TRANSLATION](#evidence-translation), [LETTERING](#evidence-lettering); semantic quality and new algorithms incomplete. |
| M10.4 | Broad real media audio/duration/quality and durable full-video ASR/caption/translation exports with long windows and honest unavailable states. | partial | [MEDIA](#evidence-media), [CAPTIONS](#evidence-captions), [DOWNLOADS](#evidence-downloads). |
| M10.5 | Evaluated Lite/Core/Max and specialist packs; transactional verification/compatibility/rollback/resource routing; useful visual/voice interaction and held-out comparisons. | partial | [MODELS](#evidence-models), [LAB](#evidence-lab); no model-intelligence parity claim. |
| M10.6 | Controlled browser research, sourced evidence, semantic/global library search, next chapters, spoiler bounds, Home/navigation customization, accessible UI and safe migrations. | partial | [WEB](#evidence-web), [LIBRARY](#evidence-library), [UI](#evidence-ui); remaining appendix features are sequencing, not omissions. |
| M10.7 | Licensed reproducible datasets/benchmarks/tool recovery traces and model experiments; measured quality/cost/latency/resource gains with stable app regression gates. | partial | [LAB](#evidence-lab); existing corpus scripts are not a completed training/evaluation system. |
| A7.6.EXTRA | Multiple real OCR blocks in the same balloon are conservatively grouped and translated together. | implemented/unverified | [OCR](#evidence-ocr); geometric merging exists; separate true shape/tail/caption/thought/dialogue/narration/SFX segmentation remains planned. |
| A11.8.EXTRA | Optional fine-tuning/LoRA/quantization runs require licensed data and available free compute; keep private corrections local and record splits/seeds/hash/metrics/quantization before and after. | partial | [LAB](#evidence-lab); no trained model/run is implied by available seed builders. |

## Audit boundaries and update rules

- Preserve every requirement ID when updating status. Link an exact candidate/report/artifact when promoting to implemented/verified. New features may add IDs; they must not delete target clauses.
- Do not promote compiled instrumentation, mocked tool output or syntax checks to real model/semantic/runtime acceptance. Record skips as skipped/blocked with a reason.
- Run changed-area tests plus feasible core-screen regression after source changes; do not erase data before upgrade/migration acceptance. Force-stop and ordinary process interruption are different cases.
- Adaptive download output may be a Media3 cache, not an exportable standalone video. Verify offline playback and describe the representation honestly.
- Dismiss Orez Task stops monitoring/future steps while current native transfer remains. Native Cancel/Remove cancels its owner before row removal. Preserve these semantics when extending execution.
- Cloud emulator checks cannot establish physical phone thermal behavior, ARM64/NPU/GPU performance or every codec/DRM surface. Keep hardware-only acceptance separate.
- Missing recording ZIP, approved reference attachment and stopped prototype are source/evidence blockers, not permission to invent their pixels/results; use controlled fixtures while retaining the exact private regressions as pending.

Contract coverage inventory: 166 Appendix A numbered sections/subsections, 1267 original list clauses, 32 explicit prose/schema/example clauses plus 21 detailed current reliability/orchestration contracts; all current mission additions, complete area/acceptance tables, all 18 Appendix B identities and Appendix C invocation are represented. No implemented/verified row exists.
