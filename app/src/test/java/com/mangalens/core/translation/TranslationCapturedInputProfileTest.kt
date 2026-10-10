package com.mangalens.core.translation

import com.mangalens.orez.OrezGenerationCompletion
import com.mangalens.orez.OrezLocalizationProfile
import com.mangalens.orez.OrezModelPin
import org.junit.Assert.*
import org.junit.Test

/** Scalar restart/request binding only: no actual JNI output or model-quality claim. */
class TranslationCapturedInputProfileTest {
    private val source = "Sir, I could not find the 2 keys."
    private val draft = "महोदय, मुझे 2 कुंजी नहीं मिल सका।"
    private val pin = OrezModelPin("qwen2.5-0.5b-q4_k_m", "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 491400032)
    private val v2RawHash = "4e9d9dc9dea9f73d670d44f2a98ed159c7b85c143660e10b7f627a88438d95ec"
    private val v2FormattedHash = "5c2cc13a895778265ced31db976d5f32f83334a44ed5e48d0fddf3404a6b6dfd"
    private fun request(revision: String?): TranslationRefinementRequest {
        val json = TranslationRefinementRequestCodec.encode(TranslationRefinementRequest(true, TranslationStyleProfile.FORMAL, pin))
        revision?.let { json.put("inputProfileRevision", it) }
        return TranslationRefinementRequestCodec.decode(json)
    }
    private fun capturedPrompt(request: TranslationRefinementRequest): String {
        val producer = TranslationRefinementPolicy.javaClass.methods.firstOrNull { it.name.startsWith("capturedPrompt$") && it.parameterCount == 7 }
        // The real old producer is the RED fallback; it demonstrates the ambient upgrade defect.
        return if (producer == null) TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, "", emptyMap())
        else producer.invoke(TranslationRefinementPolicy, source, draft, "hi", request, "", emptyMap<String, String>(), null) as String
    }
    private fun formatted(raw: String, revision: String): String {
        val formatter = OrezLocalizationProfile.javaClass.methods.firstOrNull { it.name == "formattedPrompt" && it.parameterCount == 2 }
        return if (formatter == null) OrezLocalizationProfile.formattedPrompt(raw)
        else formatter.invoke(OrezLocalizationProfile, raw, revision) as String
    }
    private fun ready(request: TranslationRefinementRequest): Boolean {
        val check = TranslationRefinementPolicy.javaClass.methods.firstOrNull { it.name.startsWith("generationReady$") && it.parameterCount == 1 }
        return if (check == null) request.enabled && request.pinnedModel != null else check.invoke(TranslationRefinementPolicy, request) as Boolean
    }

    @Test fun capturedRevisionSurvivesJsonColdReload() {
        val restored = request("orez-localization-v2")
        assertEquals("orez-localization-v2", TranslationRefinementRequestCodec.encode(restored).optString("inputProfileRevision", ""))
        assertEquals(restored, TranslationRefinementRequestCodec.decode(TranslationRefinementRequestCodec.encode(restored)))
    }
    @Test fun capturedV2AndV3CannotShareStableRequestIdentity() {
        assertNotEquals(TranslationRefinementRequestCodec.identity(request("orez-localization-v2")),
            TranslationRefinementRequestCodec.identity(request("orez-localization-v3")))
    }
    @Test fun missingHistoricalRevisionKeepsOriginalIdentityAndRefusesNewInference() {
        val legacy = request(null)
        val original = listOf(legacy.enabled.toString(), legacy.style.id, legacy.style.name, legacy.style.instruction,
            legacy.style.preserveHonorifics.toString(), legacy.style.preserveNames.toString(), legacy.style.naturalDialogue.toString(),
            pin.modelId, pin.sha256, pin.bytes.toString()).joinToString("|") { "${it.length}:$it" }
        assertEquals(original, TranslationRefinementRequestCodec.identity(legacy))
        assertFalse(TranslationRefinementRequestCodec.encode(legacy).has("inputProfileRevision"))
        assertFalse("Missing historical revision requires an explicit restart before new native work", ready(legacy))
    }
    @Test fun capturedV2RebuildsTheExactPreviouslyMeasuredInputWithCurrentV2() {
        assertEquals("orez-localization-v2", TranslationRefinementPolicy.INPUT_PROFILE_VERSION)
        val restored = request("orez-localization-v2")
        val raw = capturedPrompt(restored)
        assertEquals(v2RawHash, TranslationRefinementPolicy.hash(raw))
        assertEquals(v2FormattedHash, OrezLocalizationProfile.hash(formatted(raw, "orez-localization-v2")))
        assertTrue(ready(restored))
    }
    @Test fun unknownCapturedVersionNeverFallsBackToAmbientCurrent() {
        assertFalse(ready(request("orez-localization-v99")))
    }
    @Test fun knownCompletionCannotBorrowAnUnknownCapturedJob() {
        val fresh = request("orez-localization-v2")
        val raw = capturedPrompt(fresh)
        val scalar = OrezGenerationCompletion("orez-localization-v2", OrezLocalizationProfile.hash(formatted(raw, "orez-localization-v2")),
            "EOG", 1, 1, 288, 0, 0, 0, 0)
        val result = TranslationRefinementResult(draft, TranslationRefinementReceipt(pin, TranslationRefinementPolicy.hash(raw),
            TranslationRefinementPolicy.hash(draft), scalar), TranslationRefinementStatus.GENERATED)
        assertTrue(TranslationRefinementPolicy.matches(result, source, draft, "hi", fresh))
        assertFalse(TranslationRefinementPolicy.matches(result, source, draft, "hi", request("orez-localization-v99")))
    }
    @Test fun existingTypedV2ProofRemainsVerifiableWithoutAuthorizingMissingRevisionInference() {
        val scalar = OrezGenerationCompletion("orez-localization-v2", v2FormattedHash, "EOG", 1, 1, 288, 0, 0, 0, 0)
        val result = TranslationRefinementResult(draft, TranslationRefinementReceipt(pin, v2RawHash, TranslationRefinementPolicy.hash(draft), scalar),
            TranslationRefinementStatus.GENERATED)
        assertTrue(TranslationRefinementPolicy.matches(result, source, draft, "hi", request("orez-localization-v2")))
        assertTrue(TranslationRefinementPolicy.matches(result, source, draft, "hi", request(null)))
        assertFalse(ready(request(null)))
    }
}
