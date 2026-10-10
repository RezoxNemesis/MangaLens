package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AUTHORED_UNRUN: production journal cold capture/identity; no audio/model construction. */
class NamedSubtitleStyleJournalTest {
    @Test fun bothNamedStylesSurviveColdReopenWithExactStylePinAndProfile() {
        fixture { directory, io ->
            val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/video"), "a".repeat(64))
            for (style in listOf(TranslationStyleProfile.MANGA, TranslationStyleProfile.LITERAL)) {
                val target = SubtitleTargetOptions("hi-latn", style = style.id, localRefinement = true,
                    refinementPin = OrezModelCatalog.lite.let { SubtitleRefinementPin(it.id, it.sha256, it.bytes) },
                    capturedStyle = style).captureForNewRequest()
                val config = SubtitleGenerationConfig(modelSha256 = "b".repeat(64), threads = 2).withTarget(target)
                val task = SubtitleGenerationStore(directory, io).start(source, config, ownerRequestId = "orez:named-style")
                val restored = SubtitleGenerationStore(directory, io).get(task.id)!!
                assertEquals(task.generation, restored.generation)
                assertEquals(config, restored.config)
                assertEquals(style, restored.config.capturedStyle)
                assertEquals(target.refinementPin, restored.config.refinementPin)
                assertEquals(TranslationRefinementPolicy.INPUT_PROFILE_VERSION, restored.config.refinementInputProfileRevision)
                assertEquals(task.id, SubtitleGenerationStore(directory, io).start(source, config, ownerRequestId = "orez:named-style").id)
            }
        }
    }

    @Test fun newStyleDoesNotReplaceTheExistingFaithfulJournalGeneration() {
        fixture { directory, io ->
            val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/video"), "a".repeat(64))
            val store = SubtitleGenerationStore(directory, io)
            val faithful = SubtitleGenerationConfig(modelSha256 = "b".repeat(64), threads = 2).withTarget(
                SubtitleTargetOptions("hi", style = "faithful"))
            val old = store.start(source, faithful)
            val literal = faithful.withTarget(SubtitleTargetOptions("hi", style = "literal", localRefinement = true,
                refinementPin = OrezModelCatalog.lite.let { SubtitleRefinementPin(it.id, it.sha256, it.bytes) }))
            val new = store.start(source, literal)
            assertNotEquals(old.id, new.id)
            val reopened = SubtitleGenerationStore(directory, io)
            assertEquals(old.generation, reopened.get(old.id)!!.generation)
            assertEquals(faithful, reopened.get(old.id)!!.config)
            assertEquals("literal", reopened.get(new.id)!!.config.style)
        }
    }

    private fun fixture(block: (File, SubtitleJournalIo) -> Unit) {
        val directory = Files.createTempDirectory("named-subtitle-style-").toFile()
        val io = object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                val stage = File(file.path + ".test-new")
                stage.outputStream().use { output -> output.write(bytes); output.fd.sync() }
                Files.move(stage.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try { block(directory, io) } finally { directory.deleteRecursively() }
    }
}
