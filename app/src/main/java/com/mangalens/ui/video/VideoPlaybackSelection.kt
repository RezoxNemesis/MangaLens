package com.mangalens.ui.video

import com.mangalens.orez.agent.OrezAudioSelection
import com.mangalens.orez.agent.OrezMediaSelection
import java.net.URI
import java.util.UUID

/** Private app selection. Resolution IDs identify one accepted complete resolver tuple. */
data class VideoPlaybackSelection(
    val resolutionId: String,
    val videoUrl: String,
    val videoHeaders: Map<String, String>,
    val pageUrl: String?,
    val audioUrl: String?,
    val audioHeaders: Map<String, String>,
    val durationUs: Long? = null,
    val providerCaptions: com.mangalens.download.ProviderCaptionInventory? = null
) {
    fun captured() = copy(videoHeaders = videoHeaders.toMap(), audioHeaders = audioHeaders.toMap(), providerCaptions = providerCaptions?.captureSnapshot())

    fun toOrezSelection(): OrezMediaSelection? {
        if (audioUrl != null && durationUs !in 1..VideoPlaybackPublication.MAX_DURATION_US) return null
        return OrezMediaSelection(videoUrl, pageUrl ?: videoUrl, "Video", videoHeaders.toMap(),
            resolutionId = resolutionId,
            audio = audioUrl?.let { OrezAudioSelection(it, resolutionId, audioHeaders.toMap()) },
            expectedDurationUs = durationUs, providerCaptions = providerCaptions?.captureSnapshot())
    }
}

/** Only a current ready-player observation may fill duration; resolver estimates are insufficient. */
data class VideoReadyObservation(val selection: VideoPlaybackSelection, val durationUs: Long)

internal object VideoPlaybackPublication {
    const val MAX_DURATION_US = 21_600_000_000L
    private val allowedHeaders = setOf("accept", "accept-language", "cookie", "origin", "referer", "user-agent")

    fun capture(videoUrl: String, videoHeaders: Map<String, String>, pageUrl: String?,
        audioUrl: String?, audioHeaders: Map<String, String>,
        providerCaptions: com.mangalens.download.ProviderCaptionInventory? = null): VideoPlaybackSelection {
        require(safeUrl(videoUrl) && (audioUrl == null || safeUrl(audioUrl))) { "A detected media source is not a safe HTTP or HTTPS address." }
        return VideoPlaybackSelection(UUID.randomUUID().toString().replace("-", ""), videoUrl,
            headers(videoHeaders), pageUrl?.takeIf(::safeUrl), audioUrl,
            if (audioUrl == null) emptyMap() else headers(audioHeaders), providerCaptions = providerCaptions?.captureSnapshot())
    }

    fun sameTuple(left: VideoPlaybackSelection, right: VideoPlaybackSelection): Boolean =
        left.copy(durationUs = null) == right.copy(durationUs = null)

    fun acceptReady(current: VideoPlaybackSelection?, observed: VideoReadyObservation): VideoPlaybackSelection? =
        current?.takeIf { sameTuple(it, observed.selection) && observed.durationUs in 1..MAX_DURATION_US }
            ?.copy(durationUs = observed.durationUs)

    fun readyObservation(selection: VideoPlaybackSelection?, boundRevision: Long, currentRevision: Long,
        currentVideoUrl: String?, ready: Boolean, durationMs: Long, live: Boolean, dynamic: Boolean): VideoReadyObservation? {
        val captured = selection ?: return null
        if (!ready || live || dynamic || boundRevision != currentRevision || currentVideoUrl != captured.videoUrl ||
            durationMs !in 1..(MAX_DURATION_US / 1000)) return null
        return VideoReadyObservation(captured.captured(), durationMs * 1000)
    }

    fun canReplace(current: VideoPlaybackSelection?, expected: VideoPlaybackSelection,
        replacement: VideoPlaybackSelection): Boolean = current != null && sameTuple(current, expected) &&
        replacement.resolutionId != current.resolutionId && replacement.durationUs == null &&
        replacement.resolutionId.matches(Regex("[a-f0-9]{32}")) &&
        safeUrl(replacement.videoUrl) && (replacement.audioUrl == null || safeUrl(replacement.audioUrl))

    private fun safeUrl(value: String): Boolean = value.length in 1..8192 && value.none { it.isISOControl() } &&
        runCatching { URI(value).let { (it.scheme.equals("http", true) || it.scheme.equals("https", true)) && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)

    private fun headers(values: Map<String, String>): Map<String, String> = values.entries.take(64)
        .filter { (name, value) -> name.lowercase() in allowedHeaders && value.length <= 16_384 && value.none(Char::isISOControl) }
        .associate { it.key to it.value }
}
