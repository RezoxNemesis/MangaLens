package com.mangalens.core.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class ReadingStatus(val label: String) {
    // Retain the original names/order for existing persisted records and callers.
    READING("Reading"), COMPLETED("Completed"), ON_HOLD("On Hold"), PLAN_TO_READ("Plan to Read"), DROPPED("Dropped");
    companion object { val displayOrder = listOf(READING, PLAN_TO_READ, COMPLETED, ON_HOLD, DROPPED) }
}

data class SavedChapter(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val pages: List<ChapterPage>,
    val position: Int = 0,
    val scrollOffset: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val bookmarked: Boolean = false,
    val readingStatus: ReadingStatus = ReadingStatus.READING,
    val seriesTitle: String = "",
    val notes: String = "",
    val collections: List<String> = emptyList(),
    val addedAt: Long = updatedAt,
    val lastReadAt: Long = 0L
) {
    fun withMetadata(metadata: LibraryChapterMetadata): SavedChapter = metadata.normalized().let {
        copy(seriesTitle = it.seriesTitle, notes = it.notes, collections = it.collections)
    }
    /** Called for an explicit reader visit/position event, never passive saving or a metadata edit. */
    fun visitedAt(time: Long): SavedChapter = copy(lastReadAt = maxOf(lastReadAt, time.coerceAtLeast(0)))
}

data class LibraryChapterMetadata(val seriesTitle: String = "", val notes: String = "", val collections: List<String> = emptyList()) {
    fun normalized(): LibraryChapterMetadata {
        require(seriesTitle.length <= MAX_SERIES_LENGTH) { "Series title must be at most $MAX_SERIES_LENGTH characters." }
        require(notes.length <= MAX_NOTES_LENGTH) { "Notes must be at most $MAX_NOTES_LENGTH characters." }
        require(collections.size <= MAX_COLLECTIONS) { "Use at most $MAX_COLLECTIONS collections per chapter." }
        val names = collections.map { it.trim() }.filter { it.isNotEmpty() }
        require(names.all { it.length <= MAX_COLLECTION_LENGTH && '\n' !in it && '\r' !in it }) {
            "Collection names must be single lines of at most $MAX_COLLECTION_LENGTH characters."
        }
        return copy(seriesTitle = seriesTitle.trim(), collections = names.distinctBy(::libraryTextKey))
    }
    companion object {
        const val MAX_SERIES_LENGTH = 256
        const val MAX_NOTES_LENGTH = 8_192
        const val MAX_COLLECTIONS = 32
        const val MAX_COLLECTION_LENGTH = 80
        fun of(chapter: SavedChapter) = LibraryChapterMetadata(chapter.seriesTitle, chapter.notes, chapter.collections)
    }
}

/** Normalization preserves script marks while making compatibility-width/case matching predictable. */
fun libraryTextKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

internal interface ChapterLibraryJournalIo {
    fun read(file: File): InputStream
    fun write(file: File, bytes: ByteArray)
    fun delete(file: File)
}

private object AtomicChapterJournalIo : ChapterLibraryJournalIo {
    override fun read(file: File): InputStream = AtomicFile(file).openRead()
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    override fun delete(file: File) = AtomicFile(file).delete()
}

/** Small atomic chapter manifests. Original images remain in the existing managed directory. */
class ChapterLibrary internal constructor(filesRoot: File, private val io: ChapterLibraryJournalIo) {
    constructor(context: Context) : this(context.filesDir, AtomicChapterJournalIo)
    private val directory = File(filesRoot, "chapter_library").apply { check(mkdirs() || isDirectory) }
    private val images = File(filesRoot, "chapters")

    fun list(): List<SavedChapter> = synchronized(mutationFence) {
        manifestFiles().mapNotNull(::read).sortedWith(compareByDescending<SavedChapter> { it.updatedAt }.thenBy { it.id })
    }

    fun save(chapter: SavedChapter) = synchronized(mutationFence) {
        saveLocked(chapter)
    }

    /** Reject deletion races; a details editor cannot recreate a removed chapter. */
    fun updateMetadata(id: String, metadata: LibraryChapterMetadata, editedAt: Long = System.currentTimeMillis()): SavedChapter = synchronized(mutationFence) {
        require(validId.matches(id))
        val normalized = metadata.normalized()
        val existing = read(File(directory, "$id.json")) ?: throw IllegalStateException("This chapter is no longer saved. Reopen the Library.")
        val updated = existing.withMetadata(normalized).copy(updatedAt = editedAt.coerceAtLeast(existing.updatedAt))
        saveLocked(updated)
        updated
    }

    fun remove(id: String) = synchronized(mutationFence) {
        require(validId.matches(id))
        val target = read(File(directory, "$id.json")) ?: return@synchronized
        io.delete(File(directory, "$id.json"))
        val remaining = manifestFiles().map { read(it) }
        // Preserve source pages if an unreadable journal prevents proving that they are unshared.
        if (remaining.any { it == null }) return@synchronized
        val retained = remaining.filterNotNull().flatMap { it.pages }.mapNotNull { it.localPath }.map { File(it).canonicalFile }.toSet()
        target.pages.mapNotNull { it.localPath }.map { File(it) }.forEach { file ->
            val canonical = file.canonicalFile
            if (canonical.parentFile == images.canonicalFile && canonical !in retained) file.delete()
        }
    }

    private fun saveLocked(chapter: SavedChapter) {
        require(validId.matches(chapter.id))
        require(chapter.pages.isNotEmpty() && chapter.pages.size <= MAX_PAGES)
        val saved = chapter.withMetadata(LibraryChapterMetadata.of(chapter))
        saved.pages.mapNotNull { it.localPath }.forEach { path ->
            require(File(path).canonicalFile.parentFile == images.canonicalFile) { "Saved pages must remain in the managed chapter directory." }
        }
        val json = JSONObject().put("version", 2).put("id", saved.id).put("title", saved.title).put("sourceUrl", saved.sourceUrl)
            .put("position", saved.position.coerceAtLeast(0)).put("offset", saved.scrollOffset.coerceAtLeast(0))
            .put("updatedAt", saved.updatedAt.coerceAtLeast(0)).put("bookmarked", saved.bookmarked).put("readingStatus", saved.readingStatus.name)
            .put("seriesTitle", saved.seriesTitle).put("notes", saved.notes).put("collections", JSONArray(saved.collections))
            .put("addedAt", saved.addedAt.coerceAtLeast(0)).put("lastReadAt", saved.lastReadAt.coerceAtLeast(0))
            .put("pages", JSONArray().apply {
                saved.pages.forEach { page -> put(JSONObject().put("index", page.index).put("source", page.sourceUrl)
                    .put("file", page.localPath?.let { File(it).name }).put("error", page.error)
                    .put("documentSource", page.documentSource?.validate()?.let { source -> JSONObject()
                        .put("uri", source.uri).put("kind", source.kind).put("pageIndex", source.pageIndex)
                        .put("sha256", source.documentSha256).put("entry", source.archiveEntryName)
                        .put("persistedRead", source.persistedReadPermission) })) }
            })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Chapter metadata is too large to save." }
        io.write(File(directory, saved.id + ".json"), bytes)
    }

    /** A .bak is committed recovery data; a lone .new is an unfinished write and stays untouched. */
    private fun manifestFiles(): List<File> = directory.listFiles().orEmpty().mapNotNull { file ->
        val name = file.name.removeSuffix(".bak")
        if (name.endsWith(".json") && validId.matches(name.removeSuffix(".json"))) File(directory, name) else null
    }.distinctBy { it.name }

    private fun read(file: File): SavedChapter? = runCatching {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        val bytes = io.read(file).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (output.size() <= MAX_MANIFEST_BYTES) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_MANIFEST_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.optInt("version", 1) in 1..2)
        val id = json.getString("id")
        require(validId.matches(id) && file.name == "$id.json")
        val rows = json.getJSONArray("pages")
        require(rows.length() in 1..MAX_PAGES)
        val pages = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val name = row.optString("file")
            val pageIndex = row.getInt("index")
            val source = row.getString("source")
            val originalDocument = row.optJSONObject("documentSource")?.let { document ->
                ChapterDocumentSource(document.getString("uri"), document.getString("kind"), document.getInt("pageIndex"),
                    document.getString("sha256"), document.optString("entry").takeIf { it.isNotBlank() && it != "null" },
                    document.optBoolean("persistedRead", false)).validate()
            }
            require(originalDocument == null || originalDocument.uri == source) { "Original document metadata does not match the saved source." }
            val image = File(images, name)
            when {
                name.isBlank() || name == "null" -> ChapterPage(pageIndex, source, error = row.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: "Page needs to be downloaded. Retry.", documentSource = originalDocument)
                name != File(name).name || image.canonicalFile.parentFile != images.canonicalFile -> ChapterPage(pageIndex, source, error = "Saved page path is invalid. Retry the source.", documentSource = originalDocument)
                !image.isFile || image.length() == 0L -> ChapterPage(pageIndex, source, error = "Saved page is missing. Retry the source.", documentSource = originalDocument)
                else -> ChapterPage(pageIndex, source, image.absolutePath, row.optString("error").takeIf { it.isNotBlank() && it != "null" }, documentSource = originalDocument)
            }
        }
        val collections = json.optJSONArray("collections")
        require((collections?.length() ?: 0) <= LibraryChapterMetadata.MAX_COLLECTIONS)
        val metadata = LibraryChapterMetadata(json.optString("seriesTitle"), json.optString("notes"),
            (0 until (collections?.length() ?: 0)).map { collections!!.getString(it) }).normalized()
        SavedChapter(id, json.getString("title"), json.optString("sourceUrl"), pages,
            json.optInt("position").coerceAtLeast(0), json.optInt("offset").coerceAtLeast(0), json.optLong("updatedAt").coerceAtLeast(0), json.optBoolean("bookmarked"),
            ReadingStatus.entries.firstOrNull { it.name == json.optString("readingStatus") } ?: ReadingStatus.READING,
            metadata.seriesTitle, metadata.notes, metadata.collections, json.optLong("addedAt", 0L).coerceAtLeast(0), json.optLong("lastReadAt", 0L).coerceAtLeast(0))
    }.getOrNull()

    companion object {
        private val mutationFence = Any()
        private val validId = Regex("[a-f0-9]{32}")
        private const val MAX_MANIFEST_BYTES = 2_000_000
        private const val MAX_PAGES = 100_000
        fun id(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
