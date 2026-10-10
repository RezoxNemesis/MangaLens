package com.mangalens.ui.web

import android.content.Context
import android.system.Os
import com.mangalens.core.adblock.AdBlockMode
import com.mangalens.core.adblock.AdBlockSite
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal data class BrowserProtectionState(val snapshot: BrowserProtectionSnapshot = BrowserProtectionSnapshot(), val ready: Boolean = false, val error: String? = null)

/** Accepted writes belong to application IO; cancelling the UI waiter never abandons an open handle. */
internal class BrowserProtectionSession(private val io: BrowserWorkspaceIo, scope: CoroutineScope,
    private val writeGate: ((BrowserProtectionWriteOwner?) -> Unit) = {}, private val onRetired: () -> Unit = {},
    private val unavailable: String? = null) {
    private data class Change(val site: AdBlockSite, val mode: AdBlockMode, val owner: BrowserProtectionWriteOwner, val result: CompletableDeferred<BrowserProtectionSnapshot>)
    private val commands = Channel<Change>(4)
    @Volatile private var retired = false
    private var actor: Job? = null
    private val values = MutableStateFlow(BrowserProtectionState())
    val state = values.asStateFlow()
    init { if (unavailable != null) values.value = BrowserProtectionState(ready = true, error = unavailable) else { actor = scope.launch {
        var store: BrowserProtectionStore? = null
        fun load() = store ?: BrowserProtectionStore(io).also { store = it; if (!retired) values.value = BrowserProtectionState(it.snapshot(), true) }
        try {
            try { load() } catch (_: Exception) { if (!retired) values.value = BrowserProtectionState(ready = true, error = "Site protection settings could not be read. Standard applies until storage is available.") }
            for (change in commands) {
                if (retired) { change.result.cancel(); continue }
                try {
                    val acceptedOwner = change.owner
                    val current = { !retired && acceptedOwner.isCurrent() }
                    writeGate(acceptedOwner)
                    val saved = load().set(change.site, change.mode, current)
                    if (!retired) values.value = BrowserProtectionState(saved, true)
                    change.result.complete(saved)
                } catch (failure: Exception) {
                    if (!retired) values.value = values.value.copy(ready = true, error = "The site protection choice could not be saved and verified. Reopen the page and retry.")
                    change.result.completeExceptionally(failure)
                } finally { writeGate(null) }
            }
        } finally {
            commands.close()
            while (true) { val pending = commands.tryReceive().getOrNull() ?: break; pending.result.cancel() }
            if (retired) { values.value = BrowserProtectionState(); onRetired() }
        }
    } } }
    suspend fun awaitRetired() { actor?.join() }
    fun modeFor(url: String) = state.value.snapshot.modeFor(url)
    fun set(site: AdBlockSite, mode: AdBlockMode, owner: BrowserProtectionWriteOwner): Deferred<BrowserProtectionSnapshot> {
        val result = CompletableDeferred<BrowserProtectionSnapshot>()
        if (retired || unavailable != null || commands.trySend(Change(site, mode, owner, result)).isFailure)
            result.completeExceptionally(IllegalStateException("Site protection is busy or no longer active. Retry."))
        return result
    }
    fun retirePrivate() { retired = true; commands.close(); values.value = BrowserProtectionState() }
}

internal object BrowserProtectionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistent = mutableMapOf<String, BrowserProtectionSession>()
    private val privateSessions = mutableMapOf<String, BrowserProtectionSession>()
    fun retirePrivate(choice: BrowserProfileChoice): BrowserProtectionSession? {
        if (!choice.ephemeral) return null
        return synchronized(this) { privateSessions.remove(choice.key) }?.also { it.retirePrivate() }
    }
    fun session(context: Context, choice: BrowserProfileChoice): BrowserProtectionSession {
        choice.validate()
        if (choice.ephemeral) return synchronized(this) { privateSessions[choice.key] ?: run {
            check(privateSessions.size < 2) { "Private protection capacity reached. Close the old Private session." }
            val io = PrivateBrowserWorkspaceIo()
            BrowserProtectionSession(io, scope, onRetired = io::retire).also { privateSessions[choice.key] = it }
        } }
        val app = context.applicationContext; val key = app.filesDir.absolutePath + ":" + choice.key
        return synchronized(this) { persistent[key] ?: run {
            if (persistent.size >= BrowserProfilePolicy.MAX_BINDINGS) return@synchronized BrowserProtectionSession(
                PrivateBrowserWorkspaceIo(), scope, unavailable = "Protection profile capacity reached. Restart the app to open this profile's settings; Standard still applies.")
            val guard = AtomicReference<BrowserProtectionWriteOwner?>(null)
            val io = BrowserProfileJournal(File(app.filesDir, BrowserProfilePolicy.workspaceDirectory(choice) + "/protection.rules"),
                promote = { from, to -> requireNotNull(guard.get()).publish { Os.rename(from.absolutePath, to.absolutePath) } })
            BrowserProtectionSession(io, scope, writeGate = guard::set).also { persistent[key] = it }
        } }
    }
}
