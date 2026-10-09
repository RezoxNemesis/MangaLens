package com.mangalens.ui.video

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CaptionFileStoreTest {
    private val captions = ImportedCaptionFile.parse("1\n00:00:00,250 --> 00:00:02,000\nHello, MangaLens.\n")

    @Test fun realFileColdReopenRetainsCanonicalDialogueTimingAndProof() {
        val directory = Files.createTempDirectory("caption-store").toFile()
        try {
            val proof = CaptionFileStore(directory).publish(captions)
            assertTrue(CaptionFileStore(directory).verify(proof))
            assertEquals(captions, proof.file.inputStream().use(ImportedCaptionFile::read))
            assertEquals(proof, CaptionFileStore(directory).publish(captions))
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptionSizeAndOutsideAliasesCannotSupplyAcceptedCaptionBytes() {
        val directory = Files.createTempDirectory("caption-store").toFile()
        val outside = Files.createTempDirectory("caption-outside").toFile()
        try {
            val store = CaptionFileStore(directory)
            val proof = store.publish(captions)
            assertFalse(store.verify(proof.copy(bytes = proof.bytes + 1)))
            val other = File(outside, proof.file.name)
            other.writeBytes(proof.file.readBytes())
            assertFalse(store.verify(proof.copy(file = other)))
            val corrupt = proof.file.readText().replace("Hello", "Jello")
            proof.file.writeText(corrupt)
            assertFalse(store.verify(proof))
            val repaired = store.publish(captions)
            assertTrue(store.verify(repaired))
            assertEquals(captions.srt, repaired.file.readText())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".pending") })
        } finally { directory.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun symlinkRetargetToOutsideRootIsRejectedEvenForMatchingSourceBytes() {
        val directory = Files.createTempDirectory("caption-store").toFile()
        val outside = Files.createTempDirectory("caption-outside").toFile()
        try {
            val store = CaptionFileStore(directory)
            val proof = store.publish(captions)
            val other = File(outside, "original.srt")
            other.writeBytes(proof.file.readBytes())
            assertTrue(proof.file.delete())
            Files.createSymbolicLink(proof.file.toPath(), other.toPath())
            assertFalse(store.verify(proof))
        } finally { directory.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun actualReturnedBytesRetainTheirProofAcrossColdReopen() {
        val directory = Files.createTempDirectory("caption-read").toFile()
        try {
            val proof = CaptionFileStore(directory).publish(captions)
            val bytes = CaptionFileStore(directory).readVerified(proof)
            assertArrayEquals(captions.srt.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(proof.sha256, java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { directory.deleteRecursively() }
    }

    @Test fun sameLengthMutationAfterEarlierVerificationCannotReachTranslationOrExport() {
        val directory = Files.createTempDirectory("caption-reread").toFile()
        try {
            val store = CaptionFileStore(directory)
            val proof = store.publish(captions)
            assertTrue(store.verify(proof))
            proof.file.writeText(captions.srt.replace("Hello", "Jello"))
            assertEquals(proof.bytes, proof.file.length())
            assertThrows(IllegalArgumentException::class.java) { store.readVerified(proof) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun growthOrOversizeProofCannotTriggerAnUnboundedReturnedAllocation() {
        val directory = Files.createTempDirectory("caption-growth").toFile()
        try {
            val store = CaptionFileStore(directory)
            val proof = store.publish(captions)
            proof.file.appendText("extra")
            assertThrows(IllegalArgumentException::class.java) { store.readVerified(proof) }
            assertThrows(IllegalArgumentException::class.java) {
                store.readVerified(proof.copy(bytes = ImportedCaptionFile.MAX_BYTES.toLong() + 1))
            }
        } finally { directory.deleteRecursively() }
    }
}
