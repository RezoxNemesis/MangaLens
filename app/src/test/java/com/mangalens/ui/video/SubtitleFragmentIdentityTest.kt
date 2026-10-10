package com.mangalens.ui.video

import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** Authored UNRUN. Whole bytes, cold journals and exact generation/selection ownership. */
class SubtitleFragmentIdentityTest {
    private val bytes = byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112, 4, 5, 6)
    private fun source() = SubtitleMediaSource("https://media.example/manifest.mpd", cacheKey = "selected-audio",
        sourceResolutionId = "a".repeat(32), fragmentPlan = OriginalFragmentPlan("https://media.example/manifest.mpd",
            "audio-140", "audio/mp4", 8_000_000L, listOf(OriginalMediaFragment("https://media.example/init", expectedBytes = bytes.size.toLong()))))
    private fun pending() = source().let { SubtitleSourceIdentity(it, SubtitleInputs.fragmentDescriptor(it), false) }
    private fun verified() = pending().copy(verifiable = true, fragmentContentSha256 = sha(bytes), fragmentSize = bytes.size.toLong())
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    @Test fun capturedPlanHashDoesNotProveAnEncodedTrackWasFetched() {
        val descriptor = pending()
        assertFalse(hasSubtitleSourceProof(descriptor))
        assertFalse(hasSubtitleSourceProof(descriptor.copy(verifiable = true)))
        assertFalse(hasSubtitleSourceProof(descriptor.copy(verifiable = true, fragmentContentSha256 = descriptor.source.fragmentPlan!!.sha256())))
        assertTrue(hasSubtitleSourceProof(verified()))
    }
    @Test fun sameSelectedPlanWithDifferentCompletedBytesIsChanged() {
        val old = verified()
        val changed = old.copy(fragmentContentSha256 = sha(bytes + 7))
        assertEquals(old.fingerprint, changed.fingerprint)
        assertEquals(SubtitleSourceCheck.CHANGED, assessSubtitleSource(old, changed))
        assertFalse(canTrustSubtitleSource(old, changed))
        assertTrue(canTrustSubtitleSource(old, old.copy()))
    }
    @Test fun changedAnchorOrAcceptedResolutionCannotBorrowTheOldByteProof() {
        val old = verified()
        assertFalse(hasSubtitleSourceProof(old.copy(source = old.source.copy(uri = "https://other.example/track"))))
        assertFalse(hasSubtitleSourceProof(old.copy(source = old.source.copy(sourceResolutionId = null))))
        val next = old.source.copy(sourceResolutionId = "b".repeat(32))
        assertNotEquals(old.fingerprint, SubtitleInputs.fragmentDescriptor(next))
        assertNotEquals(old.fingerprint, SubtitleInputs.fragmentDescriptor(old.source.copy(headers = mapOf("Cookie" to "captured=next"))))
    }
    @Test fun proofEnrichmentKeepsExactTaskOwnerGenerationAndColdPlanReceipt() {
        val dir = Files.createTempDirectory("fragment-subtitle-journal").toFile()
        try {
            val store = SubtitleGenerationStore(dir)
            val task = store.start(pending(), SubtitleGenerationConfig(modelSha256 = "c".repeat(64)), ownerRequestId = "orez:captured")
            val bound = store.bindFragmentSource(task.id, task.generation, verified())!!
            assertEquals(task.id, bound.id); assertEquals(task.generation, bound.generation); assertEquals(task.ownerRequestId, bound.ownerRequestId)
            assertEquals(task.config, bound.config); assertEquals(task.status, bound.status)
            assertEquals(bound, SubtitleGenerationStore(dir).get(task.id))
            assertEquals(verified().source.fragmentPlan!!.sha256(), bound.source.source.fragmentPlan!!.sha256())
            assertTrue(hasSubtitleSourceProof(bound.source))
            assertTrue(sameSubtitleGeneration(bound, store.get(task.id)!!))
            assertFalse("Old pre-proof owner snapshot must not remain authoritative", sameSubtitleGeneration(task, bound))
        } finally { dir.deleteRecursively() }
    }
    @Test fun aLateGenerationOrDifferentPlanCannotEnrichTheCurrentTask() {
        val dir = Files.createTempDirectory("fragment-subtitle-owner").toFile()
        try {
            val store = SubtitleGenerationStore(dir)
            val old = store.start(pending(), SubtitleGenerationConfig(modelSha256 = "c".repeat(64)))
            val replacement = store.start(pending(), old.config, force = true)
            assertNull(store.bindFragmentSource(old.id, old.generation, verified()))
            val foreign = verified().copy(source = source().copy(cacheKey = "other-playback"))
            assertThrows(IllegalArgumentException::class.java) { store.bindFragmentSource(replacement.id, replacement.generation, foreign) }
            assertEquals(replacement, store.get(replacement.id))
        } finally { dir.deleteRecursively() }
    }
    @Test fun anExistingByteReceiptCannotBeReplacedByNewBytesOrPartialProof() {
        val dir = Files.createTempDirectory("fragment-subtitle-proof").toFile()
        try {
            val store = SubtitleGenerationStore(dir)
            val task = store.start(pending(), SubtitleGenerationConfig(modelSha256 = "c".repeat(64)))
            val bound = store.bindFragmentSource(task.id, task.generation, verified())!!
            assertThrows(SubtitleNetworkChanged::class.java) { store.bindFragmentSource(task.id, task.generation,
                verified().copy(fragmentContentSha256 = sha(bytes + 1))) }
            assertThrows(IllegalArgumentException::class.java) { store.bindFragmentSource(task.id, task.generation,
                verified().copy(fragmentSize = null)) }
            assertEquals(bound, SubtitleGenerationStore(dir).get(task.id))
        } finally { dir.deleteRecursively() }
    }
}
