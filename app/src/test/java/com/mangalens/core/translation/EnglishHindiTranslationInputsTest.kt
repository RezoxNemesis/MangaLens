package com.mangalens.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class EnglishHindiTranslationInputsTest {
    @Test fun negativePossessionHasAnExplicitWithoutConstruction() {
        assertEquals("A room without any windows.", normalizeEnglishDialogueForHindi("A room with no windows."))
        assertEquals("A passport without any valid visa.", normalizeEnglishDialogueForHindi("A passport WITH NO valid visa."))
    }
    @Test fun FinancialShortageIsNotPhysicalHeight() {
        assertEquals("Families who do not have enough money.", normalizeEnglishDialogueForHindi("Families who are short on money."))
        assertEquals("She does not have enough funds.", normalizeEnglishDialogueForHindi("She is short on funds."))
        assertEquals("They did not have enough cash.", normalizeEnglishDialogueForHindi("They were short on cash."))
        assertEquals("He is short and carries money.", normalizeEnglishDialogueForHindi("He is short and carries money."))
    }
    @Test fun explicitNegationRetriesOnceAndPreservesRomanHindiEvidence() = runBlocking {
        val requests = mutableListOf<String>()
        val selected = EnglishHindiTranslationInputs.translateDraft("A room with no windows.", "hi-latn") { input ->
            requests += input
            if (requests.size == 1) "खिड़कियों वाला कमरा।" else "बिना खिड़कियों वाला कमरा।"
        }
        assertEquals(listOf("A room without any windows.", "A room that does not have any windows."), requests)
        assertEquals("बिना खिड़कियों वाला कमरा।", selected.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable("A room with no windows.", selected.text, "hi-latn", selected.hindiDraft))
    }
    @Test fun usableFirstAttemptDoesNotInvokeAnotherTranslatorCall() = runBlocking {
        var calls = 0
        val draft = EnglishHindiTranslationInputs.translateDraft("A room with no windows.", "hi") {
            calls++; "बिना खिड़कियों वाला कमरा।"
        }
        assertEquals(1, calls)
        assertEquals("बिना खिड़कियों वाला कमरा।", draft.text)
    }
    @Test fun unsuccessfulAttemptsStayBoundedAndCannotPassTheFinalGate() = runBlocking {
        var calls = 0
        val draft = EnglishHindiTranslationInputs.translateDraft("A room with no windows.", "hi") {
            calls++; "खिड़कियों वाला कमरा।"
        }
        assertEquals(2, calls)
        assertFalse(TranslationQualityPolicy.isUsable("A room with no windows.", draft.text, "hi"))
        assertEquals("बिना खिड़कियों वाला कमरा।", TranslationQualityPolicy.choose(
            "A room with no windows.", draft.text, "बिना खिड़कियों वाला कमरा।", "hi"))
    }
}
