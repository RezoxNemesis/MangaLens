package com.mangalens.oreznative

import androidx.annotation.Keep

/** JNI uses this constructor directly. Legacy text generation keeps its existing return contract. */
@Keep
data class NativeGenerationResult(
    val text: String,
    val termination: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokenLimit: Int,
    val nativeLockWaitUs: Long,
    val setupUs: Long,
    val prefillUs: Long,
    val decodeUs: Long
)
