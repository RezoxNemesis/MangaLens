package com.mangalens.ui.web

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Authored UNRUN native-token/actual actor return controls; no Android filesystem/profile provider is invoked. */
class BrowserProtectionOwnershipTest {
    private class MemoryIo : BrowserWorkspaceIo {
        override fun read(maxBytes: Int): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
    }
    @Test fun navigationSameProfileReplacementAndExplicitRetirementCannotPublishOldHost() {
        val snapshot = BrowserWorkspaceStore(MemoryIo()).snapshot()
        val workspace = MutableStateFlow<BrowserWorkspaceSnapshot?>(snapshot)
        val profile = BrowserProfileOwner(BrowserProfileChoice.Normal)
        val captured = BrowserUploadScope(snapshot.activeTabId, 3, "https://reader.example/chapter?private=1")
        val live = AtomicReference<BrowserUploadScope?>(captured)
        val owner = BrowserProtectionWriteOwner(captured, live, AtomicBoolean(), profile, workspace)
        assertTrue(owner.isCurrent()); var publications = 0
        live.set(captured.copy(navigationEpoch = 4))
        assertThrows(IllegalStateException::class.java) { owner.publish { publications++ } }
        live.set(captured); profile.retire()
        assertThrows(IllegalStateException::class.java) { owner.publish { publications++ } }
        owner.retire(); assertFalse(owner.isCurrent()); assertEquals(0, publications)
    }
    @Test fun privateSettlementWaitsForActualAcceptedIoReturnAndMemoryRetirement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicBoolean()
        val io = object : BrowserWorkspaceIo {
            override fun read(maxBytes: Int): ByteArray? {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return null
            }
            override fun write(bytes: ByteArray) = Unit
        }
        val session = BrowserProtectionSession(io, scope, onRetired = { closed.set(true) })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); session.retirePrivate()
            val settlement = async(start = CoroutineStart.UNDISPATCHED) { session.awaitRetired() }
            assertFalse(settlement.isCompleted); assertFalse(closed.get())
            release.countDown(); withTimeout(2000) { settlement.await() }
            assertTrue(closed.get()); assertFalse(session.state.value.ready)
        } finally { release.countDown(); withContext(NonCancellable) { session.awaitRetired() }; scope.cancel() }
    }
}
