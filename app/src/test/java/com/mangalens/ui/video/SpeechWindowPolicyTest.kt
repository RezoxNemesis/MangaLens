package com.mangalens.ui.video
import org.junit.Assert.*
import org.junit.Test

class SpeechWindowPolicyTest {
    @Test fun silenceAndSingleTransientDoNotBecomeSpeech() {
        assertFalse(SpeechWindowPolicy.hasActivity(FloatArray(16000 * 3)))
        val transient = FloatArray(16000 * 3).apply { for (i in 0..319) this[i] = .5f }
        assertFalse(SpeechWindowPolicy.hasActivity(transient))
        assertTrue(SpeechWindowPolicy.hasActivity(FloatArray(16000 * 3) { if (it % 8000 < 2400) .03f else 0f }))
    }

    @Test fun overlapIsDeduplicatedButLaterRepeatedDialogueSurvives() {
        val old = SpeechCue(1000, 3000, "Hello, world!")
        val cues = SpeechWindowPolicy.append(
            listOf(old),
            listOf(SpeechCue(2500, 3300, "hello world"), SpeechCue(6000, 7000, "Hello world"))
        )
        assertEquals(2, cues.size)
        assertEquals(6000L, cues.last().startMs)
    }

    @Test fun overlappingTailFragmentDoesNotRepeatMovieSubtitle() {
        val old = SpeechCue(10_000, 13_400, "I can't believe this is happening.")
        val cues = SpeechWindowPolicy.append(
            listOf(old),
            listOf(SpeechCue(12_900, 14_100, "this is happening"))
        )
        assertEquals(listOf(old), cues)
    }

    @Test fun adjacentShortFragmentsBecomeOneReadableCue() {
        val cues = SpeechWindowPolicy.append(
            emptyList(),
            listOf(
                SpeechCue(1_000, 1_850, "We need to"),
                SpeechCue(1_920, 2_900, "leave now.")
            )
        )
        assertEquals(1, cues.size)
        assertEquals("We need to leave now.", cues.single().text)
        assertEquals(1_000L, cues.single().startMs)
        assertEquals(2_900L, cues.single().endMs)
    }
}
