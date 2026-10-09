package com.mangalens.download

import java.util.Locale

enum class ProviderCaptionKind { MANUAL, AUTOMATIC }
enum class ProviderCaptionFormat { VTT, SRT, JSON3 }
data class ProviderCaptionTrack(
    val url: String,
    val language: String,
    val kind: ProviderCaptionKind,
    val format: ProviderCaptionFormat,
    val originalAutomatic: Boolean = false
)
data class ProviderCaptionInventory(
    val sourcePageUrl: String,
    val videoId: String?,
    val originalLanguage: String?,
    val selectedAudioLanguage: String?,
    val expectedDurationMs: Long?,
    val tracks: List<ProviderCaptionTrack>
) {
    fun captureSnapshot() = copy(tracks = tracks.map { it.copy() })
}

internal fun normalizeCaptionLanguage(raw: String?): String? = raw?.trim()?.replace('_', '-')
    ?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")) }
