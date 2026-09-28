package com.mangalens.core.reader

import android.content.Context
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
    private val chapterDir = File(context.cacheDir, "chapters").apply { mkdirs() }
    private val _pages = MutableStateFlow<List<ChapterPage>>(emptyList())
    val pages: StateFlow<List<ChapterPage>> = _pages

    suspend fun persistPage(index: Int, sourceUrl: String): ChapterPage = withContext(Dispatchers.IO) {
        val file = File(chapterDir, index.toString() + "_" + sha256(sourceUrl) + ".img")
        if (!file.exists()) {
            client.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Page download failed: HTTP " + response.code }
                val body = response.body ?: error("Empty image response")
                body.byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
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

    fun clearChapterCache() {
        chapterDir.listFiles()?.forEach { it.delete() }
        _pages.value = emptyList()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
