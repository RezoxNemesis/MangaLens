package com.mangalens.engine

import android.graphics.RectF
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class CleanOcrBlock(
    val original: String,
    val cleaned: String,
    val bounds: RectF,
    val confidence: Float,
    val sourceLanguage: LocalSourceLanguage
)

class OcrTextProcessor {
    private val hinglish = setOf(
        "kya", "kyun", "kaise", "hai", "hain", "tha", "thi", "hoon", "mujhe",
        "tum", "aap", "mera", "meri", "karna", "karo", "nahi", "nahin",
        "acha", "achha", "bas", "abhi", "phir", "lekin", "aur", "yaar", "bhai"
    )

    fun process(blocks: List<OcrTextBlock>): List<CleanOcrBlock> {
        val ordered = blocks.filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrTextBlock> { it.bounds.top }.thenBy { it.bounds.left })
        return merge(ordered).map { block ->
            val cleaned = repair(block.text)
            CleanOcrBlock(block.text, cleaned, RectF(block.bounds), confidence(cleaned, block.confidence), detect(cleaned))
        }.filter { it.cleaned.isNotBlank() }
    }

    fun normalizeHinglish(text: String): String {
        val normalized = repair(text)
        if (!containsHinglish(normalized)) return normalized
        return normalized
            .replace(Regex("\\bmai\\b", RegexOption.IGNORE_CASE), "main")
            .replace(Regex("\\bme\\b", RegexOption.IGNORE_CASE), "mein")
            .replace(Regex("\\bnhi\\b", RegexOption.IGNORE_CASE), "nahi")
            .replace(Regex("\\bkr\\b", RegexOption.IGNORE_CASE), "kar")
            .replace(Regex("\\bkyu\\b", RegexOption.IGNORE_CASE), "kyun")
    }

    fun groupParagraphs(blocks: List<CleanOcrBlock>): List<List<CleanOcrBlock>> {
        val ordered = blocks.sortedWith(compareBy<CleanOcrBlock> { it.bounds.top }.thenBy { it.bounds.left })
        val groups = mutableListOf<MutableList<CleanOcrBlock>>()
        for (block in ordered) {
            val current = groups.lastOrNull()
            if (current == null) {
                groups += mutableListOf(block)
                continue
            }
            val previous = current.last()
            val verticalGap = block.bounds.top - previous.bounds.bottom
            val sameLine = abs(block.bounds.centerY() - previous.bounds.centerY()) <= previous.bounds.height().coerceAtLeast(1f) * .7f
            val maxGap = previous.bounds.height().coerceAtLeast(1f) * 1.35f
            if (sameLine || verticalGap <= maxGap) current += block else groups += mutableListOf(block)
        }
        return groups
    }

    fun paragraphText(group: List<CleanOcrBlock>): String =
        group.sortedBy { it.bounds.left }.joinToString(" ") { it.cleaned }
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun merge(blocks: List<OcrTextBlock>): List<OcrTextBlock> {
        val output = mutableListOf<OcrTextBlock>()
        for (block in blocks) {
            val previous = output.lastOrNull()
            if (previous != null &&
                abs(block.bounds.centerY() - previous.bounds.centerY()) <= previous.bounds.height().coerceAtLeast(1f) * .65f &&
                block.bounds.left - previous.bounds.right <= previous.bounds.height().coerceAtLeast(1f) * 3f
            ) {
                previous.bounds.right = maxOf(previous.bounds.right, block.bounds.right)
                previous.bounds.bottom = maxOf(previous.bounds.bottom, block.bounds.bottom)
                output[output.lastIndex] = previous.copy(text = previous.text + " " + block.text)
            } else {
                output += block.copy(bounds = RectF(block.bounds))
            }
        }
        return output
    }

    private fun repair(raw: String): String {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[\\u0000-\\u001F]"), " ")
            .replace('—', '-')
            .replace('–', '-')
            .replace('“', '"')
            .replace('”', '"')
            .replace('’', '\'')
            .replace(Regex("[•·▪◦]+"), " ")
            .replace(Regex("\\.{4,}"), "...")
            .replace(Regex("!{2,}"), "!")
            .replace(Regex("\\?{2,}"), "?")
            .replace(Regex("\\s+"), " ")
            .trim()

        val substitutions = linkedMapOf(
            "teh" to "the", "thls" to "this", "l'm" to "I'm", "l've" to "I've",
            "dont" to "don't", "cant" to "can't", "wont" to "won't",
            "didnt" to "didn't", "isnt" to "isn't", "wasnt" to "wasn't"
        )
        substitutions.forEach { (from, to) ->
            value = value.replace(Regex("\\b" + Regex.escape(from) + "\\b", RegexOption.IGNORE_CASE), to)
        }
        return value.replace(Regex("\\s+([,.!?;:])"), "$1").trim()
    }

    private fun confidence(text: String, base: Float): Float {
        if (text.length < 2) return (base * .55f).coerceIn(0f, 1f)
        val ratio = text.count(Char::isLetter).toFloat() / text.length.coerceAtLeast(1)
        return (base * if (ratio < .35f) .75f else 1f).coerceIn(0f, 1f)
    }

    private fun containsHinglish(text: String): Boolean =
        text.lowercase(Locale.ROOT).split(Regex("\\W+")).count { it in hinglish } >= 1

    private fun detect(text: String): LocalSourceLanguage {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            text.any { it in '\u3040'..'\u30ff' } -> LocalSourceLanguage.JAPANESE
            text.any { it in '\uac00'..'\ud7af' } -> LocalSourceLanguage.KOREAN
            text.any { it in '\u4e00'..'\u9fff' } -> LocalSourceLanguage.CHINESE
            text.any { it in '\u0900'..'\u097f' } -> LocalSourceLanguage.HINDI
            containsHinglish(lower) -> LocalSourceLanguage.HINDI
            lower.any(Char::isLetter) -> LocalSourceLanguage.ENGLISH
            else -> LocalSourceLanguage.UNKNOWN
        }
    }
}
