package com.mangalens.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** AUTHORED UNRUN: typed transport refusal retains fixed actionable text at the real error boundary. */
class UnsupportedMediaTransportFailureTest {
    @Test fun nativeBoundaryPreservesTransportKindWithFixedActionableText() {
        val unsafe = "secret-cookie https://private.invalid/token?secret=1"
        val result = MediaSourceFailure.from(IOException("wrapper", MediaSourceException(
            MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, unsafe), null)))
        assertEquals(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, result.kind)
        assertTrue(result.message.contains("download transport")); assertTrue(result.message.contains("partial files were retained"))
        assertFalse(result.message.contains(unsafe)); assertFalse(result.message.contains("private.invalid"))
    }
    @Test fun providerVerificationStillHasPriorityOverUnsupportedTransport() {
        val failure = MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, "ignored"),
            IOException("Sign in to confirm you're not a bot"))
        assertEquals(MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED, MediaSourceFailure.from(failure).kind)
    }
    @Test fun pairedAdaptiveRefusalSurvivesBoundaryWithoutAnExtractorFailureClaim() {
        val media = ResolvedMediaLink("https://fixture.invalid/video", "application/x-mpegURL", "fixture",
            audioUrl = "https://fixture.invalid/audio", audioMimeType = "application/x-mpegURL")
        val failure = runCatching { SelectedDownloadTransportPolicy.requireSupported(media) }.exceptionOrNull()!!
        val result = MediaSourceFailure.from(failure)
        assertEquals(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, result.kind)
        assertFalse(result.message.contains("installed extractor")); assertTrue(result.message.contains("No completed download"))
    }
}
