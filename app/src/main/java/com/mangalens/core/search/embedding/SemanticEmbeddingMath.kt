package com.mangalens.core.search.embedding

import kotlin.math.sqrt

/** Consume the real float hidden-state tensor; masked mean pooling includes CLS and SEP. */
internal object SemanticEmbeddingMath {
    fun maskedMeanNormalized(hidden: FloatArray, mask: LongArray, dimensions: Int = SemanticEmbeddingPin.DIMENSIONS): FloatArray {
        require(dimensions == SemanticEmbeddingPin.DIMENSIONS && mask.size in 2..SemanticEmbeddingPin.TOKENS)
        require(hidden.size == mask.size * dimensions && mask.all { it == 0L || it == 1L })
        val selected = mask.count { it == 1L }; require(selected >= 2)
        val pooled = DoubleArray(dimensions)
        for (token in mask.indices) for (dimension in 0 until dimensions) {
            val value = hidden[token * dimensions + dimension]
            require(value.isFinite())
            if (mask[token] == 1L) pooled[dimension] += value.toDouble()
        }
        for (index in pooled.indices) pooled[index] /= selected.toDouble()
        val norm = sqrt(pooled.sumOf { it * it }); require(norm.isFinite() && norm > 1e-12)
        return FloatArray(dimensions) { (pooled[it] / norm).toFloat() }.also { validate(it) }
    }
    fun validate(vector: FloatArray) {
        require(vector.size == SemanticEmbeddingPin.DIMENSIONS && vector.all { it.isFinite() })
        val norm = sqrt(vector.sumOf { it.toDouble() * it }); require(norm in 0.999..1.001)
    }
    fun cosine(first: FloatArray, second: FloatArray): Float {
        validate(first); validate(second)
        return first.indices.sumOf { first[it].toDouble() * second[it] }.coerceIn(-1.0, 1.0).toFloat()
    }
}
