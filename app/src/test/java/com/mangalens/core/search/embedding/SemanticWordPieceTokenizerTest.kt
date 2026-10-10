package com.mangalens.core.search.embedding

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Authored against the exact publisher vocabulary. Runtime/tokenizer oracle execution is deferred. */
class SemanticWordPieceTokenizerTest {
    private val vocabulary by lazy {
        val file = listOf(File("src/main/assets/semantic/vocab.txt"), File("app/src/main/assets/semantic/vocab.txt")).first { it.isFile }
        val bytes = file.readBytes()
        assertEquals(SemanticEmbeddingPin.VOCAB_SHA256, SemanticModelArtifactStore.sha256(bytes))
        bytes.toString(Charsets.UTF_8).split('\n').dropLastWhile { it.isEmpty() }
    }
    private val tokenizer by lazy { SemanticWordPieceTokenizer(vocabulary) }
    private fun ids(text: String) = tokenizer.encode(text).let { it.inputIds.take(it.tokenCount).toLongArray() }
    @Test fun exactHelloWorldUsesPublisherIdsAndBoundaries() { assertArrayEquals(longArrayOf(101, 7592, 2088, 102), ids("Hello world")) }
    @Test fun accentsAndUncasedLatinHaveEquivalentTokens() { assertArrayEquals(ids("cafe resume"), ids("CAFÉ résumé")) }
    @Test fun unicodeLineAndParagraphSeparatorsSeparateWords() { assertArrayEquals(ids("hello world again"), ids("hello\u2028world\u2029again")) }
    @Test fun nonbreakingAndThinSpacesSeparateWords() { assertArrayEquals(ids("hello world again"), ids("hello\u00a0world\u2009again")) }
    @Test fun punctuationRemainsActualSeparatePublisherTokens() {
        assertArrayEquals(longArrayOf(101, vocabulary.indexOf("hello").toLong(), vocabulary.indexOf(",").toLong(), vocabulary.indexOf("world").toLong(), vocabulary.indexOf("!").toLong(), 102), ids("Hello,world!"))
    }
    @Test fun chineseCodepointsAreSeparatedBeforeWordPiece() { assertArrayEquals(ids("中 文"), ids("中文")) }
    @Test fun exactAddedSpecialTokensArePreservedWhenAdjacent() { assertArrayEquals(longArrayOf(101, 7592, 103, 2088, 102), ids("hello[MASK]world")) }
    @Test fun lowercaseBracketTextDoesNotBecomeAddedSpecial() { assertFalse(ids("[mask]").contentEquals(ids("[MASK]"))) }
    @Test fun controlAndFormatCharactersAreRemovedBeforeSplitting() { assertArrayEquals(ids("hello world"), ids("he\u200bllo\tworld")) }
    @Test fun overHundredCodepointsUseWholeUnknown() { assertArrayEquals(longArrayOf(101, 100, 102), ids("a".repeat(101))) }
    @Test fun supplementaryUnknownDoesNotSplitSurrogatePairs() { assertArrayEquals(longArrayOf(101, 100, 102), ids("😀".repeat(100))) }
    @Test fun boundedTruncationKeepsSeparatorAndAttentionMask() {
        val value = tokenizer.encode(List(200) { "hello" }.joinToString(" "))
        assertEquals(128, value.tokenCount); assertTrue(value.truncated); assertEquals(102L, value.inputIds.last())
        assertTrue(value.attentionMask.all { it == 1L }); assertTrue(value.tokenTypeIds.all { it == 0L })
    }
    @Test fun shortTextPadsActualTokenAndMaskLengths() {
        val value = tokenizer.encode("hello")
        assertEquals(3, value.tokenCount); assertFalse(value.truncated)
        assertTrue(value.inputIds.drop(3).all { it == 0L }); assertTrue(value.attentionMask.drop(3).all { it == 0L })
    }
    @Test fun exactTokenBudgetIsNotReportedTruncated() { assertFalse(tokenizer.encode(List(126) { "hello" }.joinToString(" ")).truncated) }
    @Test fun damagedSpecialIdsFailBeforeEncoding() {
        val changed = vocabulary.toMutableList(); changed[101] = "wrong-special"
        assertThrows(IllegalArgumentException::class.java) { SemanticWordPieceTokenizer(changed) }
    }
    @Test fun duplicateVocabularyRowsAreRejected() {
        val changed = vocabulary.toMutableList(); changed[200] = changed[201]
        assertThrows(IllegalArgumentException::class.java) { SemanticWordPieceTokenizer(changed) }
    }
    @Test fun inputCharacterBudgetIsEnforcedBeforeTokenization() { assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("a".repeat(4097)) } }
}
