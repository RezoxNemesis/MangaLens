package com.mangalens.oreznative

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OrezNativeEngine {
    companion object {
        val available: Boolean = runCatching { System.loadLibrary("mangalens_orez_native"); true }.getOrDefault(false)
        // JNI currently shares one physical model; each feature holds its own lease.
        private val modelLock = Any()
        private val modelOwners = mutableSetOf<OrezNativeEngine>()
    }

    private var loaded = false
    private val generations = ConcurrentHashMap.newKeySet<Generation>()

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, requestId: Long): String
    private external fun nativeUnload()
    private external fun nativeCreateRequest(): Long
    private external fun nativeCancelRequest(requestId: Long)
    private external fun nativeReleaseRequest(requestId: Long)
    private external fun nativeRequestRunning(requestId: Long): Boolean

    class Generation internal constructor(internal val engine: OrezNativeEngine, internal val id: Long) : AutoCloseable {
        private val released = AtomicBoolean(false)
        val isRunning: Boolean get() = id != 0L && !released.get() && engine.nativeRequestRunning(id)
        fun cancel() { if (id != 0L && !released.get()) engine.nativeCancelRequest(id) }
        override fun close() {
            if (released.compareAndSet(false, true)) {
                if (id != 0L) engine.nativeReleaseRequest(id)
                engine.generations.remove(this)
            }
        }
    }

    fun newGeneration(): Generation = Generation(this, if (available) nativeCreateRequest() else 0L)
        .also { generations.add(it) }

    // Uses only the short request registry lock, never a model/generation lock.
    fun cancelGenerations() { generations.forEach { it.cancel() } }

    @Synchronized
    fun load(path: String): Boolean = available && synchronized(modelLock) {
        nativeLoad(path).also { success ->
            if (success) { loaded = true; modelOwners.add(this) }
        }
    }

    fun generate(prompt: String, maxTokens: Int = 384): String = newGeneration().use { generate(prompt, maxTokens, it) }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int, generation: Generation): String {
        require(generation.engine === this) { "Generation belongs to another engine" }
        return if (available && loaded) nativeGenerate(prompt.take(24_000), maxTokens.coerceIn(1, 512), generation.id) else ""
    }

    fun close() {
        cancelGenerations()
        synchronized(this) { releaseModel() }
    }

    private fun releaseModel() {
        if (!available) return
        synchronized(modelLock) {
            loaded = false
            if (modelOwners.remove(this) && modelOwners.isEmpty()) nativeUnload()
        }
    }
}
