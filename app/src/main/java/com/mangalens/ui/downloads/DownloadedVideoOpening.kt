package com.mangalens.ui.downloads

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.mangalens.download.DownloadDatabase
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** Reads only the persisted output, with an owned cancellable probe and a fresh post-read Room check. */
internal object DownloadedVideoOpening {
    // Leaves time for the existing production-route8s oracle; it is not a playback/decode budget.
    private const val OPEN_TIMEOUT_MS = 5_000L
    // Object lifetime survives every Activity, destination and ViewModel recreation in this process.
    private val probe = OwnedSavedVideoProbe(reportCleanupFailure = { failure ->
        Log.w("DownloadedVideo", "saved_file_cleanup_failure phase=${failure.phase.name} type=${failure.exceptionType}")
    })

    suspend fun prepare(context: Context, downloadId: String): SavedDownloadedVideo = withTimeout(OPEN_TIMEOUT_MS) {
        val application = context.applicationContext
        val signal = CancellationSignal()
        // Capture, getType, query, open, first byte, and fresh Room confirmation all run in
        // the same independently retained producer. Timeout cannot join a blocked provider.
        probe.run(cancelProvider = signal::cancel) { owner ->
            try {
                val dao = DownloadDatabase.get(application).downloads()
                val saved = DownloadedVideoPlaybackPolicy.capture(dao.get(downloadId))
                owner.checkActive()
                val uri = Uri.parse(saved.uri)
                val ownership = ownership(application, uri, signal)
                owner.checkActive()
                if (ownership == SavedVideoReadOwnership.NONE)
                    throw SavedVideoUnavailableException(SavedVideoUnavailableReason.ACCESS_EXPIRED)
                val mime = if (uri.scheme == "file") {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(requireNotNull(uri.path)).extension.lowercase(Locale.ROOT))
                } else application.contentResolver.getType(uri)
                owner.checkActive()
                val descriptor = application.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw SavedVideoUnavailableException(SavedVideoUnavailableReason.UNREADABLE_FILE)
                val asset = OwnedSavedVideoAssetHandle(descriptor, descriptor::createInputStream, owner::checkActive)
                owner.own(asset) // A descriptor delivered after cancellation is closed before its first read.
                owner.checkActive()
                val bytes = descriptor.length
                val nonempty = asset.readFirstByte()
                owner.checkActive()
                val evidence = SavedVideoReadEvidence(mime, ownership, bytes, nonempty)
                owner.closeReadHandle()
                val confirmed = DownloadedVideoPlaybackPolicy.confirm(saved, dao.get(downloadId), evidence)
                owner.checkActive()
                confirmed
            } catch (missing: java.io.FileNotFoundException) {
                throw SavedVideoUnavailableException(SavedVideoUnavailableReason.MISSING_FILE)
            } catch (expired: SecurityException) {
                throw SavedVideoUnavailableException(SavedVideoUnavailableReason.ACCESS_EXPIRED)
            }
        }
    }

    /** Recent local metadata shares the same single application-owned producer and full deadline. */
    suspend fun prepareRecentLocal(context: Context, uriText: String): Uri = withTimeout(OPEN_TIMEOUT_MS) {
        com.mangalens.ui.video.RecentVideoSource.local(uriText)
            ?: throw java.io.IOException("This saved video address is invalid. Select the video again.")
        val application = context.applicationContext
        val signal = CancellationSignal()
        probe.run(cancelProvider = signal::cancel) { owner ->
            try {
                val dao = DownloadDatabase.get(application).downloads()
                val matches = dao.atDestination(uriText)
                owner.checkActive()
                if (matches.size > 1) throw java.io.IOException("Several downloads use this saved file. Open its exact row in Downloads.")
                val saved = matches.singleOrNull()?.let(DownloadedVideoPlaybackPolicy::capture)
                val uri = Uri.parse(uriText)
                if (saved == null && (uri.scheme == "file" || uri.authority == "${application.packageName}.downloads"))
                    throw java.io.IOException("This download is no longer recorded as complete. Open Downloads or select the video again.")
                val access = ownership(application, uri, signal).takeUnless { it == SavedVideoReadOwnership.NONE }
                    ?: if (saved == null && hasMediaVideoRead(application, uri)) SavedVideoReadOwnership.RUNTIME_MEDIA_READ
                    else SavedVideoReadOwnership.NONE
                if (access == SavedVideoReadOwnership.NONE)
                    throw SavedVideoUnavailableException(SavedVideoUnavailableReason.ACCESS_EXPIRED)
                owner.checkActive()
                val mime = if (uri.scheme == "file") {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(requireNotNull(uri.path)).extension.lowercase(Locale.ROOT))
                } else application.contentResolver.getType(uri)
                owner.checkActive()
                if (!mime.orEmpty().lowercase(Locale.ROOT).startsWith("video/"))
                    throw java.io.IOException("The selected saved file is no longer a video. Select the video again.")
                val descriptor = application.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw SavedVideoUnavailableException(SavedVideoUnavailableReason.UNREADABLE_FILE)
                val asset = OwnedSavedVideoAssetHandle(descriptor, descriptor::createInputStream, owner::checkActive)
                owner.own(asset)
                owner.checkActive()
                val bytes = descriptor.length
                val nonempty = asset.readFirstByte()
                owner.closeReadHandle()
                if (saved != null) DownloadedVideoPlaybackPolicy.confirm(saved, dao.get(saved.downloadId),
                    SavedVideoReadEvidence(mime, access, bytes, nonempty))
                else if (!nonempty || bytes == 0L) throw SavedVideoUnavailableException(SavedVideoUnavailableReason.UNREADABLE_FILE)
                owner.checkActive()
                uri
            } catch (_: java.io.FileNotFoundException) {
                throw SavedVideoUnavailableException(SavedVideoUnavailableReason.MISSING_FILE)
            } catch (_: SecurityException) {
                throw SavedVideoUnavailableException(SavedVideoUnavailableReason.ACCESS_EXPIRED)
            }
        }
    }

    private fun hasMediaVideoRead(context: Context, uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY ||
            uri.path?.matches(Regex("/[a-z0-9_]+/video/media/[0-9]+")) != true) return false
        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ownership(context: Context, uri: Uri, signal: CancellationSignal): SavedVideoReadOwnership {
        if (uri.scheme == "file") {
            val roots = listOfNotNull(File(context.filesDir, "downloads"), context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
            return if (DownloadedVideoPlaybackPolicy.isManagedFile(File(requireNotNull(uri.path)), roots))
                SavedVideoReadOwnership.APP_OWNED else SavedVideoReadOwnership.NONE
        }
        val authority = uri.authority
        if (authority == "${context.packageName}.downloads" &&
            context.packageManager.resolveContentProvider(requireNotNull(authority), 0)?.packageName == context.packageName)
            return SavedVideoReadOwnership.APP_OWNED
        signal.throwIfCanceled()
        if (Build.VERSION.SDK_INT >= 29 && authority == MediaStore.AUTHORITY) {
            val appOwned = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null, signal)?.use {
                    it.moveToFirst() && it.getString(0) == context.packageName
                } == true
            signal.throwIfCanceled()
            if (appOwned) return SavedVideoReadOwnership.APP_OWNED
        }
        return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            SavedVideoReadOwnership.PERSISTED_READ else SavedVideoReadOwnership.NONE
    }
}
