package com.mangalens.oreznative

class OrezNativeEngine {
    companion object {
        init { System.loadLibrary("mangalens_orez_native") }
    }

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()

    @Synchronized
    fun load(path: String): Boolean = nativeLoad(path)

    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 384): String = nativeGenerate(prompt, maxTokens)

    @Synchronized
    fun close() = nativeUnload()
}
