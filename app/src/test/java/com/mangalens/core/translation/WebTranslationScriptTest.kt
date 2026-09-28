package com.mangalens.core.translation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WebTranslationScriptTest {
    @Test
    fun buildTargetsRequestedLanguage() {
        val script = WebTranslationScript.build("hi")
        assertTrue(script.contains("const target = 'hi'"))
        assertTrue(script.contains("TreeWalker"))
    }

    @Test
    fun applyEncodesTranslatedTextSafely() {
        val script = WebTranslationScript.apply(2, "Namaste friend")
        val encoded = Base64.getEncoder().encodeToString("Namaste friend".toByteArray())
        assertTrue(script.contains("2"))
        assertTrue(script.contains(encoded))
        assertTrue(script.contains("TextDecoder"))
    }
}
