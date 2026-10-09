package com.mangalens.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class BundledExtractorInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bundled = "trusted APK extractor 2026.08.19".toByteArray()
    private val old = "cached extractor 2025.11.12".toByteArray()
    private val installer get() = BundledExtractorInstaller(bundled.size.toLong(), sha256(bundled))

    @Test fun appUpgradeReplacesAnOlderCachedExtractor() {
        val target = cached(old)

        assertTrue(installer.install(target, { ByteArrayInputStream(bundled) }))

        assertArrayEquals(bundled, target.readBytes())
    }

    @Test fun activationPreservesTheOldInodeForAnOpenReader() {
        val target = cached(old)
        FileInputStream(target).use { activeOldReader ->
            installer.install(target, { ByteArrayInputStream(bundled) })

            assertArrayEquals("An active reader must retain its old complete archive", old, activeOldReader.readBytes())
            assertArrayEquals("New readers must open the verified bundled archive", bundled, target.readBytes())
        }
    }

    @Test fun anAlreadyVerifiedCopyDoesNotReopenTheApkResource() {
        val target = cached(bundled)

        assertFalse(installer.install(target, { error("Verified installed copy should be reused") }))

        assertArrayEquals(bundled, target.readBytes())
    }

    @Test fun corruptBundledBytesLeaveTheInstalledExtractorIntact() {
        val target = cached(old)
        val corrupted = bundled.copyOf().apply { this[0] = '!'.code.toByte() }

        assertThrows(IOException::class.java) {
            installer.install(target, { ByteArrayInputStream(corrupted) })
        }

        assertArrayEquals(old, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test fun aTruncatedBundleLeavesTheInstalledExtractorIntact() {
        val target = cached(old)

        assertThrows(IOException::class.java) {
            installer.install(target, { ByteArrayInputStream(bundled.copyOf(bundled.size - 1)) })
        }

        assertArrayEquals(old, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test fun anOversizedBundleIsBoundedBeforePublishing() {
        val target = cached(old)
        var bytesRead = 0
        val oversized = object : ByteArrayInputStream(bundled + ByteArray(50_000)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
        }

        assertThrows(IOException::class.java) { installer.install(target, { oversized }) }

        assertTrue("Reject after at most the pinned length plus one byte", bytesRead <= bundled.size + 1)
        assertArrayEquals(old, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test fun interruptedBundleReadDoesNotReplaceTheInstalledCopy() {
        val target = cached(old)
        val unreadable = object : ByteArrayInputStream(bundled) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("Read failed")
        }

        assertThrows(IOException::class.java) { installer.install(target, { unreadable }) }

        assertArrayEquals(old, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test fun cancellationWhileReadingTheBundleDoesNotPublishIt() {
        val target = cached(old)
        val cancelled = AtomicBoolean(false)
        val source = object : ByteArrayInputStream(bundled) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { cancelled.set(true) }
        }

        assertThrows(CancellationException::class.java) {
            installer.install(target, { source }, { if (cancelled.get()) throw CancellationException("Cancelled") })
        }

        assertArrayEquals(old, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test fun sameSizeCorruptedInstalledBytesAreReplaced() {
        val target = cached(bundled.copyOf().apply { this[0] = '!'.code.toByte() })

        assertTrue(installer.install(target, { ByteArrayInputStream(bundled) }))

        assertArrayEquals(bundled, target.readBytes())
    }

    @Test fun aFirstInstallCreatesTheLibrarysPrivateParentDirectories() {
        val target = File(temporary.root, "private/youtubedl-android/yt-dlp/yt-dlp")

        assertTrue(installer.install(target, { ByteArrayInputStream(bundled) }))

        assertArrayEquals(bundled, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    private fun assertNoTemporaryFiles(target: File) {
        assertEquals(setOf("yt-dlp"), target.parentFile!!.listFiles()!!.map { it.name }.toSet())
    }

    private fun cached(bytes: ByteArray): File = File(temporary.newFolder(), "yt-dlp").apply { writeBytes(bytes) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
