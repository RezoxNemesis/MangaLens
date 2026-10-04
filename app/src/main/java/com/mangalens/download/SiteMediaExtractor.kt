package com.mangalens.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real local extractor, with no remote paid API and no authentication/DRM bypass. */
fun interface SiteMediaExtractor {
    fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink?
}

class YtDlpSiteMediaExtractor(context: Context) : SiteMediaExtractor {
    private val app = context.applicationContext

    override fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink? {
        YoutubeDL.init(app)
        val request = YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("--skip-download")
            // Restrict stdout to selected-stream metadata rather than every format/thumbnail/subtitle.
            addOption("--print", "%(.{url,protocol,ext,height,vcodec,acodec,title,extractor_key,http_headers,has_drm,_type,requested_formats})j")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
            addOption("--retries", "1")
            addOption("--extractor-retries", "1")
            // Select a muxed representation. A video-only DASH URL would lose the audio.
            addOption("-f", "best[height<=${quality.height}]/worst")
        }
        val processId = "mangalens-resolve-${UUID.randomUUID()}"
        val timeout = timer.schedule({ YoutubeDL.destroyProcessById(processId) }, 90, TimeUnit.SECONDS)
        return try {
            val response = YoutubeDL.execute(request, processId = processId, callback = null)
            SiteMediaInfoParser.parse(response.out, url)
        } catch (failure: Exception) {
            if (failure is InterruptedException) throw failure
            throw IllegalArgumentException(
                "The site extractor could not find an accessible combined video and audio stream. " +
                    "Try opening the page in Web and using Open in Video; protected or login-only media may be unavailable.",
                failure
            )
        } finally {
            timeout.cancel(false)
        }
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mangalens-extractor-timeout").apply { isDaemon = true }
        }
    }
}

/** Kept separate from the native runtime so format/security decisions have JVM tests. */
internal object SiteMediaInfoParser {
    fun parse(json: String, sourcePage: String): ResolvedMediaLink? {
        if (json.length > 16 * 1024 * 1024) return null
        val info = JSONObject(json)
        if (info.optString("_type") in setOf("playlist", "multi_video") || info.optBoolean("has_drm")) return null
        if (info.optString("vcodec") == "none" || info.optString("acodec") == "none") return null
        // Never substitute one of requested_formats: those commonly contain separate tracks.
        if (info.optJSONArray("requested_formats")?.length()?.let { it > 1 } == true) return null
        val url = info.optString("url").takeIf(::isWebUrl) ?: return null
        val protocol = info.optString("protocol")
        if (protocol.contains("drm", true) || protocol.contains("rtmp", true)) return null
        val mime = when {
            protocol.startsWith("m3u8") -> "application/x-mpegURL"
            protocol.contains("dash") || url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
            info.optString("ext") == "webm" -> "video/webm"
            info.optString("ext") == "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val headers = linkedMapOf<String, String>()
        info.optJSONObject("http_headers")?.let { values ->
            values.keys().forEach { key ->
                if (key.lowercase() in setOf("user-agent", "referer", "accept", "cookie")) {
                    val value = values.optString(key)
                    if (value.length <= 16_384 && value.none { it < ' ' || it == '\u007f' }) headers[key] = value
                }
            }
        }
        return ResolvedMediaLink(
            url = url, mimeType = mime, provider = info.optString("extractor_key", "yt-dlp"),
            detectedHeight = info.optInt("height", 0).takeIf { it > 0 },
            title = info.optString("title").takeIf { it.isNotBlank() },
            sourcePageUrl = sourcePage, headers = headers
        )
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
