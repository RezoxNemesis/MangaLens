package com.mangalens.ui.components

import org.junit.Assert.*
import org.junit.Test

class BrandHeaderLayoutTest {
    @Test fun narrowHomeKeepsActionsBelowTheWholeBrand() {
        assertTrue(brandHeaderStacksActions(288, 184, 144, 12))
    }
    @Test fun largerFontKeepsMeasuredBrandAboveActions() {
        assertTrue(brandHeaderStacksActions(288, 286, 144, 12))
    }
    @Test fun wideAndExactFitHeadersKeepTheExistingInlinePlacement() {
        assertFalse(brandHeaderStacksActions(600, 184, 144, 12))
        assertFalse(brandHeaderStacksActions(340, 184, 144, 12))
    }
    @Test fun emptyActionsNeverAddAnotherRow() {
        assertFalse(brandHeaderStacksActions(288, 288, 0, 12))
    }
}
