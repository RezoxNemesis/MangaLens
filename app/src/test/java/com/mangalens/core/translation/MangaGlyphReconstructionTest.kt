package com.mangalens.core.translation

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MangaGlyphReconstructionTest {
    private data class Raster(val width: Int, val height: Int, val pixels: IntArray, val mask: BooleanArray,
        val uniform: Boolean = false, val surface: Int = rgb(255, 255, 255),
        val fallback: Int = rgb(230, 220, 210), val search: Int = 48)

    private fun compare(raster: Raster): IntArray {
        val pixels = raster.pixels.clone(); val mask = raster.mask.clone()
        val expected = LegacyGlyphReconstruction.reconstruct(raster.pixels, raster.mask, raster.width, raster.height,
            raster.uniform, raster.surface, raster.fallback, raster.search)
        val actual = MangaGlyphReconstruction.reconstruct(raster.pixels, raster.mask, raster.width, raster.height,
            raster.uniform, raster.surface, raster.fallback, raster.search)
        assertArrayEquals("Pixel reconstruction changed", expected, actual)
        assertArrayEquals("Source raster mutated", pixels, raster.pixels)
        assertArrayEquals("Glyph mask mutated", mask, raster.mask)
        for (index in mask.indices) if (!mask[index]) assertEquals("Unmasked artwork changed at $index", pixels[index], actual[index])
        return actual
    }

    @Test fun knownWhitePaperInsideMaskPreservesExactPixels() {
        val white = rgb(255, 255, 255)
        val raster = Raster(41, 33, IntArray(41 * 33) { white }, BooleanArray(41 * 33) { true }, uniform = true)
        assertArrayEquals(raster.pixels, compare(raster))
    }

    @Test fun glyphsOnTintedPaperKeepBorderAndOpaqueSurface() {
        val paper = rgb(209, 200, 184); val width = 64; val height = 38
        val pixels = IntArray(width * height) { paper }
        val mask = BooleanArray(pixels.size)
        for (y in 8..28) for (x in 8..55) {
            mask[y * width + x] = true
            if (x % 7 in 2..3 && y in 12..23) pixels[y * width + x] = rgb(0, 0, 0)
        }
        for (y in 0 until height) pixels[y * width] = rgb(45, 48, 58)
        val actual = compare(Raster(width, height, pixels, mask, uniform = true, surface = paper, fallback = paper))
        assertEquals(paper, actual[18 * width + 20])
        assertEquals(rgb(45, 48, 58), actual[18 * width])
    }

    @Test fun gradientAndTextureReconstructionMatchesEveryLegacyPixel() {
        val width = 47; val height = 31
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            rgb(190 + y + x % 3, 176 + y + x % 4, 153 + y + x % 5)
        }
        val mask = BooleanArray(pixels.size) { index -> index % width in 0..32 && index / width in 9..19 && index % 3 != 1 }
        for (index in pixels.indices) if (mask[index]) pixels[index] = rgb(15, 20, 25)
        compare(Raster(width, height, pixels, mask))
    }

    @Test fun diagonalSamplesAndMissingAxesRetainLegacyRounding() {
        val width = 11; val height = 11
        val pixels = IntArray(width * height) { rgb(0, 0, 0) }
        val mask = BooleanArray(pixels.size) { true }
        listOf(3 to 3, 7 to 3, 3 to 7, 7 to 7).forEachIndexed { index, (x, y) ->
            pixels[y * width + x] = rgb(180 + index * 11, 173 + index * 13, 169 + index * 17)
            mask[y * width + x] = false
        }
        compare(Raster(width, height, pixels, mask, search = 4))
    }

    @Test fun conflictingAxesRetainNearestFallbackColorChoice() {
        val width = 17; val height = 15; val fallback = rgb(230, 220, 210)
        val pixels = IntArray(width * height) { index -> if (index % width <= 8) fallback else rgb(20, 30, 40) }
        val mask = BooleanArray(pixels.size) { index -> index % width in 6..10 && index / width in 5..9 }
        compare(Raster(width, height, pixels, mask, fallback = fallback))
    }

    @Test fun uniformSamplesRejectGreyPanelAndBlackBalloonOutline() {
        val width = 35; val height = 29; val white = rgb(255, 255, 255)
        val pixels = IntArray(width * height) { index -> if (index % width in 3..31 && index / width in 3..25) white else rgb(235, 235, 235) }
        val mask = BooleanArray(pixels.size) { index -> index % width in 7..27 && index / width in 7..21 }
        for (y in 3..25) pixels[y * width + 3] = rgb(0, 0, 0)
        for (y in 10..17) for (x in 10..24) pixels[y * width + x] = rgb(0, 0, 0)
        val actual = compare(Raster(width, height, pixels, mask, uniform = true, surface = white, fallback = white))
        assertEquals(white, actual[14 * width + 17])
        assertEquals(rgb(0, 0, 0), actual[14 * width + 3])
    }

    @Test fun fullyMaskedRasterUsesFallbackIncludingEdges() {
        val fallback = rgb(26, 32, 45)
        val raster = Raster(13, 9, IntArray(13 * 9) { rgb(220, 210, 200) }, BooleanArray(13 * 9) { true }, fallback = fallback)
        assertArrayEquals(IntArray(raster.pixels.size) { fallback }, compare(raster))
    }

    @Test fun singlePixelAndNarrowRastersDoNotSampleOutsideImage() {
        for ((width, height) in listOf(1 to 1, 1 to 19, 23 to 1, 2 to 2)) {
            val raster = Raster(width, height, IntArray(width * height) { rgb(0, 0, 0) }, BooleanArray(width * height) { true })
            compare(raster)
        }
    }

    @Test fun unmaskedRasterPreservesAlphaAndEveryColorChannel() {
        val pixels = IntArray(29 * 17) { index -> ((index % 256) shl 24) or ((index * 101) and 0xffffff) }
        assertArrayEquals(pixels, compare(Raster(29, 17, pixels, BooleanArray(pixels.size))))
    }

    @Test fun randomizedMasksTexturesAlphaAndSearchLimitsMatchLegacyExactly() {
        val random = Random(8231507)
        repeat(180) { iteration ->
            val width = random.nextInt(1, 30); val height = random.nextInt(1, 23)
            val surface = random.nextInt(); val fallback = random.nextInt()
            val pixels = IntArray(width * height) { if (random.nextInt(5) == 0) surface else random.nextInt() }
            val mask = BooleanArray(pixels.size) { random.nextBoolean() }
            try { compare(Raster(width, height, pixels, mask, iteration % 2 == 0, surface, fallback, random.nextInt(1, 49))) }
            catch (failure: AssertionError) { throw AssertionError("Raster iteration $iteration ($width x $height)", failure) }
        }
    }

    @Test fun representativeBalloonAllocatesOnlyBoundedImageArrays() {
        // AGP compiles JVM tests against Android's API surface, which omits JMX.
        // Reflect on the test JVM's public interfaces rather than importing them.
        val managementFactory = Class.forName("java.lang.management.ManagementFactory")
        val allocationInterface = Class.forName("com.sun.management.ThreadMXBean")
        val bean = managementFactory.getMethod("getThreadMXBean").invoke(null)
        assertTrue("Required test JVM lacks allocation accounting", allocationInterface.isInstance(bean))
        assertEquals(true, allocationInterface.getMethod("isThreadAllocatedMemorySupported").invoke(bean))
        allocationInterface.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
            .invoke(bean, true)
        val allocatedBytes = allocationInterface.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun threadBytes(): Long = (allocatedBytes.invoke(bean, Thread.currentThread().id) as Number).toLong()
        val width = 96; val height = 64; val white = rgb(255, 255, 255)
        val pixels = IntArray(width * height) { index -> if (index % width in 20..76 && index / width in 24..39 && index % 5 < 2) rgb(0, 0, 0) else white }
        val mask = BooleanArray(pixels.size) { index -> index % width in 12..83 && index / width in 16..47 }
        repeat(2) { MangaGlyphReconstruction.reconstruct(pixels, mask, width, height, true, white, white, 48) }
        val allocated = (1..3).minOf {
            val before = threadBytes()
            val result = MangaGlyphReconstruction.reconstruct(pixels, mask, width, height, true, white, white, 48)
            val bytes = threadBytes() - before
            assertEquals(white, result[32 * width + 48])
            bytes
        }
        assertTrue("Glyph reconstruction allocated $allocated bytes for ${pixels.size} pixels; per-pixel search must not allocate lists/boxed candidates",
            allocated <= pixels.size * 16L + 64L * 1024)
    }

    companion object {
        private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}
