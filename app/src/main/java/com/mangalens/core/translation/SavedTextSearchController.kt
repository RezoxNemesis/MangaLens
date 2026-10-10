package com.mangalens.core.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class SavedTextSearchState(val query: String = "", val busy: Boolean = false,
    val snapshot: SavedTextSearchSnapshot? = null, val error: String? = null)

/** Main-confined request state. Every post-IO publication also consumes real native/memory authority. */
internal class SavedTextSearchController(private val chapterId: String, private val scope: CoroutineScope,
    private val search: suspend (String, String) -> SavedTextSearchSnapshot?,
    private val prepareOpen: suspend (SavedTextSearchSnapshot, String) -> PreparedSavedTextOpen?) {
    private val mutable = MutableStateFlow(SavedTextSearchState())
    val state: StateFlow<SavedTextSearchState> = mutable
    private var request = 0L
    private var active = true
    private var closed = false
    private var job: Job? = null

    fun setQuery(query: String) {
        if (query == mutable.value.query || closed) return
        retire()
        mutable.value = SavedTextSearchState(query)
    }

    fun setActive(value: Boolean) {
        if (closed || value == active) return
        active = value
        retire()
        mutable.value = mutable.value.copy(busy = false, snapshot = null, error = null)
    }

    fun close() {
        if (closed) return
        closed = true; active = false; retire()
        mutable.value = mutable.value.copy(busy = false, snapshot = null, error = null)
    }

    fun search() {
        if (closed || !active || mutable.value.busy) return
        val query = mutable.value.query.trim()
        if (query.isBlank() || query.length > 256 || '\u0000' in query || !chapterId.matches(Regex("[a-f0-9]{32}"))) {
            mutable.value = mutable.value.copy(snapshot = null, error = "Enter between 1 and 256 characters.")
            return
        }
        val captured = ++request
        mutable.value = mutable.value.copy(busy = true, snapshot = null, error = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val found = search(chapterId, query)
                if (!current(captured)) return@launch
                val accepted = found != null && found.chapterId == chapterId && found.query == query && found.tryDeliver {
                    if (!current(captured)) false else {
                        // StateFlow carries the real proof only after final Main acceptance.
                        mutable.value = mutable.value.copy(snapshot = it, error = null)
                        true
                    }
                }
                if (!accepted && current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = STALE_MESSAGE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = "Saved text could not be read. Please retry.") }
            finally { if (current(captured)) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun open(rowId: String, accept: (PreparedSavedTextOpen) -> Boolean) {
        if (closed || !active || mutable.value.busy) return
        val found = mutable.value.snapshot ?: return
        if (found.rows.none { it.id == rowId }) return
        val captured = ++request
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val pending = prepareOpen(found, rowId)
                if (!current(captured) || mutable.value.snapshot !== found) return@launch
                // The synchronous VM acceptance consumes pending's final native/memory lease.
                // No proof event is queued for a later collector to promote.
                if (pending == null || !accept(pending)) {
                    if (current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = STALE_MESSAGE)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(captured)) mutable.value = mutable.value.copy(snapshot = null, error = "The saved page could not be opened. Please search again.") }
            finally { if (current(captured)) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private fun current(captured: Long) = !closed && active && request == captured
    private fun retire() { request++; job?.cancel(); job = null }

    companion object { const val STALE_MESSAGE = "Saved text changed or needs validation. Reopen the Reader, then search again." }
}
