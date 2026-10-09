package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

/** Captured polarity regression plus unrelated clauses; these guards do not grade fluency. */
class TranslationMeaningPolicyTest {
    @Test fun capturedNoFamilyDefinitionCannotBecomeHavingAFamily() {
        val source = "*INDEPENDENT STUDENT: A STUDENT WITH NO AFFILIATED FAMILY OR GUARDIAN."
        assertFalse(TranslationQualityPolicy.isUsable(source, "* स्वतंत्र छात्र: कोई संबद्ध परिवार या अभिभावक वाला छात्र।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable(source, "*स्वतंत्र छात्र: ऐसा छात्र जिसका कोई संबद्ध परिवार या अभिभावक न हो।", "hi"))
    }

    @Test fun preservesNegativePossessionAcrossUnrelatedNouns() {
        for ((source, wrong, right) in listOf(
            Triple("A room with no windows.", "खिड़कियों वाला कमरा।", "बिना खिड़कियों वाला कमरा।"),
            Triple("She has no weapons.", "उसके पास हथियार हैं।", "उसके पास कोई हथियार नहीं है।"),
            Triple("I couldn't find the key.", "मुझे चाबी मिल गई।", "मुझे चाबी नहीं मिली।"),
            Triple("The village isn't on the map.", "गाँव नक्शे पर है।", "गाँव नक्शे पर नहीं है।")
        )) {
            assertFalse(source, TranslationQualityPolicy.isUsable(source, wrong, "hi"))
            assertTrue(source, TranslationQualityPolicy.isUsable(source, right, "hi"))
        }
    }

    @Test fun independentRefinementCannotEraseTheDraftsNegation() {
        assertEquals("उसके पास कोई हथियार नहीं है।", TranslationQualityPolicy.choose(
            "She has no weapons.", "उसके पास कोई हथियार नहीं है।", "उसके पास हथियार हैं।", "hi"))
    }

    @Test fun romanHindiEvidenceCannotAuthorizeAnOppositeHindiIntermediate() {
        try {
            HinglishTranslationOutput.fromHindiDraft("A room with no windows.", "खिड़कियों वाला कमरा।")
            fail("Opposite Hindi meaning was accepted as evidence")
        } catch (_: TranslationQualityException) { }
        val good = HinglishTranslationOutput.fromHindiDraft("A room with no windows.", "बिना खिड़कियों वाला कमरा।")
        assertTrue(TranslationQualityPolicy.isUsable("A room with no windows.", good.text, "hi-latn", good.hindiDraft))
        assertFalse(TranslationQualityPolicy.isUsable("A room with no windows.", "khidkiyon wala kamra.", "hi-latn"))
    }

    @Test fun negativeWordsInsidePositiveIdiomsAreNotBlindlyRequired() {
        for ((source, candidate) in listOf(
            "No wonder he won." to "स्वाभाविक है कि वह जीत गया।",
            "Not only students but also teachers came." to "छात्रों के साथ शिक्षक भी आए।",
            "Why not ask her?" to "उससे पूछें क्यों?",
            "No matter what happens, stay here." to "कुछ भी हो, यहीं रहो।",
            "He is none other than the king." to "वह राजा ही है।"
        )) assertTrue(source, TranslationQualityPolicy.isUsable(source, candidate, "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("No wonder she has no weapons.", "स्वाभाविक है कि उसके पास हथियार हैं।", "hi"))
    }

    @Test fun explicitAmountsCannotChangeOrDisappear() {
        assertFalse(TranslationQualityPolicy.isUsable("I bought 12 drinks.", "मैंने 13 पेय खरीदे।", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("Give me 300 coins!", "मुझे सिक्के दो!", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I bought 12 drinks.", "मैंने १२ पेय खरीदे।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I bought 3 drinks.", "मैंने तीन पेय खरीदे।", "hi"))
    }

    @Test fun explicitNamedEntitiesCannotDisappearButTransliterationIsAllowed() {
        assertFalse(TranslationQualityPolicy.isUsable("HEY, VELORA! COME HERE!", "अरे, यहाँ आओ!", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("HEY, VELORA! COME HERE!", "अरे वेलोरा, यहाँ आओ!", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("A world named 'Zevran'.", "एक दुनिया।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("A world named 'Zevran'.", "ज़ेवरन नाम की दुनिया।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("Hey, buddy! Come here!", "अरे दोस्त, यहाँ आओ!", "hi"))
    }

    @Test fun unrepairedOcrCorruptionCannotBeRecalledAsSuccessfulHindi() {
        assertFalse(TranslationQualityPolicy.isUsable("From hebHhe worlaAa", "हेभे वोला से", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("Sorry I'm so later!", "मुझे बाद में खेद है!", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find it.", "मुझे वह नहीं मिला।", "hi"))
    }
    @Test fun financialShortageCannotBecomePlentyOfMoney() {
        assertFalse(TranslationQualityPolicy.isUsable("Families who are short on money.", "वे परिवार जिनके पास बहुत पैसे हैं।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("Families who are short on money.", "वे परिवार जिनके पास पैसों की कमी है।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("Families who are short on money.", "वे परिवार जिनके पास पर्याप्त पैसा नहीं है।", "hi"))
    }
    @Test fun explicitOrdinalAndFinalLabelsCannotChange() {
        assertFalse(TranslationQualityPolicy.isUsable("The third and final door.", "दूसरा और अंतिम द्वार।", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("The third and final door.", "तीसरा और अगला द्वार।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("The third and final door.", "तीसरा और आख़िरी द्वार।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("Wait a second.", "एक पल रुको।", "hi"))
    }
    @Test fun hyphenApostropheAndStutterNamesRetainTheirCompleteIdentity() {
        for (name in listOf("Mary-Jane", "O'Neil", "Y-Yeorum")) {
            assertTrue(name, TranslationMeaningPolicy.isCompatible("Hey, $name!", "अरे, $name!", "hi"))
            assertTrue(name, TranslationQualityPolicy.isUsable("Hey, $name! Listen to me!", "अरे, $name! मेरी बात ज़रा सुनो!", "hi"))
        }
        assertFalse(TranslationMeaningPolicy.isCompatible("Hey, Mary-Jane!", "अरे, Jane!", "hi"))
        assertFalse(TranslationMeaningPolicy.isCompatible("Hi, O'Neil!", "नमस्ते, Neil!", "hi"))
    }
}
