package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class ReaderMemoryBubbleChoice(val index: Int, val originalOcr: String, val originalTranslation: String, val editable: Boolean)
internal data class ReaderMemoryEditor(val captured: MemoryBubbleEditor, val bubble: MemoryIndexedBubble, val savedHindiDraft: String?)
internal data class ReaderMemoryUiState(val open: Boolean = false, val pageIndex: Int? = null,
    val choices: List<ReaderMemoryBubbleChoice> = emptyList(), val editor: ReaderMemoryEditor? = null,
    val busy: Boolean = false, val message: String? = null, val error: Boolean = false,
    val personalOverlays: Map<Int, Map<Int, PersonalMangaLettering>> = emptyMap())

/** UI selection is one native receipt. Personal edits never enter the worker's journal or PNG. */
class ReaderMemoryController internal constructor(
    private val filesRoot: File,
    private val scope: CoroutineScope,
    private val storeProvider: suspend () -> ChapterTranslationStore,
    private val writer: MemoryJournalWriter = AtomicMemoryJournalWriter
) {
    private val authority = ReaderMemoryPublicationAuthority()
    private val _state = MutableStateFlow(ReaderMemoryUiState())
    internal val state = _state.asStateFlow()
    @Volatile private var readerVisible = false
    @Volatile private var accepted: ReaderTranslationReceipt? = null
    @Volatile private var visiblePage: Int? = null
    @Volatile private var pageRequest = 0L
    @Volatile private var overlayRequest = 0L
    private var overlayJob: Job? = null

    internal fun bindAccepted(task: ChapterTranslationTask?, receipt: ReaderTranslationReceipt?) {
        if (task == null || receipt == null || task.validationPending || !ReaderTranslationPresentation.matchesTask(receipt, task) ||
            receipt.sources != task.pages.map { ReaderTranslationSource(it.index, it.sourcePath, it.sourceSha256) } ||
            task.status !in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING,
                ChapterTranslationStatus.COMPLETED, ChapterTranslationStatus.PARTIAL)) {
            retire(); return
        }
        val editor = _state.value.editor?.captured?.receipt
        val editorChanged = editor != null && !editorStillMatches(editor, task)
        val replaced = accepted != receipt || editorChanged
        accepted = receipt
        if (replaced) {
            authority.retire(); ++pageRequest; _state.value = ReaderMemoryUiState()
            if (readerVisible) authority.activate(receipt)
        } else if (readerVisible && authority.current() == null) authority.activate(receipt)
        visiblePage?.let(::refreshOverlay)
    }

    internal fun enterReader() {
        readerVisible = true
        accepted?.let { if (authority.current() == null) authority.activate(it) }
        visiblePage?.let(::refreshOverlay)
    }
    internal fun leaveReader() {
        readerVisible = false; ++pageRequest; ++overlayRequest; authority.retire(); overlayJob?.cancel(); _state.value = ReaderMemoryUiState()
    }
    internal fun retire() {
        accepted = null; ++pageRequest; ++overlayRequest; authority.retire(); overlayJob?.cancel(); _state.value = ReaderMemoryUiState()
    }
    internal fun onVisiblePage(pageIndex: Int?) {
        if (visiblePage == pageIndex) return
        visiblePage = pageIndex
        if (_state.value.open && _state.value.pageIndex != pageIndex) dismiss()
        pageIndex?.let(::refreshOverlay)
    }
    internal fun dismiss() { ++pageRequest; _state.update { it.copy(open = false, choices = emptyList(), editor = null, message = null, error = false) } }

    internal suspend fun openPage(pageIndex: Int) {
        val selected = authority.current()
        val request = ++pageRequest
        _state.update { it.copy(open = true, pageIndex = pageIndex, choices = emptyList(), editor = null, busy = true, message = null, error = false) }
        try {
            val task = if (selected == null) null else withContext(Dispatchers.IO) {
                storeProvider().refresh(selected.receipt.taskId, selected.receipt.generation)
            }
            if (selected != authority.current() || pageRequest != request || !_state.value.open || _state.value.pageIndex != pageIndex) return
            val page = task?.takeIf { selected != null && ReaderTranslationPresentation.matchesTask(selected.receipt, it) }
                ?.pages?.singleOrNull { it.index == pageIndex }
            val choices = page?.lettering.orEmpty().mapIndexed { index, text ->
                ReaderMemoryBubbleChoice(index, text.source, text.translated,
                    page?.originalWidth != null && page.originalHeight != null && text.originalSourceBounds != null)
            }
            _state.update { it.copy(choices = choices, busy = false, message = when {
                choices.isEmpty() -> "Translate this page before adding personal corrections."
                choices.none { choice -> choice.editable } -> "This saved translation lacks original image coordinates. Retranslate this page before correcting it."
                else -> null
            }) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { if (selected == authority.current() && pageRequest == request) failure(problem) }
        finally { if (selected == authority.current() && pageRequest == request) _state.update { it.copy(busy = false) } }
    }

    internal suspend fun selectBubble(index: Int) {
        if (_state.value.busy) return
        val selected = authority.current() ?: return
        val pageIndex = _state.value.pageIndex ?: return
        val request = pageRequest
        _state.update { it.copy(busy = true, message = null, error = false) }
        try {
            val editor = withContext(Dispatchers.IO) {
                val store = storeProvider(); val adapter = adapter(store)
                val captured = adapter.openEditor(selected, pageIndex, index) ?: error("Verify or retranslate this page before correcting it.")
                val bubble = adapter.inspect(captured) ?: error("The selected bubble is unavailable.")
                val native = store.prepareMemoryPublication(selected.receipt, pageIndex)?.page?.lettering?.getOrNull(index)
                    ?: error("The saved page changed. Reopen its correction editor.")
                ReaderMemoryEditor(captured, bubble, native.savedHindiDraft)
            }
            if (isPageRequestCurrent(request, selected, pageIndex)) _state.update { it.copy(editor = editor) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { if (isPageRequestCurrent(request, selected, pageIndex)) failure(problem) }
        finally { if (isPageRequestCurrent(request, selected, pageIndex)) _state.update { it.copy(busy = false) } }
    }

    internal suspend fun save(edit: MemoryCorrectionEdit) = mutate { adapter, editor ->
        PersonalMemoryOverlayPolicy.validateEdit(editor.captured.receipt, edit, editor.savedHindiDraft)
        adapter.correct(editor.captured, editor.bubble.editRevision, edit)
    }
    internal suspend fun remove() = mutate { adapter, editor -> adapter.removeCorrection(editor.captured, editor.bubble.editRevision) }
    internal suspend fun rollback(revision: Int) = mutate { adapter, editor -> adapter.rollback(editor.captured, editor.bubble.editRevision, revision) }

    private suspend fun mutate(action: suspend (NativeMemoryPublicationAdapter, ReaderMemoryEditor) -> Any?) {
        val selected = authority.current() ?: return
        val editor = _state.value.editor ?: return
        val pageIndex = _state.value.pageIndex ?: return
        val request = pageRequest
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, error = false) }
        try {
            val saved = withContext(Dispatchers.IO) {
                val adapter = adapter(storeProvider()); action(adapter, editor)
                editor.copy(bubble = adapter.inspect(editor.captured) ?: error("This correction was removed."))
            }
            if (isPageRequestCurrent(request, selected, pageIndex) && _state.value.editor?.captured == editor.captured) {
                _state.update { it.copy(editor = saved, message = "Personal correction saved.", error = false) }
            }
            if (authority.current() == selected && visiblePage == pageIndex) refreshOverlay(pageIndex)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { if (isPageRequestCurrent(request, selected, pageIndex)) failure(problem) }
        finally { if (isPageRequestCurrent(request, selected, pageIndex)) _state.update { it.copy(busy = false) } }
    }

    internal fun refreshVisiblePage() { visiblePage?.let(::refreshOverlay) }
    private fun refreshOverlay(pageIndex: Int) {
        overlayJob?.cancel()
        val request = ++overlayRequest
        val selected = authority.current()
        if (!readerVisible || selected == null) { _state.update { it.copy(personalOverlays = emptyMap()) }; return }
        // Hide earlier facts while actual source/output bytes are checked again.
        _state.update { it.copy(personalOverlays = emptyMap()) }
        overlayJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    adapter(storeProvider()).publishPersonalOverlay(selected, pageIndex) { overlay ->
                        if (overlayRequest == request && visiblePage == pageIndex) _state.update { it.copy(personalOverlays = mapOf(pageIndex to overlay)) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (overlayRequest == request && authority.current() == selected && visiblePage == pageIndex) _state.update { it.copy(personalOverlays = emptyMap()) }
            }
        }
    }
    private fun isPageRequestCurrent(request: Long, selected: ReaderMemoryPresentation, pageIndex: Int) =
        pageRequest == request && authority.current() == selected && _state.value.open && _state.value.pageIndex == pageIndex

    private fun adapter(store: ChapterTranslationStore) = NativeMemoryPublicationAdapter(filesRoot, store, authority, writer)
    private fun failure(problem: Exception) { _state.update { it.copy(message = problem.message ?: "The personal correction could not be saved. Reopen the editor.", error = true) } }
    private fun editorStillMatches(editor: MemoryPublicationReceipt, task: ChapterTranslationTask): Boolean {
        val page = task.pages.singleOrNull { it.index == editor.source.pageIndex } ?: return false
        return editor.taskId == task.id && editor.generation == task.generation && editor.ownerRequestId == task.ownerRequestId &&
            page.sourcePath == editor.source.sourcePath && page.sourceSha256 == editor.source.sourceSha256 &&
            page.cleanedPath == editor.outputPath && page.cleanedSha256 == editor.outputSha256 &&
            page.originalWidth == editor.source.imageWidth && page.originalHeight == editor.source.imageHeight &&
            page.lettering.any { it.source == editor.originalOcr && it.translated == editor.originalTranslation &&
                it.originalSourceBounds?.let { bounds -> MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) } == editor.source.bounds }
    }
    internal fun selectionForTest() = authority.current()
}
