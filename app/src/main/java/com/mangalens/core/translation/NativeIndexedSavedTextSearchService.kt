package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import com.mangalens.core.translation.memory.SeriesMemoryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

internal class NativeIndexedSavedTextRow internal constructor(val scope: SavedTextChapterScope,
    val warm: NativeIndexWarmReceipt, val receipt: ReaderTranslationReceipt, val proof: NativeMemoryPageProof,
    val letteringIndex: Int, val kind: MemorySearchKind, val text: String,
    internal val association: MemoryChapterSnapshot? = null, internal val memoryLease: MemoryReadDeliveryLease? = null) {
    val id = "native:" + TranslationRefinementPolicy.hash(listOf(receipt.taskId, receipt.generation,
        proof.page.index.toString(), letteringIndex.toString(), kind.name, text, proof.page.sourceSha256.orEmpty(),
        proof.page.cleanedSha256.orEmpty()).joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    val chapterId: String get() = scope.chapter.id
    val chapterTitle: String get() = scope.chapter.title
    val pageOrdinal: Int get() = scope.chapter.pages.indexOfFirst { it.index == proof.page.index }
}

/** All file SHA work was completed by explicit warming on IO; cache metadata never suffices. */
internal class NativeIndexedReadDelivery(private val native: ChapterTranslationStore,
    private val rows: List<NativeIndexedSavedTextRow>) : SavedTextReadAuthority {
    override fun tryCommit(accept: () -> Boolean): Boolean {
        val scopes = rows.distinctBy { it.warm.task.id }
        fun consume(index: Int): Boolean {
            if (index == scopes.size) return accept()
            val row = scopes[index]
            if (row.scope.manifest?.isCurrent() == false || row.scope.sourceStamps.any { !it.isCurrent() }) return false
            var accepted = false
            fun nativeAccept(): Boolean {
                native.tryCommitWarmedNativeRead(row.warm, row.proof) {
                    if (row.scope.manifest?.isCurrent() != false && row.scope.sourceStamps.all { it.isCurrent() }) accepted = consume(index + 1)
                }
                return accepted
            }
            return row.memoryLease?.let { it.tryCommit { nativeAccept() } == true } ?: nativeAccept()
        }
        return consume(0)
    }
}

internal class NativeIndexedSavedTextSnapshot(val query: String, rows: List<NativeIndexedSavedTextRow>,
    val incomplete: Boolean, private val delivery: SavedTextReadAuthority) {
    val rows = Collections.unmodifiableList(rows.toList())
    init { require(rows.size <= 8 && rows.map { it.chapterId }.distinct().size <= 4) }
    fun tryDeliver(accept: () -> Boolean): Boolean = delivery.tryCommit(accept)
}

internal class NativeIndexedSavedTextSearchService(private val native: ChapterTranslationStore,
    private val warmer: NativeIndexWarmer, private val memory: SeriesMemoryStore? = null,
    private val inspectWarmedChapter: suspend (NativeIndexWarmReceipt) -> SavedTextChapterScope?) {
    private val index = NativeMetadataLexicalIndex(native)
    suspend fun search(query: String, limit: Int = 8): NativeIndexedSavedTextSnapshot? = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank() || text.length > 256 || '\u0000' in text || limit !in 1..8) return@withContext null
        val found = index.find(text)
        val rows = arrayListOf<NativeIndexedSavedTextRow>()
        var incomplete = found.incomplete
        val scopes = hashMapOf<String, SavedTextChapterScope?>()
        val chapters = hashSetOf<String>()
        val receipts = hashMapOf<String, NativeIndexWarmReceipt?>()
        for (hint in found.hints) {
            currentCoroutineContext().ensureActive()
            if (rows.size == limit) { incomplete = true; break }
            val warm = if (hint.task.id in receipts) receipts[hint.task.id] else warmer.receipt(hint.task).also { receipts[hint.task.id] = it }
            if (warm == null) { incomplete = true; continue }
            val chapterId = hint.task.chapterId
            if (chapterId !in chapters && chapters.size == 4) { incomplete = true; continue }
            chapters += chapterId
            val scope = if (hint.task.id in scopes) scopes[hint.task.id] else inspectWarmedChapter(warm).also { scopes[hint.task.id] = it }
            val receipt = ReaderTranslationPresentation.receipt(hint.task)
            if (scope == null || !scope.binds(receipt)) { incomplete = true; continue }
            val proof = native.prepareWarmedNativeReadPage(warm, hint.pageIndex)
            val letter = proof?.page?.lettering?.getOrNull(hint.letteringIndex)
            val currentText = when (hint.kind) { MemorySearchKind.OCR -> letter?.source; MemorySearchKind.TRANSLATION -> letter?.translated; else -> null }
            if (proof == null || currentText == null || currentText != hint.text || !SeriesMemoryQuery.matches(currentText, text)) { incomplete = true; continue }
            val association = memory?.inspectChapter(chapterId)
            if (association?.removed == true || association?.association?.seriesId?.let { memory?.profile(it) == null } == true) { incomplete = true; continue }
            val lease = association?.let { memory?.prepareReadDelivery(it) }
            if (association != null && lease == null) { incomplete = true; continue }
            rows += NativeIndexedSavedTextRow(scope, warm, receipt, proof, hint.letteringIndex, hint.kind, currentText, association, lease)
        }
        NativeIndexedSavedTextSnapshot(text, rows, incomplete, NativeIndexedReadDelivery(native, rows))
    }

    /** Semantic selectors bypass lexical filtering only; all existing read authority is reacquired. */
    suspend fun prepareHint(hint: NativeLexicalHint): NativeIndexedSavedTextRow? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (hint.kind !in setOf(MemorySearchKind.OCR, MemorySearchKind.TRANSLATION)) return@withContext null
        val warm = warmer.receipt(hint.task) ?: return@withContext null
        val scope = inspectWarmedChapter(warm) ?: return@withContext null
        val receipt = ReaderTranslationPresentation.receipt(hint.task)
        if (!scope.binds(receipt)) return@withContext null
        val proof = native.prepareWarmedNativeReadPage(warm, hint.pageIndex) ?: return@withContext null
        val letter = proof.page.lettering.getOrNull(hint.letteringIndex) ?: return@withContext null
        val text = if (hint.kind == MemorySearchKind.OCR) letter.source else letter.translated
        if (text != hint.text || scope.chapter.pages.none { it.index == proof.page.index }) return@withContext null
        val association = memory?.inspectChapter(scope.chapter.id)
        if (association?.removed == true || association?.association?.seriesId?.let { memory?.profile(it) == null } == true) return@withContext null
        val lease = association?.let { memory?.prepareReadDelivery(it) }
        if (association != null && lease == null) return@withContext null
        currentCoroutineContext().ensureActive()
        NativeIndexedSavedTextRow(scope, warm, receipt, proof, hint.letteringIndex, hint.kind, text, association, lease)
            .takeIf { NativeIndexedReadDelivery(native, listOf(it)).tryCommit { true } }
    }

    fun tryDeliverRows(rows: List<NativeIndexedSavedTextRow>, accept: () -> Boolean): Boolean {
        require(rows.size <= 32 && rows.map { it.chapterId }.distinct().size <= 4)
        return NativeIndexedReadDelivery(native, rows).tryCommit(accept)
    }

    suspend fun prepareOpen(row: NativeIndexedSavedTextRow): PreparedSavedTextOpen? = prepareOpen(
        NativeIndexedSavedTextSnapshot("", listOf(row), false, NativeIndexedReadDelivery(native, listOf(row))), row.id)

    suspend fun prepareOpen(snapshot: NativeIndexedSavedTextSnapshot, rowId: String): PreparedSavedTextOpen? = withContext(Dispatchers.IO) {
        val row = snapshot.rows.singleOrNull { it.id == rowId } ?: return@withContext null
        if (row.memoryLease != null && row.memoryLease.tryCommit { true } != true) return@withContext null
        val association = memory?.inspectChapter(row.chapterId)
        if (association != row.association || association?.removed == true) return@withContext null
        val lease = association?.let { memory?.prepareReadDelivery(it) }
        if (association != null && lease == null) return@withContext null
        val warm = warmer.receipt(row.warm.task) ?: return@withContext null
        if (warm !== row.warm) return@withContext null
        val scope = inspectWarmedChapter(warm) ?: return@withContext null
        if (!scope.sameSource(row.scope) || !scope.binds(row.receipt)) return@withContext null
        val proof = native.prepareWarmedNativeReadPage(warm, row.proof.page.index) ?: return@withContext null
        if (proof.page != row.proof.page || scope.chapter.pages.getOrNull(row.pageOrdinal)?.index != proof.page.index) return@withContext null
        PreparedSavedTextOpen(SavedTextReaderSelection(scope.chapter, row.receipt, proof.page, proof.page.index,
            row.pageOrdinal, row.kind, 0, SavedTextNativeReadDelivery(native, row.receipt, proof, warm, lease)),
            NativeIndexedReadDelivery(native, listOf(NativeIndexedSavedTextRow(scope, warm, row.receipt, proof,
                row.letteringIndex, row.kind, row.text, association, lease))))
    }
}
