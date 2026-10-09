package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class HinglishTranslationOutputTest {
    @Test fun hindiFirstOutputNormalizesInventedPolitenessBeforeRomanization() {
        assertEquals("tum chinta mat karo, Jin!", HinglishTranslationOutput.fromHindi("Don't worry, Jin!", "आप चिंता मत कीजिए, Jin!"))
    }

    @Test fun hindiFirstOutputKeepsNamesAndGenreTermsWithoutBorrowingWholeEnglishClauses() {
        assertEquals("Sung Jinwoo ka level badh gaya!", HinglishTranslationOutput.fromHindi(
            "Sung Jinwoo's level increased!", "Sung Jinwoo का level बढ़ गया!"))
        assertThrows(TranslationQualityException::class.java) { HinglishTranslationOutput.fromHindi("I was beaten up.", "I was beaten up.") }
    }

    @Test fun devanagariSourceAndAlreadyRomanSourceBothHaveUsableRomanOutput() {
        val hindi = "मैं Jin के साथ घर जा रहा हूँ!"
        assertEquals("main Jin ke saath ghar ja raha hoon!", HinglishTranslationOutput.fromHindi(hindi, hindi))
        assertEquals("Tum ghar kab aaoge?", HinglishTranslationOutput.alreadyRoman("Tum ghar kab aaoge?"))
    }

    @Test fun unsupportedGlyphsCannotProducePartlyDevanagariRomanOutput() {
        assertThrows(TranslationQualityException::class.java) { HinglishTranslationOutput.fromHindi("Hello", "ॿ") }
    }

    @Test fun aNameOrEstablishedGenreTermCanStayLatinWithoutBeingTreatedAsAnEnglishClause() {
        assertEquals("Jin", HinglishTranslationOutput.fromHindi("Jin", "Jin"))
        assertEquals("Sung Jinwoo", HinglishTranslationOutput.fromHindi("Sung Jinwoo", "Sung Jinwoo"))
        assertEquals("level", HinglishTranslationOutput.fromHindi("level", "लेवल"))
        assertEquals("mana", HinglishTranslationOutput.fromHindi("mana", "mana"))
    }

    @Test fun punctuationAndNumericDialogueKeepTheirMeaningAndHindiDigitsBecomeLatinDigits() {
        assertEquals("...", HinglishTranslationOutput.fromHindi("...", "..."))
        assertEquals("123!", HinglishTranslationOutput.fromHindi("123!", "123!"))
        assertEquals("123!", HinglishTranslationOutput.fromHindi("१२३!", "१२३!"))
        assertFalse(TranslationQualityPolicy.isUsable("...", "!!!", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("Wait!", "!", "hi-latn"))
    }

    @Test fun commonShortHindiNounsDoNotRequireAnInventedPronounOrVerb() {
        assertEquals("dost!", HinglishTranslationOutput.fromHindi("Friend!", "दोस्त!"))
        assertEquals("ladki", HinglishTranslationOutput.fromHindi("Girl", "लड़की"))
    }

    @Test fun fireUsesItsValidatedHindiDraftWithoutInventingGrammar() {
        assertEquals("aag!", HinglishTranslationOutput.fromHindi("Fire!", "आग!"))
    }

    @Test fun powerUsesItsValidatedHindiDraftWithoutInventingGrammar() {
        assertEquals("shakti!", HinglishTranslationOutput.fromHindi("Power!", "शक्ति!"))
    }

    @Test fun swordUsesItsValidatedHindiDraftWithoutInventingGrammar() {
        assertEquals("talvaar!", HinglishTranslationOutput.fromHindi("Sword!", "तलवार!"))
    }

    @Test fun upperCaseManhwaSourceNamesSurviveTheHindiIntermediate() {
        assertEquals("JIN chinta mat karo!", HinglishTranslationOutput.fromHindi("JIN, DON'T WORRY!", "जिन चिंता मत करो।"))
        assertThrows(TranslationQualityException::class.java) { HinglishTranslationOutput.fromHindi("HE WAS SERIOUSLY INJURED.", "वह SERIOUSLY INJURED था।") }
    }
}
