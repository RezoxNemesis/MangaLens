package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationMemoryCodecTest {
    @Test fun exactShortNounEvidenceRoundTripsWithoutBecomingVisibleDialogue() {
        for ((source, hindi) in listOf("Fire!" to "आग!", "Power!" to "शक्ति!", "Sword!" to "तलवार!")) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
            val encoded = TranslationMemoryCodec.encode(source, draft, "hi-latn")
            assertNotEquals(draft.text, encoded)
            assertEquals(draft, TranslationMemoryCodec.decode(source, encoded, "hi-latn"))
            assertEquals(draft, TranslationMemoryCodec.decode("  $source  ", encoded, "HI_latn"))
            assertNull(TranslationMemoryCodec.decode("Other source!", encoded, "hi-latn"))
            assertNull(TranslationMemoryCodec.decode(source, encoded, "hi"))
        }
    }

    @Test fun legacyEntriesRemainPlainAndMustPassTheirOwnTargetGate() {
        assertEquals(TranslationDraft("main theek hoon."), TranslationMemoryCodec.decode("I am fine.", "main theek hoon.", "hi-latn"))
        assertEquals(TranslationDraft("आग!"), TranslationMemoryCodec.decode("Fire!", "आग!", "hi"))
        assertNull(TranslationMemoryCodec.decode("Fire!", "aag!", "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", "Fire!", "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", "आग!", "hi-latn"))
        assertEquals("आग!", TranslationMemoryCodec.encode("Fire!", TranslationDraft("आग!"), "hi"))
    }

    @Test fun corruptedProofChangedRenderingAndTrailingDataAreRejected() {
        val encoded = TranslationMemoryCodec.encode("Fire!", HinglishTranslationOutput.fromHindiDraft("Fire!", "आग!"), "hi-latn")
        assertNull(TranslationMemoryCodec.decode("Fire!", encoded.replace("aag!", "foo!"), "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", encoded.replace("आग!", "अग!"), "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", encoded + "extra", "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", encoded.dropLast(1), "hi-latn"))
        assertNull(TranslationMemoryCodec.decode("Fire!", encoded.replace("HI-LATN:1:", "HI-LATN:2:"), "hi-latn"))
    }

    @Test fun invalidOrOversizedFieldsNeverDecodeOrEncodeAsTrustedOutput() {
        val prefix = "\u001eML-HI-LATN:1:"
        for (bad in listOf("-1:a", "999999999999999999999999:a", "x:a", "8001:" + "a".repeat(8001))) {
            assertNull(TranslationMemoryCodec.decode("Fire!", prefix + bad, "hi-latn"))
        }
        assertNull(TranslationMemoryCodec.decode("Fire!", prefix + "x".repeat(25_000), "hi-latn"))
        assertThrows(IllegalArgumentException::class.java) {
            TranslationMemoryCodec.encode("Fire!", TranslationDraft("aag!"), "hi-latn")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationMemoryCodec.encode("Fire!", TranslationDraft("Fire!", "आग!"), "hi-latn")
        }
    }
}
