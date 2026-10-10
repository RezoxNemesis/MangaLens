package com.mangalens.core.search.embedding

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SemanticModelDownloadPolicyTest {
    private val total = SemanticEmbeddingPin.MODEL_BYTES.toLong()
    @Test fun fixedPinnedPublisherUrlIsAdmitted() { assertEquals("huggingface.co", SemanticModelDownloadPolicy.url(SemanticEmbeddingPin.MODEL_URL).host) }
    @Test fun signedOfficialCdnHopIsAdmitted() { assertEquals("cas-bridge.xethub.hf.co", SemanticModelDownloadPolicy.url("https://cas-bridge.xethub.hf.co/object?Signature=publicsignedvalue").host) }
    @Test fun cleartextRedirectIsRefused() { assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("http://huggingface.co/model") } }
    @Test fun credentialAndFragmentHintsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("https://user:pass@huggingface.co/model") }
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("https://huggingface.co/model#other") }
    }
    @Test fun privateLiteralAndLocalhostRedirectsAreRefused() {
        for (host in listOf("127.0.0.1", "10.0.0.1", "[::1]", "localhost")) assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("https://$host/model") }
    }
    @Test fun nondefaultPortAndLookalikePublisherAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("https://huggingface.co:8443/model") }
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.url("https://huggingface.co.other.example/model") }
    }
    @Test fun validPartialResponseResumesExactRemainingBytes() { assertEquals(1024L, SemanticModelDownloadPolicy.responseOffset(206, 1024, total - 1024, "bytes 1024-${total - 1}/$total")) }
    @Test fun fullResponseIgnoringRangeRestartsAtZero() { assertEquals(0L, SemanticModelDownloadPolicy.responseOffset(200, 1024, total, null)) }
    @Test fun wrongResumeStartOrTotalFails() {
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.responseOffset(206, 1024, total - 1024, "bytes 0-${total - 1}/$total") }
        assertThrows(IllegalArgumentException::class.java) { SemanticModelDownloadPolicy.responseOffset(206, 1024, total - 1024, "bytes 1024-${total - 1}/${total + 1}") }
    }
    @Test fun missingRangeFailsBeforeAppendingAnyBytes() { assertThrows(IOException::class.java) { SemanticModelDownloadPolicy.responseOffset(206, 1024, -1, null) } }
    @Test fun declaredPartialLengthMustMatchExactRemainingSize() { assertThrows(IOException::class.java) { SemanticModelDownloadPolicy.responseOffset(206, 1024, 100, "bytes 1024-${total - 1}/$total") } }
    @Test fun errorStatusNeverGrantsOutputOffset() { assertThrows(IOException::class.java) { SemanticModelDownloadPolicy.responseOffset(403, 0, -1, null) } }
}
