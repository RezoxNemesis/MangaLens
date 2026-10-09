package com.mangalens

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ProgressiveChapterRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Real bounded acquisition, BitmapFactory validation and atomic repair of private fixture files. */
@RunWith(AndroidJUnit4::class)
class ChapterSourceRecoveryTest {
    @Test fun nonemptyCorruptRemoteCacheIsRepairedFromItsSourceWithoutLosingNeighbours() = runBlocking<Unit> {
        Fixture().use { f ->
            val first = png(Color.RED)
            val replacement = png(Color.BLUE)
            val neighbourBytes = png(Color.GREEN)
            f.server.enqueue(imageResponse(first))
            f.server.enqueue(imageResponse(neighbourBytes))
            val url = f.url("page.png")
            val page = f.repository.persistPage(1, url)
            val neighbour = f.repository.persistPage(2, f.url("neighbour.png"))
            val file = File(requireNotNull(page.localPath))
            file.writeText("Nonempty interrupted image")
            f.server.enqueue(imageResponse(replacement))

            val repaired = f.repository.persistPage(1, url)

            assertEquals("Repair retains the chapter's stable managed path", page.localPath, repaired.localPath)
            assertImage(file, replacement)
            assertNotEquals("Verified replacement must invalidate the current Reader's source revision", page, repaired)
            assertImage(File(requireNotNull(neighbour.localPath)), neighbourBytes)
            assertEquals("An explicit retry must actually reacquire the corrupt cached page", 3, f.server.requestCount)
            assertFalse(File(file.path + ".part").exists())
        }
    }

    @Test fun invalidRemoteReplacementPreservesExistingBytesUntilAVerifiedRetrySucceeds() = runBlocking<Unit> {
        Fixture().use { f ->
            f.server.enqueue(imageResponse(png(Color.RED)))
            val url = f.url("page.png")
            val page = f.repository.persistPage(1, url)
            val file = File(requireNotNull(page.localPath))
            val damaged = "Keep these damaged fixture bytes for a later retry".toByteArray()
            file.writeBytes(damaged)
            f.server.enqueue(MockResponse().setResponseCode(200).setBody("Unsupported image response"))

            try {
                f.repository.persistPage(1, url)
                fail("A corrupt cache cannot be published as a successfully repaired image")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("supported image"))
            }
            assertArrayEquals("Failed repair must preserve the previous original", damaged, file.readBytes())
            assertFalse("Failed repair must not leave temporary image bytes", File(file.path + ".part").exists())

            val repairedBytes = png(Color.BLUE)
            f.server.enqueue(imageResponse(repairedBytes))
            assertEquals(page.localPath, f.repository.persistPage(1, url).localPath)
            assertImage(file, repairedBytes)
        }
    }

    @Test fun healthyRemoteCacheRemainsAvailableOfflineWithoutAnotherFetch() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = png(Color.RED)
            f.server.enqueue(imageResponse(bytes))
            val url = f.url("page.png")
            val page = f.repository.persistPage(1, url)
            assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            f.server.shutdown()

            assertEquals(page, f.repository.persistPage(1, url))
            assertImage(File(requireNotNull(page.localPath)), bytes)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun nonemptyCorruptLocalImportIsRepairedWithoutChangingTheSelectedOriginal() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = png(Color.GREEN)
            val original = File(f.directory, "selected.png").apply { writeBytes(bytes) }
            val uri = Uri.fromFile(original)
            val page = f.repository.persistLocalImages(listOf(uri), f.context).single()
            val cached = File(requireNotNull(page.localPath))
            cached.writeText("Nonempty corrupted imported cache")

            val repaired = f.repository.persistLocalImages(listOf(uri), f.context).single()

            assertEquals(page.localPath, repaired.localPath)
            assertImage(cached, bytes)
            assertNotEquals("Local repair must invalidate already displayed source pixels", page, repaired)
            assertArrayEquals("Repair must never write to the user's selected original", bytes, original.readBytes())
            assertFalse(File(cached.path + ".part").exists())
        }
    }

    @Test fun unreadableLocalReplacementPreservesPriorCacheForALaterExplicitRepair() = runBlocking<Unit> {
        Fixture().use { f ->
            val original = File(f.directory, "selected.png").apply { writeBytes(png(Color.GREEN)) }
            val uri = Uri.fromFile(original)
            val page = f.repository.persistLocalImages(listOf(uri), f.context).single()
            val cached = File(requireNotNull(page.localPath))
            val prior = "Preserve the interrupted private cache".toByteArray()
            cached.writeBytes(prior)
            original.writeText("Selected source is temporarily unreadable")

            try {
                f.repository.persistLocalImages(listOf(uri), f.context)
                fail("A corrupt local cache must require an actual readable selected source")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("supported image"))
            }

            assertArrayEquals(prior, cached.readBytes())
            assertEquals("Selected source is temporarily unreadable", original.readText())
            assertFalse(File(cached.path + ".part").exists())
        }
    }

    @Test fun retryingTheSecondLocalPageRetainsItsIndexAndAllNeighbouringPages() = runBlocking<Unit> {
        Fixture().use { f ->
            val firstBytes = png(Color.RED)
            val secondBytes = png(Color.BLUE)
            val first = File(f.directory, "first.png").apply { writeBytes(firstBytes) }
            val second = File(f.directory, "second.png").apply { writeBytes(secondBytes) }
            val pages = f.repository.persistLocalImages(listOf(Uri.fromFile(first), Uri.fromFile(second)), f.context)
            val target = pages[1]
            val cache = File(requireNotNull(target.localPath)).apply { writeText("Damaged second page") }

            val repaired = f.repository.repairPage(target, f.context)

            assertEquals(2, repaired.index)
            assertEquals(target.sourceUrl, repaired.sourceUrl)
            assertEquals(target.localPath, repaired.localPath)
            assertEquals(listOf(pages[0], repaired), f.repository.pages.value)
            assertImage(cache, secondBytes)
            assertImage(File(requireNotNull(pages[0].localPath)), firstBytes)
            assertArrayEquals(firstBytes, first.readBytes())
            assertArrayEquals(secondBytes, second.readBytes())
        }
    }

    @Test fun aStalePageRetryCannotReplaceTheCurrentChapter() = runBlocking<Unit> {
        Fixture().use { f ->
            val original = File(f.directory, "selected.png").apply { writeBytes(png(Color.RED)) }
            val page = f.repository.persistLocalImages(listOf(Uri.fromFile(original)), f.context).single()
            val cached = File(requireNotNull(page.localPath)).apply { writeText("Preserve stale damaged original") }
            val current = page.copy(index = 3, sourceUrl = "content://different/chapter")
            f.repository.restorePages(listOf(current))

            try {
                f.repository.repairPage(page, f.context)
                fail("Retrying a stale chapter must be rejected before source or cache effects")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("selected chapter page has changed"))
            }
            assertEquals(listOf(current), f.repository.pages.value)
            assertEquals("Preserve stale damaged original", cached.readText())
            assertFalse(File(cached.path + ".part").exists())
        }
    }

    @Test fun cancellationDuringSourceReacquisitionPreservesTheOldCacheAndAllowsALaterRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            val original = png(Color.RED)
            val replacement = png(Color.BLUE)
            val url = f.url("cancelled-source.png")
            f.server.enqueue(imageResponse(original))
            val page = f.repository.persistPage(1, url)
            val cached = File(requireNotNull(page.localPath))
            val prior = "Retain interrupted original until verified repair".toByteArray()
            cached.writeBytes(prior)
            f.server.enqueue(imageResponse(replacement).throttleBody(8, 200, TimeUnit.MILLISECONDS))
            val repairing = launch(Dispatchers.Default) { f.repository.repairPage(page, f.context) }
            withTimeout(5000L) {
                while (!File(cached.path + ".part").exists()) delay(20L)
            }

            repairing.cancelAndJoin()

            assertArrayEquals(prior, cached.readBytes())
            assertEquals(listOf(page), f.repository.pages.value)
            assertFalse(File(cached.path + ".part").exists())
            f.server.enqueue(imageResponse(replacement))
            val repaired = f.repository.repairPage(page, f.context)
            assertImage(cached, replacement)
            assertNotEquals(page.contentRevision, repaired.contentRevision)
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun intactPngDimensionsCannotMakeAnUnreadableRasterASuccessfulCachedPage() = runBlocking<Unit> {
        Fixture().use { f ->
            val url = f.url("broken-raster.png")
            f.server.enqueue(imageResponse(png(Color.RED)))
            val page = f.repository.persistPage(1, url)
            val cached = File(requireNotNull(page.localPath))
            cached.writeBytes(unreadablePng(png(Color.RED)))
            assertUnreadableRasterWithIntactBounds(cached)
            val replacement = png(Color.BLUE)
            f.server.enqueue(imageResponse(replacement))

            val repaired = f.repository.persistPage(1, url)

            assertEquals("A bounds-readable but undecodable cache must be reacquired", 2, f.server.requestCount)
            assertEquals(page.localPath, repaired.localPath)
            assertImage(cached, replacement)
        }
    }

    @Test fun unreadableRasterReplacementPreservesTheOldCacheUntilAVerifiedRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            val url = f.url("invalid-raster-replacement.png")
            f.server.enqueue(imageResponse(png(Color.RED)))
            val page = f.repository.persistPage(1, url)
            val cached = File(requireNotNull(page.localPath))
            val prior = "Retain interrupted original through invalid raster response".toByteArray()
            cached.writeBytes(prior)
            f.server.enqueue(imageResponse(unreadablePng(png(Color.BLUE))))
            try {
                f.repository.persistPage(1, url)
                fail("Intact IHDR dimensions do not verify readable source pixels")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("decode"))
            }
            assertArrayEquals(prior, cached.readBytes())
            assertFalse(File(cached.path + ".part").exists())
            val replacement = png(Color.BLUE)
            f.server.enqueue(imageResponse(replacement))
            f.repository.persistPage(1, url)
            assertImage(cached, replacement)
        }
    }

    @Test fun cancellationOfARequestWithNoResponseDoesNotWaitForTheCallTimeout() = runBlocking<Unit> {
        Fixture().use { f ->
            val url = f.url("stalled-source.png")
            f.server.enqueue(imageResponse(png(Color.RED)))
            val page = f.repository.persistPage(1, url)
            assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            val cached = File(requireNotNull(page.localPath))
            val prior = "Preserve original while response headers are stalled".toByteArray()
            cached.writeBytes(prior)
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repairing = launch(Dispatchers.IO) { f.repository.repairPage(page, f.context) }
            assertNotNull("The real source request must have started before cancellation", f.server.takeRequest(5, TimeUnit.SECONDS))

            withTimeout(2500L) { repairing.cancelAndJoin() }

            assertArrayEquals(prior, cached.readBytes())
            assertEquals(listOf(page), f.repository.pages.value)
            assertFalse(File(cached.path + ".part").exists())
        }
    }

    @Test fun anExplicitRepairReacquiresEvenIdenticalPixelsAndInvalidatesTheFailedPresentation() = runBlocking<Unit> {
        Fixture().use { f ->
            val url = f.url("explicit-repair.png")
            val original = png(Color.RED)
            f.server.enqueue(imageResponse(original))
            val page = f.repository.persistPage(1, url)
            f.server.enqueue(imageResponse(original))

            val repaired = f.repository.repairPage(page, f.context)

            assertEquals("A reader decoder failure needs an actual source retry", 2, f.server.requestCount)
            assertEquals(page.localPath, repaired.localPath)
            assertEquals(page.contentRevision?.substringBefore(':'), repaired.contentRevision?.substringBefore(':'))
            assertNotEquals("Identical verified bytes are a fresh cache incarnation after explicit recovery", page.contentRevision, repaired.contentRevision)
            assertImage(File(requireNotNull(repaired.localPath)), original)
        }
    }

    @Test fun aSourceChangedDuringARepairRejectsTheVerifiedReplacementBeforeCachePromotion() = runBlocking<Unit> {
        Fixture().use { f ->
            val url = f.url("previous-chapter.png")
            f.server.enqueue(imageResponse(png(Color.RED)))
            val captured = f.repository.persistPage(1, url)
            f.server.enqueue(imageResponse(png(Color.GREEN)))
            val current = f.repository.persistPage(1, f.url("new-current-chapter.png"))
            val currentBytes = File(requireNotNull(current.localPath)).readBytes()
            f.repository.restorePages(listOf(captured))
            val cached = File(requireNotNull(captured.localPath))
            val prior = "Retain the captured cache when its chapter changes during transfer".toByteArray()
            cached.writeBytes(prior)
            f.server.enqueue(imageResponse(png(Color.BLUE)).setBodyDelay(2, TimeUnit.SECONDS)
                .throttleBody(8, 100, TimeUnit.MILLISECONDS))
            val repairing = async(Dispatchers.IO) { runCatching { f.repository.repairPage(captured, f.context) } }
            val partial = File(cached.path + ".part")
            withTimeout(8000L) {
                // The output stream is buffered, so tiny PNG bytes do not reach
                // the file until transfer completion. Existence proves the
                // request reached body copying, whose response remains held.
                while (!partial.exists()) delay(20L)
            }
            f.repository.restorePages(listOf(current))

            val rejected = withTimeout(8000L) { repairing.await().exceptionOrNull() }

            assertTrue("The captured source must be rejected before promotion", rejected is IllegalStateException)
            assertTrue(rejected?.message.orEmpty().contains("changed"))
            assertArrayEquals("A stale repair must retain the previous managed original bytes", prior, cached.readBytes())
            assertArrayEquals(currentBytes, File(requireNotNull(current.localPath)).readBytes())
            assertEquals(listOf(current), f.repository.pages.value)
            assertFalse(partial.exists())
        }
    }

    private class Fixture : AutoCloseable {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "chapter-repair-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getFilesDir(): File = directory
        }
        val server = MockWebServer().apply { start() }
        val repository = ProgressiveChapterRepository(context)
        fun url(path: String) = "http://127.0.0.1:${server.port}/$path"
        override fun close() { try { server.shutdown() } finally { directory.deleteRecursively() } }
    }

    private fun imageResponse(bytes: ByteArray) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun assertImage(file: File, expected: ByteArray) {
        assertArrayEquals("Cache must contain the verified replacement's exact image bytes", expected, file.readBytes())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals(32, bounds.outWidth); assertEquals(48, bounds.outHeight)
    }

    private fun unreadablePng(valid: ByteArray): ByteArray {
        val damaged = valid.copyOf()
        var offset = 8
        var changed = false
        while (offset + 12 <= damaged.size) {
            val length = ByteBuffer.wrap(damaged, offset, 4).int
            check(length >= 0 && offset + 12L + length <= damaged.size)
            val type = String(damaged, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT") {
                damaged.fill(0, offset + 8, offset + 8 + length)
                val crc = CRC32().apply { update(damaged, offset + 4, length + 4) }
                ByteBuffer.wrap(damaged).putInt(offset + 8 + length, crc.value.toInt())
                changed = true
            }
            offset += 12 + length
        }
        check(changed)
        return damaged
    }

    private fun assertUnreadableRasterWithIntactBounds(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals(32, bounds.outWidth); assertEquals(48, bounds.outHeight)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        decoded?.recycle()
        assertNull("The regression fixture must fail an actual Android raster decode despite intact dimensions", decoded)
    }
}
