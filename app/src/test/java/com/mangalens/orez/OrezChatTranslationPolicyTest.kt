package com.mangalens.orez

import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationMemoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezChatTranslationPolicyTest {
    private val source = "I am fine."
    private val request = OrezChatTranslationPolicy.Request(source, "hi-latn")
    private val roman = "main theek hoon."

    @Test fun chatAliasesKeepTheFullTargetAndRemoveTheActualInstruction() {
        for (alias in listOf("Hinglish", "Roman Hindi", "Hindi Latin", "Romanized Hindi", "Romanised Hindi", "hi-latn")) {
            val parsed = OrezChatTranslationPolicy.parse("Translate \"I am fine.\" into $alias", "hi")
            assertEquals("Wrong target for $alias", "hi-latn", parsed.targetLanguage)
            assertEquals(source, parsed.source)
        }
    }

    @Test fun prefixAndSuffixTargetsPreserveQuotedSourceAndPriorLanguages() {
        assertEquals(OrezChatTranslationPolicy.Request(source, "hi-latn"),
            OrezChatTranslationPolicy.parse("Please translate into Roman Hindi: \"I am fine.\"", "hi"))
        assertEquals(OrezChatTranslationPolicy.Request("Jin should go home!", "de"),
            OrezChatTranslationPolicy.parse("Translate Jin should go home! into German", "hi"))
        assertEquals(OrezChatTranslationPolicy.Request("A story in Hindi.", "hi-latn"),
            OrezChatTranslationPolicy.parse("Translate \"A story in Hindi.\" into Hinglish", "hi"))
    }

    @Test fun fallbackScriptTagIsNormalizedWithoutCollapsingToHindi() {
        assertEquals("hi-latn", OrezChatTranslationPolicy.parse("Translate I am fine.", " HI_LATN ").targetLanguage)
        assertEquals("hi", OrezChatTranslationPolicy.parse("Translate I am fine into Hindi", "hi-latn").targetLanguage)
    }

    @Test fun cacheAndModelWrongScriptCannotBypassTheRomanDraftGate() = runTest {
        val lookedUp = mutableListOf<OrezChatTranslationPolicy.Request>(); var fallbackTarget: String? = null
        val result = OrezChatTranslationPolicy.resolve(request,
            cached = { lookedUp += it; listOf(TranslationDraft("मैं ठीक हूँ।"), TranslationDraft("I am fine.")) },
            model = { "मैं ठीक हूँ।" },
            fallback = { actualSource, target -> assertEquals(source, actualSource); fallbackTarget = target; TranslationDraft(roman) })
        assertEquals(roman, result)
        assertEquals(listOf(request), lookedUp)
        assertEquals("hi-latn", fallbackTarget)
    }

    @Test fun validRomanCacheAvoidsModelAndFallbackAndKeepsMemoryTagDistinct() = runTest {
        val result = OrezChatTranslationPolicy.resolve(request,
            cached = { assertEquals("hi-latn", it.targetLanguage); listOf(TranslationDraft(roman)) },
            model = { fail("Verified cache should avoid model inference"); null },
            fallback = { _, _ -> fail("Verified cache should avoid a new model download"); TranslationDraft("") })
        assertEquals(roman, result)
    }

    @Test fun invalidCacheFallsThroughToVerifiedModelWithoutUsingFallback() = runTest {
        val result = OrezChatTranslationPolicy.resolve(request, cached = { listOf(TranslationDraft("मैं ठीक हूँ।")) },
            model = { roman }, fallback = { _, _ -> fail("Valid model answer should be accepted"); TranslationDraft("") })
        assertEquals(roman, result)
    }

    @Test fun anUnusableFallbackCannotBecomeAClaimedTranslation() = runTest {
        assertNull(OrezChatTranslationPolicy.resolve(request, cached = { emptyList() }, model = { "I am fine." },
            fallback = { _, _ -> TranslationDraft("मैं ठीक हूँ।") }))
    }

    @Test fun alreadyRomanSourceCanRemainUnchangedThroughTheFallback() = runTest {
        val retained = "Tum ghar kab aaoge, Jin?"
        assertEquals(retained, OrezChatTranslationPolicy.resolve(OrezChatTranslationPolicy.Request(retained, "hi-latn"),
            cached = { emptyList() }, model = { null }, fallback = { text, _ -> TranslationDraft(text) }))
    }

    @Test fun failedCacheOrModelDoesNotHideAGoodFallback() = runTest {
        assertEquals(roman, OrezChatTranslationPolicy.resolve(request,
            cached = { throw java.io.IOException("Cache unavailable") }, model = { throw IllegalStateException("Model busy") },
            fallback = { _, _ -> TranslationDraft(roman) }))
    }

    @Test fun cancelledModelNeverStartsFallbackWork() = runTest {
        try {
            OrezChatTranslationPolicy.resolve(request, cached = { emptyList() }, model = { throw CancellationException("Chat closed") },
                fallback = { _, _ -> fail("Cancelled chat cannot start fallback"); TranslationDraft("") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun romanTargetPromptRequestsLatinHindiRatherThanDevanagari() {
        val prompt = OrezChatTranslationPolicy.prompt(request)
        assertTrue(prompt.contains("hi-latn"))
        assertTrue(prompt.contains("Roman Hindi"))
        assertTrue(prompt.contains("Devanagari"))
        assertTrue(prompt.endsWith(source))
    }

    @Test fun checkedHindiFallbackRetainsShortNounProofAfterWrongScriptRefinement() = runTest {
        assertEquals("aag!", OrezChatTranslationPolicy.resolve(OrezChatTranslationPolicy.Request("Fire!", "hi-latn"),
            cached = { emptyList() }, model = { "आग!" }, fallback = { _, target ->
                assertEquals("hi-latn", target)
                TranslationDraft("aag!", "आग!")
            }))
    }

    @Test fun actualMemoryCodecPreservesShortNounProofWithoutModelOrFallback() = runTest {
        val source = "Fire!"
        val saved = TranslationMemoryCodec.encode(source, TranslationDraft("aag!", "आग!"), "hi-latn")
        val result = OrezChatTranslationPolicy.resolve(OrezChatTranslationPolicy.Request(source, "hi-latn"),
            cached = { listOfNotNull(TranslationMemoryCodec.decode(it.source, saved, it.targetLanguage)) },
            model = { fail("Checked memory must avoid inference"); null },
            fallback = { _, _ -> fail("Checked memory must avoid downloads"); TranslationDraft("") })
        assertEquals("aag!", result)
        assertNull(TranslationMemoryCodec.decode("Water!", saved, "hi-latn"))
        assertNull(TranslationMemoryCodec.decode(source, saved, "hi"))
    }

    @Test fun untrustedShortNounCannotInventHindiProofToClaimSuccess() = runTest {
        val fire = OrezChatTranslationPolicy.Request("Fire!", "hi-latn")
        assertNull(OrezChatTranslationPolicy.resolve(fire, cached = { listOf(TranslationDraft("aag!")) },
            model = { "aag!" }, fallback = { _, _ -> TranslationDraft("Fire!", "आग!") }))
    }

    @Test fun ordinaryHindiMemoryKeepsItsScriptAndTarget() = runTest {
        assertEquals("मैं ठीक हूँ.", OrezChatTranslationPolicy.resolve(OrezChatTranslationPolicy.Request(source, "hi"),
            cached = { listOf(TranslationDraft("मैं ठीक हूँ.")) }, model = { fail("Hindi cache should remain usable"); null },
            fallback = { _, _ -> fail("Hindi cache should remain usable"); TranslationDraft("") }))
    }
}
