package com.mangalens.orez

import java.io.File

/** Durable weight identity; a pin grants no readiness until the model store verifies it. */
data class OrezModelPin(val modelId: String, val sha256: String, val bytes: Long)

data class OrezModelAnswer @JvmOverloads constructor(val text: String, val model: OrezModelPin,
    val completion: OrezGenerationCompletion? = null)

internal data class OrezModelCandidate(val file: File, val pin: OrezModelPin)

internal object OrezPinnedModelPolicy {
    fun valid(pin: OrezModelPin): Boolean = pin.modelId.length in 1..128 &&
        pin.modelId.matches(Regex("[a-zA-Z0-9._-]+")) && pin.sha256.matches(Regex("[a-f0-9]{64}")) &&
        pin.bytes in 1..8_000_000_000L

    fun select(pin: OrezModelPin?, candidates: List<OrezModelCandidate>): List<OrezModelCandidate> {
        if (pin != null && !valid(pin)) return emptyList()
        return candidates.filter { valid(it.pin) && (pin == null || it.pin == pin) }
            .distinctBy { it.file.absolutePath }
    }
}
