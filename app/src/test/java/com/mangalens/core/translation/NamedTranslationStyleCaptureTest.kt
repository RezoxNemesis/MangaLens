package com.mangalens.core.translation

import com.mangalens.orez.OrezModelCatalog
import com.mangalens.orez.OrezModelPin
import org.junit.Assert.*
import org.junit.Test

/** AUTHORED_UNRUN: descriptor/capture compatibility, not bilingual output quality. */
class NamedTranslationStyleCaptureTest {
    private val named get() = listOf(TranslationStyleProfile.MANGA, TranslationStyleProfile.LITERAL)
    private val pin get() = OrezModelCatalog.lite.let { OrezModelPin(it.id, it.sha256, it.bytes) }
    private fun request(style: TranslationStyleProfile) = TranslationRefinementRequest(true, style, pin,
        TranslationRefinementPolicy.INPUT_PROFILE_VERSION)

    @Test fun mangaAndLiteralHaveIndependentProfileAndMemoryIdentity() {
        val all = listOf(TranslationStyleProfile.NATURAL, TranslationStyleProfile.FAITHFUL,
            TranslationStyleProfile.CASUAL, TranslationStyleProfile.FORMAL, TranslationStyleProfile.WEBTOON) + named
        assertEquals(7, all.map { it.id }.distinct().size)
        assertEquals(7, all.map { it.instruction }.distinct().size)
        assertEquals(7, all.map { it.memoryKey }.distinct().size)
        assertEquals("manga", TranslationStyleProfile.MANGA.id)
        assertEquals("literal", TranslationStyleProfile.LITERAL.id)
        assertTrue(TranslationStyleProfile.MANGA.naturalDialogue)
        assertFalse(TranslationStyleProfile.LITERAL.naturalDialogue)
    }

    @Test fun shippedProfileCodecIdentityRemainsByteCompatible() {
        // Frozen from actual pre-change descriptors and the real length-prefixed request identity.
        val expected = mapOf(
            "natural" to "f361dc6b3d979edab9b2bc22212783da1ec84633f7d275da7f2d78096aa213fd",
            "faithful" to "a823c520233b04af68be9515214574da408d88604384830d4ebeabcdc723168f",
            "casual" to "56ce9c0f51e8f3ff79e88a5e30a0a531e150bf13b6ee78cace9d431778c464bd",
            "formal" to "9359ba0036d8192e5c1e088b39b928621ffd7132703b241ef3586e85a1c959c6",
            "webtoon" to "672dc7de78123bdd56b60993adb5c1d51edcc27526d27a60aff42a0e55fc461f"
        )
        expected.forEach { (id, hash) ->
            val captured = TranslationRefinementRequest(false, TranslationStyleProfile.fromId(id))
            assertEquals(id, hash, TranslationRefinementPolicy.hash(TranslationRefinementRequestCodec.identity(captured)))
        }
    }

    @Test fun namedRequestCodecRetainsAllCapturedFieldsPinAndRevision() {
        named.forEach { style ->
            val captured = request(style)
            val restored = TranslationRefinementRequestCodec.decode(TranslationRefinementRequestCodec.encode(captured))
            assertEquals(captured, restored)
            assertEquals(style.instruction, restored.style.instruction)
            assertEquals(style.naturalDialogue, restored.style.naturalDialogue)
            assertEquals(pin, restored.pinnedModel)
            assertEquals(TranslationRefinementPolicy.INPUT_PROFILE_VERSION, restored.inputProfileRevision)
        }
    }

    @Test fun historicalCapturedNamedInstructionIsNotRecapturedFromAmbientDescriptor() {
        val captured = request(TranslationStyleProfile.MANGA.copy(name = "Captured manga profile",
            instruction = "Keep this exact historical manga instruction.", preserveHonorifics = false))
        val restored = TranslationRefinementRequestCodec.decode(TranslationRefinementRequestCodec.encode(captured))
        assertEquals(captured, restored)
        assertNotEquals(TranslationStyleProfile.MANGA, restored.style)
        val config = ChapterTranslationConfig("hi", "manga", localRefinement = true, refinementRequest = restored).normalized()
        assertEquals(captured.style, config.style())
    }

    @Test fun chapterNormalizationRetainsNamedStyleAndRejectsMismatchedCapturedStyle() {
        named.forEach { style ->
            val normalized = ChapterTranslationConfig(" HI ", " ${style.id.uppercase()} ",
                localRefinement = true, refinementRequest = request(style)).normalized()
            assertEquals(style.id, normalized.styleId)
            assertEquals(style, normalized.style())
            assertEquals("hi", normalized.targetLanguage)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterTranslationConfig("hi", "literal", localRefinement = true,
                refinementRequest = request(TranslationStyleProfile.MANGA)).normalized()
        }
    }

    @Test fun newNamesDoNotRelabelSavedFaithfulOrChangeUnknownIdFallback() {
        assertEquals(TranslationStyleProfile.FAITHFUL, ChapterTranslationConfig("hi", "faithful").normalized().style())
        assertEquals(TranslationStyleProfile.NATURAL, TranslationStyleProfile.fromId("unknown-future-style"))
        assertEquals("natural", ChapterTranslationConfig("hi", "unknown-future-style").normalized().styleId)
        assertEquals(TranslationStyleProfile.MANGA, TranslationStyleProfile.fromId("MANGA"))
        assertEquals(TranslationStyleProfile.LITERAL, TranslationStyleProfile.fromId("LITERAL"))
    }

    @Test fun actualCapturedRefinementCacheNamespacesCannotCrossNamedOrShippedStyles() {
        val configs = (named + TranslationStyleProfile.FAITHFUL).map { style ->
            ChapterTranslationConfig("hi", style.id, localRefinement = true, refinementRequest = request(style)).normalized()
        }
        val namespaces = configs.map { CapturedMemoryRefinementPolicy.styleIdentity(it.style().memoryKey, it) }
        assertEquals(3, namespaces.distinct().size)
        val changed = configs.first().copy(refinementRequest = request(TranslationStyleProfile.MANGA.copy(instruction = "A separately captured instruction.")))
        assertNotEquals(namespaces.first(), CapturedMemoryRefinementPolicy.styleIdentity(changed.style().memoryKey, changed))
    }
}
