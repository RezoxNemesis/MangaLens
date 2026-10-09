package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ImportedCaptionStore(context: Context) {
    private val context = context.applicationContext
    private val store = CaptionFileStore(File(this.context.filesDir, "captions/imported"))

    suspend fun read(uri: Uri): ImportedCaptionFile = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("content", "file")) { "Select a subtitle file from your device." }
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to read subtitle file." }
        input.use(ImportedCaptionFile::read)
    }

    suspend fun publish(captions: ImportedCaptionFile, language: String, label: String, original: PlayerCaptionTrack? = null): PlayerCaptionTrack = withContext(Dispatchers.IO) {
        val normalized = language.trim().lowercase(java.util.Locale.ROOT)
        require(normalized.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}"))) { "Invalid subtitle language." }
        val proof = store.publish(captions)
        if (original != null) require(verifySource(original)) { "Original subtitle file has changed." }
        PlayerCaptionTrack(Uri.fromFile(proof.file), captions.mimeType, normalized, label.take(120), proof.sha256, proof.bytes,
            original?.sourceUri ?: Uri.fromFile(proof.file), original?.sourceSha256 ?: proof.sha256, original?.sourceBytes ?: proof.bytes)
    }

    suspend fun verify(track: PlayerCaptionTrack): Boolean = withContext(Dispatchers.IO) {
        if (track.uri.scheme != "file" || track.mimeType != "application/x-subrip") return@withContext false
        val path = track.uri.path ?: return@withContext false
        runCatching { store.verify(StoredCaptionFile(File(path), track.sha256, track.bytes)) && verifySource(track) }.getOrDefault(false)
    }

    suspend fun readSource(track: PlayerCaptionTrack): ImportedCaptionFile = withContext(Dispatchers.IO) {
        require(verify(track)) { "Subtitle file has changed; import it again." }
        val path = requireNotNull(track.sourceUri.path)
        val content = store.readVerified(StoredCaptionFile(File(path), track.sourceSha256, track.sourceBytes))
        content.inputStream().use(ImportedCaptionFile::read)
    }

    suspend fun readExport(track: PlayerCaptionTrack): ByteArray = withContext(Dispatchers.IO) {
        require(track.uri.scheme == "file" && track.mimeType == "application/x-subrip" && verifySource(track)) {
            "Subtitle file has changed; import it again."
        }
        store.readVerified(StoredCaptionFile(File(requireNotNull(track.uri.path)), track.sha256, track.bytes))
    }

    private fun verifySource(track: PlayerCaptionTrack): Boolean {
        if (track.sourceUri.scheme != "file") return false
        val path = track.sourceUri.path ?: return false
        return store.verify(StoredCaptionFile(File(path), track.sourceSha256, track.sourceBytes))
    }
}
