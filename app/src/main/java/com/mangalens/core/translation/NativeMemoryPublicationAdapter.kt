package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class ReaderMemoryPresentation(val receipt: ReaderTranslationReceipt, val epoch: Long)

/** Navigation retires this short authority independently of disk preparation. */
internal class ReaderMemoryPublicationAuthority {
    private var epoch = 0L
    private var selected: ReaderMemoryPresentation? = null
    @Synchronized fun activate(receipt: ReaderTranslationReceipt): ReaderMemoryPresentation {
        check(epoch < Long.MAX_VALUE)
        return ReaderMemoryPresentation(receipt, ++epoch).also { selected = it }
    }
    @Synchronized fun retire() { check(epoch < Long.MAX_VALUE); ++epoch; selected = null }
    @Synchronized fun current(): ReaderMemoryPresentation? = selected
    @Synchronized fun commitIfCurrent(captured: ReaderMemoryPresentation, commit: () -> Unit) {
        check(selected == captured) { "The Reader changed. Reopen this correction editor." }
        commit()
    }
}

internal data class MemoryBubbleEditor(val receipt: MemoryPublicationReceipt)

/** Bridges an exact saved Reader selection to the separately stored personal correction journal. */
internal class NativeMemoryPublicationAdapter(
    filesRoot: File,
    private val translations: ChapterTranslationStore,
    private val authority: ReaderMemoryPublicationAuthority,
    writer: MemoryJournalWriter = AtomicMemoryJournalWriter
) {
    val memory = SeriesMemoryStore(filesRoot, writer, MemoryPublicationFence(::publish))

    suspend fun openEditor(presentation: ReaderMemoryPresentation, pageIndex: Int, letteringIndex: Int): MemoryBubbleEditor? = withContext(Dispatchers.IO) {
        if (authority.current() != presentation) return@withContext null
        val proof = translations.prepareMemoryPublication(presentation.receipt, pageIndex) ?: return@withContext null
        val letter = proof.page.lettering.getOrNull(letteringIndex) ?: return@withContext null
        if (letter.originalSourceBounds == null) return@withContext null
        val chapter = memory.inspectChapter(proof.task.chapterId)
        if (chapter.removed || chapter.association?.seriesId?.let { memory.profile(it) == null } == true) return@withContext null
        val captured = receipt(proof, letter, presentation, chapter.association?.seriesId, chapter.associationRevision)
        memory.indexBubble(captured, captured)
        MemoryBubbleEditor(captured)
    }

    suspend fun correct(editor: MemoryBubbleEditor, expectedRevision: Int, edit: MemoryCorrectionEdit): MemoryCorrection =
        memory.correct(editor.receipt, editor.receipt, expectedRevision, edit)

    suspend fun rollback(editor: MemoryBubbleEditor, expectedRevision: Int, restoreRevision: Int): MemoryCorrection =
        memory.rollback(editor.receipt, editor.receipt, expectedRevision, restoreRevision)

    suspend fun removeCorrection(editor: MemoryBubbleEditor, expectedRevision: Int) =
        memory.removeCorrection(editor.receipt, editor.receipt, expectedRevision)

    suspend fun inspect(editor: MemoryBubbleEditor): MemoryIndexedBubble? = memory.inspectChapter(editor.receipt.source.chapterId)
        .bubbles.singleOrNull { it.receipt.bubbleId == editor.receipt.bubbleId }

    /** Hash/geometry checks stay on IO; Reader/task authority protects only the final UI publication. */
    internal suspend fun publishPersonalOverlay(presentation: ReaderMemoryPresentation, pageIndex: Int,
        publish: (Map<Int, PersonalMangaLettering>) -> Unit) = memory.useChapterSnapshot(presentation.receipt.chapterId) { chapter ->
        val proof = translations.prepareMemoryPublication(presentation.receipt, pageIndex)
        if (proof == null) authority.commitIfCurrent(presentation) { publish(emptyMap()) }
        else {
            val personal = PersonalMemoryOverlayPolicy.project(proof, translations.memoryConfigurationIdentity(proof.task.config), chapter)
            authority.commitIfCurrent(presentation) { translations.commitMemoryPublication(presentation.receipt, proof) { publish(personal) } }
        }
    }

    private fun publish(captured: MemoryPublicationReceipt, commit: () -> Unit) {
        check(captured.nativeAuthorityVersion == 1) { "Reopen this legacy entry from its verified saved page." }
        val selected = authority.current()?.takeIf { it.epoch == captured.presentationEpoch }
            ?: error("The Reader changed. Reopen this correction editor.")
        val proof = translations.prepareMemoryPublication(selected.receipt, captured.source.pageIndex)
            ?: error("The current saved page could not be verified. Reopen its correction editor.")
        val matching = proof.page.lettering.filter { letter -> letter.originalSourceBounds != null &&
            receipt(proof, letter, selected, captured.seriesId, requireNotNull(captured.associationRevision)) == captured }
        check(matching.size == 1) { "The selected source bubble or translation changed. Reopen its correction editor." }
        authority.commitIfCurrent(selected) { translations.commitMemoryPublication(selected.receipt, proof, commit) }
    }

    private fun receipt(proof: NativeMemoryPageProof, letter: SavedMangaLettering, selected: ReaderMemoryPresentation,
        seriesId: String?, associationRevision: Long): MemoryPublicationReceipt {
        val page = proof.page; val bounds = requireNotNull(letter.originalSourceBounds)
        bounds.validate(requireNotNull(page.originalWidth), requireNotNull(page.originalHeight))
        return MemoryPublicationReceipt(MemorySourceProof(proof.task.chapterId, page.index, requireNotNull(page.sourcePath),
            requireNotNull(page.sourceSha256), page.originalWidth, page.originalHeight,
            MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)), proof.task.id, proof.task.generation,
            proof.task.config.targetLanguage, translations.memoryConfigurationIdentity(proof.task.config), letter.source, letter.translated,
            page.cleanedPath, page.cleanedSha256, seriesId, nativeAuthorityVersion = 1, ownerRequestId = proof.task.ownerRequestId,
            presentationEpoch = selected.epoch, associationRevision = associationRevision).also { it.validate() }
    }
}
