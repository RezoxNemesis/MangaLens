package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OriginalMediaAttemptIsolationTest {
    @Test fun rejectedOldAttemptCleanupCannotDeleteANewerResultOrResumableInput() {
        val directory = Files.createTempDirectory("original-attempt").toFile()
        try {
            val older = OriginalMediaRemuxer.newAttemptBase(directory, "request-1")
            val newer = OriginalMediaRemuxer.newAttemptBase(directory, "request-1")
            assertNotEquals(older.name, newer.name)
            OriginalMediaContainer.entries.forEach { File(directory, "${older.name}.${it.extension}").writeText("old") }
            val timingSuffixes = listOf("source-video.video", "source-video.audio", "source-audio.audio", "result.video", "result.audio")
            timingSuffixes.forEach { File(directory, "${older.name}.$it.timing").writeText("old") }
            val retained = listOf(File(directory, "${newer.name}.webm"), File(directory, "request-1.part"),
                File(directory, "request-1.audio.part"), File(directory, "request-1.validator"),
                File(directory, "${newer.name}.result.video.timing"))
            retained.forEach { it.writeText("new") }
            OriginalMediaRemuxer.removeAttemptOutputs(older)
            assertFalse(OriginalMediaContainer.entries.any { File(directory, "${older.name}.${it.extension}").exists() })
            assertFalse(timingSuffixes.any { File(directory, "${older.name}.$it.timing").exists() })
            assertTrue(retained.all { it.readText() == "new" })
            assertTrue(runCatching { OriginalMediaRemuxer.newAttemptBase(directory, "../outside") }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
