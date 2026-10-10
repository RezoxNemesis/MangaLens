package com.mangalens.core.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPromoPolicyTest {
    @Test fun adFreeAnnouncementIsPromo() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo(
            "ANNOUNCEMENT Want to enjoy our website with no interruptions from ads? " +
                "Upgrade to our premium version. CLICK HERE TO READ AD FREE."
        ))
    }

    @Test fun scanGroupCreditCardIsPromo() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo(
            "Asura Scans Chapter 67 Join our Discord translator proofreader typesetter"
        ))
    }

    @Test fun ordinaryMangaDialogueIsNotPromo() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "It hurt a bit for me too, you know? I think it was my first time experiencing pain like that."
        ))
    }

    @Test fun storyAnnouncementAloneIsNotEnough() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "Announcement: the tournament begins tomorrow. Everyone should meet at the eastern gate."
        ))
    }

    @Test fun promotionalFooterDoesNotCollapseSubstantialStoryDialogue() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "I won't let you get away after everything you've done.\n" +
                "Go and protect the children! I will hold the gate until the others arrive.\n" +
                "Join our Discord discord.gg/scanteam to read ad free."
        ))
    }

    @Test fun flattenedOcrDialogueWithPremiumFooterIsNotPromo() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "You thought the king would forget what happened at the eastern gate? " +
                "We all remember how you saved the children and brought them home. " +
                "Upgrade to our premium version. CLICK HERE TO READ AD FREE."
        ))
    }

    @Test fun japaneseDialogueWithScanlationFooterIsNotPromo() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "ここで諦めるわけにはいかない。みんなが帰ってくるまで、私がこの門を守る。\n" +
                "あなたは子供たちを連れて逃げてください。もう一度会えると信じています。\n" +
                "Scanlation team credits: translator typesetter Join our Discord"
        ))
    }

    @Test fun temporaryDomainInStoryIsNotWebsitePromotion() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo(
            "The magician made a temporary domain around the castle. Nobody can enter before dawn."
        ))
    }

    @Test fun briefChapterLabelDoesNotHideRealPromotionalCard() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo(
            "Chapter 74\nANNOUNCEMENT! Join our Discord to read without ads. Upgrade to premium."
        ))
    }
    @Test fun commercialChatAndGameCardsRequireServiceAndCallToAction() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo("GirlfriendGPT\nMia Online Now\nAre you free tonight? Let's talk\nSTART CHATTING"))
        assertTrue(ReaderPromoPolicy.isLikelyPromo("PLAY AT VEYRAGAME.COM\nPlay now"))
        assertFalse(ReaderPromoPolicy.isLikelyPromo("Are you free tonight? Let's talk and walk back to the castle."))
        assertFalse(ReaderPromoPolicy.isLikelyPromo("The villagers asked everyone to please consider donating."))
    }
    @Test fun commercialFooterDoesNotHideSubstantialStory() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo("I remember the promise we made at the old gate.\n" +
            "Please protect the children while I lead the others across the river.\n" +
            "GirlfriendGPT START CHATTING"))
    }
    @Test fun scanGroupDonationRequiresServiceEvidenceAndKeepsDialogueGuard() {
        assertTrue(ReaderPromoPolicy.isLikelyPromo("Demonicscans.org\nIf you like our work please consider donating."))
        assertFalse(ReaderPromoPolicy.isLikelyPromo("MangaDemon"))
        assertFalse(ReaderPromoPolicy.isLikelyPromo("I won't leave anyone behind when the army arrives.\n" +
            "You must take the children to the village and wait until dawn.\nDemonicscans.org support our work"))
    }
    @Test fun brandFirstNarrativeKeepsItsSubstantialSuffixDialogue() {
        assertFalse(ReaderPromoPolicy.isLikelyPromo("GirlfriendGPT was the name we gave our old boat. " +
            "Start chatting with the gatekeeper and then lead all the children back to the castle."))
    }
}
