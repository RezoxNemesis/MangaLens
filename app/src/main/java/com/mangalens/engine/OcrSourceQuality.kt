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
    private val numericFunctionWord = Regex(
        """\b(?:the|this|that|these|those|from|with|and|but|not|when|what|where)[a-z]*[0-9]+[a-z]+\b""",
        RegexOption.IGNORE_CASE
    )
    private val numericCopulaComplement = Regex(
        """\b(?:I['’]M|IM|I\s+AM|(?:YOU|WE|THEY)\s+ARE|(?:HE|SHE|IT)\s+IS|(?:I|HE|SHE|IT)\s+WAS|(?:YOU|WE|THEY)\s+WERE)\s+[A-Z]{1,2}[01][A-Z]{0,2}\b""",
        RegexOption.IGNORE_CASE
    )
    private val intrawordBackslash = Regex("""[A-Za-z]+\\[A-Za-z]+""")
    private val technicalContext = Regex(
        """\b(?:code|command|directory|drive|file|folder|path|regex|SDK|version)\b""",
        RegexOption.IGNORE_CASE
    )
    private val technicalTokens = Regex("""`[^`]*`|\b(?:https?|file)://\S+|\b[A-Za-z]:\\\S+""", RegexOption.IGNORE_CASE)
    private val clauseAnchors = setOf(
        "a", "an", "the", "this", "that", "these", "those", "from", "to", "of", "on", "in", "with",
        "and", "but", "or", "if", "then", "than", "other", "not", "no", "i", "i'm", "you", "we", "he",
        "she", "it", "they", "my", "your", "our", "their", "is", "am", "are", "was", "were", "be",
        "have", "has", "had", "do", "does", "did", "can", "could", "will", "would", "should", "so",
        "why", "how", "what", "when", "where", "who", "sorry"
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
        hasMalformedMixedCase(text) || hasUnexpectedLatinGlyphs(text)

    private fun hasUnexpectedLatinGlyphs(text: String): Boolean {
        // Digit/letter confusion and an embedded backslash can survive with a
        // high native confidence. Classify those shapes in English clause
        // context; keep the observed characters rather than filling vocabulary.
        // Explicit code/path syntax and names such as R2D2 remain valid source.
        val inspected = technicalTokens.replace(text, " ")
        if (clauseEvidence(inspected) == 0) return false
        if (numericCopulaComplement.containsMatchIn(inspected)) return true
        if (numericFunctionWord.containsMatchIn(inspected)) return true
        return !technicalContext.containsMatchIn(inspected) && intrawordBackslash.containsMatchIn(inspected)
    }

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
