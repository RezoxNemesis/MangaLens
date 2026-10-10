package com.mangalens.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Authored only. This packet's tests have not been compiled or executed. */
class ReaderWindowLeasePolicyTest {
    @Test fun defaultControlsDoNotReadOrWriteWindowProperties() {
        val host = RecordingHost(orientation = 5, brightness = -1f, awake = true)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings())
        lease.update(ReaderWindowSettings())
        lease.deactivate()
        lease.deactivate()
        assertEquals(0, host.reads)
        assertTrue(host.writes.isEmpty())
        assertEquals(5, host.orientationValue)
        assertEquals(-1f, host.brightnessValue, 0f)
        assertTrue(host.awakeValue)
    }

    @Test fun explicitControlsRestoreTheirExactPreviousValues() {
        val host = RecordingHost(orientation = 5, brightness = -1f, awake = false)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.LANDSCAPE, true, .37f, true))
        assertEquals(0, host.orientationValue)
        assertEquals(.37f, host.brightnessValue, 0f)
        assertTrue(host.awakeValue)
        lease.deactivate()
        assertEquals(5, host.orientationValue)
        assertEquals(-1f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
    }

    @Test fun orientationLockWithoutAnExplicitAxisLocksTheCurrentDeviceOrientation() {
        val host = RecordingHost(orientation = -1)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(rotationLocked = true))
        assertEquals(14, host.orientationValue)
        lease.deactivate()
        assertEquals(-1, host.orientationValue)
    }

    @Test fun explicitPortraitCanKeepSensorRotationOrLockItsAxis() {
        val host = RecordingHost(orientation = 5)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT))
        assertEquals(7, host.orientationValue)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, rotationLocked = true))
        assertEquals(1, host.orientationValue)
        lease.deactivate()
        assertEquals(5, host.orientationValue)
    }

    @Test fun explicitLandscapeCanKeepSensorRotationOrLockItsAxis() {
        val host = RecordingHost(orientation = 5)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.LANDSCAPE))
        assertEquals(6, host.orientationValue)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.LANDSCAPE, rotationLocked = true))
        assertEquals(0, host.orientationValue)
        lease.deactivate()
        assertEquals(5, host.orientationValue)
    }

    @Test fun enablingKeepAwakeDoesNotTakeOwnershipOfAnAlreadyAwakeWindow() {
        val host = RecordingHost(awake = true)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(keepScreenOn = true))
        assertTrue(host.writes.isEmpty())
        host.externalAwake(false)
        lease.deactivate()
        assertFalse(host.awakeValue)
        assertTrue(host.writes.isEmpty())
    }

    @Test fun removingBrightnessOverrideRestoresSystemBrightnessWithoutTouchingOtherFields() {
        val host = RecordingHost(orientation = 3, brightness = -1f, awake = true)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(brightnessOverride = .8f))
        lease.update(ReaderWindowSettings())
        assertEquals(-1f, host.brightnessValue, 0f)
        assertEquals(3, host.orientationValue)
        assertTrue(host.awakeValue)
        assertEquals(listOf("brightness" to .8f, "brightness" to -1f), host.writes)
    }

    @Test fun repeatedSettingsDoNotReassertValuesChangedByAnotherOwner() {
        val host = RecordingHost()
        val lease = ReaderWindowLeasePolicy(host)
        val settings = ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, false, .8f, true)
        lease.update(settings)
        val readsBefore = host.reads
        val writesBefore = host.writes.size
        host.externalOrientation(6)
        host.externalBrightness(.2f)
        host.externalAwake(false)
        lease.update(settings)
        assertEquals(readsBefore, host.reads)
        assertEquals(writesBefore, host.writes.size)
        assertEquals(6, host.orientationValue)
        assertEquals(.2f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
    }

    @Test fun deactivationDoesNotOverwriteLaterOrientationOwnership() {
        val host = RecordingHost(orientation = 5)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT))
        host.externalOrientation(6)
        lease.deactivate()
        assertEquals(6, host.orientationValue)
        assertEquals(listOf("orientation" to 7), host.writes)
    }

    @Test fun deactivationDoesNotOverwriteLaterBrightnessOwnership() {
        val host = RecordingHost(brightness = -1f)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(brightnessOverride = .8f))
        host.externalBrightness(.2f)
        lease.deactivate()
        assertEquals(.2f, host.brightnessValue, 0f)
        assertEquals(listOf("brightness" to .8f), host.writes)
    }

    @Test fun deactivationDoesNotOverwriteLaterKeepAwakeOwnership() {
        val host = RecordingHost(awake = false)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(keepScreenOn = true))
        host.externalAwake(false)
        lease.deactivate()
        assertFalse(host.awakeValue)
        assertEquals(listOf("awake" to true), host.writes)
    }

    @Test fun lostOwnershipOfOneFieldDoesNotPreventRestoringOtherFields() {
        val host = RecordingHost(orientation = 5, brightness = -1f, awake = false)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, false, .8f, true))
        host.externalOrientation(6)
        lease.deactivate()
        assertEquals(6, host.orientationValue)
        assertEquals(-1f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
    }

    @Test fun changesDuringAnOwnedSessionKeepTheOriginalRestoreValue() {
        val host = RecordingHost(brightness = .23f)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(brightnessOverride = .4f))
        lease.update(ReaderWindowSettings(brightnessOverride = .9f))
        lease.deactivate()
        assertEquals(.23f, host.brightnessValue, 0f)
    }

    @Test fun aNewExplicitValueAfterOwnershipLossRestoresTheNewlyObservedBaseline() {
        val host = RecordingHost(brightness = -1f)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(brightnessOverride = .8f))
        host.externalBrightness(.2f)
        lease.update(ReaderWindowSettings(brightnessOverride = .6f))
        assertEquals(.6f, host.brightnessValue, 0f)
        lease.deactivate()
        assertEquals(.2f, host.brightnessValue, 0f)
    }

    @Test fun returningToDefaultsAfterOwnershipLossPreservesTheLaterOwnersValues() {
        val host = RecordingHost(orientation = 5, brightness = -1f, awake = false)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, false, .8f, true))
        host.externalOrientation(6)
        host.externalBrightness(.2f)
        host.externalAwake(false)
        val writesBefore = host.writes.size
        lease.update(ReaderWindowSettings())
        lease.deactivate()
        assertEquals(writesBefore, host.writes.size)
        assertEquals(6, host.orientationValue)
        assertEquals(.2f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
    }

    @Test fun deactivatedLeaseCanCaptureAResumedWindowsNewBaseline() {
        val host = RecordingHost(brightness = -1f)
        val lease = ReaderWindowLeasePolicy(host)
        val settings = ReaderWindowSettings(brightnessOverride = .8f)
        lease.update(settings)
        lease.deactivate()
        host.externalBrightness(.3f)
        lease.update(settings)
        lease.deactivate()
        assertEquals(.3f, host.brightnessValue, 0f)
    }

    @Test fun controlsAlreadyAtTheirDesiredValuesDoNotAcquireRestoreOwnership() {
        val host = RecordingHost(orientation = 7, brightness = .8f, awake = true)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, false, .8f, true))
        assertTrue(host.writes.isEmpty())
        host.externalOrientation(6)
        host.externalBrightness(.2f)
        host.externalAwake(false)
        lease.deactivate()
        assertTrue(host.writes.isEmpty())
        assertEquals(6, host.orientationValue)
        assertEquals(.2f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
    }

    @Test fun oneFailedRestoreStillReleasesTheOtherOwnedFields() {
        val host = RecordingHost(orientation = 5, brightness = -1f, awake = false)
        val lease = ReaderWindowLeasePolicy(host)
        lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT, false, .8f, true))
        host.failOrientationWrites = true
        var failed = false
        try {
            lease.deactivate()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(-1f, host.brightnessValue, 0f)
        assertFalse(host.awakeValue)
        host.failOrientationWrites = false
        val writesBefore = host.writes.size
        lease.deactivate()
        assertEquals(writesBefore, host.writes.size)
    }

    @Test fun invalidBrightnessIsRejectedBeforeAnyWindowUpdate() {
        for (brightness in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            var rejected = false
            try {
                ReaderWindowSettings(brightnessOverride = brightness)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Invalid brightness: $brightness", rejected)
        }
        assertEquals(0f, ReaderWindowSettings(brightnessOverride = 0f).brightnessOverride!!, 0f)
        assertEquals(1f, ReaderWindowSettings(brightnessOverride = 1f).brightnessOverride!!, 0f)
    }

    private class RecordingHost(
        orientation: Int = -1,
        brightness: Float = -1f,
        awake: Boolean = false
    ) : ReaderWindowHost {
        var orientationValue = orientation
            private set
        var brightnessValue = brightness
            private set
        var awakeValue = awake
            private set
        var reads = 0
            private set
        val writes = mutableListOf<Pair<String, Any>>()
        var failOrientationWrites = false
        override var requestedOrientation: Int
            get() { reads++; return orientationValue }
            set(value) {
                check(!failOrientationWrites) { "Orientation host rejected the write" }
                writes += "orientation" to value
                orientationValue = value
            }
        override var screenBrightness: Float
            get() { reads++; return brightnessValue }
            set(value) { writes += "brightness" to value; brightnessValue = value }
        override var keepScreenOn: Boolean
            get() { reads++; return awakeValue }
            set(value) { writes += "awake" to value; awakeValue = value }
        fun externalOrientation(value: Int) { orientationValue = value }
        fun externalBrightness(value: Float) { brightnessValue = value }
        fun externalAwake(value: Boolean) { awakeValue = value }
    }
}
