package com.mangalens.core.reader

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Android can return a Bitmap for incomplete PNG rows. Verify the actual PNG stream first. */
internal object PngRasterIntegrity {
    fun verifyIfPng(
        file: File,
        maxBytes: Long = 40L * 1024 * 1024,
        maxPixels: Long = 100_000_000L,
        checkpoint: () -> Unit = {}
    ): Boolean {
        check(maxBytes > 0 && maxPixels > 0)
        checkpoint()
        if (file.length() > maxBytes) invalid("encoded image exceeds its byte budget")
        return FileInputStream(file).use { input -> verifyIfPng(input, maxBytes, maxPixels, checkpoint) }
    }

    /** Verifies the caller's held stream; neither closes nor reopens its source. */
    fun verifyIfPng(
        input: InputStream,
        maxBytes: Long = 40L * 1024 * 1024,
        maxPixels: Long = 100_000_000L,
        checkpoint: () -> Unit = {}
    ): Boolean {
        check(maxBytes > 0 && maxPixels > 0)
        val signature = ByteArray(8)
        var signatureCount = 0
        while (signatureCount < signature.size) {
            checkpoint()
            val read = input.read(signature, signatureCount,
                minOf((signature.size - signatureCount).toLong(), maxBytes - signatureCount + 1).toInt())
            if (read < 0) return false
            signatureCount += read
            if (signatureCount > maxBytes) invalid("encoded image exceeds its byte budget")
        }
        if (!signature.contentEquals(PNG_SIGNATURE)) return false
        val reader = BoundedReader(input, maxBytes, signature.size.toLong(), checkpoint)
        verifyPng(reader, maxPixels, checkpoint)
        return true
    }

    private fun verifyPng(reader: BoundedReader, maxPixels: Long, checkpoint: () -> Unit) {
        val inflater = Inflater()
        try {
            var header: Header? = null
            var scanlines: Scanlines? = null
            var hasPalette = false
            var hasData = false
            var dataClosed = false
            val encoded = ByteArray(64 * 1024)
            val decoded = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val length = reader.unsignedInt()
                val typeBytes = reader.small(4)
                if (typeBytes.any { (it.toInt() and 255) !in 'A'.code..'Z'.code &&
                        (it.toInt() and 255) !in 'a'.code..'z'.code }) invalid("invalid chunk type")
                if ((typeBytes[2].toInt() and 32) != 0) invalid("lowercase reserved PNG chunk-type bit")
                val type = typeBytes.toString(Charsets.US_ASCII)
                if (header == null && type != "IHDR") invalid("IHDR must be the first chunk")
                reader.requireRemaining(length + 4)
                if (hasData && type != "IDAT") dataClosed = true
                val crc = CRC32().apply { update(typeBytes, 0, typeBytes.size) }
                when (type) {
                    "IHDR" -> {
                        if (header != null || length != 13L) invalid("invalid or repeated IHDR")
                        val bytes = reader.small(13).also { crc.update(it, 0, it.size) }
                        val parsed = Header.parse(bytes, maxPixels)
                        header = parsed
                        scanlines = Scanlines(parsed)
                    }
                    "PLTE" -> {
                        val currentHeader = requireNotNull(header)
                        if (currentHeader.colorType in setOf(0, 4)) invalid("palette is forbidden for grayscale PNG")
                        if (hasPalette || hasData || length !in 3L..768L || length % 3 != 0L)
                            invalid("invalid palette order or length")
                        if (currentHeader.colorType == 3 && length / 3 > (1L shl currentHeader.bitDepth))
                            invalid("palette exceeds indexed PNG bit depth")
                        hasPalette = true
                        reader.consume(length, encoded) { bytes, count -> crc.update(bytes, 0, count) }
                    }
                    "IDAT" -> {
                        val currentHeader = requireNotNull(header)
                        if (dataClosed || (currentHeader.colorType == 3 && !hasPalette)) invalid("invalid IDAT order or missing palette")
                        hasData = true
                        reader.consume(length, encoded) { bytes, count ->
                            crc.update(bytes, 0, count)
                            if (inflater.finished()) invalid("compressed data continues after zlib completion")
                            check(inflater.needsInput())
                            inflater.setInput(bytes, 0, count)
                            while (true) {
                                checkpoint()
                                val inflated = try { inflater.inflate(decoded) }
                                catch (_: DataFormatException) { invalid("invalid zlib header, compressed raster or Adler checksum") }
                                if (inflated > 0) requireNotNull(scanlines).consume(decoded, inflated)
                                if (inflater.finished()) {
                                    if (inflater.remaining != 0) invalid("extra compressed bytes after the PNG raster")
                                    break
                                }
                                if (inflater.needsDictionary()) invalid("PNG zlib stream requires a dictionary")
                                if (inflated == 0) {
                                    if (inflater.needsInput()) break
                                    invalid("compressed raster made no progress")
                                }
                            }
                        }
                    }
                    "IEND" -> {
                        if (length != 0L || !hasData) invalid("invalid IEND or absent raster")
                        if (reader.unsignedInt() != crc.value) invalid("invalid IEND CRC")
                        if (!inflater.finished() || scanlines?.complete != true) invalid("incomplete compressed raster or scanlines")
                        checkpoint()
                        reader.requireEnd()
                        return
                    }
                    else -> {
                        if ((typeBytes[0].toInt() and 32) == 0) invalid("unknown critical PNG chunk")
                        reader.consume(length, encoded) { bytes, count -> crc.update(bytes, 0, count) }
                    }
                }
                if (reader.unsignedInt() != crc.value) invalid("invalid $type CRC")
            }
        } finally { inflater.end() }
    }

    private class BoundedReader(
        private val input: InputStream,
        private val maxBytes: Long,
        private var consumed: Long,
        private val checkpoint: () -> Unit
    ) {
        fun requireRemaining(count: Long) {
            if (count < 0 || count > maxBytes - consumed) invalid("PNG chunk exceeds its encoded byte budget")
        }
        fun small(count: Int): ByteArray = ByteArray(count).also { read(it, count) }
        fun unsignedInt(): Long = small(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        fun requireEnd() {
            checkpoint()
            // Require the PNG datastream to end at IEND; decoder-tolerated appended bytes
            // are not accepted as original PNG integrity evidence.
            if (input.read() != -1) invalid("trailing bytes after PNG IEND")
        }
        private fun read(buffer: ByteArray, count: Int) {
            requireRemaining(count.toLong())
            var offset = 0
            while (offset < count) {
                checkpoint()
                val got = input.read(buffer, offset, count - offset)
                if (got < 0) invalid("truncated PNG chunk")
                offset += got
                consumed += got
            }
        }
        fun consume(count: Long, buffer: ByteArray, consume: (ByteArray, Int) -> Unit) {
            requireRemaining(count)
            var remaining = count
            while (remaining > 0) {
                val next = minOf(remaining, buffer.size.toLong()).toInt()
                read(buffer, next)
                consume(buffer, next)
                remaining -= next
            }
        }
    }

    private data class Header(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int, val channels: Int, val interlace: Int) {
        companion object {
            fun parse(bytes: ByteArray, maxPixels: Long): Header {
                fun int(offset: Int) = (offset until offset + 4).fold(0L) { value, index ->
                    (value shl 8) or (bytes[index].toLong() and 255)
                }
                val width = int(0); val height = int(4)
                if (width !in 1L..Int.MAX_VALUE || height !in 1L..Int.MAX_VALUE || width * height > maxPixels)
                    invalid("PNG dimensions exceed the original pixel budget")
                val depth = bytes[8].toInt() and 255
                val color = bytes[9].toInt() and 255
                val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> invalid("invalid PNG color type") }
                val depths = when (color) { 0 -> setOf(1, 2, 4, 8, 16); 3 -> setOf(1, 2, 4, 8); else -> setOf(8, 16) }
                val interlace = bytes[12].toInt() and 255
                if (depth !in depths || bytes[10].toInt() != 0 || bytes[11].toInt() != 0 || interlace !in 0..1)
                    invalid("invalid PNG bit depth, compression, filter or interlace")
                return Header(width.toInt(), height.toInt(), depth, color, channels, interlace)
            }
        }
    }

    private data class Pass(val height: Int, val rowBytes: Long)
    private class Scanlines(header: Header) {
        private val passes: List<Pass> = if (header.interlace == 0) listOf(pass(header, header.width, header.height)) else
            ADAM7.mapNotNull { step ->
                fun size(dimension: Int, start: Int, stride: Int): Int =
                    if (dimension <= start) 0 else ((dimension.toLong() - start + stride - 1) / stride).toInt()
                val width = size(header.width, step[0], step[2]); val height = size(header.height, step[1], step[3])
                if (width == 0 || height == 0) null else pass(header, width, height)
            }
        private var currentPass = 0
        private var currentRow = 0
        private var rowBytesRemaining = 0L
        private var needsFilter = true
        val complete: Boolean get() = currentPass == passes.size
        fun consume(bytes: ByteArray, count: Int) {
            var offset = 0
            while (offset < count) {
                if (complete) invalid("decoded raster exceeds its exact scanline budget")
                if (needsFilter) {
                    if ((bytes[offset++].toInt() and 255) !in 0..4) invalid("invalid PNG scanline filter")
                    rowBytesRemaining = passes[currentPass].rowBytes
                    needsFilter = false
                }
                val next = minOf(rowBytesRemaining, (count - offset).toLong()).toInt()
                offset += next; rowBytesRemaining -= next
                if (rowBytesRemaining == 0L) {
                    needsFilter = true
                    if (++currentRow == passes[currentPass].height) { currentPass++; currentRow = 0 }
                }
            }
        }
        private fun pass(header: Header, width: Int, height: Int) = Pass(height,
            (width.toLong() * header.channels * header.bitDepth + 7) / 8)
    }

    private fun invalid(reason: String): Nothing = throw IllegalStateException("Original PNG raster could not be decoded safely: $reason")
    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val ADAM7 = listOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
        intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
}
