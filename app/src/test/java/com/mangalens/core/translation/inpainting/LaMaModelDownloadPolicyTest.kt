package com.mangalens.core.translation.inpainting

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN protocol controls, without fetching or qualifying weights. */
class LaMaModelDownloadPolicyTest {
    @Test fun immutablePublisherPinAndActualPublicContentRedirectAreAllowed() {
        assertEquals("huggingface.co", LaMaModelDownloadPolicy.url(LaMaReconstructionPin.MODEL_URL).host)
        assertEquals("cas-bridge.xethub.hf.co", LaMaModelDownloadPolicy.url("https://cas-bridge.xethub.hf.co/pinned?X-Amz-Signature=public-fixture").host)
    }
    @Test fun cleartextCredentialsAndArbitraryPrivateOrLookalikeHostsAreRefused() {
        for (url in listOf("http://huggingface.co/model", "https://name:password@huggingface.co/model", "https://huggingface.co.attacker.example/model",
            "https://127.0.0.1/model", "https://huggingface.co/model#hint"))
            assertThrows(IllegalArgumentException::class.java) { LaMaModelDownloadPolicy.url(url) }
    }
    @Test fun fullResponseRestartsAPartialInsteadOfAppendingDuplicateBytes() {
        assertEquals(0L, LaMaModelDownloadPolicy.responseOffset(200, 100, LaMaReconstructionPin.MODEL_BYTES.toLong(), null))
    }
    @Test fun resumeRequiresExactStartEndTotalAndDeclaredLength() {
        val size = LaMaReconstructionPin.MODEL_BYTES.toLong()
        assertEquals(100L, LaMaModelDownloadPolicy.responseOffset(206, 100, size - 100, "bytes 100-${size - 1}/$size"))
        for (range in listOf("bytes 101-${size - 1}/$size", "bytes 100-${size - 2}/$size", "bytes 100-${size - 1}/${size + 1}"))
            assertThrows(IllegalArgumentException::class.java) { LaMaModelDownloadPolicy.responseOffset(206, 100, size - 100, range) }
    }
    @Test fun unknownTransportLengthStillRequiresTheManagersExactBoundedByteReceipt() {
        assertEquals(0L, LaMaModelDownloadPolicy.responseOffset(200, 0, -1, null))
        assertThrows(java.io.IOException::class.java) { LaMaModelDownloadPolicy.responseOffset(200, 0, 42, null) }
        assertThrows(java.io.IOException::class.java) { LaMaModelDownloadPolicy.responseOffset(206, 0, -1, null) }
    }
    @Test fun unavailableOrForbiddenPublisherResponseCannotBecomeAnInstalledPack() {
        for (code in listOf(401, 403, 404, 416, 503))
            assertThrows(java.io.IOException::class.java) { LaMaModelDownloadPolicy.responseOffset(code, 0, -1, null) }
    }
}
