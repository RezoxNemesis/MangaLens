package com.mangalens.core.translation.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal interface PreparedMemoryJournal : AutoCloseable {
    fun commit()
}

/** Prepare and fsync on IO before acquiring any Reader/task publication authority. */
internal fun interface MemoryJournalWriter { fun prepare(file: File, bytes: ByteArray): PreparedMemoryJournal }

/** Holds the Reader/task guard only for the already prepared atomic replacement. */
fun interface MemoryPublicationFence {
    fun commitIfCurrent(receipt: MemoryPublicationReceipt, commit: () -> Unit)
}

internal object AtomicMemoryJournalWriter : MemoryJournalWriter {
    override fun prepare(file: File, bytes: ByteArray): PreparedMemoryJournal {
        val temporary = File(file.parentFile, file.name + ".pending-" + UUID.randomUUID())
        try { FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() } }
        catch (failure: Throwable) { temporary.delete(); throw failure }
        return object : PreparedMemoryJournal {
            private var committed = false
            override fun commit() {
                check(!committed) { "A prepared journal may commit only once." }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
            }
            override fun close() { temporary.delete() }
        }
    }
}

data class MemorySearchResults(val hits: List<MemorySearchHit>, val incomplete: Boolean, val scannedBytes: Long)
data class MemoryChapterSnapshot(val chapterId: String, val association: MemoryChapterAssociation?, val bubbles: List<MemoryIndexedBubble>, val removed: Boolean, val associationRevision: Long = 0)

/** Personal reader memory stays separate from imported corpora, media and generated surfaces. */
class SeriesMemoryStore internal constructor(filesRoot: File, private val writer: MemoryJournalWriter, private val publicationFence: MemoryPublicationFence? = null) {
    constructor(filesRoot: File) : this(filesRoot, AtomicMemoryJournalWriter)
    constructor(filesRoot: File, publicationFence: MemoryPublicationFence) : this(filesRoot, AtomicMemoryJournalWriter, publicationFence)
    private val privateRoot = filesRoot.canonicalFile
    private val directory = File(privateRoot, "reader_memory")
    private val chapterDirectory = File(directory, "chapters")
    private val seriesDirectory = File(directory, "series")
    private val sourceDirectory = File(privateRoot, "chapters").canonicalFile
    private val outputDirectory = File(privateRoot, "chapter_translations").canonicalFile

    suspend fun createSeries(title: String, id: String = UUID.randomUUID().toString()): SeriesMemoryProfile = io {
        val result = SeriesMemoryProfile(id, title.trim()).also { it.validate() }
        check(!seriesFile(id).exists()) { "This series identity already exists. Choose its existing glossary or create a new series." }
        check(journalFiles(seriesDirectory).size < MAX_SERIES) { "Personal memory has reached its bounded series limit." }
        writeProfile(result); result
    }

    suspend fun profile(id: String): SeriesMemoryProfile? = io { readProfile(id)?.takeUnless { it.removed } }

    suspend fun listSeries(): List<SeriesMemoryProfile> = io {
        journalFiles(seriesDirectory).map { readProfile(it.name.removeSuffix(".json"))!! }.filterNot { it.removed }.sortedBy { memoryTextKey(it.title) }
    }

    suspend fun associateChapter(chapterId: String, seriesId: String, ordinal: Int?) = io {
        val association = MemoryChapterAssociation(chapterId, seriesId, ordinal).also { it.validate() }
        requireProfile(seriesId)
        val chapter = activeChapter(chapterId)
        writeChapter(chapter.copy(association = association, associationRevision = nextAssociationRevision(chapter)))
    }

    suspend fun unlinkChapter(chapterId: String) = io {
        val chapter = activeChapter(chapterId)
        writeChapter(chapter.copy(association = null, associationRevision = nextAssociationRevision(chapter)))
    }

    private fun nextAssociationRevision(chapter: MemoryChapterJournal): Long {
        check(chapter.associationRevision < Long.MAX_VALUE) { "This chapter's series link version is exhausted." }
        return chapter.associationRevision + 1
    }

    suspend fun upsertTerm(seriesId: String, term: SeriesGlossaryTerm) = io {
        term.validate()
        val profile = requireProfile(seriesId)
        val normalized = term.origin?.let { origin ->
            val journal = activeChapter(origin.chapterId)
            require(journal.association?.seriesId == seriesId) { "A sourced term must belong to this explicitly linked series." }
            // Retain stale receipts for inspection, but only current source/output proofs
            // can establish the glossary origin or make that origin ambiguous.
            val receipts = journal.bubbles.filter { it.receipt.source.pageIndex == origin.pageIndex && it.receipt.seriesId == seriesId }
                .map { it.receipt }.filter { verified(it) }
            val hashes = receipts.map { it.source.sourceSha256 }.distinct()
            require(hashes.size == 1) { "Verify the glossary term's source page first." }
            require(term.originSourceSha256 == null || term.originSourceSha256 == hashes.single()) { "The glossary source changed." }
            term.copy(originSourceSha256 = hashes.single())
        } ?: term
        val entries = profile.glossary.filterNot { it.id == term.id }
        require(entries.none { memoryTextKey(it.source) == memoryTextKey(term.source) && it.targetLanguage.equals(term.targetLanguage, true) }) {
            "This series already has a spelling for that term and target language. Edit the existing term."
        }
        require(entries.size < 512) { "Use at most 512 glossary terms per series." }
        writeProfile(profile.copy(glossary = entries + normalized))
    }

    suspend fun removeTerm(seriesId: String, termId: String) = io {
        require(memoryValidId(termId)); val profile = requireProfile(seriesId)
        writeProfile(profile.copy(glossary = profile.glossary.filterNot { it.id == termId }))
    }

    suspend fun setStyle(seriesId: String, style: SeriesStylePreference?) = io {
        style?.validate(); writeProfile(requireProfile(seriesId).copy(style = style))
    }

    suspend fun indexBubble(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt) = io {
        checkReceipts(captured, current)
        val chapter = activeChapter(captured.source.chapterId)
        require(chapter.association?.seriesId == captured.seriesId) { "This bubble was captured for another series scope. Reopen it before indexing." }
        checkAssociation(captured, chapter)
        val previous = chapter.bubbles.singleOrNull { it.receipt.bubbleId == captured.bubbleId }
        val records = chapter.bubbles.filterNot { it.receipt.bubbleId == captured.bubbleId } +
            MemoryIndexedBubble(captured, previous?.correction, previous?.editRevision ?: 0)
        require(records.size <= 2_048) { "This chapter exceeds the bounded OCR memory limit." }
        publishChapter(captured, current, chapter.copy(bubbles = records))
    }

    suspend fun correct(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt, expectedRevision: Int, edit: MemoryCorrectionEdit): MemoryCorrection = io {
        edit.validate(); appendRevision(captured, current, expectedRevision, edit)
    }

    suspend fun rollback(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt, expectedRevision: Int, restoreRevision: Int): MemoryCorrection = io {
        val (_, bubble) = editableBubble(captured, current, expectedRevision)
        val correction = bubble.correction ?: error("This bubble has no correction history.")
        val edit = if (restoreRevision == 0) MemoryCorrectionEdit() else
            correction.revisions.singleOrNull { it.revision == restoreRevision }?.edit ?: error("The requested correction revision is unavailable.")
        appendRevision(captured, current, expectedRevision, edit)
    }

    suspend fun removeCorrection(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt, expectedRevision: Int) = io {
        val (chapter, bubble) = editableBubble(captured, current, expectedRevision)
        val nextRevision = nextEditRevision(bubble)
        publishChapter(captured, current, chapter.copy(bubbles = chapter.bubbles.map {
            if (it.receipt.bubbleId == captured.bubbleId) it.copy(correction = null, editRevision = nextRevision) else it
        }))
    }

    suspend fun inspectChapter(chapterId: String): MemoryChapterSnapshot = io {
        val value = readChapter(chapterId) ?: MemoryChapterJournal(chapterId)
        MemoryChapterSnapshot(value.chapterId, value.association, value.bubbles, value.removed, value.associationRevision)
    }

    /** Native overlay readers keep exact series/link facts through their short publication callback. */
    internal suspend fun <T> useChapterSnapshot(chapterId: String, use: (MemoryChapterSnapshot) -> T): T = io {
        val value = readChapter(chapterId) ?: MemoryChapterJournal(chapterId)
        value.association?.seriesId?.let(::requireProfile)
        use(MemoryChapterSnapshot(value.chapterId, value.association, value.bubbles, value.removed, value.associationRevision))
    }

    suspend fun search(query: String, limit: Int = 32, chapterIds: Set<String>? = null, seriesId: String? = null): MemorySearchResults = io {
        require(query.isNotBlank() && query.length <= 256 && limit in 1..32)
        chapterIds?.forEach { require(memoryValidId(it)) }; seriesId?.let { require(memoryValidId(it)) }
        var scanned = 0L; var incomplete = false
        val results = ArrayList<MemorySearchHit>(); val fresh = HashMap<String, Boolean>()
        for (file in journalFiles(chapterDirectory)) {
            val id = file.name.removeSuffix(".json")
            if (chapterIds != null && id !in chapterIds) continue
            if (scanned + file.length() > MAX_SCAN_BYTES) { incomplete = true; break }
            scanned += file.length()
            val chapter = runCatching { readChapter(id)!! }.getOrNull()
            if (chapter == null) { incomplete = true; continue }
            if (chapter.removed || (seriesId != null && chapter.association?.seriesId != seriesId)) continue

            for (bubble in chapter.bubbles.sortedWith(compareBy<MemoryIndexedBubble> { it.receipt.source.pageIndex }.thenBy { it.receipt.source.bounds.top }.thenBy { it.receipt.source.bounds.left })) {
                if (seriesId != null && bubble.receipt.seriesId != seriesId) continue
                val visibleSeries = bubble.receipt.seriesId?.takeIf { runCatching { requireProfile(it) }.isSuccess }
                val hits = SeriesMemoryQuery.search(query, listOf(bubble), visibleSeries)
                if (hits.isNotEmpty() && verified(bubble.receipt, fresh)) results += hits.take(limit - results.size)
                if (results.size >= limit) { incomplete = true; break }
            }
            if (results.size >= limit) break
        }
        MemorySearchResults(results, incomplete, scanned)
    }

    suspend fun relevant(request: MemoryRetrievalRequest): RelevantSeriesMemory = io {
        val current = readChapter(request.chapterId)?.takeUnless { it.removed }
            ?: return@io RelevantSeriesMemory(null, emptyMap(), emptyList())
        val series = current.association?.seriesId?.let { readProfile(it)?.takeUnless { value -> value.removed } }
            ?: return@io RelevantSeriesMemory(null, emptyMap(), emptyList())
        var scanned = 0L
        val associations = ArrayList<MemoryChapterAssociation>(); val records = ArrayList<MemoryIndexedBubble>(); val fresh = HashMap<String, Boolean>()
        for (file in journalFiles(chapterDirectory)) {
            if (scanned + file.length() > MAX_SCAN_BYTES) break
            scanned += file.length()
            val chapter = runCatching { readChapter(file.name.removeSuffix(".json")) }.getOrNull() ?: continue
            if (chapter.removed || chapter.association?.seriesId != series.id) continue
            associations += chapter.association
            records += chapter.bubbles.filter { it.receipt.seriesId == series.id && verified(it.receipt, fresh) }
        }
        val terms = series.glossary.filter { term ->
            val origin = term.origin
            origin == null || records.any { it.receipt.source.chapterId == origin.chapterId && it.receipt.source.pageIndex == origin.pageIndex &&
                it.receipt.source.sourceSha256 == term.originSourceSha256 }
        }
        SeriesMemoryQuery.relevant(request, associations, series.copy(glossary = terms), records)
    }

    /** Durable tombstones stop delayed old workers from recreating explicitly removed memory. */
    suspend fun removeChapter(chapterId: String) = io { require(memoryValidId(chapterId)); writeChapter(MemoryChapterJournal(chapterId, removed = true)) }

    suspend fun removeSeries(seriesId: String) = io {
        val profile = requireProfile(seriesId); writeProfile(profile.copy(glossary = emptyList(), style = null, removed = true))
    }

    private fun appendRevision(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt, expectedRevision: Int, edit: MemoryCorrectionEdit): MemoryCorrection {
        val (chapter, bubble) = editableBubble(captured, current, expectedRevision)
        val history = bubble.correction?.revisions.orEmpty()
        require(history.size < 32) { "This bubble has 32 saved correction revisions. Inspect or explicitly remove its correction history first." }
        val nextRevision = nextEditRevision(bubble)
        val correction = MemoryCorrection(bubble.correction?.original ?: captured,
            history + MemoryCorrectionRevision(nextRevision, edit, System.currentTimeMillis().coerceAtLeast(0)))
        publishChapter(captured, current, chapter.copy(bubbles = chapter.bubbles.map {
            if (it.receipt.bubbleId == captured.bubbleId) it.copy(correction = correction, editRevision = nextRevision) else it
        }))
        return correction
    }

    private fun nextEditRevision(bubble: MemoryIndexedBubble): Int {
        require(bubble.editRevision in 0 until Int.MAX_VALUE) { "This bubble's edit version is exhausted. Its current history remains unchanged." }
        return bubble.editRevision + 1
    }

    private fun publishChapter(receipt: MemoryPublicationReceipt, current: MemoryPublicationReceipt, chapter: MemoryChapterJournal) {
        val fence = publicationFence ?: error("A current reader/task publication lease is required before source history can change.")
        val file = chapterFile(chapter.chapterId)
        if (!file.exists()) check(journalFiles(chapterDirectory).size < MAX_CHAPTERS) { "Personal memory has reached its bounded chapter limit." }
        val bytes = SeriesMemoryCodec.chapter(chapter)
        prepare(file, bytes, MAX_CHAPTER_BYTES).use { pending ->
            // Hashing and fsync stay outside Reader/task control guards. The actual native
            // adapter revalidates full authority after preparation and holds it through rename.
            checkReceipts(receipt, current)
            var invoked = false
            fence.commitIfCurrent(receipt) {
                check(!invoked) { "A publication lease may commit only once." }
                invoked = true
                pending.commit()
            }
            check(invoked) { "The source owner did not accept this publication. Reopen the current bubble." }
        }
    }

    private fun editableBubble(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt, expectedRevision: Int): Pair<MemoryChapterJournal, MemoryIndexedBubble> {
        checkReceipts(captured, current)
        val chapter = activeChapter(captured.source.chapterId)
        require(chapter.association?.seriesId == captured.seriesId) { "This chapter was linked to another series. Reopen its correction editor." }
        checkAssociation(captured, chapter)
        val bubble = chapter.bubbles.singleOrNull { it.receipt.bubbleId == captured.bubbleId } ?: error("Reopen this source bubble before editing.")
        require(bubble.receipt == captured) { "This bubble's published source/output generation changed. Reopen the editor." }
        require(expectedRevision >= 0 && bubble.editRevision == expectedRevision) { "This correction changed or was removed. Reopen the editor before replacing it." }
        return chapter to bubble
    }

    private fun checkAssociation(receipt: MemoryPublicationReceipt, chapter: MemoryChapterJournal) {
        if (receipt.nativeAuthorityVersion == 1) receipt.seriesId?.let(::requireProfile)
        require(receipt.associationRevision == null || receipt.associationRevision == chapter.associationRevision) {
            "This chapter's explicit series link changed. Reopen the correction editor."
        }
    }

    private fun checkReceipts(captured: MemoryPublicationReceipt, current: MemoryPublicationReceipt) {
        captured.validate(); current.validate()
        require(captured == current) { "The current source, translation choice or generation changed. Reopen this bubble." }
        require(verified(captured)) { "Source or translated output changed. Verify the current page before saving." }
    }

    private fun verified(receipt: MemoryPublicationReceipt, cache: MutableMap<String, Boolean>? = null): Boolean = runCatching {
        receipt.validate()
        val source = File(receipt.source.sourcePath).canonicalFile
        require(sourceDirectory.parentFile == privateRoot && outputDirectory.parentFile == privateRoot)
        require(source.parentFile == sourceDirectory)
        fun matches(file: File, hash: String): Boolean {
            val key = file.path + ":" + hash
            return cache?.getOrPut(key) { file.isFile && file.length() in 1..MAX_SOURCE_BYTES && sha256(file) == hash }
                ?: (file.isFile && file.length() in 1..MAX_SOURCE_BYTES && sha256(file) == hash)
        }
        if (!matches(source, receipt.source.sourceSha256)) return@runCatching false
        receipt.outputPath?.let { path ->
            val output = File(path).canonicalFile
            require(output.toPath().startsWith(outputDirectory.toPath()) && output != outputDirectory)
            if (!matches(output, receipt.outputSha256!!)) return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun activeChapter(id: String): MemoryChapterJournal {
        val chapter = readChapter(id) ?: MemoryChapterJournal(id)
        check(!chapter.removed) { "This chapter's personal memory was removed. A late worker cannot recreate it." }
        return chapter
    }
    private fun requireProfile(id: String) = readProfile(id)?.takeUnless { it.removed } ?: error("This series memory is unavailable. Select an existing series.")
    private fun chapterFile(id: String): File { require(memoryValidId(id)); return File(chapterDirectory, "$id.json") }
    private fun seriesFile(id: String): File { require(memoryValidId(id)); return File(seriesDirectory, "$id.json") }
    private fun readChapter(id: String): MemoryChapterJournal? = chapterFile(id).takeIf { it.isFile }?.let { file ->
        SeriesMemoryCodec.readChapter(read(file, MAX_CHAPTER_BYTES)).also { require(it.chapterId == id) }
    }
    private fun readProfile(id: String): SeriesMemoryProfile? = seriesFile(id).takeIf { it.isFile }?.let { file ->
        SeriesMemoryCodec.readProfile(read(file, MAX_SERIES_BYTES)).also { require(it.id == id) }
    }
    private fun writeChapter(value: MemoryChapterJournal) {
        if (!chapterFile(value.chapterId).exists()) check(journalFiles(chapterDirectory).size < MAX_CHAPTERS) { "Personal memory has reached its bounded chapter limit." }
        write(chapterFile(value.chapterId), SeriesMemoryCodec.chapter(value), MAX_CHAPTER_BYTES)
    }
    private fun writeProfile(value: SeriesMemoryProfile) { value.validate(); write(seriesFile(value.id), SeriesMemoryCodec.profile(value), MAX_SERIES_BYTES) }
    private fun read(file: File, cap: Int): ByteArray {
        require(file.canonicalFile.parentFile == file.parentFile!!.canonicalFile && file.length() in 1..cap.toLong())
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8_192)
            while (output.size() <= cap) {
                val count = input.read(buffer, 0, minOf(buffer.size, cap + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            require(output.size() <= cap) { "Personal memory journal exceeds its bounded schema." }; output.toByteArray()
        }
    }
    private fun write(file: File, bytes: ByteArray, cap: Int) {
        require(bytes.size <= cap) { "This memory journal is full. Inspect/remove entries before adding more." }
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        require(file.canonicalFile.parentFile == file.parentFile!!.canonicalFile)
        prepare(file, bytes, cap).use { it.commit() }
    }
    private fun prepare(file: File, bytes: ByteArray, cap: Int): PreparedMemoryJournal {
        require(bytes.size <= cap) { "This memory journal is full. Inspect/remove entries before adding more." }
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        require(file.canonicalFile.parentFile == file.parentFile!!.canonicalFile)
        return writer.prepare(file, bytes)
    }
    private fun journalFiles(folder: File): List<File> {
        val files = folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") && memoryValidId(it.name.removeSuffix(".json")) }.sortedBy { it.name }
        require(files.size <= if (folder == chapterDirectory) MAX_CHAPTERS else MAX_SERIES) { "Too many personal memory journals." }
        return files
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        synchronized(mutationGuard) {
            coroutineContext.ensureActive()
            require(directory.canonicalFile.parentFile == privateRoot &&
                chapterDirectory.canonicalFile.parentFile == directory.canonicalFile &&
                seriesDirectory.canonicalFile.parentFile == directory.canonicalFile) { "Personal memory must remain in private managed storage." }
            block()
        }
    }
    companion object {
        private val mutationGuard = Any()
        private const val MAX_CHAPTER_BYTES = 2 * 1_024 * 1_024
        private const val MAX_SERIES_BYTES = 1_024 * 1_024
        private const val MAX_SCAN_BYTES = 32L * 1_024 * 1_024
        private const val MAX_SOURCE_BYTES = 96L * 1_024 * 1_024
        private const val MAX_CHAPTERS = 1_024
        private const val MAX_SERIES = 512
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1_024); while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
