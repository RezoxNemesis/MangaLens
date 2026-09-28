package com.mangalens.core.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationService {
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, targetLanguage: String): String {
        if (text.isBlank()) return text
        val target = targetLanguage.lowercase()
        val source = detectSource(text)
        if (source == target) return text

        val translator = translators.getOrPut("$source->$target") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }

        return suspendCancellableCoroutine { continuation ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
            continuation.invokeOnCancellation { }
        }
    }

    private suspend fun detectSource(text: String): String {
        val marker = Regex("\\b(kya|hai|haan|nahi|nahin|mujhe|tum|aap|mera|meri|kaise|kyun|bahut|acha|achha)\\b", RegexOption.IGNORE_CASE)
        if (marker.containsMatchIn(text)) return "hi"
        val result = suspendCancellableCoroutine<String?> { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { continuation.resume(it.takeIf { code -> code != "und" }) }
                .addOnFailureListener { continuation.resume(null) }
        }
        return result ?: if (text.any { it in '\u0900'..'\u097f' }) "hi" else "en"
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        languageIdentifier.close()
    }
}
