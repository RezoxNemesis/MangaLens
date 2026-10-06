package com.mangalens.core.reader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ProgressiveChapterRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val chapterDir = File(context.cacheDir, "chapters").apply { mkdirs() }
    private val _pages = MutableStateFlow<List<ChapterPage>>(emptyList())
    val pages: StateFlow<List<ChapterPage>> = _pages

    suspend fun persistPage(
        index: Int,
        sourceUrl: String,
        referer: String? = null,
        cookie: String? = null,
        userAgent: String? = null
    ): ChapterPage = withContext(Dispatchers.IO) {
        val page = downloadPage(index, sourceUrl, referer, cookie, userAgent)
        _pages.value = (_pages.value.filterNot { it.index == index } + page).sortedBy { it.index }
        page
    }

    suspend fun persistDiscoveredPages(
        urls: List<String>,
        referer: String? = null,
        cookie: String? = null,
        userAgent: String? = null
    ): List<ChapterPage> = coroutineScope {
        val candidates = urls
            .asSequence()
            .map(String::trim)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot(::looksLikeJunkUrl)
            .distinct()
            .take(MAX_CHAPTER_PAGES)
            .toList()

        if (candidates.isEmpty()) {
            _pages.value = emptyList()
            return@coroutineScope emptyList()
        }

        val gate = Semaphore(MAX_PARALLEL_PAGE_FETCHES)
        val pages = candidates.mapIndexed { index, url ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        downloadPage(index + 1, url, referer, cookie, userAgent)
                    }.getOrNull()
                }
            }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.index }
            .mapIndexed { index, page -> page.copy(index = index + 1) }

        _pages.value = pages
        pages
    }

    private suspend fun downloadPage(
        index: Int,
        sourceUrl: String,
        referer: String?,
        cookie: String?,
        userAgent: String?
    ): ChapterPage {
        require(!looksLikeJunkUrl(sourceUrl)) { "Filtered non-reader image URL." }

        val file = File(chapterDir, index.toString() + "_" + sha256(sourceUrl) + ".img")
        if (!file.exists() || file.length() == 0L || !isReadableChapterImage(file)) {
            file.delete()
            fetchImage(sourceUrl, file, referer, cookie, userAgent)
        }

        if (!isReadableChapterImage(file)) {
            file.delete()
            throw IOException("Downloaded chapter page is not a readable image.")
        }
        return ChapterPage(index, sourceUrl, file.absolutePath)
    }

    private suspend fun fetchImage(
        sourceUrl: String,
        destination: File,
        referer: String?,
        cookie: String?,
        userAgent: String?
    ) {
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()

        var lastFailure: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .apply {
                    referer?.takeIf { it.startsWith("http") }?.let { header("Referer", it) }
                    cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                        val failure = IOException("Page download failed: HTTP " + response.code)
                        if (!retryable) throw failure
                        lastFailure = failure
                        return@use
                    }

                    val mediaType = response.body?.contentType()
                    if (mediaType != null && (
                            mediaType.type.equals("text", true) ||
                                mediaType.subtype.contains("html", true) ||
                                mediaType.subtype.contains("json", true)
                            )
                    ) {
                        throw IOException("Page URL returned " + mediaType + " instead of an image.")
                    }

                    val body = response.body ?: throw IOException("Empty image response")
                    body.byteStream().buffered(BUFFER_SIZE).use { input ->
                        temp.outputStream().buffered(BUFFER_SIZE).use { output ->
                            input.copyTo(output, BUFFER_SIZE)
                        }
                    }

                    if (temp.length() < MIN_IMAGE_BYTES || !isReadableChapterImage(temp)) {
                        temp.delete()
                        throw IOException("Downloaded reader image is empty, tiny, or invalid.")
                    }

                    if (!temp.renameTo(destination)) {
                        temp.copyTo(destination, overwrite = true)
                        temp.delete()
                    }
                    return
                }
            } catch (failure: Throwable) {
                temp.delete()
                lastFailure = failure
            }

            if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                delay(350L * (attempt + 1))
            }
        }

        throw lastFailure ?: IOException("Unable to download chapter page.")
    }

    private fun isReadableChapterImage(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth < MIN_IMAGE_DIMENSION || bounds.outHeight < MIN_IMAGE_DIMENSION) return false
        val ratio = bounds.outWidth.toFloat() / bounds.outHeight.toFloat().coerceAtLeast(1f)
        if (ratio > 3.2f) return false
        return true
    }

    private fun looksLikeJunkUrl(url: String): Boolean {
        val lower = url.lowercase()
        val readerSignal = listOf("chapter", "page", "manga", "manhwa", "manhua", "comic", "webtoon", "reader", "cdn")
            .any(lower::contains)
        val junkSignal = listOf(
            "favicon", "sprite", "avatar", "emoji", "/icon/", "logo", "banner",
            "advert", "/ads/", "doubleclick", "tracking", "analytics", "discord",
            "announcement", "promo", "social-share", "placeholder", "captcha"
        ).any(lower::contains)
        return junkSignal && !readerSignal
    }

    suspend fun persistLocalImages(
        uris: List<Uri>,
        context: Context
    ): List<ChapterPage> = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one image." }
        val imported = mutableListOf<ChapterPage>()
        uris.forEachIndexed { index, uri ->
            val key = sha256(uri.toString() + "|" + index)
            val file = File(chapterDir, "local_" + key + ".img")
            if (!file.exists() || file.length() == 0L) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open selected image " + (index + 1))
                val temp = File(chapterDir, file.name + ".part")
                try {
                    input.use { source ->
                        temp.outputStream().buffered(BUFFER_SIZE).use { output ->
                            source.copyTo(output, BUFFER_SIZE)
                        }
                    }
                    require(temp.length() > 0L) { "Selected image " + (index + 1) + " is empty." }
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, true)
                        temp.delete()
                    }
                } catch (failure: Throwable) {
                    temp.delete()
                    throw failure
                }
            }
            imported += ChapterPage(index + 1, uri.toString(), file.absolutePath)
        }
        _pages.value = imported
        imported
    }

    fun clearChapterCache() {
        chapterDir.listFiles()?.forEach { it.delete() }
        _pages.value = emptyList()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    companion object {
        private const val MAX_CHAPTER_PAGES = 3000
        private const val MAX_PARALLEL_PAGE_FETCHES = 4
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val BUFFER_SIZE = 128 * 1024
        private const val MIN_IMAGE_BYTES = 4L * 1024L
        private const val MIN_IMAGE_DIMENSION = 180
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"
    }
}
