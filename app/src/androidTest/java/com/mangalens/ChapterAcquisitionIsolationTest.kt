package com.mangalens

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.reader.ChapterPageAcquisitionPolicy
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.orez.agent.OrezAcquisitionPrivateOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Authored only: real Android raster/promotion with intercepted fixture responses, no live source. */
@RunWith(AndroidJUnit4::class)
class ChapterAcquisitionIsolationTest {
    @Test fun fullEncodedMetadataRefusalHappensBeforeAnyOriginalRequestOrCatalogReplacement() = runBlocking<Unit> {
        fixture { context, root ->
            val good = png(); var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("Budget refusal cannot acquire originals") }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val old = ChapterPage(7, "https://example.org/old.png", File(root, "chapters/old.img").apply { writeBytes(good) }.path)
            repository.restorePages(listOf(old))
            val failure = runCatching { repository.persistDiscoveredCandidates(listOf(ChapterImageCandidate("https://example.org/new.png"))) { planned, successors ->
                withContext(Dispatchers.IO) { ChapterLibrary(context).checkMetadataBudget(
                    SavedChapter("a".repeat(32), "界".repeat(700_000), "https://example.org/chapter", planned), successors) }
            } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException); assertEquals(0, requests)
            assertEquals(listOf(old), repository.pages.value); assertArrayEquals(good, File(requireNotNull(old.localPath)).readBytes())
        }
    }

    @Test fun successorExpansionRefusesBeforeAnyOriginalOrPendingCatalogPublication() = runBlocking<Unit> {
        fixture { context, root ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("Successor budget refusal cannot acquire originals") }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val old = ChapterPage(7, "https://example.org/old.png", File(root, "chapters/old.img").apply { writeBytes(png()) }.path)
            repository.restorePages(listOf(old))
            val failure = runCatching {
                repository.persistDiscoveredCandidates(listOf(ChapterImageCandidate("https://example.org/new.png",
                    com.mangalens.core.acquisition.ChapterImagePromotion.AD_CONTAINER))) { planned, successors ->
                    withContext(Dispatchers.IO) {
                        val library = ChapterLibrary(context)
                        val seed = SavedChapter("a".repeat(32), "", "https://example.org/chapter", planned, updatedAt = 1, addedAt = 1)
                        val boundary = seed.copy(title = "x".repeat(2_000_000 - library.checkMetadataBudget(seed)))
                        assertEquals(2_000_000, library.checkMetadataBudget(boundary))
                        library.checkMetadataBudget(boundary, successors)
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException); assertEquals(0, requests)
            assertEquals(listOf(old), repository.pages.value)
            assertTrue(File(root, "chapter_library").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun restoreDuringCatalogPreflightRetiresItsPendingSlotsBeforeAnyOriginalEffects() = runBlocking<Unit> {
        fixture { context, _ ->
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("Retired catalogue cannot acquire originals") }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val work = async { runCatching { repository.persistDiscoveredCandidates(listOf(ChapterImageCandidate("https://example.org/old.png"))) { _, _ ->
                entered.complete(Unit); release.await()
            } } }
            try {
                withTimeout(5000) { entered.await() }
                val current = ChapterPage(77, "https://example.org/current.png", error = ChapterPageAcquisitionPolicy.PENDING)
                repository.restorePages(listOf(current)); release.complete(Unit)
                assertTrue(withTimeout(5000) { work.await() }.exceptionOrNull() is CancellationException)
                assertEquals(listOf(current), repository.pages.value); assertEquals(0, requests)
            } finally { release.complete(Unit); work.cancel(); work.join() }
        }
    }

    @Test fun restoreDuringBlockedLocalImportPreventsOldOriginalPromotionAndPublication() = runBlocking<Unit> {
        fixture { context, root ->
            val fifo = File(root, "original.pipe"); Os.mkfifo(fifo.path, 384)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val good = png()
            val repository = ProgressiveChapterRepository(context)
            val reading = async(Dispatchers.IO) { runCatching { repository.persistLocalImages(listOf(Uri.fromFile(fifo)), context) } }
            val writing = async(Dispatchers.IO) { runCatching { FileOutputStream(fifo).use { output ->
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); output.write(good)
            } } }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                val currentFile = File(root, "chapters/current.img").apply { writeBytes(good) }
                val current = ChapterPage(77, "https://example.org/current.png", currentFile.path)
                repository.restorePages(listOf(current)); release.countDown()
                assertTrue(withTimeout(15000) { reading.await() }.exceptionOrNull() is CancellationException)
                assertTrue(withTimeout(15000) { writing.await() }.isSuccess)
                assertEquals(listOf(current), repository.pages.value)
                assertEquals(listOf("current.img"), File(root, "chapters").listFiles().orEmpty().map { it.name }.sorted())
            } finally { release.countDown(); reading.cancel(); writing.cancel(); reading.join(); writing.join() }
        }
    }

    @Test fun aBadMiddlePageRetainsItsOrdinalAndDoesNotAbortTheLaterGoodPage() = runBlocking<Unit> {
        fixture { context, root ->
            val good = png(); val requests = ConcurrentLinkedQueue<String>(); var repaired = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); requests.add(request.url.encodedPath)
                assertEquals("https://example.org/chapter", request.header("Referer"))
                val bad = request.url.encodedPath == "/two.png" && !repaired
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body((if (bad) "Not an image".toByteArray() else good).toResponseBody()).build()
            }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val candidates = listOf("one", "two", "three").map { ChapterImageCandidate("https://example.org/$it.png") }
            val rows = repository.persistDiscoveredCandidates(candidates, "https://example.org/chapter")
            assertEquals(listOf(1, 2, 3), rows.map { it.index })
            assertNotNull(rows[0].localPath); assertNotNull(rows[2].localPath)
            assertEquals(ChapterPageAcquisitionPolicy.FAILURE, rows[1].error)
            assertNull(rows[1].localPath); assertNull(rows[1].contentRevision)
            assertEquals(listOf("/one.png", "/two.png", "/three.png"), requests.toList())
            repaired = true
            val fixed = repository.repairPage(rows[1], context, "https://example.org/chapter")
            assertEquals(2, fixed.index); assertEquals(candidates[1].url, fixed.sourceUrl); assertNull(fixed.error)
            assertSame(rows[0], repository.pages.value[0]); assertSame(rows[2], repository.pages.value[2])
            assertArrayEquals(good, File(requireNotNull(fixed.localPath)).readBytes())
            assertEquals(listOf("/one.png", "/two.png", "/three.png", "/two.png"), requests.toList())
            assertTrue(File(root, "chapters").listFiles().orEmpty().none { it.name.endsWith(".part") })
        }
    }

    @Test fun restoringAnotherChapterDuringBlockedIoPreventsOldPromotionAndFailurePublication() = runBlocking<Unit> {
        fixture { context, root ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val good = png()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(good.toResponseBody()).build()
            }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val work = async(Dispatchers.IO) { runCatching {
                repository.persistDiscoveredCandidates(listOf(ChapterImageCandidate("https://example.org/old.png")))
            } }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                val currentFile = File(root, "chapters/current.img").apply { writeBytes(good) }
                val current = ChapterPage(77, "https://example.org/current.png", currentFile.path, contentRevision = "current")
                repository.restorePages(listOf(current)); release.countDown()
                val failure = withTimeout(15000) { work.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertEquals(listOf(current), repository.pages.value)
                assertEquals(listOf("current.img"), File(root, "chapters").listFiles().orEmpty().map { it.name }.sorted())
                assertArrayEquals(good, currentFile.readBytes())
            } finally { release.countDown(); work.cancel(); work.join() }
        }
    }

    @Test fun cancellationRetainsUnfinishedCatalogSlotsWithoutInventingFailureResults() = runBlocking<Unit> {
        fixture { context, root ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = ConcurrentLinkedQueue<String>(); val good = png()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests.add(chain.request().url.encodedPath); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(good.toResponseBody()).build()
            }.build()
            val repository = ProgressiveChapterRepository(context, client)
            val work = launch(Dispatchers.IO) { repository.persistDiscoveredPages(listOf("https://example.org/one.png", "https://example.org/two.png")) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                work.cancel(); release.countDown(); withTimeout(15000) { work.join() }
                assertEquals(listOf(1, 2), repository.pages.value.map { it.index })
                assertTrue(repository.pages.value.all { it.error == ChapterPageAcquisitionPolicy.PENDING && it.localPath == null })
                assertEquals(listOf("/one.png"), requests.toList())
                assertTrue(File(root, "chapters").listFiles().orEmpty().isEmpty())
            } finally { release.countDown(); work.cancel(); work.join() }
        }
    }

    @Test fun anUnprovenPrivateCloseIsFatalAndNeverStartsTheNextCandidate() = runBlocking<Unit> {
        fixture { context, root ->
            val owner = OrezAcquisitionPrivateOwner(root); var requests = 0
            runCatching { owner.usePrivate(AutoCloseable { throw IOException("fixture close") }) { } }
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("No request may start after unproven close") }.build()
            val repository = ProgressiveChapterRepository(context, client, "0123456789abcdef", owner)
            val failure = runCatching { repository.persistDiscoveredPages(listOf("https://example.org/one.png", "https://example.org/two.png")) }.exceptionOrNull()
            assertNotNull(failure); assertEquals(0, requests); assertFalse(owner.privateReleaseProven())
            assertTrue(repository.pages.value.all { it.error == ChapterPageAcquisitionPolicy.PENDING })
            runCatching { owner.finish() }
        }
    }

    private suspend fun fixture(action: suspend (ContextWrapper, File) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "chapter-isolation-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        try { action(context, root) } finally { root.deleteRecursively() }
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.BLUE)
            return ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
