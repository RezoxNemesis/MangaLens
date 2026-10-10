package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real journal/atomic-write boundaries; decoder callbacks are metadata fixtures, not Bitmap proof. */
class ReaderBubbleInspectionDeliveryTest {
    @Test fun coldPendingReadIsNotImplicitlyRefreshedOrIndexed() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val original = f.completed(); val native = f.journal(original.id).readBytes()
            val cold = f.store(); val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(original))
            val adapter = NativeMemoryPublicationAdapter(f.root, cold, authority)
            assertNull(adapter.inspectSavedBubble(selected, 0, 0, original.pages.single().lettering.single()))
            assertTrue(cold.get(original.id)!!.validationPending)
            assertFalse(File(f.root, "reader_memory").exists())
            assertArrayEquals(native, f.journal(original.id).readBytes())
        }
    }

    @Test fun aStructurallyAlteredOwnerOrConfigCannotCreateAReadProof() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val receipt = ReaderTranslationPresentation.receipt(task)
            val authority = ReaderMemoryPublicationAuthority(); val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            val values = listOf(receipt.copy(owner = "other-owner"), receipt.copy(configuration = receipt.configuration.copy(highAccuracy = false)),
                receipt.copy(sources = receipt.sources.map { it.copy(sha256 = "f".repeat(64)) }))
            for (value in values) assertNull(adapter.inspectSavedBubble(authority.activate(value), 0, 0, task.pages.single().lettering.single()))
            assertFalse(File(f.root, "reader_memory").exists())
        }
    }

    @Test fun identicalOriginalBytesAtomicallyReplacedAfterIoRetireTheHeldIncarnation() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val same = f.source.readBytes(); val replacement = File(f.sources, "replacement-original")
            replacement.outputStream().use { it.write(same); it.fd.sync() }
            Files.move(replacement.toPath(), f.source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertEquals(task.pages.single().sourceSha256, ChapterTranslationStore.sha256(f.source))
            assertNull(adapter.tryAcceptInspection(inspection))
            assertArrayEquals(same, f.source.readBytes())
        }
    }

    @Test fun readerAuthorityMonitorCannotBlockCopiedInspectionDelivery() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val held = async(Dispatchers.Default) { synchronized(authority) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(500) { withContext(Dispatchers.Default) { assertNotNull(adapter.tryAcceptInspection(inspection)) } }
            } finally { release.countDown(); held.cancelAndJoin() }
        }
    }

    @Test fun realFsyncedProfilePreparationDoesNotBlockReadThenItsRenameRetiresThatRead() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            adapter.memory.createSeries("Explicit series", "series"); adapter.memory.associateChapter(f.chapter.id, "series", 0)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val writer = MemoryJournalWriter { file, bytes ->
                val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); pending
            }
            val heldMemory = SeriesMemoryStore(f.root, writer)
            val edit = async(Dispatchers.Default) { heldMemory.setStyle("series", SeriesStylePreference("natural", "hi")) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(500) { withContext(Dispatchers.Default) { assertNotNull(adapter.tryAcceptInspection(inspection)) } }
                release.countDown(); edit.await()
                assertNull(adapter.tryAcceptInspection(inspection))
            } finally { release.countDown(); edit.cancelAndJoin() }
        }
    }

    @Test fun realFsyncedNativePreparationDoesNotBlockReadThenAcceptedPauseRetiresThatRead() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val originalStore = f.store()
            f.completed(nativeStore = originalStore)
            val queued = originalStore.start(f.chapter, f.config, ownerRequestId = "reader:held-running")
            val original = requireNotNull(originalStore.markRunning(queued.id, queued.generation))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val heldIo = object : ChapterJournalIo {
                override fun read(file: File) = f.io.read(file)
                override fun write(file: File, bytes: ByteArray) {
                    val pending = File(file.parentFile, file.name + ".held-before-native-rename")
                    try {
                        pending.outputStream().use { it.write(bytes); it.fd.sync() }
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { pending.delete() }
                }
            }
            val native = ChapterTranslationStore(f.journals, f.sources, heldIo,
                originalDimensions = { f.actualSourceDimensions }) { it.readText().startsWith("valid surface:") }
            val task = requireNotNull(native.refresh(original.id, original.generation))
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val journal = f.journal(task.id).readBytes()
            val pause = async(Dispatchers.Default) { native.pause(task.id, task.generation) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertArrayEquals(journal, f.journal(task.id).readBytes())
                withTimeout(500) { withContext(Dispatchers.Default) { assertNotNull(adapter.tryAcceptInspection(inspection)) } }
                release.countDown(); pause.await()
                assertNull(adapter.tryAcceptInspection(inspection))
            } finally { release.countDown(); pause.cancelAndJoin() }
        }
    }
}
