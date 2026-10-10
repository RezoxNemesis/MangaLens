package com.mangalens.ui.video

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN. Actual held blocking read/close, canceled waiter and failed native release. */
class OwnedFragmentSubtitleWorkTest {
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Controlled producer boundary did not arrive", latch.await(5, TimeUnit.SECONDS))
    }
    private suspend fun available(runtime: OwnedFragmentSubtitleWork): Int = withTimeout(5_000) {
        while (true) {
            try { return@withTimeout runtime.run { 17 } }
            catch (busy: IOException) { if (!busy.message.orEmpty().contains("still finishing")) throw busy; delay(10) }
        }
        @Suppress("UNREACHABLE_CODE") 0
    }
    @Test fun cancellingTheWaiterRetainsCapacityThroughActualReadAndSoleClose() = runBlocking {
        val entered = CountDownLatch(1); val readReturn = CountDownLatch(1)
        val closeEntered = CountDownLatch(1); val closeReturn = CountDownLatch(1)
        val closes = AtomicInteger(); val runtime = OwnedFragmentSubtitleWork()
        val waiter = async { runtime.run { owner ->
            owner.own(Closeable { closes.incrementAndGet(); closeEntered.countDown(); check(closeReturn.await(5, TimeUnit.SECONDS)) })
            entered.countDown(); check(readReturn.await(5, TimeUnit.SECONDS)); 9
        } }
        try {
            await(entered); withTimeout(1_000) { waiter.cancelAndJoin() }
            try { runtime.run { fail("Another producer entered during blocked read") }; fail("Busy slot was returned") } catch (_: IOException) { }
            readReturn.countDown(); await(closeEntered)
            try { runtime.run { fail("Another producer entered during blocked close") }; fail("Close returned capacity early") } catch (_: IOException) { }
            closeReturn.countDown(); assertEquals(17, available(runtime)); assertEquals(1, closes.get())
        } finally { readReturn.countDown(); closeReturn.countDown(); waiter.cancel() }
    }
    @Test fun failedSourceCloseRetainsActualHandleAndRefusesAnyNewProducer() = runBlocking {
        val closes = AtomicInteger(); val runtime = OwnedFragmentSubtitleWork()
        try { runtime.run { it.own(Closeable { closes.incrementAndGet(); throw IOException("controlled close") }); 1 }; fail("Unsafe close reported success") }
        catch (_: IOException) { }
        try { runtime.run { fail("Failed resource owner released capacity") }; fail("Unsafe owner was replaced") } catch (_: IOException) { }
        assertEquals(1, closes.get())
    }
    @Test fun failedNativeReleaseDoesNotCloseItsStillHeldSourceDescriptor() = runBlocking {
        val closes = AtomicInteger(); val runtime = OwnedFragmentSubtitleWork()
        try { runtime.run { owner ->
            owner.own(Closeable { closes.incrementAndGet() }); owner.retainNativeConsumer(Any()); 1
        }; fail("Unproven native consumer reported success") } catch (_: IOException) { }
        assertEquals("Source FD must stay held for the unproven consumer", 0, closes.get())
        try { runtime.run { fail("A new producer entered after failed native release") }; fail("Native owner was replaced") } catch (_: IOException) { }
    }
    @Test fun cancellationBeforeProducerEntryDoesNotStartWorkOrLeakItsReservation() = runBlocking {
        val executor = Executors.newSingleThreadExecutor(); val dispatcher = executor.asCoroutineDispatcher()
        val gate = CountDownLatch(1); val occupied = CountDownLatch(1); val entries = AtomicInteger()
        executor.execute { occupied.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val runtime = OwnedFragmentSubtitleWork(dispatcher)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { runtime.run { entries.incrementAndGet() } }
        try {
            await(occupied); withTimeout(1_000) { waiter.cancelAndJoin() }
            gate.countDown(); assertEquals(17, available(runtime)); assertEquals(0, entries.get())
        } finally { gate.countDown(); waiter.cancel(); dispatcher.close(); executor.shutdownNow() }
    }
}
