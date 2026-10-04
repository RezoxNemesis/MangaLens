package com.mangalens.engine

import java.util.Locale
import kotlin.math.ln

/**
 * Scores competing ML Kit script recognizers without rewarding raw character count.
 *
 * Running several recognizers is useful for AUTO mode, but "most characters wins" is unsafe:
 * the wrong recognizer can hallucinate a longer string than the correct one. This selector
 * favours script-consistent, confident, readable output and only uses text length as a tiny,
 * saturating tie-breaker.
 */
object OcrCandidateSelector {
    data class Candidate(
        val script: String,
        val texts: List<String>,
        val confidences: List<Float> = emptyList()
    )

    data class Choice(
        val index: Int,
        val score: Double
    )

    fun choose(candidates: List<Candidate>): Choice? {
        if (candidates.isEmpty()) return null
        return candidates.indices
            .map { Choice(it, score(candidates[it])) }
            .maxWithOrNull(compareBy<Choice> { it.score }.thenByDescending { it.index })
    }

    fun score(candidate: Candidate): Double {
        val nonBlank = candidate.texts.map(String::trim).filter(String::isNotBlank)
        if (nonBlank.isEmpty()) return 0.0

        val joined = nonBlank.joinToString(" ")
        val meaningful = joined.count { it.isLetterOrDigit() }
        if (meaningful < 2) return 0.02

        val fit = scriptFit(candidate.script, joined)
        val readableRatio = nonBlank.count { isReadableRegion(candidate.script, it, null) }
            .toDouble() / nonBlank.size.coerceAtLeast(1)
        val confidenceValues = candidate.confidences.filter { it.isFinite() && it > 0f }
        val confidence = if (confidenceValues.isEmpty()) 0.48
        else confidenceValues.average().coerceIn(0.0, 1.0)
        val symbolRatio = joined.count { !it.isLetterOrDigit() && !it.isWhitespace() }
            .toDouble() / joined.length.coerceAtLeast(1)
        val replacementPenalty = joined.count { it == '\uFFFD' || it == '\u25A1' || it == '\u25A0' }
            .toDouble() / joined.length.coerceAtLeast(1)
        val lengthBonus = (ln(meaningful.toDouble() + 1.0) / ln(65.0)).coerceIn(0.0, 1.0)

        // Script fit and recognizer confidence dominate. Length is intentionally weak.
        return (
            fit * 0.38 +
            confidence * 0.38 +
            readableRatio * 0.16 +
            lengthBonus * 0.08 -
            symbolRatio.coerceAtMost(0.70) * 0.14 -
            replacementPenalty * 0.50
        ).coerceIn(0.0, 1.0)
    }

    fun isReadableRegion(script: String, text: String, confidence: Float?): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        val meaningful = value.count { it.isLetterOrDigit() }
        if (meaningful < 2) return false
        val symbols = value.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (symbols.toFloat() / value.length.coerceAtLeast(1) > 0.58f) return false
        if (confidence != null && confidence.isFinite() && confidence > 0f && confidence < 0.12f) return false
        return scriptFit(script, value) >= 0.52
    }

    fun scriptFit(script: String, text: String): Double {
        val letters = text.asSequence().filter { it.isLetter() }.toList()
        if (letters.isEmpty()) return if (text.any { it.isDigit() }) 0.45 else 0.0
        val normalized = script.uppercase(Locale.ROOT)
        val expected = letters.count { ch ->
            when (normalized) {
                "DEVANAGARI" -> ch in '\u0900'..'\u097F'
                "CHINESE" -> isHan(ch)
                "JAPANESE" -> isKana(ch) || isHan(ch)
                "KOREAN" -> isHangul(ch) || isHan(ch)
                else -> isLatin(ch)
            }
        }.toDouble() / letters.size

        val strong = letters.count { ch ->
            when (normalized) {
                "DEVANAGARI" -> ch in '\u0900'..'\u097F'
                "CHINESE" -> isHan(ch)
                "JAPANESE" -> isKana(ch)
                "KOREAN" -> isHangul(ch)
                else -> isLatin(ch)
            }
        }.toDouble() / letters.size

        // Japanese often mixes kana and kanji; Korean occasionally includes hanja.
        val strongWeight = when (normalized) {
            "JAPANESE" -> 0.18
            "KOREAN" -> 0.14
            else -> 0.10
        }
        return (expected * (1.0 - strongWeight) + strong * strongWeight).coerceIn(0.0, 1.0)
    }

    private fun isLatin(ch: Char): Boolean =
        ch in '\u0041'..'\u005A' || ch in '\u0061'..'\u007A' ||
            ch in '\u00C0'..'\u024F' || ch in '\u1E00'..'\u1EFF'

    private fun isHan(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' ||
            ch in '\uF900'..'\uFAFF'

    private fun isKana(ch: Char): Boolean =
        ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' ||
            ch in '\u31F0'..'\u31FF'

    private fun isHangul(ch: Char): Boolean =
        ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F' ||
            ch in '\uAC00'..'\uD7AF'
}
