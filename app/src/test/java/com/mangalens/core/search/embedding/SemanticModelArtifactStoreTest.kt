package com.mangalens.core.search.embedding

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** These negative pin controls never download or qualify the optional 90.4 MB artifact. */
class SemanticModelArtifactStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun absentModelIsNotReady() { assertFalse(SemanticModelArtifactStore(folder.newFolder()).verifyInstalled {}) }
    @Test fun truncatedFileFailsBeforeNativeWeightAllocation() {
        val store = SemanticModelArtifactStore(folder.newFolder()); store.checkPaths(true); store.model.writeBytes(byteArrayOf(1))
        var reserved = false
        assertThrows(SemanticModelPinMismatchException::class.java) { store.readVerifiedModel({}, { reserved = true }) }
        assertFalse(reserved)
    }
    @Test fun canceledVerificationCannotGrantReadiness() {
        val store = SemanticModelArtifactStore(folder.newFolder())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { store.verifyInstalled { throw kotlinx.coroutines.CancellationException() } }
    }
    @Test fun incompletePartialCannotReplacePreviousFile() {
        val store = SemanticModelArtifactStore(folder.newFolder()); store.checkPaths(true)
        store.model.writeBytes(byteArrayOf(7)); store.part.writeBytes(byteArrayOf(1))
        assertThrows(SemanticModelPinMismatchException::class.java) { store.commitDownloaded {} }
        assertArrayEquals(byteArrayOf(7), store.model.readBytes()); assertTrue(store.part.exists())
    }
    @Test fun actualWholeDigestMatchesStandardSha256Oracle() { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SemanticModelArtifactStore.sha256("abc".toByteArray())) }
}
