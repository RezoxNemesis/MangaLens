package com.mangalens.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Only the admitted APK tools can override the wrapper's legacy executable location. */
internal object NativeOwnedExtractorTools {
    fun configure(context: Context, request: YoutubeDLRequest, session: MediaResolutionSession) =
        configureWithInitializer(request, session) { NativeOriginalMediaRuntime.initialize(context.applicationContext, it) }

    /** The injected initializer exercises admission/cancellation ordering without Android loading. */
    internal fun configureWithInitializer(request: YoutubeDLRequest, session: MediaResolutionSession,
                                         initialize: (MediaResolutionSession) -> NativeOriginalMediaInstallation) {
        session.checkActive()
        val installed = initialize(session)
        session.checkActive()
        val ffmpeg = File(installed.binaryDirectory, NativeMediaTool.FFMPEG.fileName).absolutePath
        // Wrapper 0.18.1 adds its legacy location inside execute(). buildCommand emits
        // normal options before these raw arguments, so the verified APK location wins.
        request.addCommands(listOf("--ffmpeg-location", ffmpeg))
        session.checkActive()
    }
}
