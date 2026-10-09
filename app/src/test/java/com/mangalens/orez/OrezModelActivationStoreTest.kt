package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OrezModelActivationStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun activationUsesImmutablePathsAndKeepsPreviousWorkingFile() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        val oldBytes = old.readBytes()
        val first = store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        store.markWorking(first.file)
        val part = GgufFixture.write(folder.root, "new.part", marker = 43)
        val second = store.activate("LITE", part, GgufFixture.descriptor(part))
        assertEquals(second.file, store.runtimeFiles("LITE").first())
        assertNotEquals(old.canonicalPath, second.file.canonicalPath)
        assertArrayEquals(oldBytes, old.readBytes())
        assertTrue(second.file.name.contains(second.sha256))
        assertTrue(store.canRollback("LITE"))
        assertTrue(store.rollback("LITE"))
        assertEquals("Rollback must not automatically reuse the rejected replacement", listOf(old), store.runtimeFiles("LITE"))
        assertTrue("Rejected candidate must remain available for review", second.file.exists())
    }

    @Test fun integrityOrCompatibilityFailureLeavesCurrentActivationUntouched() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val part = GgufFixture.write(folder.root, "bad.part", marker = 31)
        val descriptor = GgufFixture.descriptor(part).copy(sha256 = "0".repeat(64))
        expectFailure { store.activate("LITE", part, descriptor) }
        assertEquals(listOf(old), store.runtimeFiles("LITE"))
        assertTrue(part.exists())
        val wrong = GgufFixture.write(folder.root, "foreign.part", architecture = "llama")
        expectFailure { store.activate("LITE", wrong, GgufFixture.descriptor(wrong)) }
        assertEquals(listOf(old), store.runtimeFiles("LITE"))
    }

    @Test fun interruptedCommitKeepsOldManifestAndAnUnadvertisedCandidate() {
        var rejectCommit = false
        val store = OrezModelActivationStore(folder.root, beforeManifestCommit = {
            if (rejectCommit) throw IOException("Simulated full storage")
        })
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val part = GgufFixture.write(folder.root, "new.part", marker = 51)
        rejectCommit = true
        expectFailure { store.activate("LITE", part, GgufFixture.descriptor(part)) }
        assertEquals(listOf(old), store.runtimeFiles("LITE"))
        assertArrayEquals(GgufFixture.write(folder.root, "expected.gguf").readBytes(), old.readBytes())
        assertTrue(File(folder.root, "verified").walk().any { it.isFile })
    }

    @Test fun freshProcessDoesNotTrustAReceiptWithoutRevalidatingItsArtifact() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val restarted = OrezModelActivationStore(folder.root)
        assertTrue(restarted.runtimeFiles("LITE").isEmpty())
        assertTrue(restarted.revalidate().isEmpty())
        assertEquals(listOf(old), restarted.runtimeFiles("LITE"))
        val timestamp = old.lastModified()
        old.writeBytes(ByteArray(old.length().toInt()) { 0x41 })
        old.setLastModified(timestamp)
        val corruptedRestart = OrezModelActivationStore(folder.root)
        assertFalse(corruptedRestart.revalidate().isEmpty())
        assertTrue(corruptedRestart.runtimeFiles("LITE").isEmpty())
    }

    @Test fun damagedActiveRestoresPreviouslyVerifiedArtifactAfterRestart() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        store.markWorking(old)
        val part = GgufFixture.write(folder.root, "new.part", marker = 61)
        val current = store.activate("LITE", part, GgufFixture.descriptor(part))
        current.file.writeBytes(ByteArray(current.file.length().toInt()))
        val restarted = OrezModelActivationStore(folder.root)
        assertFalse(restarted.revalidate().isEmpty())
        assertEquals(old, restarted.runtimeFiles("LITE").first())
        assertTrue(current.file.exists())
    }

    @Test fun cancellationBeforePromotionKeepsPartialAndWorkingModel() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val part = GgufFixture.write(folder.root, "new.part", marker = 91)
        var checks = 0
        try {
            store.activate("LITE", part, GgufFixture.descriptor(part)) {
                if (++checks >= 2) throw kotlinx.coroutines.CancellationException("Paused")
            }
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(part.exists())
        assertEquals(listOf(old), store.runtimeFiles("LITE"))
    }

    @Test fun rollbackWillNotActivateCorruptedPreviousModel() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val part = GgufFixture.write(folder.root, "new.part", marker = 111)
        val current = store.activate("LITE", part, GgufFixture.descriptor(part))
        old.writeText("corrupted")
        assertFalse(store.rollback("LITE"))
        assertEquals(current.file, store.runtimeFiles("LITE").first())
        assertTrue(old.exists())
    }

    @Test fun interruptedActivationResumesVerifiedBlobWithoutAnotherDownload() {
        val part = GgufFixture.write(folder.root, "new.part", marker = 39)
        val descriptor = GgufFixture.descriptor(part)
        val brokenCommit = OrezModelActivationStore(folder.root, beforeManifestCommit = { throw IOException("Full storage") })
        expectFailure { brokenCommit.activate("LITE", part, descriptor) }
        assertFalse(part.exists())
        val restarted = OrezModelActivationStore(folder.root)
        val saved = restarted.savedCandidate("LITE", descriptor)
        assertNotNull("Already downloaded verified bytes should be reusable", saved)
        val active = restarted.activate("LITE", saved!!, descriptor)
        assertEquals(saved, active.file)
        assertEquals(listOf(active.file), restarted.runtimeFiles("LITE"))
    }

    @Test fun verifyingReplacementDoesNotBlockReadingTheCurrentModel() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val part = GgufFixture.write(folder.root, "new.part", marker = 82)
        val verificationEntered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val activation = executor.submit {
                store.activate("LITE", part, GgufFixture.descriptor(part)) {
                    verificationEntered.countDown()
                    assertTrue(resume.await(3, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(verificationEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Working model must stay usable while verification waits", listOf(old), store.runtimeFiles("LITE"))
            resume.countDown()
            activation.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun untrustedActivationPathCannotEscapePrivateModelDirectory() {
        val store = OrezModelActivationStore(folder.root)
        val old = GgufFixture.write(folder.root, "old.gguf")
        store.adoptExisting("LITE", old, GgufFixture.descriptor(old))
        val journal = File(folder.root, "LITE.activation")
        journal.writeText(journal.readText().replace("active.path=old.gguf", "active.path=../outside.gguf"))
        assertTrue(OrezModelActivationStore(folder.root).runtimeFiles("LITE").isEmpty())
    }

    @Test fun malformedReceiptReportsRecoveryInsteadOfCrashingStartup() {
        File(folder.root, "LITE.activation").writeText("schema=1\nverifier=1\nactive.path=\\uXXXX")
        val restarted = OrezModelActivationStore(folder.root)
        assertTrue(restarted.runtimeFiles("LITE").isEmpty())
        assertFalse("Recovery needs an actionable error", restarted.revalidate().isEmpty())
    }

    @Test fun mutatedLegacyWeightsBeforeFirstAdoptionCannotCreateTrustedReceipt() {
        val file = GgufFixture.write(folder.root, "legacy.gguf", fileType = 18)
        val expectedBytes = file.length()
        val original = file.readBytes()
        val trustedFixturePin = GgufFixture.descriptor(file, OrezModelCatalog.legacy.id)
        val altered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(altered)
        assertEquals(expectedBytes, file.length())
        // The GGUF structure remains valid; a trusted publisher pin must catch the weight mutation.
        OrezModelCompatibility.inspect(file, OrezModelRequirements(fileTypes = setOf(18)))
        val store = OrezModelActivationStore(folder.root, legacyPin = trustedFixturePin)
        try { store.adoptLegacy(file, expectedBytes); fail("Mutated weights must fail the trusted pin") }
        catch (failure: OrezModelCompatibilityException) { assertTrue(failure.message, failure.message!!.contains("integrity")) }
        assertTrue(store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isEmpty())
        assertFalse(File(folder.root, "LEGACY.activation").exists())
        assertArrayEquals(altered, file.readBytes())
    }

    @Test fun locallyComputedLegacyReceiptCannotGrandfatherUntrustedWeights() {
        val file = GgufFixture.write(folder.root, "legacy.gguf", fileType = 18)
        val trustedFixturePin = GgufFixture.descriptor(file, OrezModelCatalog.legacy.id)
        val altered = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(altered)
        val selfHash = GgufFixture.sha256(file)
        // Simulate the old first-observation receipt without trusting it as publisher provenance.
        File(folder.root, "LEGACY.activation").writeText("""
            schema=1
            verifier=1
            catalog_manifest=1
            active.id=legacy-qwen2.5-0.5b-q6_k
            active.path=legacy.gguf
            active.bytes=${file.length()}
            active.sha256=$selfHash
        """.trimIndent())
        val restarted = OrezModelActivationStore(folder.root, legacyPin = trustedFixturePin)
        assertFalse(restarted.revalidate().isEmpty())
        assertTrue(restarted.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isEmpty())
        assertArrayEquals(altered, file.readBytes())
        assertTrue(File(folder.root, "LEGACY.activation").exists())
    }

    @Test fun retryOfActiveUuidArtifactNeverMovesWorkingFileBeforeCommit() {
        val file = GgufFixture.write(folder.root, "new.part", marker = 36)
        val descriptor = GgufFixture.descriptor(file)
        val collision = File(folder.root, "verified/LITE/${descriptor.sha256}.gguf")
        collision.parentFile!!.mkdirs()
        collision.writeText("Unverified canonical collision")
        var rejectCommit = false
        val store = OrezModelActivationStore(folder.root, beforeManifestCommit = {
            if (rejectCommit) throw IOException("Activation interrupted")
        })
        val active = store.activate("LITE", file, descriptor)
        assertNotEquals(collision, active.file)
        store.markWorking(active.file)
        val workingBytes = active.file.readBytes()
        rejectCommit = true
        val saved = store.savedCandidate("LITE", descriptor)!!
        try { store.activate("LITE", saved, descriptor) } catch (_: IOException) { }
        assertTrue("Active UUID artifact must retain its mmap path", active.file.exists())
        assertArrayEquals(workingBytes, active.file.readBytes())
        assertEquals(listOf(active.file), store.runtimeFiles("LITE"))
        assertTrue(collision.exists())
    }

    @Test fun interruptedManifestRefreshKeepsReferencedUuidArtifactInPlace() {
        val file = GgufFixture.write(folder.root, "new.part", marker = 48)
        val descriptor = GgufFixture.descriptor(file)
        val collision = File(folder.root, "verified/LITE/${descriptor.sha256}.gguf")
        collision.parentFile!!.mkdirs()
        collision.writeText("Unverified canonical collision")
        var rejectCommit = false
        val store = OrezModelActivationStore(folder.root, beforeManifestCommit = {
            if (rejectCommit) throw IOException("Activation interrupted")
        })
        val active = store.activate("LITE", file, descriptor)
        store.markWorking(active.file)
        val previousJournal = File(folder.root, "LITE.activation").readBytes()
        rejectCommit = true
        expectFailure { store.activate("LITE", active.file, descriptor.copy(id = descriptor.id + "-metadata-refresh")) }
        assertTrue(active.file.exists())
        assertEquals(listOf(active.file), store.runtimeFiles("LITE"))
        assertArrayEquals(previousJournal, File(folder.root, "LITE.activation").readBytes())
    }

    @Test fun authenticLegacyFixtureCanBeRevalidatedWithoutTrustingAReceiptAlone() {
        val file = GgufFixture.write(folder.root, "legacy.gguf", fileType = 18)
        val trustedFixturePin = GgufFixture.descriptor(file, OrezModelCatalog.legacy.id)
        val original = file.readBytes()
        val store = OrezModelActivationStore(folder.root, legacyPin = trustedFixturePin)
        store.adoptLegacy(file, trustedFixturePin.bytes)
        assertEquals(listOf(file), store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT))
        val restarted = OrezModelActivationStore(folder.root, legacyPin = trustedFixturePin)
        assertTrue(restarted.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isEmpty())
        assertTrue(restarted.revalidate().isEmpty())
        assertEquals(listOf(file), restarted.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT))
        assertArrayEquals(original, file.readBytes())
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected verification or commit failure") }
        catch (_: IOException) { }
        catch (_: OrezModelCompatibilityException) { }
    }
}
