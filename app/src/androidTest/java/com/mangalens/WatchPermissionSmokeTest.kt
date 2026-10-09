package com.mangalens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class WatchPermissionSmokeTest {
    @Test fun deniedMediaAccessKeepsWatchUsableAndPickerCancellationReturnsToWatch() = coreScreenSmoke("watch-permission") {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        assertEquals("Acceptance runner must start this QA case without collection access", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, permission))
        launchHome()
        tap(By.desc("Watch videos"))
        node(By.text("Choose a file with Picker, or allow access to browse your video collection."))
        tap(By.text("Browse device videos"))
        tap(By.res(Pattern.compile(".*:id/permission_deny_button")))
        waitFor("Denial unexpectedly granted collection access") {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_DENIED
        }
        node(By.text("Browse device videos"))
        capture("permission-denied")
        tap(By.text("Picker"))
        node(By.pkg(Pattern.compile(".*documentsui.*")))
        capture("explicit-system-picker")
        device.pressBack()
        node(By.text("DEVICE VIDEOS • YOUR COLLECTION"))
        assertEquals("Picker cancellation changed media permission", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, permission))
        tap(By.desc("Library"))
        node(By.text("My Library"))
        tap(By.desc("Watch videos"))
        node(By.text("Browse device videos"))
        capture("watch-after-denial-and-picker-cancel")
    }
}
