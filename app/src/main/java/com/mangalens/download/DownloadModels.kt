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
    val stage: String? = null,
    val provider: String = "generic",
    val sourcePageUrl: String? = null,
    val requestedHeight: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val selectedTransport: String? = null
) {
    val progress: Float get() = if (state == DownloadState.COMPLETED) 1f else if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val isAdaptive: Boolean get() = selectedTransport == null && isAdaptiveMediaSource(sourceUrl, mimeType)
    val isVideo: Boolean get() = mimeType.startsWith("video/") || isAdaptive
    val canPreview: Boolean get() = isVideo && bytesDownloaded >= 5L * 1024L * 1024L && (!destination.isNullOrBlank() || isAdaptive)
}

/** Minimal read-only search page; scanRowId is a SQLite cursor, never a playback identity. */
data class DownloadSearchCandidate(val scanRowId: Long, val id: String?, val title: String,
    val mimeType: String, val state: String, val createdAt: Long)

data class DownloadSearchMetadata(val id: String, val title: String, val mimeType: String, val state: String, val createdAt: Long)

@Dao
interface DownloadDao {
    @Query("""SELECT id, substr(title, 1, 768) AS title, mimeType, state, createdAt FROM media_downloads WHERE title LIKE :pattern ESCAPE '\' ORDER BY createdAt DESC LIMIT :limit""")
    suspend fun searchLocalMetadata(pattern: String, limit: Int): List<DownloadSearchMetadata>

    @Query("SELECT rowid AS scanRowId, CASE WHEN length(substr(id, 1, 129)) <= 128 THEN id ELSE NULL END AS id, substr(title, 1, 769) AS title, substr(mimeType, 1, 192) AS mimeType, substr(state, 1, 32) AS state, createdAt FROM media_downloads ORDER BY rowid DESC LIMIT :limit")
    suspend fun firstSearchCandidates(limit: Int): List<DownloadSearchCandidate>
    @Query("SELECT rowid AS scanRowId, CASE WHEN length(substr(id, 1, 129)) <= 128 THEN id ELSE NULL END AS id, substr(title, 1, 769) AS title, substr(mimeType, 1, 192) AS mimeType, substr(state, 1, 32) AS state, createdAt FROM media_downloads WHERE rowid < :beforeRowId ORDER BY rowid DESC LIMIT :limit")
    suspend fun nextSearchCandidates(beforeRowId: Long, limit: Int): List<DownloadSearchCandidate>
    @Query("SELECT id, substr(title, 1, 768) AS title, substr(mimeType, 1, 192) AS mimeType, substr(state, 1, 32) AS state, createdAt FROM media_downloads WHERE id = :id LIMIT 1")
    suspend fun searchMetadataDetail(id: String): DownloadSearchMetadata?
    @Query("SELECT EXISTS(SELECT 1 FROM media_downloads LIMIT 1)")
    suspend fun hasSearchCandidates(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM media_downloads WHERE rowid < :beforeRowId LIMIT 1)")
    suspend fun hasSearchCandidatesBefore(beforeRowId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadEntity)
    @Query("SELECT * FROM media_downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM media_downloads WHERE id = :id LIMIT 1") suspend fun get(id: String): DownloadEntity?
    /** Exact saved destination lookup; two rows expose ambiguity instead of picking a neighbour. */
    @Query("SELECT * FROM media_downloads WHERE destination = :uri LIMIT 2")
    suspend fun atDestination(uri: String): List<DownloadEntity>
    @Query("UPDATE media_downloads SET state = :state, error = :message WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED', 'PAUSED')")
    suspend fun stopIfActive(id: String, state: DownloadState, message: String?): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total, state = 'DOWNLOADING', error = NULL WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED')")
    suspend fun progressIfActive(id: String, done: Long, total: Long): Int
    @Query("UPDATE media_downloads SET destination = :uri, bytesDownloaded = :size, totalBytes = :size, mimeType = COALESCE(:mime, mimeType), state = 'COMPLETED', error = NULL WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED')")
    suspend fun completeIfActive(id: String, uri: String, size: Long, mime: String? = null): Int
    @Query("UPDATE media_downloads SET state = 'FAILED', error = :message WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun failIfActive(id: String, message: String): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total, state = :state, error = :message WHERE id = :id AND selectedTransport IS NULL AND state != 'CANCELLED' AND (:state != 'PAUSED' OR state = 'PAUSED') AND (state NOT IN ('PAUSED', 'COMPLETED', 'FAILED') OR state = :state)")
    suspend fun adaptiveStateIfActive(id: String, done: Long, total: Long, state: DownloadState, message: String?): Int
    @Query("UPDATE media_downloads SET bytesDownloaded = :done, totalBytes = :total WHERE id = :id AND selectedTransport IS NULL AND state IN ('QUEUED', 'DOWNLOADING') AND bytesDownloaded <= :done")
    suspend fun adaptiveProgressIfActive(id: String, done: Long, total: Long): Int
    @Query("UPDATE media_downloads SET state = :state, error = NULL WHERE id = :id AND state IN ('PAUSED', 'FAILED')")
    suspend fun resumeIfStopped(id: String, state: DownloadState): Int
    @Query("UPDATE media_downloads SET sourceUrl = :url, mimeType = :mime, title = :title, provider = :provider, sourcePageUrl = :pageUrl, requestedHeight = :requestedHeight, selectedTransport = :selectedTransport, error = NULL, stage = :stage WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING', 'FAILED', 'PAUSED')")
    suspend fun refreshSource(id: String, url: String, mime: String, title: String, provider: String, pageUrl: String?, requestedHeight: Int?, stage: String, selectedTransport: String? = null): Int
    @Query("UPDATE media_downloads SET stage = :stage WHERE id = :id AND state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun stageIfActive(id: String, stage: String): Int
    @Query("UPDATE media_downloads SET actualHeight = :height, stage = :stage WHERE id = :id AND state = 'COMPLETED'")
    suspend fun completedDetails(id: String, height: Int?, stage: String): Int
    @Query("""UPDATE media_downloads SET state = 'CANCELLED', stage = 'Partial cleanup requested', error = 'Stopped for partial cleanup'
        WHERE id = :id AND state = 'FAILED' AND destination IS NULL AND sourceUrl = :url AND createdAt = :createdAt
        AND bytesDownloaded = :done AND totalBytes = :total AND mimeType = :mime AND title = :title
        AND stage IS :stage AND error IS :error AND provider = :provider AND sourcePageUrl IS :page AND requestedHeight IS :height AND selectedTransport IS :selectedTransport""")
    suspend fun claimFailedPartials(id: String, url: String, createdAt: Long, done: Long, total: Long, mime: String,
        title: String, stage: String?, error: String?, provider: String, page: String?, height: Int?, selectedTransport: String? = null): Int
    @Query("""UPDATE media_downloads SET bytesDownloaded = 0, totalBytes = -1, stage = 'Partial files removed', error = NULL
        WHERE id = :id AND state = 'CANCELLED' AND stage = 'Partial cleanup requested' AND destination IS NULL
        AND sourceUrl = :url AND createdAt = :createdAt AND bytesDownloaded = :done AND totalBytes = :total""")
    suspend fun finishFailedPartials(id: String, url: String, createdAt: Long, done: Long, total: Long): Int
    @Query("DELETE FROM media_downloads WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [DownloadEntity::class], version = 4, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    companion object {
        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_downloads ADD COLUMN selectedTransport TEXT DEFAULT NULL")
            }
        }
        @Volatile private var instance: DownloadDatabase? = null
        fun get(context: android.content.Context): DownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "mangalens_downloads.db")
                    .addMigrations(
                        object : androidx.room.migration.Migration(1, 2) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("ALTER TABLE media_downloads ADD COLUMN actualHeight INTEGER")
                                db.execSQL("ALTER TABLE media_downloads ADD COLUMN stage TEXT")
                            }
                        },
                        object : androidx.room.migration.Migration(2, 3) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("ALTER TABLE media_downloads ADD COLUMN provider TEXT NOT NULL DEFAULT 'generic'")
                                db.execSQL("ALTER TABLE media_downloads ADD COLUMN sourcePageUrl TEXT")
                                db.execSQL("ALTER TABLE media_downloads ADD COLUMN requestedHeight INTEGER")
                            }
                        },
                        MIGRATION_3_4
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
