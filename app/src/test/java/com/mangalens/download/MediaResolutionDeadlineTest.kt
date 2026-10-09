package com.mangalens.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaResolutionDeadlineTest {
    @Test fun blockedCleanupCannotDelayCancellationCaller() = runBlocking {
        val started = CountDownLatch(1)
        val workRelease = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            MediaResolutionRunner.run(5_000L) { session ->
                session.onCancel { waitIgnoringInterrupts(cleanupRelease) }.use {
                    started.countDown()
                    waitIgnoringInterrupts(workRelease)
                }
            }
        }
        try {
            assertTrue(started.await(5L, TimeUnit.SECONDS))
            Thread({ job.cancel(); cancelReturned.countDown() }, "qa-media-cancel").apply { isDaemon = true }.start()
            assertTrue("Cancel waited for blocked native cleanup", cancelReturned.await(500L, TimeUnit.MILLISECONDS))
        } finally {
            cleanupRelease.countDown()
            workRelease.countDown()
        }
        withTimeout(2_000L) { job.join() }
    }

    @Test fun blockedProcessCleanupDoesNotStarveOtherDeadlines() = runBlocking {
        val cleanupStarted = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val workRelease = CountDownLatch(1)
        val session = MediaResolutionSession(5_000L)
        val guard = MediaProcessGuard(session, "blocked-process", 1L) {
            cleanupStarted.countDown()
            waitIgnoringInterrupts(cleanupRelease)
            true
        }
        try {
            assertTrue(cleanupStarted.await(2L, TimeUnit.SECONDS))
            val resolution = async(Dispatchers.Default) {
                runCatching { MediaResolutionRunner.run(150L) { waitIgnoringInterrupts(workRelease) } }
            }
            val result = withTimeoutOrNull(800L) { resolution.await() }
            assertNotNull("A blocked process cleanup starved an unrelated deadline", result)
            assertTrue(result!!.exceptionOrNull() is MediaResolutionTimeoutException)
        } finally {
            cleanupRelease.countDown()
            workRelease.countDown()
            guard.close()
        }
    }

    @Test fun deadlineReturnsWithoutWaitingForAnUncooperativeNativeCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = async(Dispatchers.Default) {
            runCatching {
                MediaResolutionRunner.run(300L) {
                    started.countDown()
                    waitIgnoringInterrupts(release)
                    "late output"
                }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val failure = withTimeout(2_000L) { result.await() }.exceptionOrNull()
            assertTrue("Expected the deadline before releasing native work: $failure", failure is MediaResolutionTimeoutException)
        } finally { release.countDown() }
    }

    @Test fun extractorAndHtmlFallbackShareOneDeadline() = runBlocking {
        val server = MockWebServer().apply { start() }
        val extractor = SiteMediaExtractor { _, _ -> Thread.sleep(200L); null }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val start = System.nanoTime()
        try {
            val result = runCatching {
                MediaLinkResolver(siteExtractor = extractor, timeoutMs = 500L)
                    .resolveCancellable(server.url("/watch").toString())
            }
            assertTrue(result.exceptionOrNull() is MediaResolutionTimeoutException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000L)
            assertNotNull("HTML fallback was never attempted", server.takeRequest(1L, TimeUnit.SECONDS))
        } finally { server.shutdown() }
    }

    @Test fun cancellingOldRequestAndItsLateFinalizerCannotCancelReplacement() = runBlocking {
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldExited = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementRelease = CountDownLatch(1)
        val replacementCancelled = AtomicBoolean(false)
        val old = launch(Dispatchers.Default) {
            MediaResolutionRunner.run(5_000L) { session ->
                try {
                    session.onCancel { /* Own old resources only. */ }.use {
                        oldStarted.countDown()
                        waitIgnoringInterrupts(oldRelease)
                    }
                } finally { oldExited.countDown() }
            }
        }
        try {
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            withTimeout(2_000L) { old.cancelAndJoin() }
            val replacement = async(Dispatchers.Default) {
                MediaResolutionRunner.run(5_000L) { session ->
                    session.onCancel { replacementCancelled.set(true) }.use {
                        replacementStarted.countDown()
                        replacementRelease.await()
                        "new output"
                    }
                }
            }
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            oldRelease.countDown()
            assertTrue(oldExited.await(2, TimeUnit.SECONDS))
            assertFalse(replacementCancelled.get())
            replacementRelease.countDown()
            assertEquals("new output", withTimeout(2_000L) { replacement.await() })
            assertFalse(replacementCancelled.get())
        } finally {
            oldRelease.countDown()
            replacementRelease.countDown()
        }
    }

    @Test fun processRegisteredAfterCancellationIsStillDestroyed() {
        val session = MediaResolutionSession(5_000L)
        val processRegistered = AtomicBoolean(false)
        val processStopped = CountDownLatch(1)
        session.cancel()
        MediaProcessGuard(session, "old-process", 5_000L) { id ->
            assertEquals("old-process", id)
            processRegistered.get().also { if (it) processStopped.countDown() }
        }.use {
            // Native ProcessBuilder.start() can finish after the initial cancellation callback.
            processRegistered.set(true)
            assertTrue(processStopped.await(2L, TimeUnit.SECONDS))
        }
    }

    @Test fun closingOldProcessGuardNeverStopsANewProcess() {
        val stopped = mutableListOf<String>()
        val old = MediaResolutionSession(5_000L)
        val replacement = MediaResolutionSession(5_000L)
        val oldGuard = MediaProcessGuard(old, "old-process", 5_000L) { synchronized(stopped) { stopped += it }; true }
        val replacementGuard = MediaProcessGuard(replacement, "new-process", 5_000L) { synchronized(stopped) { stopped += it }; true }
        try {
            old.cancel()
            oldGuard.close()
            assertFalse(synchronized(stopped) { "new-process" in stopped })
            replacement.checkActive()
        } finally { oldGuard.close(); replacementGuard.close() }
    }

    @Test fun cancelledSessionCleansUpLaterResources() {
        val session = MediaResolutionSession(5_000L)
        val cleaned = CountDownLatch(1)
        session.cancel()
        session.onCancel { cleaned.countDown() }.close()
        assertTrue(cleaned.await(2L, TimeUnit.SECONDS))
    }

    private fun waitIgnoringInterrupts(release: CountDownLatch) {
        while (release.count > 0L) {
            try { release.await() } catch (_: InterruptedException) { /* Simulate blocking native IO. */ }
        }
    }
}
