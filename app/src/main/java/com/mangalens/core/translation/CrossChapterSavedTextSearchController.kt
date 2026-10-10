package com.mangalens.core.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class CrossChapterSavedTextSearchState(val query: String = "", val busy: Boolean = false,
    val snapshot: CrossChapterSavedTextSnapshot? = null, val error: String? = null)

/** Main-confined explicit request lifecycle; editing text never reads/indexes/hashes saved chapters. */
internal class CrossChapterSavedTextSearchController(private val scope: CoroutineScope,
    private val searchSaved: suspend (String, Boolean) -> CrossChapterSavedTextSnapshot?,
    private val prepareOpen: suspend (CrossChapterSavedTextSnapshot, String) -> PreparedSavedTextOpen?) {
    private val mutable = MutableStateFlow(CrossChapterSavedTextSearchState())
    val state: StateFlow<CrossChapterSavedTextSearchState> = mutable
    private var request = 0L
    private var active = true
    private var closed = false
    private var job: Job? = null

    fun setQuery(query: String) {
        if (query == mutable.value.query || closed) return
        retire(); mutable.value = CrossChapterSavedTextSearchState(query)
    }
    fun setActive(value: Boolean) {
        if (closed || value == active) return
        active = value; retire()
        mutable.value = mutable.value.copy(busy = false, snapshot = null, error = null)
    }
    fun close() {
        if (closed) return
        closed = true; active = false; retire()
        mutable.value = mutable.value.copy(busy = false, snapshot = null, error = null)
    }

    fun search(refresh: Boolean = false) {
        if (closed || !active || mutable.value.busy) return
        val query = mutable.value.query.trim()
        if (query.isBlank() || query.length > 256 || '\u0000' in query) {
            mutable.value = mutable.value.copy(snapshot = null, error = "Enter between 1 and 256 characters.")
            return
        }
        val captured = ++request
        mutable.value = mutable.value.copy(busy = true, snapshot = null, error = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val found = searchSaved(query, refresh)
                if (!current(captured)) return@launch
                val accepted = found != null && found.query == query && found.tryDeliver {
                    if (!current(captured)) false else {
                        mutable.value = mutable.value.copy(snapshot = it, error = null)
                        true
                    }
                }
                if (!accepted && current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = STALE_MESSAGE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(captured)) mutable.value = mutable.value.copy(snapshot = null,
                error = "Saved text could not be searched. Please refresh or retry.") }
            finally { if (current(captured)) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun open(rowId: String, accept: (PreparedSavedTextOpen) -> Boolean) {
        if (closed || !active || mutable.value.busy) return
        val found = mutable.value.snapshot ?: return
        if (found.rows.none { it.id == rowId } && found.nativeRows.none { it.id == rowId }) return
        val captured = ++request
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val pending = prepareOpen(found, rowId)
                if (!current(captured) || mutable.value.snapshot !== found) return@launch
                if (pending == null || !accept(pending)) {
                    if (current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = STALE_MESSAGE)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(captured)) mutable.value = mutable.value.copy(snapshot = null,
                error = "The saved page could not be opened. Please search again.") }
            finally { if (current(captured)) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private fun current(captured: Long) = !closed && active && request == captured
    private fun retire() { request++; job?.cancel(); job = null }
    companion object { const val STALE_MESSAGE = "Saved text changed or needs validation. Reopen its Reader, then search again." }
}
