package com.mangalens.ui.ai.voice

import java.util.concurrent.atomic.AtomicBoolean

internal interface VoiceAudioPort {
    val initialized: Boolean
    fun start()
    fun read(samples: ShortArray, count: Int): Int
    fun stop()
    fun release()
}

/** One actual capture close path, used only by its producer after the actual read returned. */
internal class OwnedVoiceAudio(private val port: VoiceAudioPort) {
    private val reading = AtomicBoolean(false)
    private var startAttempted = false
    private var stopAttempted = false
    private var releaseAttempted = false
    private var released = false
    private var cleanupFailed = false
    val initialized get() = port.initialized
    fun start() { check(!startAttempted && !releaseAttempted); startAttempted = true; port.start() }
    fun read(samples: ShortArray, count: Int): Int {
        check(startAttempted && !releaseAttempted && reading.compareAndSet(false, true))
        try { return port.read(samples, count) } finally { reading.set(false) }
    }
    fun releaseAfterRead(): Boolean {
        check(!reading.get()) { "The actual microphone read still owns this source." }
        if (startAttempted && !stopAttempted) {
            stopAttempted = true
            try { port.stop() } catch (_: Exception) { cleanupFailed = true }
        }
        if (!releaseAttempted) {
            releaseAttempted = true
            try { port.release(); released = true } catch (_: Exception) { cleanupFailed = true }
        }
        return released && !cleanupFailed
    }
}
