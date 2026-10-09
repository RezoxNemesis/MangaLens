package com.mangalens.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException

class NativeSystemTrustBundleStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val expected = "OS-only CA bundle".toByteArray()

    @Test fun replacingTheBundleDoesNotTruncateAnExistingReader() {
        val old = "old complete CA bundle".toByteArray()
        val target = cached(old)
        FileInputStream(target).use { oldReader ->
            assertTrue(NativeSystemTrustBundleStore.install(target, expected))
            assertArrayEquals(old, oldReader.readBytes())
            assertArrayEquals(expected, target.readBytes())
        }
    }

    @Test fun identicalRootsReuseTheInstalledBundle() {
        val target = cached(expected)
        assertFalse(NativeSystemTrustBundleStore.install(target, expected))
        assertArrayEquals(expected, target.readBytes())
    }

    @Test fun cancellationBeforePublicationPreservesTheOldTrustFile() {
        val old = "old trusted CA bundle".toByteArray()
        val target = cached(old)
        assertThrows(CancellationException::class.java) {
            NativeSystemTrustBundleStore.install(target, expected) {
                if (target.parentFile!!.listFiles()!!.any { it != target }) {
                    throw CancellationException("Cancelled while a replacement was staged")
                }
            }
        }
        assertArrayEquals(old, target.readBytes())
        assertTrue(target.parentFile!!.listFiles()!!.all { it.name == "cert.pem" })
    }

    private fun cached(bytes: ByteArray) = File(temporary.newFolder(), "cert.pem").apply { writeBytes(bytes) }
}
