package com.mangalens.orez.agent

import java.util.Locale

/** Deterministic user-intent parsing; source descriptors never come from request text. */
internal object OrezSubtitleRequest {
    private val prefix = Regex("""^(?:(?:please|can you|could you)\s+)?(generate|create|make|transcribe|caption|download|save)\b""", RegexOption.IGNORE_CASE)
    private val subtitle = Regex("""\b(?:subtitles?|captions?)\b""", RegexOption.IGNORE_CASE)
    private val thenGenerate = Regex("""\bthen\s+(?:generate|create|make|transcribe|caption)\b""", RegexOption.IGNORE_CASE)
    private val urls = Regex("""(?:https?|content|file|android\.resource)://[^\s<>"']+""", RegexOption.IGNORE_CASE)
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
        val lower = input.lowercase(Locale.ROOT)
        for ((tag, names) in targets) if (names.any { name ->
            Regex("(?:\\b(?:in|into|to)\\s+${Regex.escape(name)}(?:\\b|$)|\\b${Regex.escape(name)}\\s+(?:subtitles?|captions?)\\b)").containsMatchIn(lower)
        }) return tag
        // Preserve a plainly requested unsupported target for a visible provider error.
        return Regex("""\b(?:in|into|to)\s+([a-z][a-z -]{0,40})\s*[.!?]*$""").find(lower)?.groupValues?.get(1)?.trim() ?: fallback
    }
}
