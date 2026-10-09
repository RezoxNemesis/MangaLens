package com.mangalens.oreznative

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeModelLeaseRegistryTest {
    private val first = ModelFileIdentity("/models/first.gguf", 100, 10, 1, 1000)

    @Test fun modelObservationDoesNotWaitForAnUnfinishedNativeLoad() {
        observeDuringLoad { leases -> assertNull(leases.sharedPath) }
    }

    @Test fun memoryTrimCanObserveOwnersDuringAnUnfinishedNativeLoad() {
        observeDuringLoad { leases -> assertEquals(1, leases.owners().size) }
    }

    private fun observeDuringLoad(observe: (NativeModelLeaseRegistry<Any>) -> Unit) {
        val leases = NativeModelLeaseRegistry<Any>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val loading = threads.submit<Boolean> {
            leases.acquire(Any(), first) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            threads.submit { observe(leases) }.get(500, TimeUnit.MILLISECONDS)
        } finally {
            release.countDown()
            assertTrue(loading.get(2, TimeUnit.SECONDS))
            threads.shutdown()
            assertTrue(threads.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun sameModelSharesOneNativeLoadAndUnloadsAfterLastOwner() {
        val leases = NativeModelLeaseRegistry<Any>()
        val reader = Any()
        val assistant = Any()
        var loads = 0
        var unloads = 0
        assertTrue(leases.acquire(reader, first) { loads++; true })
        assertTrue(leases.acquire(assistant, first) { loads++; true })
        assertEquals(1, loads)
        assertEquals(first.canonicalPath, leases.sharedPath)
        leases.release(reader) { unloads++ }
        assertEquals(0, unloads)
        assertEquals(listOf(assistant), leases.owners())
        leases.release(assistant) { unloads++ }
        assertEquals(1, unloads)
        assertNull(leases.sharedPath)
    }

    @Test fun differentModelCannotBorrowAnExistingModelsSuccessfulLoad() {
        val leases = NativeModelLeaseRegistry<Any>()
        val reader = Any()
        val assistant = Any()
        assertTrue(leases.acquire(reader, first) { true })
        var attemptedLoad = false
        assertFalse(leases.acquire(assistant, first.copy(canonicalPath = "/models/other.gguf")) {
            attemptedLoad = true
            true
        })
        assertFalse(attemptedLoad)
        assertEquals(listOf(reader), leases.owners())
        assertEquals(first.canonicalPath, leases.sharedPath)
    }

    @Test fun replacingTheFileAtTheSamePathCannotReuseTheOldMappedModel() {
        val leases = NativeModelLeaseRegistry<Any>()
        val reader = Any()
        assertTrue(leases.acquire(reader, first) { true })
        assertFalse(leases.acquire(Any(), first.copy(inode = 2000)) { fail("Replaced model must wait for old leases"); true })
        assertEquals(listOf(reader), leases.owners())
    }

    @Test fun changedSizeOrTimestampCannotReuseAFileLease() {
        val leases = NativeModelLeaseRegistry<Any>()
        assertTrue(leases.acquire(Any(), first) { true })
        assertFalse(leases.acquire(Any(), first.copy(size = 101)) { true })
        assertFalse(leases.acquire(Any(), first.copy(modifiedAt = 11)) { true })
        assertFalse(leases.acquire(Any(), first.copy(device = 2)) { true })
        assertEquals(1, leases.owners().size)
    }

    @Test fun refusedReplacementKeepsTheRequestingOwnersOldLease() {
        val leases = NativeModelLeaseRegistry<Any>()
        val owner = Any()
        assertTrue(leases.acquire(owner, first) { true })
        assertFalse(leases.acquire(owner, first.copy(canonicalPath = "/models/new.gguf")) { true })
        assertEquals(listOf(owner), leases.owners())
        assertEquals(first.canonicalPath, leases.sharedPath)
    }

    @Test fun closingAnUnownedOrAlreadyClosedEngineDoesNotUnloadPeers() {
        val leases = NativeModelLeaseRegistry<Any>()
        val owner = Any()
        var unloads = 0
        assertTrue(leases.acquire(owner, first) { true })
        leases.release(Any()) { unloads++ }
        assertEquals(0, unloads)
        leases.release(owner) { unloads++ }
        leases.release(owner) { unloads++ }
        assertEquals(1, unloads)
    }

    @Test fun failedNativeLoadDoesNotPublishAReadyModelOrOwner() {
        val leases = NativeModelLeaseRegistry<Any>()
        val owner = Any()
        assertFalse(leases.acquire(owner, first) { false })
        assertNull(leases.sharedPath)
        assertTrue(leases.owners().isEmpty())
        assertTrue(leases.acquire(owner, first.copy(canonicalPath = "/models/fallback.gguf")) { true })
        assertEquals("/models/fallback.gguf", leases.sharedPath)
    }

    @Test fun reacquiringSameLeaseIsIdempotentAndNewModelLoadsAfterRelease() {
        val leases = NativeModelLeaseRegistry<Any>()
        val owner = Any()
        var loads = 0
        assertTrue(leases.acquire(owner, first) { loads++; true })
        assertTrue(leases.acquire(owner, first) { loads++; true })
        assertEquals(1, loads)
        assertEquals(1, leases.owners().size)
        leases.release(owner) { }
        assertTrue(leases.acquire(owner, first.copy(canonicalPath = "/models/new.gguf")) { loads++; true })
        assertEquals(2, loads)
    }
}
