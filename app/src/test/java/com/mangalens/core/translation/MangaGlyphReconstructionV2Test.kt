package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class MangaGlyphReconstructionV2Test {
    private fun rgb(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v
    @Test fun allUntouchedPixelsAndAlphaRemainExact() {
        val original = IntArray(9 * 7) { it * 193783 }; val copy = original.clone()
        assertArrayEquals(copy, MangaGlyphReconstructionV2.reconstruct(original, BooleanArray(original.size), 9, 7, false, 0, 0, 8)); assertArrayEquals(copy, original)
    }
    @Test fun asymmetricHorizontalDistancesRecoverLinearSlope() {
        val pixels = IntArray(11) { rgb(20 + it * 10) }; val expected = pixels.clone(); val mask = BooleanArray(11) { it in 2..7 }
        for (i in pixels.indices) if (mask[i]) pixels[i] = rgb(255)
        val result = MangaGlyphReconstructionV2.reconstruct(pixels, mask, 11, 1, false, 0, 0, 10)
        assertArrayEquals(expected, result)
    }
    @Test fun asymmetricVerticalDistancesRecoverLinearSlope() {
        val pixels = IntArray(11) { rgb(20 + it * 10) }; val expected = pixels.clone(); val mask = BooleanArray(11) { it in 2..7 }
        for (i in pixels.indices) if (mask[i]) pixels[i] = rgb(255)
        assertArrayEquals(expected, MangaGlyphReconstructionV2.reconstruct(pixels, mask, 1, 11, false, 0, 0, 10))
    }
    @Test fun lowDiscontinuityAxisKeepsDarkDrawingEdge() {
        val pixels = IntArray(15 * 11) { if (it % 15 < 8) rgb(20) else rgb(220) }; val original = pixels.clone()
        val mask = BooleanArray(pixels.size) { it % 15 == 6 && it / 15 in 3..7 }; for (i in pixels.indices) if (mask[i]) pixels[i] = rgb(255)
        assertArrayEquals(original, MangaGlyphReconstructionV2.reconstruct(pixels, mask, 15, 11, false, 0, rgb(150), 8))
    }
    @Test fun unsupportedFullyMaskedArtworkDefersInsteadOfSolidFill() {
        try { MangaGlyphReconstructionV2.reconstruct(IntArray(5) { rgb(20) }, BooleanArray(5) { true }, 5, 1, false, 0, rgb(150), 4); fail() }
        catch (_: MangaReconstructionDeferredException) { }
    }
    @Test fun knownUniformSurfaceMayUseExactPaperFallback() {
        val paper = rgb(210)
        assertArrayEquals(IntArray(5) { paper }, MangaGlyphReconstructionV2.reconstruct(IntArray(5) { rgb(20) }, BooleanArray(5) { true }, 5, 1, true, paper, paper, 4))
    }
    @Test fun sourceAndMaskAreNeverMutated() {
        val pixels = IntArray(49) { rgb(120) }; val mask = BooleanArray(49); mask[24] = true; pixels[24] = rgb(0)
        val before = pixels.clone(); val maskBefore = mask.clone()
        MangaGlyphReconstructionV2.reconstruct(pixels, mask, 7, 7, false, 0, 0, 4)
        assertArrayEquals(before, pixels); assertArrayEquals(maskBefore, mask)
    }
    @Test fun cancellationReturnsBeforeContinuingAnotherRowBand() {
        var checks = 0
        try { MangaGlyphReconstructionV2.reconstruct(IntArray(40 * 40), BooleanArray(40 * 40), 40, 40, false, 0, 0, 4) { if (++checks == 2) throw java.util.concurrent.CancellationException() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { assertEquals(2, checks) }
    }
    @Test fun invalidSearchAndDimensionsFailClosed() {
        for (search in listOf(0, 49)) try { MangaGlyphReconstructionV2.reconstruct(intArrayOf(0), booleanArrayOf(false), 1, 1, false, 0, 0, search); fail() } catch (_: IllegalArgumentException) { }
        try { MangaGlyphReconstructionV2.reconstruct(IntArray(2), BooleanArray(2), 2, 2, false, 0, 0, 4); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun noCleanNeighbourInsideBoundedSearchDefers() {
        val pixels = IntArray(25) { rgb(20) }; val mask = BooleanArray(25) { it in 5..19 }
        try { MangaGlyphReconstructionV2.reconstruct(pixels, mask, 25, 1, false, 0, 0, 2); fail() } catch (_: MangaReconstructionDeferredException) { }
    }
}
