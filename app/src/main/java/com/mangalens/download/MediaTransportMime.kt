package com.mangalens.download

/** Explicit source dispatch is captured with the same URLs and headers; null keeps legacy inference. */
internal object MediaTransportMime {
    fun capture(value: String?): String? {
        if (value == null) return null
        require(value.length in 1..160 && value.none(Char::isISOControl)) { "Source media type is invalid." }
        val mime = value.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return when (mime) {
            "application/octet-stream" -> null
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
            "application/dash+xml" -> "application/dash+xml"
            else -> mime.takeIf { it.matches(Regex("(?:video|audio)/[a-z0-9.+-]{1,80}")) }
                ?: throw IllegalArgumentException("Source media type is not a supported video or audio transport.")
        }
    }

    /** Do not alter historical request keys when both optional fields were absent. */
    fun identitySuffix(video: String?, audio: String?): String = buildString {
        capture(video)?.let { append("video-mime=").append(it).append('\n') }
        capture(audio)?.let { append("audio-mime=").append(it).append('\n') }
    }

    fun audioContainer(extension: String): String? = when (extension.lowercase(java.util.Locale.ROOT)) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "opus", "ogg", "oga" -> "audio/ogg"
        else -> null
    }

    fun fromObservedKind(kind: String): String? = when (kind.uppercase(java.util.Locale.ROOT)) {
        "HLS" -> "application/x-mpegURL"
        "DASH" -> "application/dash+xml"
        else -> null
    }
}
