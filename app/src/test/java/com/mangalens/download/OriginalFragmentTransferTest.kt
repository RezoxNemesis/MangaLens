package com.mangalens.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN. Real HTTP/file adapters and exact original binary byte oracles. */
class OriginalFragmentTransferTest {
    private val init = byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112)
    private val one = byteArrayOf(0, 0, 0, 8, 109, 111, 111, 102, 1, 2, 3)
    private val two = byteArrayOf(0, 0, 0, 8, 109, 100, 97, 116, 4, 5, 6)
    private fun plan(server: MockWebServer) = OriginalFragmentPlan(server.url("/manifest-base/").toString(), "v1080", "video/mp4", 2_000_000L,
        listOf("init", "one", "two").zip(listOf(init, one, two)).map { (name, bytes) -> OriginalMediaFragment(server.url("/$name").toString(), expectedBytes = bytes.size.toLong()) })
    private fun binary(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
    private inline fun fixture(block: (MockWebServer, File, File, OkHttpClient) -> Unit) {
        val server = MockWebServer(); val dir = Files.createTempDirectory("fragment-transfer").toFile()
        val client = OkHttpClient.Builder().build(); server.start()
        try { block(server, File(dir, "owned.part"), File(dir, "owned.validator"), client) }
        finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }
    @Test fun initializationAndEveryMediaFragmentAreCopiedInExactSelectedOrder() = fixture { server, temp, receipt, client ->
        server.enqueue(binary(init)); server.enqueue(binary(one)); server.enqueue(binary(two))
        val selected = plan(server)
        val bytes = runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        assertArrayEquals(init + one + two, temp.readBytes()); assertEquals(temp.length(), bytes)
        assertEquals(listOf("/init", "/one", "/two"), (0 until 3).map { server.takeRequest().path })
        val checkpoint = org.json.JSONObject(receipt.readText())
        assertEquals(selected.sha256(), checkpoint.getString("planSha256")); assertEquals(3, checkpoint.getJSONArray("completed").length())
    }
    @Test fun failedMiddleFragmentRollsBackAndResumeReusesOnlyVerifiedPrefix() = fixture { server, temp, receipt, client ->
        val broken = AtomicBoolean(true); val initialRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
            "/init" -> { initialRequests.incrementAndGet(); binary(init) }
            "/one" -> if (broken.getAndSet(false)) binary(one).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else binary(one)
            "/two" -> binary(two)
            else -> MockResponse().setResponseCode(404)
        } }
        val selected = plan(server)
        try { runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }; fail("Incomplete middle fragment completed") } catch (_: IOException) { }
        assertArrayEquals(init, temp.readBytes())
        runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        assertArrayEquals(init + one + two, temp.readBytes()); assertEquals(1, initialRequests.get())
    }
    @Test fun failedSecondCheckpointWritePreservesPriorReceiptAndColdResumeVerifiedPrefix() = fixture { server, temp, receipt, client ->
        val initialRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
            "/init" -> { initialRequests.incrementAndGet(); binary(init) }
            "/one" -> binary(one)
            "/two" -> binary(two)
            else -> MockResponse().setResponseCode(404)
        } }
        val checkpoints = AtomicInteger()
        val selected = plan(server)
        val transfer = OriginalFragmentTransfer({ client }, openPrivateOutput = { file, append ->
            if (file.name.endsWith(".validator.new") && checkpoints.incrementAndGet() == 2) {
                object : FileOutputStream(file, append) { override fun write(bytes: ByteArray) {
                    super.write(bytes, 0, minOf(5, bytes.size)); throw IOException("controlled checkpoint write failure")
                } }
            } else FileOutputStream(file, append)
        })
        try { runBlocking { transfer.download(selected, temp, receipt) }; fail("Failed receipt write completed") }
        catch (_: FragmentCheckpointIOException) { }
        val prior = org.json.JSONObject(receipt.readText())
        assertEquals(selected.sha256(), prior.getString("planSha256"))
        assertEquals(1, prior.getJSONArray("completed").length())
        assertArrayEquals(init, temp.readBytes())
        runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        assertArrayEquals(init + one + two, temp.readBytes()); assertEquals(1, initialRequests.get())
        assertEquals(3, org.json.JSONObject(receipt.readText()).getJSONArray("completed").length())
        assertFalse(File(receipt.parentFile, receipt.name + ".new").exists())
    }
    @Test fun declaredRangesRequireExact206BoundsAndOrderedOriginalBytes() = fixture { server, temp, receipt, client ->
        val selected = OriginalFragmentPlan(server.url("/manifest").toString(), "v1080", "video/mp4", 2_000_000L,
            listOf(OriginalMediaFragment(server.url("/media").toString(), 10L, 21L)))
        server.enqueue(binary(one).setResponseCode(206).setHeader("Content-Range", "bytes 10-20/100"))
        runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        assertArrayEquals(one, temp.readBytes()); assertEquals("bytes=10-20", server.takeRequest().getHeader("Range"))
    }
    @Test fun ignoredRangeCannotBeDeclaredComplete() = fixture { server, temp, receipt, client ->
        val selected = OriginalFragmentPlan(server.url("/manifest").toString(), "v1080", "video/mp4", 2_000_000L,
            listOf(OriginalMediaFragment(server.url("/media").toString(), 10L, 21L)))
        server.enqueue(binary(one))
        try { runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }; fail("Ignored range accepted") } catch (_: IOException) { }
        assertEquals(0L, temp.length()); assertFalse(receipt.exists())
    }
    @Test fun documentResponseNeverCommitsFragmentOrCompletesTransfer() = fixture { server, temp, receipt, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/dash+xml").setBody("<MPD/>"))
        try { runBlocking { OriginalFragmentTransfer({ client }).download(plan(server), temp, receipt) }; fail("Manifest counted as encoded bytes") } catch (_: IOException) { }
        assertEquals(0L, temp.length()); assertFalse(receipt.exists())
    }
    @Test fun documentPrefixIsRejectedEvenUnderOctetStreamAndShortReads() = fixture { server, temp, receipt, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setChunkedBody("<?xml version='1.0'?><MPD/>", 1))
        val selected = plan(server).copy(fragments = listOf(plan(server).fragments.first().copy(expectedBytes = null)))
        try { runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }; fail("Document passed binary guard") } catch (_: IOException) { }
        assertEquals(0L, temp.length()); assertFalse(receipt.exists())
    }
    @Test fun alteredSavedPrefixFailsBeforeAnotherNetworkRequest() = fixture { server, temp, receipt, client ->
        server.enqueue(binary(init)); server.enqueue(binary(one)); server.enqueue(binary(two)); val selected = plan(server)
        runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        val changed = temp.readBytes().also { it[0] = 1 }; temp.writeBytes(changed)
        try { runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }; fail("Changed prefix accepted") } catch (_: IOException) { }
        assertEquals(3, server.requestCount); assertArrayEquals(changed, temp.readBytes())
    }
    @Test fun privateCloseFailureRetainsAppOwnershipAndDoesNotRollbackStillOwnedBytes() = fixture { server, temp, receipt, client ->
        server.enqueue(binary(init)); val failed = AtomicInteger()
        val transfer = OriginalFragmentTransfer({ client }, { failed.incrementAndGet() }, { file, append ->
            object : FileOutputStream(file, append) { override fun close() { super.close(); throw IOException("controlled ambiguous close") } }
        })
        try { runBlocking { transfer.download(plan(server), temp, receipt) }; fail("Unproven close completed") } catch (problem: IOException) { assertTrue(problem.hasUnprovenPrivateClose()) }
        assertEquals(1, failed.get()); assertArrayEquals(init, temp.readBytes()); assertFalse(receipt.exists())
    }
    @Test fun changedPlanCannotBorrowPreviousCompleteTransportReceipt() = fixture { server, temp, receipt, client ->
        server.enqueue(binary(init)); server.enqueue(binary(one)); server.enqueue(binary(two)); val selected = plan(server)
        runBlocking { OriginalFragmentTransfer({ client }).download(selected, temp, receipt) }
        try { runBlocking { OriginalFragmentTransfer({ client }).download(selected.copy(fragments = selected.fragments.reversed()), temp, receipt) }; fail("Old receipt borrowed by another order") } catch (_: IOException) { }
        assertEquals(3, server.requestCount); assertArrayEquals(init + one + two, temp.readBytes())
    }
}
