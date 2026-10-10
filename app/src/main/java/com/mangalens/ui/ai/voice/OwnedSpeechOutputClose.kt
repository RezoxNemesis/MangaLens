package com.mangalens.ui.ai.voice

/** Both actual provider calls are attempted once, and failure never pretends capacity was released. */
internal class OwnedSpeechOutputClose(private val stop: () -> Unit, private val shutdown: () -> Unit) {
    private var attempted = false
    private var proven = false
    fun close(): Boolean {
        if (attempted) return proven
        attempted = true
        var stopReturned = false
        var shutdownReturned = false
        try { stop(); stopReturned = true } catch (_: Exception) { }
        try { shutdown(); shutdownReturned = true } catch (_: Exception) { }
        proven = stopReturned && shutdownReturned
        return proven
    }
}
