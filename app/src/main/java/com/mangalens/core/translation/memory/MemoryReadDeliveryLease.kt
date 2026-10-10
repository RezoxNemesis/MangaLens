package com.mangalens.core.translation.memory

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock

/** Prepared on IO; Main never decodes a journal or waits for a held fsync/mutation monitor. */
internal class MemoryReadDeliveryLease(private val gate: ReentrantLock, private val stamps: List<MemoryReadDeliveryStamp>) {
    fun <T> tryCommit(commit: () -> T): T? {
        if (!gate.tryLock()) return null
        return try { if (stamps.all { it.isCurrent() }) commit() else null }
        finally { gate.unlock() }
    }
}

internal data class MemoryReadDeliveryStamp(val file: File, val canonical: String, val fileKey: Any?, val size: Long?, val modified: java.nio.file.attribute.FileTime?) {
    fun isCurrent(): Boolean = try {
        val attributes = Files.readAttributes(java.nio.file.Paths.get(canonical), BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        attributes.isRegularFile && attributes.fileKey() == fileKey && attributes.size() == size && attributes.lastModifiedTime() == modified
    } catch (_: java.nio.file.NoSuchFileException) { size == null }
    catch (_: Exception) { false }
    companion object {
        fun capture(file: File): MemoryReadDeliveryStamp {
            val canonical = file.canonicalPath
            if (!file.exists()) return MemoryReadDeliveryStamp(file, canonical, null, null, null)
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            require(attributes.isRegularFile)
            return MemoryReadDeliveryStamp(file, canonical, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
        }
    }
}
