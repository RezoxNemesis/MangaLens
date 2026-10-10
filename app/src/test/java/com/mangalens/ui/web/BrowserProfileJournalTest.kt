package com.mangalens.ui.web

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserProfileJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private class FailingRead(file: File) : FileInputStream(file) { var closes = 0; override fun close() { closes++; throw IOException("controlled close failure") }; fun releaseFixture() { super.close() } }
    private class FailingWrite(file: File) : FileOutputStream(file) { var closes = 0; override fun close() { closes++; throw IOException("controlled close failure") }; fun releaseFixture() { super.close() } }
    @Test fun failedActualReadCloseRetainsItsHandleAndBlocksAnotherOpen() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1,2)) }; val held = FailingRead(file); var opens = 0
        val io = BrowserProfileJournal(file, openRead = { opens++; held })
        try { try { io.read(100); fail() } catch (_: IOException) {}; try { io.read(100); fail() } catch (_: IOException) {}; assertEquals(1, opens); assertEquals(1, held.closes) } finally { held.releaseFixture() }
    }
    @Test fun failedActualPrivateWriteCloseNeverPromotesOrDeletesItsUnprovenStage() {
        val file = File(temporary.root, "session.json"); var held: FailingWrite? = null; var opens = 0; var promotions = 0
        val io = BrowserProfileJournal(file, openWrite = { opens++; FailingWrite(it).also { stream -> held = stream } }, promote = { _, _ -> promotions++ })
        try { try { io.write(byteArrayOf(1)); fail() } catch (_: IOException) {}; try { io.write(byteArrayOf(2)); fail() } catch (_: IOException) {}; assertEquals(1, opens); assertEquals(0, promotions); assertEquals(1, held!!.closes); assertTrue(File(temporary.root, "session.json.pending").exists()); assertFalse(file.exists()) } finally { held?.releaseFixture() }
    }
    @Test fun oversizedReadClosesTheActualStreamOnceAndPermitsANextValidRead() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1,2,3)) }; var closes = 0
        val io = BrowserProfileJournal(file, openRead = { object : FileInputStream(it) { override fun close() { closes++; super.close() } } })
        try { io.read(2); fail() } catch (_: IOException) {}; assertArrayEquals(byteArrayOf(1,2,3), io.read(3)); assertEquals(2, closes)
    }
    @Test fun successfulFsyncAndClosePrecedeOneFixedStagePromotion() {
        val file = File(temporary.root, "session.json"); var closed = false; var promoted = false
        val io = BrowserProfileJournal(file, openWrite = { object : FileOutputStream(it) { override fun close() { super.close(); closed = true } } }, promote = { from, to -> assertTrue(closed); assertEquals("session.json.pending", from.name); check(from.renameTo(to)); promoted = true })
        io.write(byteArrayOf(1,2,3)); assertTrue(promoted); assertArrayEquals(byteArrayOf(1,2,3), io.read(3)); assertFalse(File(temporary.root,"session.json.pending").exists())
    }
}
