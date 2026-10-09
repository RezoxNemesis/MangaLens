package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class CapturedTranslationStylePolicyTest {
    private val source = "Give me your book."
    private val respectfulHindi = "कृपया अपनी पुस्तक दीजिए।"
    private val custom = TranslationStyleProfile.custom("Use respectful Hindi addressing the listener politely.")

    @Test fun formalAndExplicitCustomHindiDoNotGetDefaultNaturalRegisterRewrite() {
        for (style in listOf(TranslationStyleProfile.FORMAL, custom)) {
            assertEquals(respectfulHindi, TranslationQualityPolicy.chooseDraft(source,
                TranslationDraft("अपनी पुस्तक दो।"), respectfulHindi, "hi", style).text)
        }
        assertEquals("कृपया अपनी पुस्तक दो।", TranslationQualityPolicy.choose(source, respectfulHindi, "", "hi"))
    }

    @Test fun capturedStylePreservesIndependentRomanRefinement() {
        for (style in listOf(TranslationStyleProfile.FORMAL, custom)) {
            val selected = TranslationQualityPolicy.chooseDraft(source, TranslationDraft("tum apni pustak do."),
                "aap apni pustak dijiye.", "hi-latn", style)
            assertEquals("aap apni pustak dijiye.", selected.text)
            assertNull(selected.hindiDraft)
        }
    }

    @Test fun selectedRespectfulHindiProofSurvivesCodecWithoutAmbientRegisterRewriting() {
        for (style in listOf(TranslationStyleProfile.FORMAL, custom)) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, respectfulHindi, style)
            assertEquals(respectfulHindi, draft.hindiDraft)
            val selected = TranslationQualityPolicy.chooseDraft(source, draft, "", "hi-latn", style)
            assertEquals(draft, selected)
            assertTrue(TranslationQualityPolicy.isUsable(source, draft.text, "hi-latn", draft.hindiDraft))
            assertEquals(draft, TranslationMemoryCodec.decode(source, TranslationMemoryCodec.encode(source, draft, "hi-latn"), "hi-latn"))
            assertFalse(TranslationQualityPolicy.isUsable(source, "aap apni pustak do.", "hi-latn", respectfulHindi))
        }
    }
}
