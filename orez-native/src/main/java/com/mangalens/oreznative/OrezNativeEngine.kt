package com.mangalens.oreznative

class OrezNativeEngine {
    companion object {
        val available: Boolean = runCatching { System.loadLibrary("mangalens_orez_native"); true }.getOrDefault(false)
    }

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()

    @Synchronized
    fun load(path: String): Boolean = available && nativeLoad(path)

    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 384): String = if (available) nativeGenerate(prompt.take(24_000), maxTokens.coerceIn(1, 512)) else ""

    @Synchronized
    fun close() { if (available) nativeUnload() }
}
