package com.mangalens

import org.junit.Assert.*
import org.junit.Test

class HomePhysicalSwipeTest {
    @Test fun actualClippedQuickRowUsesAnInteriorFingerSwipeWithoutScrollEventWait() {
        val bounds = UiActionBounds(28, 392, 692, 484)
        val swipe = homePhysicalSwipe(bounds, HomeSwipeDirection.RIGHT, 1200, 15000)!!
        assertTrue(swipe.startX > swipe.endX)
        assertEquals(438, swipe.startY); assertEquals(438, swipe.endY)
        assertTrue(swipe.startX < bounds.right && swipe.endX > bounds.left)
        assertEquals(250L, swipe.plannedMs)
    }
    @Test fun eachVerticalDirectionTraversesTheActualContainerInTheCorrectDirection() {
        val bounds = UiActionBounds(0, 200, 720, 1300)
        val down = homePhysicalSwipe(bounds, HomeSwipeDirection.DOWN, 1200, 15000)!!
        val up = homePhysicalSwipe(bounds, HomeSwipeDirection.UP, 1200, 15000)!!
        assertTrue(down.startY > down.endY); assertTrue(up.startY < up.endY)
        assertEquals(down.startX, down.endX); assertEquals(up.startX, up.endX)
        assertTrue(down.startY < bounds.bottom && down.endY > bounds.top)
        assertTrue(up.startY > bounds.top && up.endY < bounds.bottom)
    }
    @Test fun injectionCannotStartAfterTheOriginalActionBudgetIsExhausted() {
        val bounds = UiActionBounds(28, 392, 692, 484)
        assertNull(homePhysicalSwipe(bounds, HomeSwipeDirection.RIGHT, 1200, 450))
        assertNotNull(homePhysicalSwipe(bounds, HomeSwipeDirection.RIGHT, 1200, 451))
    }
    @Test fun missingOrTinyContainersNeverReceiveAnUnscopedGesture() {
        assertNull(homePhysicalSwipe(UiActionBounds(0, 0, 0, 0), HomeSwipeDirection.DOWN, 1200, 15000))
        assertNull(homePhysicalSwipe(UiActionBounds(0, 0, 100, 2), HomeSwipeDirection.RIGHT, 1200, 15000))
        assertNull(homePhysicalSwipe(UiActionBounds(0, 0, 100, 50), HomeSwipeDirection.RIGHT, 0, 15000))
    }
    @Test fun longContainersKeepThePhysicalInjectionBounded() {
        val swipe = homePhysicalSwipe(UiActionBounds(0, 0, 1000, 100000), HomeSwipeDirection.DOWN, 1, 15000)!!
        assertEquals(100, swipe.steps); assertEquals(500L, swipe.plannedMs)
    }
}
