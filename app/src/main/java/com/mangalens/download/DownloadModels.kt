package com.mangalens.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

internal fun isAdaptiveMediaSource(sourceUrl: String, mimeType: String? = null): Boolean {
    val clean = sourceUrl.substringBefore('?').lowercase()
    val mime = mimeType.orEmpty().lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mpd") ||
        clean.contains("/manifest/") ||
        mime == "application/x-mpegurl" ||
        mime == "application/vnd.apple.mpegurl" ||
        mime == "application/dash+xml"
}

@Entity(tableName = "media_downloads", indices = [Index("state"), Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val title: String,
    val mimeType: String,
    val destination: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val actualHeight: Int? = null,
    val stage: String? = null
) {
    val progress: Float get() = if (state == DownloadState.COMPLETED) 1f else if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val isAdaptive: Boolean get() = isAdaptiveMediaSource(sourceUrl, mimeType)
    val isVideo: Boolean get() = mimeType.startsWith("video/") || isAdaptive
    val canPreview: Boolean get() = isVideo && bytesDownloaded >= 5L * 1024L * 1024L && (!destination.isNullOrBlank() || isAdaptive)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadEntity)
    @Query("SELECT * FROM media_downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM media_downloads WHERE id = :id LIMIT 1") suspend fun get(id: String): DownloadEntity?
    @Query("UPDATE media_downloads SET state = :state, error = :message WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED', 'PAUSED')")
    suspend fun stopIfActive(id: String, state: DownloadState, message: String?): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total, state = 'DOWNLOADING', error = NULL WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED')")
    suspend fun progressIfActive(id: String, done: Long, total: Long): Int
    @Query("UPDATE media_downloads SET destination = :uri, bytesDownloaded = :size, totalBytes = :size, state = 'COMPLETED', error = NULL WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED')")
    suspend fun completeIfActive(id: String, uri: String, size: Long): Int
    @Query("UPDATE media_downloads SET state = 'FAILED', error = :message WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun failIfActive(id: String, message: String): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total, state = :state, error = :message WHERE id = :id AND state != 'CANCELLED' AND (:state != 'PAUSED' OR state = 'PAUSED') AND (state NOT IN ('PAUSED', 'COMPLETED', 'FAILED') OR state = :state)")
    suspend fun adaptiveStateIfActive(id: String, done: Long, total: Long, state: DownloadState, message: String?): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING') AND bytesDownloaded <= :done")
    suspend fun adaptiveProgressIfActive(id: String, done: Long, total: Long): Int
    @Query("UPDATE media_downloads SET state = :state, error = NULL WHERE id = :id AND state IN ('PAUSED', 'FAILED')")
    suspend fun resumeIfStopped(id: String, state: DownloadState): Int
    @Query("UPDATE media_downloads SET sourceUrl = :url, mimeType = :mime WHERE id = :id AND state = 'FAILED'")
    suspend fun refreshFailedSource(id: String, url: String, mime: String): Int
    @Query("UPDATE media_downloads SET stage = :stage WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun stageIfActive(id: String, stage: String): Int
    @Query("UPDATE media_downloads SET actualHeight = :height, stage = :stage WHERE id = :id AND state = 'COMPLETED'")
    suspend fun completedDetails(id: String, height: Int?, stage: String): Int
    @Query("DELETE FROM media_downloads WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    companion object {
        @Volatile private var instance: DownloadDatabase? = null
        fun get(context: android.content.Context): DownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "mangalens_downloads.db")
                    .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("ALTER TABLE media_downloads ADD COLUMN actualHeight INTEGER")
                            db.execSQL("ALTER TABLE media_downloads ADD COLUMN stage TEXT")
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
