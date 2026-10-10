package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.ui.video.SpeechCue
import java.security.MessageDigest

/** Live page owner; this is independent of a network media proof or a subtitle completion receipt. */
internal data class BrowserCaptionPageOwner(
    val tabId: String,
    val navigationEpoch: Long,
    val pageUrl: String,
    val webViewToken: String
)

/** Values observed on the accepted real video element; position is never advanced by app wall time. */
internal data class BrowserCaptionClockSample(
    val pageUrl: String,
    val documentNonce: String,
    val elementId: String,
    val sourceVersion: String,
    val currentSrc: String,
    val audioTrackKey: String?,
    val audioLanguage: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playbackRate: Double,
    val paused: Boolean,
    val seeking: Boolean,
    val readyState: Int
)

internal class BrowserSourceCaptionScope(
    val page: BrowserCaptionPageOwner,
    val captured: BrowserCaptionClockSample,
    inventory: ProviderCaptionInventory
) {
    val inventory = inventory.captureSnapshot()
    // The opaque source ID scopes presentation. It makes no claim about media bytes, PCM or ASR.
    val sourceId: String = browserCaptionScopeHash(listOf("browser-caption-source-v1", page.tabId,
        page.navigationEpoch.toString(), page.pageUrl, page.webViewToken) + identity(captured) +
        listOf(this.inventory.sourcePageUrl, this.inventory.videoId.orEmpty(), this.inventory.originalLanguage.orEmpty(),
            this.inventory.selectedAudioLanguage.orEmpty(), this.inventory.expectedDurationMs?.toString().orEmpty()) +
        this.inventory.tracks.flatMap { listOf(it.url,it.language,it.kind.name,it.format.name,it.originalAutomatic.toString()) })

    fun accepts(currentPage: BrowserCaptionPageOwner, current: BrowserCaptionClockSample): Boolean =
        page == currentPage && identity(captured) == identity(current)

    private fun identity(sample: BrowserCaptionClockSample): List<String> = listOf(sample.pageUrl,
        sample.documentNonce,sample.elementId,sample.sourceVersion,sample.currentSrc,
        sample.audioTrackKey.orEmpty(),sample.audioLanguage.orEmpty(),sample.durationMs.toString())
}

/** Invalid, unready and seeking observations clear presentation without inventing a clock. */
internal fun browserCaptionTextAt(cues: List<SpeechCue>, clock: BrowserCaptionClockSample): String? {
    if (clock.readyState !in 1..4 || clock.seeking || clock.durationMs !in 1..21_600_000 ||
        clock.positionMs !in 0..clock.durationMs || !clock.playbackRate.isFinite() || clock.playbackRate !in .1..16.0) return null
    return cues.asSequence().filter { clock.positionMs >= it.startMs && clock.positionMs < it.endMs }
        .take(8).map { it.text }.joinToString("\n").takeIf(String::isNotBlank)
}

private fun browserCaptionScopeHash(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(fields.joinToString("|") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }.take(32)
