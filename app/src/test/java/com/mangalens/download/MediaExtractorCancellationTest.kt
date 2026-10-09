package com.mangalens.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaExtractorCancellationTest {
    @Test fun cancellationDoesNotWaitForAnExtractorThatIgnoresThreadInterrupts() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extractor = SiteMediaExtractor { _, _ ->
            started.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Uncooperative native wait. */ }
            }
            null
        }
        val job = launch(Dispatchers.Default) {
            MediaLinkResolver(siteExtractor = extractor).resolveCancellable("https://www.youtube.com/watch?v=fixture")
        }
        val cancelledPromptly = try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeoutOrNull(500) { job.cancelAndJoin(); true } ?: false
        } finally {
            release.countDown()
            job.cancel()
        }
        job.join()
        assertTrue("Cancellation waited for uncooperative extraction to finish", cancelledPromptly)
    }

    @Test fun cancelledResolutionInterruptsBlockingNativeProcessWait() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val extractor = SiteMediaExtractor { _, _ ->
            started.countDown()
            try {
                CountDownLatch(1).await()
                null
            } catch (failure: InterruptedException) {
                interrupted.countDown()
                throw failure
            }
        }
        val job = launch(Dispatchers.Default) {
            MediaLinkResolver(siteExtractor = extractor).resolveCancellable("https://www.youtube.com/watch?v=fixture")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue("The worker never received its cancellation interrupt", interrupted.await(2, TimeUnit.SECONDS))
    }
}
