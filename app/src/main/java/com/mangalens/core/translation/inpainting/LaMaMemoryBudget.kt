package com.mangalens.core.translation.inpainting

/** Conservative headroom estimates, not measured device memory or a speed/quality promise. */
internal object LaMaMemoryBudget {
    const val JAVA_SCRATCH = 48L * 1024 * 1024
    const val NATIVE_SESSION_ESTIMATE = 384L * 1024 * 1024
    fun permits(javaAvailable: Long, systemAvailable: Long, systemThreshold: Long, lowMemory: Boolean,
        needsWeights: Boolean, needsSession: Boolean): Boolean {
        if (lowMemory || javaAvailable < 0 || systemAvailable < 0 || systemThreshold < 0) return false
        val weights = if (needsWeights) LaMaReconstructionPin.MODEL_BYTES.toLong() else 0L
        val native = if (needsSession) NATIVE_SESSION_ESTIMATE else 32L * 1024 * 1024
        return javaAvailable >= weights + JAVA_SCRATCH && systemAvailable >= weights + native + systemThreshold
    }
}
