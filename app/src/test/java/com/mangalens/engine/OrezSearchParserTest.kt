package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OrezSearchParserTest {
    @Test fun multilineResultsKeepTheirOwnSnippetAndDecodeRedirect() {
        val results = OrezSearchParser.parse("""
            <div class="result"><a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fnews%3Fx%3D1%26y%3D2" class="result__a">
            Public <b>news</b></a><div class="result__snippet">First <b>evidence</b>.</div></div>
            <div class="result"><a class="result__a" href="https://example.net/">Second</a></div>
            <div class="result"><a class="result__a" href="javascript:alert(1)">Unsafe</a><div class="result__snippet">Wrong snippet</div></div>
        """.trimIndent())
        assertEquals(2, results.size)
        assertEquals("https://example.org/news?x=1&y=2", results[0].url)
        assertEquals("Public news", results[0].title)
        assertEquals("First evidence.", results[0].snippet)
        assertEquals("", results[1].snippet)
    }
    @Test fun duplicateSourcesAndOversizeDocumentsAreBounded() {
        val row = "<div class='result'><a class='result__a' href='https://example.org/'>Result</a></div>"
        assertEquals(1, OrezSearchParser.parse(row.repeat(4)).size)
        assertTrue(OrezSearchParser.parse("x".repeat(1_500_001)).isEmpty())
    }
    @Test fun wikipediaResultsCannotSupplyAnExternalSourceUrlAndAreDeduplicated() {
        val row = """{"key":"https://untrusted.example/a b","title":"Background","excerpt":"<b>Text</b> &amp; context"}"""
        val results = OrezSearchParser.parseWikipedia("{\"pages\":[$row,$row,{}]}")
        assertEquals(1, results.size)
        assertTrue(results.single().url.startsWith("https://en.wikipedia.org/wiki/https%3A%2F%2F"))
        assertFalse(results.single().url.contains(" "))
        assertEquals("Text & context", results.single().snippet)
        assertTrue(OrezSearchParser.parseWikipedia("x".repeat(1_500_001)).isEmpty())
        assertTrue(OrezSearchParser.parseWikipedia("not json").isEmpty())
    }
}
