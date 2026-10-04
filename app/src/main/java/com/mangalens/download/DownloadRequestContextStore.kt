package com.mangalens.download

import android.content.Context
import com.mangalens.ui.video.MediaRequestContext
import okhttp3.Interceptor
import org.json.JSONObject
import java.io.File

/** App-private request metadata survives worker retries without putting cookies in WorkManager data. */
internal class DownloadRequestContextStore(context: Context) {
    private val directory = File(context.filesDir, "download_request_context")

    fun write(id: String, media: ResolvedMediaLink) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        directory.mkdirs()
        val json = JSONObject().put("url", media.url)
            .put("page", media.sourcePageUrl ?: JSONObject.NULL)
            .put("headers", JSONObject(media.headers))
            .put("mime", media.mimeType ?: JSONObject.NULL)
            .put("height", media.detectedHeight ?: JSONObject.NULL)
            .put("audioUrl", media.audioUrl ?: JSONObject.NULL)
            .put("audioHeaders", JSONObject(media.audioHeaders))
            .put("requestedHeight", media.requestedHeight ?: JSONObject.NULL)
        val temp = File(directory, "$id.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(directory, "$id.json"))) { "Unable to persist download request context" }
    }

    fun read(id: String): MediaRequestContext? = readMedia(id)?.let {
        MediaRequestContext(it.url, it.sourcePageUrl, it.headers)
    }

    fun readMedia(id: String): ResolvedMediaLink? {
        if (!id.matches(Regex("[a-zA-Z0-9-]+"))) return null
        val file = File(directory, "$id.json")
        if (!file.isFile || file.length() > 128 * 1024) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val values = json.optJSONObject("headers") ?: JSONObject()
            val headers = values.keys().asSequence().associateWith { values.optString(it) }
            ResolvedMediaLink(json.getString("url"), json.optString("mime").takeIf { it != "null" },
                detectedHeight = json.optInt("height", 0).takeIf { it > 0 },
                sourcePageUrl = json.optString("page").takeIf { it != "null" }, headers = headers,
                audioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" },
                audioHeaders = json.optJSONObject("audioHeaders")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty(),
                requestedHeight = json.optInt("requestedHeight").takeIf { it > 0 })
        }.getOrNull()
    }

    fun remove(id: String) {
        if (id.matches(Regex("[a-zA-Z0-9-]+"))) File(directory, "$id.json").delete()
    }
}

/** Applied on every network hop, including redirects: captured cookies stay on the exact URL. */
internal fun scopedDownloadHeaders(
    context: MediaRequestContext,
    browserCookieForUrl: (String) -> String? = { null }
): Interceptor = Interceptor { chain ->
    val request = chain.request()
    val actualUrl = request.url.toString()
    // The browser jar applies domain/path/secure rules separately for each redirect and segment.
    val cookie = runCatching { browserCookieForUrl(actualUrl) }.getOrNull()
    val safe = context.headersFor(actualUrl, cookie)
    val rebuilt = request.newBuilder().removeHeader("Cookie").removeHeader("Referer").removeHeader("Origin")
    safe.forEach { (key, value) -> rebuilt.header(key, value) }
    chain.proceed(rebuilt.build())
}
