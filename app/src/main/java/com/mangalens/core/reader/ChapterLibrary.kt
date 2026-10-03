package com.mangalens.core.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class ReadingStatus(val label: String) { READING("Reading"), COMPLETED("Completed"), ON_HOLD("On hold") }

data class SavedChapter(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val pages: List<ChapterPage>,
    val position: Int = 0,
    val scrollOffset: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val bookmarked: Boolean = false,
    val readingStatus: ReadingStatus = ReadingStatus.READING
)

/** Small chapter manifests; images remain in managed files and are never read into RAM here. */
class ChapterLibrary(context: Context) {
    private val directory = File(context.filesDir, "chapter_library").apply { mkdirs() }

    @Synchronized
    fun list(): List<SavedChapter> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { read(it) }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(chapter: SavedChapter) {
        require(chapter.id.matches(Regex("[a-f0-9]{32}")))
        val json = JSONObject().put("version", 1).put("id", chapter.id)
            .put("title", chapter.title).put("sourceUrl", chapter.sourceUrl)
            .put("position", chapter.position).put("offset", chapter.scrollOffset)
            .put("updatedAt", chapter.updatedAt).put("bookmarked", chapter.bookmarked).put("readingStatus", chapter.readingStatus.name)
            .put("pages", JSONArray().apply {
                chapter.pages.forEach { page -> put(JSONObject().put("index", page.index)
                    .put("source", page.sourceUrl).put("file", page.localPath?.let { File(it).name })) }
            })
        val atomic = AtomicFile(File(directory, chapter.id + ".json"))
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    @Synchronized
    fun remove(id: String) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        val target = list().firstOrNull { it.id == id } ?: return
        AtomicFile(File(directory, "$id.json")).delete()
        val retained = list().flatMap { it.pages }.mapNotNull { it.localPath }.toSet()
        target.pages.mapNotNull { it.localPath }.filterNot { it in retained }.forEach { path ->
            val file = File(path)
            if (file.parentFile?.canonicalFile == File(directory.parentFile, "chapters").canonicalFile) file.delete()
        }
    }

    private fun read(file: File): SavedChapter? = runCatching {
        require(file.length() <= 2_000_000)
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        val images = File(directory.parentFile, "chapters")
        val pages = json.getJSONArray("pages")
        SavedChapter(json.getString("id"), json.getString("title"), json.optString("sourceUrl"),
            (0 until pages.length()).mapNotNull { index ->
                val row = pages.getJSONObject(index)
                val name = row.optString("file")
                // Manifests cannot resolve paths outside the managed image directory.
                if (name.isBlank() || name != File(name).name) null else {
                    val image = File(images, name)
                    if (!image.isFile || image.length() == 0L) null else ChapterPage(row.getInt("index"), row.getString("source"), image.absolutePath)
                }
            }, json.optInt("position").coerceAtLeast(0), json.optInt("offset").coerceAtLeast(0), json.optLong("updatedAt"), json.optBoolean("bookmarked"),
            ReadingStatus.entries.firstOrNull { it.name == json.optString("readingStatus") } ?: ReadingStatus.READING)
    }.getOrNull()?.takeIf { it.pages.isNotEmpty() }

    companion object {
        fun id(source: String): String = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
