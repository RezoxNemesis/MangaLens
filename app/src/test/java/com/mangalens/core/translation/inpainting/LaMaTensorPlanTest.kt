package com.mangalens.core.translation.inpainting

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Authored UNRUN tensor/mask controls. Synthetic output is not model inference or artwork quality evidence. */
class LaMaTensorPlanTest {
    @Test fun nchwInputUsesRgbUnitScaleAndBinaryOneMeansRepair() {
        val input = LaMaTensorPlan.prepare(2, 1, intArrayOf(0xff204080.toInt(), -1), booleanArrayOf(true, false))
        assertEquals(32f / 255, input.image[0], 0f); assertEquals(64f / 255, input.image[262144], 0f)
        assertEquals(128f / 255, input.image[524288], 0f)
        assertEquals(1f, input.mask[0], 0f); assertEquals(0f, input.mask[1], 0f)
    }
    @Test fun nonSquareOriginalIsReflectedIntoPaddingWithoutStretchingOrMaskingPadding() {
        val input = LaMaTensorPlan.prepare(3, 2, intArrayOf(0xff010000.toInt(), 0xff020000.toInt(), 0xff030000.toInt(),
            0xff040000.toInt(), 0xff050000.toInt(), 0xff060000.toInt()), booleanArrayOf(false, false, false, true, false, false))
        assertEquals(2f / 255, input.image[3], 0f); assertEquals(1f / 255, input.image[4], 0f)
        assertEquals(1f / 255, input.image[1024], 0f)
        assertEquals(1f, input.mask[512], 0f); assertEquals(0f, input.mask[1024], 0f)
    }
    @Test fun singlePixelPaddingDoesNotDivideByZeroOrRepeatTheMask() {
        val input = LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(true))
        assertEquals(1f, input.image.last(), 0f); assertEquals(1, input.mask.count { it == 1f })
    }
    @Test fun returnedPaddingNeverChangesActualOriginalPixels() {
        val original = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt(), 0xff102030.toInt(), 0xff304050.toInt())
        val input = LaMaTensorPlan.prepare(2, 2, original, booleanArrayOf(false, false, true, false))
        val output = FloatArray(786432) { 255f }
        val result = LaMaTensorPlan.composite(input, output)
        assertEquals(original[0], result[0]); assertEquals(original[1], result[1]); assertEquals(-1, result[2]); assertEquals(original[3], result[3])
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt(), 0xff102030.toInt(), 0xff304050.toInt()), original)
    }
    @Test fun outputIsPublisherByteScaleRatherThanUnitScale() {
        val input = LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(true))
        val output = FloatArray(786432); output[0] = 10.4f; output[262144] = 20.6f; output[524288] = 30f
        assertEquals(0xff0a151e.toInt(), LaMaTensorPlan.composite(input, output)[0])
    }
    @Test fun maskIdentityIncludesTheFixedPaddedPlane() {
        val input = LaMaTensorPlan.prepare(2, 1, intArrayOf(-1, -1), booleanArrayOf(true, false))
        val expected = ByteArray(262144).apply { this[0] = 1 }
        assertEquals(MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }, input.maskSha256)
    }
    @Test fun callerArraysCannotChangeCapturedTensorOrComposite() {
        val original = intArrayOf(-1, 0xff123456.toInt()); val mask = booleanArrayOf(true, false)
        val input = LaMaTensorPlan.prepare(2, 1, original, mask); original.fill(0); mask.fill(true)
        assertEquals(0xff123456.toInt(), LaMaTensorPlan.composite(input, FloatArray(786432))[1])
    }
    @Test fun wrongLengthEmptyMaskAndOversizeInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.prepare(2, 1, intArrayOf(-1), booleanArrayOf(true)) }
        assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(false)) }
        assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.prepare(513, 1, IntArray(513), BooleanArray(513) { true }) }
    }
    @Test fun transparentOriginalCannotBecomeAnRgbRepairPretendingToPreserveAlpha() {
        assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.prepare(1, 1, intArrayOf(0x00123456), booleanArrayOf(true)) }
    }
    @Test fun nonFiniteOutOfRangeAndMalformedPaddingOutputFailClosed() {
        val input = LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(true))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 255.01f)) {
            val output = FloatArray(786432).apply { this[lastIndex] = invalid }
            assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.composite(input, output) }
        }
        assertThrows(IllegalArgumentException::class.java) { LaMaTensorPlan.composite(input, FloatArray(1)) }
    }
    @Test fun cancellationStopsPaddingAndOutputValidationBeforeReturningPixels() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(true)) {
            throw kotlinx.coroutines.CancellationException()
        } }
        val input = LaMaTensorPlan.prepare(1, 1, intArrayOf(-1), booleanArrayOf(true))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { LaMaTensorPlan.composite(input, FloatArray(786432)) {
            throw kotlinx.coroutines.CancellationException()
        } }
    }
}
