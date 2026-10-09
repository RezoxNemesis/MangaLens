package com.mangalens

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.core.compute.*
import com.mangalens.ui.settings.DeviceResourceCard
import com.mangalens.ui.theme.MangaLensTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real Settings/card and admission, with explicitly controlled signals/clock; no physical sensor claim. */
@RunWith(AndroidJUnit4::class)
class ResourceGovernorAcceptanceTest {
    @Test fun actualSettingsExposesDeviceResourcesWithoutChangingPreferences() = fixture { device, _ ->
        clickSettledUi(device, By.desc("Settings and protection"), timeoutMs = 10_000)
        assertNotNull(device.wait(Until.findObject(By.text("Settings")), 10_000))
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (device.findObject(By.text("Device resources")) == null && SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .5f)
                ?: throw AssertionError("Settings did not expose its real scrolling content")
        }
        assertNotNull("Settings resource card was not reachable", device.findObject(By.text("Device resources")))
        assertTrue(device.takeScreenshot(screenshot("settings-device-resources")))
    }

    @Test fun controlledCriticalMemoryShowsWaitingRejectsEffectsAndAllowsSerializedCleanupThenRecovers() = fixture { device, scenario ->
        val now = AtomicLong()
        val governor = ResourceGovernor(clockMs = now::get, recoveryMs = 1_000, backgroundRestMs = 2_000)
        val admission = NativeComputeAdmission(pollMs = 5, governor = governor)
        governor.update(ResourceSignals(memory = MemoryPressure.CRITICAL, batteryPercent = 8, charging = false))
        scenario.onActivity { it.setContent { MangaLensTheme { DeviceResourceCard(governor) } } }
        node(device, "Background AI waiting")
        node(device, "Thermal report unavailable")
        node(device, "Battery 8% · Unplugged")
        assertTrue(device.takeScreenshot(screenshot("controlled-critical-memory")))
        runBlocking {
            val effect = AtomicBoolean()
            try {
                admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { true }) { effect.set(true) }
                fail("A critical report allowed new computation")
            } catch (expected: ResourcePausedException) { assertTrue(expected.message.orEmpty().contains("memory")) }
            assertFalse(effect.get())
            val cleanup = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND, ResourceWorkKind.CLEANUP) { true }!!
            val queued = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val secondCleanup = async(Dispatchers.IO) {
                val lease = admission.acquire(NativeComputeAdmission.Priority.LIVE, ResourceWorkKind.CLEANUP) { queued.countDown(); true }!!
                try { effect.set(true); cleanupEntered.countDown() } finally { lease.close() }
            }
            try {
                assertTrue(queued.await(2, TimeUnit.SECONDS))
                assertFalse("Cleanup overlapped its still-active peer", cleanupEntered.await(100, TimeUnit.MILLISECONDS))
                cleanup.close()
                withTimeout(2_000) { secondCleanup.await() }
                assertTrue(effect.get())
            } finally { cleanup.close(); secondCleanup.cancelAndJoin() }
            governor.update(ResourceSignals(memory = MemoryPressure.NORMAL, charging = true))
            now.set(999)
            assertEquals(ResourcePressure.CRITICAL, governor.snapshot().pressure)
            node(device, "Background AI waiting")
            now.set(1_000)
            node(device, "Background AI ready")
            effect.set(false)
            admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { true }) { effect.set(true) }
            assertTrue("Recovered computation was permanently stranded", effect.get())
        }
        assertTrue(device.takeScreenshot(screenshot("controlled-memory-recovered")))
    }

    @Test fun controlledLowBatteryDelaysBackgroundWhileLiveProgressesAndChargingRecovers() = fixture { device, scenario ->
        val now = AtomicLong()
        val governor = ResourceGovernor(clockMs = now::get, recoveryMs = 1_000, backgroundRestMs = 2_000)
        val admission = NativeComputeAdmission(pollMs = 5, governor = governor)
        governor.update(ResourceSignals(batteryPercent = 8, charging = false))
        scenario.onActivity { it.setContent { MangaLensTheme { DeviceResourceCard(governor) } } }
        node(device, "Background AI taking short breaks")
        runBlocking {
            val queued = CountDownLatch(1)
            val backgroundEntered = AtomicBoolean()
            val background = async(Dispatchers.IO) {
                admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { queued.countDown(); true }) { backgroundEntered.set(true) }
            }
            try {
                assertTrue(queued.await(2, TimeUnit.SECONDS))
                val liveEntered = AtomicBoolean()
                admission.withLease(NativeComputeAdmission.Priority.LIVE, { true }) { liveEntered.set(true) }
                assertTrue("Resource-delayed background blocked live progress", liveEntered.get())
                assertFalse(backgroundEntered.get())
                now.set(2_000)
                withTimeout(2_000) { background.await() }
                assertTrue(backgroundEntered.get())
            } finally { background.cancelAndJoin() }
        }
        assertTrue(device.takeScreenshot(screenshot("controlled-low-battery-live-progress")))
        governor.update(ResourceSignals(charging = true))
        now.set(2_999)
        assertEquals(ResourcePressure.ELEVATED, governor.snapshot().pressure)
        now.set(3_000)
        node(device, "Background AI ready")
        node(device, "Battery 8% · Charging")
    }

    private fun fixture(check: (UiDevice, ActivityScenario<MainActivity>) -> Unit) {
        val config = Configurator.getInstance()
        val previous = config.waitForIdleTimeout
        config.setWaitForIdleTimeout(100)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()), scenario)
            }
        } finally { config.setWaitForIdleTimeout(previous) }
    }

    private fun node(device: UiDevice, text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 5_000)) {
        "Expected actual resource status: $text"
    }
    private fun screenshot(label: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir("qa"), "resource-governor-$label.png")
    }
}
