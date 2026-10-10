package com.mangalens.ui.ai.voice

internal object OfflineOrezVoicePolicy {
    const val TINY_BYTES = 77_691_713L
    const val TINY_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    const val SAMPLE_RATE = 16_000
    const val MAX_CAPTURE_MS = 15_000L
    const val MAX_SAMPLES = 240_000
    const val NATIVE_PHASE_MS = 20_000L
    fun acceptsModel(bytes: Long, sha256: String) = bytes == TINY_BYTES && sha256 == TINY_SHA256
    fun transcript(parts: List<String>): String? = parts.map(String::trim).filter(String::isNotBlank)
        .joinToString(" ").takeIf { it.isNotBlank() && it.length <= 4_000 }
    fun mergeDraft(draft: String, transcript: String): String? =
        (if (draft.isEmpty() || draft.last().isWhitespace()) draft + transcript else "$draft $transcript")
            .takeIf { it.length <= 8_000 }
    fun offlineVoices(voices: List<OfflineVoiceChoice>): List<OfflineVoiceChoice> = voices
        .filter { !it.networkRequired && it.name.isNotBlank() && it.name.length <= 256 && it.localeTag.isNotBlank() && it.localeTag.length <= 48 }.distinctBy { it.name }.sortedWith(compareBy({ it.localeTag }, { it.name })).take(64)
    fun spokenText(text: String, max: Int): String? = text.substringBefore("\n\nSources:")
        .substringBefore("\n\nVideo results:\n").trim().takeIf { it.isNotBlank() && it.length <= minOf(max, 4_000) }
}

internal data class OfflineVoiceChoice(val name: String, val localeTag: String, val networkRequired: Boolean)
internal data class VoiceDraftCapture(val routeScope: String, val revision: Long, val draft: String, val token: Long) {
    fun matches(scope: String, currentRevision: Long, currentDraft: String, ownerToken: Long?, resumed: Boolean): Boolean =
        resumed && scope == routeScope && revision == currentRevision && draft == currentDraft && ownerToken == token
}

/** One RAM buffer, never persisted. snapshot is owned until actual inference returns; clear wipes both. */
internal class BoundedVoicePcm {
    private val samples = FloatArray(OfflineOrezVoicePolicy.MAX_SAMPLES)
    private var submitted: FloatArray? = null
    var size = 0; private set
    val full get() = size == samples.size
    fun append(input: ShortArray, count: Int) {
        require(count in 0..input.size)
        val take = minOf(count, samples.size - size)
        repeat(take) { samples[size++] = input[it] / 32_768f }
    }
    fun snapshot(): FloatArray = samples.copyOf(size).also { submitted = it }
    fun clear() { samples.fill(0f); submitted?.fill(0f); submitted = null; size = 0 }
}
