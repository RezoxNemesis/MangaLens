package com.mangalens

import android.content.Context
import com.mangalens.download.AndroidNativeExtractorNetworking
import com.mangalens.download.MediaProcessGuard
import com.mangalens.download.MediaResolutionRunner
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.UUID

internal data class ProviderFormatOffer(val height: Int, val width: Int, val extension: String, val separateVideo: Boolean)

internal object ProviderNativeFormatsProbe {
    fun offeredFormats(context: Context, fixture: ProviderFixture): List<ProviderFormatOffer> = runBlocking {
        MediaResolutionRunner.run(45_000L) { session ->
            val request = YoutubeDLRequest(fixture.source).apply {
                addOption("--ignore-config"); addOption("--no-plugin-dirs"); addOption("--no-remote-components")
                addOption("--no-geo-bypass"); addOption("--no-playlist"); addOption("--skip-download")
                addOption("--socket-timeout", "8"); addOption("--retries", "0"); addOption("--extractor-retries", "0")
                // Stock output-template projection prevents URLs, headers and descriptions in stdout.
                addOption("--print", "%(formats.:.{height,width,ext,vcodec,acodec,has_drm})j")
                addOption("--no-warnings")
            }
            AndroidNativeExtractorNetworking.configure(context, request, fixture.source, session::checkActive)
            val id = "qa-format-offers-${UUID.randomUUID()}"
            val guard = MediaProcessGuard(session, id, session.remainingMillis(), YoutubeDL::destroyProcessById)
            try {
                session.checkActive()
                val output = YoutubeDL.execute(request, processId = id, callback = null).out
                session.checkActive()
                check(output.length <= 2_000_000) { "Provider format inventory exceeded the probe limit" }
                val rows = JSONArray(output)
                (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index) ?: return@mapNotNull null
                    if (row.optBoolean("has_drm") || row.optString("vcodec") == "none") return@mapNotNull null
                    val height = row.optInt("height", 0)
                    if (height !in 1..16_384) return@mapNotNull null
                    val extension = row.optString("ext").takeIf { it in setOf("mp4", "webm", "mov", "mkv") }
                        ?: return@mapNotNull null
                    ProviderFormatOffer(height, row.optInt("width", 0), extension, row.optString("acodec") == "none")
                }
            } finally { guard.close() }
        }
    }
}
