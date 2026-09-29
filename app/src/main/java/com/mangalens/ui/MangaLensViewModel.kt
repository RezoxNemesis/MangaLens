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
import com.mangalens.core.reader.ChapterPage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive

data class MangaLensUiState(
    val url: String = "",
    val mode: ContentType = ContentType.GENERIC_WEB,
    val pages: List<ChapterPage> = emptyList(),
    val chapters: List<MangaChapter> = emptyList(),
    val videoUrl: String? = null,
    val loading: Boolean = false,
    val translating: Boolean = false,
    val translationEnabled: Boolean = false,
    val overlays: Map<Int, List<TranslationOverlay>> = emptyMap(),
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val mangaTranslationEnabled: Boolean = false,
    val videoTranslationEnabled: Boolean = false,
    val webTranslationEnabled: Boolean = false,
    val targetLanguage: String = "hi",
    val translationStyle: String = "natural",
    val customTranslationStyle: String = "",
    val adBlockStats: AdBlockStats = AdBlockStats(),
)

class MangaLensViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("mangalens_preferences", Application.MODE_PRIVATE)
    private val statsStore = AdBlockStatsStore.shared
    private val router = UrlEngineRouter()
    private val repository = ProgressiveChapterRepository(application)
    private val acquirer = RenderedBrowserAcquirer(application, AdBlockEngine(statsStore))
    private val ocr = OcrInpaintingEngine()
    private val advancedOcr = AdvancedTranslationEngine(application)
    private val orezRefiner = com.mangalens.core.translation.TranslationOrezRefiner(application)
    private var translationJob: kotlinx.coroutines.Job? = null
    private var ingestionJob: kotlinx.coroutines.Job? = null
    private val chapterScraper = MangaChapterScraper(application)
    private val chapterCatalog = MangaChapterCatalogScraper(application)
    private val translator = TranslationService()
    val captchaBridge = CaptchaBridge()

    private val _state = MutableStateFlow(
        MangaLensUiState(
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme_mode", ThemeMode.SYSTEM.name) } ?: ThemeMode.SYSTEM,
            mangaTranslationEnabled = prefs.getBoolean("translation_manga", false),
            videoTranslationEnabled = prefs.getBoolean("translation_video", false),
            webTranslationEnabled = prefs.getBoolean("translation_web", false),
            targetLanguage = prefs.getString("translation_target", "hi") ?: "hi",
            translationStyle = prefs.getString("translation_style", "natural") ?: "natural",
            customTranslationStyle = prefs.getString("translation_custom_style", "") ?: "",
        )
    )
    val state: StateFlow<MangaLensUiState> = _state

    init {
        viewModelScope.launch { repository.pages.collect { pages -> _state.value = _state.value.copy(pages = pages) } }
        viewModelScope.launch { statsStore.stats.collect { stats -> _state.value = _state.value.copy(adBlockStats = stats) } }
    }

    fun setUrl(value: String) {
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
        if (normalized.isBlank()) return
        prefs.edit().putString("translation_target", normalized).apply()
        _state.value = _state.value.copy(targetLanguage = normalized)
    }

    fun setTranslationStyle(styleId: String) {
        val normalized = styleId.lowercase().trim()
        if (normalized.isBlank()) return
        prefs.edit().putString("translation_style", normalized).apply()
        _state.value = _state.value.copy(translationStyle = normalized)
    }

    fun setCustomTranslationStyle(instruction: String) {
        val normalized = instruction.trim().take(1200)
        prefs.edit().putString("translation_custom_style", normalized).apply()
        _state.value = _state.value.copy(customTranslationStyle = normalized)
    }
    fun resetAdBlockStats() = statsStore.reset()

    fun importLocalImages(uris: List<Uri>) {
        ingestionJob?.cancel()
        ingestionJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, translating = false, error = null, overlays = emptyMap(), translationEnabled = false)
            try {
                val pages = repository.persistLocalImages(uris, app)
                _state.value = _state.value.copy(
                    pages = pages,
                    mode = ContentType.IMAGE_CHAPTER,
                    loading = false,
                    mangaTranslationEnabled = true,
                    error = null
                )
                prefs.edit().putBoolean("translation_manga", true).apply()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.value = _state.value.copy(loading = false, error = "Image import failed: " + (failure.message ?: "Unable to read selected images"))
            }
        }
    }

    fun ingest() {
        ingestionJob?.cancel()
        val target = _state.value.url
        if (target.isBlank()) return
        ingestionJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                when (router.classifyUrl(target)) {
                    ContentType.VIDEO_STREAM -> _state.value = _state.value.copy(mode = ContentType.VIDEO_STREAM, videoUrl = target, loading = false)
                    ContentType.IMAGE_CHAPTER -> {
                        val chapters = runCatching { chapterCatalog.extract(target) }.getOrDefault(emptyList())
                        _state.value = _state.value.copy(chapters = chapters.map { MangaChapter(it.title, it.url) })
                        val result = acquirer.discoverWithCookie(target, 15_000L, null)
                        if (result.imageUrls.isNotEmpty()) repository.persistDiscoveredPages(result.imageUrls)
                        if (result.imageUrls.isEmpty()) {
                            val fallback = chapterScraper.extract(target)
                            if (fallback.isNotEmpty()) repository.persistDiscoveredPages(fallback)
                        }
                        if (result.imageUrls.isEmpty() && result.videoStreamUrls.isEmpty() && repository.pages.value.isEmpty()) {
                            captchaBridge.requestVerification(target, target.substringAfter("//").substringBefore("/").substringBefore(":").ifBlank { "unknown" }, "Security verification or inaccessible chapter detected.")
                        }
                        _state.value = _state.value.copy(mode = ContentType.IMAGE_CHAPTER, loading = false, error = result.navigationError)
                    }
                    ContentType.GENERIC_WEB -> _state.value = _state.value.copy(mode = ContentType.GENERIC_WEB, loading = false)
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message ?: "Acquisition failed")
            }
        }
    }

    fun ingestWithCookie(cookie: String) {
        ingestionJob?.cancel()
        val target = _state.value.url
        ingestionJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val result = acquirer.discoverWithCookie(target, 20_000L, cookie)
                if (result.imageUrls.isNotEmpty()) repository.persistDiscoveredPages(result.imageUrls)
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
        val path = page.localPath ?: return
        translationJob = viewModelScope.launch(Dispatchers.Default) {
            _state.value = _state.value.copy(translating = true, error = null)
            var bitmap: android.graphics.Bitmap? = null
            try {
                bitmap = decodeForOcr(path) ?: error("Unable to decode page image")
                val advancedRegions = advancedOcr.recognizeScriptAware(bitmap)
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
                _state.value = _state.value.copy(translating = false, translationEnabled = true, overlays = _state.value.overlays + (page.index to translated))
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(translating = false, error = t.message ?: "Local OCR translation failed")
            } finally {
                bitmap?.recycle()
            }
        }
    }

    fun translateChapter(targetLanguage: String = _state.value.targetLanguage) {
        translationJob?.cancel()
        val pages = _state.value.pages
        if (pages.isEmpty()) {
            _state.value = _state.value.copy(error = "No manga pages are available to translate.")
            return
        }
        prefs.edit().putBoolean("translation_manga", true).apply()
        _state.value = _state.value.copy(mangaTranslationEnabled = true, translating = true, error = null)
        translationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                for (page in pages) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (page.localPath.isNullOrBlank()) continue
                    translatePageInternal(page, targetLanguage)
                }
                _state.value = _state.value.copy(translating = false, translationEnabled = true)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(translating = false, error = t.message ?: "Chapter translation failed")
            }
        }
    }

    private suspend fun translatePageInternal(page: ChapterPage, targetLanguage: String) {
        val path = page.localPath ?: return
        val bitmap = decodeForOcr(path) ?: return
        try {
        val advancedRegions = advancedOcr.recognizeScriptAware(bitmap)
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
        _state.value = _state.value.copy(
            translating = true,
            translationEnabled = true,
            overlays = _state.value.overlays + (page.index to translated)
        )
        } finally {
            bitmap.recycle()
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

    fun clearTranslations() { _state.value = _state.value.copy(translationEnabled = false, overlays = emptyMap()) }

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