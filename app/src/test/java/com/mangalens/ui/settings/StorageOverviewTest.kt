package com.mangalens.ui.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageOverviewTest {
    @Test fun inventorySeparatesDurableCategoriesAndDoesNotReadOrModifyTheirBytes() {
        val root = Files.createTempDirectory("owned-storage").toFile()
        try {
            val manga = File(root, "manga").apply { mkdir() }
            val media = File(root, "video").apply { mkdir() }
            val model = File(root, "models").apply { mkdir() }
            val cache = File(root, "cache").apply { mkdir() }
            File(manga, "page.img").writeBytes(ByteArray(11) { 4 })
            File(media, "saved.mp4").writeBytes(ByteArray(13) { 5 })
            File(model, "model.gguf").writeBytes(ByteArray(17) { 6 })
            File(cache, "working.pdf").writeBytes(ByteArray(19) { 7 })
            val result = ManagedStorageInventory.scan(listOf(
                ManagedStorageRoot(manga, ManagedStorageCategory.MANGA), ManagedStorageRoot(media, ManagedStorageCategory.VIDEO),
                ManagedStorageRoot(model, ManagedStorageCategory.AI_MODELS), ManagedStorageRoot(cache, ManagedStorageCategory.CACHE)))
            assertEquals(11L, result.bytes[ManagedStorageCategory.MANGA])
            assertEquals(13L, result.bytes[ManagedStorageCategory.VIDEO])
            assertEquals(17L, result.bytes[ManagedStorageCategory.AI_MODELS])
            assertEquals(19L, result.bytes[ManagedStorageCategory.CACHE])
            assertEquals(60L, result.totalBytes)
            assertArrayEquals(ByteArray(19) { 7 }, File(cache, "working.pdf").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun overlappingOwnedRootsAreCountedOnce() {
        val root = Files.createTempDirectory("storage-overlap").toFile()
        try {
            File(root, "nested").mkdir()
            File(root, "nested/file").writeBytes(ByteArray(10))
            val result = ManagedStorageInventory.scan(listOf(ManagedStorageRoot(root, ManagedStorageCategory.VIDEO),
                ManagedStorageRoot(File(root, "nested"), ManagedStorageCategory.OTHER)))
            assertEquals(10L, result.totalBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun linkedExternalContentIsRefusedInsteadOfCountedAsOwnedOrDeleted() {
        val root = Files.createTempDirectory("storage-link-root").toFile()
        val external = Files.createTempDirectory("storage-link-external").toFile()
        try {
            File(external, "valuable").writeText("preserve")
            Files.createSymbolicLink(File(root, "linked").toPath(), external.toPath())
            assertThrows(java.io.IOException::class.java) {
                ManagedStorageInventory.scan(listOf(ManagedStorageRoot(root, ManagedStorageCategory.CACHE)))
            }
            assertEquals("preserve", File(external, "valuable").readText())
        } finally { File(root, "linked").delete(); root.deleteRecursively(); external.deleteRecursively() }
    }

    @Test fun imageCleanupDelegatesOnlyToOwnersAndLeavesActiveWorkAndSavedContent() {
        val root = Files.createTempDirectory("cache-owner-only").toFile()
        try {
            val protected = listOf("chapter_imports", "chapter_repairs", "yt_dlp_session", "media_download_cache", "chapters", "speech")
                .map { File(root, "$it/owned").apply { parentFile!!.mkdir(); writeText("retained") } }
            val calls = mutableListOf<String>()
            assertTrue(clearOwnedImageCache({ calls += "disk-owner" }, { calls += "memory-owner" }).contains("retained"))
            assertEquals(listOf("disk-owner", "memory-owner"), calls)
            protected.forEach { assertEquals("retained", it.readText()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun anOwnerFailureCannotBeReportedAsSuccessfulCacheCleanup() {
        var memoryCleared = false
        assertThrows(java.io.IOException::class.java) {
            clearOwnedImageCache({ throw java.io.IOException("controlled owner failure") }, { memoryCleared = true })
        }
        assertFalse(memoryCleared)
    }
}
