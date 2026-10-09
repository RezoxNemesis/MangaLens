package com.mangalens.ui.video

import com.mangalens.download.ProviderCaptionFormat
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionParserTest {
    private fun parse(value: String, format: ProviderCaptionFormat, duration: Long? = null) =
        ProviderCaptionParser.parse(value.toByteArray(Charsets.UTF_8), format, duration)

    @Test fun vttRetainsProviderTimingAndVisibleOriginalDialogue() {
        val parsed = parse("WEBVTT\n\nNOTE original track\n\n00:01.250 --> 00:03.750 line:90%\n<v Narrator><b>नमस्ते</b> &amp; welcome\n\n", ProviderCaptionFormat.VTT, 8000)
        assertEquals(listOf(ProviderCaptionCue(1250, 3750, "नमस्ते & welcome")), parsed.cues)
        assertTrue(parsed.payloadSha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(parsed.cuesSha256.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun srtUsesActualLongCueEndWithoutWhisperWindowClipping() {
        assertEquals(ProviderCaptionCue(500, 18_000, "The door must stay closed."),
            parse("1\n00:00:00,500 --> 00:00:18,000\nThe door must stay closed.\n", ProviderCaptionFormat.SRT, 20_000).cues.single())
    }
    @Test fun json3JoinsWordsButPreservesTheDeclaredEventRange() {
        val parsed = parse("""{"wireMagic":"pb3","events":[{"tStartMs":0,"wWinId":1},{"tStartMs":1200,"dDurationMs":2700,"segs":[{"utf8":"Don't "},{"utf8":"open it.","tOffsetMs":400}]}]}""", ProviderCaptionFormat.JSON3, 6000)
        assertEquals(listOf(ProviderCaptionCue(1200, 3900, "Don't open it.")), parsed.cues)
    }
    @Test fun overlappingRepeatedProviderCuesAreNotCollapsedAsASRWindowDuplicates() {
        val document = parse("1\n00:00:00,500 --> 00:00:02,000\nWait.\n\n2\n00:00:01,500 --> 00:00:03,000\nWait.\n", ProviderCaptionFormat.SRT)
        assertEquals(2, document.cues.size)
        assertEquals(500L, document.cues.first().startMs)
        assertEquals(1500L, document.cues.last().startMs)
    }
    @Test fun differentlyFormattedPayloadsKeepDistinctBodyEvidenceAndEquivalentTimedCueEvidence() {
        val srt = parse("1\n00:00:00,500 --> 00:00:02,000\nHello.\n", ProviderCaptionFormat.SRT)
        val vtt = parse("WEBVTT\n\n00:00.500 --> 00:02.000\nHello.\n", ProviderCaptionFormat.VTT)
        assertNotEquals(srt.payloadSha256, vtt.payloadSha256)
        assertEquals(srt.cuesSha256, vtt.cuesSha256)
    }
    @Test fun missingJsonDurationAndAppendEventsNeverInventAnEndTime() {
        for (json in listOf("""{"events":[{"tStartMs":5,"segs":[{"utf8":"Hello"}]}]}""",
            """{"events":[{"tStartMs":5,"dDurationMs":100,"aAppend":1,"segs":[{"utf8":"Hello"}]}]}""")) {
            rejects { parse(json, ProviderCaptionFormat.JSON3) }
        }
    }
    @Test fun fractionalNegativeNonMonotonicAndBeyondVideoTimingsAreRejected() {
        for (json in listOf("""{"events":[{"tStartMs":1.5,"dDurationMs":100,"segs":[{"utf8":"Hello"}]}]}""",
            """{"events":[{"tStartMs":-1,"dDurationMs":100,"segs":[{"utf8":"Hello"}]}]}""")) rejects { parse(json, ProviderCaptionFormat.JSON3) }
        rejects { parse("1\n00:00:03,000 --> 00:00:04,000\nLater\n\n2\n00:00:01,000 --> 00:00:02,000\nEarlier\n", ProviderCaptionFormat.SRT) }
        rejects { parse("1\n00:00:03,000 --> 00:00:20,000\nWrong video\n", ProviderCaptionFormat.SRT, 5000) }
    }
    @Test fun invalidEncodingOversizeAndHtmlChallengeAreUnavailableRatherThanDialogue() {
        rejects { ProviderCaptionParser.parse(byteArrayOf(0xc3.toByte(), 0x28), ProviderCaptionFormat.VTT) }
        rejects { ProviderCaptionParser.parse(ByteArray(ImportedCaptionFile.MAX_BYTES + 1), ProviderCaptionFormat.SRT) }
        rejects { parse("<html>Please sign in to continue</html>", ProviderCaptionFormat.VTT) }
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid provider timing was admitted") } catch (_: IllegalArgumentException) { }
    }
}
