package com.mangalens

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.ui.ai.voice.OrezOfflineVoiceRuntime
import com.mangalens.ui.ai.voice.OrezVoicePhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored UNRUN. Real UI permission boundary; no transcript/native-quality claim. */
@RunWith(AndroidJUnit4::class)
class OrezOfflineVoiceUiTest {
    @Test fun openingOrezAndDecliningExplicitMicrophoneTapNeverCapturesOrSends() = coreScreenSmoke("orez-offline-voice-permission") {
        val permission = Manifest.permission.RECORD_AUDIO
        val originallyGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        try {
            device.executeShellCommand("pm revoke ${context.packageName} $permission")
            launchHome()
            val runtime = OrezOfflineVoiceRuntime.get(context)
            val original = runtime.state.value
            assertEquals("This control needs an idle isolated QA app", OrezVoicePhase.IDLE, original.phase)
            val messagesBefore = runBlocking { OrezRoomDatabase.get(context).messages().observe().first().map { it.id } }
            tapSettled(By.desc("Orez AI")); node(By.text("Orez AI"))
            node(By.desc("Record offline voice input"), 5_000)
            assertEquals(original, runtime.state.value)
            val draft = node(By.clazz("android.widget.EditText").pkg(context.packageName))
            draft.text = "Review this draft before sending"
            tapSettled(By.desc("Record offline voice input"), 5_000)
            fun denyButton() = device.findObject(By.res("com.android.permissioncontroller", "permission_deny_button"))
                ?: device.findObject(By.res("com.android.packageinstaller", "permission_deny_button"))
            waitFor("Android microphone consent must be requested by the actual tap", 5_000) { denyButton() != null }
            requireNotNull(denyButton()).click()
            node(By.text("Microphone permission was declined. No audio was captured."), 5_000)
            assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, permission))
            assertEquals(original, runtime.state.value)
            assertEquals("Review this draft before sending", node(By.clazz("android.widget.EditText").pkg(context.packageName)).text)
            assertEquals(messagesBefore, runBlocking { OrezRoomDatabase.get(context).messages().observe().first().map { it.id } })
            tapSettled(By.text("Speech models"), 5_000)
            node(By.text("Offline speech models"), 5_000)
            node(By.text("Download model (74 MiB)"), 5_000)
            assertEquals("Opening model controls must not start a microphone", original, runtime.state.value)
        } finally {
            if (originallyGranted) device.executeShellCommand("pm grant ${context.packageName} $permission")
            device.pressBack()
        }
    }
}
