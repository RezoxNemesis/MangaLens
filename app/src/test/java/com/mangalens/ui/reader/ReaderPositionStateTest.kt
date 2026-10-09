package com.mangalens.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderPositionStateTest {
    private fun settled(mode: String = "ltr", page: Int = 0, offset: Int = 0): ReaderPositionState {
        val initial = ReaderPositionState.initial(mode, page, offset)
        return initial.restored(initial, page, offset, 3)
    }

    @Test fun nextIsAcceptedBeforeTheScrollSuspends() {
        val next = settled().navigate(1, 3)
        assertEquals(1, next.page)
        assertTrue(next.restoring)
        assertNull(next.checkpoint())
    }

    @Test fun heldInitialRestorationCannotOverwriteAnAcceptedNext() {
        val held = ReaderPositionState.initial("ltr", 0, 0)
        val next = held.navigate(1, 3)
        assertEquals(next, next.restored(held, 0, 0, 3))
        assertEquals(1, next.page)
    }

    @Test fun directionChangeDuringNextPreservesTheAcceptedLogicalPage() {
        val held = settled().navigate(1, 3)
        val rtl = held.switchMode("rtl", 0, 0, 3)
        assertEquals(1, rtl.page)
        assertEquals("rtl", rtl.mode)
        assertTrue(rtl.restoring)
        assertEquals(rtl, rtl.restored(held, 0, 0, 3))
    }

    @Test fun selectingTheCurrentModeDoesNotDisableObservation() {
        val before = settled("vertical", 1, 18)
        assertEquals(before, before.switchMode("vertical", 1, 18, 3))
        assertNotNull(before.switchMode("vertical", 1, 18, 3).checkpoint())
    }

    @Test fun oldLayoutObservationCannotCrossARequestOrModeBoundary() {
        val old = settled()
        val rtlRequest = old.switchMode("rtl", 0, 0, 3)
        val rtl = rtlRequest.restored(rtlRequest, 0, 0, 3)
        assertEquals(rtl, rtl.observed(old.request, old.mode, 2, 0, 3))
        val pending = rtl.navigate(1, 3)
        assertEquals(pending, pending.observed(rtl.request, rtl.mode, 0, 0, 3))
    }

    @Test fun consecutiveNextCommandsAdvanceBeforeEitherAnimationFinishes() {
        val first = settled().navigate(1, 3)
        val second = first.navigate(first.page + 1, 3)
        assertEquals(2, second.page)
        assertEquals(second, second.restored(first, 1, 0, 3))
    }

    @Test fun viewportObservationUpdatesTheUiBeforeThePersistenceDebounce() {
        val before = settled("vertical")
        val observed = before.observed(before.request, before.mode, 1, 42, 3)
        assertEquals(1, observed.page)
        assertEquals(42, observed.offset)
        assertNotNull(observed.checkpoint())
    }

    @Test fun queuedPersistenceCannotWriteAnOldPageAfterNextOrModeChange() {
        val before = settled("vertical", 1, 27)
        val checkpoint = requireNotNull(before.checkpoint())
        assertTrue(before.acceptsCheckpoint(checkpoint))
        val next = before.navigate(2, 3)
        assertFalse(next.acceptsCheckpoint(checkpoint))
        val restored = next.restored(next, 2, 8, 3)
        assertFalse(restored.acceptsCheckpoint(checkpoint))
        val rtlRequest = before.switchMode("rtl", 1, 27, 3)
        val rtl = rtlRequest.restored(rtlRequest, 1, 0, 3)
        assertFalse(rtl.acceptsCheckpoint(checkpoint))
    }

    @Test fun completedScrollPublishesTheActualViewportWhenTheTailCannotAlign() {
        val target = settled("vertical").navigate(2, 3)
        val actual = target.restored(target, 1, 180, 3)
        assertFalse(actual.restoring)
        assertEquals(1, actual.page)
        assertEquals(180, actual.offset)
        assertTrue(actual.acceptsCheckpoint(requireNotNull(actual.checkpoint())))
    }

    @Test fun leavingVerticalCapturesItsLatestUndebouncedViewport() {
        val staleUi = settled("vertical", 0)
        val rtl = staleUi.switchMode("rtl", 1, 58, 3)
        assertEquals(1, rtl.page)
        assertEquals(0, rtl.offset)
    }

    @Test fun invalidPreferenceAndNegativeInitialPositionAreNormalized() {
        val state = ReaderPositionState.initial("bad-preference", -4, -10)
        assertEquals("vertical", state.mode)
        assertEquals(0, state.page)
        assertEquals(0, state.offset)
        assertTrue(state.restoring)
    }

    @Test fun validSavedVerticalOffsetSurvivesUntilRealRestoration() {
        val saved = ReaderPositionState.initial("vertical", 1, 123)
        assertEquals(123, saved.offset)
        assertNull(saved.checkpoint())
        val restored = saved.restored(saved, 1, 123, 3)
        assertEquals(123, restored.offset)
        assertNotNull(restored.checkpoint())
    }

    @Test fun pageCommandsAreBoundedAndAnEmptyChapterHasNoAcceptedCommand() {
        val before = settled()
        assertEquals(before, before.navigate(1, 0))
        assertEquals(0, before.navigate(-1, 3).page)
        assertEquals(2, before.navigate(100, 3).page)
        assertEquals(before, before.switchMode("unexpected", 0, 0, 3))
    }

    @Test fun returningToVerticalCannotAcceptTheTemporaryZeroHeightViewport() {
        val rtl = settled("rtl", 1)
        val vertical = rtl.switchMode("vertical", 1, 0, 3)
        val waiting = vertical.restored(vertical, 0, 0, 3, geometryChecked = false)
        assertEquals(vertical, waiting)
        assertEquals(1, waiting.page)
        assertTrue(waiting.restoring)
        assertNull(waiting.checkpoint())
        val complete = waiting.restored(vertical, 1, 0, 3, geometryChecked = true)
        assertEquals(1, complete.page)
        assertFalse(complete.restoring)
    }

    @Test fun coldSavedPageAndOffsetWaitForMetadataBeforeAcceptingLayout() {
        val saved = ReaderPositionState.initial("vertical", 2, 123)
        val waiting = saved.restored(saved, 0, 0, 4, geometryChecked = false)
        assertEquals(saved, waiting)
        assertEquals(123, waiting.offset)
        assertNull(waiting.checkpoint())
        val actual = waiting.restored(saved, 2, 123, 4, geometryChecked = true)
        assertEquals(2, actual.page)
        assertEquals(123, actual.offset)
    }

    @Test fun cancelledScrollBeforeGeometryCompletesDoesNotPublishAFalseCheckpoint() {
        val pending = settled("vertical", 0).navigate(1, 3)
        val cancelled = pending.restored(pending, 0, 0, 3, geometryChecked = false)
        assertTrue(cancelled.restoring)
        assertEquals(1, cancelled.page)
        assertNull(cancelled.checkpoint())
    }

    @Test fun checkedGeometryStillAcceptsTheRealTailClampAndKeepsGenerationFence() {
        val old = settled("vertical").navigate(2, 3)
        val current = old.navigate(1, 3)
        assertEquals(current, current.restored(old, 0, 0, 3, geometryChecked = true))
        val actual = old.restored(old, 1, 180, 3, geometryChecked = true)
        assertEquals(1, actual.page)
        assertEquals(180, actual.offset)
        assertFalse(actual.restoring)
    }
}
