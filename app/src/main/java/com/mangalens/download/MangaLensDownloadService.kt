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
import androidx.media3.datasource.DataSource
import java.util.concurrent.Executor
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        Holder.persistProgress(this, downloads)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MangaLens media downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val active = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING } ?: downloads.firstOrNull()
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
        @Volatile private var downloadCache: SimpleCache? = null
        @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null
        private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val callbackMutex = Mutex()

        // DownloadService invokes this with live progress at its notification interval.
        // Byte-only writes cannot undo pause/cancel/failure/completion or move progress backwards.
        internal fun persistProgress(context: Context, downloads: List<Download>) = callbackScope.launch {
            callbackMutex.withLock {
                val dao = DownloadDatabase.get(context).downloads()
                for (download in downloads) if (download.state == Download.STATE_DOWNLOADING) {
                    dao.adaptiveProgressIfActive(download.request.id, download.bytesDownloaded, download.contentLength)
                }
            }
        }

        private suspend fun persistState(dao: DownloadDao, download: Download, message: String?) {
            val state = when (download.state) {
                Download.STATE_COMPLETED -> DownloadState.COMPLETED
                Download.STATE_FAILED -> DownloadState.FAILED
                Download.STATE_STOPPED -> DownloadState.PAUSED
                Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
                else -> DownloadState.QUEUED
            }
            val total = if (state == DownloadState.COMPLETED && download.contentLength < 0) download.bytesDownloaded else download.contentLength
            dao.adaptiveStateIfActive(download.request.id, download.bytesDownloaded, total, state, message)
        }

        @Synchronized
        private fun storage(context: Context): Pair<StandaloneDatabaseProvider, SimpleCache> {
            val app = context.applicationContext
            val provider = databaseProvider ?: StandaloneDatabaseProvider(app).also { databaseProvider = it }
            val cache = downloadCache ?: run {
                val directory = File(app.filesDir, "media_download_cache").apply { mkdirs() }
                // Explicit user downloads are durable offline data, not an evictable streaming cache.
                SimpleCache(directory, NoOpCacheEvictor(), provider).also { downloadCache = it }
            }
            return provider to cache
        }

        @Synchronized
        fun cache(context: Context): SimpleCache = storage(context).second

        @Synchronized
        fun manager(context: Context): DownloadManager {
            manager?.let { return it }
            val app = context.applicationContext
            val dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent("MangaLens/13")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(60_000)
            return createManager(app, dataSource, Executors.newSingleThreadExecutor()).also { manager = it }
        }

        // Shared construction lets instrumentation exercise real Media3 callbacks with a
        // fixture-only HTTPS client. Production always uses the system-trusted factory above.
        internal fun createManager(context: Context, dataSource: DataSource.Factory, executor: Executor): DownloadManager {
            val app = context.applicationContext
            val (provider, cache) = storage(app)
            val created = DownloadManager(
                app,
                provider,
                cache,
                dataSource,
                executor
            )
            created.maxParallelDownloads = 1

            val dao = DownloadDatabase.get(app).downloads()
            created.addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) {
                    // Completed entries are absent from currentDownloads. Restore completion
                    // and byte checkpoints without replaying stale queued/failed state over a retry.
                    callbackScope.launch(start = CoroutineStart.UNDISPATCHED) { callbackMutex.withLock {
                        withContext(Dispatchers.IO) { downloadManager.downloadIndex.getDownloads().use { cursor ->
                            while (cursor.moveToNext()) {
                                val download = cursor.download
                                if (download.state == Download.STATE_COMPLETED || download.state == Download.STATE_STOPPED) {
                                    persistState(dao, download, null)
                                } else if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED) {
                                    dao.adaptiveProgressIfActive(download.request.id, download.bytesDownloaded, download.contentLength)
                                }
                            }
                        } }
                    } }
                }
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    // Enter the fair mutex in listener order before dispatching suspended Room work.
                    callbackScope.launch(start = CoroutineStart.UNDISPATCHED) { callbackMutex.withLock {
                        persistState(dao, download, finalException?.message)
                    } }
                }
            })
            return created
        }
    }
}
