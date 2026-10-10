package com.mangalens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A valid WebP dimension header with an invalid lossless Huffman raster (not a partial PNG). */
internal object BoundsReadableRasterFixture {
    fun create(width: Int, height: Int): ByteArray {
        require(width in 1..16384 && height in 1..16384)
        return ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(34)
            put("WEBPVP8L".toByteArray(Charsets.US_ASCII)); putInt(22)
            put(0x2f.toByte()); putInt((width - 1) or ((height - 1) shl 14))
            // The remaining 17 zero bytes form an impossible complete lossless raster.
        }.array()
    }
}
