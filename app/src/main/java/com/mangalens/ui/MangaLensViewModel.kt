package com.mangalens.ui

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangalens.acquisition.RenderedBrowserAcquirer
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockStats
import com.mangalens.core.adblock.AdBlockStatsStore
import com.mangalens.core.model.ContentType
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.LibraryChapterMetadata
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.engine.MangaChapterScraper
import com.mangalens.engine.MangaChapterCatalogScraper
import com.mangalens.core.model.MangaChapter
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaLinkResolver
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationCommandScope
import com.mangalens.core.translation.ChapterTranslationRequest
import com.mangalens.core.translation.ChapterTranslationJobs
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask
import com.mangalens.core.translation.ChapterTranslationSourceIdentity
import com.mangalens.core.translation.ReaderTranslationChoice
import com.mangalens.core.translation.ReaderTranslationReceipt
import com.mangalens.core.translation.ReaderTranslationPresentation
import com.mangalens.core.verification.CaptchaBridge
import com.mangalens.ui.reader.TranslationOverlay
import com.mangalens.ui.theme.ThemeMode
import com.mangalens.ui.video.VideoSourcePolicy
import com.mangalens.ui.video.VideoPlaybackSelection
import com.mangalens.ui.video.VideoPlaybackPublication
import com.mangalens.ui.video.VideoReadyObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers

data class MangaLensUiState(
    val library: List<SavedChapter> = emptyList(),
    val activeChapter: SavedChapter? = null,
    val url: String = "",
    val mode: ContentType = ContentType.GENERIC_WEB,
    val pages: List<ChapterPage> = emptyList(),
    val chapters: List<MangaChapter> = emptyList(),
    val videoUrl: String? = null,
    val videoPageUrl: String? = null,
    val videoHeaders: Map<String, String> = emptyMap(),
    val videoAudioUrl: String? = null,
    val videoAudioHeaders: Map<String, String> = emptyMap(),
    val videoResolutionId: String? = null,
    val videoAudioResolutionId: String? = null,
    val videoExpectedDurationUs: Long? = null,
    val loading: Boolean = false,
    val translating: Boolean = false,
    val translationPaused: Boolean = false,
    val translationDone: Int = 0,
    val translationTotal: Int = 0,
    val translationEnabled: Boolean = false,
    val overlays: Map<Int, List<TranslationOverlay>> = emptyMap(),
    val translatedBackgrounds: Map<Int, String> = emptyMap(),
    val translationMessage: String? = null,
    val promoPages: Set<Int> = emptySet(),
    val error: String? = null,
    val translationError: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val mangaTranslationEnabled: Boolean = false,
    val videoTranslationEnabled: Boolean = false,
    val webTranslationEnabled: Boolean = false,
    val targetLanguage: String = "hi",
    val translationStyle: String = "natural",
    val customTranslationStyle: String = "",
    val adBlockEnabled: Boolean = true,
    val adBlockStats: AdBlockStats = AdBlockStats(),
)

internal fun MangaLensUiState.capturedVideoSelection(): VideoPlaybackSelection? {
    if (mode != ContentType.VIDEO_STREAM || videoUrl.isNullOrBlank() || videoResolutionId == null ||
        videoAudioUrl != null && videoAudioResolutionId != videoResolutionId) return null
    return VideoPlaybackSelection(videoResolutionId, videoUrl, videoHeaders.toMap(), videoPageUrl,
        videoAudioUrl, videoAudioHeaders.toMap(), videoExpectedDurationUs)
}

private fun MangaLensUiState.withVideoSelection(selection: VideoPlaybackSelection?): MangaLensUiState = copy(
    videoUrl = selection?.videoUrl, videoPageUrl = selection?.pageUrl,
    videoHeaders = selection?.videoHeaders.orEmpty(), videoAudioUrl = selection?.audioUrl,
    videoAudioHeaders = selection?.audioHeaders.orEmpty(), videoResolutionId = selection?.resolutionId,
    videoAudioResolutionId = selection?.resolutionId?.takeIf { selection.audioUrl != null },
    videoExpectedDurationUs = selection?.durationUs
)

internal fun MangaLensUiState.withLibraryChapter(chapter: SavedChapter): MangaLensUiState = withVideoSelection(null).copy(
    activeChapter = chapter, pages = chapter.pages, url = chapter.sourceUrl,
    mode = ContentType.IMAGE_CHAPTER, loading = false,
    library = library.map { if (it.id == chapter.id) it.copy(lastReadAt = chapter.lastReadAt) else it }
)

class MangaLensViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("mangalens_preferences", Application.MODE_PRIVATE)
    private val ocrPrefs = application.getSharedPreferences("mangalens_ocr", Application.MODE_PRIVATE)
    private val ocrPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("script", "high_accuracy", "preserve_style", "local_refinement")) clearTranslations()
    }
    private val statsStore = AdBlockStatsStore.shared
    private val router = UrlEngineRouter()
    private val persistenceMutex = kotlinx.coroutines.sync.Mutex()
    private val library = ChapterLibrary(application)
    private val repository = ProgressiveChapterRepository(application)
    private val acquirer = RenderedBrowserAcquirer(
        application,
        AdBlockEngine(statsStore),
        adBlockEnabled = { prefs.getBoolean("ad_block_enabled", true) }
    )
    private val translationCommands = kotlinx.coroutines.sync.Mutex()
    private val translationSelectionEpoch = MutableStateFlow(0L)
    private var displayedTranslation: ChapterTranslationTask? = null
    private var translationReceipt: ReaderTranslationReceipt? = null
    private class PendingTranslation(val selection: TranslationSelection) {
        val request = ChapterTranslationRequest()
        val ownerRequestId = "reader-" + java.util.UUID.randomUUID()
    }
    private var pendingTranslation: PendingTranslation? = null
    private var presentationOpen = true
    private var ingestionJob: kotlinx.coroutines.Job? = null
    private var translateWhenPagesReady = false
    private val chapterScraper = MangaChapterScraper(application)
    private val staticAcquirer = com.mangalens.core.acquisition.StaticChapterAcquirer()
    private val chapterCatalog = MangaChapterCatalogScraper(application)
    private val mediaLinkResolver = MediaLinkResolver(
        siteExtractor = com.mangalens.download.YtDlpSiteMediaExtractor(application, allowSeparateStreams = true),
        timeoutMs = 45_000L
    )
    val captchaBridge = CaptchaBridge()

    private val _state = MutableStateFlow(
        MangaLensUiState(
            url = prefs.getString("last_url", "") ?: "",
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme_mode", ThemeMode.DARK.name) } ?: ThemeMode.DARK,
            mangaTranslationEnabled = prefs.getBoolean("translation_manga", false),
            videoTranslationEnabled = prefs.getBoolean("translation_video", false),
            webTranslationEnabled = prefs.getBoolean("translation_web", false),
            targetLanguage = prefs.getString("translation_target", "hi") ?: "hi",
            translationStyle = prefs.getString("translation_style", "natural") ?: "natural",
            customTranslationStyle = prefs.getString("translation_custom_style", "") ?: "",
            adBlockEnabled = prefs.getBoolean("ad_block_enabled", true),
        )
    )
    val state: StateFlow<MangaLensUiState> = _state

    private data class TranslationSelection(
        val chapterId: String, val language: String, val style: String, val custom: String,
        val enabled: Boolean, val epoch: Long, val sources: List<Pair<Int, String?>>,
        val configuration: ChapterTranslationConfig
    )

    private fun translationSelection(value: MangaLensUiState = _state.value) = TranslationSelection(
        value.activeChapter?.id.orEmpty(), value.targetLanguage, value.translationStyle,
        value.customTranslationStyle, value.mangaTranslationEnabled, translationSelectionEpoch.value,
        value.pages.map { it.index to it.localPath }, capturedTranslationConfig(value.targetLanguage, value)
    )

    init {
        ocrPrefs.registerOnSharedPreferenceChangeListener(ocrPreferenceListener)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { library.list() }
            val recent = saved.firstOrNull()
            _state.value = _state.value.copy(library = saved)
            if (recent != null && _state.value.activeChapter == null && !_state.value.loading) {
                _state.value = _state.value.copy(activeChapter = recent)
                repository.restorePages(recent.pages)
            }
        }
        viewModelScope.launch { repository.pages.collect { pages ->
            _state.value = _state.value.copy(pages = pages)
            if (pages.isNotEmpty()) persistCurrentChapter()
        } }
        viewModelScope.launch { statsStore.stats.collect { stats -> _state.value = _state.value.copy(adBlockStats = stats) } }
        viewModelScope.launch {
            try {
                // Loading and validating the atomic journals never happens on the UI thread.
                val store = withContext(Dispatchers.IO) { ChapterTranslationStore.shared(app) }
                launch(Dispatchers.IO) { ChapterTranslationJobs.recoverPending(app) }
                var validatedSelection: TranslationSelection? = null
                val selections = combine(_state.map { translationSelection(it) }.distinctUntilChanged(),
                    translationSelectionEpoch) { _, _ -> translationSelection() }.distinctUntilChanged()
                combine(store.states, selections) { tasks, selected -> tasks to selected }.collect { (tasks, selected) ->
                    val choice = runCatching { ReaderTranslationChoice.from(selected.configuration) }.getOrNull()
                    val paths = selected.sources.toMap()
                    val managed = java.io.File(app.filesDir, "chapters")
                    val bound = if (!selected.enabled || choice == null) null else {
                        translationReceipt?.takeIf {
                            ReaderTranslationPresentation.matchesReader(it, selected.chapterId, choice, paths, managed)
                        } ?: ReaderTranslationPresentation.restore(tasks, selected.chapterId, choice, paths, managed)
                    }
                    translationReceipt = bound
                    var task = if (bound != null && choice != null) ReaderTranslationPresentation.select(tasks, bound,
                        selected.chapterId, choice, paths, managed) else null
                    if (task != null && (task.validationPending || validatedSelection != selected)) {
                        val refreshed = store.refresh(task.id, task.generation)
                        task = if (refreshed != null && bound != null && choice != null)
                            ReaderTranslationPresentation.select(listOf(refreshed), bound, selected.chapterId, choice, paths, managed) else null
                        validatedSelection = selected
                    }
                    if (translationSelection() == selected && pendingTranslation?.selection != selected && translationReceipt == bound) {
                        displayTranslation(task)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(translationMessage = failure.message ?: "Saved translations could not be restored.", translationError = true) }
            }
        }
    }

    private fun displayTranslation(task: ChapterTranslationTask?) {
        displayedTranslation = task?.takeUnless { it.validationPending }
        if (task == null || task.validationPending) {
            _state.update { it.copy(translating = false, translationPaused = false, translationDone = 0,
                translationTotal = 0, translationEnabled = false, translationError = false,
                translationMessage = null, overlays = emptyMap(), translatedBackgrounds = emptyMap(), promoPages = emptySet()) }
            return
        }
        val currentPaths = _state.value.pages.associate { it.index to it.localPath }
        val managedSources = java.io.File(app.filesDir, "chapters")
        val pages = task.pages.filter {
            ChapterTranslationSourceIdentity.matches(it.sourcePath, currentPaths[it.index], managedSources)
        }
        val overlays = pages.filter { it.cleanedPath != null && it.imageWidth > 0 && it.imageHeight > 0 }
            .associate { page -> page.index to page.lettering.map { text ->
                TranslationOverlay(region = com.mangalens.core.translation.OcrRegion(text.source,
                    text.sourceLeft, text.sourceTop, text.sourceRight, text.sourceBottom),
                    translatedText = text.translated, textColorArgb = text.color, fontSizePx = text.size,
                    imageWidthPx = page.imageWidth, imageHeightPx = page.imageHeight, lettering = text)
            } }.filterValues { it.isNotEmpty() }
        val active = task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING, ChapterTranslationStatus.PAUSED)
        val failed = task.status in setOf(ChapterTranslationStatus.FAILED, ChapterTranslationStatus.PARTIAL)
        val message = task.error ?: when {
            task.status == ChapterTranslationStatus.PARTIAL -> "Some pages need another attempt. Successful translations are available."
            task.status == ChapterTranslationStatus.FAILED -> "Chapter translation needs another attempt. Original pages are available."
            task.status == ChapterTranslationStatus.CANCELLED -> "Translation cancelled. Saved pages are still available."
            task.status == ChapterTranslationStatus.COMPLETED && overlays.isEmpty() -> "All pages were processed. No readable story text was detected."
            else -> null
        }
        _state.update { it.copy(translating = active, translationPaused = task.status == ChapterTranslationStatus.PAUSED,
            translationDone = task.processedPages, translationTotal = task.totalPages,
            translationEnabled = overlays.isNotEmpty(), translationError = failed, translationMessage = message,
            overlays = overlays, translatedBackgrounds = pages.mapNotNull { page -> page.cleanedPath?.let { page.index to it } }.toMap(),
            promoPages = pages.filter { it.status == ChapterTranslationPageStatus.PROMO }.map { it.index }.toSet()) }
    }

    private fun capturedTranslationConfig(language: String, value: MangaLensUiState = _state.value): ChapterTranslationConfig {
        val options = ocrPrefs.all
        return ChapterTranslationConfig(language, value.translationStyle, value.customTranslationStyle,
            options["script"] as? String ?: "AUTO", options["high_accuracy"] as? Boolean ?: true,
            options["preserve_style"] as? Boolean ?: true, options["local_refinement"] as? Boolean ?: false)
    }

    fun setUrl(value: String) {
        val normalized = value.trim()
        prefs.edit().putString("last_url", normalized).apply()
        val changed = normalized != _state.value.url
        if (changed) cancelIngestion()
        _state.value = _state.value.copy(
            url = normalized,
            mode = if (normalized.isBlank()) ContentType.GENERIC_WEB else router.classifyUrl(normalized),
            videoUrl = if (changed) null else _state.value.videoUrl,
            videoPageUrl = if (changed) null else _state.value.videoPageUrl,
            videoHeaders = if (changed) emptyMap() else _state.value.videoHeaders,
            videoAudioUrl = if (changed) null else _state.value.videoAudioUrl,
            videoAudioHeaders = if (changed) emptyMap() else _state.value.videoAudioHeaders,
            videoResolutionId = if (changed) null else _state.value.videoResolutionId,
            videoAudioResolutionId = if (changed) null else _state.value.videoAudioResolutionId,
            videoExpectedDurationUs = if (changed) null else _state.value.videoExpectedDurationUs
        )
    }
    fun setMode(mode: ContentType) {
        if (mode != _state.value.mode) cancelIngestion()
        _state.value = if (mode == _state.value.mode) _state.value else
            _state.value.withVideoSelection(null).copy(mode = mode)
    }
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }
    fun setMangaTranslationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("translation_manga", enabled).apply()
        _state.value = _state.value.copy(mangaTranslationEnabled = enabled)
        if (!enabled) clearTranslations()
    }
    fun setVideoTranslationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("translation_video", enabled).apply()
        _state.value = _state.value.copy(videoTranslationEnabled = enabled)
    }
    fun setWebTranslationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("translation_web", enabled).apply()
        _state.value = _state.value.copy(webTranslationEnabled = enabled)
    }
    fun setTargetLanguage(language: String) {
        val normalized = language.lowercase().trim()
        if (normalized == _state.value.targetLanguage) return
        clearTranslations()
        if (normalized.isBlank()) return
        prefs.edit().putString("translation_target", normalized).apply()
        _state.value = _state.value.copy(targetLanguage = normalized)
    }

    fun setTranslationStyle(styleId: String) {
        val normalized = styleId.lowercase().trim()
        if (normalized == _state.value.translationStyle) return
        clearTranslations()
        if (normalized.isBlank()) return
        prefs.edit().putString("translation_style", normalized).apply()
        _state.value = _state.value.copy(translationStyle = normalized)
    }

    fun setCustomTranslationStyle(instruction: String) {
        val normalized = instruction.trim().take(1200)
        if (normalized == _state.value.customTranslationStyle) return
        clearTranslations()
        prefs.edit().putString("translation_custom_style", normalized).apply()
        _state.value = _state.value.copy(customTranslationStyle = normalized)
    }
    fun setAdBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ad_block_enabled", enabled).apply()
        _state.value = _state.value.copy(adBlockEnabled = enabled)
    }
    fun resetAdBlockStats() = statsStore.reset()

    fun acceptResolvedVideo(
        url: String,
        headers: Map<String, String>,
        pageUrl: String,
        audioUrl: String?,
        audioHeaders: Map<String, String>
    ) {
        val selected = try { VideoPlaybackPublication.capture(url, headers, pageUrl, audioUrl, audioHeaders) }
        catch (failure: IllegalArgumentException) {
            _state.value = _state.value.copy(error = failure.message); return
        }
        cancelIngestion()
        selected.pageUrl?.let { prefs.edit().putString("last_url", it).apply() }
        _state.value = _state.value.withVideoSelection(selected).copy(
            url = selected.pageUrl ?: _state.value.url,
            mode = ContentType.VIDEO_STREAM,
            loading = false,
            error = null
        )
    }

    /** Called synchronously on the player/UI thread; both source guards precede player mutation. */
    fun acceptVideoRefresh(expected: VideoPlaybackSelection, replacement: VideoPlaybackSelection,
        commitPlayback: () -> Boolean): Boolean {
        if (!VideoPlaybackPublication.canReplace(_state.value.capturedVideoSelection(), expected, replacement)) return false
        if (!commitPlayback()) return false
        val latest = _state.value
        if (!VideoPlaybackPublication.canReplace(latest.capturedVideoSelection(), expected, replacement)) return false
        _state.value = latest.withVideoSelection(replacement.captured()).copy(error = null)
        return true
    }

    fun acceptVideoReady(observed: VideoReadyObservation) {
        _state.update { current ->
            val selected = VideoPlaybackPublication.acceptReady(current.capturedVideoSelection(), observed)
            if (selected == null) current else current.withVideoSelection(selected)
        }
    }

    fun importLocalImages(uris: List<Uri>) {
        clearTranslations()
        val previousIngestion = ingestionJob
        previousIngestion?.cancel()
        ingestionJob = viewModelScope.launch {
            previousIngestion?.join()
            _state.value = _state.value.withVideoSelection(null).copy(loading = true, translating = false, error = null, overlays = emptyMap(), translationEnabled = false)
            try {
                repository.clearChapterCache()
                val imported = com.mangalens.core.reader.DocumentImporter.prepare(app, uris)
                try {
                val key = "local:" + uris.joinToString("|")
                _state.value = _state.value.copy(activeChapter = SavedChapter(ChapterLibrary.id(key), imported.title, "", emptyList()))
                val pages = repository.persistLocalImages(imported.images, app)
                _state.value = _state.value.copy(
                    pages = pages,
                    mode = ContentType.IMAGE_CHAPTER,
                    loading = false,
                    mangaTranslationEnabled = true,
                    error = null
                )
                persistCurrentChapter()
                prefs.edit().putBoolean("translation_manga", true).apply()
                } finally { imported.close() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.value = _state.value.copy(loading = false, error = "Image import failed: " + (failure.message ?: "Unable to read selected images"))
            }
        }
    }

    fun ingestAndTranslate() {
        translateWhenPagesReady = true
        ingest()
    }

    fun cancelIngestion() {
        ingestionJob?.cancel()
        ingestionJob = null
        translateWhenPagesReady = false
        _state.update { it.copy(loading = false) }
    }

    fun ingest() {
        val previousIngestion = ingestionJob
        previousIngestion?.cancel()
        clearTranslations()
        val target = _state.value.url
        val selectedMode = _state.value.mode
        if (!com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(target)) {
            _state.value = _state.value.copy(error = "Enter a complete HTTP or HTTPS URL.", loading = false)
            return
        }
        _state.value = _state.value.withVideoSelection(null).copy(
            loading = true,
            error = null,
            videoPageUrl = if (selectedMode == ContentType.VIDEO_STREAM) target else null
        )
        ingestionJob = viewModelScope.launch {
            previousIngestion?.join()
            var videoSourceFailure: com.mangalens.download.MediaSourceFailure? = null
            try {
                when (selectedMode) {
                    ContentType.VIDEO_STREAM -> kotlinx.coroutines.withTimeout(45_000L) {
                        if (!VideoSourcePolicy.isSourcePage(target)) {
                            ensureActive()
                            if (_state.value.url != target || _state.value.mode != selectedMode) return@withTimeout
                            val selected = VideoPlaybackPublication.capture(target, emptyMap(), null, null, emptyMap())
                            _state.value = _state.value.withVideoSelection(selected).copy(
                                mode = ContentType.VIDEO_STREAM,
                                loading = false
                            )
                        } else {
                            val staticResolved = withContext(Dispatchers.IO) {
                                try { mediaLinkResolver.resolveCancellable(target) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    videoSourceFailure = com.mangalens.download.MediaSourceFailure.from(failure)
                                    null
                                }
                            }?.takeIf { com.mangalens.ui.video.isPlayableRefresh(it) }

                            val resolved = staticResolved ?: run {
                                val existingCookie = CookieManager.getInstance().getCookie(target)
                                val rendered = acquirer.discoverWithCookie(target, 12_000L, existingCookie)
                                VideoSourcePolicy.preferredMediaUrl(rendered.videoStreamUrls)?.let { mediaUrl ->
                                    val mediaCookie = CookieManager.getInstance().getCookie(mediaUrl)
                                    com.mangalens.download.ResolvedMediaLink(
                                        url = mediaUrl,
                                        mimeType = null,
                                        provider = "web-sniff",
                                        sourcePageUrl = target,
                                        headers = buildMap {
                                            if (!mediaCookie.isNullOrBlank()) put("Cookie", mediaCookie)
                                            put("Referer", target)
                                            put("User-Agent", com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
                                            put("Accept", "*/*")
                                        }
                                    )
                                }
                            }

                            if (resolved != null) {
                                val pageUrl = resolved.sourcePageUrl ?: target
                                val videoCookie = CookieManager.getInstance().getCookie(resolved.url).orEmpty()
                                val playbackHeaders = buildMap {
                                    putAll(resolved.headers)
                                    if (videoCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", videoCookie)
                                    if (keys.none { it.equals("Referer", true) }) put("Referer", pageUrl)
                                    if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
                                    if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                                }
                                val audioHeaders = resolved.audioUrl?.let { audioUrl ->
                                    val audioCookie = CookieManager.getInstance().getCookie(audioUrl).orEmpty()
                                    buildMap {
                                        putAll(resolved.audioHeaders)
                                        if (audioCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", audioCookie)
                                        if (keys.none { it.equals("Referer", true) }) put("Referer", pageUrl)
                                        if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
                                        if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                                    }
                                }.orEmpty()
                                ensureActive()
                                if (_state.value.url != target || _state.value.mode != selectedMode) return@withTimeout
                                val selected = VideoPlaybackPublication.capture(resolved.url, playbackHeaders, pageUrl, resolved.audioUrl, audioHeaders)
                                _state.value = _state.value.withVideoSelection(selected).copy(
                                    mode = ContentType.VIDEO_STREAM,
                                    loading = false,
                                    error = null
                                )
                            } else {
                                _state.value = _state.value.withVideoSelection(null).copy(
                                    mode = ContentType.VIDEO_STREAM,
                                    videoPageUrl = target,
                                    loading = false,
                                    error = videoSourceFailure?.message
                                        ?: "No accessible video stream was resolved. Retry or open the source page to check access."
                                )
                            }
                        }
                    }
                    ContentType.IMAGE_CHAPTER -> {
                        _state.value = _state.value.copy(activeChapter = SavedChapter(ChapterLibrary.id(target), target.substringAfterLast('/').ifBlank { "Chapter" }, target, emptyList()))
                        repository.clearChapterCache()
                        _state.value = _state.value.copy(pages = emptyList(), overlays = emptyMap(), translationEnabled = false)
                        val lightweight = staticAcquirer.discover(target)
                        val chapters = if (!lightweight?.chapters.isNullOrEmpty()) lightweight!!.chapters.map { MangaChapter(it.title, it.url) }
                            else try { chapterCatalog.extract(target).map { MangaChapter(it.title, it.url) } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                        _state.value = _state.value.copy(chapters = chapters,
                            activeChapter = _state.value.activeChapter?.copy(title = lightweight?.title ?: _state.value.activeChapter!!.title))
                        val result = if (!lightweight?.pageUrls.isNullOrEmpty()) {
                            com.mangalens.acquisition.RenderedPageSet(target, lightweight!!.pageUrls, emptyList(), emptyList(), 1, 200, null)
                        } else acquirer.discoverWithCookie(target, 15_000L, null)
                        if (result.imageUrls.isNotEmpty()) repository.persistDiscoveredPages(result.imageUrls, result.finalUrl)
                        if (result.imageUrls.isEmpty()) {
                            val fallback = chapterScraper.extract(target)
                            if (fallback.isNotEmpty()) repository.persistDiscoveredPages(fallback, target)
                        }
                        val chapterError = if (repository.pages.value.isEmpty()) {
                            result.navigationError
                                ?: "No chapter pages were detected automatically. Open the source in Web only if the site itself requires sign-in or verification, then retry."
                        } else null
                        // Empty extraction is not proof of a challenge. Do not force an app-side
                        // verification dialog for ordinary chapters. Real site login/CAPTCHA remains
                        // available in Web mode when the source itself requires it.
                        _state.value = _state.value.copy(
                            mode = ContentType.IMAGE_CHAPTER,
                            loading = false,
                            error = chapterError
                        )
                    }
                    ContentType.GENERIC_WEB -> _state.value = _state.value.copy(mode = ContentType.GENERIC_WEB, loading = false)
                }
                val availablePages = repository.pages.value
                if (translateWhenPagesReady) {
                    translateWhenPagesReady = false
                    if (_state.value.mode == ContentType.IMAGE_CHAPTER && availablePages.isNotEmpty()) {
                        _state.value = _state.value.copy(pages = availablePages, loading = false)
                        translateChapter()
                    } else if (_state.value.mode == ContentType.IMAGE_CHAPTER) {
                        _state.value = _state.value.copy(error = "No chapter images could be loaded. Check the URL, or open the source in Web if that site itself requires sign-in or verification.")
                    }
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update { it.copy(
                    loading = false,
                    error = if (selectedMode == ContentType.VIDEO_STREAM)
                        videoSourceFailure?.message ?: "Video detection timed out. Retry or open the source page in Web."
                    else "Chapter acquisition timed out. Retry or open the source page in Web."
                ) }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = if (selectedMode == ContentType.VIDEO_STREAM)
                    videoSourceFailure?.message ?: com.mangalens.download.MediaSourceFailure.from(t).message
                else t.message ?: "Acquisition failed")
            }
        }
    }

    fun ingestWithCookie(cookie: String) {
        val previousIngestion = ingestionJob
        previousIngestion?.cancel()
        clearTranslations()
        val target = _state.value.url
        ingestionJob = viewModelScope.launch {
            previousIngestion?.join()
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                repository.clearChapterCache()
                _state.value = _state.value.copy(activeChapter = SavedChapter(ChapterLibrary.id(target), target.substringAfterLast('/').ifBlank { "Chapter" }, target, emptyList()))
                val result = acquirer.discoverWithCookie(target, 20_000L, cookie)
                if (result.imageUrls.isNotEmpty()) repository.persistDiscoveredPages(result.imageUrls, result.finalUrl)
                _state.value = _state.value.copy(mode = ContentType.IMAGE_CHAPTER, loading = false, error = result.navigationError)
                captchaBridge.completeVerification(cookie, "Android")
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                captchaBridge.failVerification(t.message ?: "Verification retry failed")
                _state.value = _state.value.copy(loading = false, error = t.message)
            }
        }
    }

    fun translatePage(page: ChapterPage, targetLanguage: String = _state.value.targetLanguage) {
        if (!_state.value.mangaTranslationEnabled) {
            _state.update { it.copy(translationError = true,
                translationMessage = "Manga translation is disabled in Settings → Translation Modules.") }
            return
        }
        val chapter = _state.value.activeChapter
        val pages = _state.value.pages.toList()
        if (chapter == null || page.localPath == null || pages.none { it.index == page.index && it.localPath == page.localPath }) {
            _state.update { it.copy(translating = false, translationPaused = false, translationError = true,
                translationMessage = "This page is not available as a local image yet. Reopen the chapter or import the image from Files.") }
            return
        }
        startChapterTranslation(chapter.copy(pages = pages), targetLanguage, listOf(page.index), forceReprocess = true)
    }

    fun translateChapter(targetLanguage: String = _state.value.targetLanguage) {
        val chapter = _state.value.activeChapter
        val pages = _state.value.pages.toList()
        if (chapter == null || pages.isEmpty()) {
            _state.update { it.copy(translationError = true, translationMessage = "No manga pages are available to translate.") }
            return
        }
        startChapterTranslation(chapter.copy(pages = pages), targetLanguage, null)
    }

    private fun startChapterTranslation(chapter: SavedChapter, language: String, requestedPages: List<Int>?,
        forceReprocess: Boolean = false) {
        // The explicit language overload also selects its result in the reader.
        setTargetLanguage(language)
        val config = capturedTranslationConfig(_state.value.targetLanguage)
        prefs.edit().putBoolean("translation_manga", true).apply()
        _state.update { it.copy(mangaTranslationEnabled = true, translating = true, translationPaused = false,
            translationTotal = chapter.pages.size, translationMessage = null, translationError = false) }
        val requestedSelection = translationSelection()
        val pending = PendingTranslation(requestedSelection)
        pendingTranslation = pending
        ChapterTranslationCommandScope.scope.launch {
            try {
                translationCommands.withLock {
                    val task = pending.request.dispatch(
                        start = { ChapterTranslationJobs.start(app, chapter, config, requestedPages, pending.ownerRequestId,
                            forceReprocess = forceReprocess) },
                        pause = { ChapterTranslationJobs.pause(app, it.id, it.generation) },
                        resume = { ChapterTranslationJobs.resume(app, it.id, it.generation) },
                        cancel = { ChapterTranslationJobs.cancel(app, it.id, it.generation) }
                    )
                    if (presentationOpen && task != null && translationSelection() == requestedSelection) {
                        // Presentation observes the latest selected task; the pending request's
                        // controls above always use only its own generation receipt.
                        val visible = withContext(Dispatchers.IO) { ChapterTranslationStore.shared(app).get(task.id) }
                        if (translationSelection() == requestedSelection) {
                            val choice = ReaderTranslationChoice.from(requestedSelection.configuration)
                            val paths = requestedSelection.sources.toMap()
                            val managed = java.io.File(app.filesDir, "chapters")
                            val bound = ReaderTranslationPresentation.capture(task, requestedSelection.chapterId, choice, paths, managed)
                            translationReceipt = bound
                            displayTranslation(if (bound != null && visible != null)
                                ReaderTranslationPresentation.select(listOf(visible), bound, requestedSelection.chapterId, choice, paths, managed) else null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (presentationOpen && translationSelection() == requestedSelection) _state.update {
                    it.copy(translating = false, translationError = true,
                        translationMessage = failure.message ?: "Chapter translation could not be scheduled.")
                }
            } finally {
                if (pendingTranslation === pending) {
                    pendingTranslation = null
                    // A short worker may have completed while its presentation was held
                    // behind a pending control. Reconcile even when the task flow is quiet.
                    translationSelectionEpoch.value += 1
                }
            }
        }
    }

    fun setChapterDetails(id: String, bookmarked: Boolean, status: com.mangalens.core.reader.ReadingStatus) {
        viewModelScope.launch {
            try {
                persistenceMutex.withLock {
                    val old = _state.value.library.firstOrNull { it.id == id } ?: return@withLock
                    val changed = (_state.value.activeChapter?.takeIf { it.id == id } ?: old)
                        .copy(bookmarked = bookmarked, readingStatus = status)
                    withContext(Dispatchers.IO) { library.save(changed) }
                    _state.update { current -> current.copy(
                        library = current.library.map { if (it.id == id) it.copy(bookmarked = bookmarked, readingStatus = status) else it },
                        activeChapter = current.activeChapter?.let { if (it.id == id) it.copy(bookmarked = bookmarked, readingStatus = status) else it },
                        error = null
                    ) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(error = failure.message ?: "Chapter status could not be saved. Please retry.") } }
        }
    }

    suspend fun updateChapterMetadata(id: String, metadata: LibraryChapterMetadata) {
        val captured = metadata.normalized()
        kotlin.coroutines.coroutineContext.ensureActive()
        // An accepted small journal mutation finishes even if its editor is recreated.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            persistenceMutex.withLock {
                check(_state.value.library.any { it.id == id }) { "This chapter is no longer saved. Reopen the Library." }
                val saved = withContext(Dispatchers.IO) { library.updateMetadata(id, captured) }
                _state.update { current -> current.copy(
                    library = current.library.map { if (it.id == id) it.withMetadata(captured).copy(updatedAt = saved.updatedAt) else it },
                    activeChapter = current.activeChapter?.let { if (it.id == id) it.withMetadata(captured) else it },
                    error = null
                ) }
            }
        }
    }

    fun deleteSavedChapter(id: String) {
        if (_state.value.activeChapter?.id == id) {
            ingestionJob?.cancel()
            clearTranslations()
            repository.clearChapterCache()
            _state.value = _state.value.copy(activeChapter = null, pages = emptyList(), loading = false)
        }
        viewModelScope.launch {
            try {
                translationCommands.withLock {
                    ChapterTranslationJobs.removeChapter(app, id)
                    persistenceMutex.withLock {
                        withContext(Dispatchers.IO) { library.remove(id) }
                        _state.update { it.copy(library = it.library.filterNot { saved -> saved.id == id }) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(error = failure.message ?: "Chapter could not be safely removed.") }
            }
        }
    }

    fun openSavedChapter(id: String) {
        val chapter = _state.value.library.firstOrNull { it.id == id }?.visitedAt(System.currentTimeMillis()) ?: return
        ingestionJob?.cancel()
        clearTranslations()
        _state.value = _state.value.withLibraryChapter(chapter)
        repository.restorePages(chapter.pages)
        queueReadingCheckpoint()
    }

    fun saveReadingPosition(id: String, position: Int, offset: Int) {
        val chapter = _state.value.activeChapter ?: return
        if (chapter.id != id) return
        _state.value = _state.value.copy(activeChapter = chapter.copy(position = position.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0)).visitedAt(System.currentTimeMillis()))
        queueReadingCheckpoint()
    }

    private fun queueReadingCheckpoint() {
        viewModelScope.launch {
            try { persistCurrentChapter() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(error = failure.message ?: "Reading history could not be saved. Please retry.") } }
        }
    }

    private suspend fun persistCurrentChapter() = persistenceMutex.withLock {
        val chapter = _state.value.activeChapter ?: return@withLock
        val pages = _state.value.pages
        if (pages.isEmpty()) return@withLock
        val saved = chapter.copy(pages = pages, updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { library.save(saved) }
        if (_state.value.activeChapter?.id == saved.id) {
            _state.value = _state.value.copy(activeChapter = _state.value.activeChapter?.copy(pages = saved.pages), library = (listOf(saved) + _state.value.library.filterNot { it.id == saved.id }))
        }
    }

    fun downloadChapter() {
        val manager = MediaDownloadManager(app)
        viewModelScope.launch {
            _state.value.pages.forEachIndexed { index, page ->
                val source = page.sourceUrl
                if (source.startsWith("http://") || source.startsWith("https://")) {
                    runCatching { manager.enqueue(source, "MangaLens page " + (index + 1)) }
                }
            }
        }
    }

    fun pauseTranslation(paused: Boolean) {
        pendingTranslation?.takeIf { it.selection == translationSelection() }?.let {
            it.request.setPaused(paused)
            _state.update { state -> state.copy(translationPaused = paused) }
            return
        }
        val task = displayedTranslation
        if (task != null) {
            controlChapterTranslation(task) { current ->
                if (paused) ChapterTranslationJobs.pause(app, current.id, current.generation)
                else ChapterTranslationJobs.resume(app, current.id, current.generation)
            }
            return
        }
    }

    fun cancelTranslation() {
        pendingTranslation?.takeIf { it.selection == translationSelection() }?.let {
            it.request.cancel()
            _state.update { state -> state.copy(translating = false, translationPaused = false,
                translationMessage = "Translation cancelled. Saved pages are still available.", translationError = false) }
            return
        }
        _state.value = _state.value.copy(translating = false, translationPaused = false)
        displayedTranslation?.let { task -> controlChapterTranslation(task) { current ->
            ChapterTranslationJobs.cancel(app, current.id, current.generation)
        } }
    }

    fun clearTranslations() {
        // Switching reader/configuration detaches its presentation. Durable work remains
        // owned by WorkManager until the user explicitly pauses or cancels that task.
        displayedTranslation = null
        translationReceipt = null
        translationSelectionEpoch.value += 1
        _state.update { it.copy(translating = false, translationPaused = false, translationDone = 0,
            translationTotal = 0, translationEnabled = false, translationError = false, translationMessage = null,
            overlays = emptyMap(), translatedBackgrounds = emptyMap(), promoPages = emptySet()) }
    }

    private fun controlChapterTranslation(target: ChapterTranslationTask,
        command: suspend (ChapterTranslationTask) -> ChapterTranslationTask?) {
        ChapterTranslationCommandScope.scope.launch {
            try {
                translationCommands.withLock {
                    val store = withContext(Dispatchers.IO) { ChapterTranslationStore.shared(app) }
                    // Commands are serialized, so a rapid Resume→Cancel follows the new
                    // generation returned by Resume while staying scoped to this task ID.
                    val current = store.get(target.id)?.takeUnless { it.validationPending } ?: return@withLock
                    if (current.ownerRequestId != target.ownerRequestId ||
                        (target.ownerRequestId == null && current.generation != target.generation)) return@withLock
                    val selected = translationSelection()
                    val bound = translationReceipt?.takeIf { it.taskId == target.id && it.owner == target.ownerRequestId &&
                        it.configuration == target.config } ?: ReaderTranslationPresentation.receipt(target)
                    if (!ReaderTranslationPresentation.matchesTask(bound, current)) return@withLock
                    val result = command(current)
                    if (result != null && presentationOpen && translationSelection() == selected && translationReceipt == bound) {
                        ReaderTranslationPresentation.continueReceipt(bound, result)?.let {
                            translationReceipt = it
                            translationSelectionEpoch.value += 1
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val visible = displayedTranslation
                if (presentationOpen && visible?.id == target.id && visible.ownerRequestId == target.ownerRequestId &&
                    (target.ownerRequestId != null || visible.generation == target.generation)) _state.update {
                    it.copy(translationError = true, translationMessage = failure.message ?: "Translation control failed.")
                }
            }
        }
    }

    override fun onCleared() {
        presentationOpen = false
        ingestionJob?.cancel()
        ocrPrefs.unregisterOnSharedPreferenceChangeListener(ocrPreferenceListener)
        // Durable workers own their resources and continue independently of this reader.
        super.onCleared()
    }
}
