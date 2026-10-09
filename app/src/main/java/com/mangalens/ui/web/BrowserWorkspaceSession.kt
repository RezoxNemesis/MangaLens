package com.mangalens.ui.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class BrowserWorkspaceSession(io: BrowserWorkspaceIo, scope: CoroutineScope) {
    private data class Command(val action: (BrowserWorkspaceStore) -> BrowserWorkspaceSnapshot, val result: CompletableDeferred<BrowserWorkspaceSnapshot>)
    private val commands = Channel<Command>(64)
    private val mutableState = MutableStateFlow<BrowserWorkspaceSnapshot?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val state: StateFlow<BrowserWorkspaceSnapshot?> = mutableState.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        // The application owns this scope. Accepted commands outlive an individual UI waiter.
        scope.launch {
            var store: BrowserWorkspaceStore? = null
            fun load(): BrowserWorkspaceStore = store ?: BrowserWorkspaceStore(io).also {
                store = it; mutableState.value = it.snapshot()
            }
            try { load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = "Browser session could not be opened. Retry to restore it." }
            for (command in commands) {
                try {
                    val current = load()
                    command.action(current)
                    val saved = current.snapshot()
                    mutableState.value = saved
                    mutableError.value = null
                    command.result.complete(saved)
                } catch (cancelled: CancellationException) {
                    command.result.completeExceptionally(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    mutableError.value = if (failure is IllegalArgumentException || failure is IllegalStateException)
                        failure.message ?: "Browser change could not be saved. Retry."
                        else "Browser change could not be saved. Check available storage and retry."
                    command.result.completeExceptionally(failure)
                }
            }
        }
    }

    fun submit(action: (BrowserWorkspaceStore) -> BrowserWorkspaceSnapshot): Deferred<BrowserWorkspaceSnapshot> {
        val result = CompletableDeferred<BrowserWorkspaceSnapshot>()
        if (commands.trySend(Command(action, result)).isFailure) {
            val failure = IllegalStateException("Browser is busy saving changes. Please retry.")
            mutableError.value = failure.message
            result.completeExceptionally(failure)
        }
        return result
    }

    fun recordNavigation(id: String, url: String, replace: Boolean = false): Deferred<BrowserWorkspaceSnapshot> = submit { store ->
        if (store.snapshot().activeTabId == id) store.navigate(id, url, replace) else store.snapshot()
    }

    fun moveHistory(captured: BrowserTab, delta: Int): Deferred<BrowserWorkspaceSnapshot> = submit { store ->
        val current = store.snapshot().activeTab
        check(current.id == captured.id && current.entries == captured.entries && current.position == captured.position) {
            "Browser history changed before navigation. Retry."
        }
        store.move(captured.id, delta)
    }
}
