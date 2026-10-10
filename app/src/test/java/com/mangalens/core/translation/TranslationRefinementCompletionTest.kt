package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import org.junit.Assert.assertFalse
import org.junit.Test

/** Request hashes alone cannot establish a completed localization invocation. */
class TranslationRefinementCompletionTest {
    @Test fun currentLocalizationRequestRejectsTextWithoutNativeCompletionEvidence() {
        val source = "Sir, I could not find the 2 keys."
        val draft = "महोदय, मुझे 2 चाबियाँ नहीं मिलीं।"
        val request = TranslationRefinementRequest(true, TranslationStyleProfile.FORMAL,
            OrezModelPin("qwen2.5-0.5b-q4_k_m", "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 491400032))
        val result = TranslationRefinementResult(draft, TranslationRefinementReceipt(request.pinnedModel!!,
            TranslationRefinementPolicy.hash(TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, "", emptyMap())),
            TranslationRefinementPolicy.hash(draft)), TranslationRefinementStatus.GENERATED)
        assertFalse("A token-limited or failed decode must not become verified localized dialogue merely because its text hashes match",
            TranslationRefinementPolicy.matches(result, source, draft, "hi", request))
    }
}
