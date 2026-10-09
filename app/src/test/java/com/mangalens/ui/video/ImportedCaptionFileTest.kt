package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ImportedCaptionFileTest {
    @Test fun opaqueDocumentWithVttContentRetainsTimingAndUsesCanonicalSrt() {
        val parsed = ImportedCaptionFile.read(ByteArrayInputStream(("WEBVTT\n\n" +
            "intro\n00:01.125 --> 00:03.875 align:start\nHello, Velora!\n\n" +
            "00:06.000 --> 00:08.000\nWe still have 3 coins.\n").toByteArray()))
        assertEquals(listOf(1125L to 3875L, 6000L to 8000L), parsed.cues.map { it.startMs to it.endMs })
        assertEquals("application/x-subrip", parsed.mimeType)
        assertTrue(parsed.srt.startsWith("1\n00:00:01,125 --> 00:00:03,875\nHello, Velora!"))
        assertEquals(parsed.cues, ImportedCaptionFile.read(ByteArrayInputStream(parsed.srt.toByteArray())).cues)
    }

    @Test fun actualSrtContentWorksWithoutAnExtensionAndPreservesMultilineUnicode() {
        val parsed = ImportedCaptionFile.read(ByteArrayInputStream(
            "\uFEFF42\r\n00:00:00,100 --> 00:00:02,005\r\nनहीं।\r\nVelora has 3 coins.\r\n".toByteArray()))
        assertEquals(100L, parsed.cues.single().startMs)
        assertEquals(2005L, parsed.cues.single().endMs)
        assertEquals("नहीं।\nVelora has 3 coins.", parsed.cues.single().text)
    }

    @Test fun malformedTimingCannotBePublishedAsACue() {
        for (timing in listOf("00:99:00,000 --> 00:99:02,000", "00:00:02,000 --> 00:00:01,000",
            "garbage --> more garbage", "00:00:00,000 --> 00:00:01,000\n00:00:02,000 --> 00:00:03,000")) {
            assertThrows(IllegalArgumentException::class.java) {
                ImportedCaptionFile.read(ByteArrayInputStream("1\n$timing\nHello".toByteArray()))
            }
        }
    }

    @Test fun aMalformedLaterCueCannotSilentlyDisappearFromAnApparentlyCompleteImport() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportedCaptionFile.read(ByteArrayInputStream((
                "1\n00:00:00,000 --> 00:00:01,000\nFirst\n\n2\nmissing times\nLast").toByteArray()))
        }
    }

    @Test fun vttNotesStylesAndRegionsDoNotBecomeDialogue() {
        val source = "WEBVTT\n\nNOTE hidden note\nnot dialogue\n\nSTYLE\n::cue { color: red }\n\n" +
            "REGION\nid:region1\n\n00:01.000 --> 00:02.000 region:region1\nReal words\n"
        assertEquals(listOf("Real words"), ImportedCaptionFile.read(ByteArrayInputStream(source.toByteArray())).cues.map { it.text })
    }

    @Test fun oversizedInputAndMalformedUtf8FailBeforeTheTrackCanBeApplied() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportedCaptionFile.read(ByteArrayInputStream(ByteArray(ImportedCaptionFile.MAX_BYTES + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportedCaptionFile.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
    }

    @Test fun utf16BomIsDecodedFromBytesRatherThanAProviderMimeGuess() {
        val payload = "1\n00:00:00,000 --> 00:00:01,000\nHello".toByteArray(Charsets.UTF_16LE)
        val parsed = ImportedCaptionFile.read(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + payload))
        assertEquals("Hello", parsed.cues.single().text)
    }

    @Test fun validOverlappingDialogueKeepsBothOriginalTimeRanges() {
        val source = "1\n00:00:01,000 --> 00:00:03,000\nA\n\n2\n00:00:02,000 --> 00:00:04,000\nB\n"
        assertEquals(listOf(1000L to 3000L, 2000L to 4000L),
            ImportedCaptionFile.read(ByteArrayInputStream(source.toByteArray())).cues.map { it.startMs to it.endMs })
    }

    @Test fun visibleCaptionTextRemovesFormattingWithoutReplacingWordsOrNumbers() {
        assertEquals("Velora & 3 coins\nI couldn't find them.", ImportedCaptionFile.visibleText(
            "<i>Velora</i> &amp; 3 coins\n<c.yellow>I couldn't find them.</c>"))
    }

    @Test fun commonFontTagsAndEntitiesSupplyVisibleDialogueWithoutChangingNamesOrNumbers() {
        assertEquals("Fire! Velora has 3 coins & can't go <north>.", ImportedCaptionFile.visibleText(
            "<font color=\"#ffffff\">Fire!</font> Velora has &#51; coins &amp; can&#39;t go &lt;north&gt;."))
        assertEquals("Hello 🙂", ImportedCaptionFile.visibleText("Hello &#x1F642;"))
    }

    @Test fun unknownAngleDialogueAndInvalidEntitiesRemainVisibleAndAreNotInterpretedAsTags() {
        assertEquals("<Velora> has 3 < 5 coins &#xD800; &#0; &#999999999; &unknown;",
            ImportedCaptionFile.visibleText("<Velora> has 3 < 5 coins &#xD800; &#0; &#999999999; &unknown;"))
        assertEquals("<b>spoken tag</b>", ImportedCaptionFile.visibleText("&lt;b&gt;spoken tag&lt;/b&gt;"))
    }

    @Test fun emptyDialogueAndArbitraryPlainTextDoNotCreateAPlayableTrack() {
        for (source in listOf("", "Hello world", "1\n00:00:00,000 --> 00:00:01,000\n")) {
            assertThrows(IllegalArgumentException::class.java) { ImportedCaptionFile.read(ByteArrayInputStream(source.toByteArray())) }
        }
    }
}
