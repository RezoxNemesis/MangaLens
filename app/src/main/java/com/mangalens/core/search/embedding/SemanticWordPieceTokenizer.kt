package com.mangalens.core.search.embedding

import java.text.Normalizer
import java.util.Locale

internal class SemanticTokenizedInput internal constructor(ids: LongArray, mask: LongArray, types: LongArray,
    val tokenCount: Int, val truncated: Boolean) {
    val inputIds = ids.copyOf()
    val attentionMask = mask.copyOf()
    val tokenTypeIds = types.copyOf()
    init {
        require(ids.size == SemanticEmbeddingPin.TOKENS && mask.size == ids.size && types.size == ids.size)
        require(tokenCount in 2..ids.size && ids.all { it in 0 until SemanticEmbeddingPin.VOCAB_ROWS.toLong() })
        require(mask.indices.all { mask[it] == if (it < tokenCount) 1L else 0L } && types.all { it == 0L })
    }
}

/** Actual uncased BERT WordPiece; the packaged vocabulary is whole-pinned by the caller on IO. */
internal class SemanticWordPieceTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val special = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")
    init {
        require(vocabulary.size == SemanticEmbeddingPin.VOCAB_ROWS && vocab.size == vocabulary.size)
        require(vocab["[PAD]"] == 0L && vocab["[UNK]"] == 100L && vocab["[CLS]"] == 101L &&
            vocab["[SEP]"] == 102L && vocab["[MASK]"] == 103L)
        require(vocabulary.none { it.isBlank() || it.length > 100 || '\u0000' in it })
    }

    fun encode(text: String): SemanticTokenizedInput {
        require(text.length <= SemanticEmbeddingPin.MAX_INPUT_CHARS && '\u0000' !in text)
        val body = arrayListOf<Long>()
        var truncated = false
        fun accept(id: Long): Boolean {
            if (body.size == SemanticEmbeddingPin.TOKENS - 2) { truncated = true; return false }
            body += id; return true
        }
        // BERT's added special tokens remain exact, including when adjacent to ordinary words.
        var position = 0
        while (position < text.length && !truncated) {
            val next = special.mapNotNull { token -> text.indexOf(token, position).takeIf { it >= 0 }?.let { it to token } }.minByOrNull { it.first }
            val end = next?.first ?: text.length
            for (token in basic(text.substring(position, end))) {
                for (piece in wordPiece(token)) if (!accept(piece)) break
                if (truncated) break
            }
            if (truncated || next == null) break
            if (!accept(requireNotNull(vocab[next.second]))) break
            position = next.first + next.second.length
        }
        val ids = LongArray(SemanticEmbeddingPin.TOKENS)
        ids[0] = 101; body.forEachIndexed { index, value -> ids[index + 1] = value }; ids[body.size + 1] = 102
        val count = body.size + 2
        return SemanticTokenizedInput(ids, LongArray(ids.size) { if (it < count) 1L else 0L }, LongArray(ids.size), count, truncated)
    }

    private fun basic(value: String): List<String> {
        val clean = StringBuilder(value.length)
        value.codePoints().forEach { code ->
            when {
                code == 0 || code == 0xfffd -> Unit
                whitespace(code) -> clean.append(' ')
                control(code) -> Unit
                chinese(code) -> { clean.append(' '); clean.appendCodePoint(code); clean.append(' ') }
                else -> clean.appendCodePoint(code)
            }
        }
        val tokens = arrayListOf<String>()
        for (word in clean.toString().split(Regex("(?U)\\s+")).filter { it.isNotEmpty() }) {
            val lowered = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val normalized = StringBuilder(lowered.length)
            lowered.codePoints().forEach { if (Character.getType(it) != Character.NON_SPACING_MARK.toInt()) normalized.appendCodePoint(it) }
            val current = StringBuilder()
            normalized.toString().codePoints().forEach { code ->
                if (punctuation(code)) {
                    if (current.isNotEmpty()) { tokens += current.toString(); current.setLength(0) }
                    tokens += String(Character.toChars(code))
                } else current.appendCodePoint(code)
            }
            if (current.isNotEmpty()) tokens += current.toString()
        }
        return tokens
    }

    private fun wordPiece(token: String): List<Long> {
        val count = token.codePointCount(0, token.length)
        if (count > 100) return listOf(100L)
        val offsets = IntArray(count + 1); var cursor = 0
        for (index in 0 until count) { offsets[index] = cursor; cursor += Character.charCount(token.codePointAt(cursor)) }
        offsets[count] = token.length
        val pieces = arrayListOf<Long>(); var start = 0
        while (start < count) {
            var end = count; var matched: Long? = null
            while (end > start) {
                val candidate = (if (start == 0) "" else "##") + token.substring(offsets[start], offsets[end])
                matched = vocab[candidate]
                if (matched != null) break
                end--
            }
            if (matched == null) return listOf(100L)
            pieces += matched; start = end
        }
        return pieces
    }

    private fun whitespace(code: Int) = code in listOf(32, 9, 10, 13) || Character.getType(code) == Character.SPACE_SEPARATOR.toInt()
    private fun control(code: Int) = code !in listOf(9, 10, 13) && Character.getType(code) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())
    private fun punctuation(code: Int) = code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126 || Character.getType(code) in listOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    private fun chinese(code: Int) = code in 0x4e00..0x9fff || code in 0x3400..0x4dbf || code in 0x20000..0x2a6df ||
        code in 0x2a700..0x2b73f || code in 0x2b740..0x2b81f || code in 0x2b820..0x2ceaf || code in 0xf900..0xfaff || code in 0x2f800..0x2fa1f
}
