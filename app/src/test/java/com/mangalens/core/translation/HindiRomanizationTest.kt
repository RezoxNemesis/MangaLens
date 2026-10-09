package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class HindiRomanizationTest {
    @Test fun commonDialogueUsesReadableHindiSpellingsWithoutSchwasOrDiacritics() {
        assertEquals("main theek hoon. tum kaise ho?", HindiRomanization.render("मैं ठीक हूँ। तुम कैसे हो?"))
        assertEquals("mujhe nahi pata! kya hua?", HindiRomanization.render("मुझे नहीं पता! क्या हुआ?"))
        assertEquals("mera naam Jin hai.", HindiRomanization.render("मेरा नाम Jin है।"))
    }

    @Test fun preservedLatinNamesGenreTermsAndPunctuationAreNotLowercasedOrRewritten() {
        assertEquals("Jin ka S-rank level 20 hai!", HindiRomanization.render("Jin का S-rank level २० है!"))
        assertEquals("Jin aa gaya!", HindiRomanization.render("जिन आ गया!", "Jin arrived!"))
        assertEquals("Sung Jinwoo ke paas mana hai.", HindiRomanization.render("Sung Jinwoo के पास mana है।"))
        assertEquals("level badh gaya.", HindiRomanization.render("level बढ़ गया।", "Level increased."))
    }

    @Test fun unknownHindiWordsUseConjunctNuktaAndNasalRulesAndRemainLatin() {
        val text = HindiRomanization.render("क़लम क्षत्रिय ज्ञान ज़ोर फ़र्ज़ बैंक संभव")
        assertEquals("qalam kshatriya gyaan zor farz bank sambhav", text)
        assertFalse(text.any { it in '\u0900'..'\u097f' })
        assertTrue(text.all { it.code < 128 })
    }

    @Test fun romanHindiDetectionDoesNotMistakeAnEnglishNameOrAOneWordCoincidenceForHindi() {
        assertTrue(HindiRomanization.isRomanHindi("Tum ghar kab aaoge?"))
        assertTrue(HindiRomanization.isRomanHindi("Kya hua?"))
        assertTrue(HindiRomanization.isRomanHindi("Ruko!"))
        assertFalse(HindiRomanization.isRomanHindi("I met Hai yesterday."))
        assertFalse(HindiRomanization.isRomanHindi("Jin defeated the boss."))
        assertFalse(HindiRomanization.isRomanHindi("BEATEN UP."))
    }

    @Test fun romanTargetIsDistinctFromHindiAndRegionalHindiTags() {
        assertTrue(HindiRomanization.isTarget("HI_latn"))
        assertFalse(HindiRomanization.isTarget("hi"))
        assertFalse(HindiRomanization.isTarget("hi-IN"))
    }

    @Test fun allCapsOcrNamesRegainTheirSourceSpellingWithoutRewritingAnEnglishClause() {
        assertEquals("JIN chinta mat karo.", HindiRomanization.render("जिन चिंता मत करो।", "JIN, DON'T WORRY."))
        assertEquals("tum chinta mat karo.", HindiRomanization.render("तुम चिंता मत करो।", "DON'T WORRY."))
    }
}
