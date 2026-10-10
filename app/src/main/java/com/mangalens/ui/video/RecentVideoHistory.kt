package com.mangalens.ui.video

import java.net.URI
import java.security.MessageDigest

internal enum class RecentVideoKind { LOCAL, ONLINE }

/** Replay stores a source page or a selected local URI, never cookies or a resolved split pair. */
internal data class RecentVideoSource(val kind: RecentVideoKind, val uri: String) {
    fun validate(): RecentVideoSource {
        require(uri.length in 1..8192 && uri.none(Char::isISOControl))
        val parsed = URI(uri)
        require(parsed.userInfo == null && parsed.rawFragment == null && !parsed.isOpaque)
        when (kind) {
            RecentVideoKind.ONLINE -> require(parsed.scheme.lowercase() in setOf("https", "http") &&
                !parsed.host.isNullOrBlank() && parsed.port in -1..65535)
            RecentVideoKind.LOCAL -> require(parsed.rawQuery == null && when (parsed.scheme.lowercase()) {
                "content" -> !parsed.rawAuthority.isNullOrBlank() && parsed.port == -1 && !parsed.path.isNullOrBlank()
                "file" -> parsed.rawAuthority.isNullOrBlank() && parsed.path?.startsWith('/') == true
                else -> false
            })
        }
        return this
    }

    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest((kind.name + "\n" + uri).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun local(uri: String): RecentVideoSource? = runCatching { RecentVideoSource(RecentVideoKind.LOCAL, uri).validate() }.getOrNull()
        fun online(uri: String): RecentVideoSource? = runCatching { RecentVideoSource(RecentVideoKind.ONLINE, uri).validate() }.getOrNull()
    }
}

internal data class RecentVideoEntry(
    val source: RecentVideoSource,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val seekable: Boolean,
    val playedAt: Long,
    val favorite: Boolean = false
) {
    val key: String get() = source.key
    val watched: Boolean get() = seekable && durationMs > 0 && positionMs >= durationMs - durationMs / 20
    val resumePositionMs: Long get() = if (seekable && !watched) positionMs.coerceIn(0, durationMs.coerceAtLeast(positionMs)) else 0
    val canContinue: Boolean get() = resumePositionMs > 0

    fun validate(): RecentVideoEntry {
        source.validate()
        require(title.length in 1..160 && title.none(Char::isISOControl))
        require(positionMs >= 0 && durationMs >= 0 && playedAt >= 0)
        require(durationMs == 0L || positionMs <= durationMs)
        require(seekable || positionMs == 0L)
        return this
    }
}

internal object RecentVideoHistoryPolicy {
    const val MAX_ENTRIES = 40

    fun record(existing: List<RecentVideoEntry>, captured: RecentVideoEntry): List<RecentVideoEntry> {
        captured.validate()
        val old = existing.firstOrNull { it.key == captured.key }
        if (old != null && old.playedAt > captured.playedAt) return existing
        val replacement = captured.copy(favorite = old?.favorite ?: captured.favorite)
        return trim(existing.filterNot { it.key == replacement.key } + replacement)
    }

    fun favorite(existing: List<RecentVideoEntry>, key: String, value: Boolean): List<RecentVideoEntry> =
        existing.map { if (it.key == key) it.copy(favorite = value) else it }

    private fun trim(entries: List<RecentVideoEntry>): List<RecentVideoEntry> {
        require(entries.map { it.key }.distinct().size == entries.size)
        val sorted = entries.sortedWith(compareByDescending<RecentVideoEntry> { it.playedAt }.thenBy { it.key })
        // A full history keeps favourites; additional non-favourite visits replace only its oldest non-favourite.
        val keep = sorted.filter { it.favorite }.take(MAX_ENTRIES).map { it.key }.toSet()
        val remaining = MAX_ENTRIES - keep.size
        val ordinary = sorted.filterNot { it.favorite }.take(remaining).map { it.key }.toSet()
        return sorted.filter { it.key in keep || it.key in ordinary }
    }
}
