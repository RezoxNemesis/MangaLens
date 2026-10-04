package com.mangalens.core.reader

import java.io.InputStream
import java.io.OutputStream

/** Copies a stream while enforcing a hard byte ceiling before writing past it. */
internal object BoundedTransfer {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        bufferSize: Int = 64 * 1024,
        checkActive: () -> Unit = {}
    ): Long {
        require(maxBytes > 0L) { "maxBytes must be positive." }
        require(bufferSize > 0) { "bufferSize must be positive." }

        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read.toLong() > maxBytes - total) {
                throw IllegalStateException("Transfer exceeded the safe size limit of $maxBytes bytes.")
            }
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }
}
