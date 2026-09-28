package com.mangalens.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
class MangaLensDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    androidx.media3.exoplayer.R.string.exo_download_notification_channel_name,
    0
) {
    override fun getDownloadManager(): DownloadManager = Holder.manager(this)
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MangaLens media downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val active = downloads.firstOrNull()
        val percent = active?.percentDownloaded?.takeIf { it >= 0f }?.toInt() ?: 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MangaLens downloads")
            .setContentText(active?.request?.id ?: "Media download manager")
            .setProgress(100, percent, active?.percentDownloaded?.let { it < 0f } ?: false)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 10003
        private const val CHANNEL_ID = "mangalens_media_downloads"

        fun addAdaptive(context: Context, id: String, uri: Uri, mime: String?) {
            val request = DownloadRequest.Builder(id, uri)
                .apply { if (!mime.isNullOrBlank()) setMimeType(mime) }
                .build()
            DownloadService.sendAddDownload(context, MangaLensDownloadService::class.java, request, false)
        }

        fun pauseAdaptive(context: Context, id: String) {
            DownloadService.sendSetStopReason(
                context,
                MangaLensDownloadService::class.java,
                id,
                1,
                false
            )
        }

        fun resumeAdaptive(context: Context, id: String) {
            DownloadService.sendSetStopReason(
                context,
                MangaLensDownloadService::class.java,
                id,
                0,
                false
            )
        }

        fun remove(context: Context, id: String) {
            DownloadService.sendRemoveDownload(context, MangaLensDownloadService::class.java, id, false)
        }
    }

    object Holder {
        @Volatile private var manager: DownloadManager? = null
        private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Synchronized
        fun manager(context: Context): DownloadManager {
            manager?.let { return it }
            val app = context.applicationContext
            val directory = File(app.filesDir, "media_download_cache").apply { mkdirs() }
            val provider = StandaloneDatabaseProvider(app)
            val cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
            val dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent("MangaLens/13")
                .setAllowCrossProtocolRedirects(true)
            val created = DownloadManager(
                app,
                provider,
                cache,
                dataSource,
                Executors.newFixedThreadPool(3)
            )
            created.maxParallelDownloads = 3

            val dao = DownloadDatabase.get(app).downloads()
            created.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    callbackScope.launch {
                        val current = dao.get(download.request.id) ?: return@launch
                        val state = when (download.state) {
                            Download.STATE_COMPLETED -> DownloadState.COMPLETED
                            Download.STATE_FAILED -> DownloadState.FAILED
                            Download.STATE_STOPPED -> DownloadState.PAUSED
                            Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
                            else -> DownloadState.QUEUED
                        }
                        dao.upsert(
                            current.copy(
                                bytesDownloaded = download.bytesDownloaded,
                                totalBytes = download.contentLength,
                                state = state,
                                error = finalException?.message
                            )
                        )
                    }
                }
            })
            manager = created
            return created
        }
    }
}
