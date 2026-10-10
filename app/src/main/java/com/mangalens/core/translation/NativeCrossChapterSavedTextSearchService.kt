package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import com.mangalens.orez.agent.OrezChapterSourceReadBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

internal enum class SavedTextSearchSource { ALL, NATIVE, PERSONAL }

internal class CrossChapterSavedTextRow internal constructor(internal val chapterSnapshot: SavedTextSearchSnapshot,
    val row: SavedTextSearchRow) {
    val id: String get() = row.id
    val chapterId: String get() = chapterSnapshot.chapterId
    val chapterTitle: String get() = chapterSnapshot.scope.chapter.title
    val pageOrdinal: Int get() = chapterSnapshot.scope.chapter.pages.indexOfFirst { it.index == row.hit.source.pageIndex }
}

internal class CrossChapterSavedTextSnapshot internal constructor(val query: String, snapshots: List<SavedTextSearchSnapshot>,
    val incomplete: Boolean, internal val nativeSnapshot: NativeIndexedSavedTextSnapshot? = null) {
    private val chapters = Collections.unmodifiableList(snapshots.toList())
    val rows: List<CrossChapterSavedTextRow> = Collections.unmodifiableList(chapters.flatMap { chapter -> chapter.rows.map { CrossChapterSavedTextRow(chapter, it) } })
    val nativeRows: List<NativeIndexedSavedTextRow> = nativeSnapshot?.rows ?: emptyList()
    init { require((rows.map { it.chapterId } + nativeRows.map { it.chapterId }).distinct().size <= 4 && rows.size + nativeRows.size <= 8 &&
        (rows.map { it.id } + nativeRows.map { it.id }).distinct().size == rows.size + nativeRows.size) }
    /** All exact short leases remain held through a synchronous state acceptance; no UI effects here. */
    fun tryDeliver(accept: (CrossChapterSavedTextSnapshot) -> Boolean): Boolean {
        fun consume(index: Int): Boolean = if (index == chapters.size) nativeSnapshot?.tryDeliver { accept(this) } ?: accept(this)
            else chapters[index].tryDeliver { consume(index + 1) }
        return consume(0)
    }
}

/** Offline lexical index hints do not bypass current source/native/personal/whole Reader proof. */
internal class NativeCrossChapterSavedTextSearchService(private val native: ChapterTranslationStore,
    private val memory: SeriesMemoryStore, private val index: MemoryLexicalHintStore,
    private val nativeIndex: NativeIndexedSavedTextSearchService? = null,
    private val inspectChapter: suspend (String, OrezChapterSourceReadBudget) -> SavedTextChapterScope?) {
    private val opener = NativeSavedTextSearchService(native, memory) { id ->
        inspectChapter(id, OrezChapterSourceReadBudget(MAX_SOURCE_BYTES))?.takeIf { validScope(it, MAX_SOURCE_BYTES) }
    }

    suspend fun search(query: String, limit: Int = 8, forceRefresh: Boolean = false,
        source: SavedTextSearchSource = SavedTextSearchSource.ALL): CrossChapterSavedTextSnapshot? = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank() || text.length > 256 || '\u0000' in text || limit !in 1..8) return@withContext null
        val nativeSnapshot = if (source != SavedTextSearchSource.PERSONAL) nativeIndex?.search(text, limit) else null
        val nativeRows = nativeSnapshot?.rows ?: emptyList()
        if (source == SavedTextSearchSource.NATIVE || nativeRows.size == limit) return@withContext CrossChapterSavedTextSnapshot(
            text, emptyList(), nativeSnapshot?.incomplete ?: true, nativeSnapshot)
        val found = index.find(text, 32, forceRefresh, if (nativeIndex == null) null else
            setOf(MemorySearchKind.CORRECTED_OCR, MemorySearchKind.CORRECTED_TRANSLATION))
        val groups = found.hints.groupBy { it.chapterId }.entries.toList()
        var incomplete = found.incomplete || groups.size > MAX_CHAPTERS || nativeSnapshot?.incomplete == true
        val sourceBudget = OrezChapterSourceReadBudget(MAX_SOURCE_BYTES)
        val freshBudget = OrezChapterSourceReadBudget(MAX_SOURCE_BYTES)
        var remainingRows = limit - nativeRows.size
        val usedChapters = nativeRows.map { it.chapterId }.toMutableSet()
        val accepted = ArrayList<SavedTextSearchSnapshot>()
        for ((chapterId, hints) in groups.take(MAX_CHAPTERS)) {
            currentCoroutineContext().ensureActive()
            if (remainingRows == 0 || sourceBudget.remainingBytes <= 0 || freshBudget.remainingBytes <= 0) { incomplete = true; break }
            if (chapterId !in usedChapters && usedChapters.size == MAX_CHAPTERS) { incomplete = true; continue }
            val chapter = memory.inspectChapter(chapterId)
            if (chapter.removed || chapter.association?.seriesId?.let { memory.profile(it) == null } == true) { incomplete = true; continue }
            val currentHints = hints.filter { hint -> hint.associationRevision == chapter.associationRevision && hint.seriesId == chapter.association?.seriesId }
            val hits = SeriesMemoryQuery.search(text, chapter.bubbles, chapter.association?.seriesId).filter { hit ->
                (nativeIndex == null || hit.kind in setOf(MemorySearchKind.CORRECTED_OCR, MemorySearchKind.CORRECTED_TRANSLATION)) &&
                currentHints.any { hint -> hint.bubbleId == hit.bubbleId && hint.kind == hit.kind && hint.editRevision == hit.revision &&
                    hint.normalizedText == memoryTextKey(hit.text) && hint.seriesId == hit.seriesId }
            }.take(remainingRows)
            if (hits.size < hints.size) incomplete = true
            if (hits.isEmpty()) continue
            val remaining = sourceBudget.remainingBytes
            val scope = inspectChapter(chapterId, sourceBudget)
            if (scope == null || scope.chapter.id != chapterId || !validScope(scope, remaining)) { incomplete = true; continue }
            val authority = NativeMemorySearchAuthority(native)
            val prepared = hits.filter { scope.contains(it.source) }.mapNotNull { authority.prepare(chapter, it) }.filter { scope.binds(it.receipt) }
            if (prepared.isEmpty()) { incomplete = true; continue }
            val remainingFresh = freshBudget.remainingBytes
            val fresh = inspectChapter(chapterId, freshBudget)
            if (fresh == null || !validScope(fresh, remainingFresh) || !scope.sameSource(fresh)) { incomplete = true; continue }
            val lease = memory.prepareReadDelivery(chapter)
            if (lease == null) { incomplete = true; continue }
            val snapshot = memory.useChapterSnapshot(chapterId) { current ->
                if (current != chapter || current.removed) null else authority.publish(prepared) { proven ->
                    val rows = prepared.filter { it.hit in proven }.map { SavedTextSearchRow(it.hit, it.receipt, it.proof.page, it.proof) }
                    SavedTextSearchSnapshot(scope, text, chapter, rows, rows.size != hints.size,
                        SavedTextReadDelivery(lease, native, rows.map { it.receipt to it.proof }, scope.manifest, scope.sourceStamps))
                }
            }
            if (snapshot == null || snapshot.rows.isEmpty()) { incomplete = true; continue }
            incomplete = incomplete || snapshot.incomplete
            accepted += snapshot
            usedChapters += chapterId
            remainingRows -= snapshot.rows.size
        }
        CrossChapterSavedTextSnapshot(text, accepted, incomplete, nativeSnapshot)
    }

    suspend fun prepareOpen(snapshot: CrossChapterSavedTextSnapshot, rowId: String): PreparedSavedTextOpen? {
        if (snapshot.nativeRows.any { it.id == rowId }) return nativeIndex?.prepareOpen(requireNotNull(snapshot.nativeSnapshot), rowId)
        val selected = snapshot.rows.singleOrNull { it.id == rowId } ?: return null
        return opener.prepareOpen(selected.chapterSnapshot, selected.id, guardWholeSourceIncarnations = true)
    }

    private fun validScope(scope: SavedTextChapterScope, budget: Long): Boolean =
        scope.chapter.pages.size in 1..MAX_PAGES && scope.inspectedBytes in 1..budget &&
            scope.sourceStamps.size == scope.chapter.pages.size && scope.sourceStamps.all { it.size != null && it.isCurrent() }

    companion object {
        const val MAX_CHAPTERS = 4
        const val MAX_PAGES = 64
        const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    }
}
