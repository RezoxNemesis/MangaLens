package com.mangalens.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullVideoSubtitleGeneratorTest {
    @Test
    fun srtRoundTripPreservesTimedEnglishCues() {
        val cues = listOf(
            SpeechCue(1_250, 3_900, "This is the first line."),
            SpeechCue(4_050, 6_800, "And this is the second line.")
        )
        val srt = FullVideoSubtitleGenerator.toSrt(cues)
        assertTrue(srt.contains("00:00:01,250 --> 00:00:03,900"))
        assertEquals(cues, FullVideoSubtitleGenerator.parseSrt(srt))
    }

    @Test
    fun parserAcceptsMultilineSubtitleBlocks() {
        val srt = """
            1
            00:00:08,000 --> 00:00:11,500
            We need a system that can generate subtitles
            in real time.

            2
            00:00:12,000 --> 00:00:15,000
            This remains cached for playback.
        """.trimIndent()
        val cues = FullVideoSubtitleGenerator.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals("We need a system that can generate subtitles\nin real time.", cues.first().text)
        assertEquals(12_000L, cues.last().startMs)
    }
}
