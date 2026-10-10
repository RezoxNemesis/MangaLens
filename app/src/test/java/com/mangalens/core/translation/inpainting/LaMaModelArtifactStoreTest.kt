package com.mangalens.core.translation.inpainting

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Authored UNRUN real private-file negatives. No downloaded weight or positive model pin claim. */
class LaMaModelArtifactStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun anAbsentPackCannotGrantInstalledStatus() { assertFalse(LaMaModelArtifactStore(folder.newFolder()).verifyInstalled {}) }
    @Test fun wrongSizeRefusesBeforeAllocatingTheLargeJavaWeightArray() {
        val store = LaMaModelArtifactStore(folder.newFolder()); store.checkPaths(true); store.model.writeBytes(byteArrayOf(1))
        var reserve = false
        assertThrows(LaMaModelPinMismatchException::class.java) { store.readVerifiedModel({}, { reserve = true }) }
        assertFalse(reserve)
    }
    @Test fun anIncompletePartialCannotReplaceThePreviousPrivateArtifact() {
        val store = LaMaModelArtifactStore(folder.newFolder()); store.checkPaths(true)
        store.model.writeBytes(byteArrayOf(7)); store.part.writeBytes(byteArrayOf(1))
        assertThrows(LaMaModelPinMismatchException::class.java) { store.commitDownloaded {} }
        assertArrayEquals(byteArrayOf(7), store.model.readBytes()); assertTrue(store.part.exists())
    }
    @Test fun symlinkCannotBeReadDeletedOrPromotedAsTheManagedPack() {
        val store = LaMaModelArtifactStore(folder.newFolder()); store.checkPaths(true); val external = folder.newFile().apply { writeBytes(byteArrayOf(4)) }
        Files.createSymbolicLink(store.model.toPath(), external.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.verifyInstalled {} }
        assertThrows(IllegalArgumentException::class.java) { store.removeInstalled {} }
        assertArrayEquals(byteArrayOf(4), external.readBytes())
    }
    @Test fun cancellationCannotGrantVerificationOrRemoveAHealthyExistingFile() {
        val store = LaMaModelArtifactStore(folder.newFolder()); store.checkPaths(true); store.model.writeBytes(byteArrayOf(7))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { store.removeInstalled { throw kotlinx.coroutines.CancellationException() } }
        assertArrayEquals(byteArrayOf(7), store.model.readBytes())
    }
    @Test fun explicitRemovalOwnsOnlyPinnedPackAndItsPartial() {
        val store = LaMaModelArtifactStore(folder.newFolder()); store.checkPaths(true)
        store.model.writeBytes(byteArrayOf(7)); store.part.writeBytes(byteArrayOf(1)); val neighbour = java.io.File(store.directory, "other-user-file").apply { writeText("keep") }
        store.removeInstalled {}; assertFalse(store.model.exists()); assertFalse(store.part.exists()); assertEquals("keep", neighbour.readText())
    }
}
