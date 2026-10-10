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

internal class BrowserWorkspaceSession(io: BrowserWorkspaceIo, scope: CoroutineScope,
    val profileKey: String = "normal", private val ephemeral: Boolean = false) {
    @Volatile private var retired = false
    private var actor: kotlinx.coroutines.Job? = null
    fun retireEphemeral() {
        check(ephemeral); retired = true; domTools?.retire(); domTools = null
        commands.close(); actor?.cancel(); mutableState.value = null
    }
    suspend fun awaitRetired() { actor?.join() }

    private data class Command(val action: (BrowserWorkspaceStore) -> BrowserWorkspaceSnapshot, val result: CompletableDeferred<BrowserWorkspaceSnapshot>)
    private val commands = Channel<Command>(64)
    private val mutableState = MutableStateFlow<BrowserWorkspaceSnapshot?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val state: StateFlow<BrowserWorkspaceSnapshot?> = mutableState.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** Foreground ownership only: background/durable workers cannot recreate a DOM host. */
    @Volatile var domTools: com.mangalens.orez.agent.OrezBrowserTools? = null
        private set
    fun bindDomTools(tools: com.mangalens.orez.agent.OrezBrowserTools) { if (retired) tools.retire() else domTools = tools }
    fun unbindDomTools(tools: com.mangalens.orez.agent.OrezBrowserTools) {
        tools.retire()
        if (domTools === tools) domTools = null
    }


    init {
        // The application owns this scope. Accepted commands outlive an individual UI waiter.
        actor = scope.launch {
            var store: BrowserWorkspaceStore? = null
            fun load(): BrowserWorkspaceStore = store ?: BrowserWorkspaceStore(io).also {
                store = it; if (!retired) mutableState.value = it.snapshot()
            }
            try { load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = "Browser session could not be opened. Retry to restore it." }
            for (command in commands) {
                if (retired) { command.result.cancel(); continue }
                try {
                    val current = load()
                    command.action(current)
                    val saved = current.snapshot()
                    if (retired) { command.result.cancel(); continue }
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
        }.also { job ->
            job.invokeOnCompletion {
                if (ephemeral) {
                    mutableState.value = null
                    while (true) { val pending = commands.tryReceive().getOrNull() ?: break; pending.result.cancel() }
                }
            }
        }
    }

    fun submit(action: (BrowserWorkspaceStore) -> BrowserWorkspaceSnapshot): Deferred<BrowserWorkspaceSnapshot> {
        val result = CompletableDeferred<BrowserWorkspaceSnapshot>()
        if (retired) { result.cancel(); return result }
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
