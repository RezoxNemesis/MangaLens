package com.mangalens.oreznative

import android.system.Os
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OrezNativeEngine {
    companion object {
        val available: Boolean = runCatching { System.loadLibrary("mangalens_orez_native"); true }.getOrDefault(false)
        // JNI currently shares one physical model; each feature holds its own lease.
        private val modelLeases = NativeModelLeaseRegistry<OrezNativeEngine>()
        private val memoryReleases = NativeMemoryReleaseCoordinator<OrezNativeEngine> {
            android.util.Log.w("MangaLens", "Local model memory release failed", it)
        }
        val sharedModelPath: String? get() = modelLeases.sharedPath
        @JvmOverloads
        fun trimMemory(scheduleRelease: ((() -> Unit) -> Unit)? = null) {
            memoryReleases.request(modelLeases.owners(), OrezNativeEngine::cancelGenerations,
                OrezNativeEngine::close, scheduleRelease)
        }
    }

    @Volatile private var loaded = false
    val isLoaded: Boolean get() = loaded
    private val generations = ConcurrentHashMap.newKeySet<Generation>()

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, requestId: Long): String
    private external fun nativeGenerateWithReceipt(prompt: String, maxTokens: Int, requestId: Long): NativeGenerationResult
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
    fun load(path: String): Boolean {
        if (!available) return false
        val identity = runCatching {
            val file = File(path).canonicalFile
            require(file.isFile && file.length() > 0L)
            val stat = Os.stat(file.path)
            ModelFileIdentity(file.path, stat.st_size, file.lastModified(), stat.st_dev, stat.st_ino)
        }.getOrNull() ?: return false
        return modelLeases.acquire(this, identity) { nativeLoad(identity.canonicalPath) }
            .also { success -> if (success) loaded = true }
    }

    fun generate(prompt: String, maxTokens: Int = 384): String = newGeneration().use { generate(prompt, maxTokens, it) }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int, generation: Generation): String {
        require(generation.engine === this) { "Generation belongs to another engine" }
        return if (available && loaded) nativeGenerate(prompt.take(24_000), maxTokens.coerceIn(1, 512), generation.id) else ""
    }

    fun generateWithReceipt(prompt: String, maxTokens: Int = 384): NativeGenerationResult =
        newGeneration().use { generateWithReceipt(prompt, maxTokens, it) }

    @Synchronized
    fun generateWithReceipt(prompt: String, maxTokens: Int, generation: Generation): NativeGenerationResult {
        require(generation.engine === this) { "Generation belongs to another engine" }
        val limit = maxTokens.coerceIn(1, 512)
        return if (available && loaded) nativeGenerateWithReceipt(prompt.take(24_000), limit, generation.id)
        else NativeGenerationResult("", "UNAVAILABLE", 0, 0, limit, 0, 0, 0, 0)
    }

    fun close() {
        cancelGenerations()
        synchronized(this) { releaseModel() }
    }

    private fun releaseModel() {
        loaded = false
        if (!available) return
        modelLeases.release(this) { nativeUnload() }
    }
}
