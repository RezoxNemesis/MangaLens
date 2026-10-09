package com.mangalens.orez

import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationQualityPolicy
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Chat-only translation request and answer policy; tool routes never use this boundary. */
internal object OrezChatTranslationPolicy {
    data class Request(val source: String, val targetLanguage: String)
    private val targets = mapOf(
        "hinglish" to "hi-latn", "roman hindi" to "hi-latn", "hindi latin" to "hi-latn",
        "romanized hindi" to "hi-latn", "romanised hindi" to "hi-latn", "hi-latn" to "hi-latn",
        "hindi" to "hi", "english" to "en", "japanese" to "ja", "korean" to "ko",
        "chinese" to "zh", "spanish" to "es", "french" to "fr", "german" to "de",
        "arabic" to "ar", "bengali" to "bn", "gujarati" to "gu", "marathi" to "mr",
        "tamil" to "ta", "telugu" to "te", "urdu" to "ur"
    )
    private val targetPattern = targets.keys.sortedByDescending(String::length).joinToString("|") {
        it.split(' ').joinToString("\\s+") { word -> Regex.escape(word) }
    }
    private val instruction = Regex("""(?i)^\s*(?:please\s+)?(?:translate kar do|translate karo|translation|translate|anuvad)\s*[:,-]?\s*""")
    private val prefixTarget = Regex("""(?i)^\s*(?:(?:this|the)\s+)?(?:(?:text|sentence|phrase)\s+)?(?:to|into|in)\s+($targetPattern)\s*[:,-]\s*""")
    private val suffixTarget = Regex("""(?i)\s+(?:to|into|in)\s+($targetPattern)\s*[.!?]*$""")

    fun parse(input: String, fallback: String): Request {
        var text = instruction.replaceFirst(input.trim(), "").trim()
        val suffix = suffixTarget.find(text)
        val prefix = prefixTarget.find(text)
        val requested = suffix?.groupValues?.get(1) ?: prefix?.groupValues?.get(1)
        // An instruction outside the quoted source takes precedence over language
        // names inside the source itself (for example, "A story in Hindi.").
        text = when {
            suffix != null -> text.substring(0, suffix.range.first).trim()
            prefix != null -> text.substring(prefix.range.last + 1).trim()
            else -> text
        }
        val target = requested?.let { targets[normalized(it)] } ?: normalized(fallback).let { targets[it] ?: it }
        return Request(text.removeSurrounding("\"").removeSurrounding("'").ifBlank { input.trim() }, target)
    }

    fun prompt(request: Request): String {
        val scriptRule = if (request.targetLanguage == "hi-latn") {
            " Use Hinglish (Roman Hindi) in readable Latin letters. Do not use Devanagari or an English-only paraphrase. Preserve names and the speaker's register."
        } else ""
        return "Translate the following text to ${request.targetLanguage}.$scriptRule" +
            " Return only the translation, not instructions or a description of how to translate.\nTEXT:\n" + request.source
    }

    suspend fun resolve(
        request: Request,
        cached: suspend (Request) -> List<TranslationDraft>,
        model: suspend (String) -> String?,
        fallback: suspend (String, String) -> TranslationDraft
    ): String? {
        for (candidate in attempt { cached(request) }.orEmpty()) {
            checked(request, candidate)?.let { return it.text }
        }
        val modelAnswer = attempt { model(prompt(request)) }.orEmpty()
        // A model answer does not inherit proof from a cached or fallback draft.
        checked(request, TranslationDraft(modelAnswer))?.let { return it.text }
        val draft = attempt { fallback(request.source, request.targetLanguage) } ?: return null
        // A failed independent refinement must retain the checked native draft and
        // its exact Hindi intermediate, including short nouns without Hindi particles.
        return attempt {
            TranslationQualityPolicy.chooseDraft(request.source, draft, modelAnswer, request.targetLanguage).text
        }
    }

    private fun checked(request: Request, draft: TranslationDraft): TranslationDraft? = try {
        TranslationQualityPolicy.chooseDraft(request.source, draft, "", request.targetLanguage)
    } catch (_: IllegalArgumentException) { null }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace('_', '-').replace(Regex("\\s+"), " ")

}
