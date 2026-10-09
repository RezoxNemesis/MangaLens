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
}
