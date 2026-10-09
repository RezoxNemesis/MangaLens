package com.mangalens.engine

import java.util.Locale

/**
 * Repairs supported word boundaries, never missing vocabulary or names. Unresolved
 * source uncertainty needs another reading of the original pixels before lettering.
 */
object OcrSourceQuality {
    private val joinedQuestion = Regex(
        """\b((?i:why|how|when|where|what))([\t \r\n]+)(DID|DOES|DO|COULD|CAN|WOULD|WILL|SHOULD|HAVE|HAS|HAD|ARE|WERE|WAS|IS)(THEY|YOU|SHE|HE|WE|IT|I)\b(?=\s+[A-Z][A-Z']+)"""
    )
    private val joinedConjunction = Regex(
        """([,;:]\s*)(BUT|AND|OR|SO)(THEY|YOU|SHE|HE|WE|I)\b(?=\s+(?:CAN|COULD|WILL|WOULD|SHOULD|HAVE|HAS|HAD|AM|ARE|WERE|WAS|IS|DO|DID|DOES)(?:N['’]T)?\b)"""
    )
    private val missingCopulaApostrophe = Regex(
        """\bIM\b(?=\s+(?:SO|NOT|VERY|REALLY|STILL|JUST|ALREADY|ALMOST)\s+[A-Z][A-Z']+)"""
    )
    private val ambiguousComparative = Regex(
        """\b(?:I['’]?M|(?:YOU|WE|THEY)\s+ARE|(?:HE|SHE|IT)\s+IS|(?:I|HE|SHE|IT)\s+WAS|(?:YOU|WE|THEY)\s+WERE)\s+(?:SO|VERY|REALLY|TOO)\s+LATER\b""",
        RegexOption.IGNORE_CASE
    )
    private val words = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")
    private val malformedMixedCase = Regex("[a-z]{2,}[A-Z][a-z]+")
    private val clauseAnchors = setOf(
        "a", "an", "the", "this", "that", "these", "those", "from", "to", "of", "on", "in", "with",
        "and", "but", "or", "if", "then", "than", "other", "not", "no", "i", "i'm", "you", "we", "he",
        "she", "it", "they", "my", "your", "our", "their", "is", "am", "are", "was", "were", "be",
        "have", "has", "had", "do", "does", "did", "can", "could", "will", "would", "should", "so",
        "why", "how", "what", "when", "where", "who"
    )

    fun normalizeLatinSource(text: String): String {
        var result = joinedQuestion.replace(text) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + " " + match.groupValues[4]
        }
        result = joinedConjunction.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + " " + match.groupValues[3]
        }
        return missingCopulaApostrophe.replace(result, "I'M")
    }

    fun needsPixelRetry(text: String): Boolean = ambiguousComparative.containsMatchIn(text) ||
        hasMalformedMixedCase(text)

    internal fun hasMalformedMixedCase(text: String): Boolean {
        // An internal capital inside a lower-case word in a recognisable clause
        // warrants rereading pixels. Ordinary capitalized names and short brand
        // prefixes remain outside this pattern; no vocabulary is invented here.
        if (clauseEvidence(text) == 0) return false
        return words.findAll(text).any { malformedMixedCase.matches(it.value) }
    }

    internal fun clauseEvidence(text: String): Int = words.findAll(text).count {
        it.value.replace('’', '\'').lowercase(Locale.ROOT) in clauseAnchors
    }
}
