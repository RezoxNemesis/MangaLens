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

class ProgressiveChapterRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val chapterDir = File(context.filesDir, "chapters").apply {
        check(isDirectory || mkdirs()) { "Unable to create persistent chapter storage." }
    }
    private val _pages = MutableStateFlow<List<ChapterPage>>(emptyList())
    val pages: StateFlow<List<ChapterPage>> = _pages

    suspend fun persistPage(index: Int, sourceUrl: String): ChapterPage = withContext(Dispatchers.IO) {
        val file = File(chapterDir, index.toString() + "_" + sha256(sourceUrl) + ".img")
        if (file.length() == 0L) file.delete()
        if (!file.isFile) {
            val temp = File(chapterDir, file.name + ".part")
            temp.delete()
            try {
                client.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "Page download failed: HTTP " + response.code }
                    val body = response.body ?: error("Empty image response")
                    body.byteStream().use { input ->
                        temp.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                }
                check(temp.length() > 0L) { "Downloaded chapter page is empty." }
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
        _pages.value = (_pages.value + page).sortedBy { it.index }
        page
    }

    suspend fun persistDiscoveredPages(urls: List<String>): List<ChapterPage> {
        val result = mutableListOf<ChapterPage>()
        urls.distinct().forEachIndexed { index, url ->
            result += persistPage(index + 1, url)
        }
        return result
    }

    suspend fun persistLocalImages(uris: List<Uri>, context: Context): List<ChapterPage> = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one image." }
        val imported = mutableListOf<ChapterPage>()
        uris.forEachIndexed { index, uri ->
            val key = sha256(uri.toString() + "|" + index)
            val file = File(chapterDir, "local_" + key + ".img")
            if (!file.exists() || file.length() == 0L) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open selected image " + (index + 1))
                val temp = File(chapterDir, file.name + ".part")
                try {
                    input.use { source -> temp.outputStream().buffered().use { output -> source.copyTo(output, 64 * 1024) } }
                    require(temp.length() > 0L) { "Selected image " + (index + 1) + " is empty." }
                    if (!temp.renameTo(file)) temp.copyTo(file, true).also { temp.delete() }
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
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
