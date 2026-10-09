package com.mangalens.orez.agent

import java.util.Locale
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline

/** Deterministic user-intent parsing; source descriptors never come from request text. */
internal object OrezSubtitleRequest {
    private val prefix = Regex("""^(?:(?:please|can you|could you)\s+)?(generate|create|make|transcribe|caption|download|save)\b""", RegexOption.IGNORE_CASE)
    private val subtitle = Regex("""\b(?:subtitles?|captions?)\b""", RegexOption.IGNORE_CASE)
    private val thenGenerate = Regex("""\bthen\s+(?:generate|create|make|transcribe|caption)\b""", RegexOption.IGNORE_CASE)
    private val urls = Regex("""(?:https?|content|file|android\.resource)://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val destination = Regex("""\b(?:to|into)\s+([a-z][a-z-]*(?:\s+(?!(?:for|with|using|then|now|please|subtitles?|captions?)\b)[a-z][a-z-]*){0,4})""")
    private val targets = linkedMapOf("hi-latn" to listOf("hinglish", "roman hindi", "hindi latin", "romanized hindi", "romanised hindi", "hi-latn"),
        "en" to listOf("english"), "hi" to listOf("hindi"), "ja" to listOf("japanese"), "ko" to listOf("korean"),
        "zh" to listOf("chinese"), "fr" to listOf("french"), "es" to listOf("spanish"), "de" to listOf("german"),
        "it" to listOf("italian"), "ar" to listOf("arabic"), "bn" to listOf("bengali"), "ta" to listOf("tamil"), "te" to listOf("telugu"))

    fun isRequested(input: String): Boolean {
        val action = prefix.find(input.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return false
        return if (action in setOf("download", "save")) thenGenerate.containsMatchIn(input) && subtitle.containsMatchIn(input)
            else action == "transcribe" || action == "caption" || subtitle.containsMatchIn(input)
    }

    fun isDownloadChain(input: String) = isRequested(input) && prefix.find(input.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT) in setOf("download", "save")

    fun matchesSelection(input: String, selection: OrezMediaSelection) = urls.findAll(input).all {
        it.value.trimEnd('.', ',', ')', ']', '!', '?') in setOf(selection.uri, selection.cacheKey)
    }

    fun target(input: String, fallback: String): String {
        val lower = urls.replace(input, " ").lowercase(Locale.ROOT)
        // An explicit destination precedes language descriptors of the original cues.
        // Keep unknown destinations visible rather than falling back to a known source.
        destination.find(lower)?.groupValues?.get(1)?.trim()?.let { requested ->
            return targets.entries.firstOrNull { requested in it.value }?.key ?: requested.take(41)
        }
        for ((tag, names) in targets) if (names.any { name ->
            Regex("\\bin\\s+${Regex.escape(name)}(?:\\b|$)").containsMatchIn(lower)
        }) return tag
        // Output-mode words may separate a requested language from the cue noun.
        for ((tag, names) in targets) if (names.any { name ->
            Regex("\\b${Regex.escape(name)}\\s+(?:(?:dual|bilingual)\\s+)?(?:subtitles?|captions?)\\b").containsMatchIn(lower)
        }) return tag
        // Preserve a plainly requested unsupported target for a visible provider error.
        return Regex("""\b(?:in|into|to)\s+([a-z][a-z -]{0,40})\s*[.!?]*$""").find(lower)?.groupValues?.get(1)?.trim() ?: fallback
    }

    /** Only deterministic explicit user text and captured settings can select output policy. */
    fun options(input: String, fallback: OrezSubtitleOptions): OrezSubtitleOptions {
        val clean = urls.replace(input, " ").lowercase(Locale.ROOT)
        val target = target(clean, fallback.targetLanguage).trim().lowercase(Locale.ROOT).replace('_', '-')
        val dual = Regex("""\b(?:dual|bilingual)\s+(?:subtitles?|captions?)\b|\b(?:original|source)\s*(?:\+|and|with)\s*(?:translated|translation|target)\b""").containsMatchIn(clean)
        val translatedOnly = Regex("""\b(?:translated|translation|target)\s+only\b""").containsMatchIn(clean)
        val mode = if (dual) SubtitleOutputMode.DUAL else if (translatedOnly) SubtitleOutputMode.TRANSLATED else fallback.outputMode
        val captured = fallback.normalized()
        return if (OrezSubtitleContract.isLegacy(captured) && (target != "en" || mode == SubtitleOutputMode.DUAL))
            captured.copy(targetLanguage = target, style = "natural", outputMode = mode, pipeline = SubtitlePipeline.SOURCE_TRANSLATION,
                translationPolicy = "mlkit-dialogue-v1", capturedStyle = TranslationStyleProfile.NATURAL)
        else captured.copy(targetLanguage = target, outputMode = mode).normalized()
    }
}
