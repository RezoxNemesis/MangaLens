package com.mangalens.download

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MediaSourceFailureBoundaryTest {
    @Test fun genericTypedWrapperCannotHideItsNativeTimeoutCause() {
        val generic = MediaSourceException.fromFailures(emptyList())
        val wrapped = MediaSourceException(generic.failure, SocketTimeoutException("secret"))
        assertEquals(MediaSourceFailureKind.TIMEOUT, MediaSourceFailure.from(wrapped).kind)
    }

    @Test fun suppressedProviderFailureSurvivesLaterCompatibilityError() {
        val unknown = IOException("Requested format is unavailable").apply {
            addSuppressed(IOException("This content isn't available to everyone"))
        }
        assertEquals(MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED, MediaSourceFailure.from(unknown).kind)
    }

    @Test fun aggregateRetainsEarlierNativeCauseForSafeEvidenceWhileKeepingTheLastCause() {
        val provider = IOException("Sign in to confirm you're not a bot")
        val fallback = IOException("requested format unavailable")
        val failure = MediaSourceException.fromFailures(listOf(provider, fallback))
        assertSame(fallback, failure.cause)
        assertTrue(failure.suppressed.any { it === provider })
        assertEquals(MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED, MediaSourceFailure.from(failure).kind)
    }

    @Test fun wrappedSuppressedCancellationRemainsCancellation() {
        val cancellation = CancellationException("private request cancelled")
        val root = IOException("wrapper").apply { addSuppressed(cancellation) }
        assertSame(cancellation, assertThrows(CancellationException::class.java) { MediaSourceFailure.from(root) })
    }

    @Test fun typedReasonUsesFixedTextEvenIfItsConstructorContainsRawDiagnostics() {
        val unsafe = MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.NETWORK_TLS_FAILURE,
            "Cookie: private https://signed.example/secret"), null)
        val safe = MediaSourceFailure.from(unsafe)
        assertEquals(MediaSourceFailureKind.NETWORK_TLS_FAILURE, safe.kind)
        assertFalse(safe.message.contains("private"))
        assertFalse(safe.message.contains("signed.example"))
    }
}
