package com.mangalens.core.search.embedding

import org.junit.Assert.*
import org.junit.Test

class SemanticEmbeddingMathTest {
    private fun hidden(vararg rows: FloatArray): FloatArray = rows.flatMap { it.toList() }.toFloatArray()
    private fun row(x: Float = 0f, y: Float = 0f) = FloatArray(384).also { it[0] = x; it[1] = y }
    @Test fun meanIncludesBoundaryTokensAndExcludesPadding() {
        val vector = SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(1f), row(0f, 3f), row(100f, 100f)), longArrayOf(1, 1, 0))
        assertEquals((1.0 / kotlin.math.sqrt(10.0)).toFloat(), vector[0], 1e-6f)
        assertEquals((3.0 / kotlin.math.sqrt(10.0)).toFloat(), vector[1], 1e-6f)
    }
    @Test fun identicalUnitVectorsScoreOne() { assertEquals(1f, SemanticEmbeddingMath.cosine(row(1f), row(1f)), 1e-6f) }
    @Test fun oppositeAndOrthogonalActualVectorsRemainDistinct() {
        assertEquals(-1f, SemanticEmbeddingMath.cosine(row(1f), row(-1f)), 1e-6f)
        assertEquals(0f, SemanticEmbeddingMath.cosine(row(1f), row(0f, 1f)), 1e-6f)
    }
    @Test fun wrongHiddenShapeFails() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(FloatArray(383), longArrayOf(1, 1)) } }
    @Test fun unexpectedMaskValuesFail() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(1f), row(1f)), longArrayOf(1, 2)) } }
    @Test fun onlyOneSelectedTokenFails() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(1f), row(1f)), longArrayOf(1, 0)) } }
    @Test fun zeroPooledOutputFails() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(1f), row(-1f)), longArrayOf(1, 1)) } }
    @Test fun nonfiniteMaskedPaddingStillFails() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(1f), row(1f), row(Float.NaN)), longArrayOf(1, 1, 0)) } }
    @Test fun infiniteNativeOutputFails() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.maskedMeanNormalized(hidden(row(Float.POSITIVE_INFINITY), row(1f)), longArrayOf(1, 1)) } }
    @Test fun nonunitCacheVectorIsRejected() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.validate(row(2f)) } }
    @Test fun wrongEmbeddingDimensionIsRejected() { assertThrows(IllegalArgumentException::class.java) { SemanticEmbeddingMath.validate(FloatArray(768)) } }
}
