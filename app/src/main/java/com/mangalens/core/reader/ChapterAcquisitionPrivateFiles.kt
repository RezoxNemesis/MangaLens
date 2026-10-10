package com.mangalens.core.reader

import java.io.File

/** Optional native acquisition ownership. Ordinary Reader construction keeps its existing path. */
interface ChapterAcquisitionPrivateFiles {
    fun <T : AutoCloseable, R> usePrivate(value: T, action: (T) -> R): R
    fun privateReleaseProven(): Boolean
    fun openOutput(file: File, append: Boolean = false): java.io.FileOutputStream
    /** Full held-descriptor hash plus bounded raster verification; every actual FD close is owned. */
    fun verifiedRevision(file: File, checkActive: () -> Unit): String
}
