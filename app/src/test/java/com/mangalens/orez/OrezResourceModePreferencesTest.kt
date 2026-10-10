package com.mangalens.orez

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled IO suspension only; no Android preferences, service, model, sensor or JNI execution. */
class OrezResourceModePreferencesTest {
    private suspend fun <T> owned(action: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try { withTimeout(3_000) { action(scope) } } finally { scope.cancel() }
    }
    @Test fun coldRequestWaitsForSavedModeBeforeAnyCandidateWork() = runBlocking { owned { scope ->
        val read = CompletableDeferred<String?>()
        val prefs = OrezResourceModePreferences(scope, { read.await() }, { true })
        val request = async { prefs.capture() }
        yield(); assertFalse(request.isCompleted); assertFalse(prefs.state.value.loaded)
        read.complete("FAST")
        assertEquals(OrezResourceMode.FAST, request.await().mode)
    } }
    @Test fun coldControlsCannotOverwritePreferenceBeforeReadCompletes() = runBlocking { owned { scope ->
        val read = CompletableDeferred<String?>(); val writes = CopyOnWriteArrayList<String>()
        val prefs = OrezResourceModePreferences(scope, { read.await() }, { writes += it; true })
        assertFalse(prefs.select(OrezResourceMode.FAST)); read.complete("MAXIMUM")
        assertEquals(OrezResourceMode.MAXIMUM, prefs.capture().mode); assertTrue(writes.isEmpty())
    } }
    @Test fun acceptedModeStaysFixedDuringVerificationWaitAndLaterFallbacks() = runBlocking { owned { scope ->
        val prefs = OrezResourceModePreferences(scope, { "FAST" }, { true })
        val captured = prefs.capture()
        assertTrue(prefs.select(OrezResourceMode.MAXIMUM))
        prefs.state.first { !it.saving }
        withContext(captured) {
            val planning = prefs.capture(OrezModelTask.TOOL_PLANNING)
            assertEquals(OrezResourceMode.FAST, planning.mode)
            withContext(planning) {
                assertEquals(OrezResourceMode.FAST, prefs.capture(OrezModelTask.EVIDENCE_SYNTHESIS).mode)
            }
        }
        assertEquals(OrezResourceMode.MAXIMUM, prefs.capture().mode)
    } }
    @Test fun writesCannotReorderBecauseSecondChoiceIsNotAcceptedUntilFirstSettles() = runBlocking { owned { scope ->
        val firstWrite = CompletableDeferred<Boolean>(); val writes = CopyOnWriteArrayList<String>()
        val prefs = OrezResourceModePreferences(scope, { "BALANCED" }, { writes += it; firstWrite.await() })
        prefs.capture(); assertTrue(prefs.select(OrezResourceMode.FAST))
        assertFalse(prefs.select(OrezResourceMode.MAXIMUM)); assertEquals(OrezResourceMode.FAST, prefs.capture().mode)
        firstWrite.complete(true); prefs.state.first { !it.saving }
        assertEquals(listOf("FAST"), writes.toList())
    } }
    @Test fun failedWriteKeepsSessionModeAndVisibleRetryThenCanSaveSameChoice() = runBlocking { owned { scope ->
        var accept = false
        val prefs = OrezResourceModePreferences(scope, { "BALANCED" }, { accept })
        prefs.capture(); assertTrue(prefs.select(OrezResourceMode.FAST)); prefs.state.first { !it.saving }
        assertEquals(OrezResourceMode.FAST, prefs.capture().mode); assertNotNull(prefs.state.value.error)
        accept = true; assertTrue(prefs.select(OrezResourceMode.FAST)); prefs.state.first { !it.saving }
        assertNull(prefs.state.value.error)
    } }
    @Test fun readFailureFallsBackExplicitlyWithoutBlockingNewChat() = runBlocking { owned { scope ->
        val prefs = OrezResourceModePreferences(scope, { throw java.io.IOException("fixture") }, { true })
        assertEquals(OrezResourceMode.BALANCED, prefs.capture().mode); assertNotNull(prefs.state.value.error)
    } }
    @Test fun uiCallerCancellationDoesNotCancelAcceptedAppOwnedPersistence() = runBlocking { owned { scope ->
        val release = CompletableDeferred<Boolean>()
        val prefs = OrezResourceModePreferences(scope, { "BALANCED" }, { release.await() })
        prefs.capture()
        val ui = launch { assertTrue(prefs.select(OrezResourceMode.FAST)); awaitCancellation() }
        yield(); ui.cancelAndJoin(); assertTrue(prefs.state.value.saving)
        release.complete(true); prefs.state.first { !it.saving }; assertEquals(OrezResourceMode.FAST, prefs.capture().mode)
    } }
    @Test fun invalidPersistedValueUsesBalancedRatherThanEnablingMaximum() = runBlocking { owned { scope ->
        val prefs = OrezResourceModePreferences(scope, { "MAX" }, { true })
        assertEquals(OrezResourceMode.BALANCED, prefs.capture().mode)
    } }
}
