package com.mangalens.ui.video

import java.net.URI
import com.mangalens.download.ResolvedMediaLink

/** A source's refresh generation fences both late results and old request finalizers. */
internal class PlaybackRefreshRequests {
    private var generation = 0L
    private var active = false

    fun begin(): Long { active = true; return ++generation }
    fun cancel() { ++generation; active = false }
    fun isCurrent(request: Long): Boolean = active && request == generation
    fun finish(request: Long): Boolean {
        if (!isCurrent(request)) return false
        active = false
        return true
    }

    fun publish(
        request: Long,
        current: PlaybackStreamIdentity,
        refreshed: PlaybackStreamIdentity,
        apply: () -> Unit,
        restartUnchanged: () -> Unit
    ): Boolean {
        if (!isCurrent(request)) return false
        apply()
        // A changed identity is reopened by the Compose source effect. An unchanged identity
        // also needs prepare/play, since openHttp deliberately ignores duplicate requests.
        if (current.equivalentTo(refreshed)) restartUnchanged()
        return true
    }
}

internal data class PlaybackStreamIdentity(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap()
) {
    fun equivalentTo(other: PlaybackStreamIdentity): Boolean =
        url == other.url && audioUrl == other.audioUrl &&
            canonicalHeaders(headers) == canonicalHeaders(other.headers) &&
            canonicalHeaders(audioHeaders) == canonicalHeaders(other.audioHeaders)

    private fun canonicalHeaders(values: Map<String, String>): List<Pair<String, String>> =
        values.entries.sortedBy { it.key.lowercase() }.map { it.key.lowercase() to it.value }
}

internal fun isPlayableRefresh(media: ResolvedMediaLink): Boolean {
    val mime = media.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return mime.startsWith("video/") || mime in setOf(
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml"
    )
}

internal fun playbackSourcePage(sourcePage: String?, mediaUrl: String): String? =
    listOfNotNull(sourcePage, mediaUrl).firstOrNull { value ->
        runCatching {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
