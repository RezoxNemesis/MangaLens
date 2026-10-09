package com.mangalens.download

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import com.yausername.youtubedl_android.YoutubeDL

class MediaProcessAttemptDeadlineTest {
    @Test fun actualPinnedWrapperCancelledExceptionAtTheLocalDeadlineKeepsTimeoutCategory() {
        val session = MediaResolutionSession(5_000)
        val stopped = CountDownLatch(1)
        MediaProcessGuard(session, "actual-wrapper-type", 40) { stopped.countDown(); true }.use { guard ->
            val native = YoutubeDL.CanceledException()
            val failure = runCatching {
                guard.run {
                    check(stopped.await(2, TimeUnit.SECONDS))
                    throw native
                }
            }.exceptionOrNull()!!
            assertEquals(MediaSourceFailureKind.TIMEOUT, MediaSourceFailure.from(failure).kind)
            assertTrue(failure.suppressed.any { it === native })
        }
    }

    @Test fun attemptTerminationBeforeTheOuterDeadlineIsReportedAsTimeout() {
        val session = MediaResolutionSession(5_000)
        val stopped = CountDownLatch(1)
        MediaProcessGuard(session, "owned-local-attempt", 40) { stopped.countDown(); true }.use { guard ->
            val native = IOException("process terminated")
            val failure = runCatching {
                guard.run {
                    check(stopped.await(2, TimeUnit.SECONDS))
                    throw native
                }
            }.exceptionOrNull()!!
            session.checkActive()
            assertTrue(failure is MediaResolutionTimeoutException)
            assertEquals(MediaSourceFailureKind.TIMEOUT, MediaSourceFailure.from(failure).kind)
            assertTrue(failure.suppressed.any { it === native })
        }
    }

    @Test fun expiredAttemptCannotAcceptLateOutputOrEnterANewNativeCall() {
        val session = MediaResolutionSession(5_000)
        val stopped = CountDownLatch(1)
        MediaProcessGuard(session, "owned-local-attempt", 40) { stopped.countDown(); true }.use { guard ->
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            var entered = false
            assertThrows(MediaResolutionTimeoutException::class.java) { guard.run { entered = true; "late" } }
            assertFalse(entered)
        }
    }

    @Test fun providerFailureBeforeTheAttemptDeadlineKeepsItsSpecificReason() {
        val session = MediaResolutionSession(5_000)
        MediaProcessGuard(session, "owned-local-attempt", 5_000) { true }.use { guard ->
            val provider = IOException("This content isn't available to everyone")
            val failure = runCatching { guard.run<String> { throw provider } }.exceptionOrNull()
            assertSame(provider, failure)
            assertEquals(MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED, MediaSourceFailure.from(failure!!).kind)
        }
    }

    @Test fun cancellationOfThisSessionNeverBecomesAnAttemptTimeout() {
        val session = MediaResolutionSession(5_000)
        MediaProcessGuard(session, "owned-local-attempt", 5_000) { true }.use { guard ->
            val cancellation = InterruptedException("owned request cancelled")
            session.cancel(cancellation)
            assertSame(cancellation, assertThrows(InterruptedException::class.java) { guard.run { "unused" } })
        }
    }
}
