package com.mangalens.download

import android.content.Context
import com.mangalens.R
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/** One process-wide gate, used by both site resolution and Orez video discovery. */
internal object BundledYtDlpRuntime {
    const val VERSION = "2026.08.19"
    const val SIZE_BYTES = 3_072_469L
    const val SHA256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
    @Volatile private var ready = false
    private val installer = BundledExtractorInstaller(SIZE_BYTES, SHA256)

    fun initialize(context: Context, checkActive: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Site extractor initialization cancelled")
    }) {
        checkActive()
        if (ready) return
        // Share the library's init monitor so no caller can start its init while the bundled
        // archive is being activated. All production native execution enters through this gate.
        synchronized(YoutubeDL) {
            checkActive()
            if (!ready) {
                val app = context.applicationContext
                installer.install(installedFile(app), { app.resources.openRawResource(R.raw.ytdlp) }, checkActive)
                checkActive()
                YoutubeDL.init(app)
                checkActive()
                ready = true
            }
        }
    }

    fun installedFile(context: Context): File = File(
        File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName),
        YoutubeDL.ytdlpBin
    )
}
