package com.mangalens.core.translation

import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import com.mangalens.orez.agent.OrezChapterSource
import com.mangalens.orez.agent.OrezChapterSourceEvidence
import com.mangalens.orez.agent.OrezChapterSourceReadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.UUID

/** Navigation carries app-created proof. UI paths/model strings cannot create one. */
class SavedTextReaderSelection internal constructor(
    internal val chapter: SavedChapter,
    internal val receipt: ReaderTranslationReceipt,
    internal val page: ChapterTranslationPage,
    val pageIndex: Int,
    val pageOrdinal: Int,
    val kind: MemorySearchKind,
    val revision: Int,
    private val nativeDelivery: SavedTextNativeReadDelivery,
    val requestId: String = UUID.randomUUID().toString()
) {
    val chapterId: String get() = chapter.id
    val targetLanguage: String get() = receipt.configuration.targetLanguage
    internal fun tryAcceptNative(task: ChapterTranslationTask, accept: () -> Unit): Boolean =
        nativeDelivery.tryCommit(task, accept)
    internal fun warmedTaskForRead(): ChapterTranslationTask? = nativeDelivery.warmedTaskForRead()
}

/** A Reader refresh still needs current native authority when its IO result reaches Main. */
internal class SavedTextNativeReadDelivery(private val native: ChapterTranslationStore,
    private val receipt: ReaderTranslationReceipt, private val proof: NativeMemoryPageProof,
    private val warm: NativeIndexWarmReceipt? = null, private val memoryLease: MemoryReadDeliveryLease? = null) {
    fun warmedTaskForRead(): ChapterTranslationTask? {
        val warmed = warm ?: return null
        var accepted: ChapterTranslationTask? = null
        fun capture() { native.tryCommitWarmedNativeRead(warmed, proof) { accepted = warmed.task } }
        if (memoryLease == null) capture() else memoryLease.tryCommit { capture() }
        return accepted
    }
    fun tryCommit(task: ChapterTranslationTask, accept: () -> Unit): Boolean {
        if (task.config !== proof.task.config || receipt.configuration !== proof.task.config ||
            task.validationPending || !ReaderTranslationPresentation.matchesTask(receipt, task) ||
            task.pages.singleOrNull { it.index == proof.page.index } != proof.page) return false
        if (warm == null) return native.tryCommitMemoryDelivery(receipt, proof, accept)
        if (task !== warm.task) return false
        return if (memoryLease == null) native.tryCommitWarmedNativeRead(warm, proof, accept)
            else memoryLease.tryCommit { native.tryCommitWarmedNativeRead(warm, proof, accept) } == true
    }
}

internal interface SavedTextReadAuthority { fun tryCommit(accept: () -> Boolean): Boolean }

/** Only metadata acceptance runs under leases; Reader/observer effects run after their release. */
internal class SavedTextReadDelivery(private val memory: MemoryReadDeliveryLease,
    private val native: ChapterTranslationStore, private val proofs: List<Pair<ReaderTranslationReceipt, NativeMemoryPageProof>>,
    private val manifest: MemoryReadDeliveryStamp?, private val wholeSourceStamps: List<MemoryReadDeliveryStamp> = emptyList()) : SavedTextReadAuthority {
    override fun tryCommit(accept: () -> Boolean): Boolean = memory.tryCommit {
        if (manifest?.isCurrent() == false || wholeSourceStamps.any { !it.isCurrent() }) false
        else if (proofs.isEmpty()) accept()
        else {
            var accepted = false
            val first = proofs.first()
            native.tryCommitMemoryDelivery(first.first, first.second) {
                // Reentrant short gate keeps the entire bounded result set through publication.
                if (proofs.drop(1).all { (receipt, proof) -> native.tryCommitMemoryDelivery(receipt, proof) {} } &&
                    manifest?.isCurrent() != false && wholeSourceStamps.all { it.isCurrent() }) accepted = accept()
            }
            accepted
        }
    } == true
}

class PreparedSavedTextOpen internal constructor(private val selection: SavedTextReaderSelection,
    private val delivery: SavedTextReadAuthority) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean()
    internal fun tryDeliver(accept: (SavedTextReaderSelection) -> Boolean): Boolean {
        if (!consumed.compareAndSet(false, true)) return false
        return delivery.tryCommit { accept(selection) }
    }
}

internal data class SavedTextChapterScope(val chapter: SavedChapter, val fingerprint: String, val manifest: MemoryReadDeliveryStamp?,
    val sources: Map<Int, String>, val sourceDirectory: File,
    val inspectedBytes: Long = 0, val sourceStamps: List<MemoryReadDeliveryStamp> = emptyList()) {
    fun sameSource(other: SavedTextChapterScope): Boolean = fingerprint == other.fingerprint && chapter.id == other.chapter.id &&
        chapter.sourceUrl == other.chapter.sourceUrl && chapter.pages.map { Triple(it.index, it.sourceUrl, it.localPath) } ==
        other.chapter.pages.map { Triple(it.index, it.sourceUrl, it.localPath) } && manifest == other.manifest && sources == other.sources &&
        sourceStamps == other.sourceStamps

    fun contains(source: MemorySourceProof): Boolean = source.chapterId == chapter.id && sources[source.pageIndex] == source.sourcePath

    // A selected row cannot project away other captured native pages. The exact Reader
    // must be able to bind this whole saved receipt before search publishes its text.
    fun binds(receipt: ReaderTranslationReceipt): Boolean = ReaderTranslationPresentation.matchesReader(receipt, chapter.id,
        ReaderTranslationChoice.from(receipt.configuration.copy(refinementRequest = null, memoryPacket = null)), sources, sourceDirectory)

    companion object {
        suspend fun inspect(chapter: SavedChapter, sources: File, manifest: File? = null,
            sourceByteBudget: Long = 1024L * 1024 * 1024,
            sourceReadBudget: OrezChapterSourceReadBudget? = null): SavedTextChapterScope? = try {
            val before = manifest?.let(MemoryReadDeliveryStamp::capture)
            require(before == null || before.size != null)
            val sourceStamps = chapter.pages.map { MemoryReadDeliveryStamp.capture(File(requireNotNull(it.localPath))) }
            require(sourceStamps.all { it.size != null })
            val (checked, inspectedBytes) = OrezChapterSourceEvidence.inspectWithBudget(sources, chapter.id, chapter.title,
                chapter.pages.map { OrezChapterSource(it.index, it.localPath) }, sourceReadBudget ?: OrezChapterSourceReadBudget(sourceByteBudget))
            require(before == manifest?.let(MemoryReadDeliveryStamp::capture) && sourceStamps.all { it.isCurrent() })
            SavedTextChapterScope(chapter.copy(pages = Collections.unmodifiableList(chapter.pages.toList())), checked.sourceFingerprint, before,
                Collections.unmodifiableMap(chapter.pages.associate { it.index to File(requireNotNull(it.localPath)).canonicalPath }), sources.canonicalFile,
                inspectedBytes, Collections.unmodifiableList(sourceStamps))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

internal class SavedTextSearchRow internal constructor(val hit: MemorySearchHit, val receipt: ReaderTranslationReceipt,
    val page: ChapterTranslationPage, internal val proof: NativeMemoryPageProof) {
    val id: String = TranslationRefinementPolicy.hash(listOf(hit.bubbleId, hit.kind.name, hit.revision.toString(),
        receipt.taskId, receipt.generation, receipt.owner.orEmpty(), hit.configurationIdentity,
        proof.page.cleanedSha256.orEmpty(), hit.source.sourceSha256).joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
}

internal class SavedTextSearchSnapshot internal constructor(val scope: SavedTextChapterScope, val query: String,
    val association: MemoryChapterSnapshot, rows: List<SavedTextSearchRow>, val incomplete: Boolean,
    private val delivery: SavedTextReadDelivery) {
    val rows: List<SavedTextSearchRow> = Collections.unmodifiableList(rows.toList())
    val chapterId: String get() = scope.chapter.id
    val sourceFingerprint: String get() = scope.fingerprint
    val associationRevision: Long get() = association.associationRevision
    fun tryDeliver(accept: (SavedTextSearchSnapshot) -> Boolean): Boolean = delivery.tryCommit { accept(this) }
}

/** Same real bounded native-memory pipeline serves the scoped Orez read and explicit Library UI. */
internal class NativeSavedTextSearchService(private val native: ChapterTranslationStore, private val memory: SeriesMemoryStore,
    private val inspectChapter: suspend (String) -> SavedTextChapterScope?) {
    suspend fun search(chapterId: String, query: String, limit: Int = 8): SavedTextSearchSnapshot? = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (!chapterId.matches(Regex("[a-f0-9]{32}")) || text.isBlank() || text.length > 256 || '\u0000' in text || limit !in 1..8) return@withContext null
        val scope = inspectChapter(chapterId) ?: return@withContext null
        if (scope.chapter.id != chapterId) return@withContext null
        val association = memory.inspectChapter(chapterId)
        if (association.removed || association.association?.seriesId?.let { memory.profile(it) == null } == true) return@withContext null
        val found = memory.search(text, limit, chapterIds = setOf(chapterId))
        val authority = NativeMemorySearchAuthority(native)
        val prepared = found.hits.filter { scope.contains(it.source) }.mapNotNull { authority.prepare(association, it) }
            .filter { scope.binds(it.receipt) }
        val fresh = inspectChapter(chapterId) ?: return@withContext null
        if (!scope.sameSource(fresh)) return@withContext null
        val readLease = memory.prepareReadDelivery(association) ?: return@withContext null
        memory.useChapterSnapshot(chapterId) { current ->
            if (current != association || current.removed) null else authority.publish(prepared) { hits ->
                val rows = prepared.filter { it.hit in hits }.map { SavedTextSearchRow(it.hit, it.receipt, it.proof.page, it.proof) }
                SavedTextSearchSnapshot(scope, text, association, rows, found.incomplete || rows.size != found.hits.size,
                    SavedTextReadDelivery(readLease, native, rows.map { it.receipt to it.proof }, scope.manifest))
            }
        }
    }

    suspend fun prepareOpen(snapshot: SavedTextSearchSnapshot, rowId: String,
        guardWholeSourceIncarnations: Boolean = false): PreparedSavedTextOpen? = withContext(Dispatchers.IO) {
        val row = snapshot.rows.singleOrNull { it.id == rowId } ?: return@withContext null
        val currentScope = inspectChapter(snapshot.chapterId) ?: return@withContext null
        if (!snapshot.scope.sameSource(currentScope)) return@withContext null
        val association = memory.inspectChapter(snapshot.chapterId)
        if (association != snapshot.association || association.removed) return@withContext null
        val prepared = NativeMemorySearchAuthority(native).prepare(association, row.hit) ?: return@withContext null
        if (prepared.receipt != row.receipt || prepared.proof.page != row.page || !currentScope.binds(prepared.receipt)) return@withContext null
        val ordinal = currentScope.chapter.pages.indexOfFirst { it.index == row.hit.source.pageIndex && currentScope.sources[it.index] == row.hit.source.sourcePath }
        if (ordinal < 0) return@withContext null
        val lease = memory.prepareReadDelivery(association) ?: return@withContext null
        val freshScope = inspectChapter(snapshot.chapterId) ?: return@withContext null
        if (!currentScope.sameSource(freshScope)) return@withContext null
        // The final exact task/source/output and memory/profile stamps are consumed on Main,
        // after IO can no longer establish authority for a queued UI delivery.
        PreparedSavedTextOpen(SavedTextReaderSelection(currentScope.chapter, prepared.receipt, prepared.proof.page,
            row.hit.source.pageIndex, ordinal, row.hit.kind, row.hit.revision,
            SavedTextNativeReadDelivery(native, prepared.receipt, prepared.proof)),
            SavedTextReadDelivery(lease, native, listOf(prepared.receipt to prepared.proof), currentScope.manifest,
                if (guardWholeSourceIncarnations) currentScope.sourceStamps else emptyList()))
    }
}
