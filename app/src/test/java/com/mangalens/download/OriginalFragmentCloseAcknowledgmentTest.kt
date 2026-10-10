package com.mangalens.download

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN. Real OkHttp adapter negative control; source close cannot be a quiet acknowledgment. */
class OriginalFragmentCloseAcknowledgmentTest {
    private fun fixture(failClose: Boolean, work: (OkHttpClient, OriginalFragmentPlan, File, File, AtomicInteger) -> Unit) {
        val root = Files.createTempDirectory("fragment-close-ack").toFile()
        val closes = AtomicInteger()
        val encoded = byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112)
        val source = object : ForwardingSource(Buffer().write(encoded)) {
            override fun close() { closes.incrementAndGet(); if (failClose) throw IOException("controlled actual source close"); super.close() }
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type", "audio/mp4")
            .body(object : ResponseBody() {
                override fun contentType() = "audio/mp4".toMediaType()
                override fun contentLength() = encoded.size.toLong()
                override fun source() = source
            }).build() }.build()
        try {
            work(client, OriginalFragmentPlan("https://media.example/anchor", "audio", "audio/mp4", 1_000_000,
                listOf(OriginalMediaFragment("https://media.example/encoded", expectedBytes = encoded.size.toLong()))),
                File(root, "source.part"), File(root, "source.validator"), closes)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); root.deleteRecursively() }
    }
    @Test fun failedActualSourceCloseCannotCommitAFragmentOrReturnACompleteTrack() = fixture(true) { client, plan, temp, checkpoint, closes ->
        val retained = mutableListOf<Any>(); val unsafe = AtomicInteger()
        val transfer = OriginalFragmentTransfer({ client }, privateFileCloseFailed = { unsafe.incrementAndGet() }, transportCloseFailed = { retained.add(it); Unit })
        try { runBlocking { transfer.download(plan, temp, checkpoint) }; fail("Response.close quietly suppressed failed ownership") }
        catch (_: IOException) { }
        assertEquals(1, closes.get()); assertEquals(1, unsafe.get())
        assertEquals(1, retained.size); assertTrue(retained.single() is Response)
        assertFalse("No committed receipt can acknowledge the failed source close", checkpoint.exists())
        assertEquals("Unsafe resources keep the actual partial bytes", 8L, temp.length())
    }
    @Test fun successUsesOneActualSourceCloseAndRetainsTheExactCommittedBytes() = fixture(false) { client, plan, temp, checkpoint, closes ->
        assertEquals(8L, runBlocking { OriginalFragmentTransfer({ client }).download(plan, temp, checkpoint) })
        assertEquals(1, closes.get()); assertTrue(checkpoint.isFile); assertEquals(8L, temp.length())
        assertEquals(plan.sha256(), org.json.JSONObject(checkpoint.readText()).getString("planSha256"))
    }
}
