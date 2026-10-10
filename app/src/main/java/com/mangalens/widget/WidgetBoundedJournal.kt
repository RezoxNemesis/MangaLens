package com.mangalens.widget

import com.mangalens.core.reader.ChapterCbzResources
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The actual opened stream is registered before validation and closes exactly once. */
internal fun widgetReadBounded(input: InputStream, limit: Int, resources: ChapterCbzResources, check: () -> Unit): ByteArray =
    resources.usePrivate(input) { held ->
        require(limit in 1..2_000_000)
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        var emptyReads = 0
        while (true) {
            check()
            val size = held.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (size < 0) break
            if (size == 0) { if (++emptyReads > 8) throw IOException("Widget journal made no progress."); continue }
            emptyReads = 0
            if (output.size() > limit - size) throw IOException("Widget journal exceeds its bound.")
            output.write(buffer, 0, size)
        }
        check(); output.toByteArray()
    }
