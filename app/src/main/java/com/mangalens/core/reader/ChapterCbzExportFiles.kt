package com.mangalens.core.reader

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.withTimeout

/** One app-owned operation stays admitted until real source/provider work and cleanup settle. */
internal object ChapterCbzExportFiles {
    private val producer = OwnedSavedVideoProbe()
    private const val DEADLINE_MS = 300_000L
    suspend fun export(context: Context, uri: Uri, captured: ChapterCbzScope, operation: ChapterCbzOperation): ChapterCbzReceipt {
        require(uri.scheme == "content") { "Select a document provider for CBZ export." }
        val app = context.applicationContext
        val cancellation = CancellationSignal()
        return withTimeout(DEADLINE_MS) {
            producer.run(cancelProvider = cancellation::cancel) { owner ->
                val resources = ChapterCbzResources()
                try {
                    owner.own(resources)
                    resources.beginPrivateWork()
                    val library = ChapterLibrary(app)
                    val archive = ChapterCbzArchive(File(app.filesDir, "chapter_exports"), ChapterCbzOriginalSource(File(app.filesDir, "chapters"), resources), resources) {
                        capturedScope -> capturedScope.matches(library.find(capturedScope.chapterId))
                    }
                    val artifact = archive.prepare(captured, operation, owner::checkActive)
                    try {
                        owner.checkActive()
                        val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "wt", cancellation)
                            ?: throw IOException("The selected destination could not be opened.")
                        val handle = ChapterCbzDescriptorHandle(descriptor)
                        resources.ownProvider(handle) // Track even a descriptor returned after retirement.
                        // Provider opening can be delayed; stale originals must not enter the actual write.
                        archive.recheck(captured, artifact, owner::checkActive)
                        owner.checkActive()
                        val output = handle.output(owner::checkActive)
                        operation.update(ChapterCbzStage.WRITING, captured.pages.size, captured.pages.size)
                        chapterCbzCopyArchive(artifact, output, resources, owner::checkActive)
                        output.flush()
                        operation.update(ChapterCbzStage.FINISHING, captured.pages.size, captured.pages.size)
                        resources.closeProvider() // Success requires the real close, never only a close request.
                        archive.recheck(captured, artifact, owner::checkActive)
                        resources.finishPrivateWork()
                        owner.closeReadHandle() // The registered composite proves every actual close.
                        owner.checkActive()
                        operation.update(ChapterCbzStage.COMPLETE, captured.pages.size, captured.pages.size)
                        artifact.receipt
                    } finally { if (resources.privateReleaseProven()) artifact.file.delete() }
                } finally { resources.finishPrivateWork() } // Failed private handles remain retained by the registered composite.
            }
        }
    }
}

/** No provider readback claim: bind exactly the prepared bytes streamed into the chosen output. */
internal fun chapterCbzCopyArchive(artifact: ChapterCbzArtifact, output: OutputStream, resources: ChapterCbzResources = ChapterCbzResources(), check: () -> Unit) {
    resources.usePrivate(FileInputStream(artifact.file)) { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(ChapterCbzPolicy.COPY_BUFFER_BYTES); var total = 0L
        while (true) { check(); val size = input.read(buffer); if (size < 0) break; total += size
            if (total > artifact.receipt.archiveBytes || total > ChapterCbzPolicy.MAX_ARCHIVE_BYTES) throw IOException("The private export changed.")
            digest.update(buffer, 0, size); output.write(buffer, 0, size) }
        if (total != artifact.receipt.archiveBytes || digest.digest().cbzHex() != artifact.receipt.archiveSha256) throw IOException("The private export changed.")
        check()
    }
}

/** A descriptor or its successfully created stream is the single actual close target. */
private class ChapterCbzDescriptorHandle(private val descriptor: AssetFileDescriptor) : AutoCloseable {
    private val guard = java.lang.Object()
    private var creating = false
    private var started = false
    private var closing = false
    private var stream: Closeable? = null
    fun output(check: () -> Unit): OutputStream {
        check()
        synchronized(guard) {
            if (closing || started) throw IOException("CBZ export was retired.")
            started = true; creating = true
        }
        val value = try { descriptor.createOutputStream() }
        catch (failure: Throwable) { synchronized(guard) { creating = false; guard.notifyAll() }; throw failure }
        synchronized(guard) { stream = value; creating = false; guard.notifyAll() }
        check(); return value
    }
    override fun close() {
        val target = synchronized(guard) {
            if (closing) return
            closing = true
            while (creating) guard.wait()
            stream ?: descriptor
        }
        target.close()
    }
}
