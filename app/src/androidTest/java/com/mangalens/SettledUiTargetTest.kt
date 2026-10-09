package com.mangalens

import org.junit.Assert.*
import org.junit.Test

/** Actual API35 address-dialog bounds; host tests do not claim Android touch dispatch. */
class SettledUiTargetTest {
    private val rejectedAddress = UiActionBounds(238, 366, 296, 414)
    private val validAddress = UiActionBounds(238, 352, 296, 400)

    @Test fun aFourMillisecondAddressRelayoutCannotAdmitTheOldOpenTarget() {
        val gate = SettledUiTarget()
        assertFalse(gate.observe(rejectedAddress, ready = true, nowMs = 0))
        assertFalse(gate.observe(rejectedAddress, ready = true, nowMs = 4))
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 16))
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 115))
        assertTrue(gate.observe(validAddress, ready = true, nowMs = 116))
    }

    @Test fun inputOrValidationMismatchResetsAnOtherwiseSettledTarget() {
        val gate = SettledUiTarget()
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 0))
        assertTrue(gate.observe(validAddress, ready = true, nowMs = 100))
        assertFalse(gate.observe(validAddress, ready = false, nowMs = 101))
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 102))
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 201))
        assertTrue(gate.observe(validAddress, ready = true, nowMs = 202))
    }

    @Test fun hiddenOrRemovedControlsCannotInheritAnOldQuietWindow() {
        val gate = SettledUiTarget()
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 0))
        assertFalse(gate.observe(null, ready = true, nowMs = 100))
        assertFalse(gate.observe(UiActionBounds(0, 0, 0, 0), ready = true, nowMs = 150))
        assertFalse(gate.observe(validAddress, ready = true, nowMs = 151))
        assertTrue(gate.observe(validAddress, ready = true, nowMs = 251))
    }
}
