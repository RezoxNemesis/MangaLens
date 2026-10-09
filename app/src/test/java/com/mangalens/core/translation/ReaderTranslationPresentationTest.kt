package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Real Store JSON/PNG round trips; controlled lettering proves selection, not generated quality. */
class ReaderTranslationPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(root: File) {
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "journals").apply { mkdirs() }
        val source = File(sources, "page.png").apply { png(this, text = true) }
        val chapter = SavedChapter("a".repeat(32), "Reader selection", "https://explicit.example/chapter",
            listOf(ChapterPage(1, "local:selected", source.absolutePath)))
        val request = TranslationRefinementRequest(true, TranslationStyleProfile.NATURAL,
            OrezModelPin("captured-model-a", "b".repeat(64), 2_000_000))
        val config = ChapterTranslationConfig("hi-latn", localRefinement = true, refinementRequest = request)
        val choice = ReaderTranslationChoice.from(config.copy(refinementRequest = null))
        val paths = mapOf(1 to source.absolutePath)
        private val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        }
        fun store(directory: File = journals) = ChapterTranslationStore(directory, sources, io) { file ->
            pngDimensions(file.readBytes()) == (200 to 300)
        }
        suspend fun complete(store: ChapterTranslationStore, configuration: ChapterTranslationConfig = config,
            owner: String = "reader-owned", selected: SavedChapter = chapter): ChapterTranslationTask {
            val started = store.start(selected, configuration, ownerRequestId = owner)
            store.markRunning(started.id, started.generation)
            val running = store.beginPage(started.id, started.generation, 1)!!
            val output = store.createOutputFile(started.id, started.generation, 1).apply { png(this) }
            val hindi = "नमस्ते।"
            assertTrue(store.commitPage(started.id, started.generation, running.copy(
                status = ChapterTranslationPageStatus.COMPLETED, cleanedPath = output.absolutePath,
                cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 200, imageHeight = 300,
                lettering = listOf(SavedMangaLettering("Hello.", HindiRomanization.render(hindi, "Hello."),
                    10, 10, 180, 70, "sans-serif", 0, 0xff000000.toInt(), 24f, "ALIGN_CENTER",
                    12, 12, 176, 66, savedHindiDraft = hindi)))))
            return store.finish(started.id, started.generation)!!
        }
        companion object {
            private const val SOURCE_SHA = "210c742420f24e05b495bd082bf16244637fe5b6de49ecbbcae7b31d410bb545"
            private const val CLEANED_SHA = "2fbd22fe16099faea9e3c280a9adfb180d43975558a1019585b0ec122e86cc28"

            /** Host-encoded real RGB PNGs; byte pins make fixture replacement explicit. */
            fun png(file: File, text: Boolean = false) {
                val resource = "/com/mangalens/core/translation/reader-presentation/${if (text) "source" else "cleaned"}.png"
                val bytes = checkNotNull(ReaderTranslationPresentationTest::class.java.getResourceAsStream(resource)) {
                    "Missing real PNG fixture: $resource"
                }.use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(sha == if (text) SOURCE_SHA else CLEANED_SHA) { "PNG fixture bytes changed." }
                check(pngDimensions(bytes) == (200 to 300)) { "PNG fixture is not structurally decodable." }
                file.writeBytes(bytes)
            }

            /** Pure bounded PNG inspection, including chunk CRCs and every inflated scanline. */
            fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
                require(bytes.size in 57..1_048_576)
                val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                require(bytes.copyOfRange(0, 8).contentEquals(signature))
                fun int(offset: Int) = (0..3).fold(0) { value, index ->
                    (value shl 8) or (bytes[offset + index].toInt() and 255)
                }
                val compressed = ByteArrayOutputStream()
                var position = 8
                var width = 0
                var height = 0
                var ended = false
                var afterData = false
                while (position < bytes.size) {
                    require(!ended && bytes.size - position >= 12)
                    val length = int(position)
                    require(length >= 0 && length <= bytes.size - position - 12)
                    val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
                    val data = position + 8
                    val crc = CRC32().apply { update(bytes, position + 4, length + 4) }.value
                    require(crc == (int(data + length).toLong() and 0xffffffffL))
                    when (type) {
                        "IHDR" -> {
                            require(position == 8 && length == 13)
                            width = int(data); height = int(data + 4)
                            require(width in 1..2048 && height in 1..2048)
                            require(bytes[data + 8].toInt() == 8 && bytes[data + 9].toInt() == 2)
                            require((10..12).all { bytes[data + it].toInt() == 0 })
                        }
                        "IDAT" -> {
                            require(width > 0 && !afterData)
                            compressed.write(bytes, data, length)
                        }
                        "IEND" -> {
                            require(length == 0 && compressed.size() > 0)
                            ended = true
                        }
                        else -> {
                            require(width > 0 && type.first().isLowerCase())
                            if (compressed.size() > 0) afterData = true
                        }
                    }
                    position += length + 12
                }
                require(ended)
                val stride = 1 + width * 3
                val pixels = ByteArray(stride * height)
                val inflater = Inflater()
                try {
                    inflater.setInput(compressed.toByteArray())
                    var count = 0
                    while (!inflater.finished() && count < pixels.size) {
                        val read = inflater.inflate(pixels, count, pixels.size - count)
                        require(read > 0)
                        count += read
                    }
                    require(inflater.finished() && count == pixels.size && inflater.remaining == 0)
                    require((0 until height).all { (pixels[it * stride].toInt() and 255) in 0..4 })
                } finally { inflater.end() }
                width to height
            }.getOrNull()
        }
    }


    @Test fun pinnedRealPngInspectionRejectsTruncationCorruptionAndFakeHeaderOnlyData() {
        val source = File(temporary.root, "source-fixture.png").apply { Fixture.png(this, true) }.readBytes()
        val cleaned = File(temporary.root, "cleaned-fixture.png").apply { Fixture.png(this) }.readBytes()
        assertEquals(200 to 300, Fixture.pngDimensions(source))
        assertEquals(200 to 300, Fixture.pngDimensions(cleaned))
        assertFalse(source.contentEquals(cleaned))
        assertNull(Fixture.pngDimensions(source.copyOf(source.size - 1)))
        assertNull(Fixture.pngDimensions(source.copyOf().apply { this[50] = (this[50].toInt() xor 1).toByte() }))
        assertNull(Fixture.pngDimensions(source.copyOf(33))) // Valid IHDR alone does not prove image readability.
        assertNull(Fixture.pngDimensions(cleaned + byteArrayOf(0)))
    }

    @Test fun completedEnabledTaskBindsItsSavedFullRequestInsteadOfTheUiNullRequest() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store(); val task = f.complete(store)
        assertEquals(ChapterTranslationStatus.COMPLETED, task.status)
        assertTrue(task.hasTranslations)
        assertNull(ChapterTranslationDisplay.select(listOf(task), f.chapter.id, "hi-latn", "natural", "",
            f.config.copy(refinementRequest = null))) // Reproduces the actual old VM boundary.
        val bound = ReaderTranslationPresentation.restore(store.states.value, f.chapter.id, f.choice, f.paths, f.sources)!!
        assertEquals(f.config, bound.configuration)
        assertEquals(task.generation, bound.generation)
        assertEquals(task.ownerRequestId, bound.owner)
        assertEquals(task, ReaderTranslationPresentation.select(store.states.value, bound, f.chapter.id, f.choice, f.paths, f.sources))
    }

    @Test fun coldStoreReopenUsesTheSameCapturedStylePinSourceAndRealPng() = runTest {
        val f = Fixture(temporary.newFolder()); val task = f.complete(f.store()); val cold = f.store()
        assertTrue(cold.get(task.id)!!.validationPending)
        val metadata = ReaderTranslationPresentation.restore(cold.states.value, f.chapter.id, f.choice, f.paths, f.sources)!!
        val verified = cold.refresh(metadata.taskId, metadata.generation)!!
        val selected = ReaderTranslationPresentation.select(listOf(verified), metadata, f.chapter.id, f.choice, f.paths, f.sources)!!
        assertFalse(selected.validationPending)
        assertEquals(task.config, selected.config)
        assertEquals(task.pages, selected.pages)
        assertEquals(ChapterTranslationStore.sha256(f.source), metadata.sources.single().sha256)
        assertEquals(200 to 300, Fixture.pngDimensions(File(selected.pages.single().cleanedPath!!).readBytes()))
        assertEquals("नमस्ते।", selected.pages.single().lettering.single().savedHindiDraft)
    }

    @Test fun aNewerAmbientPinTaskCannotRetargetAnAlreadyBoundReceipt() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store(); val original = f.complete(store)
        val bound = ReaderTranslationPresentation.capture(original, f.chapter.id, f.choice, f.paths, f.sources)!!
        val other = f.complete(store, f.config.copy(refinementRequest = f.request.copy(pinnedModel =
            OrezModelPin("captured-model-b", "c".repeat(64), 3_000_000))), "orez-another-request")
            .copy(updatedAt = original.updatedAt + 1_000)
        val selected = ReaderTranslationPresentation.select(listOf(original, other), bound, f.chapter.id, f.choice, f.paths, f.sources)
        assertEquals(original, selected)
        assertEquals(f.request, selected!!.config.refinementRequest)
        assertNull(ReaderTranslationPresentation.select(listOf(other), bound, f.chapter.id, f.choice, f.paths, f.sources))
    }

    @Test fun sameTaskIdCannotBorrowAChangedGenerationOwnerPinOrStyleFlag() = runTest {
        val f = Fixture(temporary.newFolder()); val task = f.complete(f.store())
        val bound = ReaderTranslationPresentation.capture(task, f.chapter.id, f.choice, f.paths, f.sources)!!
        for (changed in listOf(task.copy(generation = "d".repeat(32)), task.copy(ownerRequestId = "other-owner"),
            task.copy(config = f.config.copy(refinementRequest = f.request.copy(pinnedModel = f.request.pinnedModel!!.copy(sha256 = "c".repeat(64))))),
            task.copy(config = f.config.copy(refinementRequest = f.request.copy(style = f.request.style.copy(preserveNames = false)))))) {
            assertNull(ReaderTranslationPresentation.select(listOf(changed), bound, f.chapter.id, f.choice, f.paths, f.sources))
        }
    }

    @Test fun sourceProofCannotBeReplacedEvenWithTheSameTaskAndModel() = runTest {
        val f = Fixture(temporary.newFolder()); val task = f.complete(f.store())
        val bound = ReaderTranslationPresentation.capture(task, f.chapter.id, f.choice, f.paths, f.sources)!!
        val changed = task.copy(pages = task.pages.map { it.copy(sourceSha256 = "e".repeat(64)) })
        assertNull(ReaderTranslationPresentation.select(listOf(changed), bound, f.chapter.id, f.choice, f.paths, f.sources))
        val other = File(f.sources, "different.png").apply { Fixture.png(this, true) }
        assertFalse(ReaderTranslationPresentation.matchesReader(bound, f.chapter.id, f.choice, mapOf(1 to other.absolutePath), f.sources))
        assertFalse(ReaderTranslationPresentation.matchesReader(bound, f.chapter.id, f.choice, emptyMap(), f.sources))
    }

    @Test fun everyUserTargetStyleOcrAndEnableChangeDetachesTheOldBinding() = runTest {
        val f = Fixture(temporary.newFolder()); val task = f.complete(f.store())
        val bound = ReaderTranslationPresentation.capture(task, f.chapter.id, f.choice, f.paths, f.sources)!!
        for (changed in listOf(f.config.copy(targetLanguage = "hi"), f.config.copy(styleId = "formal", refinementRequest = null),
            f.config.copy(ocrScript = "LATIN"), f.config.copy(highAccuracy = false),
            f.config.copy(preserveStyle = false), f.config.copy(localRefinement = false, refinementRequest = null))) {
            assertFalse(ReaderTranslationPresentation.matchesReader(bound, f.chapter.id,
                ReaderTranslationChoice.from(changed.copy(refinementRequest = null)), f.paths, f.sources))
        }
        assertFalse(ReaderTranslationPresentation.matchesReader(bound, "f".repeat(32), f.choice, f.paths, f.sources))
    }

    @Test fun customInstructionCannotAttachAResultFromAnotherCapturedRequest() = runTest {
        val f = Fixture(temporary.newFolder())
        val style = TranslationStyleProfile.custom("Keep respectful short dialogue.")
        val config = f.config.copy(styleId = "custom", customStyle = style.instruction,
            refinementRequest = f.request.copy(style = style))
        val task = f.complete(f.store(), config)
        val choice = ReaderTranslationChoice.from(config.copy(refinementRequest = null))
        val bound = ReaderTranslationPresentation.capture(task, f.chapter.id, choice, f.paths, f.sources)!!
        assertEquals(style, bound.configuration.refinementRequest!!.style)
        val changed = ReaderTranslationChoice.from(config.copy(customStyle = "Use casual short dialogue.", refinementRequest = null))
        assertFalse(ReaderTranslationPresentation.matchesReader(bound, f.chapter.id, changed, f.paths, f.sources))
    }

    @Test fun explicitLegacyAndUnavailableRequestsRemainUnchangedRatherThanAdoptAPin() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store()
        for (config in listOf(f.config.copy(refinementRequest = null),
            f.config.copy(refinementRequest = f.request.copy(pinnedModel = null)))) {
            val started = store.start(f.chapter, config, ownerRequestId = "reader-legacy")
            val failed = store.fail(started.id, started.generation, "Start a new translation after installing a verified model.")!!
            val bound = ReaderTranslationPresentation.restore(listOf(failed), f.chapter.id, f.choice, f.paths, f.sources)!!
            assertEquals(config, bound.configuration)
            assertNull(bound.configuration.refinementRequest?.pinnedModel)
            val selected = ReaderTranslationPresentation.select(listOf(failed), bound, f.chapter.id, f.choice, f.paths, f.sources)!!
            assertEquals(failed.error, selected.error)
            assertEquals(ChapterTranslationStatus.FAILED, selected.status)
            assertFalse(selected.hasTranslations)
        }
    }

    @Test fun trustedResumeReceiptCanRotateOnlyTheSameFullCapturedScope() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store()
        val missing = File(f.sources, "missing.png")
        val chapter = f.chapter.copy(pages = f.chapter.pages + ChapterPage(2, "local:missing", missing.absolutePath))
        val task = f.complete(store, selected = chapter)
        assertEquals(ChapterTranslationStatus.PARTIAL, task.status)
        val paths = f.paths + (2 to missing.absolutePath)
        val bound = ReaderTranslationPresentation.capture(task, chapter.id, f.choice, paths, f.sources)!!
        val resumed = store.resume(task.id, task.generation)!!
        assertNotEquals(task.generation, resumed.generation)
        val next = ReaderTranslationPresentation.continueReceipt(bound, resumed)!!
        assertEquals(f.config, next.configuration)
        assertEquals(resumed.generation, next.generation)
        assertTrue(resumed.pages.first().lettering.isNotEmpty())
        assertNull(ReaderTranslationPresentation.select(listOf(resumed), bound, chapter.id, f.choice, paths, f.sources))
        assertEquals(resumed, ReaderTranslationPresentation.select(listOf(resumed), next, chapter.id, f.choice, paths, f.sources))
        assertNull(ReaderTranslationPresentation.continueReceipt(bound, resumed.copy(ownerRequestId = "another-owner")))
        assertNull(ReaderTranslationPresentation.continueReceipt(bound, resumed.copy(config = f.config.copy(refinementRequest =
            f.request.copy(pinnedModel = f.request.pinnedModel!!.copy(sha256 = "f".repeat(64)))))))
    }

    @Test fun readerDirectoryAliasKeepsTheReceiptWhileOutsideAndRetargetedAliasesDetach() = runTest {
        val f = Fixture(temporary.newFolder()); val task = f.complete(f.store())
        val alias = File(temporary.root, "reader-alias")
        Files.createSymbolicLink(alias.toPath(), f.sources.toPath())
        val paths = mapOf(1 to File(alias, f.source.name).absolutePath)
        val bound = ReaderTranslationPresentation.capture(task, f.chapter.id, f.choice, paths, f.sources)!!
        assertEquals(task, ReaderTranslationPresentation.select(listOf(task), bound, f.chapter.id, f.choice, paths, f.sources))
        val other = temporary.newFolder("outside")
        File(other, f.source.name).writeBytes(f.source.readBytes())
        Files.delete(alias.toPath()); Files.createSymbolicLink(alias.toPath(), other.toPath())
        assertFalse(ReaderTranslationPresentation.matchesReader(bound, f.chapter.id, f.choice, paths, f.sources))
        assertNull(ReaderTranslationPresentation.restore(listOf(task), f.chapter.id, f.choice, paths, f.sources))
    }

    @Test fun aFullCapturedModelRequestCannotBeSilentlyTreatedAsAnUnpinnedUiChoice() {
        val f = Fixture(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { ReaderTranslationChoice.from(f.config) }
    }

    @Test fun realJournalAliasRoundTripRetainsEveryPageProofUnderCanonicalFileIdentity() = runTest {
        val f = Fixture(temporary.newFolder())
        val alias = File(temporary.root, "context-files-alias")
        Files.createSymbolicLink(alias.toPath(), requireNotNull(f.journals.parentFile).toPath())
        val warm = f.complete(f.store(File(alias, "journals")))
        val cold = f.store().refresh(warm.id, warm.generation)!!
        assertNotEquals(warm.pages.single().cleanedPath, cold.pages.single().cleanedPath)
        assertEquals(File(warm.pages.single().cleanedPath!!).canonicalFile, File(cold.pages.single().cleanedPath!!).canonicalFile)
        fun canonical(task: ChapterTranslationTask) = task.pages.map { page -> page.copy(
            sourcePath = page.sourcePath?.let { File(it).canonicalPath },
            cleanedPath = page.cleanedPath?.let { File(it).canonicalPath }) }
        assertEquals(canonical(warm), canonical(cold))
        assertArrayEquals(File(warm.pages.single().cleanedPath!!).readBytes(), File(cold.pages.single().cleanedPath!!).readBytes())
    }

    @Test fun aMissingFailedPageCannotHideItsVerifiedTranslatedNeighbourOnColdRestore() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store()
        val missing = File(f.sources, "missing.png")
        val chapter = f.chapter.copy(pages = f.chapter.pages + ChapterPage(2, "local:missing", missing.absolutePath))
        val task = f.complete(store, selected = chapter)
        val paths = f.paths + (2 to null) // ChapterLibrary intentionally clears a missing file's localPath.
        val cold = f.store()
        val bound = ReaderTranslationPresentation.restore(cold.states.value, chapter.id, f.choice, paths, f.sources)!!
        val verified = cold.refresh(task.id, task.generation)!!
        val selected = ReaderTranslationPresentation.select(listOf(verified), bound, chapter.id, f.choice, paths, f.sources)!!
        assertEquals(ChapterTranslationStatus.PARTIAL, selected.status)
        assertTrue(selected.pages.first().lettering.isNotEmpty())
        assertNull(selected.pages.last().cleanedPath)
        assertNull(selected.pages.last().sourceSha256)
    }

    @Test fun sourceInvalidationCanDemoteOnlyUnpaintedRowsWhileOtherSourceProofStaysExact() = runTest {
        val f = Fixture(temporary.newFolder()); val store = f.store()
        val second = File(f.sources, "second.png").apply { Fixture.png(this, true) }
        val chapter = f.chapter.copy(pages = f.chapter.pages + ChapterPage(2, "local:second", second.absolutePath))
        val task = f.complete(store, selected = chapter)
        val bound = ReaderTranslationPresentation.capture(task, chapter.id, f.choice, f.paths + (2 to second.absolutePath), f.sources)!!
        assertTrue(second.delete())
        val verified = store.refresh(task.id, task.generation)!!
        val selected = ReaderTranslationPresentation.select(listOf(verified), bound, chapter.id, f.choice, f.paths + (2 to null), f.sources)!!
        assertEquals(task.pages.first(), selected.pages.first())
        assertFalse(selected.pages.last().isComplete)
        assertTrue(selected.pages.last().lettering.isEmpty())
        assertNull(selected.pages.last().cleanedPath)
        val forged = verified.copy(pages = verified.pages.map { if (it.index == 1) it.copy(sourceSha256 = "f".repeat(64)) else it })
        assertNull(ReaderTranslationPresentation.select(listOf(forged), bound, chapter.id, f.choice, f.paths + (2 to null), f.sources))
    }
}
