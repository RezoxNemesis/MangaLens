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
    val lastReadAt: Long = 0L,
    val nativeLibraryOperation: NativeLibraryOperationReceipt? = null
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

    fun list(): List<SavedChapter> = withMetadataFence {
        val hints = PromotionVerificationBudget()
        manifestFiles().mapNotNull { read(it, hints) }.sortedWith(compareByDescending<SavedChapter> { it.updatedAt }.thenBy { it.id })
    }

    /** One explicit managed identity; callers on IO need not decode every Library manifest. */
    internal fun find(id: String): SavedChapter? = withMetadataFence {
        require(validId.matches(id))
        read(File(directory, "$id.json"))
    }

    /** Metadata consumers own manifest streams only; this lookup never opens original-image streams. */
    internal fun findMetadata(id: String): SavedChapter? = withMetadataFence {
        require(validId.matches(id))
        read(File(directory, "$id.json"), PromotionVerificationBudget(0))
    }

    fun save(chapter: SavedChapter) = withMetadataFence {
        saveLocked(chapter)
    }

    /** Native acquisition never replaces a different successful Library record. */
    internal fun saveNewAcquisition(chapter: SavedChapter, source: SavedChapter? = null) = withMetadataFence {
        source?.let { selected ->
            val current = read(File(directory, "${selected.id}.json"), PromotionVerificationBudget(0))
            check(current != null && current.sourceUrl == selected.sourceUrl && sourceEntries(current) == sourceEntries(selected)) {
                "The selected source chapter changed before Library publication."
            }
        }
        val destination = File(directory, "${chapter.id}.json")
        check(!destination.exists() && !File(destination.path + ".bak").exists()) {
            "This chapter already has a Library journal. Open or repair its saved record; native acquisition preserves it."
        }
        saveLocked(chapter)
    }

    /** Hashes were checked by the native caller; commit stays bound to this exact manifest. */
    internal fun confirmAcquisition(chapter: SavedChapter, commit: () -> Unit) = withMetadataFence {
        val current = read(File(directory, "${chapter.id}.json"), PromotionVerificationBudget(0))
        check(current != null && current.sourceUrl == chapter.sourceUrl && sourceEntries(current) == sourceEntries(chapter)) {
            "The acquired Library record changed before its receipt was saved."
        }
        commit()
    }

    private fun sourceEntries(chapter: SavedChapter) = chapter.pages.sortedBy { it.index }
        .map { Triple(it.index, it.sourceUrl, it.localPath) }

    /** Reject deletion races; a details editor cannot recreate a removed chapter. */
    fun updateMetadata(id: String, metadata: LibraryChapterMetadata, editedAt: Long = System.currentTimeMillis()): SavedChapter = withMetadataFence {
        require(validId.matches(id))
        val normalized = metadata.normalized()
        val existing = read(File(directory, "$id.json")) ?: throw IllegalStateException("This chapter is no longer saved. Reopen the Library.")
        val updated = existing.withMetadata(normalized).copy(updatedAt = editedAt.coerceAtLeast(existing.updatedAt))
        saveLocked(updated)
        updated
    }

    fun remove(id: String) = withMetadataFence {
        require(validId.matches(id))
        val target = read(File(directory, "$id.json"), PromotionVerificationBudget(0)) ?: return@withMetadataFence
        io.delete(File(directory, "$id.json"))
        val remaining = manifestFiles().map { read(it, PromotionVerificationBudget(0)) }
        // Preserve source pages if an unreadable journal prevents proving that they are unshared.
        if (remaining.any { it == null }) return@withMetadataFence
        val retained = remaining.filterNotNull().flatMap { it.pages }.mapNotNull { it.localPath }.map { File(it).canonicalFile }.toSet()
        target.pages.mapNotNull { it.localPath }.map { File(it) }.forEach { file ->
            val canonical = file.canonicalFile
            if (canonical.parentFile == images.canonicalFile && canonical !in retained) file.delete()
        }
    }

    /** Uses the exact saved-journal encoder without writing or granting original-byte authority. */
    internal fun checkMetadataBudget(chapter: SavedChapter, successors: List<ChapterAcquisitionBudgetPage> = emptyList()): Int =
        encodeManifest(chapter, successors).size

    private fun saveLocked(chapter: SavedChapter) {
        io.write(File(directory, chapter.id + ".json"), encodeManifest(chapter))
    }

    private fun encodeManifest(chapter: SavedChapter, successors: List<ChapterAcquisitionBudgetPage> = emptyList()): ByteArray {
        require(validId.matches(chapter.id))
        require(chapter.pages.isNotEmpty() && chapter.pages.size <= MAX_PAGES)
        val saved = chapter.withMetadata(LibraryChapterMetadata.of(chapter))
        val budget = successors.associateBy { it.index }
        require(budget.size == successors.size && successors.size <= saved.pages.size)
        successors.forEach { row ->
            require(saved.pages.any { it.index == row.index && it.sourceUrl == row.sourceUrl })
            require(row.fileName.length in 1..160 && row.fileName == File(row.fileName).name &&
                File(images, row.fileName).canonicalFile.parentFile == images.canonicalFile)
        }
        saved.pages.mapNotNull { it.localPath }.forEach { path ->
            require(File(path).canonicalFile.parentFile == images.canonicalFile) { "Saved pages must remain in the managed chapter directory." }
        }
        val json = JSONObject().put("version", 2).put("id", saved.id).put("title", saved.title).put("sourceUrl", saved.sourceUrl)
            .put("position", saved.position.coerceAtLeast(0)).put("offset", saved.scrollOffset.coerceAtLeast(0))
            .put("updatedAt", saved.updatedAt.coerceAtLeast(0)).put("bookmarked", saved.bookmarked).put("readingStatus", saved.readingStatus.name)
            .put("seriesTitle", saved.seriesTitle).put("notes", saved.notes).put("collections", JSONArray(saved.collections))
            .put("addedAt", saved.addedAt.coerceAtLeast(0)).put("lastReadAt", saved.lastReadAt.coerceAtLeast(0))
            .put("pages", JSONArray().apply {
                saved.pages.forEach { page ->
                    val actual = encodePage(page)
                    val reservation = budget[page.index]
                    if (reservation == null) put(actual) else {
                        val success = JSONObject(actual.toString()).put("file", reservation.fileName).put("error", null)
                            .put("promotionHint", reservation.promotion?.let { reason ->
                                // Fixed digest LENGTH only, private budget JSON; never a ChapterPage/hint or written proof.
                                JSONObject().put("reason", reason.name).put("sourceSha256", "0".repeat(64)) })
                        val failure = JSONObject(actual.toString()).put("file", null).put("error", ChapterPageAcquisitionPolicy.FAILURE)
                            .put("promotionHint", null)
                        put(listOf(actual, success, failure).maxBy { it.toString().toByteArray(Charsets.UTF_8).size })
                    }
                }
            })
        // Budget JSON conservatively reserves an existing validated receipt even if the worst row changes its digest.
        // The written manifest still requires the original exact state digest (no successor reservations).
        saved.nativeLibraryOperation?.validate()?.takeIf { it.operation == "BOOKMARK" &&
            (successors.isNotEmpty() || it.stateSha256 == NativeLibraryOperationReceipt.stateDigest(json)) }
            ?.let { json.put("nativeLibraryOperation", NativeLibraryOperationReceiptCodec.encode(it)) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Chapter metadata is too large to save." }
        return bytes
    }

    private fun encodePage(page: ChapterPage): JSONObject = JSONObject().put("index", page.index).put("source", page.sourceUrl)
        .put("file", page.localPath?.let { File(it).name }).put("error", page.error)
        .put("promotionHint", page.promotionHint?.takeIf { it.matches(page) }?.let { hint ->
            JSONObject().put("reason", hint.reason.name).put("sourceSha256", hint.sourceSha256) })
        .put("documentSource", page.documentSource?.validate()?.let { source -> JSONObject()
            .put("uri", source.uri).put("kind", source.kind).put("pageIndex", source.pageIndex)
            .put("sha256", source.documentSha256).put("entry", source.archiveEntryName)
            .put("persistedRead", source.persistedReadPermission) })

    /** A .bak is committed recovery data; a lone .new is an unfinished write and stays untouched. */
    private fun manifestFiles(): List<File> = directory.listFiles().orEmpty().mapNotNull { file ->
        val name = file.name.removeSuffix(".bak")
        if (name.endsWith(".json") && validId.matches(name.removeSuffix(".json"))) File(directory, name) else null
    }.distinctBy { it.name }

    private fun read(file: File, hints: PromotionVerificationBudget = PromotionVerificationBudget()): SavedChapter? = runCatching {
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
                else -> {
                    val hint = verifiedPromotionHint(row, image, hints)
                    ChapterPage(pageIndex, source, image.absolutePath, row.optString("error").takeIf { it.isNotBlank() && it != "null" },
                        contentRevision = hint?.sourceSha256, documentSource = originalDocument, promotionHint = hint)
                }
            }
        }
        val collections = json.optJSONArray("collections")
        require((collections?.length() ?: 0) <= LibraryChapterMetadata.MAX_COLLECTIONS)
        val metadata = LibraryChapterMetadata(json.optString("seriesTitle"), json.optString("notes"),
            (0 until (collections?.length() ?: 0)).map { collections!!.getString(it) }).normalized()
        SavedChapter(id, json.getString("title"), json.optString("sourceUrl"), pages,
            json.optInt("position").coerceAtLeast(0), json.optInt("offset").coerceAtLeast(0), json.optLong("updatedAt").coerceAtLeast(0), json.optBoolean("bookmarked"),
            ReadingStatus.entries.firstOrNull { it.name == json.optString("readingStatus") } ?: ReadingStatus.READING,
            metadata.seriesTitle, metadata.notes, metadata.collections, json.optLong("addedAt", 0L).coerceAtLeast(0), json.optLong("lastReadAt", 0L).coerceAtLeast(0),
            json.optJSONObject("nativeLibraryOperation")?.let(NativeLibraryOperationReceiptCodec::decode)?.takeIf { it.stateSha256 == NativeLibraryOperationReceipt.stateDigest(json) })
    }.getOrNull()

    /** Older manifests have no hint. A hint only survives reopening when its actual original SHA matches. */
    private class PromotionVerificationBudget(private var remaining: Long = 64L * 1024 * 1024) {
        fun reserve(bytes: Long): Boolean = if (bytes in 1..remaining) { remaining -= bytes; true } else false
    }

    private fun verifiedPromotionHint(row: JSONObject, image: File, hints: PromotionVerificationBudget): ChapterPromotionHint? = runCatching {
        val json = row.optJSONObject("promotionHint") ?: return@runCatching null
        val hint = ChapterPromotionHint(com.mangalens.core.acquisition.ChapterImagePromotion.valueOf(json.getString("reason")),
            json.getString("sourceSha256"))
        val size = image.length(); val modified = image.lastModified()
        require(size in 1..40L * 1024 * 1024 && hints.reserve(size))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        image.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read; require(count <= 40L * 1024 * 1024)
                digest.update(buffer, 0, read)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        hint.takeIf { count == size && image.length() == size && image.lastModified() == modified && sha == hint.sourceSha256 }
    }.getOrNull()

    companion object {
        private val mutationFence = Any()
        private val nativeMetadataGate = java.util.concurrent.locks.ReentrantLock()
        private fun <T> withMetadataFence(body: () -> T): T = synchronized(mutationFence) {
            nativeMetadataGate.lock()
            try { body() } finally { nativeMetadataGate.unlock() }
        }
        /** IO-only capture uses the same fence as ordinary writes and AtomicFile recovery. */
        internal fun <T> readNativeMetadata(body: () -> T): T = withMetadataFence(body)
        /** Never waits for a manifest decode, image hash or ordinary fsync while holding task authority. */
        internal fun <T> publishNativeMetadata(body: () -> T): T {
            if (!nativeMetadataGate.tryLock()) throw NativeLibraryMetadataBusyException()
            try { return body() } finally { nativeMetadataGate.unlock() }
        }
        private val validId = Regex("[a-f0-9]{32}")
        private const val MAX_MANIFEST_BYTES = 2_000_000
        private const val MAX_PAGES = 100_000
        fun id(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
