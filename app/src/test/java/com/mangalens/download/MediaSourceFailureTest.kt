package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

class MediaSourceFailureTest {
    @Test fun youtubeVerificationIsPreservedThroughTheExtractorWrapper() {
        val native = IOException("ERROR: [youtube] RzasqVwpLOA: Sign in to confirm you’re not a bot. Use --cookies-from-browser")
        assertEquals(MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED,
            MediaSourceFailure.from(IllegalArgumentException("Installed extractor did not expose media", native)).kind)
    }

    @Test fun instagramAudienceRestrictionIsDistinctFromAuthentication() {
        val failure = IOException("This content isn't available to everyone. It can't be seen by certain audiences.")
        assertEquals(MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED, MediaSourceFailure.from(failure).kind)
        assertTrue(MediaSourceFailure.from(failure).message.contains("audience", true))
    }

    @Test fun actualLoginRequirementIsActionableWithoutInventingDrm() {
        val failure = IOException("ERROR: [Instagram] Login required")
        val result = MediaSourceFailure.from(failure)
        assertEquals(MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED, result.kind)
        assertFalse(result.message.contains("DRM"))
        assertTrue(result.message.contains("source page"))
    }

    @Test fun genericProtectedLoginOnlyBoilerplateDoesNotProveProviderRestriction() {
        val failure = IllegalArgumentException("Protected, private or login-only media may be unavailable.")
        assertEquals(MediaSourceFailureKind.EXTRACTOR_FAILURE, MediaSourceFailure.from(failure).kind)
    }

    @Test fun connectProxyDenialIsANetworkOutcome() {
        val failure = IOException("Unable to download webpage: <urlopen error Tunnel connection failed: 403 Forbidden>")
        assertEquals(MediaSourceFailureKind.NETWORK_PROXY_BLOCKED, MediaSourceFailure.from(failure).kind)
    }

    @Test fun tlsChainFailureNeverSuggestsDisablingVerification() {
        val failure = SSLHandshakeException("https://signed.example/path?signature=secret").apply {
            initCause(CertificateException("Unknown CA private-cookie-value"))
        }
        val result = MediaSourceFailure.from(failure)
        assertEquals(MediaSourceFailureKind.NETWORK_TLS_FAILURE, result.kind)
        assertFalse(result.message.contains("signed.example"))
        assertFalse(result.message.contains("private-cookie"))
        assertFalse(result.message.contains("disable", true))
    }

    @Test fun nativePythonCertificateFailureUsesTheSameTlsCategory() {
        assertEquals(MediaSourceFailureKind.NETWORK_TLS_FAILURE,
            MediaSourceFailure.from(IOException("[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed")).kind)
    }

    @Test fun socketTimeoutHasADistinctRetryMessage() {
        val result = MediaSourceFailure.from(SocketTimeoutException("Read timed out from private-signed-url"))
        assertEquals(MediaSourceFailureKind.TIMEOUT, result.kind)
        assertTrue(result.message.contains("Retry"))
        assertFalse(result.message.contains("private-signed-url"))
    }

    @Test fun nativeUnavailableVideoIsNotAssumedToBeAuthentication() {
        assertEquals(MediaSourceFailureKind.PROVIDER_UNAVAILABLE,
            MediaSourceFailure.from(IOException("Video unavailable. This video has been removed")).kind)
    }

    @Test fun raw403DoesNotProveBotOrAudienceRestriction() {
        assertEquals(MediaSourceFailureKind.SOURCE_ACCESS_DENIED,
            MediaSourceFailure.from(IOException("HTTP Error 403: Forbidden")).kind)
    }

    @Test fun unknownErrorMessageAndHeadersNeverReachTheUser() {
        val result = MediaSourceFailure.from(IOException("https://cdn.example/video?token=secret Cookie: session=private"))
        assertEquals(MediaSourceFailureKind.EXTRACTOR_FAILURE, result.kind)
        assertFalse(result.message.contains("secret"))
        assertFalse(result.message.contains("session="))
        assertTrue(result.message.contains("update", true))
    }

    @Test fun explicitProviderReasonWinsOverALaterGenericClientFailure() {
        val failures = listOf(IOException("Sign in to confirm you're not a bot"), IOException("Requested format is not available"))
        val wrapped = MediaSourceException.fromFailures(failures)
        assertEquals(MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED, wrapped.failure.kind)
        assertSame(failures.last(), wrapped.cause)
    }

    @Test fun typedFailureSurvivesNestedWrappers() {
        val typed = MediaSourceException.fromFailures(listOf(IOException("This content isn’t available to everyone")))
        assertEquals(typed.failure, MediaSourceFailure.from(IOException("Different wrapper", typed)))
    }

    @Test fun cancellationAndInterruptionRemainCancellation() {
        val cancelled = CancellationException("request replaced")
        assertSame(cancelled, assertThrows(CancellationException::class.java) { MediaSourceFailure.from(cancelled) })
        val interrupted = InterruptedException("native wait cancelled")
        assertSame(interrupted, assertThrows(InterruptedException::class.java) { MediaSourceFailure.from(IOException("wrapper", interrupted)) })
    }
}
