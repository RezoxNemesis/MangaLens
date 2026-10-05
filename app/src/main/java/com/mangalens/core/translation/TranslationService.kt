package com.mangalens.core.translation

internal fun normalizeEnglishDialogueForHindi(value: String): String {
    var text = value
    val replacements = listOf(
        Regex("""(?i)\bpissed me off even more\b""") to "made me even angrier",
        Regex("""(?i)\bpissed me off\b""") to "made me angry",
        Regex("""(?i)\bused to get beaten up\b""") to "used to be beaten badly",
        Regex("""(?i)\bget beaten up\b""") to "be beaten badly",
        Regex("""(?i)\bbeaten up\b""") to "badly beaten",
        Regex("""(?i)\bhurt a bit for me too\b""") to "hurt me a little too"
    )
    replacements.forEach { (pattern, replacement) -> text = text.replace(pattern, replacement) }
    return text
}

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationService {
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorLock = Any()
    private val translators = LinkedHashMap<String, Translator>()

    suspend fun translate(text: String, targetLanguage: String): String {
        val sourceText = text.trim()
        if (sourceText.isBlank()) return text
        val target = TranslateLanguage.fromLanguageTag(targetLanguage.trim().lowercase())
            ?: throw IllegalArgumentException("Unsupported translation language: $targetLanguage")
        val detected = detectSource(sourceText)
        val source = if (target == TranslateLanguage.HINDI && isRomanizedHindi(sourceText)) {
            TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
        }
        if (source == target) return text
        val translationInput = if (source == TranslateLanguage.ENGLISH && target == TranslateLanguage.HINDI) {
            normalizeEnglishDialogueForHindi(sourceText)
        } else sourceText

        val translator = synchronized(translatorLock) {
            val key = "$source->$target"
            translators[key] ?: Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            ).also { created ->
                translators[key] = created
                while (translators.size > MAX_CACHED_TRANSLATORS) {
                    val iterator = translators.entries.iterator()
                    if (!iterator.hasNext()) break
                    val oldest = iterator.next()
                    iterator.remove()
                    runCatching { oldest.value.close() }
                }
            }
        }

        return suspendCancellableCoroutine { continuation ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    if (!continuation.isActive) return@addOnSuccessListener
                    translator.translate(translationInput)
                        .addOnSuccessListener { translated ->
                            if (continuation.isActive) continuation.resume(translated)
                        }
                        .addOnFailureListener { failure ->
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                }
                .addOnFailureListener { failure ->
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
        }
    }

    private suspend fun detectSource(text: String): String {
        if (text.any { it in '\u3040'..'\u30ff' }) return TranslateLanguage.JAPANESE
        if (text.any { it in '\uac00'..'\ud7af' }) return TranslateLanguage.KOREAN
        if (text.any { it in '\u0900'..'\u097f' }) return TranslateLanguage.HINDI
        if (isRomanizedHindi(text)) return TranslateLanguage.HINDI

        // Han-only dialogue is common in Japanese manga. Treating every Kanji-only
        // bubble as Chinese causes confidently wrong translations, so let ML Kit's
        // language identifier distinguish ja/zh first and use Chinese only as a
        // conservative fallback when the identifier cannot decide.
        val hasHan = text.any { it in '\u4e00'..'\u9fff' }
        return suspendCancellableCoroutine { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (continuation.isActive) {
                        continuation.resume(
                            TranslateLanguage.fromLanguageTag(code)
                                ?: if (hasHan) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH
                        )
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(if (hasHan) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH)
                    }
                }
        }
    }

    private fun isRomanizedHindi(text: String): Boolean = Regex(
        """\b(kya|hai|hain|haan|nahi|nahin|mujhe|tum|aap|mera|meri|kaise|kyun|bahut|acha|achha|hoon|karna|karo)\b""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

    fun close() {
        synchronized(translatorLock) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
        }
        runCatching { languageIdentifier.close() }
    }

    companion object {
        private const val MAX_CACHED_TRANSLATORS = 8
    }
}
