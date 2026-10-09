package com.mangalens.core.translation.memory

/** Bounded lexical retrieval; this API does not assert semantic/embedding relevance. */
internal object SeriesMemoryQuery {
    private val stopWords = setOf("the", "and", "but", "for", "are", "you", "your", "our", "was", "were", "has", "have", "had", "this", "that", "with", "from", "not", "now", "can", "will", "would", "could", "should", "does", "did", "what", "when", "where", "who", "how", "just", "some", "into", "than", "then", "them", "they", "she", "him", "her", "its", "yes", "sorry")

    fun relevant(request: MemoryRetrievalRequest, associations: List<MemoryChapterAssociation>,
        profile: SeriesMemoryProfile?, bubbles: List<MemoryIndexedBubble>): RelevantSeriesMemory {
        require(memoryValidId(request.chapterId) && request.pageIndex in 0 until 2_000)
        memoryValidateText(request.sourceText)
        val current = associations.singleOrNull { it.chapterId == request.chapterId }
            ?: return RelevantSeriesMemory(null, emptyMap(), emptyList())
        if (profile == null || profile.removed || profile.id != current.seriesId)
            return RelevantSeriesMemory(null, emptyMap(), emptyList())
        val links = associations.filter { it.seriesId == current.seriesId }.associateBy { it.chapterId }
        fun before(location: MemoryLocation): Boolean {
            if (location.chapterId == current.chapterId) return location.pageIndex < request.pageIndex
            val earlier = links[location.chapterId] ?: return false
            return current.ordinal != null && earlier.ordinal != null && earlier.ordinal < current.ordinal
        }
        val terms = profile.glossary.asSequence().filter {
            it.targetLanguage.equals(request.targetLanguage, true) &&
                (it.origin == null || before(it.origin)) &&
                (listOf(it.source) + it.aliases).any { alias -> matches(request.sourceText, alias) }
        }.sortedWith(compareByDescending<SeriesGlossaryTerm> { it.source.length }.thenBy { it.id })
            .take(16).toList()
        val glossary = linkedMapOf<String, String>()
        var glossaryCharacters = 0
        for (term in terms) {
            val spellings = (listOf(term.source) + term.aliases).distinctBy(::memoryTextKey)
                .filter { matches(request.sourceText, it) }
            for (spelling in spellings) {
                if (glossary.size >= 16 || glossaryCharacters + spelling.length + term.preferred.length > 2_048) continue
                if (memoryTextKey(spelling) in glossary.keys.map(::memoryTextKey)) continue
                glossary[spelling] = term.preferred
                glossaryCharacters += spelling.length + term.preferred.length
            }
        }
        val words = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(memoryTextKey(request.sourceText))
            .map { it.value }.filter { it.length >= 2 && it !in stopWords }.distinct().take(32).toList()
        val ranked = bubbles.asSequence().filter { bubble ->
            bubble.receipt.seriesId == current.seriesId && bubble.receipt.targetLanguage.equals(request.targetLanguage, true) &&
                bubble.receipt.configurationIdentity == request.configurationIdentity && bubble.translatedText != null &&
                before(MemoryLocation(bubble.receipt.source.chapterId, bubble.receipt.source.pageIndex))
        }.map { bubble -> bubble to words.count { word -> matches(bubble.sourceText, word) } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryIndexedBubble, Int>> { it.second }
                .thenByDescending { links[it.first.receipt.source.chapterId]?.ordinal ?: -1 }
                .thenByDescending { it.first.receipt.source.pageIndex }.thenBy { it.first.receipt.bubbleId })
            .take(8)
        var remaining = 4_096
        val prior = ArrayList<MemorySearchHit>()
        for ((bubble, _) in ranked) {
            if (remaining <= 0) break
            val text = bubble.translatedText!!.take(remaining).let { value ->
                if (value.lastOrNull()?.isHighSurrogate() == true) value.dropLast(1) else value
            }
            if (text.isBlank()) continue
            val kind = if (bubble.correction?.edit?.translated != null) MemorySearchKind.CORRECTED_TRANSLATION else MemorySearchKind.TRANSLATION
            prior += hit(bubble, current.seriesId, kind, text)
            remaining -= text.length
        }
        return RelevantSeriesMemory(current.seriesId, glossary, prior)
    }

    /** Exact normalized token/phrase boundaries preserve Devanagari marks and Latin name identity. */
    fun matches(text: String, term: String): Boolean {
        val needle = memoryTextKey(term)
        if (needle.isBlank()) return false
        val haystack = memoryTextKey(text)
        var from = 0
        while (from <= haystack.length - needle.length) {
            val start = haystack.indexOf(needle, from)
            if (start < 0) return false
            val end = start + needle.length
            val first = needle.codePointAt(0); val last = needle.codePointBefore(needle.length)
            val left = start == 0 || boundary(haystack.codePointBefore(start), first)
            val right = end == haystack.length || boundary(last, haystack.codePointAt(end))
            if (left && right) return true
            from = start + 1
        }
        return false
    }

    fun search(query: String, bubbles: List<MemoryIndexedBubble>, seriesId: String? = null): List<MemorySearchHit> {
        require(query.isNotBlank() && query.length <= 256) { "Search must be between 1 and 256 characters." }
        val result = ArrayList<MemorySearchHit>()
        for (bubble in bubbles) {
            val fields = listOfNotNull(
                MemorySearchKind.OCR to bubble.receipt.originalOcr,
                bubble.receipt.originalTranslation?.let { MemorySearchKind.TRANSLATION to it },
                bubble.correction?.edit?.correctedOcr?.let { MemorySearchKind.CORRECTED_OCR to it },
                bubble.correction?.edit?.translated?.let { MemorySearchKind.CORRECTED_TRANSLATION to it })
            fields.filter { matches(it.second, query) }.forEach { (kind, text) ->
                if (result.size < 32) result += hit(bubble, seriesId, kind, text)
            }
            if (result.size >= 32) break
        }
        return result
    }

    // East Asian dialogue commonly has no spaces or attaches particles/honorifics.
    // Exact substrings are lexical evidence there; Latin/Indic terms retain word/mark boundaries.
    private fun boundary(before: Int, after: Int): Boolean = !wordChar(before) || !wordChar(after) || eastAsian(before) || eastAsian(after)
    private fun wordChar(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) || codePoint == '_'.code ||
        Character.getType(codePoint) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    private fun eastAsian(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) in setOf(Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    private fun hit(bubble: MemoryIndexedBubble, seriesId: String?, kind: MemorySearchKind, text: String) =
        MemorySearchHit(bubble.receipt.bubbleId, bubble.receipt.source, seriesId, kind, text,
            bubble.editRevision, bubble.receipt.targetLanguage, bubble.receipt.configurationIdentity)
}
