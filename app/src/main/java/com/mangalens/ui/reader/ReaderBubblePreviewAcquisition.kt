package com.mangalens.ui.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A cancelled dispatch may discard a decoded preview before its dialog can receive ownership. */
internal suspend fun <T : AutoCloseable> acquireReaderBubblePreviewOnIo(open: suspend () -> T?): T? {
    var acquired: T? = null
    var handedOff = false
    try {
        val result = withContext(Dispatchers.IO) { open().also { acquired = it } }
        handedOff = true
        return result
    } finally {
        if (!handedOff) acquired?.close()
    }
}
