package com.mangalens.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** App-private timestamps only. Packet payloads, URLs and credentials never enter this receipt. */
internal object OriginalPacketTiming {
    private const val RECORD_BYTES = 16L
    private const val ROUNDING_US = 2_000L

    class Writer(file: File) : AutoCloseable {
        private val output = DataOutputStream(BufferedOutputStream(file.outputStream(), 64 * 1024))
        fun add(ptsUs: Long, durationUs: Long) { output.writeLong(ptsUs); output.writeLong(durationUs) }
        override fun close() = output.close()
    }

    fun matches(source: File?, output: File?, samples: Long, checkActive: () -> Unit = {}): Boolean {
        if (source == null || output == null || samples !in 1L..50_000_000L) return false
        if (!source.isFile || !output.isFile || source.length() != samples * RECORD_BYTES ||
            output.length() != samples * RECORD_BYTES) return false
        return try {
            DataInputStream(BufferedInputStream(source.inputStream(), 64 * 1024)).use { first ->
                DataInputStream(BufferedInputStream(output.inputStream(), 64 * 1024)).use { second ->
                    var offset: Long? = null
                    repeatLong(samples) {
                        checkActive()
                        val sourcePts = first.readLong(); val sourceDuration = first.readLong()
                        val outputPts = second.readLong(); val outputDuration = second.readLong()
                        // First observed packet preserves encoded order, including B-frames
                        // and negative codec priming. Each subsequent packet uses this rebase.
                        val packetOffset = Math.subtractExact(outputPts, sourcePts)
                        if (offset == null) offset = packetOffset
                        if (!near(packetOffset, requireNotNull(offset)) ||
                            !near(sourceDuration, outputDuration)) return false
                    }
                    first.read() == -1 && second.read() == -1
                }
            }
        } catch (failure: java.io.IOException) {
            if (failure is MediaResolutionTimeoutException) throw failure
            false
        } catch (_: ArithmeticException) { false }
    }

    private inline fun repeatLong(count: Long, action: () -> Unit) {
        var index = 0L
        while (index++ < count) action()
    }

    private fun near(a: Long, b: Long): Boolean =
        Math.subtractExact(a, b) in -ROUNDING_US..ROUNDING_US
}
