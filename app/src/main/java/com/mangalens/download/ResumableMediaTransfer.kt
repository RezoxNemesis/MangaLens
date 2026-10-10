package com.mangalens.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Downloads one representation; publishes only after its advertised length is complete. */
internal class ResumableMediaTransfer(private val client: OkHttpClient,
    private val privateFileCloseFailed: () -> Unit = {}) {
    suspend fun download(
        url: String,
        temp: File,
        validatorFile: File,
        expectedMime: String? = null,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        temp.parentFile?.mkdirs()
        val validator = validatorFile.takeIf { it.isFile && it.length() <= 512L }
            ?.let { file -> file.inputStream().useOwnedPrivateFile(privateFileCloseFailed) { it.reader(Charsets.UTF_8).readText() } }
            ?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        if (temp.length() > 0L && validator == null) reset(temp, validatorFile)
        var offset = temp.length()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13")
            .header("Accept", "*/*")
            // Ranges and persisted bytes must refer to the same, uncompressed representation.
            .header("Accept-Encoding", "identity")
            .apply {
                if (offset > 0L) {
                    header("Range", "bytes=$offset-")
                    header("If-Range", validator!!)
                }
            }.build()
        val call = client.newCall(request)
        coroutineScope {
            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (offset > 0L && response.code == 200) {
                        reset(temp, validatorFile)
                        offset = 0L
                    }
                    if (response.code == 416) {
                        reset(temp, validatorFile)
                        throw IOException("The server rejected the saved range; retrying from the start.")
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (expectsMedia(expectedMime) && (
                            contentType.startsWith("text/html") ||
                                contentType.startsWith("application/xhtml") ||
                                contentType.startsWith("application/json")
                            )) {
                        throw IOException("Media source returned ${contentType.ifBlank { "a web page" }} instead of media.")
                    }
                    val body = response.body ?: throw IOException("Empty response")
                    val announced = body.contentLength()
                    val range = if (response.code == 206) parseRange(response.header("Content-Range")) else null
                    if (response.code == 206 && (range == null || range.start != offset ||
                            (announced >= 0L && announced != range.end - range.start + 1L))) {
                        reset(temp, validatorFile)
                        throw IOException("Server returned an invalid download resume range.")
                    }
                    val freshValidator = response.header("ETag")?.takeIf { !it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    if (offset > 0L && freshValidator != null && freshValidator != validator) {
                        reset(temp, validatorFile)
                        throw IOException("The download representation changed during resume.")
                    }
                    if (freshValidator != null && freshValidator.length <= 512) {
                        validatorFile.outputStream().useOwnedPrivateFile(privateFileCloseFailed) {
                            it.write(freshValidator.toByteArray(Charsets.UTF_8))
                        }
                    } else {
                        // Never retain a previous representation's validator after a restart.
                        validatorFile.delete()
                    }
                    val total = range?.total ?: if (announced >= 0L) offset + announced else -1L
                    var done = offset
                    onProgress(done, total)
                    body.byteStream().buffered(BUFFER_SIZE).use { input ->
                        FileOutputStream(temp, offset > 0L)
                            .wrapOwnedPrivateFile(privateFileCloseFailed) { it.buffered(BUFFER_SIZE) }
                            .useOwnedPrivateFile(privateFileCloseFailed) { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(done, total)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (done == 0L || (total >= 0L && done != total)) {
                        throw IOException("The download ended before the complete file was received.")
                    }
                    if (expectsMedia(expectedMime) && looksLikeHtml(temp)) {
                        reset(temp, validatorFile)
                        throw IOException("The resolved media URL returned HTML instead of the requested media.")
                    }
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                cancellation.cancel()
            }
        }
    }

    private fun expectsMedia(expectedMime: String?): Boolean =
        expectedMime?.let { it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") || it == "audio/" } == true

    private fun looksLikeHtml(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() == 0L) return@runCatching false
        val bytes = file.inputStream().useOwnedPrivateFile(privateFileCloseFailed) { input ->
            ByteArray(minOf(1024L, file.length()).toInt()).also { input.read(it) }
        }
        val text = bytes.toString(Charsets.UTF_8).trimStart().lowercase()
        text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head") ||
            text.startsWith("<script") || text.contains("<body")
    }.getOrElse { failure ->
        if (failure.hasUnprovenPrivateClose()) throw failure
        false
    }

    private fun reset(temp: File, validator: File) {
        if (temp.exists() && !temp.delete()) throw IOException("Unable to reset the saved download.")
        validator.delete()
    }

    private data class Range(val start: Long, val end: Long, val total: Long)
    private fun parseRange(value: String?): Range? {
        val match = RANGE.matchEntire(value.orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return Range(start, end, total).takeIf { start <= end && end < total }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
