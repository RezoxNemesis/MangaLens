package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourceWorkKind
import com.mangalens.core.translation.memory.*
import com.mangalens.orez.SavedBubbleOrezAnswer
import com.mangalens.orez.SavedBubbleOrezAnswerPolicy
import com.mangalens.ui.reader.ReaderBubbleOriginalCrop
import com.mangalens.ui.reader.ReaderBubbleOriginalCropLoader
import com.mangalens.ui.reader.ReaderBubblePreviewOwnership
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

internal typealias SavedBubbleExplanation = suspend (String, NativeComputePrecondition) -> String?

internal data class ReaderBubbleToolsState(
    val open: Boolean = false,
    val pageIndex: Int? = null,
    val letteringIndex: Int? = null,
    val view: ReaderBubbleView? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val preview: ReaderBubblePreviewOwnership<ReaderBubbleOriginalCrop>? = null,
    val glossaryEditor: ReaderMemoryEditor? = null,
    val answer: SavedBubbleOrezAnswer? = null,
    val alternatives: List<ReaderBubbleRegionAlternative> = emptyList()
)

/** Explicit actions retain the selected native and personal snapshots; a callback cannot create proof. */
internal class ReaderBubbleToolsController(
    private val filesRoot: File,
    private val scope: CoroutineScope,
    private val authority: ReaderMemoryPublicationAuthority,
    private val storeProvider: suspend () -> ChapterTranslationStore,
    private val writer: MemoryJournalWriter,
    private val explanation: () -> SavedBubbleExplanation?,
    private val showEditor: (ReaderMemoryPresentation, Int, ReaderMemoryEditor) -> Unit,
    private val regionActions: () -> SavedBubbleRegionActions? = { null },
    private val showGeneratedEditor: ((ReaderMemoryPresentation, Int, ReaderMemoryEditor, MemoryCorrectionEdit, String) -> Unit)? = null
) {
    private val _state = MutableStateFlow(ReaderBubbleToolsState())
    val state = _state.asStateFlow()
    @Volatile private var selectionId = 0L
    @Volatile private var operationId = 0L
    @Volatile private var selected: ReaderMemoryPresentation? = null
    private var inspection: ReaderBubbleInspection? = null
    private var operation: Job? = null

    fun open(presentation: ReaderMemoryPresentation, pageIndex: Int, letteringIndex: Int, expected: SavedMangaLettering) {
        if (!authority.isCurrent(presentation)) return
        dismiss()
        selected = presentation
        val selection = selectionId
        _state.value = ReaderBubbleToolsState(open = true, pageIndex = pageIndex, letteringIndex = letteringIndex, busy = true)
        launchOperation(selection) { request ->
            val adapter = withContext(Dispatchers.IO) { adapter(storeProvider()) }
            val prepared = adapter.inspectSavedBubble(presentation, pageIndex, letteringIndex, expected)
                ?: throw SelectionChanged()
            requireCurrent(selection, request)
            val view = adapter.tryAcceptInspection(prepared) ?: throw SelectionChanged()
            inspection = prepared
            _state.update { it.copy(view = view, busy = false) }
            // A preview has its own verified original FD and is never sent to OCR or Orez.
            val crop = ReaderBubbleOriginalCropLoader(File(filesRoot, "chapters")).open(prepared) {
                currentCoroutineContext().ensureActive()
                isCurrent(selection) && adapter.isInspectionCurrentOnIo(prepared) &&
                    ResourceGovernorRuntime.shared.awaitBoundary(ResourceWorkKind.INTERACTIVE) { isCurrent(selection) }
            }
            if (crop != null) {
                val ownership = ReaderBubblePreviewOwnership(crop)
                try {
                    requireCurrent(selection, request)
                    if (adapter.tryAcceptInspection(prepared) == null) throw SelectionChanged()
                    _state.update { it.copy(preview = ownership) }
                } catch (problem: Throwable) { ownership.abandon(); throw problem }
            }
        }
    }

    fun dismiss() {
        ++selectionId; ++operationId
        operation?.cancel(); operation = null
        selected = null; inspection = null
        val preview = _state.value.preview
        _state.value = ReaderBubbleToolsState()
        // A displayed Image closes at Compose disposal, never while it can still draw the bitmap.
        preview?.abandon()
    }

    fun onVisiblePage(index: Int?) {
        if (_state.value.open && index != _state.value.pageIndex) dismiss()
    }

    fun edit() = act { selection, request, adapter, captured ->
        val delivery = adapter.openInspectedEditor(captured) ?: throw SelectionChanged()
        requireCurrent(selection, request)
        val editor = adapter.tryAcceptEditor(delivery) ?: throw SelectionChanged()
        val presentation = captured.presentation
        val page = captured.pageIndex
        // All state and UI effects follow the short delivery gates.
        dismiss()
        showEditor(presentation, page, editor)
    }

    fun prepareGlossary() = act { selection, request, adapter, captured ->
        check(captured.view.linkedSeriesId != null) { "Link this chapter to a series in Library before saving a glossary term." }
        val delivery = adapter.openInspectedEditor(captured) ?: throw SelectionChanged()
        requireCurrent(selection, request)
        val editor = adapter.tryAcceptEditor(delivery) ?: throw SelectionChanged()
        // Our explicit index changes the memory journal. Recapture only its read lease, retaining G1.
        val refreshed = refreshAfterOwnWrite(adapter, captured)
        requireCurrent(selection, request)
        val view = adapter.tryAcceptInspection(refreshed) ?: throw SelectionChanged()
        inspection = refreshed
        _state.update { it.copy(view = view, glossaryEditor = editor, alternatives = emptyList(), message = null, error = false) }
    }

    fun saveGlossary(source: String, preferred: String) = act { selection, request, adapter, captured ->
        val editor = _state.value.glossaryEditor ?: error("Choose Add to glossary before saving a term.")
        val receipt = editor.captured.receipt
        val term = SeriesGlossaryTerm(UUID.randomUUID().toString(), source.trim(), preferred.trim(), receipt.targetLanguage,
            kind = MemoryTermKind.PHRASE, origin = MemoryLocation(receipt.source.chapterId, receipt.source.pageIndex),
            originSourceSha256 = receipt.source.sourceSha256)
        term.validate()
        adapter.addInspectedGlossaryTerm(captured, editor, term)
        val refreshed = refreshAfterOwnWrite(adapter, captured)
        requireCurrent(selection, request)
        val view = adapter.tryAcceptInspection(refreshed) ?: throw SelectionChanged()
        inspection = refreshed
        _state.update { it.copy(view = view, glossaryEditor = null, alternatives = emptyList(), message = "Glossary term saved.", error = false) }
    }

    val regionActionsAvailable: Boolean get() = regionActions() != null && showGeneratedEditor != null

    fun retryOriginalOcr() = runRegionAction(ReaderBubbleRegionAction.RETRY_ORIGINAL_OCR)
    fun regenerateTranslation() = runRegionAction(ReaderBubbleRegionAction.REGENERATE_TRANSLATION)

    private fun runRegionAction(action: ReaderBubbleRegionAction) = act { selection, request, adapter, captured ->
        check(captured.source != null) { "Retranslate this page to establish its original coordinates." }
        val execute = regionActions() ?: error("Selected-region tools are unavailable.")
        val owner = NativeComputePrecondition { waited ->
            requireCurrent(selection, request)
            val valid = if (waited) adapter.isInspectionCurrentOnIo(captured) else adapter.tryAcceptInspection(captured) != null
            if (!valid) throw SelectionChanged()
        }
        val result = execute(captured, action, captured.view.personalOcr ?: captured.view.originalOcr, owner)
        if (!adapter.isInspectionCurrentOnIo(captured)) throw SelectionChanged()
        requireCurrent(selection, request)
        if (adapter.tryAcceptInspection(captured) == null) throw SelectionChanged()
        check(result.alternatives.size <= 8 && result.alternatives.map { it.id }.distinct().size == result.alternatives.size)
        result.alternatives.forEach { ReaderBubbleAlternativeInputPolicy.edit(it) }
        _state.update { it.copy(alternatives = result.alternatives.toList(), message = result.message, error = false) }
    }

    fun chooseAlternative(alternative: ReaderBubbleRegionAlternative) = act { selection, request, adapter, captured ->
        // A caller-created copy or a result from a previous operation cannot select a preset.
        check(_state.value.alternatives.any { it === alternative }) { "Choose an alternative from this current bubble." }
        val show = showGeneratedEditor ?: error("The correction editor is unavailable.")
        val edit = ReaderBubbleAlternativeInputPolicy.edit(alternative)
        val delivery = adapter.openInspectedEditor(captured) ?: throw SelectionChanged()
        requireCurrent(selection, request)
        val editor = adapter.tryAcceptEditor(delivery) ?: throw SelectionChanged()
        val label = when (alternative.kind) {
            ReaderBubbleAlternativeKind.ORIGINAL_OCR -> "Original-pixel OCR alternative · review OCR and translation before saving"
            ReaderBubbleAlternativeKind.ON_DEVICE_DRAFT -> "On-device translation draft · review before saving"
            ReaderBubbleAlternativeKind.PINNED_LOCAL_REFINEMENT -> "Pinned local refinement · review before saving"
        }
        dismiss()
        show(captured.presentation, captured.pageIndex, editor, edit, label)
    }

    fun ask(question: String) = act { selection, request, adapter, captured ->
        val context = adapter.askContext(captured) ?: throw SelectionChanged()
        val owner = NativeComputePrecondition { waited ->
            requireCurrent(selection, request)
            val valid = if (waited) adapter.isInspectionCurrentOnIo(captured) else adapter.tryAcceptInspection(captured) != null
            if (!valid) throw SelectionChanged()
        }
        val local = explanation()
        val answer = SavedBubbleOrezAnswerPolicy.answer(question, context) { prompt ->
            requireCurrent(selection, request)
            // The optional callback can reach only the distinct, pinned local explanation service.
            local?.invoke(prompt, owner)
        }
        if (!adapter.isInspectionCurrentOnIo(captured)) throw SelectionChanged()
        requireCurrent(selection, request)
        if (adapter.tryAcceptInspection(captured) == null) throw SelectionChanged()
        _state.update { it.copy(answer = answer) }
    }

    private suspend fun refreshAfterOwnWrite(adapter: NativeMemoryPublicationAdapter,
        old: ReaderBubbleInspection): ReaderBubbleInspection {
        val fresh = adapter.inspectSavedBubble(old.presentation, old.pageIndex, old.letteringIndex, old.expectedNative)
            ?: throw SelectionChanged()
        if (fresh.proof.source != old.proof.source || fresh.proof.output != old.proof.output || fresh.proof.page != old.proof.page ||
            fresh.chapter.association != old.chapter.association || fresh.chapter.associationRevision != old.chapter.associationRevision)
            throw SelectionChanged()
        return fresh
    }

    private fun act(action: suspend (Long, Long, NativeMemoryPublicationAdapter, ReaderBubbleInspection) -> Unit) {
        if (_state.value.busy) return
        val captured = inspection ?: return
        val selection = selectionId
        if (!isCurrent(selection)) return
        _state.update { it.copy(busy = true, message = null, error = false) }
        launchOperation(selection) { request ->
            val adapter = withContext(Dispatchers.IO) { adapter(storeProvider()) }
            if (!adapter.isInspectionCurrentOnIo(captured)) throw SelectionChanged()
            requireCurrent(selection, request)
            action(selection, request, adapter, captured)
        }
    }

    private fun launchOperation(selection: Long, action: suspend (Long) -> Unit) {
        // Retire the old completion before cancellation can synchronously run its finally block.
        val request = ++operationId
        operation?.cancel()
        operation = scope.launch {
            try { action(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SelectionChanged) { if (isCurrent(selection) && operationId == request) dismiss() }
            catch (problem: Exception) {
                if (isCurrent(selection) && operationId == request) _state.update {
                    it.copy(message = problem.message ?: "This saved selection is unavailable. Reopen it.", error = true)
                }
            }
            finally {
                if (isCurrent(selection) && operationId == request) _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun isCurrent(selection: Long): Boolean = selectionId == selection && _state.value.open &&
        selected?.let(authority::isCurrent) == true

    private suspend fun requireCurrent(selection: Long, request: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(selection) || operationId != request) throw SelectionChanged()
    }

    private fun adapter(store: ChapterTranslationStore) = NativeMemoryPublicationAdapter(filesRoot, store, authority, writer)
    private class SelectionChanged : IllegalStateException()
}
