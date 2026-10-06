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
}
