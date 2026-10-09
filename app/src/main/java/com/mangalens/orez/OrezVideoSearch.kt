package com.mangalens.orez

import android.content.Context
import com.mangalens.download.AndroidNativeExtractorNetworking
import com.mangalens.download.MediaProcessGuard
import com.mangalens.download.MediaResolutionRunner
import com.mangalens.download.NativeOwnedExtractorTools
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class OrezVideoResult(val title: String, val url: String, val thumbnail: String?, val creator: String, val durationSeconds: Int?, val uploadDate: String?, val description: String)

class OrezVideoSearch(private val context: Context) {
    suspend fun search(query: String): List<OrezVideoResult> = MediaResolutionRunner.run(25_000L) { session ->
        session.checkActive()
        val recent = Regex("(?i)latest|recent|today|newest").containsMatchIn(query)
        val request = YoutubeDLRequest((if (recent) "ytsearchdate5:" else "ytsearch5:") + query.take(500)).apply {
            addOption("--ignore-config"); addOption("--flat-playlist"); addOption("--dump-single-json")
            addOption("--no-plugin-dirs"); addOption("--no-remote-components"); addOption("--no-geo-bypass")
            addOption("--skip-download"); addOption("--socket-timeout", "10"); addOption("--retries", "0")
        }
        val source = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query.take(500), "UTF-8")
        NativeOwnedExtractorTools.configure(context, request, session)
        AndroidNativeExtractorNetworking.configure(context, request, source, session::checkActive)
        val id = "orez-video-${UUID.randomUUID()}"
        val guard = MediaProcessGuard(session, id, session.remainingMillis(), YoutubeDL::destroyProcessById)
        try {
            session.checkActive()
            OrezVideoResultCodec.parseSearch(YoutubeDL.execute(request, processId = id, callback = null).out)
        } finally { guard.close() }
    }
    companion object {
        fun isDiscovery(query: String) = OrezDiscoveryPolicy.isVideoQuery(query)
    }
}

object OrezVideoResultCodec {
    const val MARKER = "\n\nVideo results:\n"
    fun parseSearch(json: String): List<OrezVideoResult> {
        if (json.length > 2_000_000) return emptyList()
        return decode(JSONObject(json).optJSONArray("entries") ?: JSONArray())
    }
    fun encode(results: List<OrezVideoResult>): String = JSONArray().apply {
        results.take(5).forEach { r -> put(JSONObject().put("title", r.title).put("webpage_url", r.url).put("thumbnail", r.thumbnail)
            .put("channel", r.creator).put("duration", r.durationSeconds).put("upload_date", r.uploadDate).put("description", r.description)) }
    }.toString()
    fun fromMessage(message: String): List<OrezVideoResult> = if (!message.contains(MARKER)) emptyList()
        else runCatching { decode(JSONArray(message.substringAfter(MARKER))) }.getOrDefault(emptyList())
    private fun decode(array: JSONArray): List<OrezVideoResult> = (0 until minOf(array.length(), 5)).mapNotNull { i ->
        val row = array.optJSONObject(i) ?: return@mapNotNull null
        val id = row.optString("id")
        val direct = row.optString("webpage_url").takeIf(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
            ?: row.optString("url").takeIf(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
        val url = direct
            ?: if (id.matches(Regex("[a-zA-Z0-9_-]{11}"))) "https://www.youtube.com/watch?v=$id" else return@mapNotNull null
        if (!com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) return@mapNotNull null
        val thumbnail = row.optString("thumbnail").takeIf { it.startsWith("https://") && com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(it) }
            ?: row.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("https://") }
        OrezVideoResult(row.optString("title").take(250), url, thumbnail,
            row.optString("channel", row.optString("uploader", "")).take(150),
            row.optDouble("duration", 0.0).toInt().takeIf { it > 0 },
            row.optString("upload_date").takeIf { it.matches(Regex("[0-9]{8}")) }, row.optString("description").take(300))
    }
}

