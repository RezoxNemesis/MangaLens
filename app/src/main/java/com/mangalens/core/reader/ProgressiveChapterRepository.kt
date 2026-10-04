package com.mangalens.core.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit

class ProgressiveChapterRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().removeHeader("Cookie")
            CookieManager.getInstance().getCookie(request.url.toString())?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            chain.proceed(builder.build())
        }.build()
) {
    private val chapterDir = File(context.filesDir, "chapters").apply {
        check(isDirectory || mkdirs()) { "Unable to create persistent chapter storage." }
    }
    private val transferMutex = Mutex()
    private val _pages = MutableStateFlow<List<ChapterPage>>(emptyList())
    val pages: StateFlow<List<ChapterPage>> = _pages

    suspend fun persistPage(index: Int, sourceUrl: String, referrer: String? = null): ChapterPage = withContext(Dispatchers.IO) { transferMutex.withLock {
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        val file = File(chapterDir, index.toString() + "_" + sha256(sourceUrl) + ".img")
        require(com.mangalens.core.acquisition.ChapterImagePolicy.accepts(sourceUrl)) { "Non-chapter verification or placeholder image excluded." }
        if (file.isFile && runCatching { validateImage(file, remote = true) }.isFailure) file.delete()
        if (!file.isFile) {
            val temp = File(chapterDir, file.name + ".part")
            temp.delete()
            try {
                val call = client.newCall(Request.Builder().url(sourceUrl).apply {
                    referrer?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { header("Referer", it.substringBefore('#')) }
                }.build())
                val watcher = kotlinx.coroutines.CoroutineScope(jobContext).launch {
                    try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() }
                }
                try { call.execute().use { response ->
                    check(response.isSuccessful) { "Page download failed: HTTP " + response.code }
                    val body = response.body ?: error("Empty image response")
                    val declaredLength = body.contentLength()
                    check(declaredLength <= MAX_PAGE_BYTES) {
                        "Chapter page exceeds the safe per-page limit of " + formatLimit(MAX_PAGE_BYTES) + "."
                    }
                    body.byteStream().use { input ->
                        temp.outputStream().buffered().use { output ->
                            BoundedTransfer.copy(input, output, MAX_PAGE_BYTES, checkActive = { jobContext.ensureActive() })
                        }
                    }
                }
                } finally { watcher.cancel() }
                check(temp.length() > 0L) { "Downloaded chapter page is empty." }
                validateImage(temp, remote = true)
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
                check(file.isFile && file.length() > 0L) { "Unable to persist downloaded chapter page." }
            } catch (failure: Throwable) {
                temp.delete()
                throw failure
            }
        }
        val page = ChapterPage(index, sourceUrl, file.absolutePath)
        jobContext.ensureActive()
        _pages.value = (_pages.value.filterNot { it.index == index } + page).sortedBy { it.index }
        page
    } }

    suspend fun persistDiscoveredPages(urls: List<String>, referrer: String? = null): List<ChapterPage> {
        val result = mutableListOf<ChapterPage>()
        urls.distinct().filter { com.mangalens.core.acquisition.ChapterImagePolicy.accepts(it) }.forEachIndexed { index, url ->
            try { result += persistPage(index + 1, url, referrer) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val failed = ChapterPage(index + 1, url, error = failure.message ?: "Page could not load. Retry to recover.")
                result += failed
                _pages.value = result.toList()
            }
        }
        _pages.value = result.toList()
        return result
    }

    suspend fun persistLocalImages(uris: List<Uri>, context: Context): List<ChapterPage> = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one image." }
        val jobContext = currentCoroutineContext()
        val imported = mutableListOf<ChapterPage>()
        uris.forEachIndexed { index, uri ->
            val key = sha256(uri.toString() + "|" + index)
            val file = File(chapterDir, "local_" + key + ".img")
            if (!file.exists() || file.length() == 0L) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open selected image " + (index + 1))
                val temp = File(chapterDir, file.name + ".part")
                try {
                    input.use { source ->
                        temp.outputStream().buffered().use { output ->
                            BoundedTransfer.copy(source, output, MAX_PAGE_BYTES, checkActive = { jobContext.ensureActive() })
                        }
                    }
                    require(temp.length() > 0L) { "Selected image " + (index + 1) + " is empty." }
                    validateImage(temp)
                    if (!temp.renameTo(file)) temp.copyTo(file, true).also { temp.delete() }
                } catch (failure: Throwable) {
                    temp.delete()
                    throw failure
                }
            }
            jobContext.ensureActive()
            imported += ChapterPage(index + 1, uri.toString(), file.absolutePath)
        }
        _pages.value = imported
        imported
    }

    /** Changing the active chapter must never delete durable offline pages. */
    fun clearChapterCache() { _pages.value = emptyList() }
    fun restorePages(pages: List<ChapterPage>) { _pages.value = pages }

    private fun validateImage(file: File, remote: Boolean = false) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The page is not a supported image. Open the source in Web mode or choose another image." }
        if (remote) check(bounds.outWidth >= 100 && bounds.outHeight >= 100) { "Tiny loading/placeholder image excluded; retry the source." }
        check(bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "The image is too large to decode safely." }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun formatLimit(bytes: Long): String = (bytes / (1024L * 1024L)).toString() + " MiB"

    private companion object {
        const val MAX_PAGE_BYTES = 40L * 1024L * 1024L
    }
}
