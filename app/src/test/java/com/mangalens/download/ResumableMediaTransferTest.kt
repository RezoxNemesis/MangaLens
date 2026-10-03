package com.mangalens.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ResumableMediaTransferTest {
    @get:Rule val directory = TemporaryFolder()

    private fun fixture(test: suspend (MockWebServer, ResumableMediaTransfer, File, File) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val temp = File(directory.root, "media.part")
        val validator = File(directory.root, "media.validator")
        try { test(server, ResumableMediaTransfer(client), temp, validator) }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    }

    @Test fun interruptedResponseResumesWithTheExactSavedBytes() = fixture { server, transfer, temp, validator ->
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\"")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { transfer.download(server.url("/media").toString(), temp, validator); fail("Truncated response succeeded") }
        catch (_: IOException) { }
        val offset = temp.length().toInt()
        assertTrue(offset in 1 until payload.size)
        assertEquals("\"v1\"", validator.readText())
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.size}")
            .setHeader("ETag", "\"v1\"").setBody(Buffer().write(payload, offset, payload.size - offset)))
        transfer.download(server.url("/media").toString(), temp, validator)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertArrayEquals(payload, temp.readBytes())
    }

    @Test fun fullRestartRemovesOldValidatorBeforeAnotherAttempt() = fixture { server, transfer, temp, validator ->
        temp.writeText("old"); validator.writeText("\"old\"")
        server.enqueue(MockResponse().setBody("replacement"))
        transfer.download(server.url("/media").toString(), temp, validator)
        assertEquals("replacement", temp.readText())
        assertFalse(validator.exists())
        server.enqueue(MockResponse().setBody("fresh"))
        transfer.download(server.url("/media").toString(), temp, validator)
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("fresh", temp.readText())
    }

    @Test fun partialWithoutValidatorRestartsInsteadOfAppending() = fixture { server, transfer, temp, validator ->
        temp.writeText("unsafe partial")
        server.enqueue(MockResponse().setBody("complete"))
        transfer.download(server.url("/media").toString(), temp, validator)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("complete", temp.readText())
    }

    @Test fun matchingStartWithInvalidRangeEndIsRejected() = fixture { server, transfer, temp, validator ->
        temp.writeText("abc"); validator.writeText("\"v1\"")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-2/6").setBody("def"))
        try { transfer.download(server.url("/media").toString(), temp, validator); fail("Invalid range accepted") }
        catch (_: IOException) { }
        assertFalse(temp.exists()); assertFalse(validator.exists())
    }

    @Test fun rangeLengthMismatchAndMissingRangeAreRejected() = fixture { server, transfer, temp, validator ->
        for (range in listOf("bytes 3-5/6", "")) {
            temp.writeText("abc"); validator.writeText("\"v1\"")
            val response = MockResponse().setResponseCode(206).setBody("de")
            if (range.isNotEmpty()) response.setHeader("Content-Range", range)
            server.enqueue(response)
            try { transfer.download(server.url("/media").toString(), temp, validator); fail("Malformed range accepted") }
            catch (_: IOException) { }
            assertFalse(temp.exists())
        }
    }

    @Test fun changedRepresentationCannotBeAppended() = fixture { server, transfer, temp, validator ->
        temp.writeText("abc"); validator.writeText("\"v1\"")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
            .setHeader("ETag", "\"v2\"").setBody("XYZ"))
        try { transfer.download(server.url("/media").toString(), temp, validator); fail("Different representation accepted") }
        catch (_: IOException) { }
        assertFalse(temp.exists()); assertFalse(validator.exists())
    }

    @Test fun unsatisfiableRangeResetsForRetryAndChunkedBodyCompletes() = fixture { server, transfer, temp, validator ->
        temp.writeText("abc"); validator.writeText("\"v1\"")
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */2"))
        try { transfer.download(server.url("/media").toString(), temp, validator); fail("416 succeeded") }
        catch (_: IOException) { }
        server.enqueue(MockResponse().setChunkedBody("complete chunked body", 3))
        transfer.download(server.url("/media").toString(), temp, validator)
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("complete chunked body", temp.readText())
    }

    @Test fun cancellationInterruptsAStalledSocket() = fixture { server, transfer, temp, validator ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val download = launch(Dispatchers.IO) { transfer.download(server.url("/media").toString(), temp, validator) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(5_000) { download.cancelAndJoin() }
        assertTrue(download.isCancelled)
    }
}
