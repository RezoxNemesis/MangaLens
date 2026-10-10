package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class RecentVideoWidgetEntryAuthorityTest {
    @Test fun pauseThenImmediateResumeCannotReviveHeldOpenOrFailureTicket() {
        val owner = RecentVideoWidgetEntryAuthority(); val held = owner.capture()
        owner.retire() // ON_PAUSE; ON_RESUME does not change this epoch back.
        assertFalse(owner.isCurrent(held)); assertTrue(owner.isCurrent(owner.capture()))
    }
    @Test fun repeatedRetirementRejectsEveryOlderAttempt() {
        val owner = RecentVideoWidgetEntryAuthority(); val first = owner.capture(); owner.retire()
        val second = owner.capture(); owner.retire()
        assertFalse(owner.isCurrent(first)); assertFalse(owner.isCurrent(second)); assertTrue(owner.isCurrent(owner.capture()))
    }
    @Test fun DisposalCannotBeUndoneByRetryOrNewCapture() {
        val owner = RecentVideoWidgetEntryAuthority(); val held = owner.capture(); owner.close()
        assertFalse(owner.isCurrent(held)); assertFalse(owner.isCurrent(owner.capture()))
    }
    @Test fun SameEpochInAnotherRouteIsNotTheCapturedOwner() {
        val one = RecentVideoWidgetEntryAuthority(); val two = RecentVideoWidgetEntryAuthority()
        assertFalse(two.isCurrent(one.capture()))
    }
}
