package com.mangalens.core.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import com.google.android.gms.tasks.Task
import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.checkNativeComputePrecondition
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourceWorkKind
import com.mangalens.engine.awaitOcrCompletion
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale

class TranslationService {
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorLock = Any()
    private val translators = LinkedHashMap<String, Translator>()

    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String? = null): String =
        if (text.isBlank()) text else TranslationQualityPolicy.chooseDraft(text,
            translateDraft(text, targetLanguage, sourceLanguage), "", targetLanguage).text

    suspend fun translateDraft(text: String, targetLanguage: String, sourceLanguage: String? = null): TranslationDraft =
        translateDraftBody(text, targetLanguage, sourceLanguage, retainNative = false)

    /** Selected-region entry retains each actual Task before its ephemeral client may close. */
    internal suspend fun translateDraftRetainingNative(text: String, targetLanguage: String, sourceLanguage: String? = null): TranslationDraft =
        translateDraftBody(text, targetLanguage, sourceLanguage, retainNative = true)

    private suspend fun translateDraftBody(text: String, targetLanguage: String, sourceLanguage: String?, retainNative: Boolean): TranslationDraft {
        val sourceText = text.trim()
        if (sourceText.isBlank()) return TranslationDraft(text)
        val romanHindi = HindiRomanization.isTarget(targetLanguage)
        val targetTag = if (romanHindi) "hi" else targetLanguage.trim().lowercase(Locale.ROOT)
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalArgumentException("Unsupported translation language: $targetLanguage")
        // Roman Hindi already is the requested script/language. Do not feed it to the
        // English model merely because its letters are Latin.
        if (romanHindi && HindiRomanization.isRomanHindi(sourceText)) return TranslationDraft(HinglishTranslationOutput.alreadyRoman(sourceText))
        // OCR can identify a chapter script more reliably than language-ID can classify
        // a two-word fragment. Only a supported explicit hint overrides detection.
        val detected = sourceLanguage?.let(TranslateLanguage::fromLanguageTag)
            ?: detectSource(sourceText, retainNative)
        val source = if (target == TranslateLanguage.HINDI && isRomanizedHindi(sourceText)) {
            TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
        }
        if (source == target) return if (romanHindi) HinglishTranslationOutput.fromHindiDraft(sourceText, sourceText) else TranslationDraft(text)
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

        if (retainNative) {
            // Download can wait for network; it does not reserve the native compute permit.
            checkNativeComputePrecondition(false)
            retainedTask { translator.downloadModelIfNeeded() }
            checkNativeComputePrecondition(false)
        } else suspendCancellableCoroutine<Unit> { continuation ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .addOnFailureListener { failure ->
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
        }
        suspend fun translateInput(input: String): String = if (retainNative) admittedTask { translator.translate(input) }
        else suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            translator.translate(input)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
        if (source == TranslateLanguage.ENGLISH && target == TranslateLanguage.HINDI)
            return EnglishHindiTranslationInputs.translateDraft(sourceText, targetLanguage, ::translateInput)
        val draft = translateInput(sourceText)
        // Translator instances are keyed by the ML Kit language pair; rendered output
        // and persisted memories retain the full hi-latn tag in their callers.
        return if (romanHindi) HinglishTranslationOutput.fromHindiDraft(sourceText, draft) else TranslationDraft(draft)
    }

    private suspend fun detectSource(text: String, retainNative: Boolean = false): String {
        if (text.any { it in '\u3040'..'\u30ff' }) return TranslateLanguage.JAPANESE
        if (text.any { it in '\uac00'..'\ud7af' }) return TranslateLanguage.KOREAN
        if (text.any { it in '\u0900'..'\u097f' }) return TranslateLanguage.HINDI
        if (isRomanizedHindi(text)) return TranslateLanguage.HINDI

        // Han-only dialogue is common in Japanese manga. Treating every Kanji-only
        // bubble as Chinese causes confidently wrong translations, so let ML Kit's
        // language identifier distinguish ja/zh first and use Chinese only as a
        // conservative fallback when the identifier cannot decide.
        val hasHan = text.any { it in '\u4e00'..'\u9fff' }
        if (retainNative) {
            val code = try { admittedTask { languageIdentifier.identifyLanguage(text) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            checkNativeComputePrecondition(false)
            return code?.let(TranslateLanguage::fromLanguageTag)
                ?: if (hasHan) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH
        }
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

    private suspend fun <T> admittedTask(start: () -> Task<T>): T {
        val caller = currentCoroutineContext()
        val lease = NativeComputeAdmission.shared.acquire(NativeComputeAdmission.Priority.INTERACTIVE) { caller.isActive }
            ?: throw kotlinx.coroutines.CancellationException("Selected-region compute owner closed.")
        try {
            checkNativeComputePrecondition(lease.waited)
            ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
            val result = retainedTask(start)
            checkNativeComputePrecondition(false)
            return result
        } finally { lease.close() }
    }

    private suspend fun <T> retainedTask(start: () -> Task<T>): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return awaitOcrCompletion<T> { complete ->
            caller.ensureActive()
            start().addOnCompleteListener(Executor { it.run() }) { task ->
                complete(when {
                    task.isSuccessful -> Result.success(task.result)
                    task.isCanceled -> Result.failure(kotlinx.coroutines.CancellationException("Selected-region native task cancelled."))
                    else -> Result.failure(task.exception ?: IllegalStateException("Selected-region task failed without a cause."))
                })
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

