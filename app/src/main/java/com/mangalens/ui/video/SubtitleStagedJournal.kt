package com.mangalens.ui.video

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Prepared bytes are durable before authority is locked. Closing an uncommitted stage removes it. */
internal interface SubtitleStagedJournal : AutoCloseable {
    fun commit()
}

/**
 * This helper is called on the worker/command IO lane. The returned commit performs metadata
 * operations only: no serialization, body write or fsync occurs under browser authority.
 * There is no non-atomic rename fallback. AtomicFile can continue reading the same base file.
 */
internal fun prepareSubtitleJournal(file: File, bytes: ByteArray): SubtitleStagedJournal {
    val parent = requireNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs())
    val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.stage")
    try {
        FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
    } catch (failure: Throwable) {
        temporary.delete()
        throw failure
    }
    return object : SubtitleStagedJournal {
        private var committed = false
        override fun commit() {
            check(!committed)
            // Store serializes all journal writes. Remove prior interrupted AtomicFile artifacts
            // so openRead cannot restore an obsolete .new/.bak after this accepted base rename.
            listOf(File(file.path + ".new"), File(file.path + ".bak")).forEach {
                check(!it.exists() || it.delete()) { "Previous subtitle journal recovery could not finish." }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            committed = true
        }
        override fun close() { if (!committed) temporary.delete() }
    }
}
