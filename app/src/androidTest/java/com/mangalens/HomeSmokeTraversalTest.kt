package com.mangalens

import org.junit.Assert.*
import org.junit.Test

/** Recorded CP8 viewport geometry plus controlled follow-up frames; not Android gesture acceptance. */
class HomeSmokeTraversalTest {
    private val recent = "Home section: Recent Manga"
    private val continuing = "Home section: Continue Reading"
    private val row = UiActionBounds(16, 258, 304, 310)

    @Test fun actualRecentFrameWaitsForTheBelowFoldContinueItem() {
        val probe = HomeModuleOrderProbe(recent, continuing)
        // Exact CP8 failed frame: the viewport ends at y525 and Recent ends at y520.
        assertEquals(HomeOrderObservation.SEEKING, probe.observe(true,
            listOf(HomeSectionSample(recent, UiActionBounds(16, 258, 304, 520)))))
        // Controlled next viewport after physical scrolling: only the later item remains.
        assertEquals(HomeOrderObservation.IN_ORDER, probe.observe(false,
            listOf(HomeSectionSample(continuing, UiActionBounds(16, 100, 304, 229)))))
    }

    @Test fun aRestoredMidListFrameCannotPretendTheFirstItemWasObserved() {
        val probe = HomeModuleOrderProbe(recent, continuing)
        val both = listOf(HomeSectionSample(recent, UiActionBounds(16, 60, 304, 200)),
            HomeSectionSample(continuing, UiActionBounds(16, 208, 304, 337)))
        assertEquals(HomeOrderObservation.SEEKING, probe.observe(false, both))
        assertEquals(HomeOrderObservation.IN_ORDER, probe.observe(true, both))
    }

    @Test fun actualReversePlacementFailsEvenIfPreferencesReportTheRequestedOrder() {
        val probe = HomeModuleOrderProbe(recent, continuing)
        assertEquals(HomeOrderObservation.REVERSED, probe.observe(true,
            listOf(HomeSectionSample(continuing, UiActionBounds(16, 100, 304, 229)),
                HomeSectionSample(recent, UiActionBounds(16, 237, 304, 499)))))
    }

    @Test fun theRecordedRightEdgeFragmentCannotAdmitAWholeShortcutTap() {
        // Actual CP8 partial Web edge was x285..304. A clipped clickable tile is also rejected.
        assertFalse(homeShortcutFullyVisible(UiActionBounds(285, 258, 304, 310), row, 196))
        assertFalse(homeShortcutFullyVisible(UiActionBounds(16, 258, 112, 310), row, 196))
        assertFalse(homeShortcutFullyVisible(UiActionBounds(8, 258, 204, 310), row, 196))
    }

    @Test fun theCompleteLastTileCanTouchTheRowEndWithoutBeingRejected() {
        assertTrue(homeShortcutFullyVisible(UiActionBounds(108, 258, 304, 310), row, 196))
        assertFalse(homeShortcutFullyVisible(null, row, 196))
        assertFalse(homeShortcutFullyVisible(UiActionBounds(108, 250, 304, 310), row, 196))
    }

    @Test fun slowButUsefulProgressCanContinueBeyondSixGesturesWithinTheOriginalDeadline() {
        val search = HomeShortcutSearch(15_000)
        repeat(10) { assertTrue(search.canContinue(it * 500L)) }
        assertFalse(search.canContinue(15_000))
        assertFalse(search.canContinue(15_001))
    }

    @Test fun theDeadlineDoesNotResetWhenTheShortcutFinallyBecomesVisible() {
        val search = HomeShortcutSearch(1_000)
        assertTrue(search.canContinue(900))
        assertFalse(search.canContinue(1_000))
        assertFalse(search.canContinue(1_100))
    }
}
