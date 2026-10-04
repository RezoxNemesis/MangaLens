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
        val cues = SpeechWindowPolicy.append(listOf(old), listOf(SpeechCue(2500, 3300, "hello world"), SpeechCue(6000, 7000, "Hello world")))
        assertEquals(2, cues.size)
        assertEquals(6000L, cues.last().startMs)
    }
}
