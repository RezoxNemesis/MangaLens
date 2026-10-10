package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal data class ReaderMemoryPresentation(val receipt: ReaderTranslationReceipt, val epoch: Long)

/** Navigation retires this short authority independently of disk preparation. */
internal class ReaderMemoryPublicationAuthority {
    private var epoch = 0L
    @Volatile private var selected: ReaderMemoryPresentation? = null
    @Synchronized fun activate(receipt: ReaderTranslationReceipt): ReaderMemoryPresentation {
        check(epoch < Long.MAX_VALUE)
        return ReaderMemoryPresentation(receipt, ++epoch).also { selected = it }
    }
    @Synchronized fun retire() { check(epoch < Long.MAX_VALUE); ++epoch; selected = null }
    @Synchronized fun current(): ReaderMemoryPresentation? = selected
    /** Main delivery checks captured identity without waiting for a publication monitor. */
    fun isCurrent(captured: ReaderMemoryPresentation): Boolean = selected === captured
    fun peekCurrent(): ReaderMemoryPresentation? = selected
    @Synchronized fun commitIfCurrent(captured: ReaderMemoryPresentation, commit: () -> Unit) {
        check(selected == captured) { "The Reader changed. Reopen this correction editor." }
        commit()
    }
}

internal data class MemoryBubbleEditor(val receipt: MemoryPublicationReceipt, internal val scope: ReaderBubbleEditScope?) {
    constructor(receipt: MemoryPublicationReceipt) : this(receipt, null)
}

/** Bridges an exact saved Reader selection to the separately stored personal correction journal. */
internal class NativeMemoryPublicationAdapter(
    private val filesRoot: File,
    private val translations: ChapterTranslationStore,
    private val authority: ReaderMemoryPublicationAuthority,
    private val writer: MemoryJournalWriter = AtomicMemoryJournalWriter
) {
    val memory = SeriesMemoryStore(filesRoot, writer, MemoryPublicationFence(::publish))

    /** Reading does not index a bubble, prepare a journal, or recapture the Reader presentation. */
    suspend fun inspectSavedBubble(presentation: ReaderMemoryPresentation, pageIndex: Int, letteringIndex: Int,
        expectedNative: SavedMangaLettering): ReaderBubbleInspection? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!authority.isCurrent(presentation)) return@withContext null
        val proof = translations.prepareSavedPageRead(presentation.receipt, pageIndex) ?: return@withContext null
        val letter = proof.page.lettering.getOrNull(letteringIndex)?.takeIf { it == expectedNative } ?: return@withContext null
        val expectedChapter = memory.inspectChapter(proof.task.chapterId)
        if (expectedChapter.removed) return@withContext null
        val personalRead = memory.prepareChapterRead(expectedChapter) ?: return@withContext null
        val chapter = personalRead.chapter
        val profile = personalRead.profile
        val configuration = translations.memoryConfigurationIdentity(proof.task.config)
        val personal = PersonalMemoryOverlayPolicy.project(proof, configuration, chapter)[letteringIndex]
        val indexed = chapter.bubbles.filter { value ->
            nativeReceiptMatches(value.receipt, proof, configuration) &&
                value.receipt.seriesId == chapter.association?.seriesId && value.receipt.associationRevision == chapter.associationRevision &&
                value.receipt.originalOcr == letter.source && value.receipt.originalTranslation == letter.translated &&
                value.receipt.source.bounds == letter.originalSourceBounds?.let { bounds ->
                    MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
        }.singleOrNull()
        val source = if (letter.originalSourceBounds == null || proof.page.originalWidth == null || proof.page.originalHeight == null) null
            else receipt(proof, letter, presentation, chapter.association?.seriesId, chapter.associationRevision).source
        val edit = indexed?.correction?.edit?.takeIf { personal != null }
        val view = ReaderBubbleView(letter.source, letter.translated, edit?.correctedOcr, edit?.translated,
            indexed?.editRevision ?: 0, letter.savedHindiDraft, edit?.hindiDraft, proof.task.config.targetLanguage,
            source != null, chapter.association?.seriesId, profile?.title)
        val delivery = personalRead.delivery
        currentCoroutineContext().ensureActive()
        if (!authority.isCurrent(presentation)) return@withContext null
        // Main's native gate requires this actual immutable config instance, not a decoded equal copy.
        val freshReaderReceipt = ReaderTranslationPresentation.receipt(proof.task)
        ReaderBubbleInspection(presentation, pageIndex, letteringIndex, letter, source, freshReaderReceipt, proof, delivery, chapter, view, profile)
    }

    /** Main does bounded NOFOLLOW metadata stat, but no hash/JSON/fsync or waiting monitor. */
    fun tryAcceptInspection(inspection: ReaderBubbleInspection): ReaderBubbleView? {
        if (!authority.isCurrent(inspection.presentation)) return null
        val view = inspection.memoryDelivery.tryCommit {
            var accepted: ReaderBubbleView? = null
            translations.tryCommitMemoryDelivery(inspection.readerReceipt, inspection.proof) {
                // Copy immutable metadata only; dialog/StateFlow effects occur after both gates release.
                if (authority.isCurrent(inspection.presentation)) accepted = inspection.view
            }
            accepted
        }
        return view?.takeIf { authority.isCurrent(inspection.presentation) }
    }

    /** Full source verification stays on IO, including re-entry after native admission waiting. */
    suspend fun isInspectionCurrentOnIo(inspection: ReaderBubbleInspection): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (tryAcceptInspection(inspection) == null) return@withContext false
        val fresh = translations.prepareSavedPageRead(inspection.readerReceipt, inspection.pageIndex) ?: return@withContext false
        currentCoroutineContext().ensureActive()
        fresh.source == inspection.proof.source && fresh.output == inspection.proof.output &&
            fresh.page == inspection.proof.page && fresh.page.lettering.getOrNull(inspection.letteringIndex) == inspection.expectedNative &&
            tryAcceptInspection(inspection) != null
    }

    /** The user's explicit edit/glossary action may index; opening inspection alone never does. */
    suspend fun openInspectedEditor(inspection: ReaderBubbleInspection): ReaderBubbleEditorDelivery? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (inspection.source == null || !isInspectionCurrentOnIo(inspection)) return@withContext null
        val proof = translations.prepareMemoryPublication(inspection.readerReceipt, inspection.pageIndex) ?: return@withContext null
        if (proof.source != inspection.proof.source || proof.output != inspection.proof.output || proof.page != inspection.proof.page)
            return@withContext null
        val letter = proof.page.lettering.getOrNull(inspection.letteringIndex)?.takeIf { it == inspection.expectedNative } ?: return@withContext null
        val scope = ReaderBubbleEditScope(inspection.presentation, ReaderTranslationPresentation.receipt(proof.task), proof,
            inspection.letteringIndex, letter)
        val captured = receipt(proof, letter, scope.presentation, inspection.chapter.association?.seriesId,
            inspection.chapter.associationRevision)
        val editor = MemoryBubbleEditor(captured, scope)
        val store = memoryFor(editor) { check(tryAcceptInspection(inspection) != null) { "Personal or native text changed. Reopen this bubble." } }
        val indexed = store.indexInspectedBubble(captured, captured, inspection.chapter)
        currentCoroutineContext().ensureActive()
        val delivery = store.prepareReadDelivery(indexed.second) ?: return@withContext null
        if (!authority.isCurrent(scope.presentation)) return@withContext null
        ReaderBubbleEditorDelivery(scope, ReaderMemoryEditor(editor, indexed.first, letter.savedHindiDraft), delivery)
    }

    fun tryAcceptEditor(delivery: ReaderBubbleEditorDelivery): ReaderMemoryEditor? {
        if (!authority.isCurrent(delivery.scope.presentation)) return null
        val copied = delivery.memoryDelivery.tryCommit {
            var accepted: ReaderMemoryEditor? = null
            translations.tryCommitMemoryDelivery(delivery.scope.receipt, delivery.scope.proof) {
                if (authority.isCurrent(delivery.scope.presentation)) accepted = delivery.editor
            }
            accepted
        }
        return copied?.takeIf { authority.isCurrent(delivery.scope.presentation) }
    }

    suspend fun askContext(inspection: ReaderBubbleInspection): com.mangalens.orez.SavedBubbleOrezContext? = withContext(Dispatchers.IO) {
        if (!isInspectionCurrentOnIo(inspection)) return@withContext null
        val personal = PersonalMemoryOverlayPolicy.project(inspection.proof,
            translations.memoryConfigurationIdentity(inspection.proof.task.config), inspection.chapter)
        fun text(index: Int): com.mangalens.orez.SavedBubbleOrezText? {
            val native = inspection.proof.page.lettering.getOrNull(index) ?: return null
            val edit = if (personal[index] == null) null else inspection.chapter.bubbles.filter { bubble ->
                nativeReceiptMatches(bubble.receipt, inspection.proof, translations.memoryConfigurationIdentity(inspection.proof.task.config)) &&
                    bubble.receipt.originalOcr == native.source && bubble.receipt.originalTranslation == native.translated &&
                    bubble.receipt.source.bounds == native.originalSourceBounds?.let { MemoryRegionBounds(it.left, it.top, it.right, it.bottom) }
            }.singleOrNull()?.correction?.edit
            return com.mangalens.orez.SavedBubbleOrezText(native.source, native.translated, edit?.correctedOcr,
                edit?.translated, native.savedHindiDraft, edit?.hindiDraft)
        }
        val selected = text(inspection.letteringIndex) ?: return@withContext null
        com.mangalens.orez.SavedBubbleOrezContext(inspection.view.targetLanguage, selected,
            listOfNotNull(text(inspection.letteringIndex - 1), text(inspection.letteringIndex + 1)))
    }

    suspend fun addInspectedGlossaryTerm(inspection: ReaderBubbleInspection, editor: ReaderMemoryEditor,
        term: SeriesGlossaryTerm): SeriesGlossaryTerm {
        check(isInspectionCurrentOnIo(inspection)) { "Personal or native text changed. Reopen this bubble." }
        return memoryFor(editor.captured) {
            // These tryLock read gates release before the native→Reader publication monitors.
            check(tryAcceptInspection(inspection) != null) { "This series profile or bubble changed. Reopen it." }
        }.upsertBubbleTerm(editor.captured.receipt, editor.captured.receipt, editor.bubble.editRevision, term)
    }

    private suspend fun memoryFor(editor: MemoryBubbleEditor, beforePublish: () -> Unit = {}): SeriesMemoryStore {
        val scope = editor.scope ?: return memory
        val caller = currentCoroutineContext()
        return SeriesMemoryStore(filesRoot, writer, MemoryPublicationFence { captured, commit ->
            caller.ensureActive()
            beforePublish()
            publishInspected(scope, captured) { caller.ensureActive(); commit() }
        })
    }

    private fun publishInspected(scope: ReaderBubbleEditScope, captured: MemoryPublicationReceipt, commit: () -> Unit) {
        check(authority.isCurrent(scope.presentation)) { "The Reader changed. Reopen this bubble." }
        val proof = translations.prepareMemoryPublication(scope.receipt, captured.source.pageIndex)
            ?: error("The saved source or native translation changed. Reopen this bubble.")
        check(proof.source == scope.proof.source && proof.output == scope.proof.output && proof.page == scope.proof.page &&
            proof.page.lettering.getOrNull(scope.letteringIndex) == scope.expectedNative)
        check(receipt(proof, scope.expectedNative, scope.presentation, captured.seriesId,
            requireNotNull(captured.associationRevision)) == captured)
        translations.commitMemoryPublication(scope.receipt, proof) { authority.commitIfCurrent(scope.presentation, commit) }
    }

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
        memoryFor(editor).correct(editor.receipt, editor.receipt, expectedRevision, edit)

    suspend fun addGlossaryTerm(editor: MemoryBubbleEditor, expectedRevision: Int, term: SeriesGlossaryTerm): SeriesGlossaryTerm =
        memoryFor(editor).upsertBubbleTerm(editor.receipt, editor.receipt, expectedRevision, term)

    suspend fun rollback(editor: MemoryBubbleEditor, expectedRevision: Int, restoreRevision: Int): MemoryCorrection =
        memoryFor(editor).rollback(editor.receipt, editor.receipt, expectedRevision, restoreRevision)

    suspend fun removeCorrection(editor: MemoryBubbleEditor, expectedRevision: Int) =
        memoryFor(editor).removeCorrection(editor.receipt, editor.receipt, expectedRevision)

    suspend fun inspect(editor: MemoryBubbleEditor): MemoryIndexedBubble? = memory.inspectChapter(editor.receipt.source.chapterId)
        .bubbles.singleOrNull { it.receipt.bubbleId == editor.receipt.bubbleId }

    /** Hash/geometry checks stay on IO; Reader/task authority protects only the final UI publication. */
    internal suspend fun publishPersonalOverlay(presentation: ReaderMemoryPresentation, pageIndex: Int,
        publish: (Map<Int, PersonalMangaLettering>) -> Unit) = memory.useChapterSnapshot(presentation.receipt.chapterId) { chapter ->
        val proof = translations.prepareMemoryPublication(presentation.receipt, pageIndex)
        if (proof == null) authority.commitIfCurrent(presentation) { publish(emptyMap()) }
        else {
            val personal = PersonalMemoryOverlayPolicy.project(proof, translations.memoryConfigurationIdentity(proof.task.config), chapter)
            translations.commitMemoryPublication(presentation.receipt, proof) { authority.commitIfCurrent(presentation) { publish(personal) } }
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
        translations.commitMemoryPublication(selected.receipt, proof) { authority.commitIfCurrent(selected, commit) }
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
