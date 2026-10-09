package com.mangalens.download

/** Source facts belong to the complete resolved video/audio pair, never to a later URL alone. */
data class OriginalMediaSelection(
    val videoFormatId: String? = null,
    val audioFormatId: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val maximumReportedHeight: Int? = null,
    val client: String? = null
)

internal object OriginalMediaFormatPolicy {
    // Rank resolution before codec/container preference; retain the provider's
    // preferred/original audio language before comparing its encoded bitrates.
    const val SORT = "height,fps,lang,vbr,abr"

    fun selector(quality: DownloadQuality, separate: Boolean): String {
        val ceiling = if (quality == DownloadQuality.BEST) "" else "[height<=${quality.height}]"
        return if (separate) "bestvideo*$ceiling+bestaudio/best$ceiling" else "best$ceiling"
    }

    fun videoMime(extension: String): String? = when (extension.lowercase()) {
        "mp4", "m4v", "3gp" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> null
    }

    fun safeFormatId(value: String): String? = value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
    fun safeCodec(value: String): String? = value.takeIf { it.matches(Regex("[A-Za-z0-9._+-]{1,96}")) && it != "none" }
}
