package com.mangalens

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual second-package launch and independent private data, with both APKs required by CI. */
@RunWith(AndroidJUnit4::class)
class SelfTestPackageCoexistenceTest {
    @Test fun selfTestLaunchAndSettingsKeepTheExistingInstallAndItsPrivateData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Separate self-test APK was not requested by this acceptance invocation",
            InstrumentationRegistry.getArguments().getString("require_self_test") == "true")
        val context = instrumentation.targetContext
        val original = context.packageName
        val separate = "$original.selftest"
        val manager = context.packageManager
        val first = manager.getApplicationInfo(original, 0)
        val second = manager.getApplicationInfo(separate, 0)
        assertNotEquals("Preview must not share the existing app's UID", first.uid, second.uid)
        assertNotEquals(first.dataDir, second.dataDir)
        assertEquals(original, manager.resolveContentProvider("$original.downloads", 0)?.packageName)
        assertEquals(separate, manager.resolveContentProvider("$separate.downloads", 0)?.packageName)
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "qa-selftest-coexistence-$id").apply { writeText(id) }
        val preferences = context.getSharedPreferences("qa-selftest-coexistence", 0)
        assertTrue(preferences.edit().putString(id, id).commit())
        val device = UiDevice.getInstance(instrumentation)
        try {
            device.executeShellCommand("am start -W -n $separate/com.mangalens.MainActivity")
            assertNotNull("Self-test did not render its real Home", device.wait(
                Until.findObject(By.desc("Settings and protection").pkg(separate)), 15_000))
            clickSettledUi(device, By.desc("Settings and protection").pkg(separate), 10_000)
            assertNotNull("Self-test Settings did not open", device.wait(
                Until.findObject(By.text("Settings").pkg(separate)), 10_000))
            assertEquals(id, file.readText())
            assertEquals(id, preferences.getString(id, null))
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            assertNotNull("Existing install did not remain launchable", device.wait(
                Until.findObject(By.desc("Settings and protection").pkg(original)), 15_000))
            assertEquals(id, file.readText())
            assertEquals(id, preferences.getString(id, null))
        } finally {
            device.executeShellCommand("am force-stop $separate")
            preferences.edit().remove(id).commit()
            file.delete()
        }
    }
}
