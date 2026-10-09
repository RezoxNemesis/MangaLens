package com.mangalens.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderHudLayoutTest {
    @Test fun installedSmallScreenCannotPaintTheExpandedToolsOverItsPageHeader() {
        // API35 failure: actual mode+callback correct, expanded520px card covered header; Page1/3 absent from XML.
        assertEquals(428f, readerHudToolsHeight(568f, 68f, 36f), 0f)
        assertTrue(readerHudToolsHeight(568f, 68f, 36f) + 68f + 36f + 36f <= 568f)
    }
    @Test fun largerFontAndLandscapeReserveActualHeaderAndNavigationSpace() {
        assertEquals(146f, readerHudToolsHeight(320f, 100f, 38f), 0f)
        assertEquals(700f, readerHudToolsHeight(900f, 120f, 44f), 0f)
    }
    @Test fun zeroAndTemporarilyUnavailableViewportsHaveNoNegativeOrNonfiniteConstraint() {
        assertEquals(0f, readerHudToolsHeight(0f, 80f, 36f), 0f)
        assertEquals(0f, readerHudToolsHeight(100f, 100f, 36f), 0f)
        assertEquals(0f, readerHudToolsHeight(Float.NaN, 80f, 36f), 0f)
    }
    @Test fun headerChangesAlwaysReduceTheAvailableToolArea() {
        assertTrue(readerHudToolsHeight(568f, 140f, 36f) < readerHudToolsHeight(568f, 68f, 36f))
    }
}
