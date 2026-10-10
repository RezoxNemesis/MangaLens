package com.mangalens.core.translation

import com.mangalens.orez.OrezGenerationCompletion
import com.mangalens.orez.OrezLocalizationProfile
import com.mangalens.orez.OrezModelPin
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar receipts test identity filters, never claim a real native invocation or model output. */
class TranslationRefinementMemoryIdentityTest {
    private val source = "Hello Jin."
    private val draft = "नमस्ते, Jin।"
    private val request = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2")
    private val glossary = mapOf("Jin" to "Jin")

    @Test fun capturedPacketHashParticipatesInBothActualVersionedPromptAndFormattedCompletionIdentity() {
        val packetA = "b".repeat(64); val packetB = "c".repeat(64)
        val promptA = prompt(packetA); val promptB = prompt(packetB)
        assertTrue(promptA.startsWith("LOCALIZATION PROFILE: ${TranslationRefinementPolicy.INPUT_PROFILE_VERSION}\n"))
        assertTrue(promptA.contains("CAPTURED SERIES MEMORY SHA-256: $packetA\n"))
        assertNotEquals(promptA, promptB)
        val result = result(promptA, completion(promptA))
        assertTrue(TranslationRefinementPolicy.matches(result, source, draft, "hi", request, "", glossary, packetA))
        assertFalse(TranslationRefinementPolicy.matches(result, source, draft, "hi", request, "", glossary, packetB))
        val forged = result.copy(receipt = result.receipt!!.copy(promptSha256 = TranslationRefinementPolicy.hash(promptB)))
        assertFalse(TranslationRefinementPolicy.matches(forged, source, draft, "hi", request, "", glossary, packetB))
    }

    @Test fun legacyNullCompletionRemainsInspectableButCannotOwnANewMemoryPacket() {
        val legacyRequest = request.copy(inputProfileRevision = null)
        val legacy = "STYLE PROFILE ID: ${legacyRequest.style.id}\n" + buildTranslationRefinementPrompt(source, draft, "hi", legacyRequest.style, "", glossary)
        val result = result(legacy, null)
        assertTrue(TranslationRefinementPolicy.matches(result, source, draft, "hi", legacyRequest, "", glossary))
        assertFalse(TranslationRefinementPolicy.matches(result, source, draft, "hi", legacyRequest, "", glossary, "b".repeat(64)))
        assertFalse(TranslationRefinementPolicy.matches(result(prompt("b".repeat(64)), null), source, draft, "hi", legacyRequest, "", glossary, "b".repeat(64)))
    }

    @Test fun malformedPacketHashCannotEnterAnOtherwiseValidPromptOrMatchingReceipt() {
        for (hash in listOf("", "b".repeat(63), "B".repeat(64), "b".repeat(64) + "\nprivate/path")) {
            assertFalse(TranslationRefinementPolicy.validInputs(source, draft, "hi", request.style, "", glossary, hash))
            val prompt = prompt(hash)
            assertFalse(TranslationRefinementPolicy.matches(result(prompt, completion(prompt)), source, draft, "hi", request, "", glossary, hash))
        }
    }

    private fun prompt(hash: String) = TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, "", glossary, hash)
    private fun result(prompt: String, completion: OrezGenerationCompletion?) = TranslationRefinementResult(draft,
        TranslationRefinementReceipt(request.pinnedModel!!, TranslationRefinementPolicy.hash(prompt), TranslationRefinementPolicy.hash(draft), completion),
        TranslationRefinementStatus.GENERATED)
    private fun completion(prompt: String) = OrezGenerationCompletion(OrezLocalizationProfile.REVISION,
        OrezLocalizationProfile.hash(OrezLocalizationProfile.formattedPrompt(prompt)), "EOG", 150, 12, OrezLocalizationProfile.MAX_TOKENS,
        1, 1, 1, 1)
}
