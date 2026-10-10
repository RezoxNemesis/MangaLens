package com.mangalens.core.reader

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PngRasterIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun heldStreamVerificationDoesNotCloseItsValidSource() {
        var closed = false
        val input = object : ByteArrayInputStream(png(1, 1, ByteArray(5))) {
            override fun close() { closed = true; super.close() }
        }
        assertTrue(PngRasterIntegrity.verifyIfPng(input))
        assertFalse(closed)
        assertEquals(-1, input.read())
    }

    @Test fun heldStreamFailureDoesNotCloseOrReopenItsSource() {
        var closed = false
        val bytes = png(1, 1, ByteArray(5)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        try { PngRasterIntegrity.verifyIfPng(input); fail("Invalid held bytes must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("CRC")) }
        assertFalse(closed)
    }

    @Test fun heldStreamCancellationRetainsCallerOwnership() {
        var closed = false
        val input = object : ByteArrayInputStream(png(32, 32, ByteArray(32 * (32 * 4 + 1)))) {
            override fun close() { closed = true; super.close() }
        }
        var checks = 0
        try {
            PngRasterIntegrity.verifyIfPng(input) { if (++checks == 9) throw CancellationException("retired") }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) { assertEquals("retired", expected.message) }
        assertFalse(closed)
    }

    @Test fun recomputedChunkCrcDoesNotMakeZeroedIdatAValidRaster() {
        val encoded = deflate(ByteArray(1 + 32 * 4))
        val file = write(png(32, 1, ByteArray(encoded.size), encoded = true))
        val before = file.readBytes()
        rejects(file, "zlib")
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun invalidAdlerBodyIsRejectedEvenWithARecomputedIdatCrc() {
        val encoded = deflate(byteArrayOf(0, 0, 0, 0, 0))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        rejects(write(png(1, 1, encoded, encoded = true)), "Adler")
    }

    @Test fun legitimateTransparentAndOpaqueBlankPngsArePreserved() {
        for (raw in listOf(byteArrayOf(0, 0, 0, 0, 0), byteArrayOf(0, 0, 0, 0, 255.toByte()))) {
            val bytes = png(1, 1, raw); val file = write(bytes)
            assertTrue(PngRasterIntegrity.verifyIfPng(file))
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun aValidZlibChecksumCannotHideMissingScanlines() {
        rejects(write(png(1, 2, byteArrayOf(0, 0, 0, 0, 0))), "scanlines")
    }

    @Test fun aValidZlibChecksumCannotHideExtraRasterBytes() {
        rejects(write(png(1, 1, byteArrayOf(0, 0, 0, 0, 0, 0))), "scanline budget")
    }

    @Test fun invalidScanlineFilterIsRejectedWithoutPixelGuessing() {
        rejects(write(png(1, 1, byteArrayOf(5, 0, 0, 0, 0))), "filter")
    }

    @Test fun splitIdatRetainsTheSameSingleZlibStream() {
        val data = deflate(ByteArray(1 + 32 * 4))
        val bytes = png(32, 1, data, encoded = true, splitAt = 3)
        assertTrue(PngRasterIntegrity.verifyIfPng(write(bytes)))
    }

    @Test fun aSecondZlibMemberIsNotOriginalPngRasterEvidence() {
        val member = deflate(byteArrayOf(0, 0, 0, 0, 0))
        rejects(write(png(1, 1, member + member, encoded = true)), "extra compressed")
    }

    @Test fun packedPaletteAndSixteenBitScanlinesUseTheirActualEncodedWidths() {
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(9, 1, byteArrayOf(0, 0, 0), depth = 1, color = 3))))
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(9), depth = 16))))
    }

    @Test fun adam7PassesIncludeOnlyTheirPresentRowsAndRoundedPackedWidths() {
        // 3x3 RGBA8 Adam7: passes 1,4,5,6,7 contain respectively 5,5,9,10,13 bytes.
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(3, 3, ByteArray(42), interlace = 1))))
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(5), interlace = 1))))
        rejects(write(png(3, 3, ByteArray(41), interlace = 1)), "scanlines")
    }

    @Test fun pixelAndEncodedBudgetsRejectBeforeAnUnboundedRasterAllocation() {
        val file = write(png(1000, 1000, ByteArray(5)))
        try { PngRasterIntegrity.verifyIfPng(file, maxPixels = 100L); fail("Original pixel limit must apply") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("pixel budget")) }
        try { PngRasterIntegrity.verifyIfPng(file, maxBytes = file.length() - 1); fail("Original encoded limit must apply") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("byte budget")) }
    }

    @Test fun cancellationCheckpointPropagatesAndRetainsTheOriginalFile() {
        val file = write(png(256, 256, ByteArray(256 * (256 * 4 + 1))))
        val before = file.readBytes(); var checks = 0
        try {
            PngRasterIntegrity.verifyIfPng(file) { if (++checks >= 14) throw CancellationException("captured owner cancelled") }
            fail("Owner cancellation must escape verification")
        } catch (expected: CancellationException) { assertEquals("captured owner cancelled", expected.message) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun unrelatedFormatsRemainOwnedByTheirActualDecoder() {
        val file = write("RIFF valid or invalid WebP belongs to BitmapFactory".toByteArray())
        val before = file.readBytes()
        assertFalse(PngRasterIntegrity.verifyIfPng(file))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun lowercaseReservedChunkTypeBitIsNotAValidPngExtension() {
        rejects(write(png(1, 1, ByteArray(5), extraChunk = "abca")), "reserved")
    }

    @Test fun grayscaleAndGrayAlphaCannotContainAForbiddenPalette() {
        for ((color, rowBytes) in listOf(0 to 2, 4 to 3))
            rejects(write(png(1, 1, ByteArray(rowBytes), color = color, paletteEntries = 2)), "grayscale")
    }

    @Test fun indexedPaletteCannotExceedItsEncodedBitDepth() {
        rejects(write(png(1, 1, ByteArray(2), depth = 1, color = 3, paletteEntries = 3)), "bit depth")
    }

    @Test fun strictIntegrityRejectsTrailingBytesAfterIend() {
        rejects(write(png(1, 1, ByteArray(5)) + byteArrayOf(0, 1)), "trailing")
    }

    @Test fun contiguousEmptyIdatChunksDoNotInvalidateRealTransparentPixels() {
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(5), emptyIdat = true))))
    }

    @Test fun truncatedChunkCannotPassOnAlreadyReadRasterRows() {
        val complete = png(1, 1, ByteArray(5))
        rejects(write(complete.copyOf(complete.size - 7)), "truncated")
    }

    @Test fun emptyInputRemainsAnUnsupportedInputForTheExistingDecoder() {
        assertFalse(PngRasterIntegrity.verifyIfPng(write(ByteArray(0))))
    }

    @Test fun indexedPngWithAZeroAlphaTransparencyEntryRemainsValid() {
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(2), depth = 1, color = 3,
            transparency = byteArrayOf(0, 255.toByte())))))
    }

    @Test fun legitimateGrayscaleAndGrayAlphaRowsKeepTheirActualBitDepths() {
        for ((depth, color, rowBytes) in listOf(Triple(8, 0, 2), Triple(16, 0, 3), Triple(8, 4, 3), Triple(16, 4, 5)))
            assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(rowBytes), depth = depth, color = color))))
    }

    @Test fun unknownLegalAncillaryChunksBeforeAndAfterRasterRemainCompatible() {
        assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(5), extraChunk = "vpAg", afterDataChunk = "vpBg"))))
    }

    @Test fun aBadChunkCrcCannotPassBecauseTheRasterInflates() {
        val bytes = png(1, 1, ByteArray(5))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        rejects(write(bytes), "CRC")
    }

    @Test fun separatedIdatChunksCannotJoinAcrossAnAncillaryChunk() {
        rejects(write(png(1, 1, ByteArray(5), splitAt = 3, separateIdat = true)), "IDAT order")
    }

    @Test fun rgbAndRgbaCanRetainTheirOptionalSuggestedPalette() {
        for ((color, rowBytes) in listOf(2 to 4, 6 to 5))
            assertTrue(PngRasterIntegrity.verifyIfPng(write(png(1, 1, ByteArray(rowBytes), color = color, paletteEntries = 2))))
    }

    private fun rejects(file: File, message: String) {
        try { PngRasterIntegrity.verifyIfPng(file); fail("Invalid original PNG must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message, expected.message.orEmpty().contains(message)) }
    }
    private fun write(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun deflate(raw: ByteArray) = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(raw) }; output.toByteArray()
    }
    private fun png(width: Int, height: Int, raw: ByteArray, encoded: Boolean = false, splitAt: Int? = null,
        depth: Int = 8, color: Int = 6, interlace: Int = 0, paletteEntries: Int? = if (color == 3) 2 else null,
        extraChunk: String? = null, emptyIdat: Boolean = false, afterDataChunk: String? = null,
        transparency: ByteArray? = null, separateIdat: Boolean = false): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream().use { header ->
                DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.writeByte(depth); it.writeByte(color)
                    it.writeByte(0); it.writeByte(0); it.writeByte(interlace) }; header.toByteArray()
            }
            chunk(output, "IHDR", ihdr)
            if (paletteEntries != null) chunk(output, "PLTE", ByteArray(paletteEntries * 3))
            if (transparency != null) chunk(output, "tRNS", transparency)
            if (extraChunk != null) chunk(output, extraChunk, ByteArray(0))
            val data = if (encoded) raw else deflate(raw)
            if (emptyIdat) chunk(output, "IDAT", ByteArray(0))
            if (splitAt == null) chunk(output, "IDAT", data) else {
                chunk(output, "IDAT", data.copyOfRange(0, splitAt))
                if (separateIdat) chunk(output, "vpAg", ByteArray(0))
                chunk(output, "IDAT", data.copyOfRange(splitAt, data.size))
            }
            if (emptyIdat) chunk(output, "IDAT", ByteArray(0))
            if (afterDataChunk != null) chunk(output, afterDataChunk, ByteArray(0))
            chunk(output, "IEND", ByteArray(0))
        }; bytes.toByteArray()
    }
    private fun chunk(output: DataOutputStream, type: String, bytes: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(bytes) }
        output.writeInt(bytes.size); output.write(name); output.write(bytes); output.writeInt(crc.value.toInt())
    }
}
