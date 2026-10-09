package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptionTranslationTest {
    private val source = ImportedCaptionFile.parse("WEBVTT\n\n00:00.100 --> 00:01.200\n<b>Hello!</b>\n\n00:00.900 --> 00:02.000\nHow are you?\n")

    @Test fun everyOriginalCueTimingAndOverlapSurvivesDualTranslation() = runBlocking {
        val actualInputs = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val translated = CaptionTranslation.translate(source, "hi", dual = true,
            translate = { text -> actualInputs += text; TranslationDraft(if (text == "Hello!") "नमस्ते!" else "आप कैसे हैं?") },
            onProgress = { done, total -> progress += done to total })
        assertEquals(listOf("Hello!", "How are you?"), actualInputs)
        assertEquals(source.cues.map { it.startMs to it.endMs }, translated.cues.map { it.startMs to it.endMs })
        assertEquals(listOf("Hello!\nनमस्ते!", "How are you?\nआप कैसे हैं?"), translated.cues.map { it.text })
        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test fun failedOrCancelledCueCannotReturnAPartialCaptionFile() = runBlocking {
        var calls = 0
        try {
            CaptionTranslation.translate(source, "hi", false, translate = {
                calls++
                if (calls == 2) throw CancellationException("User cancelled captions")
                TranslationDraft("नमस्ते!")
            })
            fail("Cancelled second cue must not return a partial result")
        } catch (expected: CancellationException) {
            assertEquals(2, calls)
            assertEquals("User cancelled captions", expected.message)
        }
    }

    @Test fun missingProtectedCountFailsBeforeTranslatedCuePublication() = runBlocking {
        val counted = ImportedCaptionFile.parse("1\n00:00:00,000 --> 00:00:01,000\nI have 3 coins.\n")
        try {
            CaptionTranslation.translate(counted, "hi", false, translate = { TranslationDraft("मेरे पास सिक्के हैं।") })
            fail("Missing source count must not be published")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
