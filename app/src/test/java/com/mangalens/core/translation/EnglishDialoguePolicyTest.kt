package com.mangalens.core.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishDialoguePolicyTest {
    @Test fun recognisesShortImperativesAndIdioms() {
        listOf("GET OUT!", "Let me go!", "BEATEN\nUP.", "No way.", "What the hell?", "Open the gate!", "Protect the children.", "Go home.").forEach {
            assertTrue("Expected English dialogue: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun recognisesShortPersonalClauses() {
        listOf("I can do it.", "Are you okay?", "You will pay for this.", "We are going home.").forEach {
            assertTrue("Expected English dialogue: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun recognisesContractionsAndCurlyApostrophes() {
        listOf("Don't give up!", "I’m here.", "We're going home.", "You’ll regret it.").forEach {
            assertTrue("Expected English dialogue: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun latinCharacterNamesDoNotBecomeEnglishHints() {
        listOf("Sung Jin Woo", "Akira", "Nakamura Haruto", "Johan Liebert", "Li Wei", "Go Eun", "Will Graham", "The Shadow Monarch").forEach {
            assertFalse("Name should not select English: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun shortForeignLatinPhrasesDoNotBecomeEnglishHints() {
        listOf("Mon ami", "La casa", "Para ti", "Das ist gut", "Je suis ici", "Namaste dost", "Nani kore").forEach {
            assertFalse("Foreign phrase should not select English: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun nonLatinDialogueAndOcrNoiseDoNotBecomeEnglishHints() {
        listOf("こんにちは 世界", "안녕하세요", "你好", "नमस्ते", "###...?!", "1234", "").forEach {
            assertFalse("Non-English source should not select English: $it", EnglishDialoguePolicy.shouldHintEnglish(it))
        }
    }

    @Test fun embeddedEnglishNameDoesNotOverrideNonLatinDialogue() {
        assertFalse(EnglishDialoguePolicy.shouldHintEnglish("こんにちは Akira、待ってください"))
    }
}
