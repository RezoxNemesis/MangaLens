package com.mangalens.qa

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.mangalens.ui.web.WebAudioCaptureService

/** Debug-only consent surface for instrumentation; never present in a release build. */
class PlaybackCaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 71)
    }
    @Deprecated("Used for debug-only capture acceptance test")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this,
                Intent(this, WebAudioCaptureService::class.java).putExtra("token", data))
        }
        finish()
    }
}
