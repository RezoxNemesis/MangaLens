package com.mangalens.ui.video

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Captures the controls and installed-model epoch alongside a real PCM window. */
internal data class LiveSpeechControlSnapshot(
    val closed: Boolean,
    val enabled: Boolean,
    val ready: Boolean,
    val generation: Long,
    val modelEpoch: Long,
    val sourceLanguage: String
)

internal data class LiveSpeechWindowSnapshot(
    val generation: Long,
    val modelEpoch: Long,
    val sourceLanguage: String,
    val enqueuedAtMs: Long
) {
    fun inferenceLanguage(detectedLanguage: String?): String =
        if (sourceLanguage == "auto") detectedLanguage ?: "auto" else sourceLanguage
}

internal class LiveSpeechWindowSnapshots(
    private val guard: Any,
    private val clock: () -> Long,
    private val controls: () -> LiveSpeechControlSnapshot
) {
    fun capture(): LiveSpeechWindowSnapshot? = synchronized(guard) {
        val current = controls()
        if (current.closed || !current.enabled || !current.ready) null
        else LiveSpeechWindowSnapshot(current.generation, current.modelEpoch, current.sourceLanguage, clock())
    }

    fun isCurrent(window: LiveSpeechWindowSnapshot): Boolean = synchronized(guard) {
        val current = controls()
        !current.closed && current.enabled && current.ready &&
            current.generation == window.generation && current.modelEpoch == window.modelEpoch &&
            current.sourceLanguage == window.sourceLanguage
    }
}

/** The consumer inherits enqueue time through admission and actual native cleanup. */
internal class NativeLiveSpeechEnqueuedAt(val elapsedRealtimeMs: Long) :
    AbstractCoroutineContextElement(Key) {
    init { require(elapsedRealtimeMs >= 0) }
    companion object Key : CoroutineContext.Key<NativeLiveSpeechEnqueuedAt>
}
