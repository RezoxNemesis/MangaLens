package com.mangalens.download

import android.content.Context
import android.util.AtomicFile
import com.mangalens.ui.video.MediaRequestContext
import okhttp3.Interceptor
import org.json.JSONObject
import java.io.File

internal const val MAX_DOWNLOAD_REQUEST_CONTEXT_BYTES = 1024 * 1024

/** App-private request metadata survives worker retries without putting cookies in WorkManager data. */
internal class DownloadRequestContextStore(context: Context) {
    private val directory = File(context.filesDir, "download_request_context")

    fun write(id: String, media: ResolvedMediaLink) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        requireBoundMediaSource(media.url, media)
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
        media.audioMimeType?.let { json.put("audioMime", MediaTransportMime.capture(it)) }
        media.videoFragments?.let { json.put("videoFragments", it.toJson()) }
        media.audioFragments?.let { json.put("audioFragments", it.toJson()) }
        media.originalSelection?.let { selection ->
            json.put("originalSelection", JSONObject()
                .put("videoFormatId", selection.videoFormatId ?: JSONObject.NULL)
                .put("audioFormatId", selection.audioFormatId ?: JSONObject.NULL)
                .put("videoCodec", selection.videoCodec ?: JSONObject.NULL)
                .put("audioCodec", selection.audioCodec ?: JSONObject.NULL)
                .put("maximumReportedHeight", selection.maximumReportedHeight ?: JSONObject.NULL)
                .put("client", selection.client ?: JSONObject.NULL))
        }
        // Match the reader's byte bound before starting the atomic replacement.
        // Two valid sets of six 16-KiB headers can exceed the former 128-KiB cap.
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_DOWNLOAD_REQUEST_CONTEXT_BYTES) {
            "Saved media context exceeds its safe limit. Resolve a smaller source request again."
        }
        val target = AtomicFile(File(directory, "$id.json"))
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    fun read(id: String): MediaRequestContext? = readMedia(id)?.let {
        MediaRequestContext(it.url, it.sourcePageUrl, it.headers)
    }

    /** Transfers never interpret an unreadable or lost resolved pair as a silent source. */
    fun readForTransfer(id: String, sourceUrl: String, required: Boolean): ResolvedMediaLink? {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val present = listOf("$id.json", "$id.json.bak", "$id.json.new").any { File(directory, it).exists() }
        val media = readMedia(id)
        check(!present || media != null) {
            "Saved media context cannot be read. Resolve the source again; no file was published."
        }
        return requireBoundMediaSource(sourceUrl, media, receiptRequired = required)
    }

    fun readMedia(id: String): ResolvedMediaLink? {
        if (!id.matches(Regex("[a-zA-Z0-9-]+"))) return null
        val file = File(directory, "$id.json")
        val target = AtomicFile(file)
        // AtomicFile.exists() is a hidden platform API; openRead restores a legacy
        // backup, so check both public file paths before reading.
        if (!file.isFile && !File(directory, "$id.json.bak").isFile) return null
        return runCatching {
            val bytes = target.openRead().use { input ->
                val buffer = ByteArray(MAX_DOWNLOAD_REQUEST_CONTEXT_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                buffer.copyOf(size)
            }
            check(bytes.size <= MAX_DOWNLOAD_REQUEST_CONTEXT_BYTES) { "Download request context exceeds the safe limit" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            check(listOf("url", "headers", "audioUrl", "audioHeaders").all(json::has)) {
                "Download request context has no complete source shape"
            }
            val values = json.getJSONObject("headers")
            val headers = values.keys().asSequence().associateWith { values.optString(it) }
            ResolvedMediaLink(
                url = json.getString("url"),
                mimeType = json.optString("mime").takeIf { it != "null" },
                provider = json.optString("provider", "generic"),
                title = json.optString("title").takeIf { it.isNotBlank() && it != "null" },
                detectedHeight = json.optInt("height", 0).takeIf { it > 0 },
                sourcePageUrl = json.optString("page").takeIf { it != "null" }, headers = headers,
                audioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" },
                audioHeaders = json.getJSONObject("audioHeaders").let { h -> h.keys().asSequence().associateWith { h.optString(it) } },
                requestedHeight = json.optInt("requestedHeight").takeIf { it > 0 },
                audioMimeType = json.optString("audioMime").takeIf { it.isNotBlank() && it != "null" }?.let(MediaTransportMime::capture),
                videoFragments = if (json.has("videoFragments")) OriginalFragmentPlan.fromJson(json.getJSONObject("videoFragments")) else null,
                audioFragments = if (json.has("audioFragments")) OriginalFragmentPlan.fromJson(json.getJSONObject("audioFragments")) else null,
                expectedDurationUs = json.optLong("expectedDurationUs").takeIf { it > 0L },
                originalSelection = json.optJSONObject("originalSelection")?.let { selection ->
                    OriginalMediaSelection(
                        videoFormatId = OriginalMediaFormatPolicy.safeFormatId(selection.optString("videoFormatId")),
                        audioFormatId = OriginalMediaFormatPolicy.safeFormatId(selection.optString("audioFormatId")),
                        videoCodec = OriginalMediaFormatPolicy.safeCodec(selection.optString("videoCodec")),
                        audioCodec = OriginalMediaFormatPolicy.safeCodec(selection.optString("audioCodec")),
                        maximumReportedHeight = selection.optInt("maximumReportedHeight").takeIf { it in 1..16_384 },
                        client = selection.optString("client").takeIf { it in setOf("default", "tv", "web_embedded", "web_safari", "ios", "android") }
                    )
                })
        }.getOrNull()
    }

    fun remove(id: String) {
        if (id.matches(Regex("[a-zA-Z0-9-]+"))) AtomicFile(File(directory, "$id.json")).delete()
    }
}

/** One captured tuple supplies both tracks; a partially committed refresh cannot mix sources. */
internal fun requireBoundMediaSource(sourceUrl: String, media: ResolvedMediaLink?,
                                    receiptRequired: Boolean = true): ResolvedMediaLink? {
    check(media != null || !receiptRequired) {
        "Saved media context is missing. Resolve the source again; no file was published."
    }
    check(media?.videoHlsSource == null && media?.audioHlsSource == null) {
        "Selected HLS playlists have not been captured as a complete pair. Resolve the source again."
    }
    if (media?.videoFragments?.hls != null || media?.audioFragments?.hls != null) {
        check(media.videoFragments?.hls != null && media.audioFragments?.hls != null && media.originalSelection != null) {
            "Selected HLS video and audio do not share a complete captured source tuple."
        }
        check(media.videoFragments?.formatId == media.originalSelection?.videoFormatId &&
            media.audioFragments?.formatId == media.originalSelection?.audioFormatId) {
            "Selected HLS format identity differs from the captured original source."
        }
    }
    media?.videoFragments?.let { plan ->
        plan.validate()
        check(plan.sourceUrl == media.url && plan.mediaMime == media.mimeType && plan.durationUs == media.expectedDurationUs) { "Saved fragment video does not match the complete selected source." }
    }
    media?.audioFragments?.let { plan ->
        plan.validate()
        check(plan.sourceUrl == media.audioUrl && plan.mediaMime == media.audioMimeType && plan.durationUs == media.expectedDurationUs) { "Saved fragment audio does not match the complete selected source." }
    }
    check(media == null || media.url == sourceUrl) {
        "Saved media context does not match the current source. Resolve the source again; no mixed-source file was published."
    }
    return media
}

/** New queues persist these facts; unmarked migrated rows require a proven whole-source file. */
internal fun DownloadEntity.requiresBoundMediaReceipt(): Boolean =
    provider != "generic" || sourcePageUrl != null || requestedHeight != null || selectedTransport != null

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

