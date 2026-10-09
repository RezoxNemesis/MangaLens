package com.mangalens.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRecognizerLifetimeTest {
    @Test fun eachRecognizerIsClosedBeforeTheNextIsOpened() = runBlocking {
        var active = 0
        var peak = 0
        val operations = mutableListOf<String>()
        val result = readOcrScripts(
            listOf("LATIN", "JAPANESE", "KOREAN"),
            open = { script -> active++; peak = maxOf(peak, active); operations += "open $script"; script },
            recognize = { script -> operations += "read $script"; listOf(script) },
            close = { script -> operations += "close $script"; active-- }
        )
        assertEquals("Only one recognizer should own native model memory", 1, peak)
        assertEquals(0, active)
        assertEquals(listOf("LATIN", "JAPANESE", "KOREAN"), result.map { it.first })
        assertEquals(listOf("open LATIN", "read LATIN", "close LATIN", "open JAPANESE", "read JAPANESE", "close JAPANESE", "open KOREAN", "read KOREAN", "close KOREAN"), operations)
    }

    @Test fun oneFailedRecognizerClosesAndDoesNotDiscardOtherScripts() = runBlocking {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val result = readOcrScripts(
            listOf("LATIN", "JAPANESE"), open = { it },
            recognize = { if (it == "LATIN") throw IllegalStateException("model unavailable") else listOf("こんにちは") },
            close = { closed += it }, onFailure = { script, failure -> failures += "$script: ${failure.message}" }
        )
        assertEquals(listOf("LATIN", "JAPANESE"), closed)
        assertEquals("こんにちは", result.single().second.single())
        assertEquals(listOf("LATIN: model unavailable"), failures)
    }

    @Test fun cancellationClosesCurrentRecognizerAndDoesNotOpenAnother() = runBlocking {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var cancelled = false
        try {
            readOcrScripts<String, String>(
                listOf("LATIN", "JAPANESE"), open = { opened += it; it },
                recognize = { throw CancellationException("paused") }, close = { closed += it }
            )
        } catch (expected: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(listOf("LATIN"), opened)
        assertEquals(opened, closed)
    }

    @Test fun allRecognizerFailuresAreReportedInsteadOfAnEmptySuccessfulPage() = runBlocking {
        var thrown = false
        try {
            readOcrScripts<String, String>(listOf("LATIN"), open = { it }, recognize = {
                throw IllegalStateException("native OCR unavailable")
            }, close = {})
        } catch (expected: IllegalStateException) {
            thrown = true
            assertEquals("native OCR unavailable", expected.message)
        }
        assertTrue(thrown)
    }
}
