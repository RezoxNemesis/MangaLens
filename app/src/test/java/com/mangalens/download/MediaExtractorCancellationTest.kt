package com.mangalens.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaExtractorCancellationTest {
    @Test fun cancelledResolutionInterruptsBlockingNativeProcessWait() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val extractor = SiteMediaExtractor { _, _ ->
            started.countDown()
            try {
                CountDownLatch(1).await()
                null
            } catch (failure: InterruptedException) {
                interrupted.set(true)
                throw failure
            }
        }
        val job = launch(Dispatchers.Default) {
            MediaLinkResolver(siteExtractor = extractor).resolveCancellable("https://www.youtube.com/watch?v=fixture")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(interrupted.get())
    }
}
