package com.mangalens.core.compute

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic registration→current callback→grant boundary; no native effect is invoked. */
class NativeAdmissionObservedContentionTest {
    @Test fun releasingActiveOwnerDuringHeldCurrentCallbackCannotEraseObservedContention() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        val inCurrent = CountDownLatch(1); val releaseCurrent = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val waiting = async(Dispatchers.IO) {
            val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) {
                if (first.compareAndSet(true, false)) {
                    inCurrent.countDown(); check(releaseCurrent.await(2, TimeUnit.SECONDS))
                }
                true
            }!!
            try { lease.waited } finally { lease.close() }
        }
        try {
            assertTrue("The ticket must be registered before holding its actual current callback", inCurrent.await(2, TimeUnit.SECONDS))
            holder.close()
            releaseCurrent.countDown()
            assertTrue("The first successful grant must retain contention observed at registration", withTimeout(2_000) { waiting.await() })
        } finally { holder.close(); releaseCurrent.countDown(); waiting.cancelAndJoin() }
    }

    @Test fun anActualLateLiveLeaseDuringAnIdleCurrentCallbackCannotEraseRegisteredContention() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val inCurrent = CountDownLatch(1); val releaseCurrent = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val waiting = async(Dispatchers.IO) {
            val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) {
                if (first.compareAndSet(true, false)) {
                    inCurrent.countDown(); check(releaseCurrent.await(2, TimeUnit.SECONDS))
                }
                true
            }!!
            try { lease.waited } finally { lease.close() }
        }
        try {
            assertTrue("The idle BACKGROUND ticket must actually hold its first predicate", inCurrent.await(2, TimeUnit.SECONDS))
            val live = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            live.close()
            releaseCurrent.countDown()
            assertTrue("The registered ticket must retain a real LIVE lease granted and closed during its predicate",
                withTimeout(2_000) { waiting.await() })
        } finally { releaseCurrent.countDown(); waiting.cancelAndJoin() }
    }

    @Test fun idleRegistrationRemainsUnwaitedWithoutIntroducingAResourceDelay() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        repeat(2) {
            val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
            try { assertFalse("An unchanged idle call must keep its verified-source fast path", lease.waited) }
            finally { lease.close() }
        }
    }
}
