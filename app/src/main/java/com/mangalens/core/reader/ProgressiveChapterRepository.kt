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
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit
import java.util.UUID

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
    private val pagePublicationLock = Any()
    private val _pages = MutableStateFlow<List<ChapterPage>>(emptyList())
    val pages: StateFlow<List<ChapterPage>> = _pages

    suspend fun persistPage(index: Int, sourceUrl: String, referrer: String? = null): ChapterPage = withContext(Dispatchers.IO) { transferMutex.withLock {
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        val file = File(chapterDir, index.toString() + "_" + sha256(sourceUrl) + ".img")
        val revision = persistVerified(file, jobContext) { temp ->
            acquireRemote(sourceUrl, referrer, temp)
        }
        val page = ChapterPage(index, sourceUrl, file.absolutePath, contentRevision = revision)
        synchronized(pagePublicationLock) {
            jobContext.ensureActive()
            _pages.value = (_pages.value.filterNot { it.index == index } + page).sortedBy { it.index }
        }
        page
    } }

    suspend fun persistDiscoveredPages(urls: List<String>, referrer: String? = null): List<ChapterPage> {
        val result = mutableListOf<ChapterPage>()
        urls.distinct().forEachIndexed { index, url ->
            result += persistPage(index + 1, url, referrer)
        }
        return result
    }

    suspend fun persistLocalImages(uris: List<Uri>, context: Context,
        documentSources: List<ChapterDocumentSource?> = emptyList()): List<ChapterPage> = withContext(Dispatchers.IO) { transferMutex.withLock {
        require(uris.isNotEmpty()) { "Select at least one image." }
        require(documentSources.isEmpty() || documentSources.size == uris.size) { "Original document pages must match the prepared images." }
        val jobContext = currentCoroutineContext()
        val imported = mutableListOf<ChapterPage>()
        uris.forEachIndexed { index, uri ->
            val document = documentSources.getOrNull(index)?.validate()
            val key = sha256(document?.let { it.uri + "|" + it.documentSha256 + "|" + it.kind + "|" + it.pageIndex + "|" + it.archiveEntryName }
                ?: (uri.toString() + "|" + index))
            val file = File(chapterDir, "local_" + key + ".img")
            val revision = persistVerified(file, jobContext) { temp ->
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open selected image " + (index + 1))
                input.use { copySynced(it, temp, jobContext) }
            }
            jobContext.ensureActive()
            imported += ChapterPage(index + 1, document?.uri ?: uri.toString(), file.absolutePath,
                contentRevision = revision, documentSource = document)
        }
        synchronized(pagePublicationLock) {
            jobContext.ensureActive()
            _pages.value = imported
        }
        imported
    } }

    /** Reacquire one damaged source without resetting its chapter or touching neighbouring pages. */
    suspend fun repairPage(page: ChapterPage, context: Context, referrer: String? = null): ChapterPage =
        withContext(Dispatchers.IO) { transferMutex.withLock {
            val jobContext = currentCoroutineContext()
            jobContext.ensureActive()
            check(_pages.value.any { it == page }) { "The selected chapter page has changed. Reopen it before retrying." }
            val file = page.localPath?.let(::File) ?: File(chapterDir,
                page.index.toString() + "_" + sha256(page.sourceUrl) + ".img")
            check(file.canonicalFile.parentFile == chapterDir.canonicalFile && file.name.endsWith(".img")) {
                "The page is not in managed chapter storage. Import the original again from Files."
            }
            val uri = Uri.parse(page.sourceUrl)
            val revision = persistVerified(file, jobContext, forceAcquire = true, beforePromotion = {
                check(_pages.value.any { it == page }) { "The selected chapter page has changed. Reopen it before retrying." }
            }) { temp ->
                if (page.documentSource != null) {
                    DocumentImporter.reacquirePage(context, page.documentSource, temp)
                } else if (uri.scheme == "http" || uri.scheme == "https") {
                    acquireRemote(page.sourceUrl, referrer, temp)
                } else {
                    check(uri.scheme == "content" || uri.scheme == "file") { "Open the page source in Web mode or import its original from Files." }
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Unable to reopen selected image " + page.index)
                    input.use { copySynced(it, temp, jobContext) }
                }
            }
            val repaired = page.copy(localPath = file.absolutePath, error = null, contentRevision = revision)
            synchronized(pagePublicationLock) {
                jobContext.ensureActive()
                check(_pages.value.any { it == page }) { "The selected chapter page has changed. Reopen it before retrying." }
                _pages.value = _pages.value.map { if (it == page) repaired else it }
            }
            repaired
        } }

    private suspend fun persistVerified(file: File, jobContext: CoroutineContext, forceAcquire: Boolean = false,
        beforePromotion: () -> Unit = {},
        acquire: suspend (File) -> Unit): String {
        jobContext.ensureActive()
        val cachedRevision = try {
            if (!forceAcquire && file.isFile) validatedRevision(file, jobContext) else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (cachedRevision != null) return _pages.value.firstOrNull { page ->
            page.localPath == file.absolutePath && page.contentRevision?.substringBefore(':') == cachedRevision
        }?.contentRevision ?: cachedRevision
        val temp = File(chapterDir, file.name + ".part")
        check(!temp.exists() || temp.delete()) { "Unable to clear an incomplete page download." }
        try {
            acquire(temp)
            FileOutputStream(temp, true).use { it.fd.sync() }
            val revision = validatedRevision(temp, jobContext)
            // POSIX rename atomically replaces only after validation and fsync. Never copy
            // over the old image: an interrupted copy would destroy the recoverable source.
            synchronized(pagePublicationLock) {
                jobContext.ensureActive()
                beforePromotion()
                android.system.Os.rename(temp.absolutePath, file.absolutePath)
            }
            // Replacing damaged bytes with the identical original is still a new cache
            // incarnation: remembered failed decodes must be allowed to run again.
            return revision + ":" + UUID.randomUUID()
        } finally {
            temp.delete()
        }
    }

    /** Cancel this exact request while headers or body IO are blocked, then join its cleanup. */
    private suspend fun acquireRemote(sourceUrl: String, referrer: String?, temp: File) = coroutineScope {
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        val call = client.newCall(Request.Builder().url(sourceUrl).apply {
            referrer?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { header("Referer", it.substringBefore('#')) }
        }.build())
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                jobContext.ensureActive()
                check(response.isSuccessful) { "Page download failed: HTTP " + response.code }
                val body = response.body ?: error("Empty image response")
                check(body.contentLength() <= MAX_PAGE_BYTES) {
                    "Chapter page exceeds the safe per-page limit of " + formatLimit(MAX_PAGE_BYTES) + "."
                }
                body.byteStream().use { copySynced(it, temp, jobContext) }
            }
        } catch (failure: Exception) {
            // OkHttp reports canceled socket IO as IOException. Retain coroutine
            // cancellation so the old chapter cannot receive a source-error update.
            jobContext.ensureActive()
            throw failure
        } finally {
            cancellation.cancel()
        }
    }

    private fun copySynced(input: InputStream, temp: File, jobContext: CoroutineContext) {
        FileOutputStream(temp).use { stream ->
            stream.buffered().use { output ->
                BoundedTransfer.copy(input, output, MAX_PAGE_BYTES, checkActive = { jobContext.ensureActive() })
                output.flush()
                stream.fd.sync()
            }
        }
    }

    private fun validatedRevision(file: File, jobContext: CoroutineContext): String {
        check(file.length() in 1..MAX_PAGE_BYTES) { "Chapter page is empty or exceeds the safe per-page limit of " + formatLimit(MAX_PAGE_BYTES) + "." }
        validateImage(file, jobContext)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                jobContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Changing the active chapter must never delete durable offline pages. */
    fun clearChapterCache() { synchronized(pagePublicationLock) { _pages.value = emptyList() } }
    fun restorePages(pages: List<ChapterPage>) { synchronized(pagePublicationLock) { _pages.value = pages } }

    private fun validateImage(file: File, jobContext: CoroutineContext) {
        jobContext.ensureActive()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The page is not a supported image. Open the source in Web mode or choose another image." }
        check(bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "The image is too large to decode safely." }
        // An intact dimensions header does not prove readable pixels. Verify a
        // bounded software raster without allocating the full original page.
        var sample = 1
        while ((bounds.outWidth.toLong() + sample - 1) / sample > VERIFY_DECODE_SIDE ||
            (bounds.outHeight.toLong() + sample - 1) / sample > VERIFY_DECODE_SIDE) sample *= 2
        jobContext.ensureActive()
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inMutable = false
                inScaled = false
            }) ?: error("The page pixels could not be decoded. Retry its source or import the original again.")
        try {
            check(bitmap.width in 1..VERIFY_DECODE_SIDE && bitmap.height in 1..VERIFY_DECODE_SIDE) {
                "The image decoder exceeded the safe verification limit."
            }
            jobContext.ensureActive()
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun formatLimit(bytes: Long): String = (bytes / (1024L * 1024L)).toString() + " MiB"

    private companion object {
        const val MAX_PAGE_BYTES = 40L * 1024L * 1024L
        const val VERIFY_DECODE_SIDE = 512
    }
}
