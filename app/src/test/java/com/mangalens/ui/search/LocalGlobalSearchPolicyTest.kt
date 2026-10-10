package com.mangalens.ui.search

import org.junit.Assert.*
import org.junit.Test

class LocalGlobalSearchPolicyTest {
    @Test fun literalWildcardQueryCannotExpandIntoAllRows() {
        assertEquals("%50\\%\\_\\\\%", LocalGlobalSearchPolicy.likePattern("50%_\\"))
        assertEquals("%普通 हिंदी%", LocalGlobalSearchPolicy.likePattern("普通 हिंदी"))
    }
    @Test fun compatibilityWidthSearchPreservesHindiScriptMarks() {
        assertTrue(LocalGlobalSearchPolicy.matches("ＳＷＯＲＤ", "sword"))
        assertTrue(LocalGlobalSearchPolicy.matches("उसकी तलवार", "तलवार"))
        assertFalse(LocalGlobalSearchPolicy.matches("तलवार", "तलवर"))
    }
    @Test fun invalidQueryFailsBeforeAnySourceIsRead() {
        listOf("", "  ", "hello\nworld", "x".repeat(161)).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalGlobalSearchPolicy.query(it) }
        }
    }
    @Test fun snippetUsesOriginalPositionsWhenUnicodeCaseMappingChangesLength() {
        val source = "İ".repeat(80) + "sword" + "尾".repeat(800)
        val snippet = LocalGlobalSearchPolicy.snippet(source, "sword")
        assertTrue(snippet.contains("sword")); assertTrue(snippet.startsWith("…")); assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= LocalGlobalSearchPolicy.SNIPPET_CHARS + 2)
    }
}
