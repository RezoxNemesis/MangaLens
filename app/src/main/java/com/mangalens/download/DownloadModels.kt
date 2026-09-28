package com.mangalens.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

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
    val createdAt: Long = System.currentTimeMillis()
) {
    val progress: Float get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val canPreview: Boolean get() = isVideo && bytesDownloaded >= 5L * 1024L * 1024L && !destination.isNullOrBlank()
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadEntity)
    @Query("SELECT * FROM media_downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM media_downloads WHERE id = :id LIMIT 1") suspend fun get(id: String): DownloadEntity?
    @Query("DELETE FROM media_downloads WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    companion object {
        @Volatile private var instance: DownloadDatabase? = null
        fun get(context: android.content.Context): DownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "mangalens_downloads.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}