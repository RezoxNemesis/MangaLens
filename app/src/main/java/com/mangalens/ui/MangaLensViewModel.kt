package com.mangalens.ui

import android.app.Application
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangalens.acquisition.RenderedBrowserAcquirer
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockStats
import com.mangalens.core.adblock.AdBlockStatsStore
import com.mangalens.core.model.ContentType
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.core.translation.OcrInpaintingEngine
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.MangaChapterScraper
import com.mangalens.engine.MangaChapterCatalogScraper
import com.mangalens.core.model.MangaChapter
import com.mangalens.download.MediaDownloadManager
import com.mangalens.core.translation.TranslationService
import com.mangalens.core.verification.CaptchaBridge
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.ui.reader.TranslationOverlay
import com.mangalens.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive

data class MangaLensUiState(
    val library: List<SavedChapter> = emptyList(),
    val activeChapter: SavedChapter? = null,
    val url: String = "",
    val mode: ContentType = ContentType.GENERIC_WEB,
    val pages: List<ChapterPage> = emptyList(),
    val chapters: List<MangaChapter> = emptyList(),
    val videoUrl: String? = null,
    val loading: Boolean = false,
    val translating: Boolean = false,
    val translationPaused: Boolean = false,
    val translationDone: Int = 0,
    val translationTotal: Int = 0,
    val translationEnabled: Boolean = false,
    val overlays: Map<Int, List<TranslationOverlay>> = emptyMap(),
    val error: String? = null,
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

class MangaLensViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("mangalens_preferences", Application.MODE_PRIVATE)
    private val statsStore = AdBlockStatsStore.shared
    private val router = UrlEngineRouter()
    private val persistenceMutex = kotlinx.coroutines.sync.Mutex()
    private val library = ChapterLibrary(application)
    private val repository = ProgressiveChapterRepository(application)
    private val acquirer = RenderedBrowserAcquirer(application, AdBlockEngine(statsStore))
    private val ocr = OcrInpaintingEngine()
    private val advancedOcr = AdvancedTranslationEngine(application)
    private val orezRefiner = com.mangalens.core.translation.TranslationOrezRefiner(application)
    private val translationMutex = kotlinx.coroutines.sync.Mutex()
    private val translationPause = MutableStateFlow(false)
    private var translationJob: kotlinx.coroutines.Job? = null
    private var ingestionJob: kotlinx.coroutines.Job? = null
    private var translateWhenPagesReady = false
    private val chapterScraper = MangaChapterScraper(application)
    private val staticAcquirer = com.mangalens.core.acquisition.StaticChapterAcquirer()
    private val chapterCatalog = MangaChapterCatalogScraper(application)
    private val translator = TranslationService()
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

    init {
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
    }

    fun setUrl(value: String) {
        prefs.edit().putString("last_url", value.trim()).apply()
        _state.value = _state.value.copy(url = value.trim(), mode = if (value.isBlank()) ContentType.GENERIC_WEB else router.classifyUrl(value))
    }
    fun setMode(mode: ContentType) { _state.value = _state.value.copy(mode = mode) }
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

    fun importLocalImages(uris: List<Uri>) {
        clearTranslations()
        val previousIngestion = ingestionJob
        previousIngestion?.cancel()
        ingestionJob = viewModelScope.launch {
            previousIngestion?.join()
            _state.value = _state.value.copy(loading = true, translating = false, error = null, overlays = emptyMap(), translationEnabled = false)
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
        ingestionJob = viewModelScope.launch {
            previousIngestion?.join()
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                when (selectedMode) {
                    ContentType.VIDEO_STREAM -> _state.value = _state.value.copy(mode = ContentType.VIDEO_STREAM, videoUrl = target, loading = false)
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
                        if (result.imageUrls.isEmpty() && result.videoStreamUrls.isEmpty() && repository.pages.value.isEmpty()) {
                            captchaBridge.requestVerification(target, target.substringAfter("//").substringBefore("/").substringBefore(":").ifBlank { "unknown" }, "Security verification or inaccessible chapter detected.")
                        }
                        _state.value = _state.value.copy(mode = ContentType.IMAGE_CHAPTER, loading = false, error = result.navigationError)
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
                        _state.value = _state.value.copy(error = "No chapter images could be loaded. Check the URL or complete the site's verification, then retry.")
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message ?: "Acquisition failed")
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
        translationJob?.cancel()
        if (!_state.value.mangaTranslationEnabled) {
            _state.value = _state.value.copy(error = "Manga translation is disabled in Settings → Translation Modules.")
            return
        }
        val path = page.localPath ?: run {
            _state.value = _state.value.copy(error = "This page is not available as a local image yet. Reopen the chapter or import the image from Files.")
            return
        }
        translationJob = viewModelScope.launch(Dispatchers.Default) { translationMutex.withLock {
            _state.value = _state.value.copy(translating = true, error = null)
            var bitmap: android.graphics.Bitmap? = null
            try {
                bitmap = decodeForOcr(path) ?: error("Unable to decode page image")
                val advancedRegions = advancedOcr.recognizeScriptAware(bitmap)
                if (advancedRegions.isEmpty()) error("No readable text was detected on this page. Try a clearer, higher-resolution image.")
                val translated = advancedRegions.map { region ->
                    val draft = translator.translate(region.source, targetLanguage)
                    val refined = orezRefiner.refine(
                        region.source,
                        draft,
                        targetLanguage,
                        if (_state.value.translationStyle == "custom") {
                            com.mangalens.core.translation.TranslationStyleProfile.custom(_state.value.customTranslationStyle)
                        } else {
                            com.mangalens.core.translation.TranslationStyleProfile.fromId(_state.value.translationStyle)
                        },
                        chapterContext = _state.value.overlays.values.flatten().takeLast(8)
                            .joinToString("\n") { it.translatedText },
                    )
                    rememberTranslation(region.source, refined, targetLanguage)
                    TranslationOverlay(
                        region = com.mangalens.core.translation.OcrRegion(
                            region.source,
                            region.bounds.left.toInt(),
                            region.bounds.top.toInt(),
                            region.bounds.right.toInt(),
                            region.bounds.bottom.toInt()
                        ),
                        translatedText = refined,
                        textColorArgb = region.textColor,
                        backgroundColorArgb = region.backgroundColor,
                        fontSizePx = region.textSize,
                        maxWidthPx = region.bounds.width(),
                        imageWidthPx = bitmap.width,
                        imageHeightPx = bitmap.height
                    )
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(translating = false, translationEnabled = true, overlays = _state.value.overlays + (page.index to translated))
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(translating = false, error = t.message ?: "Local OCR translation failed")
            } finally {
                bitmap?.recycle()
            }
        } }
    }

    fun translateChapter(targetLanguage: String = _state.value.targetLanguage) {
        translationJob?.cancel()
        val pages = _state.value.pages
        if (pages.isEmpty()) {
            _state.value = _state.value.copy(error = "No manga pages are available to translate.")
            return
        }
        prefs.edit().putBoolean("translation_manga", true).apply()
        translationPause.value = false
        _state.value = _state.value.copy(mangaTranslationEnabled = true, translating = true, translationPaused = false, translationDone = 0, translationTotal = pages.size, error = null)
        translationJob = viewModelScope.launch(Dispatchers.Default) { translationMutex.withLock {
            try {
                for ((index, page) in pages.withIndex()) {
                    translationPause.first { !it }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (page.localPath.isNullOrBlank()) continue
                    try { translatePageInternal(page, targetLanguage) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { _state.value = _state.value.copy(error = "Page ${page.index} translation failed. Retry this page.") }
                    _state.value = _state.value.copy(translationDone = index + 1)
                }
                val hasTranslations = _state.value.overlays.values.any { it.isNotEmpty() }
                _state.value = _state.value.copy(
                    translating = false,
                    translationEnabled = hasTranslations,
                    error = if (hasTranslations) null else "No readable text was detected in this chapter. Try another page or a clearer source."
                )
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(translating = false, error = t.message ?: "Chapter translation failed")
            }
        } }
    }

    private suspend fun translatePageInternal(page: ChapterPage, targetLanguage: String) {
        val path = page.localPath ?: return
        val bitmap = decodeForOcr(path) ?: return
        try {
        val advancedRegions = advancedOcr.recognizeScriptAware(bitmap)
        if (advancedRegions.isEmpty()) {
            _state.value = _state.value.copy(error = "No readable text was detected on page " + page.index + ".")
            return
        }
        val style = if (_state.value.translationStyle == "custom") {
            com.mangalens.core.translation.TranslationStyleProfile.custom(_state.value.customTranslationStyle)
        } else {
            com.mangalens.core.translation.TranslationStyleProfile.fromId(_state.value.translationStyle)
        }
        val chapterContext = _state.value.overlays.values.flatten().takeLast(12)
            .joinToString("\n") { it.translatedText }
        val translated = advancedRegions.map { region ->
            TranslationOverlay(
                region = com.mangalens.core.translation.OcrRegion(
                    region.source, region.bounds.left.toInt(), region.bounds.top.toInt(),
                    region.bounds.right.toInt(), region.bounds.bottom.toInt()
                ),
                translatedText = orezRefiner.refine(
                    region.source,
                    translator.translate(region.source, targetLanguage),
                    targetLanguage,
                    style,
                    chapterContext = chapterContext,
                ).also { rememberTranslation(region.source, it, targetLanguage) },
                textColorArgb = region.textColor,
                backgroundColorArgb = region.backgroundColor,
                fontSizePx = region.textSize,
                maxWidthPx = region.bounds.width(),
                        imageWidthPx = bitmap.width,
                        imageHeightPx = bitmap.height
            )
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _state.value = _state.value.copy(
            translating = true,
            translationEnabled = true,
            overlays = _state.value.overlays + (page.index to translated)
        )
        } finally {
            bitmap.recycle()
        }
    }

    fun setChapterDetails(id: String, bookmarked: Boolean, status: com.mangalens.core.reader.ReadingStatus) {
        viewModelScope.launch {
            persistenceMutex.withLock {
                val old = _state.value.library.firstOrNull { it.id == id } ?: return@withLock
                val changed = old.copy(bookmarked = bookmarked, readingStatus = status)
                withContext(Dispatchers.IO) { library.save(changed) }
                _state.value = _state.value.copy(
                    library = _state.value.library.map { if (it.id == id) changed else it },
                    activeChapter = _state.value.activeChapter?.let { if (it.id == id) it.copy(bookmarked = bookmarked, readingStatus = status) else it }
                )
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
            persistenceMutex.withLock {
                withContext(Dispatchers.IO) { library.remove(id) }
                _state.value = _state.value.copy(library = _state.value.library.filterNot { it.id == id })
            }
        }
    }

    fun openSavedChapter(id: String) {
        val chapter = _state.value.library.firstOrNull { it.id == id } ?: return
        ingestionJob?.cancel()
        clearTranslations()
        _state.value = _state.value.copy(activeChapter = chapter, url = chapter.sourceUrl, mode = ContentType.IMAGE_CHAPTER, loading = false)
        repository.restorePages(chapter.pages)
    }

    fun saveReadingPosition(id: String, position: Int, offset: Int) {
        val chapter = _state.value.activeChapter ?: return
        if (chapter.id != id) return
        _state.value = _state.value.copy(activeChapter = chapter.copy(position = position.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0)))
        viewModelScope.launch { persistCurrentChapter() }
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

    private fun decodeForOcr(path: String, maxDimension: Int = 2400): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            inMutable = false
        }
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
    }

    fun pauseTranslation(paused: Boolean) {
        translationPause.value = paused
        _state.value = _state.value.copy(translationPaused = paused)
    }

    fun cancelTranslation() {
        translationJob?.cancel()
        translationPause.value = false
        _state.value = _state.value.copy(translating = false, translationPaused = false)
    }

    fun clearTranslations() {
        translationJob?.cancel()
        _state.value = _state.value.copy(translating = false, translationPaused = false, translationDone = 0, translationTotal = 0, translationEnabled = false, overlays = emptyMap())
    }

    private suspend fun rememberTranslation(source: String, target: String, language: String) {
        if (source.isBlank() || target.isBlank()) return
        runCatching {
            _state.value
            val key = (source.trim() + "|" + language.trim()).hashCode().toUInt().toString(16)
            OrezRoomDatabase.get(app).datasets().insertTranslations(
                listOf(com.mangalens.orez.OrezTranslationEntity(key, source.trim(), target.trim(), language.trim()))
            )
        }
    }

    override fun onCleared() {
        translationJob?.cancel()
        ingestionJob?.cancel()
        ocr.close()
        advancedOcr.close()
        orezRefiner.close()
        translator.close()
        super.onCleared()
    }
}