package com.mangalens.core.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderCommercialInterfacePromoTest {
    private val chatCard = "MangaDemon\nGirlfriendGPT\nMia Online Now\nAre you free tonight? Let's talk\nSTART CHATTING\nANNOUNCEMENT"
    @Test fun brandedChatCardWithSiteAnnouncementDoesNotBecomeStoryDialogue() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo(chatCard))
        assertTrue(ReaderSavedPromoPolicy.classify(chatCard.lines(), true))
    }
    @Test fun anAnnouncementOrOnlinePhraseInsideSubstantialStoryIsRetained() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo("The announcement said everyone must be online now before the army reaches the gate.\n" +
            "We have to protect the children and lead the villagers to safety.\nGirlfriendGPT START CHATTING"))
    }
    @Test fun uiLabelsWithoutBrandAndCommercialActionNeverGrantPromoStatus() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo("Mia Online Now\nANNOUNCEMENT\nAre you free tonight? Let's talk"))
        assertFalse(ReaderPromoPolicy.isLikelyPromo("MangaDemon\nANNOUNCEMENT"))
    }
    @Test fun longStoryInputsAreRejectedWholeInsteadOfClassifyingOnlyTheirAdPrefix() {
        assertFalse(ReaderSavedPromoPolicy.classify(listOf(chatCard, "The villagers are safe. ".repeat(2000)), true))
        assertFalse(ReaderPromoPolicy.isLikelyPromo(chatCard + "x".repeat(ReaderPromoPolicy.MAX_TEXT_CHARS)))
        assertFalse(ReaderSavedPromoPolicy.classify(List(513) { "GirlfriendGPT START CHATTING" }, true))
    }
    @Test fun partiallyTranslatedSourceCannotClassifyOnlyItsSuccessfulAdText() {
        assertFalse(ReaderSavedPromoPolicy.classify(chatCard.lines(), false))
    }
    @Test fun meaningfulDialogueAfterTheChatCardKeepsTheWholeMixedImageVisible() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(chatCard + "\nI will not abandon the villagers at the gate. " +
            "Take the children home while I keep the army away from the bridge."))
    }
}
