package com.mangalens.core.reader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedTransferTest {
    @Test
    fun copiesPayloadAtLimit() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val copied = BoundedTransfer.copy(ByteArrayInputStream(payload), output, maxBytes = payload.size.toLong())

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun rejectsPayloadBeyondLimitBeforeWritingPastLimit() {
        val payload = ByteArray(1025) { 7 }
        val output = ByteArrayOutputStream()

        assertThrows(IllegalStateException::class.java) {
            BoundedTransfer.copy(ByteArrayInputStream(payload), output, maxBytes = 1024, bufferSize = 256)
        }

        assertEquals(1024, output.size())
    }

    @Test
    fun reportsExactCopiedByteCount() {
        val payload = "chapter-page".repeat(37).toByteArray()
        val output = ByteArrayOutputStream()

        val copied = BoundedTransfer.copy(ByteArrayInputStream(payload), output, maxBytes = 4096, bufferSize = 17)

        assertEquals(payload.size.toLong(), copied)
    }
}
