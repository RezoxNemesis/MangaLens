package com.mangalens.download

import java.io.Closeable
import java.io.IOException

internal class UnprovenPrivateFileCloseException(cause: Throwable) : IOException(
    "A private transfer file could not be safely closed. Its partial files were retained.", cause)

/** Reports actual close failure, independently of the body/network result. Exactly one close path. */
internal inline fun <T : Closeable, R> T.useOwnedPrivateFile(closeFailed: () -> Unit, block: (T) -> R): R {
    var failure: Throwable? = null
    try { return block(this) }
    catch (problem: Throwable) { failure = problem; throw problem }
    finally {
        try { close() }
        catch (problem: Throwable) {
            closeFailed()
            val unproven = UnprovenPrivateFileCloseException(problem)
            if (failure == null) throw unproven else failure.addSuppressed(unproven)
        }
    }
}

/** A failed buffer/reader allocation cannot abandon an already opened private file descriptor. */
internal inline fun <T : Closeable, W : Closeable> T.wrapOwnedPrivateFile(closeFailed: () -> Unit, wrap: (T) -> W): W =
    try { wrap(this) } catch (problem: Throwable) { useOwnedPrivateFile(closeFailed) { throw problem } }

internal fun Throwable.hasUnprovenPrivateClose(): Boolean = this is UnprovenPrivateFileCloseException ||
    suppressed.any { it is UnprovenPrivateFileCloseException }
