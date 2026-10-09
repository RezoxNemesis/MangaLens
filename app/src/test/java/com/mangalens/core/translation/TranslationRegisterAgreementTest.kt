package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationRegisterAgreementTest {
    @Test fun noCapturedStylePreservesTheValidatedHindiRegisterAndAgreement() {
        assertEquals("आप कैसे हैं?", TranslationQualityPolicy.choose("How are you?", "आप कैसे हैं?", "", "hi"))
        assertEquals("आप कहाँ जा रही हैं?", TranslationQualityPolicy.choose("Where are you going?", "आप कहाँ जा रही हैं?", "", "hi"))
        assertEquals("आप चिंता मत कीजिए।", TranslationQualityPolicy.choose("Don't worry.", "आप चिंता मत कीजिए।", "", "hi"))
    }

    @Test fun explicitNaturalAndCasualChangeBothSubjectAndItsPresentCopula() {
        for (style in listOf(TranslationStyleProfile.NATURAL, TranslationStyleProfile.CASUAL, TranslationStyleProfile.WEBTOON)) {
            assertEquals("तुम कैसे हो?", TranslationQualityPolicy.choose("How are you?", "आप कैसे हैं?", "", "hi", style = style))
            assertEquals("तुम कहाँ जा रही हो?", TranslationQualityPolicy.choose("Where are you going?", "आप कहाँ जा रही हैं?", "", "hi", style = style))
            assertEquals("तुम क्या कर रहे हो?", TranslationQualityPolicy.choose("What are you doing?", "आप क्या कर रहे हैं?", "", "hi", style = style))
        }
    }

    @Test fun formalCustomAndFaithfulDoNotBorrowTheNaturalRegister() {
        for (style in listOf(TranslationStyleProfile.FORMAL, TranslationStyleProfile.FAITHFUL,
            TranslationStyleProfile.custom("Address the listener respectfully."))) {
            assertEquals("आप कैसे हैं?", TranslationQualityPolicy.choose("How are you?", "आप कैसे हैं?", "", "hi", style = style))
        }
    }

    @Test fun mixedSubjectsAndRelativeOrFutureClausesAreKeptIntact() {
        for ((source, draft) in listOf(
            "Where are you and where are they?" to "आप कहाँ हैं और वे कहाँ हैं?",
            "What you say is true." to "आप जो कहते हैं वह सच है।",
            "You will arrive tomorrow." to "आप कल आएँगे।",
            "You bastard, what are you doing?" to "आप क्या कर रहे हैं?"
        )) assertEquals(draft, TranslationQualityPolicy.choose(source, draft, "", "hi", style = TranslationStyleProfile.NATURAL))
    }

    @Test fun independentClausesNeverRewriteAnotherSubjectsPluralAuxiliary() {
        assertEquals("तुम यहाँ हो। वे वहाँ हैं।", TranslationQualityPolicy.choose(
            "You are here. They are there.", "आप यहाँ हैं। वे वहाँ हैं।", "", "hi", style = TranslationStyleProfile.NATURAL))
        assertEquals("तुम्हारे पास 3 सिक्के हैं।", TranslationQualityPolicy.choose(
            "You have 3 coins.", "आपके पास 3 सिक्के हैं।", "", "hi", style = TranslationStyleProfile.NATURAL))
    }

    @Test fun aKnownImperativeChangesItsExplicitSubjectTogetherWithTheVerb() {
        assertEquals("तुम यह करो।", TranslationQualityPolicy.choose(
            "You should do this.", "आप यह कीजिए।", "", "hi", style = TranslationStyleProfile.NATURAL))
        assertEquals("तू चुप रह।", TranslationQualityPolicy.choose(
            "You idiot, shut up.", "आप चुप रहिए।", "", "hi", style = TranslationStyleProfile.NATURAL))
    }

    @Test fun hindiFirstRomanProofKeepsDefaultRegisterAndTracksAnExplicitPairedRewrite() {
        val source = "How are you?"
        val original = HinglishTranslationOutput.fromHindiDraft(source, "आप कैसे हैं?")
        assertEquals("आप कैसे हैं?", original.hindiDraft)
        assertEquals(original, TranslationQualityPolicy.chooseDraft(source, original, "", "hi-latn"))
        val casual = TranslationQualityPolicy.chooseDraft(source, original, "", "hi-latn", TranslationStyleProfile.CASUAL)
        assertEquals("तुम कैसे हो?", casual.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable(source, casual.text, "hi-latn", casual.hindiDraft))
        assertEquals(casual, TranslationMemoryCodec.decode(source, TranslationMemoryCodec.encode(source, casual, "hi-latn"), "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable(source, casual.text, "hi-latn", original.hindiDraft))
    }

    @Test fun independentRomanModelOutputRequiresTheSameClauseAgreementRules() {
        assertEquals("aap kaise hain?", TranslationQualityPolicy.choose("How are you?", "aap kaise hain?", "", "hi-latn"))
        assertEquals("tum kaise ho?", TranslationQualityPolicy.choose("How are you?", "aap kaise hain?", "", "hi-latn", style = TranslationStyleProfile.NATURAL))
        assertEquals("aap kahan hain aur ve kahan hain?", TranslationQualityPolicy.choose("Where are you and where are they?",
            "aap kahan hain aur ve kahan hain?", "", "hi-latn", style = TranslationStyleProfile.NATURAL))
    }

    @Test fun aRegisterControlWordUsedAsANameNeverDisappearsDuringPostprocessing() {
        val source = "Hey, Kijiye! Don't worry."
        val candidate = "are, Kijiye! aap chinta mat kijiye."
        assertTrue(TranslationQualityPolicy.isUsable(source, candidate, "hi-latn"))
        assertEquals(candidate, TranslationQualityPolicy.choose(source, candidate, "", "hi-latn", style = TranslationStyleProfile.NATURAL))
    }
}
