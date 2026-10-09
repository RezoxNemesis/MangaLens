package com.mangalens.orez

import java.io.IOException

internal object OrezModelTransferPolicy {
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    /** Validate before truncating or appending to a saved partial. */
    fun responseOffset(
        status: Int,
        requestedOffset: Long,
        contentRange: String?,
        contentLength: Long,
        expectedBytes: Long
    ): Long {
        fun invalid(): Nothing = throw IOException("Model server returned an invalid resume range or size. Saved partial was kept; retry the download.")
        if (expectedBytes <= 0L || requestedOffset !in 0L until expectedBytes) invalid()
        if (status == 200) {
            if (contentLength >= 0L && contentLength != expectedBytes) invalid()
            return 0L
        }
        if (status != 206) throw IOException("Model server HTTP $status. Saved partial was kept; retry the download.")
        val groups = range.matchEntire(contentRange.orEmpty().trim())?.groupValues ?: invalid()
        val start = groups[1].toLongOrNull() ?: invalid()
        val end = groups[2].toLongOrNull() ?: invalid()
        val total = groups[3].toLongOrNull() ?: invalid()
        if (start != requestedOffset || total != expectedBytes || end < start || end >= total) invalid()
        if (contentLength >= 0L && contentLength != end - start + 1L) invalid()
        return requestedOffset
    }
}
