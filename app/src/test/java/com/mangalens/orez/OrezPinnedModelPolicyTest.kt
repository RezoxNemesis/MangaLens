package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OrezPinnedModelPolicyTest {
    private val captured = OrezModelPin("captured-lite", "a".repeat(64), 1234)
    private val lite = OrezModelCandidate(File("/private/verified/lite.gguf"), captured)
    private val core = OrezModelCandidate(File("/private/verified/core.gguf"), captured.copy(modelId = "new-core", sha256 = "b".repeat(64), bytes = 5678))

    @Test fun changedSelectionStillResolvesTheCapturedArtifact() {
        assertEquals(listOf(lite), OrezPinnedModelPolicy.select(captured, listOf(core, lite)))
    }
    @Test fun unavailablePinNeverUsesAnotherVerifiedModel() {
        assertTrue(OrezPinnedModelPolicy.select(captured, listOf(core)).isEmpty())
    }
    @Test fun everyIdentityFieldMustMatch() {
        for (wrong in listOf(captured.copy(modelId="other"), captured.copy(sha256="c".repeat(64)), captured.copy(bytes=1235)))
            assertTrue(OrezPinnedModelPolicy.select(wrong, listOf(lite)).isEmpty())
    }
    @Test fun unpinnedExistingCallersKeepTheirOrderedFallbackCandidates() {
        assertEquals(listOf(core, lite), OrezPinnedModelPolicy.select(null, listOf(core,lite)))
    }
    @Test fun malformedAndOversizePinsCannotResolveEvenAnIdenticalCandidate() {
        for (wrong in listOf(captured.copy(modelId="../escape"), captured.copy(sha256="A".repeat(64)), captured.copy(bytes=0), captured.copy(bytes=8_000_000_001)))
            assertTrue(OrezPinnedModelPolicy.select(wrong, listOf(lite.copy(pin=wrong))).isEmpty())
    }
}
