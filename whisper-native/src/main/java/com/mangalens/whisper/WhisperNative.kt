package com.mangalens.whisper

/** All calls except cancel are serialized by the owning engine. */
class WhisperNative {
    external fun load(path: String): Long
    external fun infer(handle: Long, pcm16k: FloatArray, language: String, threads: Int): Array<String>
    external fun detectedLanguage(handle: Long): String
    external fun cancel(handle: Long)
    external fun free(handle: Long)
    companion object { init { System.loadLibrary("mangalens_whisper") } }
}
