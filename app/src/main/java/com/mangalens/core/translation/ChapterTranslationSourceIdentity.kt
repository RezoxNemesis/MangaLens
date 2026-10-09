package com.mangalens.core.translation

import java.io.File
import java.io.IOException

/** File identity only; the Store keeps source checksum, generation and configuration validation. */
internal object ChapterTranslationSourceIdentity {
    fun matches(taskSource: String?, readerSource: String?, managedDirectory: File): Boolean {
        if (taskSource.isNullOrBlank() || readerSource.isNullOrBlank()) return false
        val expected = File(taskSource)
        val visible = File(readerSource)
        if (!expected.isAbsolute || !visible.isAbsolute) return false
        return try {
            val directory = managedDirectory.canonicalFile
            val storedSource = expected.canonicalFile
            val readerFile = visible.canonicalFile
            storedSource.parentFile == directory && readerFile.parentFile == directory &&
                storedSource.isFile && readerFile.isFile && storedSource == readerFile
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
