package com.mangalens.download

import android.content.Context
import android.util.AtomicFile
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
            .put("provider", media.provider)
            .put("title", media.title ?: JSONObject.NULL)
            .put("headers", JSONObject(media.headers))
            .put("mime", media.mimeType ?: JSONObject.NULL)
            .put("height", media.detectedHeight ?: JSONObject.NULL)
            .put("audioUrl", media.audioUrl ?: JSONObject.NULL)
            .put("audioHeaders", JSONObject(media.audioHeaders))
            .put("requestedHeight", media.requestedHeight ?: JSONObject.NULL)
            .put("expectedDurationUs", media.expectedDurationUs ?: JSONObject.NULL)
        val target = AtomicFile(File(directory, "$id.json"))
        val output = target.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    fun read(id: String): MediaRequestContext? = readMedia(id)?.let {
        MediaRequestContext(it.url, it.sourcePageUrl, it.headers)
    }

    fun readMedia(id: String): ResolvedMediaLink? {
        if (!id.matches(Regex("[a-zA-Z0-9-]+"))) return null
        val file = File(directory, "$id.json")
        val target = AtomicFile(file)
        if (!target.exists()) return null
        return runCatching {
            val bytes = target.openRead().use { input ->
                val buffer = ByteArray(128 * 1024 + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                buffer.copyOf(size)
            }
            check(bytes.size <= 128 * 1024) { "Download request context exceeds the safe limit" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val values = json.optJSONObject("headers") ?: JSONObject()
            val headers = values.keys().asSequence().associateWith { values.optString(it) }
            ResolvedMediaLink(
                url = json.getString("url"),
                mimeType = json.optString("mime").takeIf { it != "null" },
                provider = json.optString("provider", "generic"),
                title = json.optString("title").takeIf { it.isNotBlank() && it != "null" },
                detectedHeight = json.optInt("height", 0).takeIf { it > 0 },
                sourcePageUrl = json.optString("page").takeIf { it != "null" }, headers = headers,
                audioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" },
                audioHeaders = json.optJSONObject("audioHeaders")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty(),
                requestedHeight = json.optInt("requestedHeight").takeIf { it > 0 },
                expectedDurationUs = json.optLong("expectedDurationUs").takeIf { it > 0L })
        }.getOrNull()
    }

    fun remove(id: String) {
        if (id.matches(Regex("[a-zA-Z0-9-]+"))) AtomicFile(File(directory, "$id.json")).delete()
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

