package com.mangalens.core.orez

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

enum class OrezDomain {
    GREETING, TRANSLATION, MANGA, VIDEO, WEB, TROUBLESHOOTING,
    PLANNING, REASONING, ADVICE, MATH, GENERAL
}

data class OrezIntentResult(
    val domain: OrezDomain,
    val normalized: String,
    val tokens: List<String>,
    val confidence: Float
)

class OrezIntentRouterV12 {
    private val aliases = mapOf(
        "translate" to setOf("translate", "translation", "meaning", "matlab", "anuvad", "अनुवाद"),
        "manga" to setOf("manga", "manhwa", "manhua", "comic", "chapter", "scan"),
        "video" to setOf("video", "movie", "player", "subtitle", "subtitles", "playback"),
        "web" to setOf("web", "website", "site", "url", "link", "internet", "online"),
        "fix" to setOf("fix", "error", "failed", "failure", "broken", "issue", "problem"),
        "plan" to setOf("plan", "schedule", "routine", "roadmap", "steps", "timetable"),
        "reason" to setOf("why", "reason", "logic", "explain", "compare", "because"),
        "advice" to setOf("advice", "suggest", "should", "help", "decide")
    )

    fun classify(input: String): OrezIntentResult {
        val normalized = normalize(input)
        val tokens = tokenize(normalized)
        if (tokens.isEmpty()) return OrezIntentResult(OrezDomain.GENERAL, normalized, emptyList(), 0f)

        val scores = linkedMapOf<OrezDomain, Int>()
        fun score(domain: OrezDomain, key: String, weight: Int) {
            val words = aliases[key].orEmpty()
            val hits = tokens.count { token -> words.any { fuzzy(token, it) } }
            if (hits > 0) scores[domain] = (scores[domain] ?: 0) + hits * weight
        }

        score(OrezDomain.TRANSLATION, "translate", 4)
        score(OrezDomain.MANGA, "manga", 3)
        score(OrezDomain.VIDEO, "video", 3)
        score(OrezDomain.WEB, "web", 3)
        score(OrezDomain.TROUBLESHOOTING, "fix", 3)
        score(OrezDomain.PLANNING, "plan", 2)
        score(OrezDomain.REASONING, "reason", 2)
        score(OrezDomain.ADVICE, "advice", 2)

        if (Regex("-?\\d+(?:\\.\\d+)?\\s*[+\\-*/x×÷]\\s*-?\\d").containsMatchIn(normalized)) {
            scores[OrezDomain.MATH] = (scores[OrezDomain.MATH] ?: 0) + 8
        }

        if (scores.isEmpty() && tokens.firstOrNull() in setOf("hi", "hello", "hey", "yo", "namaste", "namaskar")) scores[OrezDomain.GREETING] = 5

        val best = scores.maxByOrNull { it.value }
        if (best == null) return OrezIntentResult(OrezDomain.GENERAL, normalized, tokens, .15f)
        val total = scores.values.sum().coerceAtLeast(1)
        val confidence = (best.value.toFloat() / total.toFloat()).coerceIn(.15f, .99f)
        return OrezIntentResult(best.key, normalized, tokens, confidence)
    }

    fun normalize(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u0000-\\u001F]"), " ")
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}\\p{N}'+*/?!.×÷-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokenize(input: String): List<String> =
        Regex("[\\p{L}\\p{N}']{2,}").findAll(input)
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .distinct()
            .toList()

    private fun fuzzy(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        return levenshtein(a, b) <= if (max(a.length, b.length) >= 6) 2 else 1
    }

    private fun levenshtein(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var diagonal = row[0]
            row[0] = i + 1
            for (j in b.indices) {
                val old = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, diagonal + if (a[i] == b[j]) 0 else 1)
                diagonal = old
            }
        }
        return row.last()
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "this", "that", "what", "how",
            "why", "can", "you", "please", "mujhe", "mujh", "main", "mera",
            "meri", "mere", "hai", "hain", "ka", "ki", "ke", "ko", "se"
        )
    }
}
