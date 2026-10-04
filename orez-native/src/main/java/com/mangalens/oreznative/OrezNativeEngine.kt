package com.mangalens.oreznative

class OrezNativeEngine {
    companion object {
        val available: Boolean = runCatching { System.loadLibrary("mangalens_orez_native"); true }.getOrDefault(false)
        // JNI currently shares one physical model; each feature holds its own lease.
        private val modelLock = Any()
        private val modelOwners = mutableSetOf<OrezNativeEngine>()
    }

    private var loaded = false

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()

    @Synchronized
    fun load(path: String): Boolean = available && synchronized(modelLock) {
        nativeLoad(path).also { success ->
            if (success) { loaded = true; modelOwners.add(this) }
        }
    }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 384): String = if (available && loaded) nativeGenerate(prompt.take(24_000), maxTokens.coerceIn(1, 512)) else ""

    @Synchronized
    fun close() {
        if (!available) return
        synchronized(modelLock) {
            loaded = false
            if (modelOwners.remove(this) && modelOwners.isEmpty()) nativeUnload()
        }
    }
}
